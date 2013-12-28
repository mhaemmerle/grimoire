(ns grimoire.registry
  (:require [clojure.tools.logging :as log]
            [grimoire
             [config :as config]
             [node :as node]
             [cluster :as cluster]]
            [zookeeper :as zk]
            [zookeeper.data :as zk-data]
            [zookeeper.util :as zk-util]))

(def group-name "/registry")

(def ^:dynamic *client*)

(defprotocol Registry
  (register [this user-id valid-until cluster-node])
  (deregister [this user-id])
  (extend-timeout [this user-id valid-until])
  (deregister-all [this])
  (get-location [this user-id])
  (local? [this user-id])
  (registered? [this user-id])
  (delete-all [this])
  (create-registry-group [this]))

;; in-memory

(deftype InMemoryRegistry [store]
  Registry
  (register [this user-id valid-until cluster-node]
    (log/info "register in-memory")
    (let [user {:user-id user-id :valid-until valid-until :node cluster-node}]
      (swap! store update-in [user-id]
             #(if %
                (throw
                 (Exception. (format "session_already_registered, args=[%s]" user-id)))
                user)))
    (log/info "register in-memory" @store)
    nil)

  (deregister [this user-id]
    (log/info "deregister" user-id)
    (swap! store dissoc user-id)
    nil)

  (extend-timeout [this user-id valid-until]
    (swap! store assoc-in [user-id :valid-until] valid-until)
    nil)

  (deregister-all [this]
    (log/info "deregister-all")
    (reset! store {})
    nil)

  (get-location [this user-id]
    (let [user (clojure.core/get @store user-id)]
      (log/info "get-location" user)
      (:node user)))

  (local? [this user-id]
    (let [location (get-location this user-id)
          node-name (node/get-node-name)]
      (log/info "local?" location node-name)
      (= location node-name)))

  (registered? [this user-id]
    (log/info "registered?" user-id)
    (let [user (clojure.core/get @store user-id)]
      (not (nil? user))))

  (create-registry-group [this]
    (log/info "create-registry-group")
    nil))

;; zookeeper

(defn to-zk-location
  [user-id & z-nodes]
  (str group-name "/" user-id (clojure.string/join "/" z-nodes)))

(defn get-node-data [client user-id]
  (let [response (zk/data client (to-zk-location user-id))]
    (cluster/deserialize (:data response))))

(deftype ZooKeeperRegistry [client]
  Registry
  (register [this user-id valid-until cluster-node]
    (log/info "register zookeeper")
    (let [user-znode (to-zk-location user-id)
          lock-znode (str user-znode "/_lock-")]
      (let [create-response (zk/create-all client lock-znode
                                           :persistent? true :sequential? true)
            create-id (zk-util/extract-id create-response)
            user-node-response (zk/exists client user-znode)]
        (if (= 0 create-id)
          (zk/set-data client user-znode
                       (cluster/serialize {:valid-until valid-until :node cluster-node})
                       (:version user-node-response))
          (throw
           (Exception. (format "session_already_registered, args=[%s]" user-id)))))))

  (deregister [this user-id]
    (log/info "deregister" user-id (to-zk-location user-id))
    (zk/delete-all client (to-zk-location user-id)))

  (extend-timeout [this user-id valid-until]
    (let [zk-location (to-zk-location user-id)
          response (zk/exists client zk-location)
          data (cluster/deserialize (:data response))
          new-data (assoc-in data :valid-until valid-until)]
      (zk/set-data client zk-location (cluster/serialize new-data)
                   (:version response))))

  (deregister-all [this]
    (log/info "deregister-all")
    (zk/delete-all client group-name))

  (get-location [this user-id]
    (log/info "get-location")
    (:node (get-node-data client user-id)))

  (local? [this user-id]
    (log/info "local?")
    (= (:node (get-node-data this user-id)) (node/get-node-name)))

  (registered? [this user-id]
    (log/info "registered?" user-id)
    (not (nil? (get-node-data this user-id))))

  (create-registry-group [this]
    (log/info "create-registry-group")
    (when (nil? (zk/exists client group-name))
      (zk/create client group-name :persistent? true))))

(alter-var-root #'*client* (fn [_] (InMemoryRegistry. (atom {}))))
;; (ZooKeeperRegistry. (zk/connect (str host ":" port)))
