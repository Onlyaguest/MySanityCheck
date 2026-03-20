(ns ems.core
  (:require [babashka.pods :as pods]
            [babashka.http-client]
            [org.httpkit.server :as http]
            [cheshire.core :as json]))

;; --- Pod loading (single point, before any require that uses it) ---
(pods/load-pod 'org.babashka/go-sqlite3 "0.3.13")

(require '[ems.db :as db]
         '[ems.engine :as engine]
         '[ems.collector.screentime :as st]
         '[ems.collector.roam :as roam]
         '[ems.discord :as discord])

;; --- Config loading ---

(defn- load-edn [path]
  (try
    (when (.exists (java.io.File. path))
      (clojure.edn/read-string (slurp path)))
    (catch Exception e
      (println "⚠️  Failed to load" path ":" (.getMessage e))
      nil)))

(defn load-config []
  (let [cfg     (or (load-edn "config.edn") {})
        secrets (or (load-edn (get cfg :secrets-file "secrets.edn")) {})]
    (merge cfg secrets)))

(defn- resolve-env
  "Resolve multi-env secrets. Returns flat config with active roam/discord keys."
  [config]
  (let [env (get config :env :staging)]
    (assoc config
      :active-env env
      :roam-config   (get-in config [:roam env])
      :discord-config (let [d (:discord config)]
                        {:bot-token (:bot-token d)
                         :guild-id  (:guild-id d)
                         :channel-id (if (= env :prod)
                                      (:prod-channel d)
                                      (:staging-channel d))}))))

;; --- State atom ---

(defonce state (atom nil))

;; --- Now helper ---

(defn- now-iso []
  (str (java.time.ZonedDateTime/now)))

(defn- today-str []
  (str (java.time.LocalDate/now)))

;; --- Collector + Engine cycle ---

(defn run-cycle!
  "Run one collect→compute→cache cycle."
  [config]
  (let [since     (- (quot (System/currentTimeMillis) 1000) 86400)
        dc        (:discord-config config)
        st-data   (try (st/collect {:since since}) (catch Exception _ []))
        roam-cfg  (assoc (:roam-config config) :date (today-str))
        roam-data (try (roam/collect roam-cfg) (catch Exception _ {}))
        now       (now-iso)
        snapshot  (engine/compute-state st-data roam-data config now)]
    (reset! state snapshot)
    (doseq [alert (:alerts snapshot)]
      (try (discord/send-alert! dc alert) (catch Exception _)))
    snapshot))

;; --- Scheduler ---

(defn- sleep-ms [ms] (Thread/sleep ms))

(defn- current-hhmm []
  (let [now (java.time.LocalTime/now)]
    [(. now getHour) (. now getMinute)]))

(defn start-scheduler!
  "Runs collector cycles on intervals. Blocks on its own thread."
  [config]
  (let [st-interval-ms  (* (get-in config [:intervals :screentime] 30) 60 1000)
        roam-interval-ms (* (get-in config [:intervals :roam] 60) 60 1000)
        dc               (:discord-config config)
        morning-sent     (atom false)
        evening-sent     (atom false)]
    (future
      (println "✓ Scheduler started")
      (loop [last-st 0 last-roam 0]
        (let [now-ms (System/currentTimeMillis)
              [h _m] (current-hhmm)
              run-st?   (>= (- now-ms last-st) st-interval-ms)
              run-roam? (>= (- now-ms last-roam) roam-interval-ms)]
          ;; Run cycle if any collector is due
          (when (or run-st? run-roam?)
            (try
              (run-cycle! config)
              (println (str "↻ Cycle at " (now-iso)))
              (catch Exception e
                (println "⚠️  Cycle error:" (.getMessage e)))))
          ;; Morning calibration summary (08:00-08:05)
          (when (and (= h 8) (not @morning-sent))
            (when-let [s @state]
              (try (discord/send-summary! dc s :morning) (catch Exception _)))
            (reset! morning-sent true))
          ;; Evening review summary (21:00-21:05)
          (when (and (= h 21) (not @evening-sent))
            (when-let [s @state]
              (try (discord/send-summary! dc s :evening) (catch Exception _)))
            (reset! evening-sent true))
          ;; Reset daily flags at midnight
          (when (= h 0)
            (reset! morning-sent false)
            (reset! evening-sent false))
          (sleep-ms 60000)  ;; check every minute
          (recur (if run-st? now-ms last-st)
                 (if run-roam? now-ms last-roam)))))))

;; --- HTTP API ---

(def ^:private cors-headers
  {"Access-Control-Allow-Origin"  "*"
   "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
   "Access-Control-Allow-Headers" "Content-Type"})

(defn- json-response [status body]
  {:status status
   :headers (merge {"Content-Type" "application/json"} cors-headers)
   :body (json/generate-string body)})

(defn make-handler
  "Returns handler closed over resolved config (for dashboard-url)."
  [config]
  (let [dashboard-url (get-in config [:dashboard :url])]
    (fn [req]
      (if (= :options (:request-method req))
        {:status 204 :headers cors-headers}

        (case (:uri req)
          "/state"
          (if-let [s @state]
            (json-response 200 s)
            (json-response 503 {:error "no state yet, first cycle pending"}))

          "/discord/interactions"
          (if (= :post (:request-method req))
            (discord/handle-interaction req state dashboard-url)
            (json-response 405 {:error "POST only"}))

          "/health"
          (json-response 200 {:status "ok" :has-state (some? @state)})

          (json-response 404 {:error "not found"}))))))

;; --- Entry points ---

(defn- detect-terminal-bin
  "Detect the terminal binary path for FDA instructions."
  []
  (or (try
        (let [ppid (System/getenv "PPID")
              proc (-> (Runtime/getRuntime)
                       (.exec (into-array ["ps" "-o" "comm=" "-p" ppid])))]
          (.waitFor proc)
          (some-> proc .getInputStream slurp clojure.string/trim not-empty))
        (catch Exception _ nil))
      (System/getenv "TERM_PROGRAM")
      "your terminal app"))

(defn- check-screentime []
  (let [db-path (str (System/getProperty "user.home")
                     "/Library/Application Support/Knowledge/knowledgeC.db")]
    (if-not (.exists (java.io.File. db-path))
      {:ok false :msg "knowledgeC.db not found — Screen Time may be disabled"}
      (try
        (pod.babashka.go-sqlite3/query db-path
          ["SELECT COUNT(*) as c FROM ZOBJECT WHERE ZSTREAMNAME = '/app/usage' LIMIT 1"])
        {:ok true :msg "Screen Time access OK"}
        (catch Exception e
          (let [msg (.getMessage e)
                term (detect-terminal-bin)]
            (if (or (clojure.string/includes? (str msg) "not permitted")
                    (clojure.string/includes? (str msg) "authorization denied"))
              (do
                (when term
                  (try (.exec (Runtime/getRuntime) (into-array ["open" "-R" term]))
                       (catch Exception _)))
                {:ok false
                 :msg (str "Screen Time access denied. Grant Full Disk Access to: " term)
                 :instructions
               [(str "1. Open System Settings → Privacy & Security → Full Disk Access")
                (str "2. Finder opened — drag the highlighted binary into FDA")
                (str "3. Restart your terminal and run: bb init")]}
              {:ok false :msg (str "Screen Time read error: " msg)}))))))))

(defn- check-roam [config]
  (let [{:keys [token graph]} (:roam-config config)]
    (if-not (and token graph)
      {:ok false :msg "Roam API not configured — check secrets.edn :roam"}
      (try
        (let [resp (babashka.http-client/post
                     (str "https://api.roamresearch.com/api/graph/" graph "/q")
                     {:headers {"X-Authorization" (str "Bearer " token)
                                "Content-Type" "application/json"}
                      :body (json/generate-string {:query "[:find (count ?b) :where [?b :block/uid]]"})
                      :throw false})]
          (if (= 200 (:status resp))
            {:ok true :msg (str "Roam API OK (graph: " graph ")")}
            {:ok false :msg (str "Roam API error: HTTP " (:status resp))}))
        (catch Exception e
          {:ok false :msg (str "Roam API unreachable: " (.getMessage e))})))))

(defn- check-discord [config]
  (let [{:keys [bot-token channel-id]} (:discord-config config)]
    (if-not (and bot-token channel-id)
      {:ok false :msg "Discord not configured — check secrets.edn :discord"}
      (try
        (discord/send-alert! (:discord-config config)
                             {:message "🔧 EMS init test — if you see this, Discord is working!"})
        {:ok true :msg (str "Discord OK (channel: " channel-id ")")}
        (catch Exception e
          {:ok false :msg (str "Discord send failed: " (.getMessage e))})))))

(defn init-only! []
  (let [config (resolve-env (load-config))
        checks [{:name "Database" :result (do (db/init!) {:ok true :msg "ems.db initialized"})}
                {:name "Screen Time" :result (check-screentime)}
                {:name "Roam API" :result (check-roam config)}
                {:name "Discord" :result (check-discord config)}]]
    (println "\n━━━ EMS Init ━━━\n")
    (doseq [{:keys [name result]} checks]
      (println (str (if (:ok result) "✅" "❌") " " name ": " (:msg result)))
      (doseq [line (:instructions result)]
        (println (str "   " line))))
    ;; Try to open FDA settings if Screen Time failed
    (when-let [st (some #(when (= "Screen Time" (:name %)) (:result %)) checks)]
      (when (and (not (:ok st)) (:instructions st))
        (try
          (.exec (Runtime/getRuntime)
                 (into-array ["open" "x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles"]))
          (println "\n📎 Opened System Settings → Full Disk Access")
          (catch Exception _))))
    (println "\n━━━━━━━━━━━━━━━━")
    (let [ok-count (count (filter #(:ok (:result %)) checks))
          total (count checks)]
      (println (str ok-count "/" total " checks passed."))
      (when (< ok-count total)
        (println "Fix the issues above, then run: bb init")))))

(defn start! []
  (let [config (resolve-env (load-config))
        port   (get-in config [:server :port] 8400)]
    (db/init!)
    ;; Run first cycle immediately
    (try (run-cycle! config)
         (catch Exception e (println "⚠️  Initial cycle failed:" (.getMessage e))))
    ;; Start scheduler
    (start-scheduler! config)
    ;; Start HTTP server
    (http/run-server (make-handler config) {:port port})
    (println (str "✓ EMS running on port " port " [env=" (:active-env config) "]"))
    @(promise)))
