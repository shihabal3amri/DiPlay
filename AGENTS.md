# AGENTS.md

Instructions for coding agents that work on DiPlay.

## Checks

Run the CI command from `.github/workflows/android.yml` before you report a change as done:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```

Set `ANDROID_HOME` or `local.properties` if Gradle cannot find the SDK.

## Adding or moving a setting

The Settings screen is grouped by driver goal, not by implementation.
`SettingsCategory`, `SettingsSection` and `SettingsInformationArchitecture` at the top of
`common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt` define the groups.
`SettingsLayoutPolicyTest` fails when a section has no category or has more than one.

### Choose the category

The Settings UI organizes categories into two modes:
- **Everyday Mode (Default)**: 4 clean categories visible out of the box (Connection, Display, Audio, About). Fits in one screen without rail scrolling.
- **Geek Mode (5 taps on version in About)**: reveals 2 dedicated power-user categories (DiLink and Advanced / Lab & Diagnostics).

| Category | Put a setting here when it controls… | Examples |
| --- | --- | --- |
| Connection | everyday iPhone connectivity and receiver lifecycle | connection setup, connect on open, USB permissions, iPhone choice, Android permissions |
| Display | everyday appearance and layout on head unit | day/night mode, picture, size, resolution, frame rate, dock, system bars, driving side |
| Audio | everyday sound routing | media and navigation streams, call stream |
| About | version, Geek Mode toggle, and app language | version tap easter egg, language choice, updates |
| DiLink (Geek Mode) | all BYD vehicle hardware and platform profiles | steering wheel keys (Siri, call keys, zoom, joystick), car button, cluster map, HUD navi, BYD hotspot, vehicle data |
| Advanced (Geek Mode) | experimental behavior and diagnostics | lab display (split screen, rotation, side panel), advanced media (HEVC, smooth video, echo cancellation, voice filter), GPS location to iPhone, diagnostics |
| Overview | landing page for compact layout | quick links and readiness |

A setting goes to Advanced when it is experimental, depends on specific firmware, or is an opt-in that can break sound, video or the connection on some head units.
A control that fixes a common problem and is safe at its default (for example the audio stream choice) stays in its category.
Exception: a gated control that only its own audience sees, and that completes an everyday goal, stays in that goal's category.
Example: auto car hotspot needs ADB but appears only for car-hotspot users, so it is in Connection.

Mark an experimental setting or card with the title suffix ` (experimental)`, never `· experimental`, a dash, or an `Experimental …` prefix.
Each locale MUST use its existing form: `(تجريبي)`, `(experimental)`, `(экспериментально)`, `(експериментально)`, `（实验性）`, `（實驗性）`.

### Add the code

1. To extend an existing card, add the control inside the `filteredSection(...)` block of a card in the correct category.
2. For a new card, add a `SettingsSection` entry and map it to exactly one category in `SettingsInformationArchitecture.sectionsByCategory`.
3. Build the new card with `filteredSection(content, SettingsSection.X, …)` inside `allSettingsSections()`.
   A plain `section(...)` call there MUST NOT be used: it renders on every category page.
4. A setting MUST NOT appear in two categories.
   Overview quick settings are the only duplicates. They MUST use the same persistence as the full control. Overview SHOULD NOT have more than four quick settings.
   A header shortcut MAY duplicate a Display setting when it uses the same persistence.
5. If the change reconnects CarPlay or applies at the next connection, the description MUST say so.
   A setting that only takes effect at the next connection MUST call `markReconnectNeeded()` after it saves.
   This shows the "Reconnect now" bar. It MUST NOT drop a running session without the driver's consent.
   Reconnect at once (`reconnectIfRunning()`) only after a dialog button that says "Apply and reconnect", or when the description says that the change reconnects CarPlay.
6. Add each new string to `common/src/main/res/values/` and to every `values-xx` locale folder.
   New Settings copy SHOULD use the `settings_` prefix. `SettingsTranslationsTest` fails when a `settings_*` string has no translation.
7. A control that opens a choice uses `button()` with the text `"Title · Value"`.
   That form renders as a setting row with the value and a chevron, and Search indexes the title.
8. When you move a control between categories, update `AdaptiveSettingsUiTest.settingsLiveWhereDriversLookForThem`.

### Overview

Overview holds the connection status, links to the categories, quick settings, About and Language.
Do not add a new setting to Overview. Add it to its category. Then promote it to quick settings only if drivers change it often.

## Settings layout

Spacing, size and shape in the Settings screen MUST come from one place.

1. A gap between blocks MUST use a named dp constant, such as `SETTINGS_BLOCK_GAP_DP`.
   A new literal gap value MUST NOT be added.
2. A size MUST NOT be a pixel constant (`*_PX`). Use dp, and clamp to the available window width.
   Test a menu or panel on a narrow window and on a high-density screen.
3. Controls in one row MUST have the same height.
   Corner radius MUST follow the control height. A new button MUST NOT set its own radius.
4. Every `SettingsCategory` except Overview MUST have a link on Overview.
   `AdaptiveSettingsUiTest.overviewLinksToEveryOtherCategory` checks this.
5. A layout change MUST include compact and full screenshots with a large font scale.
   Add an Arabic (right-to-left) screenshot when the change touches row alignment.

### DiLink Hardware Platforms & PR Guidelines

When introducing or modifying BYD vehicle features, identify which DiLink platform the feature applies to:
- `DiLinkProfile.DILINK_150` (31.x / 34.x, Android 13+, BYD 9000 / 8295 / 8+ Gen1, DiLink 5.1 / 6.0)
- `DiLinkProfile.DILINK_100` (23.x, Android 12, Snapdragon 778G / 782G, DiLink 5.0)
- `DiLinkProfile.DILINK_50_4` (16.x / 17.x / 21.x, Android 10, Snapdragon 690 / 665, DiLink 4.0)
- `DiLinkProfile.DILINK_50_3` (13.x / 15.x / 18.x, Android 9 / 10, Snapdragon 665 / 6125, DiLink 3.0)
- `DiLinkProfile.DILINK_20` (2.x / 4.x / 5.x / 8.x / 26.x, MTK P35 / Snapdragon 625, DiLink 2.0 / 2.1)
- `GENERIC` (Non-BYD or universal features)

Ensure universal features (wheel call answer/hangup, wheel zoom, joystick, Siri key, hotspot, song, battery SOC) are available across all platforms.
When submitting a Pull Request, complete the checklist in `.github/pull_request_template.md` with the verified DiLink platform and vehicle controller version.
