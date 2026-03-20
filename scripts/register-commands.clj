#!/usr/bin/env bb
;; Register /state slash command with Discord (guild-scoped).
;; Usage: bb scripts/register-commands.clj

(require '[babashka.http-client :as http]
         '[cheshire.core :as json])

(let [secrets   (clojure.edn/read-string (slurp "secrets.edn"))
      bot-token (get-in secrets [:discord :bot-token])
      guild-id  (get-in secrets [:discord :guild-id])
      ;; App ID is encoded in the first segment of the bot token
      app-id    (String. (.decode (java.util.Base64/getDecoder)
                                  (first (.split bot-token "\\."))))
      url       (str "https://discord.com/api/v10/applications/" app-id
                     "/guilds/" guild-id "/commands")
      commands  [{:name "state"
                  :description "Show current energy/mood/time status"
                  :type 1}]
      resp      (http/put url
                          {:headers {"Authorization" (str "Bot " bot-token)
                                     "Content-Type"  "application/json"}
                           :body (json/generate-string commands)})]
  (println (str "HTTP " (:status resp)))
  (println (:body resp)))
