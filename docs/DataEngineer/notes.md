# DataEngineer — Design Notes

## (a) Design Thoughts — Babashka/Clojure

### Tech Approach

All collectors are Babashka scripts. No JVM needed for data ingestion.

### 1. macOS Screen Time Collector

**Database:** `~/Library/Application Support/Knowledge/knowledgeC.db` (SQLite)

**Pod:** `pod-babashka-go-sqlite3` (org.babashka/go-sqlite3 v0.3.13) — provides `execute!` and `query` functions.

**Key table:** `ZOBJECT` where `ZSTREAMNAME = '/app/usage'`
- `ZVALUESTRING` → bundle ID (e.g. `com.apple.Safari`)
- `ZSTARTDATE` / `ZENDDATE` → Core Data timestamps (epoch offset +978307200 to get Unix time)
- Duration = ZENDDATE - ZSTARTDATE (seconds)

**Additional streams to query:**
- `/device/isLocked` → screen unlock events (count = unlock_count)
- `/app/inFocus` → foreground app switches (count = app_switches)

**Core extraction logic (pseudocode):**
```clojure
(ns ems.collector.screentime
  (:require [babashka.pods :as pods]))

(pods/load-pod 'org.babashka/go-sqlite3 "0.3.13")
(require '[pod.babashka.go-sqlite3 :as sqlite])

(def db-path
  (str (System/getProperty "user.home")
       "/Library/Application Support/Knowledge/knowledgeC.db"))

(def core-data-epoch 978307200)

(defn query-app-usage [since-unix]
  (let [since-cd (- since-unix core-data-epoch)]
    (sqlite/query db-path
      ["SELECT ZVALUESTRING as bundle_id,
               ZSTARTDATE + 978307200 as start_ts,
               ZENDDATE + 978307200 as end_ts,
               (ZENDDATE - ZSTARTDATE) as duration_secs
        FROM ZOBJECT
        WHERE ZSTREAMNAME = '/app/usage'
          AND ZSTARTDATE >= ?
        ORDER BY ZSTARTDATE" since-cd])))
```

**Prerequisite:** Full Disk Access (FDA) for the Terminal/process. One-time manual grant.

### 2. iOS Screen Time

**Strategy for v0:** Check if iOS device data appears in the Mac's `knowledgeC.db` via iCloud Screen Time sync. The `ZOBJECT` table has device-distinguishing fields. If present, we parse both; if not, defer iOS to v1.

### 3. Roam Research Collector

**API:** Roam Backend API (alpha) at `https://api.roamresearch.com`
- `POST /api/graph/{GRAPH_NAME}/q` — Datalog queries
- `POST /api/graph/{GRAPH_NAME}/pull` — Pull specific entities
- Auth: `Bearer {API_TOKEN}`

**Babashka HTTP:** Use built-in `babashka.http-client` for API calls, `cheshire.core` (bb-compatible) for JSON.

**Queries needed:**

1. Daily page blocks (morning calibration):
```datalog
[:find (pull ?b [:block/string :block/uid :create/time])
 :in $ ?title
 :where
 [?p :node/title ?title]
 [?b :block/page ?p]]
```
Pass `?title` as e.g. `"March 19th, 2026"` (Roam date format).

2. Tagged blocks (#Energy, #Mood, #Aha, #Friction, #Sprint):
```datalog
[:find (pull ?b [:block/string :block/uid :create/time])
 :in $ ?tag
 :where
 [?ref :node/title ?tag]
 [?b :block/refs ?ref]]
```

3. `status/raw` buffer items — same pattern, query by page reference.

**Parsing:** Regex on `:block/string` to extract numeric values, detect complaint keywords (负面词库: 累、烦、差、没睡好、头疼, etc.).

**Polling:** Hourly cron via launchd. Roam API is alpha — implement retry with exponential backoff, cache last-known values on failure.

### 4. Output Format

All collectors write EDN (not JSON — idiomatic Clojure) to a shared local data directory or pass maps directly to the engine.

```clojure
;; Screen Time record
{:type :screen-time
 :timestamp "2026-03-19T10:00:00+08:00"  ; hourly bucket
 :device :mac
 :total-minutes 47
 :app-usage {"com.apple.Safari" 20, "com.tinyspeck.slackmacgap" 15, ...}
 :unlock-count 8
 :app-switches 23}

;; Roam daily input
{:type :roam-daily
 :date "2026-03-19"
 :morning-complaint? false
 :energy-tags [{:time "2026-03-19T08:30:00" :value 70 :text "feeling ok"}]
 :mood-tags [{:time "2026-03-19T08:30:00" :value nil :text "😊"}]
 :events [{:time "2026-03-19T09:15:00" :type :friction :context "抽象沟通拉扯"}]
 :raw-buffer ["review PR for X" "write design doc"]}
```

---

## (b) Open Questions & TODOs

### Blockers
- [ ] **FDA grant** — must be documented in setup guide. No workaround.
- [ ] **Roam API token** — user needs to generate from Roam settings. Need graph name + token in config.
- [ ] **Roam API stability** — alpha API, unknown rate limits. Need graceful degradation.

### Open Questions
1. **Daemon vs cron?** I lean toward launchd plist (cron-like) with configurable interval. Simpler, no long-running process. Near-real-time not needed for hourly Screen Time buckets. Roam polling also hourly. For event markers (#Aha etc.), those come from Roam which we poll — so latency = polling interval.
2. **iOS data — defer to v1?** I recommend yes. Let's validate Mac-only first.
3. **knowledgeC.db schema drift** — need a schema fingerprint check on startup. If columns are missing, log warning and degrade gracefully.
4. **EDN vs JSON for internal data format?** I propose EDN (native Clojure). If other agents need JSON, we can add a thin conversion layer.

### TODOs
- [x] Write `ems.collector.screentime` namespace — query knowledgeC.db, bucket by hour
- [x] Write `ems.collector.roam` namespace — poll Roam API, parse tags
- [x] Write `ems.collector.roam-parser` — complaint detection, numeric extraction (inline in roam.clj)
- [ ] Create launchd plist template for scheduling
- [ ] Config file (EDN) for: db path, Roam graph name, API token, polling interval
- [ ] Integration test: read real knowledgeC.db on this Mac, verify output shape
- [ ] Document FDA setup steps for user

---

## Changelog

### Phase 1 — Initial Build (2026-03-19)
- Created `src/ems/collector/screentime.clj` — reads knowledgeC.db, buckets by hour
- Created `src/ems/collector/roam.clj` — polls Roam API, parses tags

### Phase 2 — P0 Fixes (2026-03-20 morning)
screentime.clj:
1. Removed pod loading — caller (core.clj) loads pod once
2. Added FDA error handling — `db-accessible?` probe, returns `[]` on failure
3. Removed unused `cd->unix` fn

roam.clj:
1. Removed duplicate complaint keywords — uses `ems.engine.complaint` as single source
2. Events now `(vec (concat ...))` instead of lazy seq

### Phase 2.5 — Auth + Key Bugs (2026-03-20 late morning)
roam.clj:
1. **Roam API uses `X-Authorization` header**, not `Authorization`. Fixed in both roam.clj and seed-roam.clj. Discovered by testing — no redirect involved, just wrong header name.
2. **Roam API returns colon-prefixed JSON keys** (e.g. `":block/string"`). Cheshire keywordizes these to `::block/string` which doesn't match `:block/string`. Fix: parse with `keywordize=false`, normalize keys via `normalize-block` (strips leading colon). This fixed both morning-text=nil and event timestamps=null.

scripts:
- Created `scripts/seed-roam.clj` — seeds staging Roam graph with test daily note data. Verified working against `lisp` graph.
- Created `scripts/test-screentime.clj` — quick smoke test for Screen Time collector. Awaiting FDA grant.
- Created `docs/DataEngineer/FDA-SETUP.md` — setup guide for Full Disk Access.

---

## Current File Status

| File | Status |
|------|--------|
| `src/ems/collector/screentime.clj` | ✅ Done. Tested — FDA fallback works. Awaiting FDA grant for real data test. |
| `src/ems/collector/roam.clj` | ✅ Done. Tested against live staging graph — all fields populated correctly. |
| `scripts/seed-roam.clj` | ✅ Done. Successfully seeded `lisp` graph. |
| `scripts/test-screentime.clj` | ✅ Done. Ready to run post-FDA. |
| `docs/DataEngineer/FDA-SETUP.md` | ✅ Done. |

---

## Verified Roam Collector Output (live test 2026-03-20)

```
morning-text: 早安，昨晚没睡好，有点累
morning-complaint?: true
energy-tags: [{:time 2026-03-20T03:45:48.750Z, :value 65, :text #Energy 65}]
mood-tags: [{:time ..., :value nil, :text #Mood 😐} {:time ..., :value nil, :text #Mood 😊}]
events: [{:time ..., :type :aha, :context #Aha 需求文档写完了}
         {:time ..., :type :friction, :context #Friction 又被拉进无效会议}
         {:time ..., :type :sprint, :context #Sprint 20min 专注写代码}]
raw-buffer: [..., "[[status/raw]] 研究 Screen Time API 方案", ...]
```

---

## FDA Notes

FDA grants access to the **parent process** (Terminal.app), not child processes. If running inside tmux, the tmux server was launched before FDA was granted, so child processes (including bb) still get "Operation not permitted". 

**Workaround for tmux users:** Launch bb from Terminal.app directly via osascript:
```bash
osascript -e 'tell application "Terminal" to do script "cd /Users/yuan/ems && /usr/local/bin/bb run"'
```
This spawns a new Terminal.app window (which has FDA) and runs bb there.

---

## Open Questions / Blockers

1. **FDA grant pending** — tmux reboot needed for Terminal to pick up FDA. Screen Time collector can't return real data until then.
2. **Roam `status/raw` query is too broad** — it returns ALL blocks referencing `status/raw` across the entire graph, not just today's. Need to intersect with daily page or add date filtering. Low priority for v0 but will cause noise.
3. **Roam API rate limits unknown** — alpha API, no documented limits. Current polling is fine but if we increase frequency, we may hit issues.
4. **iOS Screen Time** — deferred to v1 per crew consensus.

---

## What I Want to Tackle Next

1. **Post-FDA**: Run `scripts/test-screentime.clj` with real data, verify output shape matches what EngineBuilder expects.
2. **Roam date filtering for status/raw**: Scope the query to today's daily page only.
3. **Retry/backoff for Roam API**: Currently returns nil on failure. Add simple retry (2 attempts with 1s delay).
4. **Help QAEngineer**: Provide sample EDN fixtures for mock collector output if needed for engine tests.

---

## (c) What I Need From Other Agents

| From | Need |
|------|------|
| **SystemArchitect** | Confirm: core.clj loads pod once, collectors are called in scheduler loop, results passed directly to engine (no DB persistence for v0). Is this the wiring? |
| **EngineBuilder** | Confirm output contract is sufficient. Roam events have `:type` (:aha/:friction/:sprint) and `:context` (block text). Does engine need anything else? |
| **QAEngineer** | Do you need sample EDN fixtures from me for engine_test.clj? I can provide realistic mock data. |
