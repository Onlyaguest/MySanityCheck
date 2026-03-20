(ns ems.engine
  (:require [ems.engine.rates :as rates]
            [ems.engine.complaint :as complaint]
            [clojure.string :as str]))

(defn- clamp [v] (-> v (max 0) (min 100)))

(defn- parse-time
  "Extract hour from ISO timestamp string. Returns decimal hours since midnight."
  [ts]
  (when ts
    (let [[_ h m] (re-find #"T(\d{2}):(\d{2})" (str ts))]
      (when h (+ (parse-long h) (/ (parse-long m) 60.0))))))

(defn- hours-since-morning
  "Hours elapsed from 08:00 to now."
  [now]
  (max 0 (- (or (parse-time now) 12) 8)))

;; --- Status helpers ---

(defn- energy-status [v]
  (cond (>= v 80) {:status "充沛" :emoji "🟢"}
        (>= v 60) {:status "良好" :emoji "🟡"}
        (>= v 40) {:status "疲劳" :emoji "🟠"}
        (>= v 20) {:status "耗竭" :emoji "🔴"}
        :else     {:status "崩溃" :emoji "⚫"}))

(defn- mood-status [v]
  (cond (>= v 80) {:status "愉悦" :emoji "😊"}
        (>= v 60) {:status "平和" :emoji "😌"}
        (>= v 40) {:status "低落" :emoji "😐"}
        (>= v 20) {:status "沮丧" :emoji "😔"}
        :else     {:status "崩溃" :emoji "😭"}))

(defn- time-status [available-hrs]
  (cond (> available-hrs 4) {:status "充裕" :emoji "🟢"}
        (> available-hrs 2) {:status "适中" :emoji "🟡"}
        (> available-hrs 1) {:status "紧张" :emoji "🟠"}
        :else               {:status "匮乏" :emoji "🔴"}))

;; --- Morning calibration ---

(defn calibrate-morning
  "Returns initial {:energy :mood} based on morning Roam text."
  [roam-data]
  (let [has-complaint (complaint/complaint? (:morning-text roam-data))]
    {:energy (if has-complaint rates/energy-complaint rates/energy-default)
     :mood   (if has-complaint rates/mood-complaint rates/mood-default)}))

;; --- Screen time decay ---

(defn apply-screen-decay
  "Compute energy drain from screen time data over elapsed hours."
  [energy screen-time-records now]
  (let [hrs (hours-since-morning now)]
    (if (or (zero? hrs) (empty? screen-time-records))
      energy
      (let [total-switches (reduce + 0 (map #(get % :app-switches 0) screen-time-records))
            total-active   (reduce + 0 (map #(get % :total-minutes 0) screen-time-records))
            avg-active-hr  (/ total-active hrs)
            avg-switch-hr  (/ total-switches hrs)
            intensity      (cond (> avg-active-hr 45) (:heavy rates/screen-decay)
                                 (> avg-active-hr 30) (:moderate rates/screen-decay)
                                 :else                (:light rates/screen-decay))
            switch-drain   (if (> avg-switch-hr 15) rates/switch-penalty 0)
            drain-per-hr   (+ intensity switch-drain rates/natural-decay)]
        (clamp (+ energy (* hrs drain-per-hr)))))))

;; --- Event application ---

(defn apply-event
  "Apply a single event to {:energy :mood :events}. Returns updated map."
  [state event]
  (let [impact (get rates/event-rates (:type event) {:energy 0 :mood 0})
        e (clamp (+ (:energy state) (:energy impact)))
        m (clamp (+ (:mood state) (:mood impact)))]
    (-> state
        (assoc :energy e :mood m)
        (update :events conj {:time (:time event)
                              :tag (:type event)
                              :label (or (:context event) (name (:type event)))
                              :deltas impact
                              :snapshot {:energy e :mood m}}))))

(defn apply-events
  "Reduce all events onto state."
  [state events]
  (reduce apply-event state events))

;; --- Time quality ---

(defn compute-time-quality
  "Compute time quality ratio from screen time records."
  [screen-time-records calendar now]
  (let [total-active (reduce + 0 (map #(get % :total-minutes 0) screen-time-records))
        deep-buckets (filter #(< (get % :app-switches 0) 5) screen-time-records)
        deep-min     (reduce + 0 (map #(get % :total-minutes 0) deep-buckets))
        frag-min     (- total-active deep-min)
        ratio        (if (pos? total-active) (double (/ deep-min total-active)) 0.0)
        meeting-min  (get calendar :meeting-minutes 0)
        avail-hrs    (-> (- 16 (/ meeting-min 60.0) (/ total-active 60.0))
                         (max 0) double)
        status-info  (time-status avail-hrs)]
    (merge {:ratio (double ratio) :deep-min deep-min :frag-min frag-min
            :available-hours avail-hrs}
           status-info)))

;; --- Alerts ---

(defn evaluate-alerts
  "Returns vec of alert maps based on current values."
  [energy mood time-quality-ratio]
  (cond-> []
    (< energy rates/energy-critical)
    (conj {:type :exhaustion :severity :critical
           :message "⚠️ 精力耗竭，建议中断当前 Session"})
    (< mood rates/mood-low)
    (conj {:type :mood-low :severity :warn
           :message "😔 情绪低落，考虑休息"})
    (< time-quality-ratio rates/fragmentation-threshold)
    (conj {:type :fragmentation :severity :warn
           :message "🔀 高度碎片化，建议合并时间块"})
    (and (< energy rates/energy-critical) (< mood rates/mood-low))
    (conj {:type :emergency :severity :critical
           :message "🚨 紧急干预 — 立即停止所有工作"})))

;; --- Task recommendation (8-combo matrix) ---

(defn recommend-task
  "Recommend task type based on three-line state."
  [energy available-hrs mood]
  (let [hi-e (>= energy 60) hi-t (> available-hrs 2) hi-m (>= mood 60)]
    (condp = [hi-e hi-t hi-m]
      [true  true  true]  {:task-type "高难度创造" :suggestions ["系统架构设计" "深度写作" "战略规划"]}
      [true  true  false] {:task-type "机械性工作" :suggestions ["代码重构" "文档整理" "数据清洗"]}
      [true  false true]  {:task-type "快速冲刺"   :suggestions ["20min 专注任务" "快速决策"]}
      [true  false false] {:task-type "轻量输出"   :suggestions ["回复消息" "简单审核"]}
      [false true  true]  {:task-type "学习输入"   :suggestions ["阅读" "听播客" "看视频"]}
      [false true  false] {:task-type "强制休息"   :suggestions ["物理熔断" "停止工作"]}
      [false false true]  {:task-type "轻量娱乐"   :suggestions ["刷推" "看短视频"]}
      [false false false] {:task-type "紧急干预"   :suggestions ["立即停止所有工作" "寻求支持"]}
      {:task-type "自由安排" :suggestions ["根据当前状态自行选择"]})))

;; --- Mood regression ---

(defn apply-mood-regression
  "Mood drifts 10% closer to baseline (80) per elapsed hour."
  [mood now]
  (let [hrs (hours-since-morning now)
        baseline 80
        diff (- baseline mood)
        regression (* diff (- 1.0 (Math/pow 0.9 hrs)))]
    (clamp (Math/round (+ mood regression)))))

;; --- Phase detection ---

(defn detect-phase
  "Determine current workflow phase from energy level."
  [energy]
  (cond (< energy 30) :recovery
        (< energy 50) :raw
        :else         :action))

;; --- Trend ---

(defn calc-trend
  "Compute trend from event snapshots for a given key (:energy or :mood)."
  [events k]
  (let [vals (keep #(get-in % [:snapshot k]) events)]
    (if (< (count vals) 2)
      :stable
      (let [recent (last vals) prev (nth vals (- (count vals) 2))]
        (cond (> recent (+ prev 5)) :rising
              (< recent (- prev 5)) :falling
              :else :stable)))))

;; --- Main entry point ---

(defn compute-state
  "Pure fusion function. Takes data maps + clock, returns full state snapshot.
   screen-time-data: vec of hourly bucket maps from DataEngineer
   roam-data: map with :morning-text, :events, :mood-tags, :energy-tags
   config: map (currently unused, reserved for custom rates)
   now: ISO 8601 timestamp string (injected clock)"
  [screen-time-data roam-data config now]
  (let [;; 1. Morning calibration
        {:keys [energy mood]} (calibrate-morning roam-data)
        ;; 2. Screen time decay
        energy-decayed (apply-screen-decay energy screen-time-data now)
        ;; 3. Apply events
        events (or (:events roam-data) [])
        after-events (apply-events {:energy energy-decayed :mood mood :events []} events)
        final-energy (:energy after-events)
        final-mood   (apply-mood-regression (:mood after-events) now)
        ;; 4. Time quality
        tq (compute-time-quality screen-time-data (:calendar config) now)
        ;; 5. Alerts
        alerts (evaluate-alerts final-energy final-mood (:ratio tq))
        ;; 6. Recommendation
        rec (recommend-task final-energy (:available-hours tq) final-mood)
        ;; 7. Phase
        phase (detect-phase final-energy)
        ;; 8. Trends
        evts (:events after-events)
        e-trend (calc-trend evts :energy)
        m-trend (calc-trend evts :mood)]
    {:date      (when now (subs (str now) 0 10))
     :timestamp now
     :energy    (merge {:value final-energy :trend e-trend} (energy-status final-energy))
     :mood      (merge {:value final-mood :trend m-trend} (mood-status final-mood))
     :time-quality tq
     :recommendation rec
     :alerts    alerts
     :events    evts
     :phase     phase}))
