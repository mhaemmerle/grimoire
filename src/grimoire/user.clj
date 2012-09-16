(ns grimoire.user
  (:use [slingshot.slingshot :only [try+ throw+]]
        [cheshire.core :only [generate-string parse-string]])
  (:require [grimoire
             [config :as config]
             [map :as map-ns]]
            [clojure.tools.logging :as log]))

(defrecord User [^Integer id
                 name
                 points
                 balance
                 ;; inventory
                 map
                 updated-at
                 created-at
                 saved-at
                 version])

(defn new
  [user-id & name]
  (let [current-time (System/currentTimeMillis)
        current-version (:session-version config/system)
        default-map (map-ns/generate-empty-map 100 100)]
    (User. user-id
           (or name "John Doe")
           0
           0
           default-map
           current-time
           current-time
           0
           current-version)))

(defn from-struct
  [{:keys [id name points balance map
           updated-at created-at saved-at version], :or {saved-at 0}}]
  (log/info "from-struct" map)
  (User. id name points balance {} updated-at created-at saved-at version))

(defn from-json
  [json]
  (let [struct (parse-string json true)]
    (from-struct struct)))

(defn to-json
  [user]
  (generate-string user))