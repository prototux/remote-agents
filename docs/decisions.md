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

## Server (agentd), owner decisions 2026-11-22

Trigger: even-terminal cannot see Claude Code sessions it did not start, reports them `idle`, and a prompt to
one starts a second Claude process resuming the same transcript (a fork; observed with session bd72f82f).

| # | Topic | Decision |
|---|---|---|
| S1 | Approach | Own server in Go, single binary, **drop-in replacement** for even-terminal: same API, port 3456, same token (read from `~/.even-terminal/config.json`). Tested side by side on 3457 first; even-terminal is stopped only with the owner's go-ahead. |
| S2 | Hooks | Install HTTP hooks in the global `~/.claude/settings.json`, **fail-open** (server down or phone silent → Claude Code behaves as today), via `agentd hooks install|uninstall` with a backup. |
| S3 | Prompts into terminal sessions | All three: tmux (wrapper `agentd claude|pi` starts the agent in tmux), Claude Code's cross-session inbox socket, and Stop-hook delivery of queued messages. |
| S4 | Agents in v1 | Claude Code and pi. Adapter interface ready for Codex, opencode, Hermes (not installed, untestable). |
| S5 | Provenance | Written from `even-terminal-api.md` and observed wire behaviour only; no even-terminal code read or copied. |

## agentd core (step 1), implementation choices (2026-11-22)

Follow-only: discovery, liveness, transcript tail and the full even-terminal API surface; no hooks, hosting or
injection yet. Built on the spike findings (`server-protocols.md`), which correct the design where they differ.

- **Layout**: `server/` module `net.prototux/agentd`, Go 1.27, stdlib only. Packages as in the design plus
  `internal/jsonl` (tail of files being appended to: complete lines only, partial last line buffered,
  truncation restarts). `internal/procscan` reads `/proc` (argv, cwd, start time); helpers whose `comm` is
  `claude` (ugrep) are excluded by matching argv, and pi is found as `node …/pi`.
- **Adapter interface** (`internal/provider`): Discover, Lookup, Follow (a `Follower` with `Poll`), History, Raw,
  Info, Prompt, Interrupt, Permission, Question. The registry maps `claude-sync` to `claude` and knows `codex` as
  accepted-but-unsupported. A lookup tries the provider the client named first, then the others.
- **Liveness, Claude**: the session registry `~/.claude/sessions/<pid>.json` first (status `busy|idle|waiting`
  → `busy|idle|awaiting`), validated by pid + `procStart` against `/proc/<pid>/stat` field 22 so stale files
  after a crash do not count. Fallback for claude processes the registry does not list: `--resume`/`--session-id`
  argument, else cwd → newest transcript of that project (busy if written in the last 30 s). pi: `/proc` only
  (`--session` argument or cwd → newest session file); busy when the last entry is a prompt, a tool result or a
  tool call.
- **drive** is `none` and `canApprove` false for every session in this step. `POST /prompt` to a terminal session
  answers 409 `session_live`, also with `force`/`fork` (never fork until the owner decides how); every other
  driving call answers 501 `driving not implemented yet` after the usual 400/404 checks.
- **Buffer**: ids are one monotonic counter for all sessions (unique and increasing per session too). 500 messages
  per session, but the start of a turn in progress is kept (up to 2 500) so a client joining mid-turn sees it.
  A subscriber more than 1 024 messages behind is dropped; it reconnects and replays.
- **Following**: the first `/events` or `/messages` of a session parses its whole transcript into the buffer
  (the last 500 messages stay), then polls every 500 ms; it stops 60 s after the last watcher, and the follower
  (file offset and parser state) is kept so a session is never replayed twice under new ids.
- **Turns from transcripts**: a prompt opens a turn; `turn_duration` (TUI), an interrupt, the next prompt, or the
  registry saying idle for two polls without new lines closes it (-p sessions write no turn marker). Turn end is a
  synthesized `result` (text = last assistant text block, tokens counted once per `message.id`, no cost:
  transcripts carry none) then `status idle`. The registry also turns busy periods without a prompt (compaction,
  a peer message) into status events. Peer messages, Stop-hook feedback and background-task notices are shown as
  `notification`s; slash commands as their `/name args` prompt; `<local-command-…>` output is skipped.
  `running_stats` every 10 s while a followed turn runs. `task_progress` from TodoWrite (older Claude Code) and
  TaskCreate/TaskUpdate (input shapes not verified yet).
- **/sessions** merges all providers newest first, limit 10 by default (applied after the merge), and adds
  `liveness`, `drive`, `canApprove`. Titles: Claude's `ai-title`, else the first prompt cut to 64 characters
  (as even-terminal); pi's `session_info` name, else the first prompt.
- **/info**: account from `~/.claude.json` `oauthAccount` (`organizationType` without `claude_` as
  `subscriptionType`); `model` and `version` are the last used, read from the newest transcripts (even-terminal
  0.10.4 reports the Claude Code version as `version`, observed). `extra.expose`: `off` (LAN), `tailscale`,
  `other` (interface or address). Added `server: {name: "agentd", version}`.
- **Observed even-terminal shapes kept**: `/messages` for an unknown session answers 200 with `provider: null`;
  `/sessions/:id/history` answers `{"history":[]}` for an unknown id and its `limit` counts entries (user and
  assistant text blocks), capped at 10; error texts `Missing 'sessionId'`, `Missing 'text' field`,
  `Missing 'sessionId' query parameter`, `Session not found`, `Unsupported provider "x". Supported providers: …`.
  Changed: an unknown `/api` route answers JSON 404 instead of an HTML page; `/update-check` makes no network call
  (`newestVersion` and `updateAvailable` null).
- **codex**: `/sessions?provider=codex` answers 200 `{sessions:[], error}` (even-terminal's failure shape),
  `/info?provider=codex` 501 `{"error":{"code":"provider_unsupported",…}}`, every other route 501.
- **Config**: token `BRIDGE_TOKEN` > config `token`; agentd refuses to start without one (even-terminal would make
  a per-process token nobody can read). Port flag > `PORT` > config > 3456. Network mode from flags
  (`--lan`, `--tailscale`, `-i/--interface/--if`, `--listen`, `--loopback`, mutually exclusive) or the config;
  config `expose` mode serves loopback only (no tunnels). Loopback is always served.
- **Not in this step**: hooks, hosted processes, inbox/tmux/Stop-hook delivery, TLS flags, agentd's own config
  file, tmux pane detection.
