(ns ems.core
  (:require [babashka.pods :as pods]
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

(defn- read-body [req]
  (some-> (:body req) slurp (json/parse-string true)))

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
            (let [body (read-body req)
                  ;; Discord ping (type 1) → pong
                  ;; Slash command (type 2) → state response
                  resp (case (:type body)
                         1 {:type 1}
                         2 (discord/handle-interaction @state dashboard-url)
                         {:type 4 :data {:content "Unknown interaction"}})]
              (json-response 200 resp))
            (json-response 405 {:error "POST only"}))

          "/health"
          (json-response 200 {:status "ok" :has-state (some? @state)})

          (json-response 404 {:error "not found"}))))))

;; --- Entry points ---

(defn init-only! []
  (db/init!))

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
