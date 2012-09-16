(ns grimoire.test.storage
  (:use [grimoire
         storage
         [config :as config]]))

(defn mock-user-struct
  [^Integer user-id]
  (let [current-time (System/currentTimeMillis)]
    {:id user-id
     :name "Mister Mock"
     :points 0
     :balance 0
     :updated-at current-time
     :created-at current-time
     :version (config/system :session-version)}))
