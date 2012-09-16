(ns grimoire.config)

(defonce system
  {:save-interval 240000
   :session-timeout 480000
   :session-version 1})

(defonce facebook
  {:app-id ""
   :app-secret ""
   :app-name ""
   :api-key ""
   :canvas-url ""})

(defonce zookeeper
  [{:port 2181 :host "127.0.01"}
   {:port 2181 :host "127.0.01"}
   {:port 2181 :host "127.0.01"}])

(defonce aws
  {:access-key ""
   :secret-key ""
   :bucket "grimoire-dev"})

;; default options
(defonce db
  {:host "localhost"
   :port 6379})

;; default options
(defonce canvas-server
  {:host "localhost"
   :port 3000})

;; default options
(defonce api-server
  {:host "localhost"
   :port 4000})