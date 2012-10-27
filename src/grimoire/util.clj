(ns grimoire.util
  (:use [lamina core executor])
  (:require [clojure.tools.logging :as log]))

(defn has-keys?
  [m & ks] (every? #(contains? m %) ks))

(defn respond
  [ch msg]
  (send (agent nil) (fn [_] (enqueue-and-close ch msg))))

(defn build-response
  [result & response]
  {:response (or (first response) {}) :result result})

;; break up; so it's usable without multifunctions
(defmacro defupdate
  [multi-fn dispatch-value & body-fn]
  `(. ~(with-meta multi-fn  {:tag 'clojure.lang.MultiFn})
      addMethod
      ~dispatch-value
      (fn [request-body#]
        (fn [channel# state#]
          (try
            (let [result-map# (~@body-fn state# (:body request-body#))]
              (respond channel# (:response result-map#))
              (:result result-map#))
            (catch Exception e#
              (log/error "update failed with" (.getMessage e#))
              (when-not (closed? channel#)
                (respond channel# {"error" "fatal"})
                ;; (throw (Exception. "update failed; agent will fail"))
                state#)))))))