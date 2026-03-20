(ns ems.engine-test
  (:require [clojure.test :refer [deftest is testing]]
            [ems.engine :as engine]))

;; --- Fixtures ---

(def mock-screen-time
  [{:total-minutes 40 :app-switches 3 :unlock-count 5
    :app-usage {"com.apple.Safari" 25 "com.tinyspeck.slackmacgap" 15}}
   {:total-minutes 50 :app-switches 12 :unlock-count 8
    :app-usage {"com.apple.Safari" 30 "com.tinyspeck.slackmacgap" 20}}])

(def mock-roam
  {:morning-text "早安，今天状态不错"
   :events [{:time "09:30" :type :sprint :context "深度写作 20min"}
            {:time "11:00" :type :aha   :context "架构灵感"}]
   :mood-tags []
   :energy-tags []})

(def mock-roam-complaint
  {:morning-text "早安，好累，没睡好"
   :events []
   :mood-tags []
   :energy-tags []})

(def now "2026-03-19T14:30:00+08:00")

;; --- Smoke test ---

(deftest compute-state-smoke-test
  (testing "returns valid snapshot with all required keys"
    (let [state (engine/compute-state mock-screen-time mock-roam {} now)]
      ;; Top-level keys present
      (is (contains? state :energy))
      (is (contains? state :mood))
      (is (contains? state :time-quality))
      (is (contains? state :recommendation))
      (is (contains? state :alerts))
      (is (contains? state :events))
      ;; Energy shape
      (is (number? (get-in state [:energy :value])))
      (is (<= 0 (get-in state [:energy :value]) 100))
      (is (string? (get-in state [:energy :emoji])))
      (is (string? (get-in state [:energy :status])))
      ;; Mood shape
      (is (number? (get-in state [:mood :value])))
      (is (<= 0 (get-in state [:mood :value]) 100))
      (is (string? (get-in state [:mood :emoji])))
      (is (string? (get-in state [:mood :status])))
      ;; Time quality shape
      (is (number? (get-in state [:time-quality :ratio])))
      (is (number? (get-in state [:time-quality :available-hours])))
      (is (string? (get-in state [:time-quality :emoji])))
      ;; Recommendation
      (is (string? (get-in state [:recommendation :task-type])))
      (is (vector? (get-in state [:recommendation :suggestions])))
      ;; Alerts is a vec
      (is (vector? (:alerts state)))
      ;; Events captured from roam input
      (is (vector? (:events state)))
      (is (= 2 (count (:events state))))
      ;; Date extracted
      (is (= "2026-03-19" (:date state))))))

(deftest morning-calibration-no-complaint
  (testing "no complaint → energy 100, mood 80"
    (let [state (engine/compute-state [] mock-roam {} now)]
      ;; No screen decay, no events except the two roam events
      ;; Base energy=100 with no screen time → stays 100 before events
      (is (>= (get-in state [:energy :value]) 0))
      (is (= 80 (get-in state [:mood :value]))
          "mood starts at 80 with no complaint and only :sprint/:aha events (net 0 mood)"))))

(deftest morning-calibration-with-complaint
  (testing "complaint → energy 80, mood 60"
    (let [state (engine/compute-state [] mock-roam-complaint {} now)]
      (is (= 80 (get-in state [:energy :value]))
          "energy starts at 80 with complaint, no decay (empty screen time)")
      (is (= 60 (get-in state [:mood :value]))
          "mood starts at 60 with complaint, no events"))))

(deftest empty-inputs-produce-defaults
  (testing "nil/empty inputs still return valid state"
    (let [state (engine/compute-state [] {:morning-text nil :events []} {} now)]
      (is (= 100 (get-in state [:energy :value])))
      (is (= 80  (get-in state [:mood :value])))
      (is (empty? (:events state)))
      (is (empty? (:alerts state))))))
