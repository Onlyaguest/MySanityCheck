#!/usr/bin/env bb

;; Integration test: Screen Time + Roam → Engine → Discord (staging)
;; Usage: bb scripts/test-pipeline.clj
;;
;; ⚠️  FDA REQUIREMENT: This script reads macOS knowledgeC.db.
;; Must run from a process with Full Disk Access (Terminal.app or launchd).
;; Will NOT work from tmux unless tmux was restarted after FDA was granted.

(require '[babashka.pods :as pods])
(pods/load-pod 'org.babashka/go-sqlite3 "0.3.13")

(require '[ems.engine :as engine]
         '[ems.collector.screentime :as st]
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
  (println (str "▸ date: " (:date roam-cfg)))
  (println)

  ;; --- 2. Collect Screen Time (last 24h) ---
  (println "▸ Collecting Screen Time...")
  (let [since   (- (quot (System/currentTimeMillis) 1000) 86400)
        st-data (st/collect {:since since})]
    (println (str "  hourly buckets: " (count st-data)))
    (when (seq st-data)
      (let [total-min (reduce + 0 (map :total-minutes st-data))
            total-sw  (reduce + 0 (map :app-switches st-data))]
        (println (str "  total minutes: " total-min))
        (println (str "  total app switches: " total-sw))))
    (when (empty? st-data)
      (println "  ⚠️  No data — check FDA permissions (must run from Terminal.app, not tmux)"))
    (println)

    ;; --- 3. Collect Roam ---
    (println "▸ Collecting from Roam...")
    (let [roam-data (roam/collect roam-cfg)]
      (println (str "  morning-text: " (pr-str (:morning-text roam-data))))
      (println (str "  events: " (count (:events roam-data))))
      (println (str "  raw-buffer: " (count (:raw-buffer roam-data))))
      (println)

      ;; --- 4. Feed to engine ---
      (println "▸ Computing state...")
      (let [now   (str (java.time.ZonedDateTime/now))
            state (engine/compute-state st-data roam-data config now)]

        ;; --- 5. Print snapshot ---
        (println)
        (println "=== STATE SNAPSHOT ===")
        (println (json/generate-string state {:pretty true}))
        (println)

        ;; --- 6. Send to Discord staging ---
        (println "▸ Sending to Discord staging channel...")
        (let [msg  (str "🧪 Pipeline test @ " (:date roam-cfg)
                        "\n" (discord/format-state state)
                        (when (seq st-data)
                          (str "\n📱 Screen Time: " (count st-data) " buckets")))
              resp (discord/send-alert! dc {:message msg})]
          (if resp
            (println "✓ Discord message sent")
            (println "✗ Discord send failed")))

        (println)
        (println "✓ Pipeline test complete")))))
