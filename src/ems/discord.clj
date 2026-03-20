(ns ems.discord
  "Discord integration via Bot API."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]))

;; --- Bot API messaging ---

(defn- post-message!
  "POST message to Discord channel via Bot API. Returns nil on error.
   Accepts :channel-id or :channel key in config."
  [{:keys [bot-token channel-id channel]} content]
  (let [ch (or channel-id channel)]
    (if-not ch
      (do (binding [*out* *err*] (println "discord: no channel-id in config")) nil)
      (try
        (http/post (str "https://discord.com/api/v10/channels/" ch "/messages")
                   {:headers {"Authorization" (str "Bot " bot-token)
                              "Content-Type"  "application/json"}
                    :body (json/generate-string {:content content})})
        (catch Exception e
          (binding [*out* *err*]
            (println "discord:" (.getMessage e)))
          nil)))))

(defn format-state
  [{:keys [energy mood time-quality recommendation]}]
  (str "⚡ Energy: " (:value energy) " " (:emoji energy)
       "  |  ⏱ Time: " (format "%.1f" (double (or (:available-hours time-quality) 0)))
       "h " (:emoji time-quality)
       "  |  " (:emoji mood) " Mood: " (:value mood) " " (:status mood)
       "\n💡 推荐: " (:task-type recommendation)))

(defn send-alert!
  [discord-cfg alert]
  (post-message! discord-cfg (:message alert)))

(defn send-summary!
  [discord-cfg state summary-type]
  (let [header (case summary-type
                 :morning "☀️ 晨间校准"
                 :evening "🌙 晚间对账"
                 "📊 状态总结")
        alerts-str (when (seq (:alerts state))
                     (str "\n⚠️ " (count (:alerts state)) " 条预警"))
        msg (str header "\n" (format-state state) alerts-str)]
    (post-message! discord-cfg msg)))

;; --- Interaction endpoint (slash commands) ---

(defn verify-signature
  "Verify Discord Ed25519 signature. TODO: implement real verification."
  [_req _public-key]
  true)

(defn handle-interaction
  "Ring handler for POST /discord/interactions.
   state-atom: atom holding current engine state.
   dashboard-url: base URL for Vercel dashboard (or nil)."
  [req state-atom dashboard-url]
  (let [body (json/parse-string (slurp (:body req)) true)
        typ  (:type body)]
    (cond
      ;; PING — Discord verification handshake
      (= typ 1)
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:type 1})}

      ;; APPLICATION_COMMAND — slash command
      (= typ 2)
      (let [state @state-atom
            msg   (str (format-state state)
                       (when dashboard-url
                         (str "\n📊 Dashboard: " dashboard-url "/" (:date state))))]
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:type 4 :data {:content msg}})})

      :else
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:error "unknown interaction type"})})))
