# CodeReviewer — Notes

Updated: 2026-03-20 12:26

---

## 1. What I've Built

No implementation code (per role). Two review documents:

| File | Status |
|------|--------|
| `docs/CodeReviewer/notes.md` | This file — living notes |
| `docs/CodeReviewer/review-p2.md` | Phase 2 re-review with P0 verdicts |

## 2. What's Changed Since Last Update

### Phase 1 review (initial)
- Reviewed all 8 source files + bb.edn + config.edn + all agent notes
- Found 4 P0 blockers, 10 P1 issues, 3 cross-module contract mismatches

### Phase 2 re-review (post-P0 fixes)
- **All 4 P0s: RESOLVED.** Pod version unified, single load point, safe config loading, full orchestration loop wired.
- **Complaint keywords: consolidated.** `engine/complaint.clj` is canonical. Roam.clj imports it. Config.edn points to it via comment.
- **Multi-env secrets: correct.** `core.clj` `resolve-env` properly resolves `:roam-config` and `:discord-config` per `:env`.
- **Found 4 new P1 issues:**
  - N1: Discord output dead — core.clj looks for `:webhook-url` that doesn't exist; Bot API decision made but not wired
  - N2: Smoke test asserts mood=80 but `:aha` event adds +10 → should be 90
  - N3: `bb test` task loads file but never calls `run-tests`
  - N4: `resolve-channel` in discord.clj is dead code (core.clj already resolves env)

## 3. Open Questions / Blockers

- **No blockers for me** — I review what others produce.
- **Waiting on:** Everyone to commit their Phase 3 / post-FDA changes so I can re-review.

### Outstanding P1 tracker (carry-over + new)

| # | Issue | Owner | Status |
|---|-------|-------|--------|
| N1 | Discord output dead (webhook-url → bot API) | SystemArchitect + DiscordDev | Open — SA decided Bot API, DiscordDev needs to implement `post-channel!`, SA needs to update `run-cycle!` callers |
| N2 | Test assertion wrong (mood=80 should be 90) | QAEngineer | Open |
| N3 | `bb test` doesn't run tests | QAEngineer / SystemArchitect | Open — need `(run-tests)` call or test runner |
| N4 | `resolve-channel` dead code | DiscordDev | Open — remove or use |
| N5 | `recommend-task` no default clause | EngineBuilder | Open (carry-over) |
| N6 | Mood regression not implemented | EngineBuilder | Open (carry-over) |
| N7 | Relative file paths fragile under launchd | SystemArchitect | Open (carry-over) |
| N8 | DB tables created but never written to | SystemArchitect / DataEngineer | Open — state lives in atom only, lost on restart |
| N9 | DB column names don't match collector output | DataEngineer | Open (carry-over) — `pickups` vs `unlock-count`, etc. |
| N10 | Config.edn rates never read (hardcoded in rates.clj) | EngineBuilder | Open (carry-over) — duplication, divergent key names |
| N11 | Ed25519 verification missing | DiscordDev | Open (deferred to post-v0) |

## 4. What I Want to Tackle Next

1. **Re-review after FDA reboot** — once the team commits their next round of changes (especially Discord Bot API wiring and test fixes), do a full pass.
2. **Verify `bb run` actually starts** — after FDA is granted, confirm the daemon boots, collects real Screen Time data, and serves `/state` with real numbers.
3. **Verify `bb test` actually runs** — once test runner is fixed, confirm all assertions pass.
4. **Check secrets hygiene** — `secrets.edn` is gitignored (confirmed), but contains real tokens in plain text on disk. Acceptable for local-only v0, but flag if deployment scope changes.
