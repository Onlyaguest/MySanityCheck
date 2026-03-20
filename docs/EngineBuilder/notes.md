# EngineBuilder — Design Notes

## Checkpoint (2026-03-20 12:26, pre-reboot)

### 1. What I've Built

| File | Status | Description |
|------|--------|-------------|
| `src/ems/engine.clj` | ✅ Done | Main engine: `compute-state` pure function pipeline |
| `src/ems/engine/rates.clj` | ✅ Done | Event impact rates, decay constants, thresholds |
| `src/ems/engine/complaint.clj` | ✅ Done | Canonical complaint keyword matcher (13 keywords) |

`compute-state` signature: `(compute-state screen-time-data roam-data config now)` → full state snapshot map.

Pipeline: calibrate morning → screen decay → apply events → mood regression → time quality → alerts → recommendation → phase → trends.

### 2. What Changed Since Initial Notes

- **P0: Complaint keywords consolidated.** Removed duplicate set from `config.edn`. `engine/complaint.clj` is the single source of truth. DataEngineer's `roam.clj` now imports from it.
- **P0: Output shape verified.** Matches DiscordDev's `format-state` and FrontendDev confirmed they'll adapt to flat shape.
- **P1: `recommend-task` default case.** Replaced `case` with `condp =` for bb compatibility + added default `{:task-type "自由安排"}`.
- **P1: Mood regression implemented.** `apply-mood-regression` moves mood 10% closer to baseline (80) per elapsed hour via exponential decay `(1 - 0.9^hrs)`. Wired into `compute-state` after event processing.
- **Morning-text contract documented.** Engine expects `:morning-text` (top-level string in roam-data). Handles nil gracefully → defaults (E=100, M=80).

### 3. Open Questions / Blockers

- **No blockers.** Engine is complete and testable in isolation.
- **Calendar data** — `compute-time-quality` accepts `(:calendar config)` with `{:meeting-minutes N}` but no collector produces this yet. Falls back to 0. Not blocking.
- **Sleep data** — Spec mentions sleep/HRV capping energy ceiling. Deferred to v1. Not blocking.
- **Phase `:buffer`** — Currently only `:recovery`, `:raw`, `:action` based on energy thresholds. `:buffer` not yet distinguished from `:raw`. Awaiting SystemArchitect clarification.
- **Config-driven rates** — `rates.clj` is hardcoded. `config.edn` rates section exists but is not read. Will wire when SystemArchitect confirms key paths.

### 4. What I Want to Tackle Next

1. **Smoke test validation** — QAEngineer is writing `test/ems/engine_test.clj`. I want to verify my functions pass. Will pair on expected outputs if needed.
2. **Wire config-driven rates** — Read event rates from `config.edn` instead of hardcoded `rates.clj`, once SystemArchitect locks the config schema.
3. **Evening summary** — Add a `:summary` key to state with top-3 energy drains for the evening Discord message.
4. **Calendar integration** — When DataEngineer adds calendar data, wire it into `compute-time-quality`.

---

## Contracts

### Input: roam-data (from DataEngineer's roam.clj)

```clojure
{:morning-text "早安，今天有点累"  ;; raw string, engine runs complaint? on it
 :morning-complaint? true           ;; pre-computed by roam.clj, engine ignores this
 :events [{:time "09:15" :type :friction :context "抽象沟通拉扯"}]
 :energy-tags [...]
 :mood-tags [...]}
```

Engine uses `:morning-text` (not `:morning-complaint?`) for richer 13-keyword matching.

### Input: screen-time-data (vec of hourly buckets from DataEngineer's screentime.clj)

```clojure
[{:total-minutes 47 :app-switches 23 :unlock-count 8
  :app-usage {"com.apple.Safari" 20}}]
```

### Output: state snapshot

```clojure
{:date "2026-03-20"
 :timestamp "2026-03-20T14:30:00+08:00"
 :energy {:value 72 :status "良好" :emoji "🟡" :trend :falling}
 :mood {:value 78 :status "平和" :emoji "😌" :trend :stable}
 :time-quality {:ratio 0.7 :deep-min 180 :frag-min 45
                :available-hours 3.5 :status "适中" :emoji "🟡"}
 :phase :action
 :recommendation {:task-type "高难度创造"
                  :suggestions ["系统架构设计" "深度写作" "战略规划"]}
 :alerts []
 :events [{:time "09:30" :tag :sprint :label "深度写作 20min"
           :deltas {:energy -18 :mood 0}
           :snapshot {:energy 82 :mood 80}}]}
```
