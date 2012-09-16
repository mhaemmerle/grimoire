(ns grimoire.level
  (:require [clojure.tools.logging :as log])
  (:use grimoire.util
        [lamina core executor]))

(def example-levels
  {1 {:required-points 0 :reward 0}
   2 {:required-points 10 :reward 5}
   3 {:required-points 20 :reward 5}
   4 {:required-points 30 :reward 5}
   5 {:required-points 40 :reward 5}})

(defmulti update :verb)

(defupdate update :complete
  (fn [state {:keys [level]}]
    {:pre (number? level)}
    (let [level-config (example-levels level)]
      (if (>= (:points state) (:required-points level-config))
        (-> state
            (update-in [:balance] + (:reward level-config))
            build-response)
        (throw (Exception.
                (format "insufficient_points_for_level_complete, args=[%s, %s]"
                        (:id state) level)))))))