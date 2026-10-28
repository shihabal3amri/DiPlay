# Side panel next to CarPlay (experimental)

CarPlay can share the head unit's main screen with DiPlay: the iPhone draws CarPlay in two thirds of the screen while DiPlay shows its own panel in the rest. On a landscape screen CarPlay stays on the driver's side and the panel is the passenger's third (the right in a left-hand-drive car, the left with **Right-hand drive** on); on a portrait screen the panel is a band at the bottom. The car switches between this and full-screen CarPlay at any time without reconnecting. It is off by default (**Settings → Display and performance → Side panel next to CarPlay**); once on, the in-session menu has a **Side panel** button.

## Using it

- **Open:** drag the tab on CarPlay's passenger-side edge (on a portrait screen, its bottom edge) out with one finger; the panel follows the finger. The tab shows for ten seconds when a session starts and fades; it keeps working without the pill, and a tap shows the pill again. It sits 20 dp in from the screen edge because BYD's own gesture monitor takes swipes that start at the edge (and two-finger swipes), which made an edge swipe unreliable.
- **Resize:** drag the panel's edge that faces CarPlay. On release CarPlay takes the nearest of two thirds, half or one third of the screen; the choice is remembered. A grip marks that edge for ten seconds after the panel opens and again when the edge is touched.
- **Close:** drag the edge to the far side; let go past five sixths of the screen and CarPlay goes back to the whole screen. The in-session menu button still works too.
- While the edge moves, CarPlay's last picture (copied small, blurred) covers the video and the panel's contents are blurred, as CarPlay blurs apps whose size changes (Android 12 and later). The blur fades as soon as the finger lifts and CarPlay's own transition takes over; in the car CarPlay's transition looked quicker without holding ours after the release.
- The card has rounded corners, follows the app theme (black when dark, white when light) and on a landscape screen ends at the bottom level with CarPlay's floating dock.
- On a BYD head unit with DiPlay's adb access, the panel shows vehicle tiles read from BYD's autoservice binder: the clock, the battery (charge and range) and the four tyres (pressure and temperature), plus any tiles the driver adds. A head-unit tile shows DiPlay's own machine instead of the car: CPU and memory load with bars, the fastest core's frequency, and the hottest thermal zone when the kernel lets a plain app read it. That tile needs no adb and no permission (it reads /proc/stat, /proc/meminfo and sysfs directly), and no GPU figure: Android 10 gives an ordinary app no way to read GPU utilisation.

## Widgets

The panel is a hero card over a grid of compact tiles the driver lays out. The queue's first tile renders as the hero — full width, oversized value, a progress arc when the signal has a natural share (charge, fuel) — and the rest sit in 60 dp rows with the label and value on one line, so eight or more tiles fit without scrolling; the old per-tile icon headers and the whole-row group captions are gone (groups only organise the add drawer). Reordering is a long-press drag: holding a tile 350 ms lifts it with a shadow while a dashed slot previews the landing index, the list auto-scrolls near the edges, and release moves the tile once (its size travels with it) — dropping at the top makes it the hero. In the editor the driver taps a size chip to cycle small, wide, tall, large, taps × to remove it, and taps + to open the drawer of tiles to add, grouped by Energy, Driving, Climate, Lights, Hybrid, Battery, Safety and Car. The order and sizes are remembered as one saved line, so an existing layout opens with its first tile as the hero. Columns follow the panel's width, so the two thirds, half and one third layouts differ; a long list scrolls. A tile the car does not answer stays blank rather than guessing, and the fuel tiles stay blank on an electric car.

Related positions share one dense tile instead of one tile per signal: front/rear motor speed, current and temperature, and inverter temperature; the four doors and the four windows as 2×2 blocks; the five belts and five seats; trunk/hood; driver and passenger seat heat/vent (H/V); low/high/side lights; a wipers pair; and an A/C summary. Cells carry a short position token (FL/FR/RL/RR, F/R, D/P, H/V) that needs no translation, and enum signals show a compact state word (OPEN, SHUT, MOVE, ECO/SPRT/NORM/SNOW, OFF/ON/DRV, EV/HEV, ◀/▶/⚠, REC/EXT, AUTO/MAN) rather than a raw number.

The data comes from BYD's autoservice binder over DiPlay's own adb shell (read only, never asking for approval), the same channel the tyres used: the fields a tile needs are polled only while that tile is on screen, on one of three speed tiers (drive pedals, speed, gear, motor rpms/currents, ignition, lights and wipers about every second, charge, climate, doors and temperatures every five, mileage, battery health, insulation and lifetime counters every thirty), and every field of a tier is read in one shell round-trip. On DiLink 3 and 4 head units, where the same signal is filed under a different number, the addresses are first remapped through the firmware's own catalog; a symbol whose catalog number already equals the compiled constant is left untouched, so a read never moves to another device by accident.

## How it works

The panel builds on the view areas described in [VIEW_AREAS.md](VIEW_AREAS.md). With the setting on, the main display also declares, for each screen orientation and dock edge, three areas with two thirds, half and one third of the screen: the left of a landscape screen, the top of a portrait one. The **Side panel** button moves CarPlay there with `updateViewArea`; DiPlay lays out the canvas by the whole screen and an ordinary Android view covers the strip of the stream CarPlay leaves black, mapped through the video's layout, so it stays on that strip when the video is letterboxed. Touches on the panel stay in DiPlay; touches elsewhere go to the iPhone as before. A window change (a turn, the head unit's split screen) ends the panel and picks the matching area as usual. Closing DiPlay or ending the session hides the panel and stops its refresh.

CarPlay has no notion of the panel's contents; the iPhone only knows the car asked it to use a smaller area.

Panel placement uses physical video coordinates, so switching the UI to a right-to-left language does not move it over CarPlay. When a new host Activity adopts a live session with the panel open, it restores the panel and its refresh callback without changing the view area.

## Tested

Resizing, the tab, the blur and the tyres: the same Tang with iOS 27 over wireless CarPlay, on a build of 0.2.15 with this change. Tyre values matched the cluster's TPMS page. CarPlay moved between the three shares and back to the whole screen without reconnecting.


2024 BYD Tang (DiLink 5.0, 2560×1440 rotating centre screen) with an iPhone on iOS 27, over wireless CarPlay (lab build of the same design): CarPlay moved to the left two thirds (landscape) or the top two thirds (portrait) and back within a fraction of a second, without reconnecting; touches in CarPlay's area landed correctly; the panel showed a clock and the car's battery and range. With an automatic dock, CarPlay puts its dock at the bottom once several areas are declared; the dock setting places it.

Tyre values come from BYD's autoservice binder over DiPlay's own adb shell (read only, never asking for approval): pressure from the tyre device (1016), temperature from the instrument device (1007) on CAN-FD firmware. Out-of-range values (pressure outside 50–600 kPa, temperature outside −50–150 °C) are left out, and the block is hidden without a pressure reading.

## What it could become

The panel is an Android view, so it can show anything DiPlay can read or do: the vehicle data it now polls over the head unit's adb, controls for the car (for example the climate fids documented for BYD's autoservice, which write rather than read), shortcuts to BYD apps, or the map from another source. With a few more pieces (a wheel key or an automatic rule when parked to show the panel, and a choice of panel side), DiPlay could act as a launcher around CarPlay rather than a full-screen projection only.

## Other findings from the same research

Tried in the same lab build on the same car, using parameter definitions from Apple's iAP2 message spec in Xcode's CarPlay Simulator (`iap2messages-internal.i2mspecarchive`, read as data):

- **Vehicle status extras:** declaring OutsideTemperature, InsideTemperature, WiperStatus, BarometricPressure, Alerts and PassengerSeatStatus in the VehicleStatus component is accepted; the iPhone then subscribes to outside temperature, wipers, pressure, alerts and passenger seat (not inside temperature). Live values from the car (outside temperature, wipers, hazard lights) were sent and accepted, but nothing visible changed in CarPlay or Apple Maps.
- **Road object detection** (identification param 33, for a car's camera to report signs, lanes and objects): the iPhone rejects the identification (`IdentificationRejected` for 0x0021), presumably reserved for approved vehicles.
- **App discovery** (`StartAppDiscoveryUpdates` for all CarPlay apps with icons): the iPhone answers `AppDiscoveryUpdate` with `CarPlayAppListAvailable = 0` (Unknown) and no list.
