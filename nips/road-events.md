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

**Version.** This document describes **v1** of the format. Its canonical text is frozen: the `alt`
tag says `"Roadstr: …"` and that string is part of every v1 event ID. It is a format constant, not
an app name — clients must reproduce it exactly. A brand-neutral v2, with self-contained
confirmations, is proposed in [`docs/ecosystem.md`](../docs/ecosystem.md).

**Implementations.** [Roadbeacon](../README.md) (OsmAnd plugin and web client) and
[Roadstr](https://github.com/roadstrapp/roadstr-app) (an independent navigation app, which also
publishes the conformance vectors this document is checked against). References to
[`docs/interoperability/MESHCORE_NOSTR.md`](https://github.com/roadstrapp/roadstr-app/blob/main/docs/interoperability/MESHCORE_NOSTR.md)
below point at that project's independent audit of this format, including a read-only probe of 138
real events on public relays.

## Road Event Report (kind 1315)

A road event report signals the presence of a notable condition at a specific geographic location.

The `.content` field is a plain-text optional comment (e.g., `"Checking seatbelts"`). An empty
string `""` indicates no comment. **An event with a non-empty comment cannot be represented in the
compact binary form** (see [Compact Binary Representation](#compact-binary-representation)).

### Tags

The following tags MUST appear in this canonical order:

- `t` (required) event type string from the defined enum (see [Event Types](#event-types))
- `g` (required, repeated) geohash of the position at precision levels 4, 5, and 6 characters. Levels 4-6 enable efficient spatial querying.
- `lat` (required) latitude as decimal string with exactly 7 decimal places (e.g., `"48.8566140"`)
- `lon` (required) longitude as decimal string with exactly 7 decimal places (e.g., `"2.3522219"`)
- `expiration` (required) Unix timestamp, always computed as `created_at + 1209600` (14 days). Per [NIP-40](40.md), relays SHOULD delete the event after this time. See [Expiration](#expiration).
- `alt` (optional) human-readable fallback per [NIP-31](31.md). If present, MUST be exactly `"Roadstr: <type_string> report"`.

### Example

```json
{
  "id": "<32-bytes lowercase hex-encoded SHA-256>",
  "pubkey": "<32-bytes lowercase hex-encoded public key>",
  "created_at": 1700000000,
  "kind": 1315,
  "tags": [
    ["t", "police"],
    ["g", "u09t"],
    ["g", "u09tv"],
    ["g", "u09tvw"],
    ["lat", "48.8566140"],
    ["lon", "2.3522219"],
    ["expiration", "1701209600"],
    ["alt", "Roadstr: police report"]
  ],
  "content": "",
  "sig": "<64-bytes hex-encoded signature>"
}
```

The geohashes above were recomputed from the coordinates: 48.8566140, 2.3522219 is in `u09t`,
`u09tv`, `u09tvw`. Earlier revisions of this document printed `u2ed*`, which is wrong.

## Road Event Confirmation (kind 1316)

A confirmation event indicates whether a previously reported road event is still present or has
cleared. Any user passing the reported location can publish a confirmation.

The `.content` field is a plain-text optional comment. An empty string `""` indicates no comment.

### Tags

The following tags MUST appear in this canonical order:

- `e` (required) event ID of the kind 1315 report being confirmed or denied
- `g` (required, repeated) geohash at precision levels 4, 5, and 6 characters. MUST match the geohashes of the referenced report.
- `status` (required) either `"still_there"` or `"no_longer_there"`
- `lat` (required) latitude as decimal string with exactly 7 decimal places. MUST match the referenced report.
- `lon` (required) longitude as decimal string with exactly 7 decimal places. MUST match the referenced report.
- `expiration` (required) Unix timestamp, always `created_at + 1209600` (14 days)
- `alt` (optional) human-readable fallback. If present, MUST be:
  - `"Roadstr: event confirmed"` when status is `still_there`
  - `"Roadstr: event denied"` when status is `no_longer_there`

Note: The `g`, `lat`, and `lon` tags duplicate the location from the referenced report. This enables
efficient spatial querying of both reports and confirmations using a single `#g` filter. A receiver
MUST drop a confirmation whose coordinates differ from its report's.

### Example

```json
{
  "id": "<32-bytes lowercase hex-encoded SHA-256>",
  "pubkey": "<32-bytes lowercase hex-encoded public key>",
  "created_at": 1700003600,
  "kind": 1316,
  "tags": [
    ["e", "<id of the kind 1315 report>"],
    ["g", "u09t"],
    ["g", "u09tv"],
    ["g", "u09tvw"],
    ["status", "still_there"],
    ["lat", "48.8566140"],
    ["lon", "2.3522219"],
    ["expiration", "1701213200"],
    ["alt", "Roadstr: event confirmed"]
  ],
  "content": "",
  "sig": "<64-bytes hex-encoded signature>"
}
```

## Event Types

The `t` tag value MUST be one of the following strings:

| Value | String              | Default Effective TTL |
|-------|---------------------|----------------------|
| 0     | `police`            | 2 hours              |
| 1     | `speed_camera`      | 30 days              |
| 2     | `traffic_jam`       | 1 hour               |
| 3     | `accident`          | 3 hours              |
| 4     | `road_closure`      | 7 days               |
| 5     | `construction`      | 7 days               |
| 6     | `hazard`            | 4 hours              |
| 7     | `road_condition`    | 6 hours              |
| 8     | `pothole`           | 7 days               |
| 9     | `fog`               | 3 hours              |
| 10    | `ice`               | 6 hours              |
| 11    | `animal`            | 1 hour               |
| 255   | `other`             | 2 hours              |
| 12–254 | reserved          | —                    |

The numeric "Value" column is used only in the compact binary representation. In Nostr events, the
string form is always used in the `t` tag.

Reserved values MUST NOT be mapped to `other` when decoding: that would change the `t` tag and with
it the event ID. Drop the frame instead. (`docs/ecosystem.md` proposes assigning 12
`police_station` and 13 `surveillance_camera` in v2.)

The "Default Effective TTL" is used by clients to determine how long to display a marker. It is NOT
the relay-side expiration (see [Expiration](#expiration)). It applies to events **in this layout
only**: a client that also understands another client's layout must use that layout's lifetimes for
those events, and label their provenance.

## Expiration

This NIP distinguishes between two expiration concepts:

### Relay-side expiration (NIP-40)

The `expiration` tag is always set to `created_at + 1209600` (14 days). This is a fixed constant for
all event types. It tells relays when to garbage-collect the event. The 14-day window is long enough
that confirmations can extend the effective lifetime of an event without the underlying data being
deleted.

### Effective TTL (client-side)

Clients compute an _effective expiration_ from the event type's default TTL and any confirmations
received:

1. Base effective expiry = `created_at + default_ttl` (from the event types table)
2. Each `still_there` confirmation extends the effective expiry: `max(effective_expiry, confirmation.created_at + default_ttl)`
3. Each `no_longer_there` confirmation reduces it: `min(effective_expiry, confirmation.created_at)`

Clients SHOULD NOT display markers whose effective expiry has passed, even if the relay-side
expiration has not yet been reached. Only the latest vote of a key counts; a vote older than its
report, or more than five minutes in the future, is ignored; when two votes share a timestamp,
denials are applied first so that a tie keeps the report alive.

## Geohash Querying

Both reports and confirmations include geohash tags at multiple precision levels to enable efficient
spatial filtering:

| Precision | Cell Size         | Use Case                  |
|-----------|-------------------|---------------------------|
| 4 chars   | ~20km x 20km     | Highway / regional queries |
| 5 chars   | ~5km x 5km       | City-level queries         |
| 6 chars   | ~1.2km x 0.6km   | Neighborhood queries       |

Precise coordinates are provided via the `lat` and `lon` tags. Geohashes MUST be derived from those
coordinates (precision 4, 5, 6, `>=` on the midpoint, alphabet
`0123456789bcdefghjkmnpqrstuvwxyz`); a report whose `g` tags disagree with its coordinates is
malformed.

Clients SHOULD query using the `#g` filter at a precision level appropriate to its context (zoom
level, vehicle speed, etc.) and include the center cell plus its 8 neighbors to avoid boundary
effects. The `since` value of a cold-start query SHOULD cover the longest default TTL (30 days), not
a fixed 24 hours, or valid long-lived reports will be hidden.

Since both reports (1315) and confirmations (1316) have geohash tags, clients can fetch both in a
single query:

```json
["REQ", "road-events", {
  "kinds": [1315, 1316],
  "#g": ["u09tvw", "u09tvx", "u09tvn", "u09tvj", "u09tvs", "u09tvt", "u09tvu", "u09tvv", "u09tvy"],
  "since": 1699993200
}]
```

This returns all reports and their confirmations for the specified area in a single round-trip,
enabling clients to compute effective TTL before displaying markers.

## Compact Binary Representation

For bandwidth-constrained transports (mesh networks, BLE), events can be encoded in a compact binary
format and reconstructed into fully verifiable Nostr events.

Integers are big-endian. (MeshCore's own companion frames are little-endian; the payload carried
inside a channel datagram is opaque to the radio.)

### Report Binary Layout (110 bytes)

```
Offset  Size  Field
------  ----  -----
0       1     Flags byte
1       32    Public key (x-only)
33      4     created_at (uint32, big-endian)
37      4     Latitude (int32, big-endian, degrees x 10^7)
41      4     Longitude (int32, big-endian, degrees x 10^7)
45      1     Event type (registry value 0-11 or 255)
46      64    Schnorr signature (BIP-340)
------
Total: 110 bytes
```

### Confirmation Binary Layout (134 bytes)

```
Offset  Size  Field
------  ----  -----
0       1     Flags byte
1       32    Public key (x-only)
33      4     created_at (uint32, big-endian)
37      32    Referenced event ID
69      1     Status (0 = no_longer_there, 1 = still_there)
70      64    Schnorr signature (BIP-340)
------
Total: 134 bytes
```

A confirmation frame does **not** carry coordinates. The receiver MUST obtain the referenced report
(from cache or a relay) to rebuild the `lat`, `lon` and geohash tags, and MUST reject a confirmation
whose coordinates differ from its report's. A vote heard before its report cannot be verified until
the report arrives; v2 (see `docs/ecosystem.md`) fixes this by carrying the report's coordinates.

### Flags byte

```
Bits 7-4  Version (0 = v1)
Bit  3    Event class (0 = report, 1 = confirmation)
Bit  2    Ephemeral hint (unsigned; never evidence)
Bits 1-0  Reserved, MUST be zero
```

The flags byte is not covered by the signature: anyone relaying a frame can change it. Bit 2 is
therefore only a hint from the sender that a one-time key was used, never proof of anything.

### Coordinate Encoding

- Latitude: `int32(lat x 10^7)` — range [-900000000, 900000000]
- Longitude: `int32(lon x 10^7)` — range [-1800000000, 1800000000]
- Precision: ~1.1 cm

Both directions MUST use integer arithmetic:

- **Encoding** reads the value from the `lat`/`lon` tag text. It MUST NOT compute `lat * 1e7` from a
  `Double`: that truncates and lands one unit short for about 6 % of coordinates, producing frames
  whose signatures cannot verify.
- **Decoding** formats the integer as `-?D+.DDDDDDD` — seven digits, a decimal point, no locale and
  no `printf`-style formatting. `String.format("%.7f")` writes a comma on many phones, which changes
  the signed text.

### Reconstruction

A receiver reconstructs the full Nostr event deterministically.

#### Report Reconstruction

1. Take `pubkey`, `created_at`, position and type from the frame
2. Format `lat`/`lon` from the integers (above) and derive the geohashes at precision 4, 5, 6
3. Look up the type string from the registry value; an unknown value MUST be dropped
4. Set `expiration` to `created_at + 1209600`
5. Set `alt` to `"Roadstr: <type_string> report"`
6. Assemble tags in canonical order and set `content` to `""`
7. Serialize per [NIP-01](01.md): `[0, <pubkey_hex>, <created_at>, <kind>, <tags>, ""]`
8. Compute `id` as SHA-256 of the serialized array
9. Verify the BIP-340 signature of the ID under the public key. **Without a valid signature the
   frame is not an event.**

#### Confirmation Reconstruction

1. Obtain the referenced report and extract its `lat`, `lon` and geohashes
2. Format the coordinates and tags as in a report, with `status` from the frame
3. Set `alt` to `"Roadstr: event confirmed"` / `"Roadstr: event denied"`
4. Set `content` to `""`
5. Serialize, compute the ID and verify the signature as above

### Encoding Rules

An encoder MUST refuse anything that does not round-trip exactly:

```
event -- encode --> frame -- decode --> event'      event'.id == event.id
                                                    event'.pubkey == event.pubkey
                                                    event'.sig == event.sig
```

"Semantically equivalent" is not enough: one different tag, one decimal more or a comma changes the
ID. Refusal reasons (stable names; used by the conformance vectors):

| Code | Meaning |
|---|---|
| `WRONG_KIND`, `MALFORMED_EVENT` | not a well-formed 1315/1316 event |
| `NON_EMPTY_CONTENT` | the report has a comment |
| `NON_CANONICAL` | tag order, spelling, `alt`, `expiration`, geohash or coordinate text differs |
| `UNKNOWN_TYPE` | `t` not in the registry |
| `COORDINATE_RANGE` | outside ±90° / ±180° |
| `TIMESTAMP_RANGE` | `created_at` does not fit in uint32 |
| `REFERENCE_MISMATCH` | a confirmation repeats coordinates other than its report's |
| `SIGNATURE_INVALID` | the ID or signature does not verify |

### Conformance Vectors

[`roadstr-nostr-meshcore-v1.json`](https://github.com/roadstrapp/roadstr-app/blob/main/docs/interoperability/vectors/roadstr-nostr-meshcore-v1.json)
contains 21 valid, 33 invalid, 12 non-representable, 1 tolerated and 5 truncation-recovery cases,
generated by an independent stdlib-only Python program and cross-checked with `@noble/curves`.
Implementations SHOULD run them in CI. Roadbeacon pins at least one of them as a unit test.

## Mesh Transport

The frame is sent as-is in one MeshCore **channel data datagram** (`0x3E` on the companion protocol),
with:

- channel: the `#roadstr` hashtag channel (key = first 16 bytes of `SHA-256("#roadstr")` =
  `b7f4317db7b03aeb563b2c4bdc476d6a`) — a public channel by construction, not a privacy feature;
- `data_type`: `0xFF52` (inside the firmware's unregistered testing range `0xFF00–0xFFFE`) until a
  stable value is registered in `0100–FEFF` with MeshCore;
- payload: the 110 or 134 bytes, nothing added; 165 bytes is the largest payload current companion
  firmware accepts (166–167 pass the host check and then fail permanently, so never use them).

Receivers MUST check length, version, reserved bits, type, coordinate range and status before doing
any signature work, and treat the datagram's channel and `data_type` as untrusted input. The
payload is 110 bytes, so at SF9/125 kHz a report is on air for roughly 0.6 s and at SF10 about
1.1 s; rate limits are part of the protocol, not an optimisation. See `docs/ecosystem.md` for bridge
limits and the hardware validation checklist.

## Kind Allocation

Kinds 1315 and 1316 are in the regular event range (1000-9999) defined by [NIP-01](01.md). They are
currently unassigned in the NIPs kind registry. Registration requires a PR to
[the NIPs repository](https://github.com/nostr-protocol/nips) adding entries to the event kinds
table, referencing this specification.

## References

- [NIP-01](01.md) - Basic protocol flow description
- [NIP-31](31.md) - Dealing with unknown event kinds (`alt` tag)
- [NIP-40](40.md) - Expiration timestamp
- [`docs/ecosystem.md`](../docs/ecosystem.md) - the two implementations, compatibility, v2 proposal
