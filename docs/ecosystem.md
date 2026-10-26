# The road-event ecosystem

**Two apps, one event format, and what to do next**

Status: **proposal**, 2026-10-26. Written after reading `roadstrapp/roadstr-app` at
`main` (`9cb5352`, 2026-10-25, version 1.2.3), its interoperability specification and
test vectors, and comparing them with this repository's clients.

---

## 0. Summary

There are two Android apps named **Roadstr** on Zapstore:

| Listing | App | Code |
|---|---|---|
| [`com.roadstr`](https://zapstore.dev/apps/com.roadstr) | this project — OsmAnd plugin + web viewer | Kotlin, 26 source files, no navigation |
| [`app.roadstr`](https://zapstore.dev/apps/app.roadstr) | `roadstrapp/roadstr-app` — a full navigation app | Kotlin (was Flutter), ≈1,400 JVM tests at 1.1 |

Both publish the same **kind 1315/1316 road events**, and the other app implements *our*
event layout on purpose: its interoperability document names `jooray/roadstr` as the
normative source and its radio bridge refuses anything that does not rebuild byte for byte.

This document proposes:

1. **Rename this app to Roadbeacon** (`app.roadbeacon`) so two listings cannot be confused.
   The signed-event format keeps its v1 text (`alt = "Roadstr: …"`) because that text is part
   of the format, not of the app.
2. **Keep the event format, fix our encoder.** Our client demonstrably produces events that
   nobody can put on a radio (6 % of coordinates, comma on some phones). We fixed that.
3. **Add a v2 frame** — brand-neutral `alt`, self-contained confirmations — as a coordinated
   change, not a unilateral one.
4. **Make their test vectors the contract** between implementations, and agree on one
   MeshCore `data_type` and one channel for both apps (registering them once hardware has
   validated the transport).
5. **Divide the work**: they do navigation; we do the OsmAnd overlay, the browser client,
   the strict codec and the specification.

Nothing here requires the other project to change anything in a hurry, and nothing here
requires us to give up our differentiators. The point is that two independent clients are
what turns a private format into a standard — as long as they agree on the bytes.

---

## 1. The other app, in one page

`roadstrapp/roadstr-app`, **MIT**, Android-only, package `app.roadstr`.

* Real turn-by-turn navigation (MapLibre, OSRM/GraphHopper/OpenRouteService, offline Valhalla),
  screen-off navigation in a foreground service, on-device voice guidance, public transport,
  OSM speed limits and ZTL warnings, zaps, NIP-46/Amber sign-in with no private key in the app.
* 27 languages, an encrypted favourites sync, an "Explore" mode, achievements.
* History: a Flutter app up to 0.5.x, a native Kotlin rewrite from 1.0 that installs over it
  with the same package name and signing key.
* Current work: `docs/interoperability/MESHCORE_NOSTR.md` — reading and writing the
  *jooray/roadstr* event layout, a 110/134-byte frame codec, and a MeshCore LoRa bridge.
  Implemented and tested against a simulated radio; **not yet run on real hardware**.
* Its own native event layout is different from ours (`lat, lon, g, g, g, t, expiration[,
  maxspeed]`, six decimals, kinds 1315/1316 plus 1317/1318 for corrections). It keeps working
  for its older clients; the compatible layout is an additional, opt-in publication.

So: it is not a fork, not a competitor with our code, and not affiliated. It is a
better-resourced app that chose to interoperate with our format instead of inventing one.

---

## 2. What we verified

Everything below was read in their repository or recomputed here; nothing is taken from a
README claim alone.

### 2.1 Their protocol findings about us

Their document is, uncomfortably, the most careful audit our format has had. It is right on
every point we re-checked:

| Finding | Verified here |
|---|---|
| Our NIP's example geohashes (`u2ed`, `u2edc`, `u2edcg`) are wrong; 48.8566140, 2.3522219 is `u09t/u09tv/u09tvw` | recomputed, yes |
| `(lat * 1e7).toLong()` truncates; ~6.2 % of coordinates land one unit short (e.g. 35.1976312 → 351976311.99999994) | yes, in `BinaryCodec.encodeReport` |
| `"%.7f".format()` follows the device locale; 3 of 20 real reports on public relays have `"49,0765652"` | yes, in `EventSigner.createReportEvent` and `BinaryCodec.decodeReport` |
| `SPECIFICATION.md` says 109 bytes in a heading (the table sums to 110), calls flag bit 2 "privacy mode", and its §15 worked example is arithmetically wrong | yes |
| Our codec test used fake keys and signatures, so `event → frame → event` had never been demonstrated with a real signature | yes |
| Real-relay probe: 138 events, 103 in their layout, 20 in ours, 15 confirmations, all signatures valid | plausible; we did not repeat it, but the classification matches the layouts |

Both coordinate bugs are now fixed (see §6), and the encoder refuses any event that does not
round-trip through its own frame.

### 2.2 What their implementation does

* Reads events by tag **name**, not order, so it can tell the two layouts apart by the tags
  alone: ours has `t, g, g, g, lat, lon, expiration, alt`, theirs starts with `lat, lon`.
* Applies **our** lifetime rules to our events (police 2 h, speed camera 30 d, …) and its own
  to its own. It also applies the vote rule (extend on `still_there`, cut on
  `no_longer_there`, last five minutes decide visibility).
* Encodes only canonical events: empty `content`, exact tag order, exact `alt`, seven decimals
  with a dot, `expiration = created_at + 1209600`. Otherwise it shows the report but refuses
  the radio (`NON_CANONICAL`, `NON_EMPTY_CONTENT`, …).
* Rebuilds the event from the frame and **verifies the BIP-340 signature** before treating a
  datagram as an event.
* Ships 21 valid + 33 invalid + 12 non-representable + 1 tolerated + 5 truncation-recovery
  vectors, generated by a stdlib-only Python program that shares no code with the app and
  checks itself against the official BIP-340 vectors; all event IDs and signatures were
  cross-checked with `@noble/curves`.

That vector suite is the single most valuable artifact in the whole ecosystem, and it is MIT.

### 2.3 The parts that do not line up

| Topic | Ours | Theirs |
|---|---|---|
| Native layout | `t, g×3, lat, lon, expiration, alt`, 7 decimals | `lat, lon, g×3, t, expiration[, maxspeed]`, 6 decimals, `alt` absent |
| Wire `alt` | part of the canonical form | `alt` absent from their native form |
| Categories | 12 + `other` (255) | adds `police_station`, `surveillance_camera`; `other` unused |
| Lifetime (police) | 2 h | 4 h |
| Lifetime (accident) | 3 h | 4 h |
| Lifetime (road closure) | 7 d | 24 h |
| Lifetime (construction) | 7 d | 15 d |
| Lifetime (road condition) | 6 h | 15 d |
| Lifetime (fog / ice) | 3 h / 6 h | 4 h / 4 h |
| Corrections | none | 1317 (owner edit) / 1318 (edit request) |
| Frame codec | implemented, **broken** (fixed now) | implemented, strict, vectors |
| MeshCore transport | specified, not implemented | implemented, simulated, off by default |
| Readers | tags by name → shows their reports, with **our** lifetimes | tags by name → shows ours, with **our** lifetimes |
| Merge/duplicate logic | none | same key + same category + ±5 min + 1 µdegree |

Neither app is "right" about the other's lifetimes; each simply does not know where a report
came from. That is the cheapest thing to fix on both sides and it is worth doing (see §7.2).

---

## 3. Are they compatible? The honest answer

**At event level: yes, in one direction by design, and in both directions by accident.**

* They deliberately read and write our layout; that is tested and documented.
* We read their layout because our parser looks tags up by name. Nobody designed it, it is not
  tested against them, we apply our lifetimes to their events, and we have no provenance label.
* Neither app treats the other's native events as first-class: the same signed event can show
  one lifetime in one app and another lifetime in the other.

**At client level: no.** Different apps, different code, different features, no shared state,
no shared navigation, and no merge of the two layouts. A user with both apps installed sees
two markers for the same incident when both publish.

The word to use externally is **"independent and partially interoperable"**, not
"incompatible" (which is what both READMEs say today and what this document proposes to
correct) and not "compatible" (which would promise more than exists).

---

## 4. Location keying: is it the same idea?

Yes. Both implementations key a report the same way, and it is the right way for relays:

* Three `g` tags per event: geohash of the report position at **precision 4, 5 and 6**
  (~20 km, ~5 km, ~1.2 km cells).
* Geohash computed by the usual interleaved bisection with `>=` on the midpoint, alphabet
  `0123456789bcdefghjkmnpqrstuvwxyz`.
* **Only `#g` tags are used for querying**; `lat`/`lon` are the exact position. Clients
  subscribe with the centre cell plus its eight neighbours.
* A confirmation **repeats the report's** geohashes and coordinates, so one `#g` query returns
  reports and votes together (`#e` on the report IDs also works).
* The precise position lives in `lat`/`lon` as a seven-decimal string; the frame stores the
  same value as int32 × 10⁷, so nothing is lost.

Improvements worth considering, in order of value:

1. **Confirmations should carry their own coordinates in the frame** — they already repeat
   them in the event, but the 134-byte frame does not, so a receiver that hears a vote before
   the report cannot rebuild it (their bridge holds such votes for 15 minutes and 64 at most).
   This is the one real design flaw in the frame format. Proposed fix in §7.3.
2. **The query window must match the longest lifetime.** Our client asked relays for the last
   24 hours while a speed camera lives 30 days, so on a cold start we hid valid reports. Fixed
   in this pass (30 days, filtered by type after arrival).
3. **Precision 7 is not worth it.** It adds 7 bytes per event and no relay filter uses it;
   the exact position is already in `lat`/`lon`.
4. **Do not put a geohash in the frame.** The frame carries the exact int32 coordinates, and
   the geohashes are derived from them deterministically; a second copy could only disagree.
5. **A per-cell `since` is better than a global one** for busy cities, but that is an
   optimisation, not a protocol change.

So: no new location scheme is proposed. The existing one is fine; what needs work is what
travels *next to* the location.

---

## 5. Canonical and one packet?

**A report: yes.** 110 bytes of payload in one MeshCore channel-data datagram, with 165 bytes
available on current companion firmware (their code conservatively assumes 163; 166–167 pass
the host check and then fail permanently in the radio, so stay ≤ 165).

```
report frame   110 B   fits, one datagram, no fragmentation
confirmation   134 B   fits, one datagram — but not self-contained (§5.1)
v2 confirmation 142 B  fits, one datagram, self-contained (§7.3)
```

Requirements, all enforced by our encoder now:

* `content` must be empty — a comment changes the ID and is not in the frame.
* tag order, `alt` text, `expiration`, coordinate spelling and geohashes must be exactly what
  the decoder will rebuild. Anything else is a different event and cannot be verified.
* `created_at` must fit in uint32 (until 2106), coordinates in ±90°/±180°.
* the signature is BIP-340 over the rebuilt event ID; the flags byte is **not** signed, so
  bit 2 (the "ephemeral" hint) is a hint from the sender and never evidence.

Airtime, as a rough order of magnitude: at SF9/125 kHz a 110-byte payload is about 0.6 s on
air, at SF10 about 1.1 s, before MeshCore's own header — enough that the protocol's rate
limits and de-duplication are not optional. Measure on real hardware before promising
anything; nobody has yet.

### 5.1 The confirmation problem

A confirmation names a report by its 32-byte event ID and repeats the report's position in
its tags. The frame carries only the ID, so a receiver needs the report (from cache or a
relay) to rebuild the tags, and therefore cannot verify a vote heard before the report. On
the internet that is a cache miss; on a mesh with no internet it can be a lost vote.

Worse, it costs 32 bytes for the ID and then 0 bytes for position, while a self-contained
version costs 40 bytes. There is room: 142 ≤ 165.

### 5.2 Can a Roadstr-native event be converted into a frame?

**Not after the fact.** A conversion from their layout (`lat, lon, g, g, g, t, expiration[,
maxspeed]`, six decimals, no `alt`) into ours is a different tag set, therefore a different event
ID, therefore an invalid signature. Nobody but the author's signer can produce the second event —
which is exactly what their opt-in "publish compatible copy" does. A bridge cannot translate; it
can only carry or drop.

**It could be made frameable with a second layout in the frame.** Everything their native report
needs is derivable from a compact field set if five things are pinned:

| Requirement | Their native layout | Consequence |
|---|---|---|
| Coordinates | six decimals, `BigDecimal HALF_UP` from a `Double` | frame carries degrees × 10⁶; rounding must match at the sixth decimal |
| Tag order | `lat, lon, g, g, g, t, expiration[, maxspeed]` | fixed rebuild rule |
| `alt` | absent | fixed rebuild rule |
| `content` | may carry a comment | a comment makes the event unframeable, same as ours |
| `expiration` | `created_at + category TTL` (verified: `safeClientExpiration`) | the TTL table becomes part of the wire contract |
| `maxspeed` | optional, 5–300 on speed cameras | needs a uint16 (1 byte cannot hold 300) |

Size: 110 + 2 (maxspeed) = **112 bytes**, or 116 if the expiration is carried instead of derived.
Both fit in 165.

Why not do it now: it doubles the codec matrix (two layouts × two classes), turns their category
TTL table into something that cannot be changed without invalidating old frames, adds a sixth-
decimal rounding edge case, and still excludes every report with a comment. It also only helps for
events their app chose to publish — and, by default, it publishes only its own layout. The
justified use case is a mesh-only bridge between two communities whose Roadstr users never turn on
the compatible copy; even then, asking them to publish the copy is cheaper than a second decoder.

**Recommendation:** one layout in the frame. Read their layout for display, and for the radio rely
on the compatible copy (v1 today, v2 after §7.3).

---

## 6. What we fixed in this pass

Kept deliberately small, because each of these is a prerequisite for any mesh work:

| Fix | File |
|---|---|
| Integer-exact coordinate parse/format, no `String.format`, no truncation | `CanonicalCoordinates.kt` (new) |
| Events are created from the rounded E7 value, so tag text, geohashes and frame agree | `EventSigner.kt` |
| Encoder refuses non-canonical events and verifies its own round trip before emitting bytes | `BinaryCodec.kt` |
| Decoder rebuilds tags from integers, rejects unknown types, reserved bits and bad versions | `BinaryCodec.kt` |
| One of their published vectors is now a unit test (frames → exact event ID and back) | `BinaryCodecTest.kt` |
| Relay query window covers the longest lifetime | `RoadbeaconService.kt` |
| Web client verifies every event signature before display | `roadbeacon-web/js/nostr.js` |

The vector test is the important one: it pins our canonicalisation to a byte sequence produced
by an independent implementation, so a future refactor cannot quietly change event IDs.

---

## 7. Path forward

### 7.1 Naming

* App: **Roadbeacon**, package `app.roadbeacon`, new Zapstore listing, same signing key.
* Format: **Road Events** (kinds 1315/1316), v1 today, v2 proposed. No brand in the name.
* The string `"Roadstr: …"` inside v1 events is a **format constant**, not branding. It stays
  until v2 exists, and v1 stays readable forever.
* The MeshCore channel stays `#roadstr` (key `b7f4317db7b03aeb563b2c4bdc476d6a`, first 16
  bytes of SHA-256 of the name). Renaming it would split the network for zero benefit; it is
  invisible to users. Document it as "the format's legacy name".

### 7.2 Immediate, unilateral (weeks)

1. **Read their native layout properly.** One adapter, mapping their tags and *their* category
   lifetimes, with a provenance line ("from Roadstr", "over MeshCore"). This is the mirror of
   what they already do for us, and it removes the only case where we show wrong lifetimes.
2. **Publish only one layout.** Never a second copy "for compatibility": two signed events for
   one incident is what forces their merge heuristic, and older clients show both markers.
3. **Correct the documents.** `SPECIFICATION.md` and the NIP: 110 not 109, flag bit 2 is
   reserved, right geohashes, and a confirmation needs its report's coordinates. Mark the
   layout as v1/frozen and link the vectors.
4. **Add a "related app" line** in README and in the app's About/Settings, worded as in §8.

### 7.3 v2 of the frame (coordinated, ~1 day per side)

Same kinds, same tags, same geohash scheme. Two changes, both carried by the existing version
nibble, so no length guessing:

| | v1 (today) | v2 |
|---|---|---|
| Report frame | 110 B, version 0 | 110 B, version 1 |
| Confirmation frame | 134 B, version 0, needs the report | **142 B, version 1**, carries the report's int32 lat/lon |
| `alt` | `"Roadstr: <type> report"` | `"Road event: <type>"` / `"Road event confirmed"` / `"Road event denied"` |
| Type registry | 0–11, 255 | adds 12 `police_station`, 13 `surveillance_camera`, 14–254 reserved |

Rules:

* A decoder accepts both versions and rebuilds the tags that belong to that version.
* An encoder emits the version the event is canonical for: a v1 event (with the legacy `alt`)
  becomes a v0 frame; a v2 event becomes a v1 frame. Nothing is re-signed, nothing is rewritten.
* Publishing v2 by default starts only when both apps decode it, and both are green on a new
  vector set that includes v1 and v2 cases.
* Type values 12/13 give their app something it cannot do today: put police-station and
  surveillance-camera reports on the radio at all.

Why bother, if v1 works? Because v1 puts another project's app name into every event we sign,
and because a vote that cannot be verified without a cache is a bad fit for the transport the
format is named after. Both are cheap to fix now and impossible to fix later without a version.

### 7.4 Make the vectors the contract

* One canonical copy of `vectors/road-events-v1.json`, generated by one implementation-independent
  program, versioned like a spec.
* Both apps vendor it and fail CI when it changes unexpectedly (hash + a full run).
* Any change to canonicalisation, tag order, `alt` or frame layout requires a new vectors file
  and a version bump; "semantically equivalent" is not a thing here.
* Proposed home: this repository (it holds the NIP today) until a third implementation appears;
  then a neutral repository. Their copy stays valid either way — it is MIT and pinned.

### 7.5 MeshCore parameters

| Parameter | Value | Note |
|---|---|---|
| Channel | `#roadstr`, slot chosen by the user | public by construction; the key is derivable from the name |
| `data_type` | `0xFF52` | inside the firmware's unregistered testing range `0xFF00–0xFFFE`; keep it, and register a stable value in `0100–FEFF` once a real radio has been tested |
| Payload | 110 / 134 / 142 B | stay ≤ 165 B; never 166–167 |
| Consent | off by default, per direction | radio traffic reveals position, time and key to anyone in range |

Registration is a PR to `meshcore-dev/MeshCore` adding one row to `docs/number_allocations.md`.
Do it after hardware validation, with both apps named in it, so the number means "road events",
not "one app".

### 7.6 Division of work

| Area | Owner | Why |
|---|---|---|
| Navigation, routing, voice, transit | them | years of work, not our differentiator |
| Mobile UX for drivers | them | same |
| OsmAnd overlay, in-car HUD, AIDL | us | they render their own map; OsmAnd users are a different audience |
| Browser client (no install, NIP-07) | us | nobody else has one |
| Specification, type registry, conformance vectors | us + them | we wrote it, they hardened it |
| Strict codec, second implementation | us | two implementations is what makes a format real |
| MeshCore bridge | both, independently | same bytes, two clients, real interoperability test |

Concretely, this means: **we do not build navigation**, and **they do not build an OsmAnd
plugin**. Everything else is shared.

### 7.7 Roadmap

| Phase | Version | Content | Gate |
|---|---|---|---|
| 0 | 0.4.0 Roadbeacon | rename, canonical codec, vector test, docs, cross-link | this pass |
| 1 | 0.5 | read their layout with their lifetimes + provenance; NIP/SPEC corrections; relay interop test read-only | their reports display correctly |
| 2 | 0.6 | MeshCore transport: BLE companion client, receive, send, bridge with limits (port/adapt their MIT code, credit it) | two radios, one report, same event ID |
| 3 | 1.0 | v2 frame + shared vectors + registered `data_type` | both apps green on v1+v2 vectors |
| 4 | — | decision gate: keep the standalone app, or keep spec + web + OsmAnd plugin only | see §9 |

Phase 2 is the big one (their own estimate: implemented, never run on hardware). Their code is
MIT and Kotlin, so porting is legitimate and much cheaper than re-deriving the companion
protocol; the BLE layer is the part that needs a device, not more code.

---

## 8. Cross-linking (both directions)

Proposed text for our README, and for a line in the app:

> ### The other Roadstr
>
> [Roadstr](https://github.com/roadstrapp/roadstr-app) ([Zapstore](https://zapstore.dev/apps/app.roadstr))
> is an independent navigation app for Android by a different author. It is not affiliated with
> Roadbeacon, has its own code and roadmap, and the two apps are not interchangeable. They do
> share the road-event event format (kinds 1315/1316) partially: each can display the other's
> reports, with its own category and lifetime rules, and a report only travels over a MeshCore
> radio when it is exactly canonical. We like it and we recommend it if you want turn-by-turn
> navigation; Roadbeacon is for people who want road events inside OsmAnd and in a browser.

Ask them to add the same one-paragraph note pointing here. Do not use their name or icon in our
app icon, store listing title or package id; the README note is enough, and it is what keeps the
two projects from being mistaken for each other in search results.

---

## 9. What "best of all worlds" looks like — and when to stop

Three end states are defensible:

**A. Two apps, one format (recommended now).** They own navigation, we own OsmAnd + web +
mesh. Cost: two codebases to keep compatible. Benefit: independence, two implementations, and
no single point of failure for the format. This is what phases 0–3 build.

**B. Contribute and retire (fallback).** If our app has no users after the interop release,
fold the parts that only we have (OsmAnd AIDL overlay, browser client, vectors) into their
project or keep them as small satellites, and retire the standalone Android app. The format
survives; the duplicate client does not. Trigger: no organic installs and no OsmAnd users
after ~3 months of phase 3.

**C. Merge into one app (unlikely).** Their app gains an OsmAnd mode; ours disappears entirely.
This needs them to take on the OsmAnd dependency, its AIDL quirks and its users. Offer our
`osmand/` package as a library if they ever ask; do not push it.

The decision is not urgent, but it should be explicit. Writing it down now is what stops a
second app being maintained out of inertia.

---

## 10. Concrete asks to the other developer

1. **Confirm the freeze**: v1 `alt` stays `"Roadstr: …"` on the wire; the format is called
   "Road Events", not "the Roadstr format", in new text.
2. **v2**: neutral `alt`, 142-byte self-contained confirmation, `police_station` and
   `surveillance_camera` as type values 12/13. Tell us which of these you want; any of them can
   ship alone.
3. **Vectors**: keep one canonical file. We will vendor a pinned copy and run it in CI; we are
   happy for it to live in your repository, since your generator is implementation-independent.
4. **MeshCore**: keep `#roadstr` and `0xFF52` as the shared defaults, and put both apps' names
   in the registration PR when hardware validation is done.
5. **Provenance**: keep labelling our events as "from the Nostr network" (you already do); we
   will label yours as "from Roadstr". Then neither app has to merge or guess.
6. **Corrections**: we will document 1317/1318 in the NIP as your contribution and implement
   read-only support. If you would rather keep them out of the NIP for now, say so.
7. **Cross-links**: add the note in §8 and tell us if the wording should change.
8. **Tell us when a real radio works**, including airtime numbers and firmware version; that
   is the missing piece in both projects' documentation.

---

## 11. Things we deliberately will not do

* Introduce a new kind, a new geohash scheme, or a "mesh-native" event that is not a Nostr event.
* Publish two events for one report.
* Put a brand name in v2 events, or a geohash in the frame.
* Truncate signatures or drop fields to save airtime: an unverifiable report is worse than no
  report, and 110 bytes already fits.
* Merge reports from different authors that are close together. Two drivers reporting the same
  police car are two voices; merging them lets one author suppress another.
* Claim MeshCore support before a real radio and a real firmware have been tested.

## 12. Risks

| Risk | Mitigation |
|---|---|
| Two canonical forms coexist for years | v1 is frozen and only read; v2 is the write default once both sides are green |
| Brand name still in v1 events | documented as a format constant; no new event carries it |
| Our app and theirs show different lifetimes for the same event | provenance adapters on both sides (§7.2), not a shared lifetime table |
| MeshCore airtime/duty cycle unproven | defaults conservative, measure first, keep the radio opt-in and rate-limited |
| The public hashtag channel is open to anyone | treat radio input as untrusted; verify signatures; bound rates and queues |
| Our app becomes a duplicate client nobody needs | explicit decision gate (§9) |
| Their app changes its native layout | we only depend on the 1315/1316 tags we actually parse, and we pin their vectors |

---

## Appendix A — Sources

Their repository (MIT), read at `main`:

* `docs/interoperability/MESHCORE_NOSTR.md` — the interoperability specification, findings,
  frame layout, bridge design, real-relay probe, hardware checklist.
* `docs/interoperability/vectors/roadstr-nostr-meshcore-v1.json` — 21/33/12/1/5 vectors.
* `tools/interop/generate_vectors.py` — independent generator (stdlib only).
* `android/app/src/main/kotlin/app/roadstr/core/protocol/interop/` — `CompactRoadCodec.kt`,
  `CompactCoordinates.kt`, `CompactRoadType.kt`, `ExternalRoadReportAdapter.kt`,
  `RoadEventLedger.kt`, `InteropLifetime.kt`.
* `android/app/src/main/kotlin/app/roadstr/service/meshcore/` — `MeshCoreClient.kt`,
  `RoadMeshBridge.kt`, `MeshRepublishQueue.kt`, `AndroidMeshCoreBle.kt`.
* `android/app/src/main/kotlin/app/roadstr/core/protocol/nostr/NostrProtocol.kt` — their native
  layout, categories and lifetimes, kinds 1317/1318.

MeshCore (upstream):

* `docs/companion_protocol.md` — `0x3E` channel data datagram, payload limits (165 bytes
  radio-side), `data_type` ranges, hashtag channel keys.
* `docs/number_allocations.md` — registration procedure.

This repository:

* `nips/road-events.md` (was `nips/roadstr.md`) — the format.
* `SPECIFICATION.md` — the long design document; older, with known errors.
* `roadbeacon-android/.../nostr/CanonicalCoordinates.kt`, `BinaryCodec.kt`, `ProtocolText.kt`
  — the strict codec and the frozen v1 text.
* `roadbeacon-web/js/roadbeacon.js` — the browser client's copy of the format.

## Appendix B — Numbers quoted in this document

| Number | Meaning |
|---|---|
| 110 / 134 / 142 B | report, v1 confirmation, v2 confirmation frame |
| 165 B | largest channel-data payload current companion firmware accepts |
| 163 B | the limit their code enforces (conservative) |
| 3 / 4 / 5 / 6 | geohash precisions used for querying (4, 5, 6) and reserved |
| 1 209 600 s | `expiration = created_at + 14 days`, always |
| 0xFF52 | `data_type` both apps should use until registration |
| `b7f4317db7b03aeb563b2c4bdc476d6a` | channel key of `#roadstr` |
| 6.2 % / ~12 % | coordinates / reports our old encoder could not round-trip |
| 138 | real relay events used for their read-only validation (103 theirs, 20 ours, 15 votes) |
