# Co (Coordinator) — Session Notes

## Session: 2026-03-19 ~ 2026-03-20

### Key Decisions Made
- Tech stack: Babashka/Clojure
- Single bb daemon process (http-kit + scheduler)
- Discord Bot API (not webhooks)
- Multi-env: staging (lisp graph, staging channel) / prod (yuanvv graph, prod channel)
- X-Authorization header for Roam API
- FDA required on: Terminal.app + pod-babashka-go-sqlite3 binary

### FDA Workaround
macOS FDA doesn't take effect until the parent process restarts. Since we run inside tmux with many sessions, we can't easily restart tmux server. Workaround:
```bash
# Run bb from Terminal.app (which has FDA) via osascript:
osascript -e 'tell application "Terminal" to do script "cd /Users/yuan/ems && /usr/local/bin/bb run 2>&1 | tee /tmp/ems-run.log"'

# Check output:
cat /tmp/ems-run.log
```
This avoids tmux restart while still getting FDA access for Screen Time.

### Git
- Repo: git@github.com:Onlyaguest/MySanityCheck.git (SSH)
- Branch: main (direct push for now)

### Crew Status
| Agent | Files Owned | Status |
|-------|------------|--------|
| SystemArchitect | bb.edn, config.edn, core.clj, db.clj | ✅ Done |
| DataEngineer | collector/screentime.clj, collector/roam.clj | ✅ Done |
| EngineBuilder | engine.clj, engine/rates.clj, engine/complaint.clj | ✅ Done |
| DiscordDev | discord.clj, register-commands.clj | ✅ Done |
| FrontendDev | vercel/* | ✅ Built, not deployed |
| QAEngineer | test/ems/engine_test.clj | ✅ 4 tests passing |
| CodeReviewer | review docs | ✅ P0s resolved |

### Open Items
- [ ] Ed25519 signature verification for Discord interactions
- [ ] Vercel dashboard deploy
- [ ] launchd plist for auto-start
- [ ] DiscordDev: handle-interaction arity mismatch in core.clj
- [ ] Cloud relay decision for Vercel → engine data
- [ ] Dashboard auth token scheme
- [ ] Calendar data integration
- [ ] Sleep data integration
