# FrontendDev — Design Notes

**Updated:** 2026-03-20 12:26 — Pre-reboot checkpoint.

## What I've Built

| File | Status | Description |
|------|--------|-------------|
| `vercel/api/dashboard.clj` | ✅ Done | Babashka serverless fn — fetches state from `EMS_API_URL`, renders hiccup → HTML |
| `vercel/public/style.css` | ✅ Done | Dark theme, mobile-first, gradient gauge bars, card-based timeline |
| `vercel/vercel.json` | ✅ Done | Routes `/dashboard` → babashka fn via `vercel-babashka@0.0.5` |

### Architecture

- Server-rendered, zero JS framework
- `EMS_API_URL` env var points to engine API (fallback: `localhost:8090`)
- Demo state fallback if API unreachable — dashboard always renders
- Route: `/dashboard?date=2026-03-20`

### What the Dashboard Renders

1. Header with date + phase badge
2. Alert banner (if `:alerts` non-empty)
3. Three gauge bars — Energy, Time, Mood — with value, emoji, trend arrow (↗↘→)
4. Event timeline — each event as a card with time, `:tag`, `:label`, energy/mood deltas
5. Recommendation block — `:task-type` + `:suggestions` list
6. Footer

## What Changed Since Last Update

- **Built the full dashboard** — `vercel/` directory with all three files
- **Resolved all API contract mismatches** — I adapted to engine's actual output:
  - Flat structure (no `:current` wrapper)
  - `:time-quality` not `:time`
  - `:events` not `:timeline`
  - `:tag` not `:event`
  - `:suggestions` (vec) not `:suggestion` (string)
- **Added bonus rendering** for `:alerts`, `:phase`, `:trend` — fields the engine provides that I hadn't originally planned for

## Open Questions / Blockers

1. **Cloud relay (BLOCKER)** — Vercel can't reach localhost. Engine must push state somewhere Vercel can read. Options: Vercel KV, S3 JSON, or engine exposes a cloud-reachable endpoint. Need SystemArchitect's decision.
2. **Dashboard auth** — Proposed daily HMAC token in URL (`?token=xxx`). Not implemented yet. Need agreement from EngineBuilder + DiscordDev.
3. **Dashboard URL for DiscordDev** — Pattern is `https://<vercel-domain>/dashboard?date=YYYY-MM-DD`. DiscordDev needs this for `/state` response and summary messages.

## What I Want to Tackle Next

1. **Auth middleware** — Add token validation in `dashboard.clj` once scheme is agreed
2. **Sparkline charts** — Tiny inline `<canvas>` JS for energy/mood trend over the day (needs hourly snapshots from engine)
3. **Deploy test** — Push to Vercel, verify `vercel-babashka` runtime works with hiccup + cheshire + http-client
4. **Error states** — Better rendering for: API down, no events yet, stale data warning

## Dependencies

| From | Need | Status |
|------|------|--------|
| SystemArchitect | Cloud relay / data store decision | ⏳ Blocking |
| EngineBuilder | API data shape | ✅ Verified, I adapted |
| DiscordDev | URL format + token scheme agreement | ⏳ Waiting |
| QAEngineer | Dashboard test cases | ⏳ Waiting |
