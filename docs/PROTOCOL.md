# Tethr Link protocol (v1)

The Windows app is the **server**; the Pixel is the **client**.

| Channel | Port | Transport | Content |
| --- | --- | --- | --- |
| Control | 47100 | TLS 1.3 over TCP | Newline-delimited JSON messages (UTF-8) |
| Mirror | 47101 | TLS 1.3 over TCP | Length-prefixed H.264 Annex-B frames |
| Files | 47100 | Control channel | Base64 chunks of 256 KiB (see `file.*`) |

Discovery after pairing: the PC broadcasts `{"pixlink":1,"pcId":"…","port":47100}` over UDP to port **47102** on every local network every 3 seconds. The phone remembers the last IP; if the paired PC shows up at a new address (new Wi-Fi, or the PC joined the phone's hotspot) it switches to it and reconnects.

## Bluetooth

The PC also advertises an RFCOMM service (UUID `6f3c1e9a-2b7d-4c5e-9a1f-8d2b3c4e5f60`) when "Allow Bluetooth connections" is on. After `hello`, the PC sends `pc.info {bt, bluetooth}` with its Bluetooth address; the phone stores it with the pairing.

The phone's **Connect using** setting picks the route: `auto` (Wi-Fi first, Bluetooth if the PC can't be reached; switches back to Wi-Fi when the PC's beacon is seen again), `wifi` or `bluetooth`. Over Bluetooth the phone bridges the RFCOMM socket to a one-time loopback port and runs the normal TLS client through it, so the encryption and certificate pinning are identical to Wi-Fi. `hello` carries `via: "wifi" | "bluetooth"`. Screen mirroring (port 47101) needs Wi-Fi.

## Find my PC, Lock PC

- `pc.ring {ring: true|false}` (phone → PC): the PC shows a looping alarm pop-up and flashes its window; `false` stops it.
- `pc.lock` (phone → PC): locks Windows.

## Quiet mode (Pixel without the always-on notification)

Opt-in on both sides. The PC (Settings → Connections → Let my Pixel find this PC over Bluetooth) advertises the same Bluetooth LE service iPhones use and sends `phoneWake: true` in `pc.info`. The phone makes a Companion Device Manager association with it (Android's own confirmation dialog), reads the PC id characteristic (which pairs them) to check it's the right PC, and calls `startObservingDevicePresence`. Android then binds `PixlinkCompanionService` whenever the PC is nearby, which keeps Tethr Link running and lets it use the network without a foreground service, so no notification. If Android refuses a background start, Tethr Link falls back to the normal foreground service.

## iPhone

Tethr Link for iPhone speaks the same protocol, with these differences:

- **Pairing:** iOS can't create a self-signed certificate, so the iPhone connects to the pairing screen without one (the PC allows that only while pairing is open) and sends `pair.request` with `platform: "ios"` and `needsIdentity: true`. The PC creates a client certificate for it and replies `pair.accept {pcName, pcId, identityP12, identityPassword}` (PKCS#12, base64). The iPhone stores it, reconnects with it and says `hello`. From then on the PC pins that certificate like an Android phone's. QR code only: receiving the UDP beacon needs an Apple permission apps outside the App Store can't get.
- **Finding the PC again:** the PC advertises `<pcId>._pixlink._tcp` with Bonjour (a placeholder listener on port 47103). The iPhone browses for it to learn the PC's new address.
- **hello:** `platform: "ios"`, `powerMode: false`, `canControl: false`.
- **Supported:** `status`, `ping`, `photos.*`, `files.roots` / `files.list` (Tethr Link's own folder on the iPhone), `file.get`, `file.put`, `file.chunk`, `clipboard.set`, `clipboard.text`, `phone.ring`. Notifications, texts, calls, hotspot and input aren't available on iOS.
- **Notifications (Bluetooth LE):** the PC publishes GATT service `6f3c1e9a-2b7d-4c5e-9a1f-8d2b3c4e5f61` with characteristics `…5f62` (read, encrypted: the PC id; reading it makes the iPhone pair), `…5f63` (write, encrypted: `[battery 0–100 or 255, charging 0/1]`) and `…5f64` (notify, keeps the link up). The iPhone app connects as a central with the `bluetooth-central` background mode, so iOS keeps and restores the link with Tethr Link closed. Once paired, the PC subscribes to Apple's ANCS on the iPhone and shows each notification as `notif.posted` with key `ios-<uid>`. Its buttons map to ANCS positive/negative actions (Answer/Decline for calls, Clear otherwise); dismissing on the PC sends Clear and never declines a call.
- **Screen casting:** started on the iPhone (Apple's broadcast button), never by the PC. The broadcast extension connects to port 47101 and first sends `"PXLI"` + `uint32 width` + `uint32 height` (big-endian), then normal mirror frames. The PC ends the cast by closing the connection.

## Several PCs

A phone can be paired with any number of PCs. It keeps one control connection per PC and remembers each PC's host, port, fingerprint and name. Notifications, battery, media and other phone → PC events go to every connected PC; replies to a request go only to the PC that asked. Screen mirroring streams to the PC that asked for it.

## Pairing

1. PC generates (once) a self-signed RSA-2048 certificate and stores it in the user profile.
2. PC shows a QR code containing:

```
pixlink://pair?v=1&host=192.168.1.20&port=47100&token=K3J9...&fp=SHA256:AB:CD:...&name=DESKTOP-01
```

3. Phone scans it (or the user types the 5-digit code, see below), opens TLS to `host:port`, and **only accepts** the server certificate whose SHA-256 matches `fp`.
4. Phone presents its own self-signed client certificate and sends `pair.request` with the token.
5. PC checks the token (single use, expires after 5 minutes), stores the phone's certificate fingerprint and replies `pair.accept`.
6. Future connections: both sides pin each other's fingerprint; no token needed.

### Pairing with a 5-digit code

While the pairing screen is open, the PC also shows a 5-digit code and its beacon (UDP 47102, every second) adds
`"pairing":true, "name":"<PC name>", "fp":"<cert SHA-256>"`. The phone listens for these, connects to the PC that
advertised (pinning `fp`), and sends `pair.request` with `token` = the 5 digits. After 5 wrong codes the PC makes a new
code, so the 100,000 possibilities can't practically be guessed within its 5-minute lifetime.

## Message envelope

```json
{"t":"notif.posted","id":"42","d":{ ... }}
```

- `t` — message type (string)
- `id` — optional request id; replies echo it
- `d` — payload object

## Messages

### Session
| Type | Direction | Payload |
| --- | --- | --- |
| `pair.request` | phone → PC | `{token, phoneName, model, androidVersion}` |
| `pair.accept` | PC → phone | `{pcName, pcId}` |
| `pair.reject` | PC → phone | `{reason}` |
| `hello` | phone → PC | `{phoneName, model, appVersion, powerMode}` |
| `status` | phone → PC | `{battery, charging, signal, wifiSsid, dnd}` |
| `ping` / `pong` | both | `{}` (every 15 s) |
| `power.state` | phone → PC | `{enabled}` — sent whenever Power mode turns on or off |

### Notifications & messages
| Type | Direction | Payload |
| --- | --- | --- |
| `notif.posted` | phone → PC | `{key, app, appName, title, text, time, canReply, isMessage, conversation?, actions:[{title, semantic}], history:[{sender, text, time, fromMe}]}`. `actions` are the app's buttons without a text box (phone + Wear OS actions); `semantic` is one of `markAsRead, delete, archive, mute, like, call` or empty. `history` holds the last 8 messages of a MessagingStyle conversation. |
| `notif.removed` | phone → PC | `{key}` |
| `notif.dismiss` | PC → phone | `{key}` |
| `notif.reply` | PC → phone | `{key, text}` |
| `notif.action` | PC → phone | `{key, actionIndex}` (index into `actions`) |
| `app.icon` | phone → PC | `{app, pngBase64}` (sent once per app) |

### Mirroring & control
| Type | Direction | Payload |
| --- | --- | --- |
| `mirror.start` | PC → phone | `{maxSize, bitrate, fps}` |
| `mirror.started` | phone → PC | `{width, height}` — phone then opens port 47101 |
| `mirror.stop` | either | `{}` |
| `input.tap` | PC → phone | `{x, y}` normalized 0..1 (Power mode) |
| `input.swipe` | PC → phone | `{x1, y1, x2, y2, ms}`; same start and end = long press (Power mode) |
| `input.touch` | PC → phone | `{action: down|move|up, x, y}` low-level, kept for compatibility |
| `input.scroll` | PC → phone | `{x, y, dy}` (Power mode) |
| `input.key` | PC → phone | `{keycode}` or `{text}` (Power mode) |
| `input.nav` | PC → phone | `{button: back|home|recents}` (Power mode) |

Mirror channel framing: `[uint32 big-endian length][uint64 pts microseconds][NAL bytes]`. First frame carries SPS/PPS.

### Extras
| Type | Direction | Payload |
| --- | --- | --- |
| `phone.ring` | PC → phone | `{ring}` — alarm sound at full volume for up to 30 s |
| `dnd.set` | PC → phone | `{enabled}` — Do Not Disturb (priority only) |
| `dnd.state` | phone → PC | `{enabled}` |
| `media.state` | phone → PC | `{active, playing, title?, artist?, app?}` |
| `media.get` | PC → phone | `{}` → `media.state` |
| `media.action` | PC → phone | `{action: play|pause|toggle|next|previous}` |
| `clipboard.set` | PC → phone | `{text}` |
| `share.text` | phone → PC | `{text}` — from "Send to PC" in Android's share menu; PC puts it on the clipboard |

Files shared from the phone arrive as `file.chunk` messages the PC didn't ask for (first chunk carries `name`); the PC saves them to Downloads\Pixlink.

Each extra can be turned off on the phone (Settings → Your PC can).

### Hotspot
| Type | Direction | Payload |
| --- | --- | --- |
| `hotspot.state` | phone → PC | `{enabled?, switched?, openedSettings?, toggleSupported, ssid?, password?}` |
| `hotspot.set` | PC → phone | `{enabled}` request; reply `hotspot.state` with the same id. Power mode switches it directly; otherwise the phone opens hotspot settings |
| `hotspot.get` | PC → phone | `{}` → `hotspot.state` |
| `hotspot.config` | PC → phone | `{ssid, password}` — the PC asks the user once; the phone stores them |

### Calls
| Type | Direction | Payload |
| --- | --- | --- |
| `call.dial` | PC → phone | `{number}` |
| `call.state` | phone → PC | `{state: ringing|active|ended, number, name?}` |
| `call.answer` / `call.hangup` | PC → phone | `{}` |
| `contacts.list` | PC → phone | `{query?}` → reply `{contacts:[{name, numbers[]}]}` |
| `calllog.list` | PC → phone | `{limit}` → reply `{calls:[...]}` or `{error:"power_mode_required"}` |

### Photos & files
| Type | Direction | Payload |
| --- | --- | --- |
| `photos.list` | PC → phone | `{offset, limit}` → `{photos:[{id, date, width, height}]}` |
| `photos.thumb` | PC → phone | `{id, size}` → `{jpegBase64}` |
| `files.list` | PC → phone | `{path}` → `{entries:[{name, dir, size, modified}]}` |
| `file.get` | PC → phone | `{path or photoId}` → stream of `file.chunk` |
| `file.put` | PC → phone | `{name, size}` then `file.chunk` messages |
| `file.chunk` | both | `{transferId, seq, last, dataBase64}` |
