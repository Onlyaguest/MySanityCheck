# QA Engineer — Design Notes

**Last updated:** 2026-03-20 12:26 (pre-reboot checkpoint)

---

## 1. What I've Built

| File | Status | Description |
|------|--------|-------------|
| `test/ems/engine_test.clj` | ✅ Passing | 4 tests, 31 assertions. Smoke test for `compute-state`, calibration (no-complaint, complaint), empty inputs |
| `scripts/test-pipeline.clj` | ✅ Passing | End-to-end integration: loads config+secrets → resolves staging env → collects Screen Time (real, last 24h) + Roam → feeds engine → prints snapshot → sends Discord message. Requires FDA. |
| `bb.edn` test task | ✅ Fixed | Was broken (just `load-file`, never ran tests). Now uses `clojure.test/run-tests` with exit code on failure |

### Test Results (latest run)

**Unit tests (`bb test`):** 4 tests, 31 assertions, 0 failures, 0 errors

**Integration pipeline (`bb scripts/test-pipeline.clj`):**
- Config/secrets load: ✅
- Staging env resolution: ✅
- Roam collector (staging graph `lisp`): ✅ connected, returned empty (no seeded data yet)
- Engine compute-state: ✅ valid snapshot with defaults
- Discord staging message: ✅ sent successfully

## 2. What's Changed Since Last Notes

- **bb.edn test task fixed** — Original just loaded the file. Now properly runs `clojure.test/run-tests` and exits non-zero on failure. Had to use fully-qualified `clojure.test/run-tests` because bb's task analysis phase can't resolve aliases from runtime `require`.
- **Test assertions corrected twice:**
  1. First run: fixed mood expectation (80→90 because `:aha` adds +10) and empty-inputs alert expectation (0.0 ratio triggers fragmentation alert — correct engine behavior)
  2. Second run: EngineBuilder added mood regression (10%/hr toward baseline=80). Fixed by using `now-morning` ("08:00") for calibration tests so zero hours elapse and regression doesn't apply
- **Integration pipeline script created** — Full Roam→Engine→Discord flow verified against staging
- **Resolved questions:**
  - Clock injection: ✅ EngineBuilder accepts `now` param on `compute-state`
  - Complaint detection: ✅ Deterministic keyword matching (no LLM), canonical source is `engine/complaint.clj`
  - Decay function: Step-based (3 tiers: heavy/moderate/light per hour)
  - Engine signature: `(compute-state screen-time-data roam-data config now)` — 4 args

## 3. Open Questions / Blockers

### Blockers
- **No seeded staging Roam data** — Pipeline test ran but Roam returned empty. Need DataEngineer to seed the `lisp` graph with test entries (早安 text, #Aha/#Friction/#Sprint, #Energy/#Mood tags) before I can validate real data flow.
- **No Screen Time data in staging** — FDA needs to take effect after reboot. Once it does, need DataEngineer to confirm `screentime/collect` works and provide a sample `.db` fixture for offline tests.

### Open Questions
1. **Fragmentation alert on zero data** — Engine fires fragmentation alert when there's no screen time (ratio=0.0 < 0.3 threshold). Is this intended? Arguably "no data" ≠ "fragmented". Should engine skip this alert when screen-time input is empty?
2. **API contract mismatch** — CodeReviewer flagged: FrontendDev expects `:time` / `:timeline` / `:event`, engine outputs `:time-quality` / `:events` / `:tag`. Who adapts? I need the final shape to write contract tests.
3. **`case` on vector in `recommend-task`** — CodeReviewer P1: unverified in Babashka. Currently passes in tests (bb 1.x seems to support it), but needs explicit confirmation or a default clause added.
4. **Discord latency SLA** — Still undefined. What's acceptable for alert delivery?

## 4. What I Want to Tackle Next

1. **Post-reboot: Screen Time integration test** — Once FDA is active, run `scripts/test-pipeline.clj` with real screen time data added to the pipeline
2. **Seeded Roam test** — Re-run pipeline after DataEngineer seeds staging graph, verify real events flow through engine correctly
3. **Edge case tests** — Rapid event burst (5× #Friction), energy clamping to 0, emergency alert when all three lines critical
4. **Contract test** — Once API shape is finalized, write assertions that engine output matches what FrontendDev/DiscordDev consume
5. **Full-day simulation** — 08:00 calibration → hourly screen time buckets → scattered events → 21:00 evening review, all with injected clock

---

## Reference: Test Strategy

### Test Layers
1. **Unit** — Pure engine functions (decay, calibration, thresholds, recommend-task). `clojure.test` + `bb test`.
2. **Integration** — Full pipeline with real or mock collectors. `scripts/test-pipeline.clj`.
3. **Contract** — API response shape validation (engine ↔ frontend ↔ discord).
4. **Edge cases** — Missing data, clamping, midnight rollover, timezone, rapid bursts.

### Approach
- EDN fixtures for test data
- `with-redefs` for mocking external deps
- Clock injection via `now` parameter (no system clock in engine)
- `now-morning` pattern for calibration tests (zero elapsed time = no regression)

### ⚠️ FDA Requirement for Screen Time Tests
Tests that touch macOS Screen Time (`knowledgeC.db`) **must** run from a process with Full Disk Access:
- ✅ Terminal.app (after FDA granted in System Settings → Privacy & Security → Full Disk Access)
- ✅ launchd daemon (inherits FDA from parent)
- ❌ tmux — will NOT have FDA unless tmux was **restarted after** FDA was granted to Terminal.app
- ❌ SSH sessions — no FDA unless explicitly granted

If `screentime/collect` returns empty with no warning, FDA is the likely cause. The script prints a diagnostic when this happens.

### Key Edge Cases to Cover
| Case | Status |
|------|--------|
| No Screen Time DB / FDA denied | ⬜ Needs fixture |
| Empty Roam daily page | ✅ Covered in `empty-inputs-produce-defaults` |
| 早安 with complaint | ✅ Covered in `morning-calibration-with-complaint` |
| 早安 without complaint | ✅ Covered in `morning-calibration-no-complaint` |
| Rapid #Friction burst | ⬜ Next |
| Energy clamp to 0 | ⬜ Next |
| Triple-low emergency | ⬜ Next |
| Midnight rollover | ⬜ Later |
