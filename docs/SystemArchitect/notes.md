# SystemArchitect — Design Notes (Babashka/Clojure)

**Last updated:** 2026-03-20 12:26 (pre-reboot checkpoint)

---

## 1. What I've Built (files + status)

### Files I own / created

| File | Status | Description |
|------|--------|-------------|
| `bb.edn` | ✅ Done | Pod 0.3.13, cheshire dep, tasks: run, init, test |
| `config.edn` | ✅ Done | All energy/mood/time rates from spec v0.2, alert thresholds, complaint keywords, server port, polling intervals, `:secrets-file` pointer |
| `secrets.edn` | ✅ Done | Multi-env: `:env :staging`, `:roam {:staging {:token :graph} :prod {...}}`, `:discord {:bot-token :guild-id :staging-channel :prod-channel}` |
| `secrets.edn.template` | ✅ Done | Commented template with placeholder values |
| `.gitignore` | ✅ Done | Excludes ems.db, secrets.edn, .DS_Store, old spec files |
| `src/ems/core.clj` | ✅ Done | Full daemon: config loading, env resolution, pod init, scheduler, collector→engine→discord wiring, HTTP API, `bb init` diagnostics |
| `src/ems/db.clj` | ✅ Done | Schema init (events, screentime, daily_state), generic query/execute helpers. Pod loaded by core.clj. |

### Current project tree

```
ems/
├── bb.edn
├── config.edn
├── secrets.edn          (gitignored)
├── secrets.edn.template
├── .gitignore
├── src/ems/
│   ├── core.clj          ← SystemArchitect (me)
│   ├── db.clj            ← SystemArchitect (me)
│   ├── engine.clj        ← EngineBuilder
│   ├── engine/
│   │   ├── rates.clj     ← EngineBuilder
│   │   └── complaint.clj ← EngineBuilder
│   ├── collector/
│   │   ├── screentime.clj ← DataEngineer
│   │   └── roam.clj       ← DataEngineer
│   └── discord.clj        ← DiscordDev
└── test/ems/
    └── engine_test.clj    ← QAEngineer
```

---

## 2. What Changed Since Last Notes Update

### P0 fixes (from CodeReviewer)
- **bb.edn:** Pod version fixed `0.3.1` → `0.3.13`; added test path + task
- **db.clj:** Removed duplicate pod loading; now expects pod loaded by core.clj
- **core.clj:** Full rewrite — was a stub, now complete daemon

### P1 fixes
- **Discord: Bot API decision.** Dropped webhook URLs entirely. All Discord sends use `POST /channels/{channel-id}/messages` with bot token. `discord-config` is env-resolved by `resolve-env` in core.clj.
- **core.clj wiring:** `run-cycle!` calls `discord/send-alert!` and scheduler calls `discord/send-summary!` with `(:discord-config config)` — Discord owns the HTTP call, core owns the wiring.
- **Discord interactions route:** `POST /discord/interactions` delegates to `discord/handle-interaction` (DiscordDev's Ring handler). Handles ping (type 1) and slash commands (type 2).
- **CORS:** All routes return CORS headers. OPTIONS preflight handled.
- **`:channel` → `:channel-id`** in `resolve-env` for consistency with discord.clj.
- **Roam auth header:** Fixed `Authorization` → `X-Authorization` in init check.
- **Terminal detection:** Fixed `ProcessHandle/current` (JVM-only) → `TERM_PROGRAM` env var + `ps` via `PPID`.

### `bb init` rewrite
Full diagnostic on init:
1. Creates ems.db with schema
2. Checks Screen Time FDA — detects terminal app, prints step-by-step instructions, auto-opens System Settings
3. Checks Roam API — test query against configured graph
4. Checks Discord — sends test message to env-resolved channel
5. Prints pass/fail summary

---

## 3. Open Questions / Blockers

### Blocking
- **FDA reboot pending.** We're about to reboot tmux so FDA takes effect for kiro-cli-chat. After reboot, `bb init` should pass the Screen Time check.

### Open (not blocking v0)
1. **Cloud relay not set up.** Cloudflare Tunnel needed for Vercel dashboard + AI agents to reach localhost:8400. Not blocking local dev.
2. **Ed25519 verification skipped.** Discord interactions endpoint works but unsigned. P1 — recommend CF Worker proxy approach.
3. **Dashboard URL not configured.** `config.edn` needs `:dashboard {:url "https://..."}` once Vercel is deployed. Currently nil — interactions response omits dashboard link.
4. **launchd plist not created.** Needed for auto-start on login. Low priority — `bb run` works for now.
5. **Config rates vs hardcoded rates.** `config.edn` has rates, `engine/rates.clj` hardcodes them. Duplication. EngineBuilder should read from config or we remove config rates.
6. **DB column names vs collector output keys.** `pickups` vs `unlock-count`, `context_switches` vs `app-switches`. Nobody writes to DB yet so not blocking, but needs alignment.

---

## 4. What I Want to Tackle Next

1. **Post-reboot:** Run `bb init` to verify FDA works, then `bb run` for first live cycle
2. **Cloudflare Tunnel setup** — expose localhost:8400, get a stable URL for Discord interactions + Vercel dashboard
3. **Discord slash command registration** — one-time `POST /applications/{app-id}/guilds/{guild-id}/commands` to register `/state`
4. **launchd plist** — auto-start daemon on login
5. **Wire DB persistence** — collectors should INSERT into ems.db, not just pass data in-memory (needed for history/trends)

---

## Architecture Decisions Log

| Decision | Choice | Date | Rationale |
|----------|--------|------|-----------|
| Runtime | Babashka | 03-19 | Co directive. Fast startup, single binary. |
| Discord integration | Bot API (not webhooks) | 03-20 | Already have bot-token + channel IDs. One auth mechanism. |
| Daemon vs cron | Daemon (launchd) | 03-19 | http-kit + Discord interactions must be always-on. Scheduler runs inside. |
| Pod loading | Single point in core.clj | 03-20 | CodeReviewer P0: was double-loaded in db.clj + screentime.clj. |
| Secrets | Multi-env secrets.edn | 03-20 | `:env` key selects staging/prod. `resolve-env` flattens at startup. |
| Cloud relay | Cloudflare Tunnel (planned) | 03-19 | Free, stable. Not yet set up. |
| Ed25519 | Deferred (CF Worker proxy planned) | 03-20 | bb lacks native Ed25519. CF Worker at edge is cleanest. |

---

## 5. FDA + Launch Strategy (2026-03-20)

### The Problem
- FDA (Full Disk Access) is granted per-binary. Terminal.app has FDA, so `bb` run from Terminal.app can read `knowledgeC.db`.
- **tmux inherits the FDA of the process that started it.** If tmux server was started before FDA was granted, all tmux panes lack FDA — even after the grant. Restarting tmux server fixes it, but that's fragile.
- kiro-cli-chat runs inside tmux → no FDA → Screen Time collector fails silently.

### The Solution: Three launch paths

1. **Production: launchd (recommended)**
   - launchd services inherit FDA from the system. A plist that runs `bb run` will have FDA as long as the `bb` binary (or its parent) has FDA.
   - This is the correct long-term path. The daemon auto-starts on login, always has FDA.

2. **Development: Terminal.app directly**
   - `open -a Terminal.app` then `cd /Users/yuan/ems && bb run`
   - Works immediately. FDA inherited.

3. **Fallback: osascript relaunch from bb run**
   - If Screen Time collector fails with permission error during `bb run`, auto-relaunch in Terminal.app:
   ```
   osascript -e 'tell app "Terminal" to do script "cd /Users/yuan/ems && bb run"'
   ```
   - This gives the new process FDA via Terminal.app.

### Implementation Plan
- `bb init` already detects FDA failure and prints instructions.
- `bb run` should: attempt first Screen Time read → if permission denied → print warning + offer osascript relaunch.
- launchd plist (TODO) is the real fix. osascript is a dev convenience.

### launchd Plist (TODO — next task)
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "...">
<plist version="1.0">
<dict>
  <key>Label</key><string>com.ems.daemon</string>
  <key>ProgramArguments</key>
  <array>
    <string>/usr/local/bin/bb</string>
    <string>run</string>
  </array>
  <key>WorkingDirectory</key><string>/Users/yuan/ems</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>/Users/yuan/ems/ems.log</string>
  <key>StandardErrorPath</key><string>/Users/yuan/ems/ems.err</string>
</dict>
</plist>
```
Install: `cp com.ems.daemon.plist ~/Library/LaunchAgents/ && launchctl load ~/Library/LaunchAgents/com.ems.daemon.plist`
