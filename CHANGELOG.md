# DiPlay 0.2.8 — 2026-09-30

- Keep iPhone location reporting active across the wireless Bluetooth-to-Wi-Fi CarPlay handoff; limit location updates to one per second on wireless and USB.
- Add optional ADB wheel-speed and gear reporting for iPhone dead reckoning when GPS is unavailable. Tunnel use has not yet been verified.
- Add optional iOS 27 video playback on the car screen while parked, with iPhone, touchscreen and steering-wheel controls; close playback when leaving P.
- Explain unsupported DRM-protected video such as Apple TV+, which requires a licensed FairPlay receiver.
- Improve playback error reporting and preserve CarPlay when the head unit cannot play a video.

# DiPlay 0.2.7 — 2026-09-29

- App interface in English, Simplified Chinese, Arabic, Russian and Spanish; synchronized Android app-language settings.
- Steering-wheel media controls and long-press Siri on supported BYD firmware while CarPlay is on screen.
- Dashboard display choices: map, turn card, or both; corrected dashboard keyframe recovery.
- Optional ADB feature on supported DiLink 5.0: pause the dashboard map stream when its display mode hides the map.
- Optional ADB battery reporting for Apple Maps, with warning threshold, charging-connector selection and a checked reconnect action.
- Audio playback reliability fixes and clearer dashboard settings.
- Clarify the BYD-only support scope on the README and all five website editions.

# Unreleased

- Music buffer adds a 1500 ms option for the weakest Wi-Fi links.
- The chosen music buffer is advertised to the iPhone as `media` output latency; other streams still report 0.
- Audio receive and playback threads run at urgent-audio priority.
- If a head unit rejects a large music AudioTrack, fall back to the low-latency size instead of losing audio.

# Unreleased

- Open DiPlay when the selected iPhone joins the car's Bluetooth (opt-in, *Open when your iPhone connects*). Head units usually wake from sleep instead of rebooting, so the boot trigger alone never fired on an ordinary drive; reported on a Song Plus (DiLink 4.0).
- *Open after the car starts* also reacts to quick boot.
- Android 10+ only lets DiPlay open itself from the background with *Display over other apps*; the settings page offers the permission, and without it DiPlay posts a notification to tap. Repeated triggers within 30 s count once; nothing happens while a CarPlay session runs.
- Decision logic is covered by unit tests. Not yet validated on the DiLink 5.1 development car.

# Unreleased

- The in-session Resolution slider now goes up to 1.5x. Steps above 1.0x ask the iPhone for a supersampled canvas (for example 1920x990 → 2880x1486 at 1.5x), capped at 4K; if the head unit's decoder cannot handle it, CarPlay uses Native and says so. The default stays 1.0x.
- CarPlay keeps a fixed negotiated resolution when the car's camera or 360° surround view resizes the screen, instead of reconnecting. The video is letterboxed without distortion and touch maps to the visible CarPlay area.
- Swiping down with three fingers in CarPlay opens the in-session settings overlay again. CarPlay keeps running while it is open; Save and reconnect applies changes with one reconnection, and closing without saving leaves the session untouched.
- Each Resolution option is a fixed size derived from the largest screen size seen for the current bar layout; the settings page and diagnostic reports show it. Changing it applies and reconnects once.

# 0.2.0 — BYD navigation and connection improvements

- Standalone windshield HUD arrows, distance and street names on the verified DiLink5.1 firmware; no ADB, root or computer helper.
- Retain contributor cluster/SOME-IP navigation, route parsing, BYD CarPlay icon and display-size presets.
- Fix Car hotspot startup by using scoped IPv6 when available and binding discovery/probing to the AP interface. Physically confirmed on the development car.
- Drain asynchronously decoded audio during packet gaps and rebuild the music buffer after starvation. Wi-Fi Direct is much better in the user retest; occasional audio cutouts remain for a later version.
- Preserve bounded music-buffer choices, USB read improvements and decoder recovery; fix USB request/close races and keep vendor output outside phone callbacks.
- Save audio/video/receive timing and discovery diagnostics without road names or protocol payloads.
- HUD cleanup on normal end/disconnect/off/stale input; interrupted sessions recover on the next app launch. Force-stop may leave guidance visible until reopening.
- Thanks to @romanchukg-cloud and @georgiyrr for PR #3 and vehicle testing.

# 0.1.0 release restored — 2026-09-25

- Rebuilt and signed the APK locally with explicitly supplied runtime authentication assets.
- Restored release downloads; no app behavior or version-code change from 0.1.0.
- Accessory identity remains in the APK only. No credential files enter Git or the source archive.
- Retained generated test identities and public-source credential checks.
- Source/CI builds omit runtime identity assets by default; local packaging requires an explicit external directory.

# Source reset — 2026-09-25

- Withdrew the 0.1.0 APK and removed its release tag.
- Reset the public branch after preserving restricted local incident records.
- Removed static synthetic test private keys; generate test identities at runtime.
- Removed automatic private-asset packaging and disabled the old release build script.
- Added a build guard rejecting credential asset files.
- Replaced the download site with a five-language suspension notice.

The APK was subsequently rebuilt and restored as described above. Existing copies cannot be recalled by a Git history reset.
