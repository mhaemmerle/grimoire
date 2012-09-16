(ns grimoire.game
  (:require [clojure.tools.logging :as log]
            [grimoire
             [level :as level]
             [map :as map]]))

(defn get-update-function
  [action]
  (case (:action action)
    :level (level/update action)
    :map (map/update action)
    (throw (Exception. "no_update_function_found, args=[%s]" action))))