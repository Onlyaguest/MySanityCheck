# QA Engineer — Design Notes

## (a) Test Strategy for Babashka/Clojure

### Test Framework

Use `clojure.test` (built into Babashka) with `cognitect-labs/test-runner`. bb.edn config:

```clojure
{:tasks
 {test {:extra-paths ["test"]
        :extra-deps {io.github.cognitect-labs/test-runner
                     {:git/tag "v0.5.1" :git/sha "dfb30dd"}}
        :task (exec 'cognitect.test-runner.api/test)
        :exec-args {:dirs ["test"]}
        :org.babashka/cli {:coerce {:nses [:symbol]
                                    :vars [:symbol]}}}}}
```

Run: `bb test`, or `bb test --vars ns/specific-test`.

### Test Layers

1. **Unit tests** — Pure function tests for engine logic (decay formulas, calibration, thresholds). Clojure's immutable data makes this straightforward — pass maps in, assert maps out.

2. **Integration tests** — Full pipeline simulation: mock Screen Time DB (SQLite fixture) + mock Roam data (EDN fixture) → engine → verify Discord payload shape. Use `with-redefs` to stub external calls (Discord API, file system reads).

3. **Contract tests** — Validate API response shapes match what FrontendDev and AI agents expect. Define expected schemas as Clojure specs or simple predicate maps.

4. **Edge case suite** — See below.

### Key Edge Cases

| Case | What breaks | Test approach |
|------|-------------|---------------|
| No Screen Time DB / permissions revoked | Collector returns nil | Engine must produce valid default state |
| Empty Roam daily page | No #Energy/#Mood tags | Calibration falls back to defaults (E=100, M=80) |
| 早安 with complaint | Calibration E=80, M=60 | NLP detection accuracy — test with known phrases |
| 早安 without complaint | Calibration E=100, M=80 | Ensure no false positives |
| Midnight rollover (UTC+8) | Screen Time uses UTC internally | Verify date boundary conversion |
| Rapid event burst (#Friction ×5 in 10min) | Energy could go negative | Clamp to 0, verify alert fires |
| Three lines all critical simultaneously | Emergency intervention | Verify alert priority and message |
| Sprint spanning midnight | Session split across days | Verify correct day attribution |
| Stale Screen Time data (>2h old) | Engine uses outdated input | Verify staleness detection/warning |

### Testing Approach for Babashka

- All test data as EDN fixtures (Clojure's native data format)
- Mock external deps with `with-redefs` — no extra mocking library needed
- Time-dependent tests: pass time as parameter (no `(System/currentTimeMillis)` calls in engine logic — inject clock)
- SQLite reading via `babashka.pods` (pod-babashka-go-sqlite3) — test with fixture `.db` files

## (b) Open Questions & TODOs

### Open Questions

1. **Screen Time DB path** — Need DataEngineer to confirm exact path and schema on target macOS version. Tests depend on realistic fixture files.
2. **Decay function shape** — Is it continuous (linear/exponential) or step-based? Affects how I test intermediate states between polling intervals.
3. **Discord message latency SLA** — What's acceptable delay from engine alert → Discord delivery? Need this to set integration test timeouts.
4. **Complaint detection method** — Is it keyword-based or LLM-based? Determines whether I can write deterministic tests or need fuzzy assertions.
5. **Clock injection** — EngineBuilder: please accept a `now` parameter (or clock function) in all time-dependent functions. Critical for testability.

### TODOs

- [x] Write smoke test: `test/ems/engine_test.clj` — validates compute-state output shape, calibration defaults, complaint path, empty inputs
- [ ] Create SQLite fixture file mimicking Screen Time DB schema
- [ ] Write full-day simulation test (08:00 calibration → events → 21:00 reconciliation)
- [ ] Define API response schema assertions (engine output ↔ FrontendDev contract — mismatch flagged by CodeReviewer)
- [ ] Set up CI task in bb.edn
- [ ] Test `case` on vector in `recommend-task` under Babashka (CodeReviewer P1 flag)

## (c) What I Need From Other Agents

| From | What I need |
|------|-------------|
| **DataEngineer** | Screen Time DB path, schema, and a sample `.db` file. Roam parser input/output contract. Polling interval. |
| **EngineBuilder** | Interface contract: function signatures, input map shape, output map shape. Decay function specification. Confirm clock injection pattern. |
| **DiscordDev** | Expected Discord message format/embeds. Slash command response shape. Alert trigger → delivery flow. |
| **FrontendDev** | API response shape you expect (so I can write contract tests for it). |
| **SystemArchitect** | Deployment model (daemon vs cron) — affects how I test lifecycle. Test/staging environment plan. |
