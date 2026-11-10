# Initial UI and map appearance

Author: 寒叙 (@Hanxu4131). Updated for #1159.

## Current behavior

The `/info` reply does not declare `uiAppearanceMode`, `uiAppearanceSetting`, `mapAppearanceMode` or `mapAppearanceSetting` by default.
This is the same `/info` as DiPlay 0.2.15.
Runtime theme changes still go only through `setNightMode`.

To declare the four fields, turn on Settings → Advanced → Display (experimental) → **Declare automatic CarPlay appearance (experimental)**.
With it on, the main and cluster displays both declare all four fields as `0` (automatic), as 0.2.16 to 0.2.22 did for every car.
The setting applies at the next connection.

## Why the declaration is opt-in

The fields came from #346 (0.2.16).
寒叙 tested the local BYD CarPlay implementation on a 2023 BYD Tang DM-i Champion Edition, platform/controller 21.
There, `setNightMode` alone did not make CarPlay follow the head unit's light/dark changes, and CarPlay followed them after the four fields were added.
That was the source implementation's vehicle result, not a vehicle test of the upstream port.

Two later reports show the declaration stopping `setNightMode` from working:

- #1159, 2024 BYD Yuan Plus (ATTO 3), DiLink 4.0, iPhone 16 Pro Max on iOS 18.7.2.
  0.2.15 followed the head unit's theme; 0.2.16 to 0.2.22 did not.
  In a 0.2.22 diagnostic report, DiPlay wrote `setNightMode` dark and light (`THEME_DIAGNOSTIC send … commandWritten=true`).
  The reporter saw CarPlay's navigation screen and map stay unchanged; with 0.2.15 they followed the same manual switch.
  Between 0.2.15 and 0.2.16, these four fields are the only change in what DiPlay sends the iPhone about appearance.
- #1021, Harman head unit, iPhone on iOS 26.1, DiPlay 0.2.21.
  `setNightMode` darkened only the map, and the rest of CarPlay followed the iPhone's clock.

So the fields are not declared unless the driver turns the setting on.
DiPlay cannot yet tell which cars need them: the Tang's platform is not detected, and the iOS version did not explain the difference.

## Still to test

- A/B on the Yuan Plus from #1159: with the setting off, CarPlay should follow a manual light → dark → light switch of the head unit; with it on, it should not.
- On a Tang (platform 21), check whether CarPlay follows the head unit with the setting off.
  If it does not, the setting is the workaround, and turning it on should restore 0.2.22 behavior.
- Forced Day and Night modes and the instrument (cluster) display with the setting both off and on.

The four fields describe the initial automatic UI/map mode.
They are not a substitute for runtime theme updates, and a written `setNightMode` is not evidence that the iPhone applied it.
