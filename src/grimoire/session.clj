(ns grimoire.session
  (:use [lamina core executor])
  (:import (org.jboss.netty.util HashedWheelTimer Timeout TimerTask))
  (:import (java.util.concurrent TimeUnit))
  (:require [grimoire
             [config :as config]
             [registry :as registry]
             [node :as node]
             [storage :as storage]
             [user :as user]
             [game :as game]]
            [clojure.tools.logging :as log]))

(defonce sessions (atom {}))

(defonce ^:private ^HashedWheelTimer hashed-wheel-timer (HashedWheelTimer.))

(defn get-expire-time
  []
  (+ (System/currentTimeMillis) (:session-timeout config/system)))

(defn sessions-running [] (count @sessions))

(defn register-event-channel
  [user-id event-channel]
  (let [stored-event-channel (:event-channel (@sessions user-id))]
    (when (or (nil? stored-event-channel)
              (closed? stored-event-channel))
      (swap! sessions assoc-in [user-id :event-channel] event-channel)))
  nil)

(defn ^:private agent-error-handler
  [session exception]
  (log/info "agent-error-callback" session exception (agent-error session))
  (when-not (nil? (agent-error session))
    (restart-agent session @session)))

(defn ^:private create-watch-fn
  [callback]
  (partial (fn [_callback _key _reference old-state new-state]
             (log/info "watch-fn" _key _reference old-state new-state)
             (_callback))
           callback))

(defn update
  [user-id response-channel action verb request-body]
  (if-let [session (:session (@sessions user-id))]
    (let [update-fn (game/get-update-function {:action (keyword action)
                                               :verb (keyword verb)
                                               :body request-body})]
      (send session (partial (fn [_response-channel _session]
                               (update-fn _response-channel _session))
                             ;; response-channel)))
                             (:event-channel (@sessions user-id)))))
    (throw (Exception. (format "session_not_running, args=[%s]" user-id))))
    nil)

(defn ^:private cancel-timeout
  [user-id timeout-type]
  ;; (log/info "cancel-timeout" user-id timeout-type)
  (let [session (@sessions user-id)]
    (.cancel ^Timeout (timeout-type session))))

;; FIXME write try/catch macro specifically for this
(defn ^:private clean-timeouts
  [session]
  (let [^Timeout session-timeout (:session-timeout session)
        ^Timeout save-timeout (:save-timeout session)]
    (try
      (.cancel session-timeout)
      (catch Exception e
        (log/error "canceling session-timeout failed" (.getMessage e))))
    (try
      (.cancel save-timeout)
      (catch Exception e
        (log/error "canceling save-timeout failed" (.getMessage e))))))

(defn ^:private save-to-storage
  [user-id session]
  ;; (log/info "save-to-storage" user-id session)
  (send-off session
            (fn [user]
              (let [user-json (user/to-json user)]
                (storage/put-data user-id user-json))
              (assoc user :saved-at (System/currentTimeMillis)))))

(defn ^:private safe-close
  [ch]
  (when-not (nil? ch)
    (try
      (close ch)
      (catch Exception e))))

(defn ^:private clean
  [user-id]
  (let [session (@sessions user-id)]
    (safe-close (:remote-channel session))
    (safe-close (:event-channel session))
    (clean-timeouts session)
    (swap! sessions dissoc user-id)
    (registry/deregister user-id))
  nil)

(defn ^:private stop
  [user-id]
  ;; FIXME stop timeouts
  (save-to-storage user-id (:session (@sessions user-id)))
  ;; FIXME should wait synchronously for successful save, yet not
  ;; block thread
  (clean user-id)
  nil)

(defn ^:private get-timeout
  [user-id delay timeout-fn]
  (let [timer-task (reify org.jboss.netty.util.TimerTask
                     (^void run [this ^Timeout timeout]
                       (timeout-fn timeout user-id)))]
    (.newTimeout hashed-wheel-timer timer-task delay (TimeUnit/MILLISECONDS))))

(defn renew-timeout
  [user-id delay timeout-type timeout-fn]
  ;; (log/info "renew-timeout" user-id timeout-type timeout-fn)
  (cancel-timeout user-id timeout-type)
  (let [timeout (get-timeout user-id delay timeout-fn)]
    (swap! sessions assoc-in [user-id timeout-type] timeout)))

(defn ^:private session-timeout-handler
  [^:Timeout timeout user-id]
  ;; (log/info "session-timeout-handler" user-id timeout)
  (stop user-id))

(defn ^:private save-timeout-handler
  [^:Timeout timeout user-id]
  ;; (log/info "save-timeout-handler" user-id timeout)
  (let [session (:session (@sessions user-id))
        save-interval (config/system :save-interval)]
    (save-to-storage user-id session)
    ;; renew timeout on response from amazon
    (renew-timeout user-id save-interval :save-timeout save-timeout-handler)))

;; FIXME implement remote message handling example
(defn handle-remote-message
  [msg]
  (log/info "handle-remote-message" msg))

(defn ^:private start
  [user-id session]
  (let [remote-channel (named-channel (keyword (str user-id)) (fn [_]))
        event-channel (permanent-channel)
        session-timeout (get-timeout user-id (config/system :session-timeout)
                                     session-timeout-handler)
        save-timeout (get-timeout user-id (config/system :save-interval)
                                  save-timeout-handler)
        data {:session session :event-channel event-channel
              :remote-channel remote-channel :session-timeout session-timeout
              :save-timeout save-timeout}]
    (ground event-channel)
    (set-error-handler! session agent-error-handler)
    ;; (add-watch session nil (create-watch-fn #(log/info "watch callback")))
    (swap! sessions assoc user-id data)
    (receive-all remote-channel handle-remote-message))
  nil)

(defn ^:private load-user
  [user-id]
  (let [result (storage/get-data user-id)]
    (if (nil? result)
      (user/new user-id)
      (user/from-json result))))

(defn setup
  [user-id]
  (registry/register user-id (get-expire-time) (node/get-node-name))
  (try
    (let [session (agent (load-user user-id))]
      (start user-id session)
      (user/to-json @session))
    (catch Exception e
      (log/error (.getMessage e) user-id)
      (clean user-id)
      (throw (Exception. (format "session_start_failed, args=[%s]" user-id))))))

(defn run-bench
  []
  (let [runs 1000
        global-start (* (node/get-node-id) runs)]
    (Thread/sleep 5000)
    (dotimes [j runs]
      (let [batch-size 5000
            start (+ global-start (* j batch-size))
            end (+ start batch-size)
            the-range (range start end)
            t (time (doseq [i the-range] (setup i)))]
        (log/info t))
      (log/info (sessions-running))
      (Thread/sleep 1000))))
