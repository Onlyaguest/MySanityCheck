(ns ems.collector.roam
  "Polls Roam Research Backend API for daily notes, parses #Energy #Mood
   #Aha #Friction #Sprint tags and status/raw blocks. Returns EDN maps."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [ems.engine.complaint :as complaint]))

;; --- Roam key normalization ---
;; Roam API returns JSON keys like ":block/string", ":create/time" (colon-prefixed).
;; Cheshire keywordizes these to keywords with ns ":block" which don't match :block/string.
;; We parse without keywordize and normalize keys ourselves.

(defn- normalize-key
  "Convert Roam JSON key \":block/string\" → :block/string"
  [k]
  (keyword (if (str/starts-with? k ":") (subs k 1) k)))

(defn- normalize-block
  "Normalize a Roam block map's keys."
  [m]
  (when m
    (into {} (map (fn [[k v]] [(normalize-key k) v]) m))))

;; --- API layer ---

(defn- roam-post
  "POST to Roam Backend API. Uses X-Authorization header."
  [{:keys [graph token]} endpoint body]
  (try
    (let [url  (str "https://api.roamresearch.com/api/graph/" graph endpoint)
          resp (http/post url
                 {:headers {"X-Authorization" (str "Bearer " token)
                            "Content-Type"    "application/json"}
                  :body    (json/generate-string body)
                  :throw   false})]
      (when (= 200 (:status resp))
        (json/parse-string (:body resp) false)))
    (catch Exception _e nil)))

(defn- q [config query & args]
  (let [result (roam-post config "/q"
                 (cond-> {:query query}
                   (seq args) (assoc :args (vec args))))]
    (get result "result")))

;; --- Date formatting ---

(defn- roam-date-title [date-str]
  (let [dt     (java.time.LocalDate/parse date-str)
        month  (.getMonth dt)
        day    (.getDayOfMonth dt)
        year   (.getYear dt)
        suffix (cond
                 (<= 11 day 13)       "th"
                 (= 1 (mod day 10))   "st"
                 (= 2 (mod day 10))   "nd"
                 (= 3 (mod day 10))   "rd"
                 :else                "th")
        mname  (str/capitalize (str/lower-case (str month)))]
    (str mname " " day suffix ", " year)))

;; --- Parsing ---

(defn- extract-number [s]
  (when-let [m (re-find #"\d+" (or s ""))]
    (parse-long m)))

(defn- block-time [block]
  (when-let [ct (:create/time block)]
    (str (java.time.Instant/ofEpochMilli ct))))

(defn- parse-tagged-blocks [blocks]
  (mapv (fn [b]
          (let [s (:block/string b)]
            {:time (block-time b) :value (extract-number s) :text s}))
        blocks))

(defn- parse-events [tag-type blocks]
  (mapv (fn [b]
          {:time (block-time b) :type tag-type :context (or (:block/string b) "")})
        blocks))

;; --- Queries ---

(def ^:private daily-blocks-q
  "[:find (pull ?b [:block/string :block/uid :create/time])
    :in $ ?title
    :where [?p :node/title ?title] [?b :block/page ?p]]")

(def ^:private tagged-blocks-q
  "[:find (pull ?b [:block/string :block/uid :create/time])
    :in $ ?tag
    :where [?ref :node/title ?tag] [?b :block/refs ?ref]]")

(defn- query-blocks
  "Run a query and normalize all returned block maps."
  [config query & args]
  (let [rows (apply q config query args)]
    (mapv (comp normalize-block first) (or rows []))))

;; --- Public API ---

(defn collect
  "Collect Roam daily data for a given date.
   Config keys: :graph, :token (from resolved secrets), :date (YYYY-MM-DD).
   Returns EDN map. Nil-safe on API failure."
  [{:keys [date] :as config}]
  (let [title        (roam-date-title date)
        daily-blocks (query-blocks config daily-blocks-q title)
        morning-text (->> daily-blocks
                          (filter #(some-> (:block/string %) (str/includes? "早安")))
                          first :block/string)]
    {:type               :roam-daily
     :date               date
     :morning-complaint? (complaint/complaint? morning-text)
     :morning-text       morning-text
     :energy-tags        (parse-tagged-blocks (query-blocks config tagged-blocks-q "Energy"))
     :mood-tags          (parse-tagged-blocks (query-blocks config tagged-blocks-q "Mood"))
     :events             (vec (concat (parse-events :aha (query-blocks config tagged-blocks-q "Aha"))
                                      (parse-events :friction (query-blocks config tagged-blocks-q "Friction"))
                                      (parse-events :sprint (query-blocks config tagged-blocks-q "Sprint"))))
     :raw-buffer         (mapv :block/string (query-blocks config tagged-blocks-q "status/raw"))}))
