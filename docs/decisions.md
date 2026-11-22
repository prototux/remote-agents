# Decisions

Owner decisions (2026-11-22) and the implementation choices made inside them. Spec:
`../NothingEven/nothingeven/docs/specs/even-terminal-android.md`, protocol: `even-terminal-api.md` (v0.10.4).

## Owner decisions

| # | Topic | Decision |
|---|---|---|
| 1 | Name, package, licence | **Remote Agents**, `net.prototux.remoteagents`, GPL-3.0-or-later |
| 2 | Architecture | Pure even-terminal client (no sidecar, no own server). Code split so a sidecar could be added later. |
| 3 | Network | VPN/private addresses over plain HTTP; a public server only over HTTPS (reverse proxy) with a pinned key. No mTLS. |
| 4 | Background | Foreground service while a watched session is busy or awaiting; stops when all are idle. |
| 5 | Design | Material 3 (dynamic colour) and a Nothing theme, switchable. Tokens copied from NothingEven, no dependency on it. |
| 6 | Voice | Both: Android `SpeechRecognizer` (prefer offline) or the owner's OpenAI-compatible STT server, chosen in Settings. |
| 7 | Codex | Supported in v1 (provider picker, badge). |

## Implementation choices

- **Modules**: `:api` pure Kotlin/JVM (message model, HTTP client, SSE stream, transcript reducer, TLS pinning,
  host policy) with JVM tests; `:app` Android (data, notify, ui packages).
- **Dependencies** (all Apache-2.0): OkHttp + okhttp-sse 5.5.1, kotlinx.serialization/coroutines, AndroidX
  Compose (BOM 2026.09.00), navigation-compose, lifecycle, `material-icons-core`, `zxing-android-embedded`
  4.3.0 (QR scanning without Google Play services). Fonts Doto and Hanken Grotesk (OFL), copied from NothingEven.
  Markdown rendering is in-house (no library).
- **Error bodies**: the server answers `{"error":"text"}` on most routes and `{"error":{code,message}}` on
  auth and `/info` (checked against 0.10.4 on the workstation). Both are parsed.
- **Initial load then live**: opening a session reads `/messages?after=0` as one batch (so already-resolved
  permission requests in the backlog never notify), then opens `/events?needReplay=true` and drops what it
  already has. When the buffer is empty (server restarted, idle session) the last turns come from `/history`.
- **De-duplication**: by message id plus a content fingerprint. A restarted server reuses ids from 1, so an id
  seen with other content means a restart: the transcript is rebuilt.
- **Liveness**: SSE read timeout of 45 s (heartbeats every 15 s keep it alive); reconnect backoff 1 s → 30 s.
  401 stops all retries and marks the server "re-pair needed".
- **No request leaves through a public address**: every server gets its own OkHttp client whose DNS drops
  public addresses (unless HTTPS with a pin, or the per-server override), and an interceptor refuses any
  request to another origin than the paired one. Cleartext is enabled app-wide in the network security config
  because Android cannot allow it per IP; the guard above is what enforces it.
- **TLS pinning**: at pairing the app reads the proxy's certificate chain without sending anything, shows the
  SHA-256 SPKI fingerprint, and the owner confirms. Afterwards only a chain containing a pinned key is
  trusted, whatever the CA (self-signed proxies work) and the host name is not checked (the key is the
  identity). On a key change the app shows the new fingerprint and asks before re-pinning. With Caddy and a
  public CA, set `reuse_private_keys` so renewals keep the key.
- **Tokens**: encrypted with an Android Keystore AES-GCM key, never logged, never in URLs; `allowBackup=false`.
- **Notification actions** (allow/deny from the notification) require device unlock by default
  (`setAuthenticationRequired`), so a locked phone in someone else's hand cannot approve a tool. Settings can
  turn this off.
- **Foreground service type** `specialUse` (no daily time limit, unlike `dataSync` on Android 15).
- **Slash commands** are offered in the prompt box and sent as prompt text; whether they act depends on the
  provider (claude-sync drives a real TUI; the Agent SDK supports some). Not verified against a live turn.
