# ZeroClaw for Android

A native Android client for a ZeroClaw gateway. It is a **companion app**: the
gateway keeps the agents, models, tools and history; the phone is a chat surface
with streaming replies, tool cards, and remote approval of tool calls.

Status: **0.1 alpha**. Chat, sessions, agents and approvals work against the
gateway's public WebSocket/REST surface. Device-side node capabilities cover
the clock, location, a user-granted folder, photos, contacts, calendar, motion,
SMS/call log, notifications, camera capture, accessibility UI control and
on-phone voice transcription.

## What it does

| Area | State |
|---|---|
| Pair with a gateway (code → bearer token) | Done |
| Streaming chat over `/ws/chat` | Done |
| Markdown replies, code blocks, tool cards | Done |
| Tool approval prompts (approve / always / deny) | Done |
| Interactive questions (radio / checkbox / typed answer) | Done |
| Conversation list from `/api/sessions` | Done |
| Agent switcher from `/api/agents/available` | Done |
| Stop an in-flight turn (`/abort`) | Done |
| Reconnect with backoff, connection status | Done |
| Device link: clock, location, folder, photo library | Done |
| QR pairing, push notifications, offline outbox | Not yet |
| Attachments (inbound), voice, model picker | Not yet |
| Camera, SMS, notification forwarding, contacts | Not yet |
| OIDC / mTLS login | Not yet |

## Build

Requires JDK 17+, Android SDK Platform 36, and Gradle 9.x (the wrapper pins
9.9.2). AGP 9.5 provides Kotlin itself; there is no separate Kotlin Gradle
plugin.

```bash
cd apps/android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Unit tests (pure protocol/markdown logic, no device needed):

```bash
./gradlew :app:testDebugUnitTest
```

### Building inside Termux

The Android Gradle Plugin's `aapt2` daemon cannot load Termux's
`libtermux-exec-ld-preload.so` (`__gnu_basename: symbol not found`). Run Gradle
with the preload cleared:

```bash
env -u LD_PRELOAD ./gradlew :app:assembleDebug
```

### SDK versions

`compileSdk`/`targetSdk` are 36 because the installed SDK has no Platform 37.
AndroidX artifacts newer than the 2026.06 line require `compileSdk 37`, so the
dependency set is pinned to the last Platform-36-compatible versions. Raise the
SDK first, then the libraries.

## Connect

1. On the gateway host, make it reachable from the phone (LAN bind or tunnel):

   ```bash
   zeroclaw config set gateway.require_pairing true
   # loopback-only by default; for a LAN phone:
   # bind 0.0.0.0 (needs gateway.allow_public_bind = true)
   zeroclaw daemon
   zeroclaw gateway get-paircode
   ```

2. Open the app, enter `host:port` (e.g. `192.168.1.20:42617`) and the pairing
   code. The app POSTs `/api/pair` and stores the returned bearer token in
   app-private storage.

3. For access outside the LAN, expose the gateway over TLS (Tailscale Serve,
   Cloudflare Tunnel, or a reverse proxy) and enter the `https://` URL. Use
   `wss://` for anything that is not a trusted local network: the app's chat
   frames carry a bearer token.

Cleartext `http://`/`ws://` stays available so first-run LAN pairing works.
Before the app opens a cleartext connection it shows a one-time warning for
that origin; the operator chooses **Connect anyway** or **Cancel**, and an
acceptance is remembered for that host and port. Use `https://` or Secure setup
(mTLS) for anything outside a trusted network.

## Identity-based mTLS transport

For remote use without a public CA, enable the gateway's `[gateway.mtls]`
listener. Keep `enroll = false` and open the enrollment port only for a pairing
window. On the gateway run:

```sh
zeroclaw pair --host <public-host-or-ddns> --qr --pair-timeout 120
```

The enrollment listener closes when the timeout expires, after the first
successful pairing, or when `q` is pressed at the pairing prompt. The timeout
is capped at 600 seconds. Add `--enroll-port 42620` to use a different
enrollment port for that window; the QR advertises it automatically.

In the app's onboarding **Secure setup (mTLS)** section, scan that QR (or enter
the host and pairing code). The app fetches the gateway CA, shows the SAS to
confirm, generates its own key and CSR locally, receives a client certificate
and bearer token from the gateway, and connects over mTLS. No certificates are
copied by hand.

The pairing code is single use; after enrollment the app stores the CA, client
certificate, key, gateway id and token in app-private storage. The client
certificate is valid for 30 days and is renewed at half its lifetime: a single
deferred WorkManager job (not an hourly poll) is armed for the midpoint, and the
foreground app also renews before connecting when the midpoint has passed. The
gateway revokes the old leaf and rotates both keys on success. Use **Settings →
Transport → Renew certificate** to renew immediately, or `zeroclaw mtls list`
and `zeroclaw mtls delete` to inspect or revoke a device; revocation drops its
live connections.

For a manual fallback, issue a certificate with
`zeroclaw security issue-client-cert --ca-dir <data_dir>/gateway-mtls` and
import the three PEMs under **Settings → Transport**.

The server identity is a generated URI (`spiffe://zeroclaw/gateway/<id>`), not a
DNS name or IP, so public IP and DDNS changes do not invalidate the
certificates. The client pins the gateway CA and verifies that URI; ordinary
hostname matching is intentionally not used.

## Device link: phone capabilities

The chat socket makes the phone a *client*. Alarms and timers need the phone to
be a **node**: a second WebSocket where the device advertises capabilities, and
the gateway turns each one into an agent tool. Both halves must be on:

1. Enable node discovery on the gateway and restart it:

   ```bash
   zeroclaw config set nodes.enabled true
   ```

   Node auth uses the gateway pairing token when `gateway.require_pairing` is
   on (the default), so the same paired token works. A dedicated
   `nodes.auth_token` is supported if you rotate tokens separately.

2. In the app, **Settings → Device capabilities → Device link** must be on
   (default). It starts a `connectedDevice` foreground service, so the link
   stays up when the app is backgrounded.

3. The agent now sees one tool per capability, named
   `node_<node_id>__<capability>` (dots become `_`). Ask for a timer or alarm,
   the time, the phone's location, a file in the granted folder, or a gallery
   image, and the gateway invokes the phone. The node tools sit beside the
   gateway's own native tools, so the agent can choose the phone or the host;
   the tool descriptions tell it to ask when the user has not said which.

## Tool permissions

**Settings → Tools** lists the selected agent's tools, grouped into phone and
server tools. Each tool has four policy choices:

- **Default** — let the risk profile decide.
- **Ask** — require approval every time (`always_ask`).
- **Always** — preapprove (`auto_approve`).
- **Block** — hide it from the agent (`excluded_tools`).

The app writes these lists on the agent's `risk_profiles.<alias>` through the
gateway config API. Connected node capabilities appear under the same
`node_<node_id>__<capability>` names the model sees, so phone tools can be
preapproved from the same screen.

Advertised capabilities:

| Capability | Tool parameters | Device effect |
|---|---|---|
| `alarm.set` | `hour` 0–23, `minute` 0–59, `label?`, `skip_ui?`, `vibrate?` | `AlarmClock.ACTION_SET_ALARM` |
| `timer.set` | `seconds` 1–86400, `label?`, `skip_ui?` | `AlarmClock.ACTION_SET_TIMER` |
| `alarm.list` / `timer.list` | — | ZeroClaw-tracked entries (see below) |
| `alarm.delete` / `timer.delete` | `id` or `label?` | Drop the record; send the dismiss intent |
| `time.get` / `date.get` | — | Local time/date, zone, epoch |
| `location.get` | `timeout_secs?`, `max_age_secs?` | One location fix (LocationManager) |
| `file.list` | `path?` | List the operator-granted SAF tree |
| `file.read` | `path` | Read text (binary: base64, size-capped) |
| `file.create` | `path`, `content`, `overwrite?` | Create/overwrite a text file |
| `gallery.list` | `limit?` | Newest MediaStore images |
| `gallery.read` | `id` | Downscaled JPEG attached to the tool result |
| `photos.latest` | `limit?`, `maxWidth?`, `quality?` | Newest images, all attached |
| `device.info` / `.status` / `.health` / `.permissions` / `.apps` | `query?`, `include_system?`, `limit?` (apps) | Device, battery, memory, permission and app inventory |
| `system.notify` | `title`, `body`, `priority?` | Post a local notification |
| `contacts.search` | `query?`, `limit?` | Name search with phones/emails |
| `contacts.add` | names, `phone_numbers?`, `emails?` | Create a contact |
| `calendar.events` | `range_days?`, `limit?` | Upcoming events |
| `calendar.add` | `title`, `begin`, `end?`/`duration_minutes?`, `calendar_id?` | Create an event |
| `motion.activity` | — | Stationary / walking / running |
| `motion.pedometer` | — | Steps since boot |
| `sms.send` | `to`, `body` | Send an SMS |
| `sms.search` | `query?`, `limit?` | Search SMS |
| `callLog.search` | `query?`, `limit?` | Search the call log |
| `notifications.list` | `limit?` | Posted notifications |
| `notifications.actions` | `key`, `action` (`open`/`dismiss`/`reply`), `reply_text?` | Act on a notification |
| `camera.list` | — | Cameras and facing |
| `camera.snap` | `facing?`, `maxWidth?` | Headless JPEG, attached to the result |
| `camera.clip` | `facing?`, `durationMs?` | MP4 saved on the phone (path only) |
| `mobile.ui.observe` | — | Accessibility snapshot with node refs |
| `mobile.ui.act` | `snapshot_id`, `action` | click/set_text/scroll/tap/swipe/global |
| `talk.ptt.once` | `timeout_secs?` | Transcribe one utterance |
| `talk.ptt.start` / `.stop` / `.cancel` | — | Session-based push-to-talk |

## Voice input in the composer

The chat composer has a mic button beside Send:

- **Press and hold**, speak, release: the recording is finalized and sent.
- **Tap and let go**: the recorder keeps listening until it detects silence,
  then transcribes and sends automatically.

Two backends are available under **Settings → Voice input**:

| Backend | Where audio goes | Requirement |
|---|---|---|
| Android speech-to-text | Stays on the phone (SpeechRecognizer) | Microphone permission |
| ZeroClaw gateway | POST to `/api/transcribe`; the gateway calls its STT provider | `[transcription] enabled = true` plus a provider on the gateway |

The gateway endpoint reuses the existing transcription providers (Groq,
OpenAI, Deepgram, Google, local Whisper, or
`[providers.transcription.<type>.<alias>]`), so STT credentials stay on the
gateway. A missing or disabled provider returns a clear error in the composer.
The same `talk.ptt.*` node tools remain available to the agent; they use the
on-phone recognizer.

Notes:

- **Listing and deleting clock entries is best-effort by design.** Android gives
  third-party apps no API to enumerate or cancel the clock app's alarms and
  timers; only the set/dismiss intents exist. So `alarm.list` / `timer.list`
  return only entries ZeroClaw created (persisted in the app), and
  `alarm.delete` / `timer.delete` remove that record. `alarm.delete` also sends
  the clock app's dismiss intent: AOSP DeskClock drops a pending alarm, some
  OEMs only dismiss one that is firing, and a timer cannot be cancelled by
  intent at all. The tool text says this; the agent is told not to claim a
  pending alarm or timer was cancelled unless the tool output says so.
- The phone's clock app performs the action; the app only sends the intent.
  Most clock apps honour `skip_ui=true`, but an OEM clock may still show a
  confirmation screen — that is the device owner's gate, not the agent's.
- `SET_ALARM` is declared in the manifest. No runtime permission is needed for
  alarms or timers. Location, photos, contacts, calendar, activity, SMS and
  call log are runtime permissions granted from **Settings → Device
  capabilities**; each capability returns a pointer to that screen until its
  grant exists.
- `contacts.*`, `sms.*` and `callLog.search` need Play-restricted permissions;
  this build is sideloaded, so the grants are allowed. The emulator has no SIM
  or call history, so those tools exercise their empty/error paths there.
- `notifications.list` / `.actions` need the **Notification access** special
  grant (Settings → Device capabilities → Notification access), not a runtime
  permission. The listener keeps posted notifications in memory only.
- `mobile.ui.observe` / `.act` need the **Accessibility** special grant
  (Settings → Device capabilities → Accessibility) and a service config that
  can retrieve window content and perform gestures. Snapshots hold refs only;
  an act re-resolves the node against the current window and refuses a stale
  snapshot id.
- `camera.snap`/`clip` need the Camera runtime permission and the app in the
  foreground on Android versions that restrict background camera use.
  `camera.clip` writes an MP4 into the app cache and returns its path: the node
  protocol carries image attachments only, so video is not attached.
- `talk.ptt.*` need the Microphone runtime permission. They transcribe on the
  phone with Android's SpeechRecognizer; the composer's ZeroClaw backend is the
  path that sends audio to the gateway (`/api/transcribe`).
- **Files are confined to a folder the operator picks** with the system folder
  picker (SAF `OpenDocumentTree`). The persistable tree grant is stored with the
  pairing; `..` and absolute paths are rejected, so the agent cannot walk out of
  the tree. There is no blanket storage permission.
- `gallery.read` downscales to a 1280 px max edge JPEG and sends it as a
  base64 attachment over `/ws/nodes`. The gateway validates the MIME type and
  size cap, then attaches the bytes as a `data:` URI so the vision model can
  inspect the image. This is the only attachment kind the gateway surfaces
  today.
- The node id is a stable `android-xxxxxxxx` value stored with the pairing. It
  appears in tool names, which is why the gateway sanitizes them to
  `[a-zA-Z0-9_-]` (OpenAI-compatible providers reject `:` and `.`).
- If no app handles the intent, the tool returns an error instead of failing
  silently.

The chat socket is reopened when the device link comes up so the agent rebuilds
its tool list; a socket opened before the node connected would otherwise keep
serving the old list.

Gateway plumbing: `/ws/nodes` registers the device, and the gateway attaches a
`NodeToolSource` to the live agent capability for each WebSocket chat agent.
It is layered over the config-backed tool source, so a connected phone adds
tools instead of replacing the host's own. Each connected capability becomes a
`NodeTool`; a disconnected phone simply contributes no tools. Node tools still
pass through the agent's tool policy and the caller's allowlist, and tool
approvals reach the paired client over the chat socket's `approval_request`
frame.

## Wire contract used

| Purpose | Call |
|---|---|
| Health probe | `GET /health` |
| Pair | `POST /api/pair` `{code, device_name, device_type}` → `{token}` |
| Agents | `GET /api/agents/available` |
| Conversations | `GET /api/sessions` |
| Transcript | `GET /api/sessions/{id}/messages` |
| Stop a turn | `POST /api/sessions/{id}/abort` |
| Chat stream | `WS /ws/chat?session_id=…&agent=…` |
| Device node | `WS /ws/nodes` (register + invoke/result, optional `attachments`) |
| Voice transcription | `POST /api/transcribe` (audio/wav body) → `{text}` |

Chat sockets authenticate with `Sec-WebSocket-Protocol: zeroclaw.v1,
bearer.<token>`, which the gateway accepts alongside the `Authorization` header
and `?token=` fallback.

Client → server frames: `{"type":"message","content":…}`,
`{"type":"approval_response","request_id":…,"decision":"approve|deny|always"}`,
and `{"type":"choice_response","request_id":…,"selected":[…],
"free_form":"…","cancelled":false}`. Omit `free_form`/`selected` when unused,
or set `cancelled: true` to dismiss the question.

Server → client frames handled: `session_start`, `chunk`, `message`,
`thinking`, `tool_call`, `tool_result`, `done`, `error`, `aborted`,
`approval_request`, `choice_request`, `history_trimmed`, `safeguard_fallback`.
Unknown frames are ignored so a newer gateway does not break the app.

A `choice_request` comes from `ask_user` or `poll` with choices. The app renders
radio buttons for a single choice and checkboxes when `multi_select` is true,
plus a "type your own" field when `allow_free_form` is set.

The client owns the session id (a UUID) exactly as the web dashboard does, so
history written on the phone is visible on the dashboard and vice versa.

## Layout

```
app/src/main/java/ai/zeroclaw/app/
├── MainActivity.kt          edge-to-edge entry point
├── data/
│   ├── Models.kt            wire DTOs, socket frames, URL helpers
│   ├── SettingsStore.kt     DataStore-backed gateway URL/token/agent/device id
│   ├── GatewayClient.kt     REST: pair, agents, sessions, history, abort
│   ├── ChatSocket.kt        `/ws/chat` transport with backoff
│   ├── NodeClient.kt        `/ws/nodes` device link: register + invoke + attachments
│   ├── NodeRuntime.kt       device-link status shared with the UI
│   ├── DeviceCapabilities.kt  capability registry, clock + time/date
│   ├── ClockStore.kt        ZeroClaw-tracked alarms/timers
│   ├── LocationCapability.kt  one-shot LocationManager read
│   ├── FileCapabilities.kt  SAF folder list/read/create
│   ├── GalleryCapabilities.kt  MediaStore list/read + photos.latest
│   ├── DeviceInfoCapabilities.kt  device.* + system.notify
│   ├── ContactsCapabilities.kt  contacts.search/add
│   ├── CalendarCapabilities.kt  calendar.events/add
│   ├── MotionCapabilities.kt  motion.activity/pedometer
│   ├── SmsCapabilities.kt  sms.send/search
│   ├── CallLogCapabilities.kt  callLog.search
│   ├── NotificationCapabilities.kt  notifications.list/actions
│   ├── CameraCapabilities.kt  camera.list/snap/clip (Camera2)
│   ├── MobileUiCapabilities.kt  accessibility observe/act
│   ├── VoiceCapabilities.kt  talk.ptt.* via SpeechRecognizer
│   ├── DevicePermissions.kt runtime permission groups
│   └── AppViewModel.kt      state machine for pairing → chat → approvals
├── service/NodeService.kt   foreground service that owns the device link
│   ├── NotificationListener.kt  notification-access bridge
│   └── AgentAccessibilityService.kt  accessibility tree + gesture executor
└── ui/
    ├── theme/Theme.kt       design tokens and type scale
    ├── App.kt               root, drawer shell, splash
    ├── OnboardingScreen.kt  pairing
    ├── ChatScreen.kt        top bar, transcript, composer, approval dialog
    ├── Sidebar.kt           agents, conversations, settings entry
    ├── SettingsScreen.kt    connection editing, status, forget gateway
    ├── Markdown.kt          block/inline markdown renderer
    └── Components.kt        bubbles, tool cards, composer, status
```

## Design

The shell, spacing scale, type scale and surface hierarchy follow the OpenClaw
Android client (MIT) so the two apps feel alike in normal use, with ZeroClaw's
cyan accent (`#22D3EE`) and canvas (`#0E1015`) instead of the red palette. No
OpenClaw source is vendored; the tokens were re-expressed here.

## Known gaps

- Clock list/delete only sees ZeroClaw-created entries (Android platform limit,
  described above).
- `camera.clip` video files stay on the phone; only image attachments cross
  the node protocol.
- PTT transcription depends on the device's speech-recognition service (and on
  a usable microphone); the emulator returns "no speech".
- Accessibility actions are limited to the active window; there is no
  multi-window or scoped-app targeting yet.
- Location reads need the app in the foreground (or an OEM that relaxes
  background location for a `connectedDevice` foreground service); the tool
  fails with a clear message rather than a stale fix.
- Node tools are attached to the WebSocket-chat agent. Turns driven by a
  Telegram/Discord/other channel do not see them yet.
- A phone whose clock app ignores `EXTRA_SKIP_UI` will show its own
  confirmation screen before setting the alarm.
- Sending while a reply streams is queued by the gateway, but the app does not
  show a per-message outbox.
- A dropped socket does not replay an in-flight turn (same as the dashboard).
- Conversation rename/delete/archive are gateway endpoints the app does not
  expose yet.
- Cleartext is accepted per origin after a one-time in-app warning; the
  Android network-security-config allowlist is still app-wide.
