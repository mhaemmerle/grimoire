(ns grimoire.user
  (:use [slingshot.slingshot :only [try+ throw+]]
        [cheshire.core :only [generate-string parse-string]])
  (:require [grimoire
             [config :as config]
             [map :as game-map]]
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
        default-map (game-map/empty-map 5 5)]
    {:id user-id
     :name (or name "John Doe")
     :points 0
     :balance 0
     :map default-map
     :updated-at current-time
     :created-at current-time
     :saved-at 0
     :version current-version}))

(defn from-struct
  [{:keys [id name points balance map
           updated-at created-at saved-at version], :or {saved-at 0}}]
  ;; (log/info "from-struct" map)
  ;; (User. id name points balance {} updated-at created-at saved-at
                                        ;version)
  (apply hash-map
         (User. id name points balance {}
                updated-at created-at saved-at version))
            )

(defn from-json
  [json]
  (let [struct (parse-string json true)]
    (from-struct struct)))

(defn to-json
  [user]
  (generate-string user))