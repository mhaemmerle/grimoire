(ns grimoire.registry
  (:require [clojure.tools.logging :as log]
            [grimoire
             [config :as config]
             [node :as node]
             [cluster :as cluster]]
            [zookeeper :as zk]
            [zookeeper.data :as zk-data]
            [zookeeper.util :as zk-util]))

(defonce registry-group-name "/registry")

(def ^:dynamic *client* nil)

(defn ^:private to-zk-location
  [user-id & z-nodes]
  (str registry-group-name "/" user-id (clojure.string/join "/" z-nodes)))

(defn register
  [user-id valid-until cluster-node]
  ;; (log/info "register" user-id valid-until cluster-node)
  (let [user-znode (to-zk-location user-id)
        lock-znode (str user-znode "/_lock-")]
    (let [create-response (zk/create-all *client* lock-znode
                                         :persistent? true :sequential? true)
          create-id (zk-util/extract-id create-response)
          user-node-response (zk/exists *client* user-znode)]
      (if (= 0 create-id)
        (zk/set-data *client* user-znode
                     (cluster/serialize {:valid-until valid-until :node cluster-node})
                     (:version user-node-response))
        (throw
         (Exception. (format "session_already_registered, args=[%s]" user-id)))))))

(defn deregister
  [user-id]
  (log/info "deregister" user-id (to-zk-location user-id))
  (zk/delete-all *client* (to-zk-location user-id)))

(defn extend-timeout
  [user-id valid-until]
  (let [zk-location (to-zk-location user-id)
        response (zk/exists *client* zk-location)
        data (cluster/deserialize (:data response))
        new-data (assoc-in data :valid-until valid-until)]
    (zk/set-data *client* zk-location (cluster/serialize new-data) (:version response))))

(defn deregister-all
  []
  (log/info "delete all registered users")
  (zk/delete-all *client* registry-group-name))

(defn get-node-data
  [user-id]
  (let [response (zk/data *client* (to-zk-location user-id))]
    (cluster/deserialize (:data response))))

(defn get-location
  [user-id]
  (:node (get-node-data user-id)))

(defn local?
  [user-id]
  (let [user-node-name (:node (get-node-data user-id))]
    (= user-node-name (node/get-node-name))))

(defn registered?
  [user-id]
  (not (nil? (get-node-data user-id))))

(defn ^:private create-registry-group
  []
  (when (nil? (zk/exists *client* registry-group-name))
    (zk/create *client* registry-group-name :persistent? true)))

;; TODO check for dev environment
(defn ^:private dev-clean
  []
  (let [remote-nodes (cluster/get-remote-nodes)]
    (when (empty? remote-nodes)
      (zk/delete-all *client* registry-group-name))))

(defn start
  [host port]
  (let [client (zk/connect (str host ":" port))]
    (alter-var-root #'*client* (fn [_] client)))
  ;; developer-sanity-protection-measures
  (dev-clean)
  (create-registry-group))

(defn b-fn
  [i]
  (zk/create *client* (to-zk-location i)))

;; (future
;;   (do
;;     (log/info "begin sleep")
;;     (Thread/sleep 8000)
;;     (log/info "done sleeping")
;;     (log/info "begin zk node creation")
;;     (let [t (time (dotimes [i 5000] (b-fn i)))]
;;       (log/info t))))
