(ns grimoire.session-store
  (:refer-clojure :exclude [get remove])
  (:require [clojure.tools.logging :as log]))

(defonce ^:private store (atom {}))

(defn num-sessions [] (count @store))

(defn exists?
  [user-id]
  (not (nil? (@store user-id))))

(defn get
  [user-id]
  (@store user-id))

(defn put
  [user-id session]
  (if (nil? (@store user-id))
    (swap! store assoc user-id session)
    (throw (Exception. (format "session_already_running, args=[%s]" user-id)))))

(defn remove
  [user-id]
  (swap! store dissoc user-id))
