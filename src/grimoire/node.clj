(ns grimoire.node
  (:use lamina.stats
        [lamina core executor])
  (:require [clojure.tools.logging :as log]
            [aleph.tcp :as aleph]
            [gloss.core :as gloss]
            [grimoire.protocol :as protocol])
  (:import java.util.Arrays
           java.util.zip.ZipException))

(def ^:dynamic *server-close-fn* nil)

(defonce node-prefix "grim-")
(def local-node-id)
(def local-node-name)

(def inbound-channel (permanent-channel))
(def stats-channel (permanent-channel))

(def connections (atom {}))

(defn get-node-id [] local-node-id)
(defn get-node-name [] local-node-name)

(defn get-connections [] @connections)

(defn register-stats-channel
  [event-channel]
  (log/info "register-stats-channel" event-channel)
  (siphon (fork stats-channel) event-channel))

(defn publish-event
  [node-name message]
  (when-not (nil? (@connections node-name))
    (let [remote-channel (:channel (@connections node-name))]
      (when-not (nil? remote-channel)
        ;; TODO move encoding/decoding to separate namespace
        (enqueue remote-channel (protocol/encode message))))))

(defn ^:private decode-frame
  [msg-bytes]
  (let [msg-buffer (gloss.io/contiguous msg-bytes)
        msg-byte-array (byte-array (.remaining msg-buffer))]
    (.get msg-buffer msg-byte-array)
    (protocol/decode msg-byte-array)))

(defn ^:private client-success-callback
  [node-name client-channel]
  (log/info "on-success-callback" node-name client-channel)
  ;; TODO make this (:status) less explicit and actually get state from channel
  (swap! connections assoc node-name {:status :connected :channel client-channel})
  (log/info "client-success-callback" (@connections node-name))
  ;; FIXME use pipeline and handle errors
  ;; synchronously wait for reply and move forward from there on
  (let [msg {:type :node-connection-start :content {:node-name node-name}}]
    (enqueue client-channel (protocol/encode msg)))
  (siphon (map* #(decode-frame %) client-channel) inbound-channel))

(defn ^:private client-error-callback
  [node-name error]
  (log/info "on-error-callback" node-name error))

(defn start-client
  [host port node-name]
  (let [options {:host host :port port :frame (gloss/finite-block :int32)}
        client-result-channel (aleph/tcp-client options)]
    (on-realized client-result-channel
                        (partial client-success-callback node-name)
                        (partial client-error-callback node-name))))

;; TODO actually implement reconnection logic
;; don't forget back-off
(defn ^:private reconnect
  []
  (log/info "reconnect-client"))

(defn disconnect
  [node-name]
  (log/info "disconnect")
  (let [remote-connection (@connections node-name)]
    (try
      (close (:channel remote-connection))
      (catch Exception exception
        (log/error "disconnect" (.getMessage exception))))
    (swap! connections dissoc node-name)))

(defn connect
  [node-name node-data]
  (log/info "connect" node-name node-data)
  (when (nil? (@connections node-name))
    (swap! connections assoc-in [node-name :status] :connecting)
    (start-client (:host node-data) (:port node-data) node-name)))

(defn server-handler
  [connection-channel client-info]
  (log/info "server-handler" client-info (:address client-info))
  ;; TODO map bare rate messages to more descriptive hashmaps
  ;; (siphon (rate connection-channel) stats-channel)
  ;; FIXME very brittle; fix
  (run-pipeline
   connection-channel
   read-channel
   (fn [message]
     (let [node-connection-start (decode-frame message)
           remote-node-name (get-in node-connection-start [:event
                                                           :node-connection-start
                                                           :node-name])]
       (swap! connections assoc remote-node-name
              {:status :connected :channel connection-channel})
       (siphon (map* #(decode-frame %) connection-channel) inbound-channel)))))

(defn ^:private create-distributor
  []
  (distributor {:facet :user-id
                :initializer (fn [facet facet-channel]
                               (log/info "distributor" facet facet-channel)
                               ;; here's hoping to named-channel's idempotency
                               (let [user-channel (named-channel (keyword (str facet)) (fn [_]))]
                                 (log/info "distributor" user-channel)
                                 (close-on-idle 5000 facet-channel)
                                 (close-on-idle 5000 user-channel)
                                 (ground user-channel)
                                 (siphon facet-channel user-channel)))}))

(defn start-server
  [^Integer port ^Integer node-id]
  (log/info "start-server" port node-id)
  (alter-var-root #'local-node-id (fn [_] node-id))
  (alter-var-root #'local-node-name (fn [_] (str "grim-" node-id)))
  ;; (receive-all inbound-channel #(log/info "inbound-channel received" %))
  (siphon inbound-channel (create-distributor))
  ;; If a tree falls in a forest and no one is around to hear it, does it make a sound?
  (ground stats-channel)
  (let [options {:port port :frame (gloss/finite-block :int32)}
        server (aleph/start-tcp-server server-handler options)]
    (alter-var-root #'*server-close-fn* (fn [_] server))))

(defn stop-server
  []
  (*server-close-fn*)
  (log/info "stop-server"))
