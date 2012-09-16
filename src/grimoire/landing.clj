(ns grimoire.landing
  (:use [ring.adapter.jetty :only [run-jetty]]
        [compojure.core]
        [clojure.tools.logging :only [info error]])
  (:require [compojure.route :as route]
            [hiccup
             [page :refer [html5 include-js]]
             [element :refer [javascript-tag]]]
            [grimoire.config :as config]))

(def ^:dynamic *jetty-server* (atom nil))

(defn- run-clojurescript [path init]
  (list
   (javascript-tag "var CLOSURE_NO_DEPS = true;")
   (include-js path)
   (javascript-tag init)))

(defn index
  []
  (html5
   [:head
    [:title "grimoire"]]
   [:body
    [:div {:id "content"} "Hello World"]
    (run-clojurescript
     "/js/main.js"
     "grimoire_client.core.say_hello()")]))

(defroutes application
  (GET "/" [] (index))
  (route/resources "/")
  (route/not-found "Page not found"))

(defn start
  [port]
  (let [jetty-server (run-jetty #'application {:port (or port 8080) :join? false})]
    (reset! *jetty-server* jetty-server))
  (info "Canvas server running on port " port))

(defn stop
  []
  (.stop @*jetty-server*)
  (info "Stopped canvas server"))