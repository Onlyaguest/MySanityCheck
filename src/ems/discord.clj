(ns ems.discord
  "Discord integration: alerts, slash commands, summaries."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]))

(defn- post-webhook!
  "POST JSON to Discord webhook URL."
  [webhook-url content]
  (http/post webhook-url
             {:headers {"Content-Type" "application/json"}
              :body (json/generate-string {:content content})}))

(defn format-state
  "Format engine state as Discord message string."
  [{:keys [energy mood time-quality recommendation]}]
  (str "⚡ Energy: " (:value energy) " " (:emoji energy)
       "  |  ⏱ Time: " (format "%.1f" (:available-hours time-quality)) "h " (:emoji time-quality)
       "  |  " (:emoji mood) " Mood: " (:value mood) " " (:status mood)
       "\n💡 推荐: " (:task-type recommendation)))

(defn send-alert!
  "Send alert to Discord webhook. Returns response."
  [webhook-url alert]
  (post-webhook! webhook-url (:message alert)))

(defn handle-interaction
  "Handle /state slash command. Returns Discord interaction response map."
  [state dashboard-url]
  (let [msg (str (format-state state)
                 (when dashboard-url
                   (str "\n📊 Dashboard: " dashboard-url "/" (:date state))))]
    {:type 4  ;; CHANNEL_MESSAGE_WITH_SOURCE
     :data {:content msg}}))

(defn send-summary!
  "Send morning/evening summary to Discord webhook."
  [webhook-url state summary-type]
  (let [header (case summary-type
                 :morning "☀️ 晨间校准"
                 :evening "🌙 晚间对账"
                 "📊 状态总结")
        alerts-str (when (seq (:alerts state))
                     (str "\n⚠️ " (count (:alerts state)) " 条预警"))
        msg (str header "\n" (format-state state) alerts-str)]
    (post-webhook! webhook-url msg)))
