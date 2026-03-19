# EngineBuilder — Design Notes

## (a) Three-Line Engine Design in Babashka/Clojure

### Core Principle

All engine logic is pure functions over immutable maps. No side effects in computation. State lives in SQLite (per SystemArchitect's design) — engine reads from DB, computes, writes back.

### Interface: `compute-state`

Aligning with SystemArchitect's proposed `(compute-state db as-of)`:

```clojure
(ns ems.engine
  (:require [ems.engine.rates :as rates]
            [ems.engine.complaint :as complaint]))

(defn compute-state
  "Pure fusion. Takes collected data map + config, returns state snapshot.
   `now` is injected clock (ISO string) for testability (per QAEngineer request)."
  [collected-data config now]
  (let [base   (calibrate-morning collected-data config)
        decayed (apply-screen-decay base (:screen-time collected-data) now)
        evented (reduce apply-event decayed (:events collected-data))
        tq      (compute-time-quality (:screen-time collected-data) (:calendar collected-data) now)
        state   (assoc evented :time-quality tq)
        alerts  (evaluate-alerts state)
        rec     (recommend-task state)]
    (assoc state :alerts alerts :recommendation rec :timestamp now)))
```

The caller (core.clj / scheduler) handles DB reads and writes — engine never touches IO.

### State Snapshot Shape

Aligned with FrontendDev's requested data shape and SystemArchitect's API contract:

```clojure
{:date           "2026-03-19"
 :timestamp      "2026-03-19T14:30:00+08:00"
 :energy         {:value 72 :status "良好" :emoji "🟡" :trend :falling}
 :mood           {:value 85 :status "愉悦" :emoji "😊" :trend :stable}
 :time-quality   {:ratio 0.7 :deep-min 180 :frag-min 45
                  :available-hours 3.5 :status "适中" :emoji "🟡"}
 :phase          :action  ;; per SystemArchitect: :raw | :buffer | :action | :recovery
 :recommendation {:task-type "高难度创造"
                  :suggestions ["系统架构设计" "深度写作" "战略规划"]}
 :alerts         []
 :events         [{:time "09:30" :tag :sprint :label "深度写作 20min"
                   :deltas {:energy -18 :mood 0}
                   :snapshot {:energy 82 :mood 80}}]}
```

### Input Contract (from DataEngineer)

Consuming DataEngineer's EDN output directly:

```clojure
;; Screen Time (hourly buckets from DataEngineer)
{:type :screen-time
 :timestamp "2026-03-19T10:00:00+08:00"
 :total-minutes 47
 :app-switches 23
 :unlock-count 8
 :app-usage {"com.apple.Safari" 20 "com.tinyspeck.slackmacgap" 15}}

;; Roam daily (from DataEngineer)
{:type :roam-daily
 :date "2026-03-19"
 :morning-complaint? false  ;; DataEngineer pre-computes this — OR I can re-check raw text
 :morning-text "早安，今天状态不错"  ;; raw text for my own complaint detection
 :energy-tags [{:time "..." :value 70 :text "..."}]
 :mood-tags [{:time "..." :value nil :text "😊"}]
 :events [{:time "..." :type :friction :context "抽象沟通拉扯"}]
 :raw-buffer ["review PR" "write doc"]}
```

**Answer to DataEngineer's question:** Hourly buckets are fine for v0.2. I don't need per-minute granularity. Raw bundle IDs are fine — I'll categorize internally if needed later.

### Morning Calibration

```clojure
(def complaint-keywords
  #{"累" "烦" "没睡好" "不想" "头疼" "难受" "焦虑" "失眠" "不舒服" "差" "困"})

(defn complaint? [text]
  (when text
    (some #(clojure.string/includes? text %) complaint-keywords)))

(defn calibrate-morning
  "Returns initial energy + mood values. Deterministic keyword matching (per QAEngineer: no LLM)."
  [collected-data config]
  (let [morning-text (get-in collected-data [:roam :morning-text])
        has-complaint (complaint? morning-text)
        energy-init (if has-complaint 80 100)
        mood-init   (if has-complaint 60 80)]
    {:energy {:value energy-init} :mood {:value mood-init}
     :events [{:time (:morning-time config "08:00") :tag :calibration
               :label (if has-complaint "晨间校准（低起点）" "晨间校准")
               :deltas {:energy 0 :mood 0}
               :snapshot {:energy energy-init :mood mood-init}}]}))
```

### Screen Time Decay

```clojure
(defn apply-screen-decay
  "Applies energy decay based on screen intensity over elapsed hours."
  [state screen-time-records now]
  (let [hours-elapsed (calc-hours-since-morning now)
        total-switches (reduce + 0 (map :app-switches screen-time-records))
        avg-switches-per-hr (if (pos? hours-elapsed) (/ total-switches hours-elapsed) 0)
        total-active-min (reduce + 0 (map :total-minutes screen-time-records))
        avg-active-per-hr (if (pos? hours-elapsed) (/ total-active-min hours-elapsed) 0)
        ;; Decay tiers
        intensity-drain (cond (> avg-active-per-hr 45) -5  ;; heavy
                              (> avg-active-per-hr 30) -3  ;; moderate
                              :else                    -1) ;; light
        switch-drain    (if (> avg-switches-per-hr 15) -3 0)
        natural-drain   -2
        total-drain     (* hours-elapsed (+ intensity-drain switch-drain natural-drain))
        new-energy      (-> (get-in state [:energy :value])
                            (+ total-drain)
                            (max 0) (min 100))]
    (assoc-in state [:energy :value] new-energy)))
```

### Event Impact (hardcoded rates for v0.2)

```clojure
(def event-rates
  {:aha          {:energy  5  :mood 10}
   :friction     {:energy -30 :mood -15}
   :sprint       {:energy -18 :mood  0}
   :social-drain {:energy -25 :mood  -8}
   :family-time  {:energy 10  :mood 10}
   :solo-rest    {:energy 10  :mood  5}
   :outdoor      {:energy  8  :mood  8}
   :nap          {:energy 15  :mood  5}
   :deep-convo   {:energy  5  :mood 10}
   :meeting      {:energy -10 :mood -3}})

(defn apply-event [state event]
  (let [rates (get event-rates (:type event) {:energy 0 :mood 0})
        e (-> (+ (get-in state [:energy :value]) (:energy rates)) (max 0) (min 100))
        m (-> (+ (get-in state [:mood :value]) (:mood rates)) (max 0) (min 100))]
    (-> state
        (assoc-in [:energy :value] e)
        (assoc-in [:mood :value] m)
        (update :events conj {:time (:time event) :tag (:type event)
                              :label (:context event "")
                              :deltas rates
                              :snapshot {:energy e :mood m}}))))
```

### Time Quality

```clojure
(defn compute-time-quality
  "Ratio of deep work time vs total active time. Uses screen time switch frequency."
  [screen-time-records calendar now]
  (let [total-active (reduce + 0 (map :total-minutes screen-time-records))
        total-switches (reduce + 0 (map :app-switches screen-time-records))
        ;; Deep = periods with <5 switches per hour-bucket
        deep-buckets (filter #(< (:app-switches %) 5) screen-time-records)
        deep-min (reduce + 0 (map :total-minutes deep-buckets))
        frag-min (- total-active deep-min)
        ratio (if (pos? total-active) (double (/ deep-min total-active)) 0.0)
        ;; Available hours from calendar
        meeting-min (get calendar :meeting-minutes 0)
        remaining-hrs (-> (- 16 (/ meeting-min 60.0))  ;; 16 waking hours
                          (- (/ total-active 60.0))
                          (max 0))]
    {:ratio ratio :deep-min deep-min :frag-min frag-min
     :available-hours (double remaining-hrs)
     :status (cond (> remaining-hrs 4) "充裕"
                   (> remaining-hrs 2) "适中"
                   (> remaining-hrs 1) "紧张"
                   :else "匮乏")
     :emoji (cond (> remaining-hrs 4) "🟢"
                  (> remaining-hrs 2) "🟡"
                  (> remaining-hrs 1) "🟠"
                  :else "🔴")}))
```

### Decision Gateway (Alerts)

```clojure
(defn evaluate-alerts [state]
  (let [e (get-in state [:energy :value])
        m (get-in state [:mood :value])
        tq (get-in state [:time-quality :ratio] 1.0)]
    (cond-> []
      (< e 30)  (conj {:type :exhaustion :severity :critical
                        :message "⚠️ 精力耗竭，建议中断当前 Session"})
      (< m 40)  (conj {:type :mood-low :severity :warn
                        :message "😔 情绪低落，考虑休息"})
      (< tq 0.3) (conj {:type :fragmentation :severity :warn
                         :message "🔀 高度碎片化，建议合并时间块"})
      (and (< e 30) (< m 40))
                 (conj {:type :emergency :severity :critical
                         :message "🚨 紧急干预 — 立即停止所有工作"}))))
```

### Task Recommendation (8-combo matrix from spec)

```clojure
(defn recommend-task [state]
  (let [high-e (>= (get-in state [:energy :value]) 60)
        much-t (> (get-in state [:time-quality :available-hours] 0) 2)
        good-m (>= (get-in state [:mood :value]) 60)]
    (case [high-e much-t good-m]
      [true  true  true]  {:task-type "高难度创造" :suggestions ["系统架构设计" "深度写作" "战略规划"]}
      [true  true  false] {:task-type "机械性工作" :suggestions ["代码重构" "文档整理" "数据清洗"]}
      [true  false true]  {:task-type "快速冲刺"   :suggestions ["20min 专注任务" "快速决策"]}
      [true  false false] {:task-type "轻量输出"   :suggestions ["回复消息" "简单审核"]}
      [false true  true]  {:task-type "学习输入"   :suggestions ["阅读" "听播客" "看视频"]}
      [false true  false] {:task-type "强制休息"   :suggestions ["物理熔断" "停止工作"]}
      [false false true]  {:task-type "轻量娱乐"   :suggestions ["刷推" "看短视频"]}
      [false false false] {:task-type "紧急干预"   :suggestions ["立即停止所有工作" "寻求支持"]})))
```

### Phase Detection (per SystemArchitect)

```clojure
(defn detect-phase [state]
  (let [e (get-in state [:energy :value])]
    (cond
      (< e 30)  :recovery
      (< e 50)  :raw      ;; low energy → good time for Raw→Buffer processing
      :else     :action)))
```

### Status Labels & Emoji

```clojure
(defn energy-status [v]
  (cond (>= v 80) {:status "充沛" :emoji "🟢"}
        (>= v 60) {:status "良好" :emoji "🟡"}
        (>= v 40) {:status "疲劳" :emoji "🟠"}
        (>= v 20) {:status "耗竭" :emoji "🔴"}
        :else     {:status "崩溃" :emoji "⚫"}))

(defn mood-status [v]
  (cond (>= v 80) {:status "愉悦" :emoji "😊"}
        (>= v 60) {:status "平和" :emoji "😌"}
        (>= v 40) {:status "低落" :emoji "😐"}
        (>= v 20) {:status "沮丧" :emoji "😔"}
        :else     {:status "崩溃" :emoji "😭"}))
```

### Trend Calculation

```clojure
(defn calc-trend [events key]
  (let [vals (map #(get-in % [:snapshot key]) events)]
    (if (< (count vals) 2) :stable
      (let [recent (last vals) prev (nth vals (- (count vals) 2))]
        (cond (> recent (+ prev 5)) :rising
              (< recent (- prev 5)) :falling
              :else :stable)))))
```

### Namespace Layout

```
src/ems/
  engine.clj          ;; compute-state (top-level pipeline)
  engine/
    rates.clj         ;; event-rates map, decay constants (hardcoded v0.2, EDN config later)
    complaint.clj     ;; complaint? keyword detection
```

Keeping it minimal — one main namespace with rates and complaint as small helpers.

---

## (b) Open Questions & TODOs

### Open Questions

1. **Phase field** — SystemArchitect proposes `:raw | :buffer | :action | :recovery`. I've mapped energy thresholds to phases. Does `:buffer` need explicit detection (e.g., user is in Roam processing raw items)? Or is it purely energy-driven?
2. **Mood natural regression** — Spec says mood regresses toward baseline over time. I'll default to +2/hr toward 80. Acceptable?
3. **Sleep data** — Spec mentions sleep duration/HRV capping energy ceiling. DataEngineer hasn't included this in v0.2 output. Defer to v1?
4. **Rate config** — Hardcoded for v0.2. SystemArchitect has `config.edn` in the plan. I'll read from it when ready — just need the key paths defined.
5. **Calendar data** — DataEngineer's output doesn't include calendar. For TimeQuality `available-hours`, should I estimate from Screen Time alone, or is calendar integration in scope?
6. **Hourly snapshots** — FrontendDev asks: event-only timeline or hourly snapshots too? I can emit a snapshot on every compute cycle (every 30 min per SystemArchitect's scheduler). FrontendDev can interpolate for sparklines.

### TODOs

- [ ] Implement `compute-state` pipeline
- [ ] Implement `calibrate-morning` with complaint detection
- [ ] Implement `apply-screen-decay` with intensity tiers
- [ ] Implement `apply-event` with rate table
- [ ] Implement `compute-time-quality`
- [ ] Implement `evaluate-alerts`
- [ ] Implement `recommend-task` (8-combo matrix)
- [ ] Implement `detect-phase`
- [ ] Status/emoji helper functions
- [ ] Trend calculation from event history
- [ ] Clamp all values to 0-100 range
- [ ] Unit tests for all pure functions

---

## (c) What I Need From Other Agents

### From DataEngineer
- **Confirmed:** Hourly Screen Time buckets are fine. Raw bundle IDs are fine.
- **Need:** Will you include `morning-text` (raw string) in Roam output so I can run my own complaint detection? Your output shows `morning-complaint?` boolean — I'd like both (boolean as fallback, raw text for richer analysis later).
- **Need:** Calendar data (meeting minutes, free blocks) — is this in scope for v0.2?
- **Need:** Sleep data — deferred to v1?

### From SystemArchitect
- **Confirmed:** I align with single daemon process, engine as pure functions called by scheduler.
- **Need:** Confirm `config.edn` key paths for rates/thresholds so I can read from it.
- **Need:** Clarify phase field semantics — is `:buffer` energy-driven or context-driven?
- **Need:** How does scheduler invoke engine? I expect: scheduler calls `(ems.engine/compute-state collected-data config now)`, writes result to SQLite.

### From DiscordDev
- **I provide:** State snapshot (see shape above) readable via API `GET /state`. Alerts in `:alerts` vec of the snapshot.
- **Alert delivery:** Since SystemArchitect chose single process, I propose: engine returns alerts in snapshot → `core.clj` checks alerts → calls your `send-alert!` fn directly. No file watching needed.
- **Summary data:** Morning/evening summaries are just state snapshots at 08:00 and 21:00. I'll add a `:summary` key with top-3 drains for evening.

### From FrontendDev
- **Confirmed:** My output shape matches your requested format (timeline with deltas + snapshots, current values with status/emoji, recommendation).
- **Answer:** I'll emit snapshots every compute cycle (~30 min). You can use these for sparklines.

### From QAEngineer
- **Confirmed:** Clock injection via `now` parameter on all time-dependent functions. Deterministic keyword matching for complaint detection (no LLM). All functions are pure — pass maps in, assert maps out.
- **Request:** Sample day scenario EDN fixtures would help me validate. Happy to co-define the expected outputs.
