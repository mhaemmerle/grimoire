(ns grimoire-client.core)

(defn- on-open
  [event]
  (.log js/console "connection open"))

(defn- on-message
  [event]
  (.log js/console "received message" event))

(defn- on-error
  [event]
  (.log js/console "error" event))

(defn ^:export init
  [user-id]
  (let [source (js/EventSource. (str "http://localhost:4000/" user-id "/events"))]
    (.addEventListener source "open" on-open)
    (.addEventListener source "message" on-message)
    (.addEventListener source "error" on-error)))