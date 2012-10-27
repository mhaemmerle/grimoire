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
             [game :as game]
             [session-store :as store]]
            [clojure.tools.logging :as log]))

(defonce ^:private ^HashedWheelTimer hashed-wheel-timer (HashedWheelTimer.))

(defn get-expire-time
  []
  (+ (System/currentTimeMillis) (:session-timeout config/system)))

(defn register-event-channel
  [user-id event-channel]
  (let [session (store/get user-id)
        stored-event-channel (:event-channel @session)]
    (when (or (nil? stored-event-channel)
              (closed? stored-event-channel))
      (swap! session assoc :event-channel event-channel)))
  nil)

(defn- touch
  [state]
  (assoc state :updated-at (System/currentTimeMillis)))

;; maybe to tightly coupled with game actions '(game/get-update-function ...'
;; maybe (if (game-action? ...
(defn- execute-update
  [message]
  (log/info "exec")
  (let [{:keys [user-id chunk-channel]} message
        session (store/get user-id)
        state (:state @session)
        update-fn (partial (game/get-update-function message) chunk-channel)
        new-state (update-fn state)]
    ;; touch could be soulved via :post
    (swap! session assoc :state (touch new-state))))

(defn update
  [user-id chunk-channel message]
  (let [session (store/get user-id)
        request-queue (:request-queue @session)
        new-message (assoc message :user-id user-id :chunk-channel chunk-channel)]
    (enqueue request-queue new-message)
    nil))

(defn- cancel-timeout
  [user-id timeout-type]
  (log/info "cancel-timeout" user-id timeout-type)
  (let [session (store/get user-id)]
    (.cancel ^Timeout (timeout-type @session))))

;; FIXME write try/catch macro specifically for this
(defn- clean-timeouts
  [session]
  (let [^Timeout session-timeout (:session-timeout session)
        ^Timeout save-timeout (:save-timeout session)]
    (log/info "clean-timeouts" session-timeout save-timeout)
    (try
      (.cancel session-timeout)
      (catch Exception e
        (log/error "canceling session-timeout failed" (.getMessage e))))
    (try
      (.cancel save-timeout)
      (catch Exception e
        (log/error "canceling save-timeout failed" (.getMessage e))))))

(defn- save-to-storage
  [user-id state]
  (log/info "save-to-storage" user-id)
  (let [user-json (user/to-json state)]
    (storage/put-data user-id user-json)))

(defn- safe-close-channel
  [ch]
  (when-not (nil? ch)
    (try
      (close ch)
      (catch Exception e))))

(defn- clean
  [user-id]
  (log/info "clean" user-id)
  (let [session (store/get user-id)]
    (safe-close-channel (:remote-channel @session))
    (safe-close-channel (:event-channel @session))
    (clean-timeouts @session)
    (store/remove user-id)
    (registry/deregister user-id))
  nil)

(defn- should-save?
  [state]
  (let [answer (> (:updated-at state) (:saved-at state))]
    (log/info "should-save?" answer)
    answer))

;; TODO make sure request queue is empty
;; long-running saves and session timeouts interfere with each other
;; they NEED to be queued
(defn- stop
  [user-id]
  (log/info "stop" user-id)
  (let [session (store/get user-id)
        state (:state @session)
        request-queue (:request-queue @session)]
    (clean-timeouts @session)
    (if (should-save? state)
      (try
        (do
          (save-to-storage user-id state)
          (clean user-id))
        (catch Exception e
          (log/error (.getMessage e) user-id)))
      (clean user-id))
    nil))

(defn- get-timeout
  [user-id delay timeout-fn]
  (let [timer-task (reify org.jboss.netty.util.TimerTask
                     (^void run [this ^Timeout timeout]
                       (timeout-fn timeout user-id)))]
    (.newTimeout hashed-wheel-timer timer-task delay (TimeUnit/MILLISECONDS))))

(defn- renew-timeout
  [user-id delay timeout-type timeout-fn]
  (log/info "renew-timeout" user-id timeout-type timeout-fn)
  (cancel-timeout user-id timeout-type)
  (let [session (store/get user-id)
        timeout (get-timeout user-id delay timeout-fn)]
    (swap! session assoc timeout-type timeout)))

(defn- session-timeout-handler
  [^:Timeout timeout user-id]
  (log/info "session-timeout-handler" user-id timeout)
  (future
    (stop user-id)))

(declare save-timeout-handler)

(defn maybe-save
  [user-id]
  (let [session (store/get user-id)
        state (:state @session)
        save-interval (config/system :save-interval)]
    ;; FIXME must enqueue auto-/final save in normal request-queue
    (when (should-save? state)
      (try
        (do
          (save-to-storage user-id state)
          (swap! session assoc-in [:state :saved-at] (System/currentTimeMillis)))
        (catch Exception e
          (log/error (.getMessage e) user-id))
        (finally
         (renew-timeout user-id save-interval :save-timeout save-timeout-handler))))
    nil))

(defn- save-timeout-handler
  [^:Timeout timeout user-id]
  (log/info "save-timeout-handler" user-id timeout)
  (future
    (maybe-save user-id)))

;; FIXME implement remote message handling example
(defn handle-remote-message
  [msg]
  (log/info "handle-remote-message" msg))

(defn- start
  [user-id state]
  (let [remote-channel (named-channel (keyword (str user-id)) (fn [_]))
        request-queue (channel)
        session-timeout (get-timeout user-id
                                     (config/system :session-timeout)
                                     session-timeout-handler)
        save-timeout (get-timeout user-id (config/system :save-interval)
                                  save-timeout-handler)
        session (atom {:state state
                       :request-queue request-queue
                       :event-channel nil
                       :remote-channel remote-channel
                       :session-timeout session-timeout
                       :save-timeout save-timeout})]
    (receive-in-order request-queue execute-update)
    (store/put user-id session)
    (receive-all remote-channel handle-remote-message))
  nil)

(defn- load-user
  [user-id]
  (let [result (storage/get-data user-id)]
    (if (nil? result)
      (user/new user-id)
      (user/from-json result))))

(defn setup
  [user-id]
  (registry/register user-id (get-expire-time) (node/get-node-name))
  (try
    (let [state (load-user user-id)]
      (start user-id state)
      (user/to-json state))
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
      (log/info (store/num-sessions))
      (Thread/sleep 1000))))
