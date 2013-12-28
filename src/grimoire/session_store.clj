(ns grimoire.session-store
  (:refer-clojure :exclude [get remove])
  (:require [clojure.tools.logging :as log]))

(defonce ^:private store (atom {}))

(defn num-sessions [] (count @store))

(defn exists?
  [user-id]
  (log/info "exists" user-id)
  (let [user (clojure.core/get @store user-id)]
    (not (nil? user))))

(defn get
  [user-id]
  (log/info "get" user-id)
  (clojure.core/get @store user-id))

(defn put
  [user-id session]
  (log/info "put" user-id)
  (if (nil? (clojure.core/get @store user-id))
    (swap! store assoc user-id session)
    (throw (Exception. (format "session_already_running, args=[%s]" user-id)))))

(defn remove
  [user-id]
  (swap! store dissoc user-id))
