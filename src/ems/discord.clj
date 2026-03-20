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

;; --- Ed25519 signature verification ---

(def ^:private ed25519-x509-prefix
  "DER prefix for Ed25519 public key in X.509 SubjectPublicKeyInfo format."
  (byte-array [0x30 0x2a 0x30 0x05 0x06 0x03 0x2b 0x65 0x70 0x03 0x21 0x00]))

(defn- hex->bytes [^String hex]
  (let [len (/ (count hex) 2)]
    (byte-array (for [i (range len)]
                  (unchecked-byte
                   (Integer/parseInt (subs hex (* 2 i) (+ (* 2 i) 2)) 16))))))

(defn- make-ed25519-pubkey
  "Build Ed25519 PublicKey from 32-byte hex-encoded Discord public key."
  [hex-key]
  (let [raw   (hex->bytes hex-key)
        der   (byte-array (concat (seq ed25519-x509-prefix) (seq raw)))
        spec  (java.security.spec.X509EncodedKeySpec. der)
        kf    (java.security.KeyFactory/getInstance "Ed25519")]
    (.generatePublic kf spec)))

(defn verify-signature
  "Verify Discord Ed25519 request signature.
   public-key-hex: hex string from Discord app settings.
   Returns true if valid."
  [req public-key-hex]
  (try
    (let [sig-hex   (get-in req [:headers "x-signature-ed25519"])
          timestamp (get-in req [:headers "x-signature-timestamp"])
          body      (if (string? (:body req)) (:body req) (slurp (:body req)))]
      (when (and sig-hex timestamp body)
        (let [message (.getBytes (str timestamp body) "UTF-8")
              sig-bytes (hex->bytes sig-hex)
              pub-key (make-ed25519-pubkey public-key-hex)
              verifier (java.security.Signature/getInstance "Ed25519")]
          (.initVerify verifier pub-key)
          (.update verifier message)
          (.verify verifier sig-bytes))))
    (catch Exception _ false)))

;; --- Interaction endpoint (slash commands) ---

(defn handle-interaction
  "Ring handler for POST /discord/interactions.
   public-key-hex: Discord app public key for signature verification.
   state-atom: atom holding current engine state.
   dashboard-url: base URL for Vercel dashboard (or nil).
   Note: caller must ensure (:body req) is a string (read once, pass through)."
  [req public-key-hex state-atom dashboard-url]
  (if-not (verify-signature req public-key-hex)
    {:status 401
     :headers {"Content-Type" "application/json"}
     :body (json/generate-string {:error "invalid signature"})}
    (let [body (json/parse-string (if (string? (:body req)) (:body req) (slurp (:body req))) true)
          typ  (:type body)]
      (cond
        (= typ 1)
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:type 1})}

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
         :body (json/generate-string {:error "unknown interaction type"})}))))
