(ns ems.discord
  "Discord integration via Bot API."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]))

(defn- post-message!
  "POST message to Discord channel via Bot API. Returns nil on error."
  [{:keys [bot-token channel-id]} content]
  (try
    (http/post (str "https://discord.com/api/v10/channels/" channel-id "/messages")
               {:headers {"Authorization" (str "Bot " bot-token)
                          "Content-Type"  "application/json"}
                :body (json/generate-string {:content content})})
    (catch Exception e
      (binding [*out* *err*]
        (println "discord:" (.getMessage e)))
      nil)))

(defn format-state
  [{:keys [energy mood time-quality recommendation]}]
  (str "⚡ Energy: " (:value energy) " " (:emoji energy)
       "  |  ⏱ Time: " (format "%.1f" (double (or (:available-hours time-quality) 0)))
       "h " (:emoji time-quality)
       "  |  " (:emoji mood) " Mood: " (:value mood) " " (:status mood)
       "\n💡 推荐: " (:task-type recommendation)))

(defn send-alert!
  "Send engine alert to Discord channel."
  [discord-cfg alert]
  (post-message! discord-cfg (:message alert)))

(defn handle-interaction
  "Handle /state slash command. Returns Discord interaction response map."
  [state dashboard-url]
  (let [msg (str (format-state state)
                 (when dashboard-url
                   (str "\n📊 Dashboard: " dashboard-url "/" (:date state))))]
    {:type 4 :data {:content msg}}))

(defn send-summary!
  "Send morning/evening summary to Discord channel."
  [discord-cfg state summary-type]
  (let [header (case summary-type
                 :morning "☀️ 晨间校准"
                 :evening "🌙 晚间对账"
                 "📊 状态总结")
        alerts-str (when (seq (:alerts state))
                     (str "\n⚠️ " (count (:alerts state)) " 条预警"))
        msg (str header "\n" (format-state state) alerts-str)]
    (post-message! discord-cfg msg)))
