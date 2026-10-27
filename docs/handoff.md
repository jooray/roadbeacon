# Handoff and review request

**Date:** 2026-10-27
**Scope:** rename to Roadbeacon, one shared road-event wire format, a new MeshCore frame, a
MeshCore Open local API (fork), bridging in both apps, Android Auto plan.

This document is for another agent (or a human) picking this up. It lists where everything is,
which commit does what, what is proven and what is not, and ends with concrete review questions.

---

## 1. Repositories and directories

| What | Remote | Local path |
|---|---|---|
| Roadbeacon (this repo) | `github.com/jooray/roadbeacon` | `/Users/juraj/projects/roadstr` (directory name is historical) |
| Roadstr (independent app, fork) | `github.com/jooray/roadstr-app` (fork of `roadstrapp/roadstr-app`) | `/private/var/folders/79/vj3tpl0x40v10jwzbhvkw61c0000gp/T/opencode/roadstr-app` (temporary opencode checkout) |
| MeshCore Open (fork) | `github.com/jooray/meshcore-open` (fork of `zjs81/meshcore-open`) | `~/projects/meshcore-open` |

Roadbeacon layout:

```
roadbeacon-android/            Kotlin app (OsmAnd plugin)
  app/src/main/kotlin/app/roadbeacon/
    nostr/                     BinaryCodec.kt, CanonicalCoordinates.kt, EventSigner.kt,
                               RoadEventFormat.kt, NostrClient.kt
    mesh/                      MeshCoreApiClient.kt (loopback WS + P-256 pairing),
                               MeshBridge.kt (verify, dedupe, area, parking, bridges)
    storage/                   Settings.kt (encrypted prefs), EventCache.kt
    ui/                        SettingsFragment.kt (MeshCore section), MainActivity.kt, dialogs
roadbeacon-web/                Vanilla JS client; js/roadbeacon.js is the event library
nips/road-events.md            Normative spec (format, frame, transport references)
nips/roadstr.md                Redirect stub kept for external links
docs/ecosystem.md              The two apps, compatibility, migration
docs/meshcore.md               MeshCore transport: channel, data type, bridge, pairing, status
docs/android-auto.md           Android Auto plan (separate branch)
SPECIFICATION.md               Long design doc; format chapters now point at the NIP
```

Roadstr layout (shared Kotlin sources; the app is built from `native-android/`):

```
android/app/src/main/kotlin/app/roadstr/
  core/protocol/interop/       CompactRoadCodec.kt, CompactCoordinates.kt, CompactRoadType.kt,
                               ExternalRoadReportAdapter.kt, InteropLifetime.kt, RoadEventLedger.kt
  service/meshcore/            MeshCoreClient.kt (BLE), LocalApiMeshClient.kt (MeshCore Open),
                               MeshRadioClient.kt, SwitchableMeshRadio.kt, RoadMeshBridge.kt,
                               MeshRuntime.kt
  service/nostr/               NativeRoadEvent.kt (reader ignores old alt layout), NativeRoadEventService
  feature/home/                NativeShellMeshHost.kt (transport toggle/status), NostrController
  feature/settings/            NativeSettingsPanel/Presentation (MeshCore section, no Interop section)
android/app/src/test/kotlin/app/roadstr/core/protocol/interop/
                               SharedLayoutCodecTest.kt, CompactCoordinatesTest.kt,
                               InteropLifetimeTest.kt, RoadEventLedgerTest.kt
docs/interoperability/MESHCORE_NOSTR.md   historical banner + old design (kept for the audit)
```

MeshCore Open layout:

```
lib/services/local_api/
  local_api_server.dart        WS server, auth/pairing, subscriptions, send, replay, regions
  local_api_pairing.dart       pairing broker + persisted paired clients
  local_api_crypto.dart        P-256 verify, fingerprints
  local_api_archive.dart       interests + bounded channel-data archive
  local_api_service.dart       settings (persisted), start/stop, revoke, archive, regions
  local_api_host.dart          connector adapter
  mesh_regions.dart            region catalogue + nearest-centre resolver
lib/models/mesh_event.dart     ChannelTextEvent / ChannelDataEvent / DirectTextEvent / AdvertEvent
lib/models/channel_data_packet.dart
lib/screens/local_api_screen.dart   settings UI (toggle, paired apps, regions, clear)
assets/mesh_regions.json       editable region map (SK/CZ seed)
documentation/LOCAL_API.md     protocol, security, history, regions, future extensions
```

APKs for device testing: `~/Downloads/roadbeacon-testing/`
(`meshcore-open-local-api.apk`, `roadbeacon-0.4.0-mesh.apk`, `roadstr-roadtest-mesh.apk`).

---

## 2. PRs and issues

| # | Repo | What |
|---|---|---|
| [PR 7](https://github.com/roadstrapp/roadstr-app/pull/7) | roadstr-app | **Shared layout + new frame + MeshCore Open radio**; one squashed commit `b9318b6`, base `main` |
| [PR 5](https://github.com/roadstrapp/roadstr-app/pull/5) | roadstr-app | Hide the navigation HUD on the offline-maps screen (small bug fix) |
| [#6](https://github.com/roadstrapp/roadstr-app/issues/6) | roadstr-app | Slovakia/Czechia missing from the offline map catalog (+ Geofabrik Shortbread 404 finding) |
| [#4](https://github.com/roadstrapp/roadstr-app/issues/4) | roadstr-app | Switch the radio codec to the shared frame (now implemented by PR 7) |
| [#2](https://github.com/roadstrapp/roadstr-app/issues/2), [#3](https://github.com/roadstrapp/roadstr-app/issues/3) | roadstr-app | Expiration floor and vote extension (policy, still open) |
| [#616](https://github.com/zjs81/meshcore-open/issues/616) | meshcore-open | Local API proposal; discussion of transport, security, library relationship |

The Roadstr maintainer discussed PR 7 privately; the repo’s “discuss first” rule is satisfied.

---

## 3. Commits

### Roadbeacon (`/Users/juraj/projects/roadstr`, branch `main`, pushed)

| Commit | What |
|---|---|
| `0b0e64d` | Rename to Roadbeacon, applicationId `app.roadbeacon`, strict frame codec |
| `93e8394` | `docs/ecosystem.md`, corrected NIP/SPEC |
| `48b96b1` | Pin the Roadstr revision the review was based on |
| `966fea2` | **Adopt the shared layout, drop the old layout**, new frame, categories, web |
| `24bcefa` | AGENTS updates |
| `4b61d1f` | Pin frame offsets with maxspeed + comment |
| `d498c3f` | Web links to `/static/roadbeacon` (old path redirects) |
| `13ed833` | 163-byte BLE datagram note |
| `fb30861` | Retire old listing + screenshots |
| `5b6e405` | **MeshCore bridge via MeshCore Open local API** (client, bridge, settings, wiring) |
| `c0bdae6` | **Only accept the current layout** (`BinaryCodec.isCanonical`, web) |
| `e256eed` | `docs/meshcore.md`, `docs/android-auto.md` |
| `eb5301c` | **40 km filter, vote parking, live mesh toggle** |
| `a5269b3` | **`since` replay cursor** in encrypted prefs |
| `29176c2` | (Juraj) project npub in the web relay dialog and README |

### Roadstr (checkout at the temp path; `mesh-local-api` branch, PR 7 is a squash)

| Commit | What |
|---|---|
| `cf4c4d1` | Shared layout/frame in `CompactRoadCodec`, `CompactCoordinates`, `CompactRoadType`; local API client; runtime switch |
| `1bdde33` | roadtest: local API needs no BLE; settings no longer hides the section |
| `2c21c51` | `SwitchableMeshRadio`: user chooses MeshCore Open or BLE; settings toggle |
| `45eafa7` | Apply the transport choice live (effect key) |
| `cf7d7cc` | Never hide MeshCore section in local-API mode |
| `5e69e80` | Always render the transport switch (warning instead of early return) |
| `64ffdc4` | Ignore old-layout events (`alt`) |
| `a169f17` | **Remove old codec tests, twin publisher, Interop UI; docs banner/README** |
| `9668952` | `since` cursor for MeshCore Open replay |
| `c1cb828` | `SharedLayoutCodecTest` with real BIP-340 signatures |
| `b9318b6` | Squashed PR commit (same content as the branch) |

### MeshCore Open (`~/projects/meshcore-open`, branch `feat/local-api`, pushed)

| Commit | What |
|---|---|
| `7fd38cba` | Generic `GRP_DATA` API + loopback WS bridge (connector, server, tests, docs) |
| `e80e9d86` | GUI pairing with P-256 keys (no token/QR) |
| `8d4c29b2` | Pairing dialog mounted under the Navigator |
| `a289eb0e` | LOCAL_API.md status update |
| `ade7d986` | **Settings screen, persisted toggle, paired-app revoke, archive + replay** |
| `54d5bdb5` | **Per-message `region`, `regions.list/resolve`, static region map** |

---

## 4. What is done, and what is not

**Wire format (kinds 1315/1316)**

* One layout: `lat, lon, g, g, g, t, expiration[, maxspeed]`, six decimals, no `alt`; votes are
  `e, status`.
* Both clients write it. Both ignore the deprecated layout (`alt` tag). No twin copies, no merge.
* Roadbeacon has frame/canonical tests (31 total in its suite). Roadstr has shared-layout codec
  tests with real signatures (30 interop tests in the native harness).

**Frame**

* Report 117 bytes + comment (0–48, 165 max); vote 134, self-contained.
* Offsets, maxspeed, expiration and UTF-8 rules are in `nips/road-events.md`.
* Roadbeacon tests pin a frame + event ID; Roadstr tests round-trip with real keys.

**MeshCore Open**

* Loopback WS API, P-256 pairing with a consent dialog, subscriptions, send methods, rate limits,
  bounded queues.
* Settings UI: on/off (persisted), paired apps + revoke, region list, clear data.
* Archive only for `(channel, dataType)` interest pairs a paired app has subscribed to; 200
  frames / 24 h; `events.subscribe {since}` replays after the ack.
* Per-message `region` override; `regions.list` / `regions.resolve` over `assets/mesh_regions.json`
  (nearest centre within radius; country fallbacks).
* 11 tests green; `flutter analyze` clean.

**Bridging**

* Roadbeacon: verify signature, dedupe ledger, show, radio→Nostr unchanged, own events→radio,
  relay→radio inside 40 km, votes parked 15 min, replay cursor, live toggle.
* Roadstr: same codec via its `RoadMeshBridge`, MeshCore Open or BLE, `since` cursor.
* **No physical radio has been tested.** No hardware was available.

**Android Auto**

* Plan only: `docs/android-auto.md` (template-only phase 0, MapLibre Surface phase 1). Branch
  `feat/android-auto` not created yet.

**Not done**

* Physical radio validation (pairing, send/receive, firmware ≥ 1.11 for channel data, airtime).
* Roadbeacon release with the mesh features (published 0.4.0 predates them; versionCode still 4).
* MeshCore Open upstream merge; its APK is debug-signed (484 MB).
* Roadstr full test suite in this checkout (the Flutter module’s build task fails); only the
  interop suite was run, via a temporary test source set in `native-android` (not in PR 7).
* A bridge test for the new frame in Roadstr (the old-frame `RoadMeshBridgeTest` was removed).
* `regions` are not used by Roadbeacon/Roadstr yet; only the API supports them.
* Max hop/TTL is not settable: firmware constant `MAX_PATH_SIZE=64`, no companion command.

---

## 5. Review request

Please review for correctness and design, not just style. Specific questions:

1. **Codec.** Compare `roadbeacon-android/.../BinaryCodec.kt` with Roadstr’s
   `CompactRoadCodec.kt`. Same canonical rules? Any divergence in offsets, comment limit,
   maxspeed, expiration handling, UTF-8 validation or signature checks? Any input that would
   round-trip in one and not the other?
2. **Pairing/security.** P-256 verify accepts DER and raw `r||s`; nonce lifetime 2 minutes; key
   stored in memory/prefs. Any replay, downgrade or auth-bypass path? Is the fingerprint UX
   sufficient given the name is self-declared? Any risk in the archive exposing old positions?
3. **Local API.** Subscription filters, `since`/replay ordering, rate limits, outbox bound,
   archive interests after revoke, thread safety in `LocalApiServer`/`_ApiClient`. Is closing a
   slow consumer at 256 queued messages sound? Are unknown regions safe to pass through?
4. **Bridge.** Ledger/parking/area logic in `MeshBridge.kt`; loops between radio→relay→radio;
   ordering of own event vs publish; expiry; votes for unknown reports. Does the 40 km check use
   the right position (driver, not event)? What happens with no GPS fix?
5. **Dual transport.** `SwitchableMeshRadio` state mirroring, switching while a send is in flight,
   BLE permission/consent flow, and service lifecycle. Does the settings effect start/stop the
   right client on every path?
6. **Roadstr PR 7.** Is the change set acceptable as one PR? Is removing `RoadMeshBridgeTest`
   without a replacement acceptable, or should a shared-layout bridge test be added first? Is the
   `NativeRoadTestActivity` change (always local API) appropriate for the app, or should it be a
   setting?
7. **Docs.** Is `nips/road-events.md` internally consistent? Are `docs/meshcore.md` and
   `documentation/LOCAL_API.md` (fork) describing the same bytes and limits? Anything missing for
   a third implementer?
8. **Android Auto plan.** Is phase 0 really 1–2 weeks? Is the MapLibre sample (offscreen
   `MapView` → `TextureView` bitmap → car `Surface`) the right approach, or is there a better
   open option?

Suggested output from the reviewer: a prioritized list of defects/risks, then design suggestions,
then test gaps. Do not rewrite large parts without checking with Juraj.

---

## 6. Build and test

```bash
# Roadbeacon Android (JDK 17; Studio's bundled JDK may be too new for Gradle 8.5)
cd /Users/juraj/projects/roadstr/roadbeacon-android
export JAVA_HOME="$HOME/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x.2/jdk-17.0.17+10/Contents/Home"
./gradlew test assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk

# Roadbeacon web: open roadbeacon-web/index.html (no build)

# Roadstr (native app; roadtest package app.roadstr.roadtest)
cd <roadstr checkout>/native-android
./gradlew :app:assembleDebug
# APK: ../build/native-roadtest/app/outputs/apk/debug/app-debug.apk

# Roadstr interop tests (local test source set, not in PR 7)
./gradlew :app:testDebugUnitTest --rerun-tasks

# MeshCore Open
cd ~/projects/meshcore-open
flutter analyze --fatal-infos --fatal-warnings
flutter test test/services/local_api
flutter build apk --debug --dart-define=LOCAL_API_ENABLED=true
```

Local-only files not in any PR: `android/local.properties` and the test source set in
`native-android/app/build.gradle.kts` (used to run the Roadstr interop tests here).
