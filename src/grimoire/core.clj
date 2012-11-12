(ns grimoire.core
  (:gen-class)
  (:use clojure.tools.cli)
  ;; (:use [clojure.tools.nrepl.server :only [start-server stop-server]])
  (:require [grimoire
             [config :as config]
             [web :as web]
             [cluster :as cluster]
             [node :as node]
             [registry :as registry]
             [landing :as landing]
             [session :as session]]
            [clojure.tools.logging :as log]))

(set! *warn-on-reflection* true)

;; (defonce nrepl-server (start-server :port 7888))

(defn run-test
  [users]
  (dotimes [user-id users]
    (session/setup user-id)
    (when (= (rem user-id 1000) 0)
      ;; (log/info (str "done" user-id "so far"))
      )
    ))

(defn start
  [options]
  (println "Starting server...")
  (node/start-server (:cluster-port options) (:node-id options))
  (cluster/start {:zk-host "127.0.0.1" :zk-port 2181
                  :local-port (:cluster-port options)})
  (registry/start "127.0.0.1" 2181)
  (web/start (:api-port options))
  ;; (landing/start (:canvas-port options))
  (println (format "'%s' waiting for work, work!" (node/get-node-name))))

(defn stop
  []
  (println "Preparing exit...")
  ;; FIXME server states are missing
  (shutdown-agents)
  (web/stop)
  (println "All agents are dead.\nGoodbye."))

(defn at-exit
  [runnable]
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable runnable)))

(defn -main
  [& args]
  (let [[options args banner]
        (cli args
             ["-h" "--help" "Show help" :default false :flag true]
             ["-i" "--node-id" "Node ID for cross-node addressing"
              :default 0 :parse-fn #(Integer/parseInt %)]
             ["-n" "--cluster-port" "Local cluster listening port"
              :default 7000 :parse-fn #(Integer/parseInt %)]
             ["-a" "--api-port" "Client facing API listening port"
              :default (config/api-server :port) :parse-fn #(Integer/parseInt %)]
             ["-c" "--canvas-port" "Landing page listening port"
              :default (config/canvas-server :port) :parse-fn #(Integer/parseInt %)])]
    (when (:help options)
      (println banner)
      (System/exit 0))
    (start options))
  (at-exit stop))