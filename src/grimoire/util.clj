(ns grimoire.util
  (:use [lamina core executor]))

(defn has-keys?
  [m & ks] (every? #(contains? m %) ks))

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
              (enqueue-and-close channel# (:response result-map#))
              (:result result-map#))
            (catch Exception e#
              (log/error "update failed with" (.getMessage e#))
              (when-not (closed? channel#)
                (enqueue-and-close channel# {"error" "fatal"})
                ;; (throw (Exception. "update failed; agent will fail"))
                state#)))))))
