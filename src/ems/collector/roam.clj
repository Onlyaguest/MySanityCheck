(ns ems.collector.roam
  "Polls Roam Research Backend API for daily notes, parses #Energy #Mood
   #Aha #Friction #Sprint tags and status/raw blocks. Returns EDN maps."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; --- API layer ---

(defn- roam-post
  "POST to Roam Backend API. Returns parsed JSON body or nil on failure."
  [{:keys [graph token]} endpoint body]
  (try
    (let [url (str "https://api.roamresearch.com/api/graph/" graph endpoint)
          resp (http/post url
                 {:headers {"Authorization" (str "Bearer " token)
                            "Content-Type"  "application/json"}
                  :body (json/generate-string body)
                  :throw false})]
      (when (= 200 (:status resp))
        (json/parse-string (:body resp) true)))
    (catch Exception _e nil)))

(defn- q
  "Run a Datalog query against the Roam graph."
  [config query & args]
  (roam-post config "/q"
    (cond-> {:query query}
      (seq args) (assoc :args (vec args)))))

;; --- Date formatting ---

(defn- roam-date-title
  "Convert YYYY-MM-DD to Roam's daily page title format: 'March 19th, 2026'."
  [date-str]
  (let [dt (java.time.LocalDate/parse date-str)
        month (.getMonth dt)
        day (.getDayOfMonth dt)
        year (.getYear dt)
        suffix (cond
                 (<= 11 day 13) "th"
                 (= 1 (mod day 10)) "st"
                 (= 2 (mod day 10)) "nd"
                 (= 3 (mod day 10)) "rd"
                 :else "th")
        month-name (str/capitalize (str/lower-case (str month)))]
    (str month-name " " day suffix ", " year)))

;; --- Parsing ---

(def ^:private complaint-keywords
  #{"累" "烦" "差" "没睡好" "头疼" "焦虑" "难受" "不想" "崩" "丧" "糟"
    "tired" "bad" "awful" "exhausted" "anxious"})

(defn- complaint?
  "Check if text contains complaint keywords."
  [text]
  (let [t (str/lower-case (or text ""))]
    (boolean (some #(str/includes? t %) complaint-keywords))))

(defn- extract-number
  "Extract first integer from a block string, e.g. 'Energy:: 70' → 70."
  [s]
  (when-let [m (re-find #"\d+" (or s ""))]
    (parse-long m)))

(defn- block->time
  "Extract create/time from a block map, return ISO string or nil."
  [{:keys [create/time]}]
  (when time
    (str (java.time.Instant/ofEpochMilli time))))

(defn- parse-tagged-blocks
  "Parse blocks into tag records with optional numeric value."
  [blocks]
  (mapv (fn [b]
          (let [s (:block/string b)]
            {:time  (block->time b)
             :value (extract-number s)
             :text  s}))
        blocks))

(defn- parse-events
  "Parse event-tagged blocks (#Aha #Friction #Sprint) into event records."
  [tag-type blocks]
  (mapv (fn [b]
          {:time    (block->time b)
           :type    tag-type
           :context (or (:block/string b) "")})
        blocks))

;; --- Queries ---

(def ^:private daily-blocks-query
  "[:find (pull ?b [:block/string :block/uid :create/time])
    :in $ ?title
    :where
    [?p :node/title ?title]
    [?b :block/page ?p]]")

(def ^:private tagged-blocks-query
  "[:find (pull ?b [:block/string :block/uid :create/time])
    :in $ ?tag
    :where
    [?ref :node/title ?tag]
    [?b :block/refs ?ref]]")

(defn- query-tagged [config tag]
  (let [result (q config tagged-blocks-query tag)]
    (mapv first (or result []))))

;; --- Public API ---

(defn collect
  "Collect Roam daily data for a given date.
   Config map keys:
     :graph  — Roam graph name (required)
     :token  — Roam API token (required)
     :date   — 'YYYY-MM-DD' string (required)
   Returns EDN map or nil if API unreachable."
  [{:keys [date] :as config}]
  (let [title        (roam-date-title date)
        daily-result (q config daily-blocks-query title)
        daily-blocks (mapv first (or daily-result []))
        morning-text (->> daily-blocks
                          (filter #(some-> (:block/string %) (str/includes? "早安")))
                          first :block/string)
        energy-blocks (query-tagged config "Energy")
        mood-blocks   (query-tagged config "Mood")
        aha-blocks    (query-tagged config "Aha")
        friction-blocks (query-tagged config "Friction")
        sprint-blocks (query-tagged config "Sprint")
        raw-blocks    (query-tagged config "status/raw")]
    {:type              :roam-daily
     :date              date
     :morning-complaint? (complaint? morning-text)
     :morning-text      morning-text
     :energy-tags       (parse-tagged-blocks energy-blocks)
     :mood-tags         (parse-tagged-blocks mood-blocks)
     :events            (concat (parse-events :aha aha-blocks)
                                (parse-events :friction friction-blocks)
                                (parse-events :sprint sprint-blocks))
     :raw-buffer        (mapv :block/string raw-blocks)}))

(comment
  ;; Example usage
  (collect {:graph "my-graph"
            :token "roam-api-token-here"
            :date  "2026-03-19"}))
