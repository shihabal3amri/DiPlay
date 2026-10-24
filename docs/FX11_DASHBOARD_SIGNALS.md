# Native dashboard vehicle signals — 2026-10-16

Midnight, Panorama and the Replica design use the same read-only FX11 signal layer.
CarPlay is not required for vehicle readings. Layout and key behavior are documented separately
in NATIVE_FX11_DASHBOARD.md; this document describes the data evidence and its limitations.
See [FX11_DATA_AVAILABILITY.md](FX11_DATA_AVAILABILITY.md) for the current Chinese capability
matrix and the evidence required after the reported fuel/lamps/range mismatch.

## Implemented read-only sources

Normally HIDL reads require the property to be advertised as readable/global by the current firmware.
V11 permits a missing configuration entry only for nine native-dashboard feedback bindings whose
direct reads succeeded on this FX11: fuel, indicated DTE, four tire pressures and vendor ambient
temperature (`Binding.allowMissingConfig=true`). This is a targeted read-only `get()` fallback,
not permission to read arbitrary properties. An existing write-only configuration or a configuration
without global area 0 still rejects the binding; ordinary CarPlay bindings retain their existing policy.
The callback must return the requested property/area with OK result and available value status.
Values are discarded after 2.5 seconds without successful reads, and immediately on read failure.
Native dashboard bindings are opt-in; existing CarPlay/Ultra vehicle readers do not gain these polls.

| Display | Property | Decode |
| --- | --- | --- |
| Fuel | 561024416 | PA_IntBase, 0..100 % |
| Remaining range | 561024488 only | PA_TS_DTEIndicated integer km, including valid zero. Missing, failed or expired vendor feedback remains unknown; standard 0x11600308 is retained for diagnostics, not a display fallback. |
| Odometer | standard 0x11600204; fallback 561024140 | km; fallback is PA_IntBase. Standard preferred when advertised. |
| FL / FR / RL / RR pressure | 561024399 / 400 / 401 / 402 | PA_IntBase integer / 10 = bar, corrected by the 2026-10-16 FX11 sample: raw 23 = 2.3 bar |
| Ambient temperature | 559970552 / 0x216078f8 only | vendor FLOAT °C is a separate INDICATED_AMBIENT_C signal; DashboardSnapshot.ambientC becomes unknown without valid vendor feedback. Standard 0x11600703 remains diagnostic; the ordinary CarPlay AMBIENT_C binding is unchanged. |
| Left / right turn | 557873489 / 490 | strict 0 off, 1 on |
| Low / high / position / rear fog | 557849510 / 557849181 / 557849179 / 557855125 | strict 0 off, 1 on |
| Driver belt | 557855239 | strict 0 unlocked, 1 locked |
| Assist mode | 557849285 | 0 inactive, 1 ACC, 2 GPilot; not proof of steering engagement |
| ACC state | 557871508 | 1 standby, 2 active, 4 override, 5 stopped, 6 waiting, 7/8 fault; reserved values unknown |
| ACC target (standard only) | 291508243 / 0x11601013 | m/s × 3.6; only if advertised readable. Not found in this car’s saved HAL; no guarantee of availability. |
| ACC target diagnostics | 557849502 | nonnegative raw int/float, units unresolved, never labelled km/h |
| Following gap | 557849293 | 1/2/3 → one/two/three bars; 0=None and reserved values clear the display |
| Lateral diagnostics only | 557871388 / 557874031 | raw integer / timeout flag; never promoted to centering-active |
| Left / right lane line | 557871401 / 403 | 0 grey, 1 white, 2 blue, 3 red, 4 yellow; right uses same-family colour enum |

Fuel uses percentage, not litres. Tire pressures have no invented warning threshold. Unknown lights remain unknown; a missing value never means the lamp is definitely off.
The user confirmed this FX11 has no front fog lamp. V27 removes its placeholder from all three
dashboard styles; FRONT_FOG has no raw source. The current UI does not show seatbelts; the legacy
driver-belt decoder is retained for diagnostics.

## V27: parking lamp source and supported lamps

The earlier native dashboard used Android `PARKING_BRAKE_ON` (0x11200402). Saved captures
showed 1 while parked, but did not establish a release transition. The user now reports a
persistent red parking badge; those captures alone cannot prove the standard source is accurate.

AutoService's extracted FX11 profile maps property 10040 to `557850632 / 0x21402008`
(`LCM_EPBLAMPREQEPBLAMPREQ`), with an explicit inversion: raw 0 → lamp ON, raw 1 → lamp OFF.
The property is advertised READ/ON_CHANGE in `outputs/fx11-live-20261016/car-property-configs.txt:865`
(workspace-relative). V27 uses this feedback only when the firmware advertises readable global
access. It never writes the signal, never falls back to the previous standard Boolean, and never
infers the lamp from gear P or AutoHold. Other numbers, malformed/unavailable replies, failed reads
and expired read receipts clear the value. Int32 and exact 0/1 float payloads use the same decoder.
The static mapping is established; its applied/released transition on this particular car remains
unverified because ADB is disconnected.

Classic/Replica lamp row: left turn, combined low beam/AUTO, high beam, position lamps, rear fog,
right turn (with the existing assistance indicators between the lamp group and right turn).
An A inside the low-beam lamp requires confirmed AUTO selector feedback. Its green/dim color
continues to describe actual low-beam feedback; AUTO alone does not claim the low beam is lit.
Missing AUTO feedback omits A, and is not inferred from an illuminated low beam. The previously
observed ECARX selector-unavailable condition is not resolved by this UI change.

## PA wire format evidence

The existing OEM decompilation at `/tmp/fx11-oem-hvac/api/sources/` provides
`ecarx/car/hardware/vehicle/PATypes.java:234–283`: PA_IntBase has exactly four little-endian int32 fields,
availability at byte 0, value at 4, format at 8, status at 12. Only availability `1` is active
(`annotation/AvailabilitySts.java`). The exact selected PA classes inherit IntBase.
The decoder accepts only those explicitly registered properties and 16-byte packets. It does not
attempt arbitrary BYTES decoding or treat unknown bytes as scalar values. OEM consumers use
availability for validity; format/status are retained in the wire envelope but have no invented meaning.

Historical static analysis combined `Sensor.java:2388–2398` (PA integer divided by 10) with the
FX11 demo/catalog kPa label and inferred PA / 1000 = bar. That inference is **superseded for this
FX11 binding**: the 2026-10-16 direct reads returned PA data 23 for all four wheels while the driver
reported 2.3 bar on all four. V10 uses PA / 10 = bar. This is one observed pressure point on this
vehicle, not a unit guarantee for every Geely model or firmware. The old 2400 → 2.40 fixture tested
the old assumption and was not vehicle evidence. The earlier demo raw 21 was explicitly simulated;
it remains separate from the actual raw 23 sample. The PA availability/length checks are unchanged.
Odometer units still use the FX11 catalog and require independent real-vehicle confirmation.

Lights and driver belt IDs follow the FX11 profile, rather than the different IDs in an older SDK.
`BltLockSt1.java` supplies the raw belt enum. `AsyAutDrvCtrlTyp.java` and `AsyALgtSts.java`
supply mode/state enums. `AsyLineLeColor.java` supplies lane-line colours.

## Range and ACC target evidence — v5

Remaining fuel range: FX11 mapping entry 10022 → 561024488 (`patsdteindicated`).
OEM `PATypes.java:16843` derives PA_TS_DTEIndicated from PA_IntBase.
`Sensor.java:576–588` and `:2323–2325` pass its data directly to sensor 1054720 (fuel endurance
mileage), with no scaling. V5–v9 preferred valid OEM indicated DTE and used standard range as a
fallback. V10 removes that fallback because the standard 50 km contradicted the paired OEM
111 km reading on this car. Missing, failed or expired vendor data now remains unknown. A
legitimate zero is preserved. We do not infer electric range or calculate distance from fuel percentage.

Standard ACC target units: [AOSP VehicleProperty.aidl](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/android16-release/automotive/vehicle/aidl_property/android/hardware/automotive/vehicle/VehicleProperty.aidl)
identifies CRUISE_CONTROL_TARGET_SPEED as 0x1013 + SYSTEM + GLOBAL + FLOAT, metres per second.
This optional binding is native-dashboard-only. The captured FX11 native binary, Java property
catalog and saved logs do not contain it, so its availability on this car is doubtful.

The actual FX11 feedback is CLUSTER_ADAS_DISPSPDSETFORLGTCTRL (557849502):

- Declaration: `/tmp/fx11-cluster-deeper/carservice/sources/android/hardware/automotive/vehicle/V2_0/VehicleProperty.java:394`.
- Native HAL file offset 0xc1f0 holds property/access `(557849502,1)`; configuration at
  0x2007c0..0x2007e4 has READ access and ON_CHANGE. Global area config copied from 0x6aab0
  through 0x1fdb1c..0x1fdb48 and 0x2007b8..0x2007e8 is minInt32=0, maxInt32=125.
- The similarly named SETSPDFORCRSCTRLFCT (557849448) is WRITE-only, not read or used.
- Native Bosch→RX conversion can preserve float payloads under INT32 IDs. This diagnostic
  binding accepts a single int or float, retains its numerical raw value and never converts it to km/h.
- VehiclePropStatus descriptor at file 0x82205 defines AVAILABLE=0, UNAVAILABLE=1, ERROR=2,
  DEFAULT=3; fromProto 0x2c09a0..0x2c09d8 preserves 0/1/2 (only 3→0). The reader rejects every
  nonzero returned status, mismatched property/area and failed result.
- Saved cluster-start/coldboot/current and animation logs contain only raw 0; roundkmData and
  roundMphData are also 0. No independent timeout property, scaling formula or nonzero pair was
  found. Zero/255/65535 are not claimed to be decoded OEM sentinel values.

**FX11-specific ACC target display remains incomplete.** Standard valid data can display, but raw
vendor feedback cannot yet. A paired nonzero OEM setting/raw observation or the QNX
AdasModule implementation is still required. The screen keeps “—” if there is no verified reading;
fixture value 80 km/h is testing-only. No engagement state is inferred from the target.

## Outstanding interfaces and operations

The original left steering-wheel buttons and roller keep their OEM behavior. DiPlay does not intercept,
reassign, simulate or inject ADAS commands. Mode/state changes are reflected when the read signals change.

**Following-gap feedback is implemented, conditional on firmware exposing valid readings.**
`CLUSTER_ADAS_TIGAPSETFORLGTCTRL` (557849293 / 0x21401acd) is declared by the shipped
VehicleProperty class. OEM `TiGapSetForLgtCtrl` defines None=0 and TimeGap_1/2/3=1/2/3,
so this is three levels, not the unrelated four-value IADAS setting interface.
All three native styles display the same selected level independently of CarPlay and page selection.
We do not infer engagement from a selected following gap, or count wheel presses.
The old `IADAS.SETTING_FUNC_ACC_TIME_GAP` stub is not used.

### Static and historical evidence (follow-up, 2026-10-15)

Source root: `/tmp/fx11-cluster-deeper/` (no new vehicle capture):

- `carservice/sources/android/hardware/automotive/vehicle/V2_0/VehicleProperty.java:546`:
  gap property. `adapterservice/sources/vendor/ecarx/xma/automotive/vehicle/V1_0/TiGapSetForLgtCtrl.java:4`
  defines its named levels. These are declarations, not a hardware acceptance test.
- `cluster-start.log:6378`: OEM AdasModule receives gap=3 with timeout=0. ACC/GPilot are
  inactive here, so the OEM active-control display can be hidden even while the selected gap exists.
  `:7749–7755` records timeout=1 and gap=0. Our decoder clears None=0.
- Full native HAL `/tmp/fx11-theme-probe/vehicle-service`, SHA-256
  `8673c68108b1c61d239be76038b67713840661f379362bb3cedc4c88f200df59`, BuildID
  `e0a79138008779205a2e9c6b8978613c`. Existing LLVM disassembly and matching debug symbols
  establish gap config READ=1 and ON_CHANGE=1: rodata file offset 0xc190, initializer addresses
  0x1f8a40, 0x1f8a58, 0x1f8a60, 0x1f8a78. This does not prove delivery on the current car.
- FDBusClient::CarBoschChangeToRX at 0x2f9adc retains unmapped properties at
  0x2f9bd0–0x2f9be8; the gap ID need not appear in the Bosch→RX rename table.

**Actual lane-centering state is still unresolved and shows “—”.** We now read its real raw
feedback and timeout for diagnostics, without guessing an active flag:

- `carservice/sources/ecarx/car/hardware/signal/CarSignalManager.java:7800` and `:14456`
  expose raw 557871388 and timeout 557874031. `HalPropertyIdTransformation.java:53`
  preserves both IDs.
- Native rodata offset 0x7dfd4 pairs RX 0x2140711c with cluster 0x21401ac2. At
  0x2f9d80–0x2f9da0 the converter renames the property and copies the raw payload unchanged.
- `ESCSportState` at 0xbf9b2c consumes this raw property and checks 3/4 at 0xbf9c04–0xbf9c0c.
  Those checks do not label the centering states. The nearby `AsyALatSts` enum has no proven
  reference from INDCR, so its 2=Active must not be applied to this signal.
- The 53 relevant local QNX log entries only contain raw lateral value 0 (often timed out).
  They establish feedback/timeout propagation, not the meanings of nonzero states.
- PA_Asy_HWA / PA_Asy_LKA are enable settings, not steering engagement feedback.

Diagnostic text includes LATERAL_INDICATOR_RAW/TIMEOUT when readable. Missing/failed reads
remain missing; raw values are not user-facing driving-state labels. A verified enum mapping or
paired OEM display/feedback observations are required before enabling the centering indicator.
No touchscreen driving-assistance writes are included.

Analysis boundary: shell inspection of existing decompilation, disassembly, symbols and historical
logs supplied the evidence above. REA Android required an unavailable JADX provider; its native
Hopper bridge could not start (license dialog, then missing Python framework). No successful REA
function-analysis Evidence ID was produced. The temporary native document was closed through the
UI; the provider did not acknowledge automated cleanup and retained `/tmp/rea-HGIQUh`.

## Validation

Pure protocol tests cover PA availability/length, units, reserved enum values, tri-state indicators
and expiry, follow-gap 3→2→1 changes, invalid/None clearing, and lateral non-inference. Renderer
fixtures are not real-vehicle observations. The 2026-10-16 v9 diagnostic confirms a first 1920×532
frame on Display 4 and a connected reader. Direct reads and the driver's paired values establish
the v10 correction evidence below. They do not yet establish v10 APK acceptance, moving-vehicle
accuracy, all lamp combinations or left-wheel state transitions. Record build/test/install outcomes
separately rather than treating these earlier samples as a successful new-build test.


## V6: automatic headlamps and ordinary cruise presentation (AUTO source superseded by V11)

- `AUTO_HEADLIGHTS`: standard `HEADLIGHTS_SWITCH` `289410576 / 0x11400e10`.
  OEM `carservice/sources/android/hardware/automotive/vehicle/V2_0/VehicleLightSwitch.java`
  defines OFF=0, ON=1, DAYTIME_RUNNING=2, AUTOMATIC=256. Only 256 lights the A icon;
  this does not assert the dipped beam is on. Other valid modes return false; unknown,
  unavailable and expired values stay unknown. The property is only polled if advertised readable.
  The 2026-10-16 direct read and v9 diagnostic both returned 256 and decoded AUTO. This establishes
  readable feedback on this car; the driver did not independently confirm the selector position.
- `CRUISE_STATE`: `CLUSTER_ADAS_CRSCTRLRSTS` `557849289`, separate from ACC's `AsyALgtSts`.
  OEM `adapterservice/sources/vendor/ecarx/xma/automotive/vehicle/V1_0/CrsCtrlrSts1.java`
  and `api/sources/ecarx/car/hardware/annotation/CrsCtrlrSts1.java` define Off=1, Stb=2, Actv=3.
  Decoder preserves these as OFF/STANDBY/ACTIVE; zero, fractional, reserved and expired values are unknown.
  The native dashboard displays ordinary cruise only with a positive STANDBY/ACTIVE reading AND explicit
  `ASSIST_MODE=OFF`. An ACC/GPilot selection takes priority and uses its own state; an expired/missing
  mode never turns the ordinary-cruise icon green. This cross-property interpretation and actual firmware
  availability still require a vehicle-side observation; local tests establish decoder/presentation behavior only.
- No vehicle writes have been added. ACC target vendor scaling and lateral engagement remain unresolved
  as described above. No simulated values are injected into the APK.


## V9 investigation and reader corrections (before the live capture)

The driver reported fuel percentage, lamps and remaining range did not match the car. During the
initial v9 implementation no ADB device was attached, so the following changes addressed visible
reader/diagnostic issues without confirming that session's cause. The later same-day live capture
now confirms configuration filtering and the tire/temperature issues described in the v10 section.

Changes with bounded scope:

- Native-panel vendor INT32 bindings now also accept a single float when the int32 array is empty.
  The prior native Bosch→RX audit established that renaming a property preserves its numeric payload
  slot; this compatibility previously existed only for raw ACC target diagnostics. Existing exact
  enum/range validators still apply: e.g. lamp 1.0 is on but 1.5 stays unknown. Standard properties,
  ordinary CarPlay bindings and PA BYTES envelopes are not broadened.
- The old three-second timeout applied to a whole round of roughly 35 properties. A round could be
  making progress yet be cancelled and permanently stop the source. The watchdog now waits in
  bounded three-second windows and stops only when a window has **no completed operation**.
  The fixed global reader pool, interruption, close behavior and clear-on-stall remain in place;
  a genuinely blocked Binder call does not cause unbounded replacement threads.
- `diagnosticReport()` records each allowlisted binding's config/readability/area, result code,
  value status, a bounded int/float sample, PA envelope fields, event age and last accepted read age.
  The diagnostic export can distinguish missing property, unavailable PA, wrong payload, invalid
  enum, rejected timestamp and a stale reader. It does not log identity, coordinates or arbitrary
  byte payloads. Unmapped front fog is visible as an unsupported capability in the matrix.

ON_CHANGE freshness is **not changed or weakened**: old positive timestamps on fuel, DTE and lamps
were already valid while `get()` returned the correct available property. Each successful getter
refreshes the read receipt; 2.5 seconds without another accepted response expires the display.
Failed/invalid reads clear immediately. Zero/future timestamps are still rejected; there is no
present capture proving a safe vendor exception. Speed still additionally needs a source event
within two seconds for ordinary CarPlay/iPhone use.

The new source-adapter tests reproduce repeated old-event fuel/lamp reads, confirm they remain
visible while getters succeed and expire when reads stop, reject fractional float lamp enums,
expose unavailable PA metadata, and distinguish a progressing round from a stuck reader.
These are controlled test fixtures, not vehicle observations.

Some historical paths in the earlier sections refer to temporary decompilations from the v5/v6
investigation. On 2026-10-16 their Java and QNX log files had been removed, although directories
remained. Their prior findings are retained as historical analysis, not falsely claimed to have
been re-inspected in this turn. The FX11 demo mapping and saved 2026-10-11 device verification
files are still available; the native vehicle-service and disassembly also remain. See the
availability matrix for exact durable source paths and the limits of the saved evidence.

## V10 correction evidence — live FX11 capture, 2026-10-16

The 21:54 direct sample queried 31 specified properties; 29 returned available values at area 0,
including 15 absent from the full configuration directory. The 21:57 v9 diagnostic independently
reported `config missing; lastAcceptedAgeMs=never` for fuel, indicated DTE and all four tires.
Its 31 dashboard bindings are a different set from the 31 directly queried properties.

| Signal | Direct value | Driver/OEM comparison and v9 behavior |
| --- | --- | --- |
| Fuel | PA `(1,17,0,0)` | mapped to 17%; no independent fuel-percent reference supplied; v9 skipped it |
| Indicated DTE | PA `(1,111,0,0)` | 111 km matches the driver; v9 skipped it and used standard 50000 m / 50 km |
| Four tires | each PA `(1,23,0,0)` | each 2.3 bar per driver; v9 skipped them and its old multiplier was also wrong |
| Vendor ambient | FLOAT 23.3°C | consistent with the driver's integer 23°C; OEM rounding not confirmed; v9 used standard 25°C |
| Low beam | int 1 then int 0 | v9 accepted both; driver confirmed automatic/manual shutoff during sampling |
| ACC target | vendor raw 0; standard get failed | raw is readable, but no nonzero units/scale evidence; target remains unknown |
| Lateral indicator | RX and cluster alias both raw 1; timeout raw 0 | no verified active-state enum; centering remains unknown |

The seven missing-config exceptions apply only to the first four table rows (one fuel, one DTE,
four tire and one ambient binding). Other absent properties, including turn indicators, gear and
unconfirmed ADAS signals, do not gain blanket probing in this change. Return result, value status,
property/area match, payload/PA validity, bounds, timestamps and expiry are still enforced.
The three dashboard layouts and normal CarPlay share no new UI behavior from this correction.

V10 native `DashboardSnapshot.rangeKm` and `ambientC` use only their verified vendor signals.
The standard range (50 km) and temperature (25°C) contradicted the same-session OEM values,
so they must not replace failed or expired vendor feedback in the native dashboard. Standard reads
may remain in diagnostics, and ordinary CarPlay's base bindings are unchanged.

The FX11 vhal demo's “property read and subscribe” screen uses the same underlying vehicle-property
service. It can issue an explicit read even when an ID is absent from the directory; DiPlay previously
filtered it out before issuing `get()`. V10 uses that direct-read path for these verified feedback
bindings inside DiPlay. It does not scrape the demo UI or require the demo to remain running.
Successful direct reads do not prove that subscriptions for unadvertised IDs work; this correction
retains periodic reads and their freshness/expiry rules.

Durable evidence under the workspace's `outputs/fx11-live-20261016/`:

- [Live audit and paired values](../../../outputs/fx11-live-20261016/FX11实车数据核对.md)
- [Direct values with configuration matches](../../../outputs/fx11-live-20261016/baseline-with-configs.json)
- [v9 DiPlay FX11 diagnostic](../../../outputs/fx11-live-20261016/diplay-fx11-diagnostics.txt)
- [Complete configuration directory](../../../outputs/fx11-live-20261016/car-property-configs.txt)

These are pre-fix observations. V10 packaging, installation and post-fix verification must be recorded
after execution; this document does not claim that the new APK has already passed a vehicle test.

## V11 lamp and RESET follow-up (2026-10-16)

Driver operated left indicator during a 100-second capture: RX 557873489 and APA
557848164 both toggled 0/1. RX left/right 557873489/557873490 are known FX11 demo
feedback IDs but absent from the config list. Add only these two IDs to the prior seven
missing-config read exceptions. Values/status/area/timestamps still require validation.
Right-turn raw remained zero; its physical on-state is not yet verified.

Standard HEADLIGHTS_SWITCH stayed 256 despite the driver's report of AUTO being off.
Remove that source entirely from the native panel. The new read-only ECARX source
uses SETTING_FUNC_LAMP_EXTERIOR_LIGHT_CONTROL=537136640, global zone Int.MIN_VALUE.
AUTO=537136643 is true; OFF=0, POSITION=537136641 and LOW=537136642 are false;
AHBC=537136644 or unsupported values remain unknown. Require active function and
advertised zone/value; fail/disconnect/expiry clears the indication. Actual illuminated
lamps continue to use VHAL feedback independently. This selector requires vehicle validation.

RESET has no named SDK constant. Add the documented DIM_MAP_FULL_SCREEN=300050 to
the finite learning candidates only; never assume it is RESET. Six OneOS callback types
are recorded in a bounded diagnostic buffer. Only confirmed short-click callbacks can
learn or act; raw/holding/long/double events do not cycle. MODE=200110 remains the
existing three-screen animation toggle, separate from the saved content key.

### V11 first installed capture, 22:36

`DiPlay-20261016-223629-490.txt`: VHAL 23/31 readable, 9 direct-read bindings; both
turn signals accepted fresh value 0 (not a physical blink-on UI validation). Fuel=17%,
DTE=111 km, OEM ambient=23.3°C, FL/FR/RL/RR=2.4/2.3/2.3/2.4 bar.
ECARX exterior-light function was not active: AUTO remains unknown. The source now
keeps an inactive connection and rechecks after 30 seconds; transport failures back off
2/5/15/30 seconds instead of recreating a connection every 500 ms.

An initial installed process hit Android BinderInternal GcWatcher finalization timeout.
The stack alone does not prove its cause; bounded SDK retries reduce connection churn.
A read-only Service.dump entry now exposes the existing state without changing the
foreground app. Recheck stability after installing the final build.


## V12: 本车 MODE 校准与未知 AUTO 图标 (2026-10-16)

- 本车单独短按 MODE，新增完整 `200400` down/up/short 回调；此前写死的 SDK MODE `200110` 不适配本车。拉取的 `com.geely.inputservice` 1.0.20260509G(5d) 上游数组含200400、不含200110。SDK对200400的名称仍为WECHAT，物理对应依据是本次隔离按压，不能推广为所有吉利车辆的固定映射。
- 新增安装级 `native_dashboard.mode_key`，未设置仍200110；本车部署保存已确认200400。注册、分流及诊断统一使用保存值，翻页键与MODE禁止冲突，未确认的RESET继续未绑定。旧冲突配置读作未绑定；学习忽略当前MODE，避免误存成翻页键。
- 三套样式中，AUTO状态未知时完全不绘制该灯图标与A字；已知开/关保持绿/暗灰。保留其它灯、三套仪表、普通CarPlay及V10油量/续航/胎压/温度读取。ACC设定速度及车道居中未确认项仍未知。
- RESET在前一轮14候选监听内，两次隔离短按未产生新回调；这只是该外发路径的阴性结果，不能证明车辆不发送RESET，也未据此猜码绑定。后续范围限定为本车InputService固定31唯一码的临时被动监听，以及HW_KEY_INPUT单属性订阅；均不拦截、不写车辆属性。
- 原始静态与运行时证据保存于 `outputs/diplay-native-dashboard-v11/device/inputservice-analysis/` 和 `runtime-reset-isolated.txt`。本节先记录代码与证据边界，实车安装/最终读回应以V12交付目录installation与device记录为准。
