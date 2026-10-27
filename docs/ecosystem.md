# The road-event ecosystem

**One layout, two apps**

Status: **implemented in 0.4.0** (2026-10-26). Roadbeacon now writes the same event layout as the
independent [Roadstr](https://github.com/roadstrapp/roadstr-app) client, and a new frame carries it
over MeshCore. This document explains why, what it cost, what is still different, and what to ask
the other developer for.

---

## 0. Summary

There are two Android apps in this space:

| Listing | App | Code |
|---|---|---|
| [`app.roadbeacon`](https://zapstore.dev/apps/app.roadbeacon) | this project — OsmAnd plugin + web viewer | Kotlin, 26 source files, no navigation |
| [`app.roadstr`](https://zapstore.dev/apps/app.roadstr) | `roadstrapp/roadstr-app` — full navigation app | Kotlin (was Flutter), ≈1,400 JVM tests at 1.1 |

Until 0.4.0 they used two different tag layouts for the same kinds, and we proposed a v2 of ours.
That was the wrong direction: **we adopted theirs instead**, and dropped ours completely.

Why this is the right move now, and only now:

- No users, no installed base, no published release in our layout. Migration cost is a rewrite, not
  a politics problem.
- Their layout has no `alt`, so the brand-in-signed-content problem disappears without a v2.
- Their vote is two tags (`e`, `status`), which makes the 134-byte confirmation frame
  **self-contained** — the one real flaw of our format was ours, not the frame's.
- Their category set is a superset of ours, and their layout already carries `maxspeed`.
- One layout means no twin events, no merge heuristic, no provenance adapter, and half the codec.

What is left is not bytes but policy: relay retention, vote extension, and a shared category table.
Section 7 lists the concrete asks.

---

## 1. The other app, in one page

`roadstrapp/roadstr-app`, **MIT**, Android-only, package `app.roadstr`.

* Turn-by-turn navigation (MapLibre, OSRM/GraphHopper/OpenRouteService, offline Valhalla), screen-off
  guidance in a foreground service, on-device voice, public transport, OSM speed limits and ZTL
  warnings, zaps, Amber/NIP-46 sign-in with no private key in the app.
* 27 languages, encrypted favourites sync, Explore, achievements.
* Flutter up to 0.5.x; a native Kotlin rewrite from 1.0 that installs over it with the same package
  name and signing key; current release 1.2.3.
* Its road events are kinds 1315/1316 with the layout now specified in
  [`nips/road-events.md`](../nips/road-events.md), plus 1317 (owner corrections) and 1318 (edit
  requests) for speed limits.
* Its interoperability work (`docs/interoperability/MESHCORE_NOSTR.md`) audited the *old* layout,
  built a strict codec with 71 conformance vectors, and implemented a MeshCore bridge — tested
  against a simulated radio, **not yet on hardware**.

It is not a fork, not affiliated, and it has the larger feature surface and user base. The sensible
division is in §8.

---

## 2. What the joint review found

Their document is, uncomfortably, the most careful audit the old format ever had. Every point we
re-checked was correct:

| Finding | Status |
|---|---|
| The old NIP's example geohashes (`u2ed*`) were wrong; 48.8566140, 2.3522219 is `u09t/u09tv/u09tvw` | fixed, then made moot by the layout change |
| `(lat * 1e7).toLong()` truncated; ~6.2 % of coordinates landed one unit short (≈12 % of reports unverifiable) | fixed in 0.4.0, then made moot (six decimals, integer parse) |
| `"%.7f".format()` followed the device locale; 3 of 20 real relay reports had a decimal comma | fixed the same way |
| `SPECIFICATION.md` said 109 bytes in a heading, called flag bit 2 "privacy mode", and had a wrong worked example | corrected |
| The old codec test used fake keys and signatures, so `event → frame → event` had never been shown with a real signature | replaced by a frame+ID vector computed by an independent implementation |
| Read-only probe: 138 real events, 103 in their layout, 20 in ours, 15 votes, all signatures valid | not repeated here; the classification matches both parsers |

Their two implementations of the *old* layout (event reader + frame codec) are now historical: our
clients no longer write it, and the new frame over their layout is defined in the NIP.

---

## 3. Compatibility today

**One event layout.** A report written by either app is read by the other with no adapter, no
provenance hack and no twin event. A vote is two tags everywhere.

What still differs is client policy, not bytes:

| Topic | Roadbeacon (ours) | Roadstr (theirs) |
|---|---|---|
| `expiration` on new reports | `created_at + max(14 d, lifetime)` | `created_at + lifetime` |
| Votes extend a report | yes | no (native reports die at `created_at + lifetime`) |
| Category lifetimes | their table (adopted verbatim) | their table |
| Frame over this layout | specified + implemented + tested | still implements the old 110-byte frame |
| `maxspeed` UI | not yet (codec and reader support it) | yes, with 1317/1318 corrections |
| Platform | OsmAnd plugin + browser | standalone navigator |

The first two rows are the only ones that make the same event behave differently in the two apps.
They are behaviour, not wire format, and both are cheap for them to change (§7).

---

## 4. Location keying

Unchanged, and correct for relays:

* Reports carry `g` at precision 4, 5, 6 (≈20 km, 5 km, 1.2 km), derived from the six-decimal
  position; clients query by `#g` with the centre cell and its eight neighbours.
* **Votes carry no position.** They are fetched by `#e` with the IDs of the reports in view — which
  is what both apps already did. A vote without coordinates cannot be area-queried, and that is the
  trade that makes its frame self-contained.
* The precise position is only in `lat`/`lon` (six decimals ≈ 11 cm). The frame stores the same
  value as int32 × 10⁶; nothing is lost.

Improvements considered and rejected:

* **Precision 7.** Adds bytes for 1 cm of nothing; GPS is not that good and no filter uses it.
* **A geohash inside the frame.** The coordinates are already there; a second copy could only
  disagree.
* **Coordinates in votes.** They would let a vote be area-queried, but cost 8 bytes and reintroduce
  the lookup the two-tag vote removes. Not worth it.

---

## 5. One packet: the frame we now define

### 5.1 Sizes

| Object | Bytes | Sent by radio? |
|---|---|---|
| Report, no comment | 117 | yes |
| Report, 46-byte comment | 163 | yes: the largest frame an encoder makes |
| Report, 47–48-byte comment | 164–165 | decoded if heard, never encoded (BLE/local API carry 163) |
| Report, longer comment or control characters | — | no: relay-only, never truncated |
| Vote | 134 | yes, self-contained |

The report frame carries key, time, lat/lon (×10⁶), category, `expiration`, `maxspeed` (uint16,
0 = absent), comment length + UTF-8 bytes, and the signature. The vote carries key, time, report ID,
status and signature; nothing needs to be looked up.

46 bytes of comment is ≈46 Latin characters or ≈15 CJK characters — enough for "police checking
seatbelts". The radio carries 165, but a BLE companion link and MeshCore Open's local API carry 163,
so encoders stop at 46 bytes; decoders still accept 48. Two-packet comments were considered and rejected: partial events and reassembly state
are not worth it for a comment that long. If field data ever says otherwise, that is a new frame
version, not a silent change.

### 5.2 Can a report from the *old* layout be framed?

No, and that is why old events are not bridged. Rebuilding a frame produces the canonical tags of
*this* layout; feeding it an event from the old layout gives a different ID and an invalid signature.
Only the author's signer could have produced a second event, and we no longer do that: the old layout
is deprecated and neither client writes it.

There is no compatibility adapter. Our reader is tolerant — it looks tags up by name — so an old
event may still appear until it expires (14 days at most), with current lifetimes and no provenance.
That is not a supported path and it will disappear on its own.

### 5.3 Airtime

At SF9/125 kHz a 117–163-byte report is roughly 0.6–0.9 s on air, a 134-byte vote about 0.7 s,
before MeshCore's own header. Enough that rate limits and de-duplication are protocol requirements,
not tuning. Measure on real hardware before promising numbers; nobody has yet.

---

## 6. What changed in 0.4.0

The app was renamed **Roadbeacon** (`app.roadbeacon`) to end the two-listings-one-name confusion.
The protocol move is the larger part:

| Change | Where |
|---|---|
| Writer: shared layout, six decimals, no `alt`, `maxspeed` support, `expiration = max(14 d, lifetime)` | `EventSigner.kt` |
| Vote: two tags, no position, no expiration | `EventSigner.kt` |
| Frame: 117–163-byte reports, self-contained 134-byte votes, strict refusal | `BinaryCodec.kt` |
| Coordinates: integer E6 parse/format, locale-free, no truncation | `CanonicalCoordinates.kt` |
| Category table: their 15 categories, values 0–13, 255 | `EventType.kt` |
| Lifetime: display expiry capped at the event's `expiration` | `EventCache.kt` |
| Relay query window covers the longest lifetime (30 d) | `RoadbeaconService.kt` |
| Web client: same layout, six decimals, two-tag votes, expiry cap, signature checks | `roadbeacon-web/js/` |
| Old layout: **dropped** — no adapter, no special handling; a tolerant reader may still display old events until they expire, with current lifetimes and no provenance | — |
| NIP rewritten around the shared layout; old layout in Appendix A as deprecated | `nips/road-events.md` |

Not done yet: the MeshCore transport itself (BLE companion client, queues, bridge). The codec is
ready; the transport is still the largest piece and needs hardware.

---

## 7. What is still different — and the asks

1. **Relay retention.** They write `created_at + lifetime`; we write `created_at + max(14 d,
   lifetime)`. Without the floor, a "still there" at hour five cannot extend a four-hour police
   report because relays already dropped it. One-line change in their writer; their reader already
   accepts any `expiration` value, and older clients are unaffected (they cap display at the
   lifetime anyway).
2. **Vote extension.** Their native reports use a fixed lifetime and ignore votes for expiry; the
   extend/cut function exists in their codebase (`InteropLifetime.effectiveExpiry`) and is applied
   only to the old layout. Applying it to their own reports is a call-site change. Without it the
   same event lives longer in our app than in theirs once it has votes.
3. **Frame over the new layout.** Their `CompactRoadCodec` still builds the old 110-byte frame; it
   needs the new offsets (117 + comment, expiration, maxspeed) and a two-tag vote rebuild. The
   vectors they published describe the old frame and are obsolete.
4. **Conformance vectors.** We pin one frame + event ID computed independently. Ideally one shared
   file covers both implementations, regenerated for the new frame (comment and maxspeed boundaries,
   negative and extreme coordinates, every category).
5. **Types 12/13.** `police_station` and `surveillance_camera` are reportable in their app but could
   not go on the radio at all. They now have registry values.
6. **MeshCore parameters.** Keep `#roadstr` (key
   `b7f4317db7b03aeb563b2c4bdc476d6a`) and `data_type` `0xFF52` until a real radio is validated,
   then register one stable value with both apps named in the PR.
7. **Corrections.** Kinds 1317/1318 remain theirs; we do not implement them yet. They should be
   documented in the NIP when we do.

None of these requires a change to a single tag name. That is the point of migrating first.

---

## 8. Division of work

| Area | Owner | Why |
|---|---|---|
| Navigation, routing, voice, transit | them | years of work, not our differentiator |
| Mobile UX for drivers | them | same |
| OsmAnd overlay and AIDL | us | they render their own map; different audience |
| Browser client | us | nobody else has one |
| Specification, category registry, conformance | both | we wrote it, they hardened the old one |
| Strict codec, second implementation | us | two implementations make a format real |
| MeshCore bridge | both, independently | same bytes, two clients |

We do not build navigation. They do not build an OsmAnd plugin. Everything else is shared.

### Roadmap

| Phase | Version | Content | Gate |
|---|---|---|---|
| 0 | 0.4.0 | rename, layout migration, new frame, docs | this pass |
| 1 | 0.5 | read/write `maxspeed` and correction kinds (1317/1318) | camera limits editable from our app |
| 2 | 0.6 | MeshCore transport: BLE companion client, receive, send, bridge with limits | two radios, one report, same event ID |
| 3 | 1.0 | shared vector file green in both apps, registered `data_type` | both clients reject the same frames |
| 4 | — | decision gate (§10) | see §10 |

Phase 2 can reuse their MIT Kotlin code as a reference and port; the BLE layer needs a device, not
more code.

---

## 9. Cross-linking

Proposed text, already in our README:

> #### The other Roadstr
>
> [Roadstr](https://github.com/roadstrapp/roadstr-app) ([Zapstore](https://zapstore.dev/apps/app.roadstr))
> is an independent navigation app for Android by a different author. It is not affiliated with
> Roadbeacon, has its own code and roadmap, and the two apps are not interchangeable. They do share
> the road-event format (Nostr kinds 1315/1316): reports and votes written by one are read by the
> other. We like it and we recommend it if you want turn-by-turn navigation; Roadbeacon is for
> people who want road events inside OsmAnd and in a browser.

Ask them for the same paragraph pointing here. Keep their name out of our icon, listing title and
package id; the README note is enough to prevent confusion in search results.

---

## 10. Best of all worlds — and when to stop

**A. Two apps, one format (current).** They own navigation, we own OsmAnd + web + mesh. Cost: two
codebases; benefit: independence, and two implementations that keep the format honest.

**B. Contribute and retire (fallback).** If our app has no users after the interop releases, fold
what only we have (OsmAnd overlay, browser client, strict codec) into their project or keep them as
small satellites, and retire the standalone app. The format survives; the duplicate client does not.
Trigger: no organic installs and no OsmAnd users ~3 months after phase 3.

**C. Merge into one app (unlikely).** Their app gains an OsmAnd mode. Offer our `osmand/` package as
a library if they ask; do not push.

Writing the trigger down is what stops a second app being maintained out of inertia.

---

## 11. Things we deliberately will not do

* Write the old layout again, or read it "for compatibility": it is dead, and the frame cannot carry
  it.
* Publish two events for one report (no twins, no merge heuristics).
* Put a brand or a geohash in an event or a frame.
* Truncate a comment to fit the radio: it changes the ID, or the text, or both.
* Truncate signatures or drop fields to save airtime.
* Merge reports from different authors that are close together: two drivers reporting the same police
  car are two voices.
* Claim MeshCore support before a real radio and firmware have been tested.

## 12. Risks

| Risk | Mitigation |
|---|---|
| Their app never switches to the new frame | our codec is a complete second implementation; the spec and vectors are the contract |
| Their app never adopts `expiration = max(14 d, life)` or vote extension | same events, different lifetimes; documented, visible, and fixable without a wire change |
| Their layout changes again | the NIP freezes the layout and the category table; changes need a version and vectors |
| Public hashtag channel is open to anyone | verify signatures; bound rates and queues; treat radio input as untrusted |
| Airtime/duty cycle unproven | keep the radio opt-in and rate-limited; measure first |
| Our app becomes a duplicate client nobody needs | explicit decision gate (§10) |

---

## Appendix A — sources

Roadstr (MIT), read at `9cb5352` (2026-10-25, version 1.2.3):

* `docs/interoperability/MESHCORE_NOSTR.md` — interop spec, findings, old frame, bridge, hardware
  checklist.
* `docs/interoperability/vectors/roadstr-nostr-meshcore-v1.json` — 21/33/12/1/5 vectors for the old
  frame.
* `tools/interop/generate_vectors.py` — independent generator.
* `android/.../core/protocol/nostr/NostrProtocol.kt` — the layout, categories and lifetimes.
* `android/.../core/protocol/interop/` — old layout codec, coordinates, lifetime rules.
* `android/.../service/meshcore/` — companion protocol client and bridge.

MeshCore upstream: `docs/companion_protocol.md` (`0x3E` channel data, 165-byte radio limit,
`data_type` ranges, hashtag channel keys), `docs/number_allocations.md`.

This repository: `nips/road-events.md`, `docs/ecosystem.md`,
`roadbeacon-android/.../nostr/{CanonicalCoordinates,BinaryCodec,EventSigner,RoadEventFormat}.kt`,
`roadbeacon-web/js/roadbeacon.js`.

## Appendix B — numbers

| Number | Meaning |
|---|---|
| 117 / 163 | report frame without / with a 46-byte comment (largest encoded) |
| 165 | largest frame decoded (48-byte comment); the radio's own payload limit |
| 134 | vote frame, self-contained |
| 46 | comment limit in UTF-8 bytes when encoding |
| 163 | largest channel datagram over BLE / MeshCore Open's local API |
| 14 d / 30 d | relay retention floor / long-lived category lifetime |
| 0xFF52 | `data_type` until registration |
| `b7f4317db7b03aeb563b2c4bdc476d6a` | channel key of `#roadstr` |
| 6 decimals | coordinate precision (≈11 cm) |
