# DiscordDev — Design Notes

## Architecture Decision: HTTP-Only Webhook Bot

Discord supports two bot models: Gateway (WebSocket) and Interactions Endpoint (HTTP webhook).

**Recommendation: HTTP-only webhook bot.** This is the ideal fit for Babashka:
- No persistent WebSocket connection needed
- Discord POSTs interactions to our HTTP endpoint; we respond synchronously
- Babashka's built-in `org.httpkit.server` handles incoming requests
- `babashka.http-client` handles outgoing calls (webhooks for alerts, API calls)
- Instant startup, minimal resource usage, deployable as a simple process

**Trade-off:** HTTP-only bots cannot listen to message events or presence — but we don't need those. We only need slash commands + outbound alerts.

## Components

### 1. Slash Command Handler (`/state`)
- Babashka HTTP server listens on a port (e.g., 8090)
- Discord sends POST with interaction payload
- We verify Ed25519 signature (required by Discord)
- Read current state from engine (local file or API call)
- Respond with three-line snapshot

Response format:
```
⚡ Energy: 62 🟡 | ⏱ Time: 3.2h 🟢 | 😌 Mood: 75 🟡
📊 Dashboard: https://ems.vercel.app/2026-03-19
💡 推荐: 快速冲刺
```

### 2. Silent Alerts (Outbound Webhooks)
- Engine writes alert events (Energy < 30, high fragmentation, triple-low)
- Discord bot process watches for alerts (file watch or engine calls alert fn directly)
- Sends to private `#ems-alerts` channel via Discord webhook URL (simple HTTP POST)
- No bot gateway needed — Discord channel webhooks are just POST endpoints

### 3. Vercel Link Generation
- Dashboard URL pattern: `https://<domain>/<date>` (e.g., `/2026-03-19`)
- Included in `/state` response and morning/evening summaries

### 4. Scheduled Summaries (晨间 08:00 / 晚间 21:00)
- Engine computes summary data, triggers Discord delivery
- Bot formats and POSTs to channel via webhook
- Could be a Babashka cron task or triggered by the engine process

## Ed25519 Signature Verification

This is the hardest part for Babashka. Discord requires verifying Ed25519 signatures on every interaction request. Options:
1. **Babashka pod** — use `pod-babashka-buddy` or shell out to a native tool
2. **JVM Clojure for this component** — if Ed25519 is too painful in bb, run the interaction endpoint as a small JVM Clojure service using `buddy-core` or Java's `java.security`
3. **Reverse proxy** — let a lightweight proxy (e.g., nginx module or small Go binary) handle signature verification, forward valid requests to Babashka

**Preferred:** Option 2 as fallback, but try Option 1 first. If neither works cleanly, Option 3.

## Open Questions / TODOs

- [ ] **Ed25519 in Babashka** — verify feasibility. Can `pod-babashka-buddy` or `bb` native do Ed25519? If not, what's the lightest JVM fallback?
- [ ] **Alert delivery model** — does the engine push alerts by calling a Clojure fn, or write to a file/queue that the bot watches? Need contract from EngineBuilder.
- [ ] **Bot hosting** — HTTP webhook bot still needs to be reachable from Discord. Options: ngrok (dev), Fly.io/VPS with domain + TLS (prod), or Vercel serverless (if we can do Ed25519 there).
- [ ] **Slash command registration** — one-time setup via Discord REST API. Guild-only for dev (instant), global for prod. Need bot token + app ID.
- [ ] **Morning/evening summary trigger** — who schedules? If engine owns the cron, it calls bot's send fn. If bot owns cron, it pulls from engine API.
- [ ] **Dashboard URL pattern** — need from FrontendDev.

## What I Need from Other Agents

| From | What |
|------|------|
| **EngineBuilder** | State read contract: how do I get current Energy/Time/Mood? File path? Function call? API endpoint? |
| **EngineBuilder** | Alert push contract: how are alerts delivered to me? |
| **EngineBuilder** | Summary data shape for morning/evening messages |
| **FrontendDev** | Vercel dashboard URL pattern |
| **SystemArchitect** | Deployment model: where does the bot process run? How is it exposed to Discord? |
| **SystemArchitect** | Auth model for internal API (if any) |
| **DataEngineer** | No direct dependency, but need to know data freshness — how stale can Screen Time data be when `/state` is called? |
