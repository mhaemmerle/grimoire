(ns grimoire.storage
  (:use [slingshot.slingshot :only [try+ throw+]]
        [cheshire.core :only [generate-string]])
  (:require [aws.sdk.s3 :as s3]
            [grimoire
             [config :as config]]
            [clojure.tools.logging :as log])
  (:import com.amazonaws.services.s3.model.AmazonS3Exception))

(def credentials {:access-key (config/aws :access-key),
                  :secret-key (config/aws :secret-key)})

(def bucket (config/aws :bucket))

(defn ^:private id-to-storage-key
  [user-id]
  (str user-id))

(defn ^:private storage-key-to-id
  [storage-key]
  (Integer/parseInt storage-key))

;; TODO implement
(defn ^:private clean-bucket
  [])

(defn get-data
  [^Integer user-id]
  (log/info "get-data" user-id)
  ;; (try
  ;;   (let [s3-object (s3/get-object credentials bucket (id-to-storage-key user-id))]
  ;;     (slurp (:content s3-object)))
  ;;   (catch AmazonS3Exception exception
  ;;     (when-not (= (.getErrorCode exception) "NoSuchKey")
  ;;       (log/error (.getMessage exception)))))
  nil)

(defn put-data
  [^Integer user-id ^String json]
  (log/info "put-data" user-id)
  (let [result (s3/put-object credentials bucket (id-to-storage-key user-id) json)]
    (log/info "put-data" result))
  nil)
