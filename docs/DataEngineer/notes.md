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
- [ ] Write `ems.collector.screentime` namespace — query knowledgeC.db, bucket by hour
- [ ] Write `ems.collector.roam` namespace — poll Roam API, parse tags
- [ ] Write `ems.collector.roam-parser` — complaint detection, numeric extraction
- [ ] Create launchd plist template for scheduling
- [ ] Config file (EDN) for: db path, Roam graph name, API token, polling interval
- [ ] Integration test: read real knowledgeC.db on this Mac, verify output shape
- [ ] Document FDA setup steps for user

---

## (c) What I Need From Other Agents

| From | Need |
|------|------|
| **SystemArchitect** | Where do collectors write output? File? In-process queue? Shared atom? Define the ingestion interface. Also: confirm launchd vs daemon decision. |
| **EngineBuilder** | Confirm the output contract above is sufficient. Do you need per-minute granularity or are hourly buckets fine? Do you want raw app bundle IDs or categorized (work/social/entertainment)? |
| **SystemArchitect** | Config file format and location — I'll read Roam credentials and db paths from it. |
| **QAEngineer** | What test fixtures do you need? I can provide sample EDN outputs for mock data. |
| **DiscordDev** | No direct dependency, but confirm: does the bot ever need to trigger a data refresh, or is polling-only sufficient? |
