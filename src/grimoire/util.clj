(ns grimoire.util
  (:use [lamina core executor])
  (:require [clojure.tools.logging :as log]))

(defn current-time
  []
  (System/currentTimeMillis))

(defmacro log-with-thread
  [& msg]
  `(log/info ~@msg (Thread/currentThread)))

(defn has-keys?
  [m & ks] (every? #(contains? m %) ks))

(defn touch
  [state]
  (assoc state :updated-at (current-time)))

(defn to-result-map
  ([state]
     (to-result-map state {}))
  ([state response]
     {:state state :response response}))

(defmacro defupdate
  [multi-fn dispatch-value & body-fn]
  `(. ~(with-meta multi-fn  {:tag 'clojure.lang.MultiFn})
      addMethod
      ~dispatch-value
      (fn [event#]
        (fn [state#]
          (let [result-map# (~@body-fn state# (:body event#))]
            (assoc-in result-map# [:state :updated-at] (current-time)))))))
