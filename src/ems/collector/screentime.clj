(ns ems.collector.screentime
  "Extracts Screen Time data from macOS knowledgeC.db.
   Assumes pod-babashka-go-sqlite3 is already loaded by the caller (core.clj)."
  (:require [pod.babashka.go-sqlite3 :as sqlite]))

(def ^:private core-data-epoch 978307200)

(def ^:private default-db-path
  (str (System/getProperty "user.home")
       "/Library/Application Support/Knowledge/knowledgeC.db"))

(defn- unix->cd [unix-ts] (- unix-ts core-data-epoch))

(defn- hour-bucket [unix-ts]
  (let [inst (java.time.Instant/ofEpochMilli (* unix-ts 1000))
        zdt  (.atZone inst (java.time.ZoneId/systemDefault))
        trunc (.truncatedTo zdt java.time.temporal.ChronoUnit/HOURS)]
    (str trunc)))

(defn- query-stream [db-path stream since-unix]
  (sqlite/query db-path
    [(str "SELECT ZVALUESTRING, "
          "ZSTARTDATE + 978307200 as start_ts, "
          "ZENDDATE + 978307200 as end_ts "
          "FROM ZOBJECT "
          "WHERE ZSTREAMNAME = ? AND ZSTARTDATE >= ? "
          "ORDER BY ZSTARTDATE")
     stream (unix->cd since-unix)]))

(defn- bucket-app-usage [rows]
  (reduce
    (fn [acc {:keys [ZVALUESTRING start_ts end_ts]}]
      (let [bucket (hour-bucket start_ts)
            dur    (max 0 (- end_ts start_ts))
            app    (or ZVALUESTRING "unknown")]
        (-> acc
            (update-in [bucket :total-seconds] (fnil + 0) dur)
            (update-in [bucket :app-usage app] (fnil + 0) dur))))
    {} rows))

(defn- count-events-per-hour [rows]
  (reduce
    (fn [acc {:keys [start_ts]}]
      (update acc (hour-bucket start_ts) (fnil inc 0)))
    {} rows))

(defn- db-accessible? [db-path]
  (try
    (sqlite/query db-path ["SELECT 1 FROM ZOBJECT LIMIT 1"])
    true
    (catch Exception _ false)))

(defn collect
  "Collect Screen Time data since `since` (unix epoch seconds).
   Config keys: :db-path (optional), :since (required).
   Returns vector of hourly EDN records, or empty vec if DB inaccessible (FDA not granted)."
  [{:keys [db-path since] :or {db-path default-db-path}}]
  (if-not (db-accessible? db-path)
    (do (println "[screentime] WARNING: knowledgeC.db inaccessible — check Full Disk Access")
        [])
    (let [usage-rows    (query-stream db-path "/app/usage" since)
          unlock-rows   (query-stream db-path "/device/isLocked" since)
          focus-rows    (query-stream db-path "/app/inFocus" since)
          app-buckets   (bucket-app-usage usage-rows)
          unlock-counts (count-events-per-hour unlock-rows)
          switch-counts (count-events-per-hour focus-rows)
          all-hours     (sort (distinct (concat (keys app-buckets)
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
            all-hours))))
