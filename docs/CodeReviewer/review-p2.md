# CodeReviewer — Phase 2 Re-Review

Reviewed: 2026-03-20

---

## 1. P0 Status

### P0-1: Pod version mismatch — ✅ RESOLVED
`bb.edn` now declares `0.3.13`. `screentime.clj` and `db.clj` no longer call `load-pod`. `core.clj` loads the pod once before any `require`. Consistent.

### P0-2: Double pod loading — ✅ RESOLVED
`db.clj` now does `(:require [pod.babashka.go-sqlite3 :as sqlite])` — no `load-pod` call. `screentime.clj` same. Pod loaded once in `core.clj`. Clean.

### P0-3: Top-level side-effecting def — ✅ RESOLVED
`core.clj` replaced the top-level `(def config ...)` with `(defn load-config [])` called inside `start!`. Missing file returns `{}` with a warning instead of crashing. Good.

### P0-4: No orchestration loop — ✅ RESOLVED
`core.clj` now has:
- `run-cycle!` — collects screen time + roam → engine → caches in atom → fires Discord alerts
- `start-scheduler!` — minute-tick loop checking intervals, morning/evening summary triggers
- `start!` — runs first cycle immediately, starts scheduler, starts HTTP server
- `/state` serves real engine output from the atom

The system will actually run now.

---

## 2. Multi-Env Secrets Resolution

### core.clj `resolve-env` — ✅ Correct

```clojure
(defn- resolve-env [config]
  (let [env (get config :env :staging)]
    (assoc config
      :active-env env
      :roam-config   (get-in config [:roam env])
      :discord-config {:bot-token (:bot-token (:discord config))
                       :guild-id  (:guild-id (:discord config))
                       :channel   (if (= env :prod)
                                    (:prod-channel (:discord config))
                                    (:staging-channel (:discord config)))})))
```

Verified against `secrets.edn` structure. `:roam-config` resolves to `{:token "..." :graph "..."}` for the active env. `:discord-config` flattens to `{:bot-token :guild-id :channel}`. Correct.

### Issue: `run-cycle!` still uses webhook-url, not bot API (P1-new)

`core.clj` `run-cycle!` does:
```clojure
(when-let [webhook (get-in config [:discord :webhook-url])]
  (doseq [alert (:alerts snapshot)]
    (discord/send-alert! webhook alert)))
```

But `secrets.edn` has no `:webhook-url`. It has `:bot-token` + channel IDs. SystemArchitect's notes (Section F) explicitly decided to use Bot API over webhooks. But `discord.clj` still has `post-webhook!` taking a webhook URL, and `core.clj` looks for a webhook URL that doesn't exist.

**Result:** Alerts and summaries silently never fire — `(get-in config [:discord :webhook-url])` returns nil, `when-let` skips the block. No crash, but no Discord output.

SystemArchitect documented this as pending DiscordDev's interface update. Acknowledged — but this means Discord integration is effectively dead in the current code.

### discord.clj `resolve-channel` — Unused

`discord.clj` added `resolve-channel` which reads `:env` and picks the right channel. But nobody calls it — `core.clj` already resolves this in `resolve-env`. Dead code. Remove it or use it.

---

## 3. Contract Mismatches

### Engine ↔ Discord — ✅ No new mismatches
`discord/format-state` accesses `:energy :value`, `:energy :emoji`, `:mood :value`, `:mood :status`, `:time-quality :available-hours`, `:time-quality :emoji`, `:recommendation :task-type`. Engine output provides all of these. Match confirmed.

One improvement in `format-state`: now wraps `(:available-hours time-quality)` in `(double (or ... 0))` — handles nil. Good defensive fix.

### Engine ↔ Frontend — Acknowledged mismatch, deferred
FrontendDev notes confirm they will adapt to engine's flat output shape (no `:current` wrapper). EngineBuilder confirmed no engine changes needed. This is resolved by agreement, not code change. Acceptable.

### Engine ↔ core.clj `run-cycle!` — ✅ Match
`run-cycle!` calls `(engine/compute-state st-data roam-data config now)` with the right arity. Screen time data comes from `st/collect`, roam data from `roam/collect`. Config passed through. Timestamp from `now-iso`. Correct wiring.

### Roam collector ↔ core.clj — ✅ Match
`core.clj` does `(assoc (:roam-config config) :date (today-str))` → passes `{:token "..." :graph "..." :date "2026-03-20"}` to `roam/collect`. Roam collector expects exactly these keys. Match.

### Screentime collector ↔ core.clj — ✅ Match
`core.clj` passes `{:since (- now 86400)}`. Collector expects `:since`. Match.

---

## 4. Complaint Keywords Consolidation

### ✅ RESOLVED

- `engine/complaint.clj` — canonical source, 13 keywords. Unchanged.
- `roam.clj` — removed local `complaint-keywords` and `complaint?`. Now `(:require [ems.engine.complaint :as complaint])` and calls `complaint/complaint?`. ✓
- `config.edn` — replaced `:complaint-keywords` set with a comment: `"Canonical source: src/ems/engine/complaint.clj"`. ✓

Single source of truth achieved. One note: the English keywords from the old `roam.clj` set ("tired", "bad", "awful", "exhausted", "anxious") were dropped entirely. If the user writes morning notes in English, complaints won't be detected. Acceptable for v0 (user writes in Chinese), but worth noting.

---

## 5. Smoke Test Review

### test/ems/engine_test.clj — ✅ Sound, with notes

**4 tests, well-structured:**

1. `compute-state-smoke-test` — validates all top-level keys present, value ranges, types. Good shape test.
2. `morning-calibration-no-complaint` — checks defaults (E=100, M=80). The assertion `(= 80 (get-in state [:mood :value]))` assumes `:aha` event gives +10 mood and `:sprint` gives 0, netting +10 from base 80 → 90... wait. Let me check:
   - Base mood = 80 (no complaint)
   - Events: `:sprint` (+0 mood), `:aha` (+10 mood)
   - Expected: 80 + 0 + 10 = 90
   - **Test asserts `(= 80 ...)` — this will fail.** The `:aha` event adds +10 mood. Final mood should be 90, not 80.
   - The test comment says "net 0 mood" but `:aha` has `:mood 10` in rates.clj. **Bug in test (P1-new).**

3. `morning-calibration-with-complaint` — empty events, E=80 M=60. No screen time → no decay. Correct.

4. `empty-inputs-produce-defaults` — nil morning text, empty events. E=100, M=80, empty alerts. Correct.

### Test infrastructure
`bb.edn` has `test` task: `(load-file "test/ems/engine_test.clj")`. This loads the file but doesn't run `clojure.test`. Need to add `(clojure.test/run-tests 'ems.engine-test)` or use a test runner. **Tests won't actually execute with `bb test` as written (P1-new).**

---

## 6. New Issues Found

| # | Severity | Issue | File |
|---|----------|-------|------|
| N1 | P1 | **Discord output dead** — `run-cycle!` and scheduler look for `:discord :webhook-url` which doesn't exist in secrets. Bot API decision made but not implemented. Alerts/summaries silently skip. | `core.clj` |
| N2 | P1 | **Test assertion wrong** — `morning-calibration-no-complaint` asserts mood=80 but `:aha` event adds +10. Expected: 90. | `engine_test.clj` |
| N3 | P1 | **`bb test` doesn't run tests** — task does `load-file` but never calls `run-tests`. Tests compile but don't execute. | `bb.edn` |
| N4 | P1 | **`resolve-channel` in discord.clj is dead code** — `core.clj` already resolves env. Either use it or remove it. | `discord.clj` |
| N5 | P1 (carry-over) | **`recommend-task` `case` has no default** — still unfixed from Phase 1 review. | `engine.clj` |
| N6 | P1 (carry-over) | **Mood regression not implemented** — still unfixed. | `engine.clj` |
| N7 | P1 (carry-over) | **Relative file paths** — `config.edn`, `secrets.edn`, `ems.db` still relative. Fragile under launchd. | `core.clj`, `db.clj` |
| N8 | Minor | **`morning-sent`/`evening-sent` atoms reset at hour 0 only** — if the process restarts mid-day, summaries re-fire. Acceptable for v0. | `core.clj` |
| N9 | Minor | **DB still not used for persistence** — `run-cycle!` caches state in atom only. `db/init!` creates tables but nothing writes to them. State is lost on restart. | `core.clj`, `db.clj` |

---

## 7. Summary

**All 4 P0s are resolved.** The system can start, load config safely, run collector→engine cycles, and serve real state from `/state`.

**Top 3 things to fix next:**
1. **N1** — Wire Discord Bot API (replace webhook-url references with bot-token + channel-id). Without this, the system runs silently with zero output.
2. **N3 + N2** — Fix test runner so `bb test` actually executes, then fix the mood assertion.
3. **N5** — Add default clause to `recommend-task` `case`.

The architecture is solid. The wiring works. Discord output is the last gap before this is a functional v0.
