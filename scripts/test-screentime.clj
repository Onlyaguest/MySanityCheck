#!/usr/bin/env bb
;; Test Screen Time collector. Requires FDA granted to Terminal.
;; Usage: bb scripts/test-screentime.clj

(require '[babashka.pods :as pods])
(pods/load-pod 'org.babashka/go-sqlite3 "0.3.13")
(require '[ems.collector.screentime :as st])

(def since (- (quot (System/currentTimeMillis) 1000) 3600)) ;; last 1 hour

(println "Collecting Screen Time data (last 1 hour)...\n")
(let [results (st/collect {:since since})]
  (if (empty? results)
    (println "No data returned. Check FDA or no screen activity in the last hour.")
    (doseq [r results]
      (println (str "Hour: " (:timestamp r)))
      (println (str "  Total minutes: " (:total-minutes r)))
      (println (str "  Unlocks: " (:unlock-count r)))
      (println (str "  App switches: " (:app-switches r)))
      (println (str "  Top apps: " (take 5 (sort-by val > (:app-usage r)))))
      (println))))
