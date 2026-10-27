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
hashtag channel with `data_type` `0xFF52` until a stable value is registered. A bridge must never
change author, ID or signature, must de-duplicate by event ID across transports, and must rate-limit
both directions. See the NIP for parameters and `docs/ecosystem.md` for the bridge design and the
hardware checklist.

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
