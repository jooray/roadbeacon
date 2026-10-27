NIP-XX
======

Road Events
-----------

`draft` `optional`

This NIP defines two event kinds for decentralized road event reporting: `kind:1315` for road event
reports and `kind:1316` for confirmations/denials of those reports. The same events travel over
Nostr relays and, in a compact binary form, over LoRa mesh networks (MeshCore).

Road events include police checks, speed cameras, accidents, traffic jams, road closures,
construction zones, and other hazards. Events are anchored to geographic locations using geohash
tags, enabling spatial querying by clients.

**One layout, two implementations.** The tag layout below is the one the independent
[Roadstr](https://github.com/roadstrapp/roadstr-app) client writes natively, specified here once so
that both clients (and any third one) agree on the bytes. It replaces the earlier `jooray/roadstr`
layout, which is deprecated (see [Appendix A](#appendix-a--the-deprecated-layout)). There is no
`alt` tag: the format carries no brand and no redundant position data.

## Road Event Report (kind 1315)

A road event report signals the presence of a notable condition at a specific geographic location.

The `.content` field is a plain-text optional comment (e.g., `"Checking seatbelts"`). A comment is
carried in the event and, up to 46 UTF-8 bytes without control characters, in the compact frame;
longer comments, or comments containing U+0000–U+001F or U+007F, are valid on relays but are never
framed. Clients SHOULD NOT put control characters in a comment.

### Tags

The following tags MUST appear in exactly this order:

- `lat` (required) latitude as a decimal string with exactly 6 decimal places (e.g., `"48.856614"`)
- `lon` (required) longitude as a decimal string with exactly 6 decimal places (e.g., `"2.352222"`)
- `g` (required, repeated) geohash of the position at precision levels 4, 5 and 6, in that order
- `t` (required) category string from the registry (see [Categories](#categories))
- `expiration` (required) Unix timestamp, `created_at + max(1209600, lifetime)` — see [Expiration](#expiration)
- `maxspeed` (optional) speed limit in km/h, an integer 5–300, no leading zeros. Only meaningful for
  `speed_camera`, but the tag is allowed on any report.

Six decimals is ~11 cm, far below GPS accuracy, and is the precision the frame reproduces exactly.
Seven decimals (the old layout) is not canonical here.

### Example

```json
{
  "id": "<32-bytes lowercase hex-encoded SHA-256>",
  "pubkey": "<32-bytes lowercase hex-encoded public key>",
  "created_at": 1700000000,
  "kind": 1315,
  "tags": [
    ["lat", "48.856614"],
    ["lon", "2.352222"],
    ["g", "u09t"],
    ["g", "u09tv"],
    ["g", "u09tvw"],
    ["t", "police"],
    ["expiration", "1701209600"]
  ],
  "content": "",
  "sig": "<64-bytes hex-encoded signature>"
}
```

Geohashes MUST be derived from the same six-decimal value that `lat`/`lon` name
(precision 4, 5, 6, `>=` on the midpoint, alphabet `0123456789bcdefghjkmnpqrstuvwxyz`).

## Road Event Confirmation (kind 1316)

A confirmation event indicates whether a previously reported road event is still present or has
cleared. Any user passing the reported location can publish a confirmation.

A vote carries **no position, no expiration and no `alt`**: it is two tags and an empty `.content`.

### Tags

The following tags MUST appear in this canonical order:

- `e` (required) event ID of the kind 1315 report being confirmed or denied
- `status` (required) either `"still_there"` or `"no_longer_there"`

### Example

```json
{
  "id": "<32-bytes lowercase hex-encoded SHA-256>",
  "pubkey": "<32-bytes lowercase hex-encoded public key>",
  "created_at": 1700003600,
  "kind": 1316,
  "tags": [
    ["e", "<id of the kind 1315 report>"],
    ["status", "still_there"]
  ],
  "content": "",
  "sig": "<64-bytes hex-encoded signature>"
}
```

Consequences:

- Votes are found by `#e` with the IDs of the reports a client is showing, not by area. Clients
  MUST NOT expect `#g` to find them. (A vote without a position cannot be area-queried, and that is
  a deliberate trade: it makes the 134-byte vote frame self-contained.)
- A vote has no NIP-40 `expiration`; relays keep it unless they apply their own policy. Clients MUST
  ignore votes older than their report and votes more than 5 minutes in the future, and count one
  vote per key (see [Display lifetime](#display-lifetime-client-side)).

## Categories

The `t` tag value MUST be one of these strings; the numeric value is the byte used in the frame.

| Value | String | Display lifetime |
|---|---|---|
| 0 | `police` | 4 hours |
| 1 | `speed_camera` | 30 days |
| 2 | `traffic_jam` | 1 hour |
| 3 | `accident` | 4 hours |
| 4 | `road_closure` | 24 hours |
| 5 | `construction` | 15 days |
| 6 | `hazard` | 4 hours |
| 7 | `road_condition` | 15 days |
| 8 | `pothole` | 7 days |
| 9 | `fog` | 4 hours |
| 10 | `ice` | 4 hours |
| 11 | `animal` | 1 hour |
| 12 | `police_station` | 30 days |
| 13 | `surveillance_camera` | 30 days |
| 14–254 | reserved | — |
| 255 | `other` | 4 hours |

Reserved values MUST NOT be mapped to `other` when decoding: that would change the `t` tag and with
it the event ID. Drop the event or frame instead.

The numeric values are part of the wire format; renaming or renumbering a category changes every
event that uses it. Adding a category means a new value and a vectors bump.

## Expiration

Two lifetimes exist and are often confused:

### Relay-side retention (NIP-40)

The `expiration` tag tells relays when they may delete the event. It MUST be
`created_at + max(1209600, lifetime)` — 14 days for most categories, 30 days for the long-lived ones
(`speed_camera`, `police_station`, `surveillance_camera`).

The 14-day floor exists so that votes can extend a report: if relays dropped a police report after
its 4-hour lifetime, a "still there" at hour five would have nothing to extend. It MUST NOT be
shorter than the category's own lifetime.

Readers MUST accept any `expiration` value they can parse and MUST NOT reject an event because the
tag does not match this rule: older clients wrote `created_at + lifetime`, and those events are
valid.

### Display lifetime (client-side)

```
expiry = created_at + lifetime(type)
for vote in votes, ordered by (created_at, denial before confirmation):
    still_there     -> expiry = max(expiry, vote.created_at + lifetime)
    no_longer_there -> expiry = min(expiry, vote.created_at)
expiry = min(expiry, expiration)
```

Choices the specification leaves open, made once:

- one voice per key: only the latest vote of a key counts;
- a vote older than its report, or more than 5 minutes in the future, is ignored;
- votes with the same timestamp are applied denials first, so a tie keeps the report alive;
- a report is hidden while the **last five minutes** contain more (admissible) denials than
  confirmations (a tie stays visible).

These are requirements, not suggestions: two clients that count votes differently show different
maps for the same events.

A client MUST NOT show a marker past the event's `expiration`: the data may no longer be served. It
MUST NOT hide a report earlier than this algorithm says either — in particular not at
`created_at + 14 days` for a category whose lifetime is 30 days.

## Geohash Querying

| Precision | Cell size | Use |
|---|---|---|
| 4 chars | ~20 km × 20 km | highway / regional |
| 5 chars | ~5 km × 5 km | city |
| 6 chars | ~1.2 km × 0.6 km | neighbourhood |

Clients query reports with `#g` at a precision appropriate to zoom or speed, including the centre
cell and its eight neighbours, then fetch votes with `#e` for the report IDs they show:

```json
["REQ", "road-reports", {
  "kinds": [1315],
  "#g": ["u09ts", "u09tt", "u09tu", "u09tv", "u09tw", "u09ty", "u09wh", "u09wj", "u09wn"],
  "since": 1699993200
}]

["REQ", "road-votes", {
  "kinds": [1316],
  "#e": ["<visible report id>", "…"]
}]
```

The `since` value of a cold-start query SHOULD cover the longest display lifetime (30 days), not a
fixed 24 hours, or valid long-lived reports will be hidden.

## Compact Binary Representation

For bandwidth-constrained transports (mesh networks, BLE), events are packed into frames that are
rebuilt into fully verifiable Nostr events. Integers are big-endian. (MeshCore's own companion
frames are little-endian; the payload inside a channel datagram is opaque to the radio.)

### Report frame (117 + N bytes, 0 ≤ N ≤ 46 when encoding, ≤ 48 when decoding)

```
Offset  Size  Field
0       1     Flags
1       32    Public key (x-only)
33      4     created_at (uint32)
37      4     Latitude (int32, degrees x 10^6)
41      4     Longitude (int32, degrees x 10^6)
45      1     Category (registry value)
46      4     expiration (uint32)
50      2     maxspeed (uint16, 0 = tag absent)
52      1     N, length of the comment in bytes
53      N     Comment, UTF-8 (may be empty)
53+N    64    Schnorr signature (BIP-340)
```

Largest frame an encoder produces: **163 bytes** (N = 46). That is what a MeshCore companion link
(BLE, and MeshCore Open's local API on top of it) accepts for one channel datagram; the radio itself
carries 165. An encoder MUST refuse a comment over 46 bytes or one containing a control character
(U+0000–U+001F, U+007F); a comment is never truncated or cleaned up, because that would change the
signed event.

A decoder MUST accept N up to 48 (frames up to 165 bytes, which a serial-connected node could
send) and MUST reject a comment that is not valid UTF-8 or contains a control character.

### Vote frame (134 bytes)

```
Offset  Size  Field
0       1     Flags
1       32    Public key
33      4     created_at (uint32)
37      32    ID of the referenced report
69      1     Status (0 = no_longer_there, 1 = still_there)
70      64    Schnorr signature
```

The vote frame is self-contained: a two-tag event needs nothing from the report it names. A vote
heard before its report can be verified immediately.

### Flags byte

```
Bits 7-4  Frame version (0)
Bit  3    Class (0 = report, 1 = vote)
Bit  2    Ephemeral hint (unsigned; never evidence)
Bits 1-0  Reserved, MUST be zero
```

The flags byte is not covered by the signature: anyone relaying a frame can change it. Bit 2 is only
a hint from the sender that a one-time key was used.

### Coordinate encoding

- Latitude: `int32(lat × 10^6)`, range [-90 000 000, 90 000 000]
- Longitude: `int32(lon × 10^6)`, range [-180 000 000, 180 000 000]

Both directions MUST use integer arithmetic:

- **Encoding** reads the value from the `lat`/`lon` tag text. It MUST NOT compute `lat * 1e6` from a
  `Double`: that truncates and can land one unit short, producing a frame whose signature cannot
  verify.
- **Decoding** formats the integer as `-?D+.DDDDDD` — six digits, a decimal point, no locale and no
  `printf`-style formatting. `String.format("%.6f")` writes a comma on many phones and changes the
  signed text.

### Reconstruction

Report:

1. take `pubkey`, `created_at`, position, category, `expiration`, `maxspeed` and the comment
2. format `lat`/`lon` from the integers and derive the geohashes at 4, 5, 6
3. build tags in the canonical order (`maxspeed` only when the uint16 is non-zero)
4. serialize per [NIP-01](01.md): `[0, <pubkey_hex>, <created_at>, 1315, <tags>, <content>]`, compute
   the ID and **verify the BIP-340 signature**. Without a valid signature the frame is not an event.

Vote: build `[["e", id], ["status", …]]`, `content = ""`, kind 1316, compute the ID, verify.

### Encoding rules

An encoder MUST refuse anything that does not round-trip exactly:

```
event -- encode --> frame -- decode --> event'      event'.id == event.id
                                                    event'.pubkey == event.pubkey
                                                    event'.sig == event.sig
```

"Semantically equivalent" is not enough: one different tag, one decimal more or a comma changes the
ID. Refusal reasons (stable names):

| Code | Meaning |
|---|---|
| `WRONG_KIND`, `MALFORMED_EVENT` | not a well-formed 1315/1316 event |
| `NON_CANONICAL` | tag order, spelling, extra tag, `expiration` or coordinate text differs; comment with a control character or invalid UTF-8 |
| `UNKNOWN_TYPE` | `t` not in the registry |
| `COORDINATE_RANGE` | outside ±90° / ±180° |
| `TIMESTAMP_RANGE` | `created_at` or `expiration` does not fit in uint32 |
| `COMMENT_TOO_LONG` | comment over 46 UTF-8 bytes (encoding) |
| `BAD_SPEED_LIMIT` | `maxspeed` outside 5–300 or not canonical text |
| `BAD_LENGTH` | frame length inconsistent with its class or comment length, or over 165 bytes |
| `BAD_VERSION`, `RESERVED_BITS`, `BAD_STATUS` | flags or status byte invalid |
| `SIGNATURE_INVALID` | the ID or signature does not verify |

Decoders check length, version, reserved bits, class, type, coordinate range, speed limit and UTF-8
validity before doing any signature work.

### Conformance vectors

[`vectors/road-events-v1.json`](vectors/road-events-v1.json) is the contract. It is generated by
[`tools/gen_road_event_vectors.py`](../tools/gen_road_event_vectors.py), a standalone
implementation (Python standard library, the BIP-340 reference code, fixed keys, zero auxiliary
randomness), so it reproduces byte for byte. It holds:

| Section | An implementation must |
|---|---|
| `valid` | encode the event to exactly `frame`, decode `frame` to exactly `event`, verify the signature |
| `decode_only` | decode the frame to `event` (ephemeral flag, 47/48-byte comments) — and refuse to encode the 47/48-byte ones |
| `decode_invalid` | refuse the frame with `error` (`SIGNATURE_INVALID` = decodes, does not verify) |
| `encode_invalid` | refuse the event with `error` |

Roadbeacon (`ConformanceVectorsTest`, `roadbeacon-web/test/vectors.mjs`) and Roadstr
(`CompactRoadCodec` tests) both run the whole file. A change that alters any vector is a new frame
version.

## Mesh Transport

The frame is sent unchanged in one MeshCore **channel data datagram** (`0x3E` on the companion
protocol), with:

- channel: `#roadstr` (key = first 16 bytes of `SHA-256("#roadstr")` =
  `b7f4317db7b03aeb563b2c4bdc476d6a`) — a public channel by construction, not a privacy feature;
- `data_type`: `0xFF52` (inside the firmware's unregistered testing range) until a stable value is
  registered with MeshCore in `0100–FEFF`;
- payload: 117–163 bytes for a report (up to 165 accepted), 134 for a vote. A comment longer than
  46 bytes keeps a report relay-only; radio-first clients should keep comments short.

Rate limits, de-duplication and bridge consent are part of any implementation's obligations: a
report is 117–163 bytes, roughly 0.6–0.9 s of airtime at SF9/125 kHz, so a misbehaving bridge is a
channel-wide problem. The operational details — topology, the `#roadstr` channel, `0xFF52`, the
one-BLE-owner local API and the bridge limits — are in
[`docs/meshcore.md`](../docs/meshcore.md).

## Kind Allocation

Kinds 1315 and 1316 are in the regular range (1000–9999). They are unassigned in the NIPs kind
registry; registration requires a PR to [the NIPs repository](https://github.com/nostr-protocol/nips).

## Appendix A — the deprecated layout

Before this revision, Roadbeacon and the first interoperability work used a different layout:

```
report (kind 1315): t, g4, g5, g6, lat, lon, expiration, alt        seven decimals
vote   (kind 1316): e, g4, g5, g6, status, lat, lon, expiration, alt
expiration:         created_at + 1209600 (always)
alt:                "Roadstr: <type> report" / "Roadstr: event confirmed" / "Roadstr: event denied"
```

It is **deprecated**: clients MUST NOT write it and SHOULD NOT display it (the reference clients
ignore it), and the frame defined above cannot carry it (the rebuilt tags would differ, so the
signature would not verify). Such events expire on relays within 14 days of their creation. A client
can recognise them by the `alt` tag or by `t` appearing before `lat`.

The old frame (110 bytes for a report, 134 for a vote with coordinates in its tags) is likewise
obsolete and MUST NOT be decoded as the frames above; the vote frame has the same length but a
different meaning, so implementations must not mix versions.

## References

- [NIP-01](01.md) — basic protocol flow
- [NIP-40](40.md) — expiration timestamp
- [Roadstr interoperability document](https://github.com/roadstrapp/roadstr-app/blob/main/docs/interoperability/MESHCORE_NOSTR.md) — independent audit of the deprecated layout, the bridge design and the hardware checklist
- [`docs/ecosystem.md`](../docs/ecosystem.md) — the two implementations and the migration plan
