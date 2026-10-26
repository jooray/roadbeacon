# Roadbeacon Android

Decentralized road event reporting app using Nostr (custom kinds 1315/1316) and OsmAnd map
integration. Package `app.roadbeacon`; formerly `com.roadstr`.

## Build

```bash
# Android Studio's bundled JDK first; if Gradle fails with a bare version string, use JDK 17:
export JAVA_HOME="$HOME/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x.2/jdk-17.0.17+10/Contents/Home"
./gradlew assembleRelease
```

Output APK: `app/build/outputs/apk/release/app-release.apk`

## Signing

Release builds are signed automatically. Credentials are read from `keystore.properties` (gitignored)
in this directory:

```properties
storeFile=/path/to/keystore.jks
storePassword=...
keyAlias=my-key-alias
keyPassword=...
```

The keystore file itself lives outside the repo at the path specified in `storeFile`.

## Architecture

- **NostrClient** — WebSocket connections to relays with exponential backoff reconnect
- **RoadbeaconService** — Foreground service: location monitoring, Nostr subscriptions, OsmAnd AIDL
- **EventCache** — In-memory cache with 60s cleanup timer; callbacks for map removal on expiry
- **MapLayerManager** — Manages OsmAnd map layer points via AIDL
- **AlertEngine** — Proximity-based notifications for nearby road events
- **BinaryCodec** — Strict frame codec: reports 117–165 bytes, votes 134, refuses anything that does not round-trip
- **CanonicalCoordinates** — Integer E6 parse/format; no `String.format`, no truncation
- **RoadEventFormat** — retention rule (`created_at + max(14 d, lifetime)`) and the 48-byte comment limit

## Key Nostr Kinds

- **1315** — Road event report: `lat, lon, g, g, g, t, expiration[, maxspeed]`
- **1316** — Confirmation/denial: `e, status` only (no position, no expiration)

The old `alt`-bearing layout is deprecated. Do not write it, do not add a reader for it.

## Testing

```bash
./gradlew test
```

`BinaryCodecTest` pins a frame and event ID computed by an independent implementation. Changing
canonicalisation without changing that test's expectations is the intended way to notice a mistake.
