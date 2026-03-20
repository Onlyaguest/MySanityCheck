#!/usr/bin/env bb
;; Seed Roam Research staging graph with test daily note data.
;; Usage: bb scripts/seed-roam.clj

(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def secrets (edn/read-string (slurp "secrets.edn")))
(def env (:env secrets))
(def roam-cfg (get-in secrets [:roam env]))
(def graph (:graph roam-cfg))
(def token (:token roam-cfg))

(println (str "Seeding Roam graph '" graph "' (env: " env ")"))

(defn roam-write! [action-body]
  (let [url  (str "https://api.roamresearch.com/api/graph/" graph "/write")
        resp (http/post url
               {:headers {"X-Authorization" (str "Bearer " token)
                          "Content-Type"    "application/json"}
                :body    (json/generate-string action-body)
                :throw   false})]
    (when (not= 200 (:status resp))
      (println "  WARN:" (:status resp) (:body resp)))
    resp))

(defn create-page! [title uid]
  (println (str "  Creating page: " title))
  (roam-write! {:action "create-page"
                :page   {:title title :uid uid}}))

(defn create-block! [parent-uid string & {:keys [uid order] :or {order "last"}}]
  (println (str "  Block: " (subs string 0 (min 50 (count string)))))
  (roam-write! {:action   "create-block"
                :location {:parent-uid parent-uid :order order}
                :block    (cond-> {:string string}
                            uid (assoc :uid uid))}))

(defn roam-query [query args]
  (let [url  (str "https://api.roamresearch.com/api/graph/" graph "/q")
        resp (http/post url
               {:headers {"X-Authorization" (str "Bearer " token)
                          "Content-Type"    "application/json"}
                :body    (json/generate-string {:query query :args args})
                :throw   false})]
    (when (= 200 (:status resp))
      (:result (json/parse-string (:body resp) true)))))

;; --- Build today's daily note ---

(def today (java.time.LocalDate/now))
(def day (.getDayOfMonth today))
(def suffix (cond (<= 11 day 13) "th"
                  (= 1 (mod day 10)) "st"
                  (= 2 (mod day 10)) "nd"
                  (= 3 (mod day 10)) "rd"
                  :else "th"))
(def title (str (str/capitalize (str/lower-case (str (.getMonth today))))
                " " day suffix ", " (.getYear today)))

(println (str "\nDaily note: " title "\n"))

;; 1. Create daily page (or find existing UID)
(create-page! title (str "seed-" today))
(Thread/sleep 500)

(def page-uid
  (let [result (roam-query "[:find ?uid :in $ ?t :where [?e :node/title ?t] [?e :block/uid ?uid]]" [title])]
    (ffirst result)))

;; 2. Morning entry (triggers complaint: 没睡好, 累)
(create-block! page-uid "早安，昨晚没睡好，有点累" :uid (str "seed-am-" today))
(Thread/sleep 300)

;; 3. Event tags
(create-block! page-uid "#Aha 需求文档写完了" :uid (str "seed-aha-" today))
(Thread/sleep 300)
(create-block! page-uid "#Friction 又被拉进无效会议" :uid (str "seed-fri-" today))
(Thread/sleep 300)
(create-block! page-uid "#Sprint 20min 专注写代码" :uid (str "seed-spr-" today))
(Thread/sleep 300)

;; 4. Mood tags
(create-block! page-uid "#Mood 😊" :uid (str "seed-mood1-" today))
(Thread/sleep 300)
(create-block! page-uid "#Mood 😐" :uid (str "seed-mood2-" today))
(Thread/sleep 300)

;; 5. status/raw buffer item
(create-block! page-uid "[[status/raw]] 研究 Screen Time API 方案" :uid (str "seed-raw-" today))
(Thread/sleep 300)

;; 6. Energy tag for completeness
(create-block! page-uid "#Energy 65" :uid (str "seed-eng-" today))

(println "\n✓ Seed data written.")
