# Install and connect

1. Park the car. Download `DiPlay-0.2.8.apk` from the official GitHub release linked on the website.
2. Install on the Android head unit using its supported APK installation method. Do not install on the iPhone. Update over an existing DiPlay beta to retain settings and pairing records; the signing key is unchanged.
3. Open DiPlay. Grant the permissions requested for the features you use: Bluetooth/Nearby devices, Wi-Fi/Location on older Android, and microphone for Siri/calls. Allow notifications for connection controls.
4. Close other phone-projection apps before connecting.

## Wireless

Built-in car hotspot is the default. Open **Settings → Connection setup → Built-in car hotspot**, turn on the car hotspot at 5 GHz if available, and save its exact name and password. Keep Bluetooth and Wi-Fi enabled on the iPhone, pair it with the car, then tap **Connect phone**. The phone joins automatically after the Bluetooth handshake; manual Wi-Fi joining and ADB are not required. **Choose iPhone** changes the selected paired device. See [the setup guide](CONNECTION_SETUP.md).

Wi-Fi Direct remains an alternative on Android 10+. The Local hotspot option has been removed; existing selections migrate to built-in hotspot. USB remains available.

## USB

Connect the iPhone to a USB **data** port with a data-capable cable and choose **Connect with USB**. Approve USB access, Trust/CarPlay and the local VPN permission if requested. The local VPN carries the USB network link; it is not an internet VPN service. Charge-only ports/cables cannot work.

## Settings

Swipe down with three fingers in CarPlay to open the CarPlay settings overlay. CarPlay keeps running underneath: **Save and reconnect** applies the changes with one reconnection, while **X**, Back or discarding changes closes the overlay without interrupting CarPlay. The DiPlay home and settings pages remain available from the home screen. In the DiPlay settings page, icon/text size, resolution and frame rate use **Apply and reconnect** during an active session. A selection alone does not apply; Cancel preserves the old setting. When disconnected, **Save** applies to the next connection. Other settings also apply on the next connection.

Start with 30 fps, Efficient video (HEVC) off and Default icon/text size. Try 80% or 60% resolution for a slower head unit. Some iPhone/head-unit combinations still ignore icon/text scaling.

Each resolution option is a fixed size negotiated with the iPhone; the settings page shows it after the first connection. When the car's camera or 360° surround view resizes the screen, CarPlay keeps that resolution and is letterboxed instead of reconnecting.


## Automatic start

By default DiPlay starts only when you open it. Two opt-in settings under **Automatic connection** change that:

- **Open when your iPhone connects** — DiPlay opens and connects when the iPhone you chose joins the car’s Bluetooth. This also works when the head unit wakes from sleep, which is what most cars do between drives.
- **Open after the car starts** — DiPlay opens after a full restart of the head unit. It depends on the head unit’s own startup settings.

On Android 10 and newer, tap **Allow display over other apps** on the same page once; Android only lets an app open itself from the background with that permission. Without it, DiPlay shows a notification to tap instead. If your head unit has a background-app or autostart list in its own settings, allow DiPlay there too.

## Connection recovery and reports

If reinstalling left an old group, close other projection apps, then use **Settings → Wireless connection help → Reset CarPlay Wi-Fi**. DiPlay asks before removing an unrecognized Wi-Fi Direct group. Updating in place is preferable to uninstalling.

Use **Settings → Diagnostics → Save diagnostic report** after reproducing a problem. Android 10+ saves to **Downloads/DiPlay**; Android 9 uses a document picker. Review the file, then attach it to a GitHub issue with car model, DiLink/Android versions, iPhone/iOS, transport and reproduction steps. Nothing is uploaded automatically.

APK installation restrictions are controlled by your car's firmware. ADB is optional if your car supports it, not an app runtime requirement:

```sh
adb install -r DiPlay-0.2.8.apk
```

Only use a trusted computer. A different signing certificate cannot update this build; do not uninstall until you have saved any reports you need.

## BYD navigation

See [BYD navigation displays](BYD_NAVIGATION.md) for the firmware scope, map metadata requirements, settings and cleanup behavior. No runtime ADB starter is required.
