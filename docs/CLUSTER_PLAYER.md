# Local cluster player (experimental)

Based on v0.2.22. Settings → Advanced → dashboard map card → Dashboard content → Player (experimental). The existing projection switch must be enabled; it opens the vehicle's projection area for either page. Switching pages is live and retains all map/content/card settings. Map remains the default.

The player replaces the map page, not an overlay. The map page is GONE and its output surface is released; the host does not attach that decoder or map mirror surfaces in player mode. The controller sends stopUI for stream 111 and retries on the existing policy ticker if the event channel is not ready. Returning to Map sends showUI and forceKeyFrame for the previously selected iPhone content, subject to the existing navigation-mode pause policy. Map mirrors/launcher embeddings pause too: they share that stream. The main CarPlay display/audio are unaffected.

The local canvas shows a square cover, title, artist, album and elapsed/duration when provided by iAP2. Missing values do not become invented times. Position advances from monotonic time between phone updates and stops on pause. Artwork callbacks do not reset its clock. Disconnect clears metadata and cover through the existing media owner. Three radial gradients use a bounded cover sample once per changed bitmap, then move at up to 20 fps while playing and visible. No per-frame blur or bitmap allocation is used. Performance savings require vehicle measurement, not an inferred percentage.

Speed is enabled by default in this page and has its own toggle, preserving the map speedometer setting. It uses the existing independent vehicle speed reader (ADB), not a media/video timestamp. The number and km/h share the metadata typeface and have no box. Missing/invalid/stale readings show — within two seconds. Old reader callbacks cannot restore speed after disabling or detaching. Original speed/readout and telltale visibility still depend on vehicle projection/cropping.

Existing Presentation and ADB Activity routes reuse their established projection ownership, layout bounds and recovery mechanisms. Legacy ADB routing can confirm the local player page without requiring a map video frame. A deliberate map stop keeps the projection while the main session is active; genuine disconnect still allows OEM handback. DiLink 5.1 retains its automatic theme/card visibility rules.

## Validation

Automated checks cover full-page switching and map preference retention, speed expiry/callback ownership, progress extrapolation/pause/clamping, disconnect clearing, actual stopUI/showUI/forceKeyFrame event commands, event-channel retry, no ADB dependency for player pause, and cluster handback. Native-renderer captures cover 1280×480, 1920×720, a narrow 600×720 area, large fonts, empty playback, compact/full Spanish settings and Arabic settings.

Vehicle acceptance remains required: connect the iPhone, select Player, confirm live metadata/cover/progress and actual speed, pause/seek/change tracks, switch to Map and back, reconnect, and verify stock indicators. Compare diagnostic stream traffic/decoder load in both modes before claiming measured savings. In-car wireless connection, compositor compatibility and physical cropping are not proven by desktop tests.

## Layout and compatibility

The optional fitted layout keeps foreground content between the native strips (18% reserved above, 24% below). The animated background covers the available canvas without artificial edge fades. Native vehicle composition remains responsible for its own fades, warnings and menus. Disable fitting for other display geometries. Song metadata reserves a separate column for the larger speed readout; progress remains below the metadata.

On DiLink 3 the player requests FULL_PROJECTION (16), independently of the saved map shape. Returning to Map restores its projection policy. This does not guarantee physical edge-to-edge output: native strips and clipping depend on firmware. No native drawing priorities, firmware resources or speed geometry are modified.

An independent opt-in shows gear, traction-battery percentage and electric range via existing authorized ADB getters. Gear is sampled once per second; battery and range once per ten seconds. Invalid or stale readings show unknown values, and polling stops when the page is hidden or the option disabled. No CAN writes are issued.
