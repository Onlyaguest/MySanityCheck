# DiscordDev — Design Notes

**Last updated:** 2026-03-20 12:26

---

## 1. What I've Built

| File | Status | Description |
|------|--------|-------------|
| `src/ems/discord.clj` | ✅ Done | Bot API messaging + interaction handler |
| `scripts/register-commands.clj` | ✅ Done | Registers `/state` slash command with Discord |

### discord.clj functions

- `post-message!` — POST to Discord Bot API v10 `/channels/{id}/messages`. Accepts both `:channel-id` and `:channel` keys defensively. Error handling + stderr logging.
- `format-state` — Formats engine state into one-line: `⚡ Energy: 62 🟡 | ⏱ Time: 3.2h 🟢 | 😌 Mood: 75 🟡 / 💡 推荐: ...`
- `send-alert!` — Sends engine alert to Discord channel.
- `send-summary!` — Morning (☀️) or evening (🌙) summary with state + alert count.
- `verify-signature` — Stub (returns true). TODO: Ed25519.
- `handle-interaction` — Ring handler for `POST /discord/interactions`. PING (type 1) → pong. APPLICATION_COMMAND (type 2) → formatted `/state` response.

### register-commands.clj

- Extracts app-id from bot token (Base64 first segment), reads guild-id from secrets.edn
- PUTs command to Discord guild endpoint
- **Already run** — command ID `1484398065115729983` registered in staging guild

## 2. What Changed Since Initial Notes

1. **Webhook → Bot API** — Rewritten per SystemArchitect decision. Uses `POST /channels/{id}/messages` with `Authorization: Bot {token}`.
2. **Multi-env** — core.clj resolves env, passes `{:bot-token :channel}` to discord fns. `resolve-channel` removed from discord.clj.
3. **405 bug fixed** — core.clj passes `:channel`, discord.clj expected `:channel-id`. Nil channel → malformed URL → 405. Fixed: accepts both keys via `(or channel-id channel)`.
4. **Paren fix** — Missing `)` on `defn- post-message!`. Fixed, all 4 tests pass (31 assertions).
5. **Interaction handler added** — Handles PING + `/state` slash command with state atom deref.
6. **Slash command registered** — `/state` live in staging guild.

## 3. Open Questions / Blockers

- [ ] **Ed25519 verification** — P1. Required before setting Discord Interactions Endpoint URL in prod. Options: pod-babashka-buddy, java.security interop, or reverse proxy.
- [ ] **Dashboard URL** — Need from FrontendDev. Currently nil in `/state` response.
- [ ] **Interactions endpoint exposure** — Server is localhost:8400. Need ngrok (dev) or tunnel (prod) for Discord to reach us.
- [ ] **core.clj call site mismatch** — `make-handler` calls `(discord/handle-interaction @state dashboard-url)` (2-arg old signature). Should be `(discord/handle-interaction req state-atom dashboard-url)` (3-arg ring handler). SystemArchitect needs to update.

## 4. Next Steps

1. Coordinate with SystemArchitect to fix `handle-interaction` call site in core.clj (pass ring req + state atom)
2. Ed25519 spike — test pod-babashka-buddy or `java.security` in bb
3. End-to-end alert test after FDA reboot — verify alerts land in staging channel
4. Weekly summary (spec mentions Sunday report) — low priority
