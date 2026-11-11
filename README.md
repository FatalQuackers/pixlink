# Pixlink 

Website: https://fatalquackers.github.io/pixlink/ 

**Your Pixel, on your Windows PC.** Pixlink is a free, open-source companion app that links a Google Pixel phone to a Windows PC: notifications and texts, screen mirroring with control, one-click hotspot, calls through your PC, your photo gallery and your phone's files.

> Pixlink is an independent open-source project. It is not made by, affiliated with, or endorsed by Google or Microsoft. "Pixel" is a trademark of Google LLC.

## Features

| Feature | Works for everyone | Power mode adds |
| --- | --- | --- |
| Notifications & texts | Mirror notifications, reply to SMS / RCS / chat apps | — |
| Screen mirroring | See your phone screen live | Mouse, keyboard and scroll control |
| Hotspot | One tap opens hotspot settings, PC joins automatically | Real on/off toggle from the PC |
| Calls | Dialer, contacts, calls through your PC's Bluetooth | Call history |
| Photos | Full gallery | — |
| Files | Pick folders to share | Browse all phone storage |

**Power mode** uses [Shizuku](https://shizuku.rikka.app/) and a one-time Wireless Debugging pairing. It's optional. See [docs/POWER_MODE.md](docs/POWER_MODE.md).

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

## Building

## Status

Early development — the code is scaffolded and the core flow (pairing → notifications → mirroring) is the first thing to get working on real hardware. See open issues and the project tracker.

## Contributing

PRs welcome! See [CONTRIBUTING.md](CONTRIBUTING.md).

## Support the project

Pixlink is free forever. If it helps you, consider sponsoring development

Website: https://fatalquackers.github.io/pixlink/ 

## License

[MIT](LICENSE)
