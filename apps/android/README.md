# ZeroClaw for Android

A native Android client for a ZeroClaw gateway. It is a **companion app**: the
gateway keeps the agents, models, tools and history; the phone is a chat surface
with streaming replies, tool cards, and remote approval of tool calls.

Status: **0.1 alpha**. Chat, sessions, agents and approvals work against the
gateway's public WebSocket/REST surface. Device-side node capabilities (camera,
location, notifications) are not implemented yet.

## What it does

| Area | State |
|---|---|
| Pair with a gateway (code → bearer token) | Done |
| Streaming chat over `/ws/chat` | Done |
| Markdown replies, code blocks, tool cards | Done |
| Tool approval prompts (approve / always / deny) | Done |
| Conversation list from `/api/sessions` | Done |
| Agent switcher from `/api/agents/available` | Done |
| Stop an in-flight turn (`/abort`) | Done |
| Reconnect with backoff, connection status | Done |
| Device link: alarms and timers set on the phone | Done |
| QR pairing, push notifications, offline outbox | Not yet |
| Attachments, voice, model picker | Not yet |
| Camera, location, SMS, notification forwarding | Not yet |
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

The app allows cleartext `ws://` so first-run LAN pairing works. That is the
same trade-off the dashboard makes on `localhost`; do not rely on it over the
public internet.

## Device link: alarms and timers

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

3. The agent now sees tools named `node_<node_id>__alarm_set` and
   `node_<node_id>__timer_set`. Ask for a timer or alarm and it invokes the
   phone.

Advertised capabilities:

| Capability | Tool parameters | Device effect |
|---|---|---|
| `alarm.set` | `hour` 0–23, `minute` 0–59, `label?`, `skip_ui?`, `vibrate?` | `AlarmClock.ACTION_SET_ALARM` |
| `timer.set` | `seconds` 1–86400, `label?`, `skip_ui?` | `AlarmClock.ACTION_SET_TIMER` |

Notes:

- The phone's clock app performs the action; the app only sends the intent.
  Most clock apps honour `skip_ui=true`, but an OEM clock may still show a
  confirmation screen — that is the device owner's gate, not the agent's.
- `SET_ALARM` is declared in the manifest. No runtime permission prompt is
  needed for alarms or timers.
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
Each connected capability becomes a `NodeTool`; a disconnected phone simply
contributes no tools. Node tools still pass through the agent's tool policy and
the caller's allowlist.

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
| Device node | `WS /ws/nodes` (register + invoke/result) |

Chat sockets authenticate with `Sec-WebSocket-Protocol: zeroclaw.v1,
bearer.<token>`, which the gateway accepts alongside the `Authorization` header
and `?token=` fallback.

Client → server frames: `{"type":"message","content":…}` and
`{"type":"approval_response","request_id":…,"decision":"approve|deny|always"}`.

Server → client frames handled: `session_start`, `chunk`, `message`,
`thinking`, `tool_call`, `tool_result`, `done`, `error`, `aborted`,
`approval_request`, `history_trimmed`, `safeguard_fallback`. Unknown frames are
ignored so a newer gateway does not break the app.

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
│   ├── NodeClient.kt        `/ws/nodes` device link: register + invoke
│   ├── NodeRuntime.kt       device-link status shared with the UI
│   ├── DeviceCapabilities.kt  alarm/timer capability definitions + intents
│   └── AppViewModel.kt      state machine for pairing → chat → approvals
├── service/NodeService.kt   foreground service that owns the device link
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

- Device capabilities are limited to `alarm.set` and `timer.set`. Camera,
  location, notifications and contacts need their own capability entries and
  runtime permissions.
- Node tools are attached to the WebSocket-chat agent. Turns driven by a
  Telegram/Discord/other channel do not see them yet.
- A phone whose clock app ignores `EXTRA_SKIP_UI` will show its own
  confirmation screen before setting the alarm.
- Sending while a reply streams is queued by the gateway, but the app does not
  show a per-message outbox.
- A dropped socket does not replay an in-flight turn (same as the dashboard).
- Conversation rename/delete/archive are gateway endpoints the app does not
  expose yet.
- `ws://` is allowed app-wide; a per-host cleartext allowlist is the next
  hardening step.
