# Android Auto — plan

**Branch:** `feat/android-auto` (separate from the mesh work). Applies to the Roadstr native app,
not Roadbeacon; Roadbeacon is an OsmAnd plugin and stays out of the car head unit. This plan belongs
with Roadstr: propose it there (issue first, per that repo's rules) and keep only a link here once it
is accepted.

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

### Phase 0 — navigation template with a drawn mini-map (2–3 weeks)

* `CarAppService` + `Session` + manifest metadata (`androidx.car.app.category.NAVIGATION`,
  `minCarApiLevel`).
* `NavigationTemplate` fed from `NativeNavigationHudSnapshot`: maneuver icon/text, distance,
  `TravelEstimate` (remaining time/distance/ETA), speed limit, “mute” action, “stop” action.
* `NavigationManager.navigationStarted/navigationEnded`, trip updates while active.
* The existing foreground service remains the source of truth; the car session subscribes to the
  HUD state (a small repository/binder, not a second navigation engine).
* Voice: reuse the existing guidance; handle audio focus and ducking.
* **A navigation app always draws its own map.** `NavigationTemplate` has no host-drawn background:
  without `SurfaceCallback` the instruction card floats over a black screen, which reads as broken and
  will not pass review. Phase 0 therefore draws a cheap Canvas mini-map on the surface (route
  polyline, vehicle arrow, next manoeuvre point; no tiles) — what was the phase-1 fallback.
* Map every router manoeuvre to a `Maneuver` type and a `CarIcon`; handle rerouting
  (`NavigationTemplate.Builder.setLoading`), arrival, and `NavigationManagerCallback.onStopNavigation`.
  This mapping, not the template, is most of the work and the reason for 2–3 weeks rather than 1–2.
* **Road alerts with votes.** The point of this ecosystem: when a report is ahead on the route, show
  it with `AppManager.showAlert` (car API ≥ 5) — category, distance, and two actions, *Still there*
  / *Gone*, which sign and publish the 1316 vote exactly like the phone UI. Fall back to a
  `CarToast` + navigation-template action strip on older hosts.
* Acceptance: Desktop Head Unit (DHU) runs a route; disconnect/reconnect; app backgrounded;
  navigation stopped from the car ends it on the phone.

### Phase 1 — map surface (3–6 weeks)

* `SurfaceCallback` on `NavigationTemplate`/`MapTemplate`.
* Prototype first: a `VirtualDisplay` created on the car `Surface` with a `Presentation` that
  hosts an ordinary MapLibre `MapView`. The map renders straight into the car surface on the GPU, no
  per-frame copy. Measure it on the target phone against the next option.
* Otherwise follow MapLibre's MIT sample (`maplibre/MapLibre-Android-Auto-Sample`): an offscreen
  `MapView` in texture mode renders into a `TextureView`; its bitmap is drawn to the car `Surface` on
  frame callbacks (the sample reports ~30 fps) at the cost of a bitmap copy per frame.
* Render at a capped rate (15–20 fps) to bound battery/CPU; pause when the surface is destroyed.
* Draw the route, vehicle position and next maneuver; keep the template’s instruction card on top.
* Known cost: a bitmap copy per frame and a second MapLibre instance. Measure on the target phone
  before promising smoothness.
* If both are too slow, keep the phase-0 Canvas mini-map.

### Phase 2 — polish (1–2 weeks)

* Lane guidance only if a lane model is added to the router (not present today).
* Day/night styling from the car’s night mode.
* Localisation of car strings; large-text and RTL checks.
* Reconnect semantics when the car disconnects mid-route; error templates for no GPS / no route.
* Battery/thermal measurements with the map surface active.

### Phase 3 — distribution

* Sideload / Zapstore: works once Android Auto developer mode allows unknown sources on the phone
  (“Unknown sources” in the AA developer settings).
* Google Play: the navigation category needs Google’s Android Auto review; submit with the tiled
  map (phase 1), not the mini-map.
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
| Map surface too slow | try VirtualDisplay first, then the bitmap sample; cap fps; keep the mini-map |
| Play review rejects a mini-map navigation app | ship phase 0 to sideload users; add the tiled map before Play submission |
| Alerts distract the driver | one alert at a time, only ahead on the route, auto-dismiss; votes are one tap |
| Car host process lifecycle | session reads a repository, never owns state |
| Two MapLibre instances (phone + car) | pause the phone map while the car surface is active |
| AA developer mode needed for sideload | document it in the release notes / website |

## Task list (phase 0)

1. Add `androidx.car.app` dependency and manifest service/metadata.
2. `RoadstrCarAppService` + `RoadstrCarSession`.
3. `NavigationCarRepository` exposing the HUD snapshot.
4. Build `NavigationTemplate` updates (maneuver, distance, ETA, speed limit) and the manoeuvre map.
5. Canvas mini-map on the surface (`SurfaceCallback`), capped frame rate.
6. Road alerts with *Still there* / *Gone* actions.
7. Wire actions: mute, stop, open phone.
8. DHU test script and a manual checklist.
9. Screenshots and a short doc page.
