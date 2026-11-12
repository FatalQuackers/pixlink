# Pixlink 

Website: https://fatalquackers.github.io/pixlink/ 

**Your Pixel, on your Windows PC.** Pixlink is a free, open-source companion app that links a Google Pixel phone to a Windows PC: notifications and texts, screen mirroring with control, one-click hotspot, calls through your PC, your photo gallery and your phone's files.

> Pixlink is an independent open-source project. It is not made by, affiliated with, or endorsed by Google or Microsoft. "Pixel" is a trademark of Google LLC.

## Features

| Feature | Works for everyone | Power mode adds |
| --- | --- | --- |
| Notifications & texts | Mirror notifications, reply to SMS / RCS / chat apps, Windows pop-ups with quick reply | — |
| Screen mirroring | See your phone screen live, choose quality | Click, long-press, swipe, scroll and type |
| Hotspot | One button: phone opens its hotspot switch, PC joins automatically | Phone switches the hotspot on by itself |
| Calls | Dialer, contacts, calls through your PC's Bluetooth | Call history |
| Photos & files | Full gallery, drag files both ways, shared folders | Browse all phone storage |
| Now playing | See what's playing on your phone; play, pause, skip | — |
| Find my phone | Ring it at full volume, even on silent | — |
| Do Not Disturb | Silence your phone from your PC | — |
| Clipboard | Send copied text from your PC to your phone | — |
| Send to PC | Share text, links, photos and files from any app to your PC | — |
| Battery | Low battery alert on your PC | — |

**Linking** takes seconds: scan the QR code or type the 5-digit code shown on your PC.

**Power mode** uses [Shizuku](https://shizuku.rikka.app/) and a one-time Wireless Debugging pairing. It's optional. See [docs/POWER_MODE.md](docs/POWER_MODE.md).

### Settings

- **PC:** PC name, theme, start with Windows, pop-ups (on/off, message text, sound, per app), low battery alert level, hotspot details, screen mirroring quality / frame rate / bitrate, automatic updates.
- **Phone:** device name, which apps send notifications, hide private message text, what your PC is allowed to do (ring, clipboard, music, Do Not Disturb), hotspot details, shared folders, background running, updates, welcome tour.

## How to install

Pixlink isn't on Google Play yet, so for now you install it from this page. It takes about 5 minutes.

**You need:** a Google Pixel on Android 13 or newer, a Windows 10 or 11 PC, and both on the same Wi-Fi.

### 1. Install on your Pixel

1. On your phone, open the [latest release](https://github.com/FatalQuackers/pixlink/releases/latest) and tap **Pixlink-android.apk** to download it.
2. Open the downloaded file. If your phone asks, allow your browser to **install unknown apps**.
3. Tap **Install**. If Google Play Protect warns you, tap **More details → Install anyway** (this happens because the app isn't on the Play Store yet).
4. Open Pixlink and tap **Notification access**, then turn Pixlink on. Tap **Calls, contacts & photos** and allow the permissions.

### 2. Install on your Windows PC

1. On your PC, open the [latest release](https://github.com/FatalQuackers/pixlink/releases/latest) and download **Pixlink-windows-x64.zip**.
2. Right-click the zip → **Extract All**, and pick a folder to keep it in (for example `Documents\Pixlink`).
3. Open the folder and double-click **Pixlink.exe**. If Windows SmartScreen appears, click **More info → Run anyway**.
4. When Windows Firewall asks, tick **Private networks** and click **Allow**. Pixlink needs this to talk to your phone.

### 3. Link them

1. On your PC, Pixlink shows a QR code and a 5-digit code.
2. On your Pixel, open Pixlink. After the short welcome tour, tap **Scan QR code** and point the camera at your screen, or tap **Enter 5-digit code** and type it.
3. Done. Your notifications appear on your PC within a few seconds.

### Try the phone app on your PC first (Android emulator)

You can test the Android app without installing it on your phone, using Google's free Android emulator.

1. Install [Android Studio](https://developer.android.com/studio) and open it.
2. Click **More Actions → Virtual Device Manager → Create device**. Pick **Pixel 8**, then a system image with API 35, and click **Finish**. Press ▶ to start it.
3. Drag **Pixlink-android.apk** onto the emulator window to install it.
4. Open Pixlink on your PC and click **Copy link** under the QR code.
5. In the emulator, open Pixlink, tap **Scan QR code**, then tap **Paste pairing link**. The emulator shares your PC's clipboard.

The emulator has no real phone calls or photos, but pairing, notifications and screen mirroring all work.

### Optional extras

- **Calls through your PC:** pair your phone with your PC in **Windows Settings → Bluetooth & devices → Add device**.
- **Hotspot:** click **Hotspot** in the PC app. The first time, it asks for your phone's hotspot name and password. With Power mode on, your phone's hotspot switches on by itself and your PC joins it; without Power mode, your phone opens its hotspot setting for you to flip.
- **Power mode** (screen control, hotspot toggle, call history, all files): follow [docs/POWER_MODE.md](docs/POWER_MODE.md).

### Troubleshooting

- **Pixlink doesn't open on Windows:** download the newest release. If it still won't start, it shows an error and saves details to `%LOCALAPPDATA%\Pixlink\crash.log`; please attach that file to a [GitHub issue](https://github.com/FatalQuackers/pixlink/issues).
- **It connects, then drops straight away:** update to the newest release on both phone and PC. The phone's home screen shows the reason for the last disconnect; please include it if you report a problem.
- **The phone can't find the PC:** check both are on the same Wi-Fi (not a guest network), and that Pixlink is allowed in Windows Firewall on private networks.
- **The QR code expired:** click **Get a new code** on your PC.
- **Notifications stopped:** on your phone, check Pixlink still has notification access, and that battery saver isn't stopping it.

### Updates

Both apps check for new versions by themselves. On the PC, click **Update now** in the banner and Pixlink updates and restarts. On the phone, tap **Download** on the home screen and install the new APK over the old one. You can also check manually in **Settings** on either app.

### Uninstall

- **Pixel:** long-press the Pixlink icon → **Uninstall**.
- **PC:** close Pixlink and delete its folder. To remove its settings too, delete `%LOCALAPPDATA%\Pixlink`.

## Repo layout

```
android/   Pixel app — Kotlin + Jetpack Compose (Android 13+)
windows/   PC app — C# + WinUI 3 / Windows App SDK (Windows 10 22H2+ / 11)
docs/      Protocol spec, Power mode guide
website/   Project website (GitHub Pages)
```

## How it works

1. The Windows app shows a QR code with its local IP, port, a one-time token and its certificate fingerprint.
2. The Pixel scans it and connects over your Wi-Fi using TLS. Both sides pin each other's certificate. No accounts, no cloud.
3. Messages travel as JSON over TLS (port 47100). Screen video is a separate H.264 stream (port 47101).
4. Call audio uses Bluetooth, with the PC acting as a headset.

Full details: [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Building from source

- **Android:** open `android/` in [Android Studio](https://developer.android.com/studio) and press **Run** with your Pixel plugged in (USB debugging on).
- **Windows:** open `windows/Pixlink.sln` in [Visual Studio 2022](https://visualstudio.microsoft.com/) with the **Windows application development** workload, set the platform to **x64**, and press **F5**.

Every push to `main` is built automatically by GitHub Actions and published as a release.

## Status

Early development — the code is scaffolded and the core flow (pairing → notifications → mirroring) is the first thing to get working on real hardware. See open issues and the project tracker.

## Contributing

PRs welcome! See [CONTRIBUTING.md](CONTRIBUTING.md).

## Support the project

Pixlink is free forever. If it helps you, consider sponsoring development.

## License

[MIT](LICENSE)
