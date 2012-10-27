(ns grimoire.session-store
  (:refer-clojure :exclude [get remove])
  (:require [clojure.tools.logging :as log]))

(defonce ^:private store (atom {}))

(defn num-sessions [] (count @store))

(defn get
  [user-id]
  (log/info "get")
  (if-let [session (@store user-id)]
    session
    (throw (Exception. (format "session_not_running, args=[%s]" user-id)))))

(defn put
  [user-id session]
  (log/info "put")
  (if (nil? (@store user-id))
    (swap! store assoc user-id session)
    (throw (Exception. (format "session_already_running, args=[%s]" user-id)))))

(defn remove
  [user-id]
  (log/info "remove")
  (swap! store dissoc user-id))