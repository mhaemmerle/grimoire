(ns grimoire.game
  (:require [clojure.tools.logging :as log]
            [grimoire
             [level :as level]
             [map :as map]]))

(defn get-update-function
  [event]
  (case (:action event)
    :level (level/update event)
    :map (map/update event)
    (throw (Exception. "no_update_function_found, args=[%s]" event))))