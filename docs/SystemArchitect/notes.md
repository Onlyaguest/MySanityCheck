# SystemArchitect — Design Notes (Babashka/Clojure)

## A. Architecture Adapted for Babashka

### Tech Stack

| Layer | Choice | Notes |
|-------|--------|-------|
| Runtime | **Babashka** | Fast startup, single binary, long-lived daemon. No heavy computation — bb is sufficient. |
| HTTP Server | **http-kit** (built into bb) | Ring-compatible, async. Serves read-only API + Discord Interactions Endpoint. |
| SQLite | **pod-babashka-go-sqlite3** | Babashka pod. Reads Apple's knowledgeC.db, writes to ems.db. |
| Scheduler | **chime** or manual loop | Morning calibration (08:00), evening review (21:00), Screen Time polling (30 min). |
| Discord | **REST API + Interactions Endpoint** | No gateway WebSocket. Slash commands via HTTP POST. Alerts via webhook. |
| Dashboard | **Vercel** static HTML/JS | Fetches from API. No bb involvement. |
| Deployment | **macOS launchd** | Single bb process, auto-starts on login. |
| Config | **EDN files** | Clojure-native. Energy rates, thresholds, event types in `config.edn`. |

### Data Flow

```
┌──────────────────────────────────────────────────┐
│              USER'S MAC (launchd)                 │
│                                                   │
│  bb src/ems/core.clj  (single process)            │
│                                                   │
│  ┌────────────┐ ┌──────────┐ ┌────────────────┐  │
│  │ collector/  │ │collector/│ │ collector/     │  │
│  │ screentime  │ │ roam     │ │ health (future)│  │
│  └─────┬──────┘ └────┬─────┘ └───────┬────────┘  │
│        │             │               │            │
│        ▼             ▼               ▼            │
│  ┌────────────────────────────────────────────┐   │
│  │          ems.db (SQLite via pod)            │   │
│  └───────────────────┬────────────────────────┘   │
│                      │                            │
│                      ▼                            │
│  ┌────────────────────────────────────────────┐   │
│  │       engine/ (pure functions)              │   │
│  │  compute-energy, compute-time-quality,     │   │
│  │  compute-mood, decide, detect-alerts       │   │
│  └──────┬─────────────────────┬───────────────┘   │
│         │                     │                   │
│         ▼                     ▼                   │
│  ┌──────────────┐   ┌──────────────────┐          │
│  │ api/         │   │ discord/         │          │
│  │ http-kit     │   │ REST + webhook   │          │
│  │ GET /state   │   │ /state slash cmd │          │
│  │ (read-only)  │   │ silent alerts    │          │
│  └──────────────┘   └──────────────────┘          │
│                                                   │
└───────────────────────────────────────────────────┘
       │ (Cloudflare Tunnel)
       ▼
┌────────────────┐   ┌──────────────┐
│Vercel Dashboard│   │ AI Agents    │
│(static page)   │   │ GET /state   │
└────────────────┘   └──────────────┘
```

### Key Decisions

1. **Single bb process.** http-kit + scheduler + Discord handler share one process. Event-driven, not CPU-bound — bb handles this fine.

2. **Discord via REST, not WebSocket gateway.** Register Interactions Endpoint URL → Discord POSTs slash commands to our http-kit server. Alerts via webhook (simple HTTP POST). No bot library needed.

3. **Engine is pure functions.** `(compute-state db as-of)` → map. No side effects. Testable.

4. **EDN config.** Energy rates, event mappings, thresholds in `config.edn`. Supports customizable rules natively.

### Project Structure

```
ems/
├── bb.edn                  # deps, pods
├── config.edn              # user rules & thresholds
├── src/ems/
│   ├── core.clj            # entry: starts server + scheduler
│   ├── db.clj              # SQLite helpers, schema init
│   ├── collector/
│   │   ├── screentime.clj
│   │   ├── roam.clj
│   │   └── health.clj
│   ├── engine.clj          # three-line fusion
│   ├── api.clj             # Ring handlers
│   └── discord.clj         # webhook + interactions
├── resources/schema.sql
└── com.ems.daemon.plist
```

### API Contract

```
GET /state
→ {"energy": 72, "energy_status": "良好",
   "mood": 65, "mood_status": "平和",
   "time_available": 3.5, "time_quality": 0.7, "time_status": "充裕",
   "recommendation": "高难度创造",
   "phase": "action",
   "alerts": []}

GET /state/history?date=2026-03-19
→ {"date": "2026-03-19", "events": [...], "snapshots": [...]}

GET /state/week
→ {"days": [...], "trends": {...}, "top_drains": [...]}
```

---

## B. Open Questions & TODOs

1. **Screen Time FDA** — knowledgeC.db requires Full Disk Access. #1 blocker. DataEngineer to confirm.
2. **iOS Screen Time** — defer to v2? Need coordinator confirmation.
3. **Daemon vs cron** — I recommend daemon (http-kit must be always-on for API). Single process.
4. **Cloud relay** — Cloudflare Tunnel (free, stable) is my pick. Alternatives: ngrok, Tailscale Funnel.
5. **Roam integration path** — API (real-time) vs JSON export (batch)? DataEngineer to confirm.
6. **Dashboard auth** — Cloudflare Access if using CF Tunnel. Otherwise bearer token.
7. **Phase field** — propose `:raw | :buffer | :action | :recovery` in state snapshot. EngineBuilder to confirm.

---

## C. What I Need From Other Agents

| From | Need |
|------|------|
| **DataEngineer** | knowledgeC.db access feasibility. Roam path (API vs export). Collected data record shape. |
| **EngineBuilder** | Agree on `(compute-state db as-of)` interface. Confirm phase field. StateSnapshot keys. |
| **DiscordDev** | Confirm Interactions Endpoint approach. Slash command list. Webhook message format. |
| **FrontendDev** | Confirm API shape works. Additional endpoints needed? |
| **QAEngineer** | Integration test boundaries. I'll provide in-memory SQLite test harness. |
| **Coordinator** | Decisions: iOS defer? Cloud relay? Dashboard auth? |

---

## D. Cross-Agent Alignment (after reading all notes)

### Resolved: Answers to Other Agents' Questions

**→ DataEngineer asks:** "Where do collectors write output?"
**Answer:** Collectors write directly to `ems.db` (SQLite) via the pod. All collectors run in the same bb process, share the pod connection. No files, no queues. Just `INSERT INTO events ...` and `INSERT INTO screentime ...`. The engine reads from the same db.

**→ DataEngineer asks:** "EDN vs JSON for internal data?"
**Answer:** EDN internally (idiomatic). JSON only at the HTTP API boundary (for AI agents and Vercel dashboard). `cheshire.core` handles the conversion in `api.clj`.

**→ DataEngineer asks:** "Config file format and location?"
**Answer:** `config.edn` at project root. Contains: db paths, Roam graph name + API token, Discord webhook URL + app ID, polling intervals, energy/mood rate overrides.

**→ DiscordDev asks:** "Where does the bot process run? How is it exposed to Discord?"
**Answer:** Same bb process as everything else. http-kit serves on `localhost:8400`. Cloudflare Tunnel exposes it to the internet. Discord Interactions Endpoint URL = tunnel URL + `/discord/interactions`. Alert webhooks go outbound — no exposure needed.

**→ FrontendDev asks:** "Where does state live so Vercel can read it?"
**Answer:** Two options:
1. **Vercel reads from the tunnel URL** (`GET /state`) — simplest, but depends on tunnel uptime.
2. **Engine pushes state snapshot to Cloudflare KV or R2** on each update — Vercel reads from there. More reliable, adds a write step.
I recommend option 1 for v0, option 2 if tunnel reliability is a problem.

**→ QAEngineer asks:** "Deployment model? Test environment plan?"
**Answer:** Daemon (launchd). For testing: `bb test` runs all tests with in-memory SQLite (no launchd needed). Integration tests use fixture `.db` files.

### Debate: Daemon vs Cron

EngineBuilder and DataEngineer lean toward cron. I maintain **daemon is required** because:
- http-kit server must be always-on for `/state` API (AI agents query anytime)
- Discord Interactions Endpoint must be always-on (Discord POSTs slash commands anytime)
- Cloudflare Tunnel needs a persistent process to maintain the connection

**Compromise:** Single daemon process that runs http-kit + scheduler. The scheduler triggers collector runs and engine recomputation on intervals (effectively cron-inside-daemon). EngineBuilder's pure functions don't care — they get called either way.

### Risk: Ed25519 Signature Verification (from DiscordDev)

DiscordDev flagged this correctly. Discord requires Ed25519 verification on every interaction POST. Options in priority order:
1. **Try `buddy-core` via bb pod or classpath** — if Ed25519 is available, done.
2. **Shell out to `openssl` or a small Go/Rust binary** for signature verification only.
3. **Cloudflare Worker as proxy** — verify signature at the edge, forward valid requests to tunnel. This is elegant if we're already using CF Tunnel.

I recommend exploring option 3 — it offloads crypto from bb entirely and adds zero latency (CF Worker runs at edge).

### FrontendDev: vercel-babashka Approach

FrontendDev proposes server-side hiccup rendering on Vercel. This is a good call — keeps the entire stack in Clojure. The dashboard becomes a Babashka serverless function that fetches `/state` from our tunnel and renders HTML. No client-side JS framework needed.
