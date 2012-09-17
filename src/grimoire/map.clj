(ns grimoire.map
  (:require [clojure.tools.logging :as log])
  (:use [lamina core executor]
        [grimoire.util]
        [cheshire.core :only [generate-string parse-string]]))

(def example-entities
  {1 {:name "entity 1" :width 1 :height 1 :cost 5}
   2 {:name "entity 2" :width 1 :height 1 :cost 7}
   3 {:name "entity 3" :width 1 :height 1 :cost 4}})

(def example-contracts
  {1 {:name "contract 1" :duration 2 :reward 5}
   2 {:name "contract 2" :duration 4 :reward 22}
   3 {:name "contract 3" :duration 6 :reward 30}})

(def default-width 100)
(def default-height 100)

(defn ^:private m-keyword
  ([x y]
     (m-keyword [x y]))
  ([[x y]]
     (keyword (str "x" x "y" y))))

(defn generate-empty-map
  ;; Creates an empty map of the structure {:x1y1 nil :x1y2 nil}
  [width height]
  (reduce
   #(assoc %1 (m-keyword %2) nil) {} (map vector (range width) (range height))))

(def start-map (generate-empty-map default-width default-height))

;; (defmacro defaction
;;   [name body]
;;   `(defn ~name
;;      [request-body#]
;;      (fn [channel# state#]
;;        (try
;;          (let [result-map# (~@body state# (:body request-body#))]
;;            ;; (enqueue-and-close channel# (:response result-map#))
;;            (enqueue channel# (:response result-map#))
;;            (:result result-map#))
;;          (catch Exception e#
;;            (log/error "update failed with" (.getMessage e#))
;;            (when-not (closed? channel#)
;;              ;; (enqueue-and-close channel# {"error" "fatal"})
;;              (enqueue channel# {"error" "fatal"})
;;              state#))))))

;; (defaction update
;;   (fn [state {:keys [id x y]}]
;;     (let [entity-config (example-entities id)
;;           coord-key (m-keyword x y)
;;           entity (get-in state [:map coord-key])]
;;       (-> state
;;           (assoc-in [:map coord-key] {:id id})
;;           (update-in [:balance] - (:cost entity-config))
;;           build-response))))

(defmulti update :verb)

(defupdate update :add
  (fn [state {:keys [id x y]}]
    (let [entity-config (example-entities id)
          coord-key (m-keyword x y)
          entity (get-in state [:map coord-key])]
      ;; (if (nil? entity)
        (-> state
            (assoc-in [:map coord-key] {:id id})
            (update-in [:balance] - (:cost entity-config))
            build-response)
        ;; (throw (Exception. (format "map_position_not_empty,
        ;; args=[%s,%s]" x y))))
        )))

(defupdate update :start-contract
  (fn [state {:keys [id x y]}]
    (let [coord-key (m-keyword x y)
          entity (get-in state [:map coord-key])]
      (if (and (not (nil? entity))
               (nil? (:contract-start entity)))
        (-> state
            (update-in [:map coord-key] merge
                       {:contract-start (System/currentTimeMillis) :contract-id id})
            build-response)
        (throw (Exception. (format "start_contract_failed, args=[%s, %s]"
                                   (:id state) entity)))))))

(defn ^:private collectible?
  [entity]
  {:pre [(has-keys? entity :contract-start :contract-id)]}
  (let [contract-config (example-contracts (:contract-id entity))
        duration (:duration contract-config)]
    (> (System/currentTimeMillis) (+ duration (:contract-start entity)))))

(defupdate update :collect-contract
  (fn [state {:keys [x y]}]
    (let [coord-key (m-keyword x y)
          entity (get-in state [:map coord-key])]
      (if (collectible? entity)
        (-> state
            (assoc-in [:map coord-key] (dissoc entity :contract-start :contract-id))
            (update-in [:balance] - (:reward (example-contracts (:contract-id entity))))
            build-response)
        (throw (Exception. (format "collect_contract_failed, args=[%s, %s]"
                                   (:id state) entity)))))))