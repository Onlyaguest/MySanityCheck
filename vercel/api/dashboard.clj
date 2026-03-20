(ns api.dashboard
  (:require [cheshire.core :as json]
            [babashka.http-client :as http]
            [clojure.string :as str]
            [hiccup2.core :as h]))

;; --- Data fetching ---

(defn fetch-state [date]
  (try
    (let [url (or (System/getenv "EMS_API_URL") "http://localhost:8090")
          resp (http/get (str url "/state")
                         {:query-params (when date {"date" date})
                          :timeout 5000
                          :throw false})]
      (when (= 200 (:status resp))
        (json/parse-string (:body resp) true)))
    (catch Exception _ nil)))

(defn demo-state []
  {:date "2026-03-20" :timestamp "2026-03-20T10:30:00+08:00"
   :energy {:value 72 :status "良好" :emoji "🟡" :trend "falling"}
   :mood {:value 85 :status "愉悦" :emoji "😊" :trend "stable"}
   :time-quality {:available-hours 3.5 :ratio 0.7 :deep-min 180 :frag-min 45
                  :status "适中" :emoji "🟡"}
   :phase "action"
   :recommendation {:task-type "高难度创造" :suggestions ["系统架构设计" "深度写作" "战略规划"]}
   :alerts []
   :events [{:time "08:00" :tag "calibration" :label "晨间校准"
             :deltas {:energy 0 :mood 0} :snapshot {:energy 100 :mood 80}}
            {:time "09:30" :tag "sprint" :label "深度写作 20min"
             :deltas {:energy -18 :mood 0} :snapshot {:energy 82 :mood 80}}
            {:time "10:15" :tag "friction" :label "抽象沟通拉扯"
             :deltas {:energy -30 :mood -15} :snapshot {:energy 52 :mood 65}}]})

;; --- Trend arrow ---

(defn trend-arrow [t]
  (case (keyword t)
    :rising  "↗" :falling "↘" :stable "→" ""))

;; --- Delta display ---

(defn fmt-delta [v]
  (cond (pos? v) (str "+" v) (neg? v) (str v) :else "0"))

;; --- Hiccup rendering ---

(defn render-alerts [alerts]
  (when (seq alerts)
    [:div.alerts
     (for [a alerts]
       [:div.alert {:class (name (or (:severity a) :warn))}
        (:message a)])]))

(defn render-gauges [state]
  (let [e (:energy state) m (:mood state) tq (:time-quality state)]
    [:section.gauges
     [:div.gauge
      [:div.gauge-header [:span.gauge-icon "⚡"] [:span.gauge-label "Energy"]]
      [:div.gauge-bar [:div.gauge-fill.energy {:style (str "width:" (:value e) "%")}]]
      [:div.gauge-meta
       [:span (str (:value e) "/100 " (:emoji e))]
       [:span.trend (trend-arrow (:trend e))]]]
     [:div.gauge
      [:div.gauge-header [:span.gauge-icon "⏱"] [:span.gauge-label "Time"]]
      [:div.gauge-bar [:div.gauge-fill.time
                       {:style (str "width:" (min 100 (* (/ (:available-hours tq 0) 8.0) 100)) "%")}]]
      [:div.gauge-meta
       [:span (str (:available-hours tq) "h " (:emoji tq))]]]
     [:div.gauge
      [:div.gauge-header [:span.gauge-icon "😊"] [:span.gauge-label "Mood"]]
      [:div.gauge-bar [:div.gauge-fill.mood {:style (str "width:" (:value m) "%")}]]
      [:div.gauge-meta
       [:span (str (:value m) "/100 " (:emoji m))]
       [:span.trend (trend-arrow (:trend m))]]]]))

(defn render-events [events]
  [:section.events
   [:h2 "Events"]
   (if (seq events)
     [:div.event-list
      (for [ev events]
        [:div.event
         [:div.event-time (:time ev)]
         [:div.event-body
          [:div.event-label
           [:span.event-tag (str ":" (:tag ev))]
           [:span (:label ev)]]
          [:div.event-deltas
           [:span.delta.energy (str "E:" (fmt-delta (get-in ev [:deltas :energy] 0)))]
           [:span.delta.mood (str "M:" (fmt-delta (get-in ev [:deltas :mood] 0)))]]]])]
     [:p.empty "No events yet"])])

(defn render-recommendation [rec]
  (when rec
    [:section.rec
     [:h2 (str "💡 " (:task-type rec))]
     [:ul (for [s (:suggestions rec)] [:li s])]]))

(defn render-page [state]
  (str
   "<!DOCTYPE html>"
   (h/html
    [:html {:lang "zh-CN"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width,initial-scale=1"}]
      [:title (str "EMS — " (:date state))]
      [:link {:rel "stylesheet" :href "/style.css"}]]
     [:body
      [:header
       [:h1 (str "EMS — " (:date state))]
       [:span.phase (str "Phase: " (name (or (keyword (:phase state)) :unknown)))]]
      (when (:offline state)
        [:div.alert.warn "⚠ Offline — showing demo data"])
      (render-alerts (:alerts state))
      (render-gauges state)
      (render-events (:events state))
      (render-recommendation (:recommendation state))
      [:footer [:p "Energy Management System"]]]])))

;; --- Handler ---

(defn handler [{:keys [query-params]}]
  (let [date (get query-params "date")
        live (fetch-state date)
        state (or live (assoc (demo-state) :offline true))]
    {:status 200
     :headers {"content-type" "text/html; charset=utf-8"}
     :body (render-page state)}))
