# Pixlink protocol (v1)

The Windows app is the **server**; the Pixel is the **client**.

| Channel | Port | Transport | Content |
| --- | --- | --- | --- |
| Control | 47100 | TLS 1.3 over TCP | Newline-delimited JSON messages (UTF-8) |
| Mirror | 47101 | TLS 1.3 over TCP | Length-prefixed H.264 Annex-B frames |
| Files | 47100 | Control channel | Base64 chunks of 256 KiB (see `file.*`) |

Discovery after pairing: the PC advertises mDNS `_pixlink._tcp.local`, TXT `id=<pcId>`. The phone remembers the last IP and falls back to mDNS.

## Pairing

1. PC generates (once) a self-signed RSA-2048 certificate and stores it in the user profile.
2. PC shows a QR code containing:

```
pixlink://pair?v=1&host=192.168.1.20&port=47100&token=K3J9...&fp=SHA256:AB:CD:...&name=DESKTOP-01
```

3. Phone scans it, opens TLS to `host:port`, and **only accepts** the server certificate whose SHA-256 matches `fp`.
4. Phone presents its own self-signed client certificate and sends `pair.request` with the token.
5. PC checks the token (single use, expires after 5 minutes), stores the phone's certificate fingerprint and replies `pair.accept`.
6. Future connections: both sides pin each other's fingerprint; no token needed.

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

### Notifications & messages
| Type | Direction | Payload |
| --- | --- | --- |
| `notif.posted` | phone → PC | `{key, app, appName, title, text, time, canReply, isMessage, conversation?}` |
| `notif.removed` | phone → PC | `{key}` |
| `notif.dismiss` | PC → phone | `{key}` |
| `notif.reply` | PC → phone | `{key, text}` |
| `notif.action` | PC → phone | `{key, actionIndex}` |
| `app.icon` | phone → PC | `{app, pngBase64}` (sent once per app) |

### Mirroring & control
| Type | Direction | Payload |
| --- | --- | --- |
| `mirror.start` | PC → phone | `{maxSize, bitrate, fps}` |
| `mirror.started` | phone → PC | `{width, height}` — phone then opens port 47101 |
| `mirror.stop` | either | `{}` |
| `input.touch` | PC → phone | `{action: down|move|up, x, y}` normalized 0..1 (Power mode) |
| `input.scroll` | PC → phone | `{x, y, dy}` (Power mode) |
| `input.key` | PC → phone | `{keycode}` or `{text}` (Power mode) |
| `input.nav` | PC → phone | `{button: back|home|recents}` (Power mode) |

Mirror channel framing: `[uint32 big-endian length][uint64 pts microseconds][NAL bytes]`. First frame carries SPS/PPS.

### Hotspot
| Type | Direction | Payload |
| --- | --- | --- |
| `hotspot.state` | phone → PC | `{enabled, ssid?, password?}` |
| `hotspot.set` | PC → phone | `{enabled}` — Power mode toggles directly; otherwise phone opens settings |

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
