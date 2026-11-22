# Remote Agents

An Android app to follow and drive the coding agents (Claude Code, Codex) running on your own machines,
privately: through your VPN, or a public server behind an HTTPS proxy with a pinned key. It is a client of
[even-terminal](https://www.npmjs.com/package/@evenrealities/even-terminal)'s HTTP + SSE API (built against
v0.10.4), written from the protocol only.

GPL-3.0-or-later. Decisions and their reasons: [docs/decisions.md](docs/decisions.md).

## What it does

- **Servers**: pair by scanning even-terminal's QR code or pasting its URL; several servers; connection test
  (version, model, account, public-tunnel warning); HTTPS proxy with key pinning; re-pair on token change.
- **Sessions**: list with state (working, needs you), agent, folder, last activity; search; Claude/Codex filter;
  pull to refresh, auto-refresh every 10 s; start a session (agent, working directory with recent folders,
  first prompt).
- **Session**: live conversation from the event stream: prompts, streamed Markdown replies with code blocks,
  compact tool rows like Claude Code's (Bash command, Edit as a diff, Write, Read, Grep, web, sub-agents),
  expandable input/output, todo list and progress, thinking/writing/running status, a live time and token ticker,
  turn results (time, cost, tokens), errors, notices, earlier turns from history when the buffer is empty.
- **Prompt box**: multi-line, slash-command suggestions (sent as text), recent prompts, quick replies,
  dictation, Interrupt while working, messages can be queued while busy.
- **Approvals**: permission requests (one button per offered option) and AskUserQuestion (single or multiple
  choice, descriptions, previews, free text), in a sheet that opens by itself.
- **Background**: sessions you open or start are followed. While one works, a quiet ongoing notification keeps
  the stream open (foreground service) and you get notified of approvals, questions, finished turns and errors.
  Approve or deny, answer, or reply to a finished turn from the notification (unlock required by default).
- **Themes**: Material 3 with wallpaper colours, or Nothing (monochrome, red accent, dot-matrix headings);
  light/dark/system.
- **Voice**: the phone's recogniser (on-device when available) or your own OpenAI-compatible STT server.

Not possible through even-terminal's API (would need a sidecar or another server): switching model or
permission mode, plan mode, browsing folders, diffs beyond tool inputs, images, full transcripts.

## Workstation setup

```sh
npm i -g @evenrealities/even-terminal
even-terminal --tailscale        # or: even-terminal -i wg0   (never --lan on a shared network, never --expose)
```

Scan the QR code it prints from the app (Servers → Pair server).

### Public server (optional)

Plain HTTP to a public address is refused unless you override it per server, because the token is
effectively remote code execution on your workstation. Instead put an HTTPS proxy in front of even-terminal
and pin its key in the app, for example Caddy:

```caddy
agents.example.org {
    reverse_proxy 127.0.0.1:3456 {
        flush_interval -1   # do not buffer the SSE stream
    }
}
```

With a public CA, make Caddy keep the same key across renewals or the pin breaks every ~60 days (Caddy has a
`reuse_private_keys` option; check the Caddy docs for where it goes in your version, not verified here).

In the app choose "HTTPS proxy", host `agents.example.org`, port 443, paste the token, then "Fetch
certificate" and compare the fingerprint with
`openssl x509 -in cert.pem -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64`.
A self-signed certificate works too: the pinned key is what is trusted.

## Build

Requires the Android SDK (platform 37) and JDK 17+. On the development machine every Gradle run goes through
the memory-capped queue:

```sh
MEM=5 ~/.cache/amtest/heavy.sh timeout 1800 ./gradlew :api:test                 # protocol layer tests (JVM)
MEM=5 ~/.cache/amtest/heavy.sh timeout 1800 ./gradlew :app:testDebugUnitTest    # app rules tests (JVM)
MEM=5 ~/.cache/amtest/heavy.sh timeout 1800 ./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/
./gradlew --stop
```

Install on the phone with `adb install -r app/build/outputs/apk/debug/app-debug.apk`. No emulator is used.

## Layout

- `api/`: pure Kotlin/JVM. Message model, HTTP client, reconnecting SSE stream with replay de-duplication and
  restart detection, transcript reducer, Markdown parser, tool formatting, host policy, TLS pinning. Tests use
  a scripted fake even-terminal (`FakeEvenTerminal`).
- `app/`: Android. `data` (encrypted server store, settings, session hub, alert rules), `notify` (notifications,
  foreground service, notification actions), `voice`, `ui` (Compose screens, themes).
