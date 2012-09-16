(ns grimoire.protocol
  (:refer clojure.string :only [upper-case])
  (:require [clojure.tools.logging :as log])
  (:import Nodemessages$Event
           Nodemessages$Foo
           Nodemessages$Bar
           Nodemessages$NodeConnectionStart)
  (:use protobuf.core))

(def Event (protodef Nodemessages$Event))
(def Foo (protodef Nodemessages$Foo))
(def Bar (protodef Nodemessages$Bar))
(def NodeConnectionStart (protodef Nodemessages$NodeConnectionStart))

(def protobuf-content-types {:foo Foo
                             :bar Bar
                             :node-connection-start NodeConnectionStart})

(defn ^:private get-protobuf-type
  [type]
  (if-let [the-type (type protobuf-content-types)]
    the-type
    (throw (Exception. (format "protobuf_type_does_not_exist, args=[%s]" type)))))

(defn decode
  [msg-bytes]
  (let [event (protobuf-load Event msg-bytes)
        content ((:type event) event)]
    {:user-id (:receiver content) :event event}))

(defn encode [message]
  (let [type (:type message)
        content (flatten (seq (:content message)))
        protobuf-content-type (get-protobuf-type type)
        protobuf-content (apply protobuf protobuf-content-type content)
        event-type (upper-case (name type))
        protobuf-msg (protobuf Event :type event-type type protobuf-content)]
    (protobuf-dump protobuf-msg)))