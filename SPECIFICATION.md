# Roadbeacon — Specification

> **Status.** This is the long design document, kept for its architecture and UI sections. The
> normative description of the event format is [`nips/road-events.md`](nips/road-events.md);
> where the two disagree, the NIP wins. The app was called Roadstr until 0.4.0, and 0.4.0 also
> adopted the event layout of the independent Roadstr client (six decimals, no `alt`, two-tag
> votes); the old layout is deprecated — see [`docs/ecosystem.md`](docs/ecosystem.md).

## 1. Overview

Roadbeacon is a decentralized road event reporting system built on the Nostr protocol. It operates as an OsmAnd plugin (Android) that allows drivers to report, confirm, and view road events (police checks, construction, closures, traffic jams, etc.) directly from the navigation interface. Events are published as signed Nostr events and displayed as color-coded map markers.

A compact binary encoding allows events to be transferred over MeshCore mesh networks in one datagram (117–163 bytes for a report including its comment, 134 for a self-contained vote), allowing off-grid propagation of road reports.

### Design Principles

- **Decentralized**: No central server. Events are published to Nostr relays.
- **Verifiable**: All events are cryptographically signed. Any recipient can verify authenticity.
- **Compact**: Binary representation allows mesh network transfer in one 117–163-byte datagram.
- **Trust-all model (v1)**: No reputation system. All users are trusted equally.
- **Privacy-configurable**: Users choose between persistent identity or ephemeral keys.

---

## 2. Architecture

```
┌─────────────────────────────────────────────────┐
│                   OsmAnd App                     │
│  ┌───────────────────────────────────────────┐  │
│  │           Map View + Navigation            │  │
│  │  - Roadstr map layer (markers)            │  │
│  │  - Report button (widget)                 │  │
│  │  - Context menu (confirm/deny)            │  │
│  └───────────────────────────────────────────┘  │
└──────────────────────┬──────────────────────────┘
                       │ AIDL IPC
┌──────────────────────┴──────────────────────────┐
│              Roadstr Plugin (APK)                │
│                                                  │
│  ┌────────────┐  ┌────────────┐  ┌───────────┐ │
│  │  Nostr     │  │  Location  │  │  Alert    │ │
│  │  Client    │  │  Monitor   │  │  Engine   │ │
│  └─────┬──────┘  └─────┬──────┘  └─────┬─────┘ │
│        │               │               │        │
│  ┌─────┴───────────────┴───────────────┴─────┐  │
│  │            Event Manager                   │  │
│  │  - Create/sign events                     │  │
│  │  - Query by geohash                       │  │
│  │  - Compute effective expiration           │  │
│  │  - Binary encode/decode                   │  │
│  └────────────────────────────────────────────┘  │
└──────────────────────┬──────────────────────────┘
                       │ WebSocket (NIP-01)
              ┌────────┴────────┐
              │  Nostr Relays   │
              └─────────────────┘
```

**Language**: Kotlin (Android native, required for AIDL integration with OsmAnd)

**Key Components**:
- **Nostr Client**: WebSocket connection to relays, event publishing/subscribing (NIP-01)
- **Location Monitor**: Tracks user position via OsmAnd callbacks, triggers area queries
- **Alert Engine**: Proximity detection, directional awareness, sound/vibration alerts
- **Event Manager**: Event creation, signing, binary encoding, expiration calculation
- **AIDL Bridge**: Map layer management, markers, widgets, context menu buttons

---

## 3. Event Format (summary)

The normative specification is [`nips/road-events.md`](nips/road-events.md). This is a short
summary so the rest of this document can be read without switching files.

### 3.1 Kinds

| Kind | Name | NIP-01 class |
|---|---|---|
| 1315 | Road event report | regular |
| 1316 | Confirmation / denial | regular |

### 3.2 Report (kind 1315)

Tags in exactly this order:

| # | Tag | Value |
|---|---|---|
| 1 | `lat` | latitude, exactly 6 decimals (`48.856614`) |
| 2 | `lon` | longitude, exactly 6 decimals (`2.352222`) |
| 3-5 | `g` | geohash of the position at precision 4, 5, 6 |
| 6 | `t` | category string from the registry (§3.4) |
| 7 | `expiration` | NIP-40 retention, `created_at + max(14 days, lifetime)` |
| 8 | `maxspeed` | optional, km/h, 5–300, no leading zeros |

`.content` is an optional comment. There is **no `alt` tag**: the format carries no brand.

### 3.3 Vote (kind 1316)

Exactly two tags: `e` (the report's event ID) and `status` (`still_there` or `no_longer_there`),
with empty `.content`. A vote carries no position and no expiration, which is what makes its
134-byte frame self-contained.

### 3.4 Categories

| Value | `t` | Display lifetime |
|---|---|---|
| 0 | `police` | 4 h |
| 1 | `speed_camera` | 30 d |
| 2 | `traffic_jam` | 1 h |
| 3 | `accident` | 4 h |
| 4 | `road_closure` | 24 h |
| 5 | `construction` | 15 d |
| 6 | `hazard` | 4 h |
| 7 | `road_condition` | 15 d |
| 8 | `pothole` | 7 d |
| 9 | `fog` | 4 h |
| 10 | `ice` | 4 h |
| 11 | `animal` | 1 h |
| 12 | `police_station` | 30 d |
| 13 | `surveillance_camera` | 30 d |
| 14–254 | reserved | — |
| 255 | `other` | 4 h |

The numeric value is the byte carried in the frame. Reserved values MUST NOT be mapped to `other`.

## 4. Compact Binary Format

A report is **117 bytes plus its comment** (0–46 UTF-8 bytes without control characters when
encoding, 163 bytes maximum — the MeshCore companion/local-API datagram limit; decoders accept up to
48 bytes, 165 total). A vote is **134 bytes** and needs no other event to be rebuilt.

| Frame | Layout |
|---|---|
| Report | flags, pubkey(32), created_at(4), latE6(4), lonE6(4), category(1), expiration(4), maxspeed(2, 0 = absent), comment length(1), comment(N), signature(64) |
| Vote | flags, pubkey(32), created_at(4), report id(32), status(1), signature(64) |

Integers are big-endian. Coordinates are degrees × 10^6 in int32. The flags byte is
`version(4) | class(1) | ephemeral hint(1) | reserved(2)`, version 0, class 0 for reports and 1 for
votes. The flags byte is not signed.

Encoding and decoding rules, refusal reasons and the exact rebuild algorithm are in the NIP. The
short version: the encoder rebuilds the event from its own frame and refuses unless tags, key, time,
content and signature are identical; the decoder rebuilds tags by fixed rules, formats coordinates
from integers (never `String.format`), and the caller verifies the signature.

The previous 110-byte frame over the `alt`-bearing layout is obsolete. Its vote frame has the same
length as the new one but a different meaning, so implementations must not mix versions.

### 4.1 Mesh transport

The frame is sent unchanged in one MeshCore channel-data datagram (`0x3E`) on the public `#roadstr`
hashtag channel, with `data_type` `0x0150` ("Road Events", allocated by MeshCore). A bridge must never
change author, ID or signature, must de-duplicate by event ID across transports, and must rate-limit
both directions. See the NIP for parameters and `docs/ecosystem.md` for the bridge design and the
hardware checklist.

Signed frames go on the radio only from their author's device (and back to the relays from any
bridge). What a bridge learned from the relays goes on the radio only as the bridge summary of
section 4.2, never as a full frame, and never as a vote.

### 4.2 Bridge summary (relays → mesh)

A full report on the radio is a 133–181 byte packet that floods the whole mesh, and 96 bytes of it
are the author's key and signature. A driver who only has the mesh gains nothing from them when the
report came from the internet: the bridge could have invented it either way. So a bridge does not
retransmit relay events. It sends a short summary of what is on the relays near someone who is
using the mesh, signed by nobody, and receivers show it as **from a bridge, unverified**.

#### Frame family

All mesh frames of this protocol use `data_type` `0x0150`. The high nibble of the first byte
selects the family; frames of version 0 (section 4) always have it 0, and their decoders refuse any
other value (`BAD_VERSION`), so older apps drop the new frames unread.

| First byte | Frame |
|---|---|
| `0x00`–`0x0F` | signed report or vote (section 4) |
| `0x10` | bridge summary, version 0 |
| `0x20` | summary request, version 0 |
| other | reserved; drop |

The low nibble of `0x10` and `0x20` is the family's version, 0 here; a decoder drops any other
value. No new `data_type` is needed. MeshCore pads `data_type` (2), length (1) and the content to
16-byte blocks, so content of 13, 29 or 45 bytes wastes nothing; both new frames are sized for that.

#### Summary frame (`0x10`, 13 + 8·k bytes for k = 0…4, so 1–5 entries)

```
Offset  Size  Field
0       1     0x10
1       2     ref latitude  (int16, 0.01 degree units, -9000..9000)
3       2     ref longitude (int16, 0.01 degree units, -18000..18000)
5       8·n   n entries, 1 <= n <= 5:
  +0    2     latitude offset  (int16, 0.00001 degree units)
  +2    2     longitude offset (int16, 0.00001 degree units)
  +4    1     category (registry value, section 3.4)
  +5    1     age: time since the report's created_at (duration code)
  +6    1     remaining: time until the report stops being shown (duration code)
  +7    1     votes: high nibble "still there" count, low nibble "gone" count, each capped at 15
```

Integers are big-endian. The content is 5 + 8·n bytes: 13, 21, 29, 37 or 45. One, three and five
entries fill the padding blocks exactly; two or four cost the same airtime as three or five.

Coordinates are exact integer arithmetic on the report's degrees × 10^6 values:
`ref = round(point × 100)` for the point the summary is about, `offset = round((E6 − ref × 10000) / 10)`
(round half away from zero), and the receiver's `E6 = ref × 10000 + offset × 10`. The offset range
±32767 is ±0.32767°, about ±36 km north–south; a report that does not fit is left out of that
summary. The error is under 1 m.

The votes byte is the state of the report as the bridge sees it on the relays, not the votes
themselves. Votes are never put on the radio by a bridge (section 4.3).

**Duration code** (one byte, rounded down to the nearest representable value):

| Code | Meaning |
|---|---|
| 0–59 | 0–59 minutes |
| 60–82 | 1–23 hours (code − 59) |
| 83–112 | 1–30 days (code − 82) |
| 113–254 | reserved; the decoder drops the entry |
| 255 | more than 30 days (age only) |

Decoders drop the whole frame on a bad first byte, a length that is not 5 + 8·n with 1 ≤ n ≤ 5, or
a reference outside its range. They drop one entry for a reserved category, a reserved duration
code, `remaining` = 0 or 255, an age longer than the category's lifetime, or a position outside
±90 / ±180 degrees.

#### Request frame (`0x20`, 13 bytes)

A driver with the mesh but no internet may ask for a summary. Clients send it only when the user
asks, or automatically at most once per 30 minutes, and never while they have relays.

```
Offset  Size  Field
0       1     0x20
1       2     latitude  (int16, 0.01 degree units): the point of interest
3       2     longitude (int16, 0.01 degree units)
5       1     radius in km, 1..25 (0 = the bridge's default, 25)
6       1     since: only reports younger than this (duration code; 255 = any age)
7       2     category mask: bit i = category i (0..13), bit 15 = other (255); 0 = all
9       4     reserved, zero
```

Decoders drop a request whose reserved bytes are not zero, whose radius is over 25, or whose
point is out of range. The point is rounded to about 1 km, which is all the bridge needs and all a
driver should give away on a public channel.

### 4.3 Bridge policy for relays → mesh

Everything here is a hard rule for an implementation, not a tuning default, unless it says so.

1. **Off by default.** A bridge sends summaries only when the user turned on *relay → radio*. Own
   reports and votes (from the driver's device) are a separate switch and are not affected.
2. **Only on demand.** A summary is sent only for an area where, within the last **2 hours**, the
   bridge heard on the radio (not from its own sends):
   - a signed report or vote (section 4) from another node, with the report's position, or for a
     vote the bridge's own position, within 25 km of the bridge's position fix (at most 10 minutes
     old); or
   - a request frame whose point is within 25 km of the bridge.

   Summary frames from other bridges are not demand. Without a fresh position fix the bridge sends
   nothing.
3. **Area and interval.** The area is the geohash-4 cell (about 20 × 39 km) of the demand point. A
   bridge sends at most one summary per area per **30 minutes**, or per **10 minutes** when a
   request asked for it. An area is also counted as served for the same time when another bridge's
   summary for it is heard on the radio, or another bridge won the claim for it (rule 7).
4. **Content.** Up to 5 reports that the bridge verified from the relays, still shown for at least
   5 more minutes, within 25 km of the demand point (or the request's radius) and inside the offset
   range. Left out: reports heard on the radio (the mesh already has them), reports outside the
   request's `since` and category mask, and votes in any form. Newest first. A summary is sent only
   if at least one entry was not in a summary for this area in the last 2 hours; repeats may fill
   the remaining slots. The bridge sends 1, 3 or 5 entries when it can (no padding waste).
5. **Never a plain flood.** The bridge sends **zero-hop** where its radio link allows it (companion
   `CMD_SEND_CHANNEL_DATA` with `path_len` 0) and the demand was heard at 0 hops. Otherwise it sends
   **region-scoped**: the flood region the user configured, or else the one MeshCore Open resolves
   for the demand point (`regions.resolve`). With neither, it does not send. MeshCore Open's local
   API has no zero-hop option yet, so through it summaries are always region-scoped.
6. **Airtime budget.** At most **0.1 % of each rolling hour** (3.6 s) of the bridge's own
   transmissions for summaries, computed from the LoRa time-on-air of the whole packet (5 bytes of
   header, the 16-byte blocks, 4 bytes of region code) with the radio's spreading factor, bandwidth
   and coding rate (SF11, 250 kHz, 4/5 when unknown), MeshCore's preamble (32 symbols up to SF8, 16
   above), explicit header and CRC, and at most 6 summaries per hour. The formula and worked values
   are in the vectors. A 45-byte summary at SF11/250 kHz is about 0.7 s, so the budget allows about 5.
7. **One bridge per area, agreed over Nostr.** Before sending, the bridge publishes an ephemeral
   claim event (kind **21315**) on its relays:

   ```json
   {"kind": 21315, "content": "", "tags": [["g", "<geohash-4 area>"], ["hops", "<n>"], ["expiration", "<now + 120>"]]}
   ```

   `hops` is the hop count at which the bridge heard the demand (255 when unknown). The claim is
   signed by a random key the bridge makes when it starts and never reuses: neither the driver's
   identity nor a stable bridge identity is tied to a place. The bridge subscribes to claims for the
   area before publishing, waits **1.5 s**, then publishes the claim once more (same key and tags,
   `created_at` + 1) and waits **0.3 s** more: relays do not store ephemeral events, so a bridge
   that subscribed after the first claim only sees the second. It counts claims for the same area
   created from 60 s before its first claim to 5 s after, one per key. The winner has the
   fewest hops; on a tie, the lowest `SHA-256(pubkey bytes ‖ area as UTF-8)`, compared as unsigned
   bytes. Only the winner sends; the others count the area as served (rule 3). A bridge that cannot
   reach a relay has nothing from the relays to summarise and sends nothing.
8. **Before sending** the bridge waits 2–10 s at random after the demand, so that it does not answer
   on top of the frame that asked, and drops the summary if another bridge's summary for the area is
   heard meanwhile.
9. **Uplink is unchanged and silent.** Receiving and bridging radio → relays never transmits on the
   radio. Demand only schedules a later summary under the rules above. Summaries and requests are
   never published to the relays.

#### Receiving a summary

Each entry becomes a report shown as **from a bridge, unverified**: position from the frame,
`created_at` = receive time − age, shown until receive time + remaining, the votes byte as its
counts. It has no author, no Nostr ID and no signature, so it cannot be voted on, is never
published, never re-sent and never counts as heard on the radio. Its local ID is
`bridge:` + the first 16 hex digits of `SHA-256(category ‖ E6 lat ‖ E6 lon)` (one byte, two int32
big-endian), so the same report from the next summary replaces the earlier entry. An entry is
ignored when a verified report of the same category is within 100 m, and a verified report that
arrives later replaces any unverified entry of the same category within 100 m.

Conformance vectors: [`nips/vectors/road-bridge-v1.json`](nips/vectors/road-bridge-v1.json), made
by `tools/gen_road_bridge_vectors.py`.

## 5. Expiration & Confirmation System

Two lifetimes, often confused:

* **Relay retention.** The `expiration` tag is `created_at + max(1209600, lifetime)` — 14 days for
  most categories, 30 for the long-lived ones. The floor exists so votes can extend a report; if
  relays dropped a police report after 4 hours, a "still there" at hour five could not extend it.
  Readers accept any parseable `expiration` value, because older clients wrote `created_at +
  lifetime`.
* **Display lifetime.** `created_at + lifetime`, extended by every `still_there`
  (`max(expiry, vote.created_at + lifetime)`) and cut by every `no_longer_there`
  (`min(expiry, vote.created_at)`), never past the event's `expiration`. Only the latest vote of a
  key counts; a vote older than its report or more than five minutes in the future is ignored; ties
  apply denials first; a report is hidden while the last five minutes contain more denials than
  confirmations.

## 6. Geohash & Spatial Querying

### 6.1 Precision levels

| Level | Example (Paris) | Cell size | Use |
|---|---|---|---|
| 4 | `u09t` | ~20 km × 20 km | highway / regional |
| 5 | `u09tv` | ~5 km × 5 km | city |
| 6 | `u09tvw` | ~1.2 km × 0.6 km | neighbourhood |

Reports carry all three; geohashes are derived from the six-decimal position.

### 6.2 Querying

Reports are found by `#g`; votes are found by `#e` for the report IDs in view (a vote has no
position, so `#g` cannot find it). Two subscriptions:

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

Query at the precision that matches zoom and speed (level 4 on a highway, 5 in a city, 6 when
stationary), include the centre cell and its eight neighbours, and use a `since` that covers the
longest lifetime (30 days) on a cold start.

### 6.3 Subscription management

* Maintain a rolling subscription as the user moves; re-subscribe when the cell changes.
* Refresh the `#e` vote subscription when the set of visible reports changes.
* Unsubscribe from cells that are no longer relevant.

## 7. OsmAnd Plugin Integration

### 7.1 AIDL Integration Points

The plugin communicates with OsmAnd via `IOsmAndAidlInterface`:

| AIDL Method | Purpose |
|-------------|---------|
| `addMapLayer` | Create "roadstr_events" layer |
| `addMapPoint` / `updateMapPoint` | Add/update event markers |
| `removeMapPoint` | Remove expired events |
| `addMapWidget` | Add "Report" quick-action widget |
| `addContextMenuButtons` | "Still there" / "No longer there" buttons on markers |
| `registerForUpdates` | Receive periodic position updates |

### 7.2 Map Layer

```kotlin
const val LAYER_ID = "roadstr_events"
const val LAYER_NAME = "Road Events"
const val LAYER_Z_ORDER = 5.5f // Above roads, below UI

osmandHelper.addMapLayer(LAYER_ID, LAYER_NAME, LAYER_Z_ORDER, null)
```

### 7.3 Event Markers

Each active event is displayed as a colored map point:

```kotlin
fun addEventMarker(event: RoadEvent) {
    osmandHelper.addMapPoint(
        layerId = LAYER_ID,
        pointId = event.id,
        shortName = event.type.icon,       // Single char: "P", "!", "X", etc.
        fullName = event.type.displayName, // "Police Check"
        typeName = formatTimeAgo(event),   // "12 min ago"
        color = event.type.color,          // Color int
        location = ALatLon(event.lat, event.lon),
        details = listOf(
            "Reported by: ${event.pubkey.short}",
            "Confirmations: ${event.confirmCount}",
            "Expires: ${formatExpiry(event)}"
        ),
        params = mapOf(
            "roadstr_event_id" to event.id,
            "roadstr_type" to event.type.name
        )
    )
}
```

### 7.4 Color Coding

| Type | Marker Color | Hex |
|------|-------------|-----|
| Police / Speed camera | Blue | `#2196F3` |
| Traffic jam / Accident | Red | `#F44336` |
| Road closure | Dark gray | `#424242` |
| Construction | Orange | `#FF9800` |
| Hazard / Road condition | Yellow | `#FFC107` |
| Custom | Gray | `#9E9E9E` |

### 7.5 Widget (Report Button)

A map widget that opens the report dialog:

```kotlin
osmandHelper.addMapWidget(
    id = "roadstr_report",
    menuTitle = "Report Road Event",
    lightIconName = "roadstr_report_day",
    darkIconName = "roadstr_report_night",
    text = "",
    intentOnClick = createReportIntent()
)
```

### 7.6 Context Menu Buttons

When a user taps an event marker, context menu buttons appear:

```kotlin
osmandHelper.addContextMenuButtons(
    appPackage = BuildConfig.APPLICATION_ID,
    leftButton = AContextMenuButton(
        buttonId = "roadstr_confirm",
        leftTextCaption = "Still there",
        leftIconName = "ic_action_confirm",
        needColorizeIcon = true
    ),
    rightButton = AContextMenuButton(
        buttonId = "roadstr_deny",
        rightTextCaption = "Gone",
        rightIconName = "ic_action_deny",
        needColorizeIcon = true
    )
)
```

---

## 8. User Interface

### 8.1 Main Plugin Activity

The plugin's own UI (separate from OsmAnd map) provides:

1. **Status bar**: Connection status (relay connected/disconnected), active subscriptions
2. **Event list**: Recent events in the area, sorted by distance
3. **Settings**: Configuration (see §9)
4. **Key management**: nsec import/generation, ephemeral key toggle

### 8.2 Report Dialog

Triggered by the map widget button or a menu action:

1. **Type selection**: Grid of event type buttons (icons + labels)
2. **Location**: Auto-filled from current GPS, with option to long-press map
3. **Custom description**: Text field (only shown if "Custom" type selected)
4. **Submit**: Signs and publishes the event

The dialog should be minimal and fast — usable while driving (large touch targets, minimal steps).

### 8.3 Event Detail View

Shown when tapping a marker's info area:

- Event type and icon
- Time reported (relative: "23 min ago")
- Reporter pubkey (truncated, e.g., `npub1a2b...x9z0`)
- Number of confirmations / denials
- Effective expiry countdown
- "Still there" / "Gone" action buttons

---

## 9. Configuration

### 9.1 Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `nsec` | string | — | Private key (hex or bech32). Required. |
| `relays` | string[] | `["wss://relay.damus.io", "wss://nos.lol"]` | Nostr relay URLs |
| `use_ephemeral_keys` | bool | `false` | Generate throwaway key per event |
| `alert_enabled` | bool | `true` | Enable proximity alerts |
| `alert_distance` | int | `500` | Alert distance in meters |
| `alert_sound` | bool | `true` | Play sound on alert |
| `alert_vibration` | bool | `true` | Vibrate on alert |
| `query_speed_threshold` | int | `80` | km/h threshold for query level switch |
| `visible_types` | set | *all* | Which event types to display |
| `osmand_package` | string | `net.osmand.plus` | OsmAnd package to connect to |

### 9.2 Key Management

- **Import**: Paste nsec (bech32) or hex private key
- **Generate**: Create new keypair, display npub for sharing
- **Ephemeral mode**: When enabled, each event is signed with a fresh random key. The ephemeral pubkey is not stored. Events cannot be linked to the user's identity.
- **Key storage**: Encrypted in Android Keystore

### 9.3 Relay Management

- Add/remove relay URLs
- Per-relay connection status indicator
- Read/write toggle per relay (some relays for reading only)
- Connection retry with exponential backoff

---

## 10. Proximity Alerts

### 10.1 Alert Logic

```kotlin
fun checkProximityAlerts(
    userPos: Location,
    userBearing: Float,
    activeEvents: List<RoadEvent>
) {
    for (event in activeEvents) {
        val distance = userPos.distanceTo(event.location)
        val bearing = userPos.bearingTo(event.location)
        val isAhead = angleDifference(userBearing, bearing) < 45f

        if (distance <= settings.alertDistance && isAhead) {
            if (!event.alreadyAlerted) {
                triggerAlert(event)
                event.alreadyAlerted = true
            }
        }
    }
}
```

### 10.2 Alert Behavior

- **Trigger**: When user is within 500m and event is ahead (±45° of travel direction)
- **Sound**: Short distinctive tone (configurable)
- **Vibration**: Two short pulses
- **Visual**: Brief toast/overlay showing event type and distance
- **Cooldown**: Same event does not re-alert for 10 minutes
- **Suppression**: Expired or denied events never alert

### 10.3 Direction Awareness

"Ahead" is defined as within ±45° of the user's current bearing (heading). This prevents alerts for events on the opposite side of a divided highway or behind the user.

If bearing data is unavailable (GPS only, no compass), fall back to computed bearing from last two GPS fixes.

---

## 11. Privacy

### 11.1 Persistent Identity Mode (default)

- Events are signed with the user's configured nsec
- Pubkey is visible on all reports
- Reports can be linked to the same user
- Enables building reputation (future versions)

### 11.2 Ephemeral Key Mode

- A fresh random keypair is generated for each event
- The private key is discarded after signing
- Events cannot be linked to each other or to the user
- Trade-off: No reputation, no ability to update/delete own events

### 11.3 Relay Privacy

- Users should be aware that relay operators can see their IP
- For enhanced privacy: use Tor-capable relays or VPN
- The plugin does not implement Tor by default

---

## 12. Data Flow

### 12.1 Reporting an Event

```
User taps "Report" widget
    → Report dialog opens
    → User selects event type
    → Plugin reads current GPS position
    → Plugin computes geohash (levels 4, 5, 6)
    → Plugin computes expiration (created_at + max(14 days, type lifetime))
    → Plugin assembles Nostr event (kind 1315, canonical tags)
    → Plugin signs event with nsec (or ephemeral key)
    → Plugin publishes to all configured relays
    → Plugin adds marker to OsmAnd map layer
```

### 12.2 Confirming/Denying an Event

```
User taps event marker → context menu appears
    → User taps "Still there" or "Gone"
    → Plugin assembles confirmation event (kind 1316)
    → Plugin signs and publishes
    → Plugin updates effective expiry locally
    → Plugin updates marker opacity/visibility
```

### 12.3 Receiving Events

```
Plugin maintains subscriptions based on user position
    → Relay sends matching events (kind 1315, 1316)
    → Plugin verifies signature
    → Plugin computes effective expiry
    → If active: adds/updates marker on map
    → If expired: removes marker
    → If within alert distance: triggers alert
```

---

## 13. Nostr Protocol Details

### 13.1 Relay Communication (NIP-01)

Standard WebSocket protocol:

```
// Subscribe to events in area
→ ["REQ", "sub1", {"kinds": [1315], "#g": ["u09tv", ...], "since": <ts>}]

// Publish event
→ ["EVENT", <event-json>]

// Close subscription
→ ["CLOSE", "sub1"]
```

### 13.2 Expiration (NIP-40)

The `expiration` tag signals to relays that the event can be garbage-collected after the timestamp. Relays SHOULD NOT serve expired events. This naturally cleans up old road reports.

### 13.3 Unknown Kind Handling

The format has no `alt` tag: adding one would change every event ID and make the event impossible to
frame. A client that does not know kinds 1315/1316 simply ignores them; NIP-31 was traded away for a
brand-free, exactly-rebuildable canonical form.

### 13.4 Event ID Computation

```
id = sha256(serialize([0, pubkey, created_at, kind, tags, content]))
```

Where `serialize` produces minimal JSON (no whitespace, proper escaping per NIP-01).

---

## 14. Version Roadmap

### v1.0 — Core Functionality

- OsmAnd plugin with AIDL integration
- Report events (fixed enum types)
- View events as map markers
- Confirm/deny events
- Proximity alerts (500m, direction-aware)
- Multi-relay support
- Geohash-based area queries
- Key management (persistent + ephemeral)
- NIP-40 expiration

### v1.5 — Mesh Network Support

- MeshCore binary encoding/decoding (§4)
- Mesh-to-relay bridge functionality
- Receive events from mesh, verify, publish to Nostr
- Encode own events to binary, broadcast on mesh channel
- Off-grid reporting capability

### v2.0 — Enhanced Features

- Reputation system (trust scoring based on confirmation accuracy)
- Custom event types with text descriptions
- Event clustering (multiple reports at same location)
- Route-aware querying (query ahead on planned route, not just radius)
- Event photos/media attachments
- Multi-language support

---

## 15. Binary Format — Worked Example

Values from the unit-test vector; the event ID was recomputed from the NIP-01 serialisation by an
independent implementation.

**Inputs:**
- Public key: `96cadb55d2b283776471521f22d5ef9e78c2dc816db69fb6cf70eaa08231b453`
- Timestamp: `1700000000` → `0x6553F100`
- Latitude: `48.856614` → `48 856 614` → `0x02E97E26`
- Longitude: `2.352222` → `2 352 222` → `0x0023E45E`
- Category: `0` (police)
- Geohashes: `u09t`, `u09tv`, `u09tvw`
- Expiration: `1701209600` → `0x65666600`
- Comment: empty; `maxspeed`: absent

**Frame (117 bytes):**
```
00                              # version 0, report, persistent key
96cadb55d2b283776471521f22d5ef9e78c2dc816db69fb6cf70eaa08231b453
6553F100                        # created_at
02E97E26                        # latitude x 10^6
0023E45E                        # longitude x 10^6
00                              # category: police
65666600                        # expiration
0000                            # maxspeed: absent
00                              # comment length: 0
aa…aa                           # 64-byte signature (test material)
```

**Reconstructed event ID:**
`dac79ddf0e4aeb64f56f454497afd54e7926e19a5d3c062f75c25e226f4fcbca`

**Reconstructed tags:**
```
lat=48.856614  lon=2.352222  g=u09t,u09tv,u09tvw  t=police  expiration=1701209600
```

A 46-byte comment adds 46 bytes before the signature (163 total). `maxspeed` is a uint16 after
`expiration`, 0 meaning the tag is absent.

A new shared vector file for this layout is still to be generated; until then this example and the
`BinaryCodecTest` fixture are the contract. The vector file published by the Roadstr client describes
the deprecated layout and cannot be used.

## 16. Error Handling

| Scenario | Behavior |
|----------|----------|
| No relay connection | Queue events locally, publish when connected |
| OsmAnd not installed | Show dialog directing user to install |
| OsmAnd AIDL unavailable | Retry connection with backoff |
| GPS unavailable | Disable report button, show warning |
| Invalid event signature | Silently discard event |
| Expired event received | Ignore, do not display |
| Duplicate event | Deduplicate by event ID |

---

## 17. Security Considerations

- **No injection**: Event content is never interpreted as code
- **Signature verification**: All received events are verified before display
- **Key storage**: nsec stored in Android Keystore (hardware-backed when available)
- **Relay trust**: Relays can withhold events but cannot forge them (signatures)
- **Spam**: v1 has no spam protection beyond NIP-40 expiration. Future versions may add proof-of-work (NIP-13) requirements.
- **Location privacy**: Reporting an event reveals approximate location. Ephemeral keys mitigate identity linkage but not location exposure.

---

## 18. Dependencies

| Dependency | Purpose |
|------------|---------|
| OsmAnd AIDL | Map integration |
| secp256k1-kmp | Schnorr signatures (Nostr) |
| OkHttp | WebSocket connections to relays |
| Geohash library | Coordinate ↔ geohash conversion |
| Android Keystore | Secure key storage |
| kotlinx-serialization | JSON handling |

---

## 19. File Structure (Proposed)

```
app/
├── src/main/
│   ├── AndroidManifest.xml
│   ├── kotlin/app/roadbeacon/
│   │   ├── RoadbeaconApplication.kt
│   │   ├── RoadbeaconService.kt       # Foreground service
│   │   ├── nostr/
│   │   │   ├── NostrClient.kt         # Relay WebSocket client
│   │   │   ├── Event.kt               # Nostr event model
│   │   │   ├── EventSigner.kt         # Signing/verification
│   │   │   ├── Subscription.kt        # REQ/CLOSE management
│   │   │   ├── BinaryCodec.kt         # Strict frame encoder/decoder
│   │   │   ├── RoadEventFormat.kt     # Retention rule and comment limit
│   │   │   └── CanonicalCoordinates.kt # Integer E6 parse/format
│   │   ├── model/
│   │   │   ├── RoadEvent.kt           # Road event domain model
│   │   │   ├── EventType.kt           # Type enum with TTL/colors
│   │   │   └── Confirmation.kt        # Confirmation model
│   │   ├── osmand/
│   │   │   ├── OsmandAidlHelper.kt    # AIDL wrapper
│   │   │   ├── MapLayerManager.kt     # Marker management
│   │   │   └── WidgetManager.kt       # Widget/button management
│   │   ├── location/
│   │   │   ├── LocationMonitor.kt     # Position tracking
│   │   │   ├── GeohashUtil.kt         # Geohash computation
│   │   │   └── AlertEngine.kt         # Proximity alerts
│   │   ├── ui/
│   │   │   ├── MainActivity.kt        # Settings/status activity
│   │   │   ├── ReportDialogActivity.kt # Report dialog
│   │   │   └── KeyManagementFragment.kt
│   │   └── storage/
│   │       ├── EventCache.kt          # Local event cache
│   │       ├── KeyStore.kt            # Secure key management
│   │       └── Settings.kt            # App preferences
│   ├── aidl/net/osmand/aidl/          # OsmAnd AIDL interfaces
│   └── res/
│       ├── drawable/                   # Icons for markers/widgets
│       ├── layout/                     # UI layouts
│       └── values/                     # Strings, colors
└── build.gradle.kts
```
