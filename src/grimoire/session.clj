(ns grimoire.session
  (:use [lamina core executor])
  (:import (org.jboss.netty.util HashedWheelTimer Timeout TimerTask)
           (java.util.concurrent TimeUnit))
  (:require [grimoire
             [config :as config]
             [registry :as registry]
             [node :as node]
             [storage :as storage]
             [user :as user]
             [game :as game]
             [session-store :as store]
             [util :as util]]
            [clojure.tools.logging :as log]))

(defonce ^:private ^HashedWheelTimer hashed-wheel-timer (HashedWheelTimer.))

(defn get-expire-time
  []
  (+ (System/currentTimeMillis)
     (:session-timeout config/system)))

(defn- receive-in-order-with-pipeline
  [ch]
  (consume ch
           :channel nil
           :initial-value nil
           :reduce (fn [_ event]
                     (let [p (:pipeline event)]
                       (p ch (dissoc event :pipeline))))))

(defn- generic-event-stage
  [event]
  (let [{:keys [user-id function args]} event
        session (store/get user-id)]
    (util/log-with-thread "generic-event-stage - start sleep")
    (Thread/sleep 5000)
    (util/log-with-thread "generic-event-stage - done sleeping")
    (apply function session args)))

(defn- get-request-channel
  [user-id]
  (let [session (store/get user-id)]
    (:request-channel @session)))

(defn- enqueue-event
  [user-id event]
  (enqueue (get-request-channel user-id) event))

(defn- close-request-channel
  [user-id]
  (let [session (store/get user-id)
        request-channel (:request-channel @session)]
    (close request-channel)))

(defn get-event-channel
  [user-id]
  (let [session (store/get user-id)
        event-channel (:event-channel @session)]
    (if (or (nil? event-channel)
            (closed? event-channel))
      (let [new-event-channel (channel)]
        (swap! session assoc :event-channel new-event-channel)
        new-event-channel)
      event-channel)))

(defn- wrap-state-update-fn
  [update-fn]
  (fn [session & args]
    (let [result-map (apply update-fn (:state @session) args)]
      (swap! session assoc :state (:state result-map))
      (:response result-map))))

(defn- get-game-event-pipeline
  [& {:keys [async?] :or {:async? false} :as options}]
  (let [event-stage (if async?
                      #(task (generic-event-stage %))
                      generic-event-stage)]
    (fn [request-channel event]
      (run-pipeline event
                    {:error-handler (fn [e]
                                      (log/error "game_event_pipeline_error" e event)
                                      (error request-channel e)
                                      (enqueue-and-close (:response-channel event)
                                                         {:error (.getMessage e)}))}
                    event-stage
                    #(enqueue-and-close (:response-channel event) %)))))

(defn enqueue-game-event
  [user-id game-event & {:keys [response-channel] :as options}]
  (if-let [session (store/get user-id)]
    (let [request-channel (:request-channel @session)]
      (if (closed? request-channel)
        (enqueue-and-close response-channel {:error "request_channel_is_closed"})
        (let [f (wrap-state-update-fn (game/get-update-function game-event))
              event {:user-id user-id
                     :function f
                     :pipeline (get-game-event-pipeline)
                     :response-channel response-channel
                     :close? true
                     :reload-on-error? true
                     :args nil}]
          (enqueue request-channel event))))
    (throw (Exception. (format "session_not_running, args=[%s]" user-id)))))

(defn- clean-timeouts
  [session]
  (doseq [timeout-type [:session-timeout :save-timeout]]
    (try
      (.cancel ^Timeout (timeout-type @session))
      (catch Exception e
        (log/error "clean-timeouts" (.getMessage e)))
      (finally
       (swap! session assoc timeout-type nil)))))

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
      (catch Exception e
        (log/error "safe-close-channel" (.getMessage e))))))

(defn- clean
  [user-id]
  (log/info "clean" user-id)
  (let [session (store/get user-id)
        channel-keys [:remote-channel :event-channel]]
    (doseq [channel-key channel-keys]
      (safe-close-channel (channel-key @session)))
    (clean-timeouts session)
    (store/remove user-id)
    (registry/deregister user-id))
  nil)

(defn- should-save?
  [state]
  (let [answer (> (:updated-at state) (:saved-at state))]
    (log/info "should-save?" answer)
    answer))

(defn- stop
  [user-id]
  (log/info "stop" user-id)
  (let [session (store/get user-id)
        state (:state @session)
        request-channel (:request-channel @session)]
    (close request-channel)
    (clean-timeouts session)
    (when (should-save? state)
      (try
        (save-to-storage user-id (assoc state :saved-at (System/currentTimeMillis)))
        (catch Exception e
          (log/error "stop" (.getMessage e) user-id))))
    (clean user-id)
    nil))

(defn- get-timeout
  [delay timeout-fn]
  (let [timer-task (reify org.jboss.netty.util.TimerTask
                     (^void run [this ^Timeout timeout]
                       (timeout-fn timeout)))]
    (.newTimeout hashed-wheel-timer timer-task delay (TimeUnit/MILLISECONDS))))

(defn- renew-timeout
  [user-id delay timeout-type timeout-fn]
  (log/info "renew-timeout" user-id timeout-type timeout-fn)
  (let [session (store/get user-id)]
    (.cancel ^Timeout (timeout-type @session))
    (swap! session assoc timeout-type (get-timeout delay
                                                   (partial timeout-fn user-id)))))

(defn- session-timeout-handler
  [user-id ^:Timeout timeout]
  (log/info "session-timeout-handler" user-id timeout)
  (future (stop user-id)))

(declare save-timeout-handler)

(defn- periodic-save
  [session user-id]
  (log/info "periodic-save" user-id)
  (let [new-state (assoc (:state @session) :saved-at (System/currentTimeMillis))
        save-interval (config/system :save-interval)]
    (save-to-storage user-id new-state)
    (swap! session assoc :state new-state)))

(defn- pipeline-with-finally
  [finally-fn & {:keys [async?] :or {:async? false} :as options}]
  (let [event-stage (if async?
                      #(task (generic-event-stage %))
                      generic-event-stage)
        finally-stage (fn [_]
                        (finally-fn))]
    (fn [request-channel event]
      (run-pipeline event
                    {:error-handler (fn [e]
                                      (log/error "pipeline_error" e event)
                                      (finally-fn))}
                    event-stage
                    finally-stage))))

(defn- save-timeout-handler
  [user-id ^:Timeout timeout]
  (log/info "save-timeout-handler")
  (let [state (:state @(store/get user-id))
        save-interval (config/system :save-interval)
        renew-fn #(renew-timeout user-id save-interval :save-timeout
                                 save-timeout-handler)]
    (if (should-save? state)
      (let [event {:user-id user-id
                   :function periodic-save
                   :pipeline (pipeline-with-finally renew-fn :async? true)
                   :response-channel nil
                   :reload-on-error false
                   :args '(user-id)}]
        (enqueue-event user-id event))
      (renew-fn))))

;; not yet implemented
(defn handle-remote-message
  [msg]
  (log/info "handle-remote-message" msg))

(defn- start
  [user-id state]
  (log/info "start")
  (let [remote-channel (named-channel (keyword (str user-id)) nil)
        request-channel (channel)
        session-timeout (get-timeout (config/system :session-timeout)
                                     (partial session-timeout-handler user-id))
        save-timeout (get-timeout (config/system :save-interval)
                                  (partial save-timeout-handler user-id))
        session (atom {:state state
                       :request-channel request-channel
                       :event-channel nil
                       :remote-channel remote-channel
                       :session-timeout session-timeout
                       :save-timeout save-timeout})]
    (receive-in-order-with-pipeline request-channel)
    (store/put user-id session)
    (receive-all remote-channel handle-remote-message))
  nil)

(defn- try-restart
  [user-id]
  (log/info "try-testart")
  (let [session (store/get user-id)
        request-channel (:request-channel @session)]
    (if (closed? request-channel)
      (let [new-request-channel (channel)]
        (receive-in-order-with-pipeline new-request-channel)
        (swap! session assoc :request-channel request-channel)
        (user/to-json (:state @session)))
      (throw (Exception. (format "session_still_running, args=[%s]" user-id))))))

(defn- load-user
  [user-id]
  (log/info "load-user")
  (let [result (storage/get-data user-id)]
    (if (nil? result)
      (user/new user-id)
      (user/from-json result))))

(defn- load-and-start
  [user-id]
  (log/info "load-and-start")
  (registry/register user-id (get-expire-time) (node/get-node-name))
  (try
    (let [state (load-user user-id)]
      (start user-id state)
      (user/to-json state))
    (catch Exception e
      (log/error "setup_failed" (.getMessage e) user-id)
      (clean user-id)
      (throw (Exception. (format "session_start_failed, args=[%s]" user-id))))))

(defn setup
  [user-id]
  (log/info "setup")
  (if (and (store/exists? user-id)
           (registry/registered? user-id)
           (registry/local? user-id))
    (try-restart user-id)
    (load-and-start user-id)))

(defn run-bench
  []
  (log/info "run-bench" (node/get-node-id))
  ;; (let [runs 1000
  (let [runs 10
        ;; global-start (* (node/get-node-id) runs)]
        global-start (* 0 runs)]
    ;; (Thread/sleep 5000)
    (dotimes [j runs]
      (let [batch-size 100
            start (+ global-start (* j batch-size))
            end (+ start batch-size)
            the-range (range start end)]
        (time (doseq [i the-range] (setup i))))
      (log/info "num-sessions" (store/num-sessions))
      ;; (Thread/sleep 1000)
      )))
