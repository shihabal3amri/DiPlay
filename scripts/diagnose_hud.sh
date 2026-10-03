#!/usr/bin/env bash
# Diagnose why DiPlay's BYD HUD (SOME/IP path) fails on DiLink 4.0 China.
#
# Usage: bash scripts/diagnose_hud.sh
# Run WHILE the factory Gaode map is actively casting to the HUD, so its live
# service bindings show up in `dumpsys activity services`.
#
# What we compare:
#   DiPlay hardcodes the DiLink 5.0 (overseas) gateway:
#     package com.ts.car.someip.service
#     class   com.ts.car.someip.service.manager.SomeIpServerService
#     action  com.ts.car.someip.SomeIpServerService
#   If DiLink 4.0 China ships the same SOME/IP stack under different names,
#   the factory Gaode will still be bound to it — and this script catches it.

set -u
OUT="scripts/probe/hud_dump"
mkdir -p "$OUT"

hr() { printf '\n=== %s ===\n' "$1"; }

hr "0. ADB link"
adb devices | tail -n +2 | grep -q 'device$' || { echo "!! no device — adb connect <head-unit-ip>:<port> first"; exit 1; }
adb devices

hr "1. Firmware"
adb shell getprop ro.build.fingerprint | tr -d '\r'
adb shell getprop ro.product.model   | tr -d '\r'
adb shell getprop ro.build.version.release | tr -d '\r'

hr "2. Candidate packages (someip / ts.car / hud / amap / navi)"
adb shell pm list packages | tr -d '\r' \
  | grep -iE 'someip|ts\.car|hud|amap|gaode|autonavi|clusterdebug|navi' | sort || echo "  (none matched)"

hr "3. DiPlay's hardcoded targets present on this firmware?"
for p in com.ts.car.someip.service com.byd.amapservice com.byd.clusterdebug; do
  if adb shell pm list packages "$p" 2>/dev/null | tr -d '\r' | grep -q "package:$p"; then
    echo "  present: $p"
    adb shell dumpsys package "$p" 2>/dev/null | grep -m1 -E 'versionName' | tr -d '\r' | sed 's/^/    /'
  else
    echo "  MISSING: $p   <-- if this is DiPlay's gateway, the bind can never succeed"
  fi
done

hr "4. LIVE service snapshot (dump while factory HUD is casting)"
adb shell dumpsys activity services > "$OUT/services.txt" 2>/dev/null
wc -l "$OUT/services.txt"
echo "-- ServiceRecords touching someip/ts.car/hud/amap --"
grep -nE 'ServiceRecord' "$OUT/services.txt" | grep -iE 'someip|ts\.car|hud|amap|autonavi' | head -30 || echo "  (none)"
echo "-- who is bound to whom (ConnectionRecord clients) --"
grep -nE 'ConnectionRecord|client=|act=|cmp=' "$OUT/services.txt" \
  | grep -iE 'someip|ts\.car|hud|amap|autonavi' | head -40 || echo "  (none)"

hr "5. Is a HUD exposed as a display?"
adb shell dumpsys display 2>/dev/null | grep -iE 'hud|DisplayDeviceInfo' | head -25

hr "6. Recent SOME/IP / HUD logs"
adb logcat -d -t 800 2>/dev/null | tr -d '\r' | grep -iE 'someip|hud' | tail -40 || echo "  (quiet)"

hr "7. Cluster display visibility forensics — can an ADB-free path exist?"
adb shell dumpsys display > "$OUT/display.txt" 2>/dev/null
echo "-- every display with its flags --"
grep -E 'DisplayDeviceInfo|mDisplayId=' "$OUT/display.txt" | head -30
echo "-- the cluster projection display in particular --"
grep -iE -A8 'fission|XDJAScreenProjection' "$OUT/display.txt" | head -30 || echo "  (not even shell sees it)"
echo "  FLAG_PRIVATE (0x4) on it  => hidden from ALL apps by firmware; the adbd side-door is the only way."
echo "  no FLAG_PRIVATE           => the user is right, an ADB-free path likely exists — dig further."
echo "-- does the OEM export an official projection API? --"
for p in com.byd.containerservice com.byd.automap; do
  echo "  [$p]"
  adb shell dumpsys package "$p" 2>/dev/null | tr -d '\r' | grep -iE 'exported=true' | head -10
done

hr "8. Next step"
echo "Keep the factory HUD casting ON, then toggle it OFF and ON once while:"
echo "  adb logcat -c && adb logcat | grep -iE 'someip|hud'"
echo "The service the factory app binds to in step 4 is the one DiPlay must target."
