(ns grimoire.cluster
  (:refer-clojure :exclude [join])
  (:require  [grimoire
              [node :as node]
              [protocol :as protocol]]
             [zookeeper :as zk]
             [zookeeper.data :as data]
             [zookeeper.logger :as logger]
             [clojure.tools.logging :as log]))

;; publish node-state to zookeeper

(def group-name "/cluster")

(def ^:dynamic *client* nil)

(defn get-local-ip
  []
  (.getHostAddress (java.net.InetAddress/getLocalHost)))

(defn serialize
  [node-data]
  (data/to-bytes (pr-str node-data)))

(defn deserialize
  [node-data]
  (read-string (data/to-string node-data)))

(defn get-node-path
  [node-name]
  (str group-name "/" node-name))

(defn get-node-data
  [node-name]
  (let [response (zk/data *client* (get-node-path node-name))]
    (deserialize (:data response))))

(defn get-remote-ip
  [node-name]
  (let [response (zk/exists *client* (get-node-path node-name))]
    (when-not (nil? response)
      (:host (deserialize (:data response))))))

(defn ^:private create-group
  []
  (when-not (zk/exists *client* group-name)
    (zk/create *client* group-name :persistent? true)))

(defn ^:private node-watcher
  [node-name event]
  (log/info "node-watcher" node-name event)
  (if (= (:event-type event) :NodeDeleted)
    (node/disconnect node-name)
    (do
      (log/info "node-watcher" (:path event))
      (zk/exists *client* (:path event) :watcher (partial node-watcher node-name)))))

(defn get-remote-nodes
  []
  (filter #(not (= (node/get-node-name) %)) (zk/children *client* group-name)))

(defn ^:private group-watcher
  [event]
  (when (= (:event-type event) :NodeChildrenChanged)
    (log/info "group-watcher" event)
    (let [all-nodes (zk/children *client* group-name :watcher group-watcher)
          connections (node/get-connections)
          remote-nodes (->> all-nodes
                            (filter #(not (= (node/get-node-name) %)))
                            (filter #(nil? (connections %))))]
      (log/info "group-watcher" all-nodes remote-nodes)
      (doseq [node-name remote-nodes]
        (let [node-data (get-node-data node-name)]
          (log/info "group-watcher" node-name node-data)
          (zk/exists *client* (get-node-path node-name)
                     :watcher (partial node-watcher node-name))
          (node/connect node-name node-data))))))

(defn join
  [local-port]
  (let [ip (get-local-ip)
        ;; needs refactoring
        ;; inet-socket-address (str ip ":" local-port)
        node-name (node/get-node-name)
        node-data (serialize {:host ip :port local-port :node-name node-name})]
    (log/info "join-cluster" ip local-port node-name)
    (zk/create *client* (get-node-path node-name) :data node-data))
  (zk/children *client* group-name :watcher group-watcher))

(defn start
  [{:keys [zk-host zk-port local-host local-port]}]
  (log/info "start" zk-host zk-port local-host local-port)
  (let [client (zk/connect (str zk-host ":" zk-port))]
    (alter-var-root #'*client* (fn [_] client)))
  (create-group)
  (join local-port))