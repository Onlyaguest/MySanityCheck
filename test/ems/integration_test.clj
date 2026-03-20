(ns ems.integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ems.core :as core]
            [ems.collector.screentime :as st]
            [ems.collector.roam :as roam]
            [ems.discord :as discord]
            [cheshire.core :as json]))

;; --- Mock data ---

(def mock-st-data
  [{:total-minutes 45 :app-switches 18 :unlock-count 6
    :app-usage {"com.apple.Safari" 30 "com.tinyspeck.slackmacgap" 15}}])

(def mock-roam-data
  {:type :roam-daily :date "2026-03-20"
   :morning-text "早安，好累"
   :events [{:time "10:00" :type :friction :context "反复拉扯"}]
   :mood-tags [] :energy-tags [] :raw-buffer []})

(def mock-config
  {:active-env :staging
   :roam-config {:graph "test" :token "test"}
   :discord-config {:bot-token "test" :channel-id "test"}})

;; --- Reset state atom before each test ---

(use-fixtures :each
  (fn [f]
    (reset! core/state nil)
    (f)))

;; --- 1. run-cycle! updates state atom ---

(deftest run-cycle-updates-state
  (testing "run-cycle! with mocked collectors populates state atom"
    (with-redefs [st/collect   (fn [_] mock-st-data)
                  roam/collect (fn [_] mock-roam-data)
                  discord/send-alert! (fn [_ _] nil)]
      (let [snapshot (core/run-cycle! mock-config)]
        ;; State atom updated
        (is (some? @core/state))
        (is (= snapshot @core/state))
        ;; Snapshot has required keys
        (is (number? (get-in snapshot [:energy :value])))
        (is (number? (get-in snapshot [:mood :value])))
        (is (map? (:time-quality snapshot)))
        (is (map? (:recommendation snapshot)))
        (is (vector? (:alerts snapshot)))
        (is (vector? (:events snapshot)))))))

;; --- 2. Complaint calibration flows through run-cycle! ---

(deftest run-cycle-complaint-calibration
  (testing "complaint in morning text lowers starting energy/mood"
    (with-redefs [st/collect   (fn [_] [])
                  roam/collect (fn [_] mock-roam-data)
                  discord/send-alert! (fn [_ _] nil)]
      (core/run-cycle! mock-config)
      ;; "好累" triggers complaint → energy starts at 80, mood at 60
      ;; friction event further reduces both
      (is (< (get-in @core/state [:energy :value]) 80))
      (is (< (get-in @core/state [:mood :value]) 60)))))

;; --- 3. Discord send-alert! called when alerts fire ---

(deftest run-cycle-sends-discord-alerts
  (testing "alerts trigger discord/send-alert! calls"
    (let [alerts-sent (atom [])]
      ;; Mock data that triggers exhaustion: complaint + friction drains energy
      (with-redefs [st/collect   (fn [_] mock-st-data)
                    roam/collect (fn [_] (assoc mock-roam-data
                                           :events (repeat 5 {:time "10:00" :type :friction :context "drain"})))
                    discord/send-alert! (fn [dc alert]
                                          (swap! alerts-sent conj alert)
                                          nil)]
        (core/run-cycle! mock-config)
        (is (pos? (count @alerts-sent))
            "at least one alert should fire with heavy friction load")
        (is (every? :message @alerts-sent)
            "each alert has a :message")))))

;; --- 4. /state API returns valid JSON ---

(deftest state-api-returns-json
  (testing "/state returns 503 when no state"
    (reset! core/state nil)
    (let [handler (core/make-handler mock-config)
          resp    (handler {:uri "/state" :request-method :get})]
      (is (= 503 (:status resp)))
      (is (string? (:body resp)))))

  (testing "/state returns 200 with valid snapshot after cycle"
    (with-redefs [st/collect   (fn [_] mock-st-data)
                  roam/collect (fn [_] mock-roam-data)
                  discord/send-alert! (fn [_ _] nil)]
      (core/run-cycle! mock-config)
      (let [handler (core/make-handler mock-config)
            resp    (handler {:uri "/state" :request-method :get})
            body    (json/parse-string (:body resp) true)]
        (is (= 200 (:status resp)))
        (is (= "application/json" (get-in resp [:headers "Content-Type"])))
        ;; Parsed JSON has expected keys
        (is (map? (:energy body)))
        (is (map? (:mood body)))
        (is (map? (:time-quality body)))
        (is (map? (:recommendation body)))))))

;; --- 5. /health endpoint ---

(deftest health-endpoint
  (testing "/health returns ok"
    (let [handler (core/make-handler mock-config)
          resp    (handler {:uri "/health" :request-method :get})
          body    (json/parse-string (:body resp) true)]
      (is (= 200 (:status resp)))
      (is (= "ok" (:status body))))))
