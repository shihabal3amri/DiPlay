# FX11 OEM overlay composition investigation — 2026-10-18

## Confirmed physical results

Device: FX11_LOW, Android 11, Android display 4 / physical local:131, 1920×532.
User confirmed parked before the comparisons.

| Build / path | Vehicle feedback | Physical result |
| --- | --- | --- |
| v24, transparent Presentation 2037 | 3 | CarPlay map visible, OEM gauges absent, black outside map |
| v25, independent WindowManager overlay 2038, alpha 1.0 | 3 | User confirmed OEM gauges absent and black background |
| v25, same overlay, alpha 0.8 | 3 | User confirmed OEM gauges still absent and black background |

The v25 comparisons had no active phone map/navigation/music at the time of diagnostic capture. They establish failure to preserve OEM gauges even for an otherwise empty transparent view, not success/failure of phone reconnection.

After both failures, the existing controller restored OEM state 3→0; VHAL readback confirmed 0. A successful mode-3 write alone is **not** evidence that OEM and Android layers were composed.

## AutoDisplay / AutoService evidence

Decompiled files under `work/autodisplay-cluster-jadx/sources/` (outside this repository):

- `tn.java:654–681`: physical-display context, WindowManager, transparent FrameLayout, type 2038, flags 536, pixel format -3.
- `qn.java:59–76`: alternative Presentation type 2037, window alpha 0.8, transparent background, dimAmount 0, clear DIM_BEHIND.
- `model/ScreenConfig.java:19–31`: explicit screen_N parses to Android display ID N; `tn.java:457` looks up that ID first. Enumeration fallback applies only without a valid parsed ID.
- HUD_MAP uses TextureMapView / TextureView in both hooked-map and built-in-navigation paths. Retain hardware acceleration for DiPlay's TextureView.
- AutoService property 30010 maps to FX11 feedback 561023759 and request 557876790; value legend 0 OEM, 1 custom, 3 overlay. This catalog does not demonstrate support on this firmware.
- HAB→QNX specialized path explicitly warns it is for Galaxy L6/L7. Do not execute its receiver/loader or assume it applies to Boyue L.
- Bounded searches found no additional ordinary-map SurfaceControl/SurfaceFlinger/sysprop alpha prerequisite. This is not proof none exists elsewhere or in firmware.

## Android output evidence

v24 PixelCopy outside content yielded alpha 0. v25 SurfaceFlinger reports RGBA_8888, isOpaque=false, device composition, type 2038. Thus neither another transparent background nor window alpha 0.8 alone repairs the observed physical output.

The device exposes two public, ON, PRESENTATION-capable outputs at 1920×532:

- Android 3 → local:130, no Android content before this comparison.
- Android 4 → local:131, existing custom dashboard path.

v26 adds a non-exported, debug-only diagnostic action to compare these outputs. It is not persisted, does not change the normal display-4 path, and refuses a switch while the service owns active/pending projection. Invalid/private/off/wrong-size displays are rejected. Output loss does not silently fall back to another screen. Only the existing explicit toggle writes vehicle mode.

Physical output selection remains unverified until the user observes the result. Three complete dashboard styles and ordinary CarPlay keep their existing rendering and connection paths.

## Artifacts

Outside repository, under `outputs/fx11-v25-overlay-investigation/`: baseline/device inventory, mode readbacks, window/SurfaceFlinger dumps, v25 alpha comparison diagnostics and screenshots. Android screenshots do not capture the final QNX OEM composition.

v25 full build, lint and tests passed: 3315 tests, zero failures/errors, one skip. APK signer and all 71 runtime assets/native libraries match v24, including the offline authentication assets.

v26 full build, lint and tests passed: 3317 tests, zero failures/errors, one skip. Signer and all 71 runtime assets/native libraries match v25. Before installation, ADB disconnected. v26 is packaged but not yet installed; final physical composition remains unresolved. Last confirmed vehicle mode was 0 (OEM restored).

The device's `DirectRenderingCluster.apk` was pulled read-only and decompiled. `ClusterDisplayProvider.java` obtains an output from CarOccupantZoneManager (driver zone, display type 2), without a hardcoded 3/4 mapping. It does not establish which of these two Bosch/QNX outputs supports OEM underlay.
