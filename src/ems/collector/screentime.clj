(ns ems.collector.screentime
  "Extracts Screen Time data from macOS knowledgeC.db via pod-babashka-go-sqlite3.
   Returns hourly-bucketed EDN maps of app usage, unlock count, and app switches."
  (:require [babashka.pods :as pods]))

(pods/load-pod 'org.babashka/go-sqlite3 "0.3.13")
(require '[pod.babashka.go-sqlite3 :as sqlite])

;; Core Data epoch offset: 2001-01-01 vs 1970-01-01
(def ^:private core-data-epoch 978307200)

(def ^:private default-db-path
  (str (System/getProperty "user.home")
       "/Library/Application Support/Knowledge/knowledgeC.db"))

(defn- cd->unix [cd-ts] (+ cd-ts core-data-epoch))
(defn- unix->cd [unix-ts] (- unix-ts core-data-epoch))

(defn- hour-bucket
  "Truncate unix timestamp to hour boundary ISO string."
  [unix-ts]
  (let [ms (* unix-ts 1000)
        inst (java.time.Instant/ofEpochMilli ms)
        zdt (.atZone inst (java.time.ZoneId/systemDefault))
        truncated (.truncatedTo zdt java.time.temporal.ChronoUnit/HOURS)]
    (str truncated)))

(defn- query-stream
  "Query ZOBJECT for a given ZSTREAMNAME since a unix timestamp."
  [db-path stream since-unix]
  (sqlite/query db-path
    [(str "SELECT ZVALUESTRING, "
          "ZSTARTDATE + 978307200 as start_ts, "
          "ZENDDATE + 978307200 as end_ts "
          "FROM ZOBJECT "
          "WHERE ZSTREAMNAME = ? AND ZSTARTDATE >= ? "
          "ORDER BY ZSTARTDATE")
     stream (unix->cd since-unix)]))

(defn- bucket-app-usage
  "Group app usage rows into hourly buckets."
  [rows]
  (reduce
    (fn [acc {:keys [ZVALUESTRING start_ts end_ts]}]
      (let [bucket (hour-bucket start_ts)
            dur (max 0 (- end_ts start_ts))
            app (or ZVALUESTRING "unknown")]
        (-> acc
            (update-in [bucket :total-seconds] (fnil + 0) dur)
            (update-in [bucket :app-usage app] (fnil + 0) dur))))
    {} rows))

(defn- count-events-per-hour
  "Count events per hourly bucket."
  [rows]
  (reduce
    (fn [acc {:keys [start_ts]}]
      (let [bucket (hour-bucket start_ts)]
        (update acc bucket (fnil inc 0))))
    {} rows))

(defn collect
  "Collect Screen Time data since `since-unix` (unix epoch seconds).
   Config map keys:
     :db-path  — path to knowledgeC.db (optional, defaults to standard location)
     :since    — unix timestamp to query from (required)
   Returns seq of hourly EDN records."
  [{:keys [db-path since] :or {db-path default-db-path}}]
  (let [usage-rows   (query-stream db-path "/app/usage" since)
        unlock-rows  (query-stream db-path "/device/isLocked" since)
        focus-rows   (query-stream db-path "/app/inFocus" since)
        app-buckets  (bucket-app-usage usage-rows)
        unlock-counts (count-events-per-hour unlock-rows)
        switch-counts (count-events-per-hour focus-rows)
        all-hours    (sort (distinct (concat (keys app-buckets)
                                             (keys unlock-counts)
                                             (keys switch-counts))))]
    (mapv (fn [hour]
            {:type          :screen-time
             :timestamp     hour
             :device        :mac
             :total-minutes (Math/round (/ (get-in app-buckets [hour :total-seconds] 0) 60.0))
             :app-usage     (get-in app-buckets [hour :app-usage] {})
             :unlock-count  (get unlock-counts hour 0)
             :app-switches  (get switch-counts hour 0)})
          all-hours)))

(comment
  ;; Example: collect last 24 hours
  (collect {:since (- (quot (System/currentTimeMillis) 1000) 86400)}))
