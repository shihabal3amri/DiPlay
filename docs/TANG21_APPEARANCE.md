# Initial UI and map appearance

Author: 寒叙 (@Hanxu4131). Updated for #1159.

## Current behavior

By default the `/info` reply declares no appearance fields (`uiAppearanceMode`, `uiAppearanceSetting`, `mapAppearanceMode`, `mapAppearanceSetting`), as 0.2.15 did.
Other `/info` fields (audio formats, safe areas, corner masks) have changed since 0.2.15, so only the appearance part matches that release.
Runtime theme changes still go only through `setNightMode`.

To declare the four fields, turn on Settings → Advanced → Display (experimental) → **Declare automatic CarPlay appearance (experimental)**.
With it on, the main and cluster displays both declare all four fields as `0` (automatic), as 0.2.16 to 0.2.22 did for every car.
The setting applies at the next connection.

## Why the declaration is opt-in

The fields came from #346 (0.2.16).
寒叙 tested the local BYD CarPlay implementation on a 2023 BYD Tang DM-i Champion Edition, platform/controller 21.
There, `setNightMode` alone did not make CarPlay follow the head unit's light/dark changes, and CarPlay followed them after the four fields were added.
That was the source implementation's vehicle result, not a vehicle test of the upstream port.

Two later reports involve the declaration:

- #1159, 2024 BYD Yuan Plus (ATTO 3), DiLink 4.0, iPhone 16 Pro Max on iOS 18.7.2.
  The reporter says 0.2.15 followed the head unit's automatic theme changes, and also followed manual light/dark switches made in daylight: the "DiPlay main interface", the navigation screen and the map all changed.
  0.2.16 did not follow, and 0.2.22 still does not.
  In a 0.2.22 diagnostic report, DiPlay wrote `setNightMode` dark and light (`THEME_DIAGNOSTIC send … commandWritten=true`).
  For that test the reporter said on 2026-11-10 that when the head unit switched from dark to light, "the DiPlay main interface" switched to light but the navigation screen and map did not change.
  It is not yet clear whether "main interface" means CarPlay's home screen or DiPlay's own app.
  Between 0.2.15 and 0.2.16, these four fields are the only change in what DiPlay sends the iPhone about appearance, and `setNightMode` itself did not change.
  The 0.2.15 and 0.2.22 results were not taken back to back, so this points at the fields but does not prove they are the cause.
- #1021, Harman head unit, iPhone on iOS 26.1, DiPlay 0.2.21, with the fields declared.
  There `setNightMode` did darken the map, and the rest of CarPlay followed the iPhone's clock.
  #1021 was never tested without the fields, so it shows neither that the fields block `setNightMode` nor that the iOS version is irrelevant.

The fields are not declared unless the driver turns the setting on.
Whether that should stay the default is still open and depends on the tests below.
DiPlay cannot yet tell which cars need the fields: the Tang's platform is not detected, and there is not enough data to say whether the iOS version matters.

## Still to test

- A/B on the Yuan Plus from #1159: a manual light → dark → light switch of the head unit with the setting off, then with it on. Note separately whether CarPlay's home screen, the navigation screen and the map follow.
- On a Tang (platform 21), check whether CarPlay follows the head unit with the setting off.
  If it does not, the setting is the workaround, and turning it on should restore 0.2.22 behavior.
- On one car with iOS 26 (for example the #1021 setup), check whether CarPlay follows the head unit with the setting off.
- Forced Day and Night modes and the instrument (cluster) display with the setting both off and on.

The four fields describe the initial automatic UI/map mode.
They are not a substitute for runtime theme updates, and a written `setNightMode` is not evidence that the iPhone applied it.
