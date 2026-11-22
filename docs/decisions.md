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
- **Buffer**: ids are one monotonic counter per session (changed in the review fixes below; first one counter
  for all sessions). 500 messages
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

## agentd driving (step 2), implementation choices (2026-11-22)

Hosting, hooks and terminal delivery, on the formats verified in `server-protocols.md`. Choices marked
**(owner)** settle an open spike question provisionally and are easy to change.

- **Packages**: `internal/approval` (pending permissions and questions, shared by hosted processes and the hook
  receiver; the design had them in `hooks`), `internal/hooks` (receiver, relay, settings installer),
  `internal/inject` (tmux, inbox, Stop-hook queue), `internal/ids`, `internal/testutil/fake{claude,pi}`
  (scripted agents; a test binary re-executes itself as one, so no model runs in tests).
- **Hook transport (owner)**: every hook is a command hook, `'<agentd>' hooks relay --port N --timeout S`.
  SessionStart cannot be an HTTP hook, and the relay reads the secret from `~/.config/agentd/hook-secret`
  (0600, created by `hooks install` or `serve`; a file others can read is refused), so the secret is not in the
  0644 settings.json and terminals need no environment variable. The relay always exits 0 (fail open). Our
  entries are recognised by a command hook running `<…agentd…> hooks relay`; install replaces them
  (idempotent), uninstall removes only them, the file keeps its key order and indentation and is backed up to
  `settings.json.agentd-backup-<UTC time>` before any change. The settings path defaults to
  `~/.claude/settings.json` and `--settings` overrides it. Receiver: `/hooks/claude` on the API port, loopback
  peers with the secret only, outside the token check.
- **Terminal permissions**: PermissionRequest waits for the phone up to `--hook-wait` (120 s; hook timeout
  wait + 15 s) while the terminal dialog stays usable; no phone answer, or Claude Code dropping the call → `{}`
  and a notification. Sessions agentd hosts get `{}` at once (their stdio host asks), so nothing is asked twice.
  `allowAlways` is offered only when Claude sent `permission_suggestions`, which go back as
  `updatedPermissions` (documented, not verified live). **(owner)** AskUserQuestion in hooked terminal sessions is
  answered from the phone (`updatedInput.answers`), as the spike verified. `canApprove` is true for a terminal
  session once it has called a hook (until SessionEnd).
- **Hosted Claude**: `claude -p --input-format stream-json --output-format stream-json --include-partial-messages
  --verbose --permission-prompt-tool stdio` plus `--session-id <new uuid>` / `--resume <id>` /
  `--resume <id> --fork-session`, `--model` when `/prompt` has `model`. One turn at a time: agentd queues later
  prompts (notification "Queued") and sends the next on `result`; interrupt is the `interrupt` control request
  and also drops the queue and cancels pending approvals. Hosted approvals are denied after
  `--approval-timeout` (S8, below). `costUsd` is the turn's increase of the cumulative `total_cost_usd`; `num_turns:0` task-notification
  results (stale background tasks after `--resume`) are ignored. The process runs in its own process group with
  the parent session's `CLAUDE*` session variables removed (agentd may be started from a Claude Code shell).
  An idle process is ended after `--hosted-idle` (30 min; 0 = never) by closing stdin, which also ends its
  background tasks; the next prompt resumes it. Shutdown closes stdin, then SIGTERMs the group after 5 s.
- **Ids**: a new session gets a random UUID; a fork's id comes from `system/init` (up to 60 s) and `/prompt`
  returns it. `force:true` never hosts a terminal session (S8, below); `fork:true` never
  touches the original.
- **No double display**: while a session is hosted its process output is the source and the transcript
  follower only advances its offset; when the process ends the follower catches up silently, and a follower
  created later for a session hosted in this run skips the backlog (the buffer already has it). Resuming loads
  the history into the buffer first.
- **Terminal delivery** (`drive`): tmux pane (found by walking the agent's parent pids to a `pane_pid`, any
  pane of the user's default tmux server), else the inbox socket (registry `messagingSocketPath` + peer token
  from `<pid>.<hash>.key`), else the Stop-hook queue (only while a turn runs, since an idle session's Stop hook
  does not fire), else `none` → 409 `session_live` as before. **(owner)** The inbox is used even though Claude
  sees the text as another session's message; tmux comes first because it delivers the user's words. A client
  can pick a method with the `drive` body field (agentd extension). tmux refuses while the session is
  `awaiting` (keys would answer the dialog); one line goes as `send-keys -l` + Enter, several lines as a
  bracketed paste (`paste-buffer -p`). Interrupt of a terminal session: Escape in its pane, else 409.
- **Errors**: 400 for "No permission pending", "No question pending", a decision not offered, a bad `cwd`; 409
  `session_live` for a method a live session does not offer.
- **pi**: `pi --mode rpc` with `--session-id <uuid>` (new), `--session <file>` (resume), `--fork <file>
  --session-id <uuid>` (fork); one prompt at a time, `abort`, `agent_settled` ends the turn (aborted →
  "Interrupted by user"). Extension dialogs go to the phone (confirm → permission, select/input/editor →
  question) within the dialog's timeout. **(owner)** agentd ships no approval extension, so hosted pi runs tools
  without asking, as pi does in a terminal.
- **Wrappers**: `agentd claude|pi [args]` creates a detached tmux session `agentd-<program>-<6 hex>` in the
  current directory running the agent directly (arguments unchanged, verified) and `exec`s `tmux attach`; inside
  tmux it just execs the agent. If a tmux server already runs, the session gets that server's environment, not
  the calling shell's (tmux behaviour).
- **Not in this step**: TLS flags, agentd's own config file, a pi approval extension, answering terminal
  questions by keys, Codex.

## agentd review fixes (2026-11-22)

Fixes from the security and correctness review of steps 1-2. Choices inside the agreed design; the ones marked
**(owner)** change wire behaviour additively and are easy to revert.

- **Request ids (owner)**: every pending permission/question has an id, its `toolUseId` (the agent's, the
  matching PreToolUse's for hook calls, or `agentd_<hex>`). `/permission-response` and `/question-response`
  accept an optional `toolUseId`; without it only a lone pending request of that kind is answered. Several
  pending, or an id no longer pending, answer `409 {"error", "code":"request_not_pending"}`. A request that
  ends unanswered publishes `permission_result` with decision `expired` (or `question_answer` with no
  answers); results carry `toolUseId`. The app sends the id from the sheet and the notification actions and
  matches results by it. Rejected: answering the newest (what clients show) without an id, since a stale tap
  would still reach a newer request.
- **Message ids per session**: the same transcript replays under the same ids after a restart, so clients see
  duplicates or a known id with other content (their reset signal), never a second copy under higher ids.
  Rejected: an epoch field in `/info` and messages (needs a client change for every client).
- **tmux delivery** is refused while the broker holds a request, while the hook receiver saw a PermissionRequest
  whose tool call has not finished, while the registry read without cache says waiting, and for sessions neither
  the registry nor the hooks report on. A pane counts only when the agent's controlling tty is the pane's and its
  process group is the foreground group. Text goes as `send-keys -l -- <text>` with a trailing `;` escaped.
- **Liveness without a registry**: every session of the directory written since an unnamed process started is
  live; only one process with one such session is an exact match (pid, pane, tmux). The rest are live with drive
  `none`, so `/prompt` answers 409 unless `force`.
- **Hook auth**: the relay never sends the secret; it signs `v1 <ts> <nonce> HMAC(secret, req…body)` and prints an
  answer only with a valid `X-Agentd-Reply` HMAC. The receiver refuses stale (±5 min) or replayed calls and calls
  with `Forwarded`/`X-Forwarded-For`/`X-Real-IP`. `hooks install` warns when agentd does not answer on the port.
- **Stop-hook queue**: entries expire after 30 min and are dropped on SessionEnd (notification "Not delivered");
  queueing without an explicit `drive` is refused when the registry says idle.
- **Hosted processes**: unregistered before `exited` closes; the idle timer marks closing under its lock;
  `WaitDelay` 5 s; `BRIDGE_TOKEN`, `PORT`, `EVEN_ALLOW_CORS` removed from their environment.
- **Bounds**: session ids must match `[A-Za-z0-9][A-Za-z0-9_-]{0,127}` (no glob patterns); `/history` reads from the
  end; SSE writes have a 30 s deadline; hub entries for unknown ids are forgotten with their last watcher.
- **settings.json**: written without HTML escaping, through symlinks, and not at all when the file changed
  between read and rename.
- **Open (owner)**: `/sessions` without `?provider=` still merges claude and pi; NothingEven never sends the
  parameter and was built against single-provider lists. Options: default to the configured provider (merged only
  with `?provider=all`), or keep the merge after checking NothingEven tolerates `provider:"pi"`.

## Remote Agents on agentd (2026-11-22)

The app uses agentd's extensions and keeps working against plain even-terminal (absent fields = old behaviour).

- **`:api`**: `SessionSummary` and `StatusResponse` parse `liveness`, `drive`, `canApprove`; `Reach` (pure,
  JVM-tested) turns them into the list label, the session banner, watch-only and the resume warning.
  `ApiException` also reads agentd's `{"error","code"}` shape, and 409 `session_live` is a
  `SessionLiveException`. `prompt()` takes `fork`/`force`, sent only when true.
- **List**: "in terminal · tmux|inbox|after the turn|watch only", "hosted", "ended" next to the provider;
  nothing for even-terminal or `unknown`.
- **Session screen**: a banner for terminal sessions ("Running in a terminal: messages are typed into it via
  tmux" / "… arrive in it as a message from another Claude session" / "… are delivered when the turn ends" /
  "watch only"). Watch-only disables the prompt box (no send, interrupt, quick replies or dictation). The
  liveness comes from the list at once and from `/status` when the stream opens and after each prompt; a
  `session_live` answer re-reads it.
- **First prompt to an existing session** asks first when it would resume the session in a new process: liveness
  `ended` or `unknown`, or no liveness at all (even-terminal, which cannot see terminal sessions; this is the
  fork the owner hit with bd72f82f). Asked once per session per app process; sessions the app started or forked
  are never asked. Notification replies are not asked (they only exist for sessions already live here).
- **Fork**: "Fork into a new session" (menu, the watch-only banner, and "Fork instead" in the resume dialog) only
  when the server reports liveness, i.e. agentd; even-terminal would ignore `fork` and resume the original.
  The fork opens as a new watched session titled "Fork: <title>".
- **Not used yet**: `force` (no UI: it writes one transcript from two processes), `drive` selection, `canApprove`
  in the UI (parsed only).

## agentd after review, owner decisions 2026-11-22

| # | Topic | Decision |
|---|---|---|
| S6 | `/sessions` without `?provider=` | Merge Claude and pi by default, **configurable** (which providers the unfiltered list includes). `?provider=` still filters. |
| S7 | Terminal sessions | Keep all three: AskUserQuestion answered from the phone through the PermissionRequest hook; inbox delivery (after tmux); Stop-hook delivery (last). |
| S8 | Hosted safety | Permission requests and questions in hosted sessions are **denied after a timeout** (default 30 min, configurable); hosted pi loads an **approval extension** so tools ask the phone; `force:true` **never** hosts a session that is live in a terminal (409 `session_live`); the additive wire extensions (toolUseId, 409 `request_not_pending`, decision `expired`, per-session ids) are accepted. |
| S9 | Cutover | Now: install the hooks (with backup), stop even-terminal, run agentd on port 3456 as a systemd user service. |
| S10 | Defaults accepted | Hook secret through `agentd hooks relay` (0600 file, not in settings.json); refuse to start without a token; network mode `expose` refuses to start; `/info` unchanged; app skips the resume warning when even-terminal already hosts the session, no "force" button, shows when a terminal session's approvals can't reach the phone. |

### Implemented (2026-11-22)

- **S6**: `--session-providers` (comma list, default `claude,pi`; env `AGENTD_SESSION_PROVIDERS`, flag wins) sets
  what `/sessions` merges without `?provider=`. Names are checked at start against the served adapters (aliases
  allowed, `claude-sync` counts as `claude`); an unknown or planned one (`codex`) stops `agentd serve` with exit 2
  rather than giving a silently empty list. Rejected: checking per request (the error would reach the phone).
- **S8 timeout**: `--approval-timeout` (default `30m`, `0` = never) is a field of the approval request, not a
  context deadline, so a host can tell "timed out: deny" from "cancelled elsewhere: say nothing". On expiry the
  broker publishes `permission_result` decision `expired` (or an empty `question_answer`) and a notification
  "The phone did not answer within 30m, so the request was denied"; Claude gets a `can_use_tool` deny with that
  message, pi gets `confirmed:false` (other dialogs `cancelled:true`). Terminal hook waits keep `--hook-wait`.
- **S8 pi extension**: `server/internal/pi/approval-extension.ts`, embedded with `go:embed`, written to
  `~/.config/agentd/pi-approval.ts` (dir 0700, file 0600, temp file + rename, rewritten when the bytes differ)
  before every hosted pi start and passed with `-e`. Hook `tool_call` (pi `extensions.md`) on `bash`,
  `powershell` (pi's shell tool on Windows), `write` and `edit`; `ctx.ui.confirm(<tool>, <command or path>,
  {signal})`; anything but a confirm blocks the call (`{block:true, reason}`), as does a missing UI. agentd reads
  the title back as the tool name and the message as `command`/`path`, so the phone shows it like a Claude tool
  call. A hosted pi refuses to start when the file cannot be written. Other tools (`read`, `grep`, `find`, `ls`,
  extension tools) are not asked about. Terminal pi (`agentd pi`) does not load it.
- **S8 force**: `provider.Hostable`: `terminal` is never hosted (409 `session_live` with or without force),
  `unknown` only with force, `ended`/`hosted` always. `fork` is unchanged. No adapter reports `unknown` yet.
- **S9**: `server/contrib/agentd.service` (user unit, `%h/.local/bin/agentd serve`, `Restart=on-failure`,
  `After=network-online.target`, PATH with `~/.local/bin`, no flags or secrets); steps in `server/README.md`
  "Install as a service". Not installed.
- **Open**: S10 says network mode `expose` refuses to start; `config.Parse` still maps it to loopback.
