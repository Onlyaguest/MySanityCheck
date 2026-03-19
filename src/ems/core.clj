(ns ems.core
  (:require [org.httpkit.server :as http]
            [cheshire.core :as json]
            [ems.db :as db]))

(def config
  (let [cfg (clojure.edn/read-string (slurp "config.edn"))
        secrets-file (:secrets-file cfg "secrets.edn")
        secrets (when (.exists (java.io.File. secrets-file))
                  (clojure.edn/read-string (slurp secrets-file)))]
    (merge cfg secrets)))

(defn handler [req]
  (case (:uri req)
    "/state" {:status 200
              :headers {"Content-Type" "application/json"}
              :body (json/generate-string {:status "ok" :message "EMS running"})}
    {:status 404
     :headers {"Content-Type" "application/json"}
     :body (json/generate-string {:error "not found"})}))

(defn start! []
  (db/init!)
  (let [port (get-in config [:server :port] 8400)]
    (http/run-server handler {:port port})
    (println (str "✓ EMS running on port " port))
    ;; TODO: start scheduler (collectors, morning/evening jobs)
    @(promise)))  ;; block forever
