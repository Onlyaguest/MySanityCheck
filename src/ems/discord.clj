(ns ems.discord
  "Discord integration: alerts, slash commands, summaries.
   All fns that POST to Discord take a resolved channel webhook URL."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]))

(defn resolve-channel
  "Given secrets map, return the active channel ID based on :env."
  [secrets]
  (let [env (get secrets :env :staging)]
    (get-in secrets [:discord (if (= env :prod) :prod-channel :staging-channel)])))

(defn- post-webhook!
  "POST JSON to Discord webhook URL. Returns nil on error (logs to stderr)."
  [webhook-url content]
  (try
    (http/post webhook-url
               {:headers {"Content-Type" "application/json"}
                :body (json/generate-string {:content content})})
    (catch Exception e
      (binding [*out* *err*]
        (println "discord: webhook POST failed:" (.getMessage e)))
      nil)))

(defn format-state
  "Format engine state as Discord message string."
  [{:keys [energy mood time-quality recommendation]}]
  (str "⚡ Energy: " (:value energy) " " (:emoji energy)
       "  |  ⏱ Time: " (format "%.1f" (double (or (:available-hours time-quality) 0)))
       "h " (:emoji time-quality)
       "  |  " (:emoji mood) " Mood: " (:value mood) " " (:status mood)
       "\n💡 推荐: " (:task-type recommendation)))

(defn send-alert!
  "Send alert to Discord webhook. Returns response or nil on error."
  [webhook-url alert]
  (post-webhook! webhook-url (:message alert)))

(defn handle-interaction
  "Handle /state slash command. Returns Discord interaction response map."
  [state dashboard-url]
  (let [msg (str (format-state state)
                 (when dashboard-url
                   (str "\n📊 Dashboard: " dashboard-url "/" (:date state))))]
    {:type 4
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
