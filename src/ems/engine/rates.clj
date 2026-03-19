(ns ems.engine.rates)

;; Energy/Mood impact per event type (from spec v0.2, hardcoded for now)
(def event-rates
  {:aha          {:energy   5 :mood  10}
   :friction     {:energy -30 :mood -15}
   :sprint       {:energy -18 :mood   0}
   :social-drain {:energy -25 :mood  -8}
   :family-time  {:energy  10 :mood  10}
   :solo-rest    {:energy  10 :mood   5}
   :outdoor      {:energy   8 :mood   8}
   :nap          {:energy  15 :mood   5}
   :deep-convo   {:energy   5 :mood  10}
   :meeting      {:energy -10 :mood  -3}
   :task-done    {:energy   0 :mood   8}
   :feedback-pos {:energy   0 :mood   5}
   :family-fight {:energy   0 :mood -20}
   :work-setback {:energy   0 :mood -10}
   :social-pressure {:energy 0 :mood -8}
   :sick         {:energy   0 :mood -10}})

;; Screen time decay rates (energy per hour)
(def screen-decay
  {:heavy   -5   ;; >45 active min/hr
   :moderate -3  ;; >30 active min/hr
   :light   -1}) ;; <=30 active min/hr

(def switch-penalty -3)       ;; per hour when >15 switches/hr
(def natural-decay  -2)       ;; baseline energy drain per hour
(def mood-regression-rate 2)  ;; mood moves +2/hr toward baseline (80)

;; Calibration defaults
(def energy-default 100)
(def energy-complaint 80)
(def mood-default 80)
(def mood-complaint 60)

;; Alert thresholds
(def energy-critical 30)
(def mood-low 40)
(def fragmentation-threshold 0.3)
