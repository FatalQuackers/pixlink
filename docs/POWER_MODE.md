# Power mode

Android only lets system apps do a few things Tethr Link needs (toggle the hotspot, inject touches, read the call log, browse all storage). Power mode gets around this safely using **Shizuku**, which runs a small helper with ADB-level ("shell") permission.

## What it unlocks

- Control your phone from the PC (mouse, keyboard, scroll)
- Turn the hotspot on and off from the PC
- Call history in the Calls tab
- Browse all phone storage in the Files tab

## Setup (one time, about 3 minutes)

1. Install **Shizuku** from Google Play.
2. On your Pixel: **Settings → About phone →** tap **Build number** 7 times to enable Developer options.
3. **Settings → System → Developer options →** turn on **Wireless debugging**.
4. Open Shizuku → **Start via Wireless debugging** → **Pairing**, then follow the notification to enter the pairing code.
5. Open Tethr Link → **Power mode** → **Grant**.

Tethr Link's in-app wizard walks through these steps.

## After a reboot

On phones without root, Shizuku stops when the phone restarts. Tethr Link shows a "Restart Power mode" prompt; open Shizuku and tap **Start** again (Wi-Fi must be on).

## Is it safe?

Shizuku is open source and widely used. Tethr Link only runs the specific commands listed in `android/app/src/main/java/app/pixlink/power/`. You can revoke access anytime in Shizuku.
