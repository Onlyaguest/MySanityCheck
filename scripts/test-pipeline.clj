#!/usr/bin/env bb

;; Integration test: Roam collector → Engine → Discord (staging)
;; Usage: bb scripts/test-pipeline.clj

(require '[babashka.pods :as pods])
(pods/load-pod 'org.babashka/go-sqlite3 "0.3.13")

(require '[ems.engine :as engine]
         '[ems.collector.roam :as roam]
         '[ems.discord :as discord]
         '[cheshire.core :as json])

;; --- 1. Load config + secrets, resolve staging env ---

(defn load-edn [path]
  (try
    (when (.exists (java.io.File. path))
      (clojure.edn/read-string (slurp path)))
    (catch Exception e
      (println "✗ Failed to load" path ":" (.getMessage e))
      (System/exit 1))))

(let [cfg     (or (load-edn "config.edn") {})
      secrets (or (load-edn (get cfg :secrets-file "secrets.edn")) {})
      config  (merge cfg secrets)
      env     (get config :env :staging)
      roam-cfg (assoc (get-in config [:roam env])
                      :date (str (java.time.LocalDate/now)))
      dc      (let [d (:discord config)]
                {:bot-token  (:bot-token d)
                 :channel-id (if (= env :prod)
                               (:prod-channel d)
                               (:staging-channel d))})]

  (println (str "▸ env: " env))
  (println (str "▸ roam graph: " (:graph roam-cfg)))
  (println (str "▸ date: " (:date roam-cfg)))
  (println)

  ;; --- 2. Call Roam collector ---
  (println "▸ Collecting from Roam...")
  (let [roam-data (roam/collect roam-cfg)]
    (println (str "  morning-text: " (pr-str (:morning-text roam-data))))
    (println (str "  events: " (count (:events roam-data))))
    (println (str "  energy-tags: " (count (:energy-tags roam-data))))
    (println (str "  mood-tags: " (count (:mood-tags roam-data))))
    (println (str "  raw-buffer: " (count (:raw-buffer roam-data))))
    (println)

    ;; --- 3. Feed to engine ---
    (println "▸ Computing state (no screen-time in staging)...")
    (let [now   (str (java.time.ZonedDateTime/now))
          state (engine/compute-state [] roam-data config now)]

      ;; --- 4. Print full snapshot ---
      (println)
      (println "=== STATE SNAPSHOT ===")
      (println (json/generate-string state {:pretty true}))
      (println)

      ;; --- 5. Send test message to Discord staging ---
      (println "▸ Sending to Discord staging channel...")
      (let [msg  (str "🧪 Pipeline test @ " (:date roam-cfg)
                      "\n" (discord/format-state state))
            resp (discord/send-alert! dc {:message msg})]
        (if resp
          (println "✓ Discord message sent")
          (println "✗ Discord send failed")))

      (println)
      (println "✓ Pipeline test complete"))))
