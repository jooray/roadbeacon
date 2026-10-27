# Android Auto — plan

**Branch:** `feat/android-auto` (separate from the mesh work). Applies to the Roadstr native app,
not Roadbeacon; Roadbeacon is an OsmAnd plugin and stays out of the car head unit.

## Goal

Roadstr navigation on the car screen: turn-by-turn instructions, distance, ETA, speed limit and
voice, with the phone in the pocket. A map surface is desirable but is a second step, not the
minimum.

## What already exists

* `NativeNavigationHudSnapshot` — current/next maneuver, distance, remaining distance/time, ETA,
  speed, speed limit, street name, voice-muted.
* A foreground navigation service that survives screen-off.
* MapLibre Native + OSRM/GraphHopper/ORS routing; voice guidance.
* A complete Compose UI for the phone, which the car cannot host.

## Approach

Use the **Android for Cars App Library** (`androidx.car.app`, Apache-2.0). The car host renders
templates; the app supplies data and, for a map, draws onto a `Surface`.

### Phase 0 — template-only navigation (1–2 weeks)

* `CarAppService` + `Session` + manifest metadata (`androidx.car.app.category.NAVIGATION`,
  `minCarApiLevel`).
* `NavigationTemplate` fed from `NativeNavigationHudSnapshot`: maneuver icon/text, distance,
  `TravelEstimate` (remaining time/distance/ETA), speed limit, “mute” action, “stop” action.
* `NavigationManager.navigationStarted/navigationEnded`, trip updates while active.
* The existing foreground service remains the source of truth; the car session subscribes to the
  HUD state (a small repository/binder, not a second navigation engine).
* Voice: reuse the existing guidance; handle audio focus and ducking.
* No map surface. The host shows the instruction card; this is enough for glanceable guidance.
* Acceptance: Desktop Head Unit (DHU) runs a route; disconnect/reconnect; app backgrounded;
  navigation stopped from the car ends it on the phone.

### Phase 1 — map surface (3–6 weeks)

* `SurfaceCallback` on `NavigationTemplate`/`MapTemplate`.
* Follow MapLibre's MIT sample (`maplibre/MapLibre-Android-Auto-Sample`): an offscreen `MapView`
  in texture mode renders into a `TextureView`; its bitmap is drawn to the car `Surface` on frame
  callbacks (the sample reports ~30 fps).
* Render at a capped rate (15–20 fps) to bound battery/CPU; pause when the surface is destroyed.
* Draw the route, vehicle position and next maneuver; keep the template’s instruction card on top.
* Known cost: a bitmap copy per frame and a second MapLibre instance. Measure on the target phone
  before promising smoothness.
* Alternative if that is too slow: a hand-drawn Canvas mini-map (route polyline, arrow, next turn)
  with no base tiles — cheaper, less useful.

### Phase 2 — polish (1–2 weeks)

* Lane guidance only if a lane model is added to the router (not present today).
* Day/night styling from the car’s night mode.
* Localisation of car strings; large-text and RTL checks.
* Reconnect semantics when the car disconnects mid-route; error templates for no GPS / no route.
* Battery/thermal measurements with the map surface active.

### Phase 3 — distribution

* Sideload / Zapstore: works once Android Auto developer mode allows unknown sources on the phone
  (“Unknown sources” in the AA developer settings).
* Google Play: the navigation category needs Google’s Android Auto review; a template-only build
  may not satisfy it — plan for the map before submitting.
* Android Automotive OS: the same `car_common` code can be reused; not part of this branch.

## Architecture sketch

```
NativeNavigationForegroundService
        │  HUD snapshot (StateFlow)
        ▼
  NavigationCarRepository  ──►  RoadstrCarAppService
                                     │
                              CarSession (LifecycleOwner)
                                     │
                       NavigationTemplate + SurfaceCallback
                                     │
                          MapLibreCarRenderer (phase 1)
```

The car session must never own navigation state; it reads the same state the phone UI reads. A
process death restarts the service and the session re-attaches.

## Licensing

* `androidx.car.app`: Apache-2.0.
* MapLibre Android Auto sample: MIT; copy the approach, keep the notice.
* **Do not port OsmAnd's car code**: OsmAnd is GPLv3 and Roadstr is MIT; combining them would
  relicense the whole app. The architecture can be implemented independently.

## Risks

| Risk | Mitigation |
|---|---|
| Map surface too slow via bitmap copy | cap fps, measure early; fall back to template-only |
| Play review rejects a map-less navigation app | ship template-only to sideload users; add the map before Play submission |
| Car host process lifecycle | session reads a repository, never owns state |
| Two MapLibre instances (phone + car) | pause the phone map while the car surface is active |
| AA developer mode needed for sideload | document it in the release notes / website |

## Task list (phase 0)

1. Add `androidx.car.app` dependency and manifest service/metadata.
2. `RoadstrCarAppService` + `RoadstrCarSession`.
3. `NavigationCarRepository` exposing the HUD snapshot.
4. Build `NavigationTemplate` updates (maneuver, distance, ETA, speed limit).
5. Wire actions: mute, stop, open phone.
6. DHU test script and a manual checklist.
7. Screenshots and a short doc page.
