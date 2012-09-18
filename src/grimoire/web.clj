(ns grimoire.web
  (:use [slingshot.slingshot :only [try+ throw+]]
        [lamina core executor]
        [aleph http formats]
        compojure.core
        [ring.middleware
         [json-params]
         [reload]]
        [cheshire.core :only [generate-string]])
  (:require [compojure.route :as route]
            [clojure.tools.logging :as log]
            [grimoire
             [node :as node]
             [session :as session]
             [storage :as storage]
             [user :as user]
             [registry :as registry]
             [config :as config]])
  (:import org.codehaus.jackson.JsonParseException)
  ;; index/stats html imports
  ;; TODO move to template ns
  (:require [hiccup
             [page :refer [html5 include-js]]
             [element :refer [javascript-tag]]]))

(def ^:dynamic *aleph-stop* (atom nil))

(def error-codes
  {:invalid 400
   :not-found 404})

(defn json-response [data & [status]]
  {:status (or status 200)
   :headers {"Content-Type" "application/json"}
   :body (generate-string (or data {}))})

(defn ^:private encode-event-data
  [data]
  (str "data:" (generate-string data) "\n\n"))

(defn respond
  [response-channel data]
  (enqueue response-channel (json-response data)))

(defn wrap-bounce-favicon [handler]
  (log/info "bouncing favicon request")
  (fn [req]
    (if (= [:get "/favicon.ico"] [(:request-method req) (:uri req)])
      {:status 404
       :headers {}
       :body ""}
      (handler req))))

(defn wrap-error-handling [handler]
  (log/info "entering error-handling" handler)
  (fn [req]
    (try+
     (or (handler req)
         (json-response {"error" "resource not found"} 404))
     (catch JsonParseException e
       (json-response {"error" "malformed json"} 400))
     (catch [:type :grimoire] {:keys [message args]}
       (log/error message args)
       (json-response {"error" message}))
     (catch Exception e
       (let [message (.getMessage ^Exception e)]
         (log/error "wrap-error-handling" e message)
         (json-response {"error" message})))
     (catch Object e
       (let [{:keys [type message]} (meta e)]
         (log/error "wrap-error-handling" type message e)
         (json-response {"error" message} (error-codes type)))))))

(defn- run-clojurescript [path init]
  (list
   (javascript-tag "var CLOSURE_NO_DEPS = true;")
   (include-js path)
   (javascript-tag init)))

(defn index
  [user-id]
  (html5
   [:head
    [:title "grimoire"]]
   [:body
    [:div {:id "content"} ""]
    (run-clojurescript
     "/js/main.js"
     (format "grimoire_client.core.init(%s)" user-id))]))

(defn stats-index
  []
  (html5
   [:head
    [:title "stats"]]
   [:body
    ;; http://goo.gl/QrXDs
    (javascript-tag "var source = new EventSource('/stats-events');")
    (javascript-tag "source.addEventListener('message', function(event) { console.log(JSON.parse(event.data)) })")]))

;; FIXME wrap result in 'wrap-aleph-handler'
(defmacro defpipeline
  [name & tasks]
  `(defn ~name
     [response-channel# request#]
     (run-pipeline request#
                   {:error-handler (fn [error#]
                                     (let [message# (.getMessage ^Exception error#)]
                                       (log/error "pipeline" message#)
                                       (respond response-channel# {"error" message#})))}
                   ~@tasks
                   (fn [response#]
                     (respond response-channel# response#)))))

;; (clojure.pprint/pprint (macroexpand '(defpipeline setup)))

(defpipeline setup-handler
  (fn [request]
    (let [{{:keys [user-id]} :route-params} request]
      (session/setup (read-string user-id)))))

(defpipeline where-is-handler
  (fn [request]
    (let [{{:keys [user-id]} :route-params} request]
      {:node (registry/get-location (read-string user-id))})))

(defn action-handler
  [response-channel request]
  (let [event-channel (map* generate-string (channel))
        {{:keys [user-id action verb]} :route-params} request
        body (decode-json (:body request))]
    (session/update (read-string user-id) event-channel action verb body)
    (enqueue response-channel
             {:status 200
              :headers {"Content-Type" "application/json"}
              :body event-channel})))

(defn ^:private create-event-data-response
  [response-channel event-channel]
  (enqueue response-channel
           {:status 200
            :headers {"Content-Type" "text/event-stream"}
            :body (map* encode-event-data event-channel)}))

(defn session-events-handler
  [response-channel request]
  (let [event-channel (channel)
        {{:keys [user-id]} :route-params} request]
    (session/register-event-channel (read-string user-id) event-channel)
    (create-event-data-response response-channel event-channel)))

(defn stats-events-handler
  [response-channel request]
  (let [event-channel (channel)]
    (node/register-stats-channel event-channel)
    (create-event-data-response response-channel event-channel)))

;; TODO move to bench/test namespace
(defn bench-handler
  [response-channel request]
  (let [event-channel (map* generate-string (channel))
        user-id (Integer/parseInt "123")]
    (session/update user-id event-channel "map" "add"
                    {:id 1 :x (rand-int 100) :y (rand-int 100)})
    (enqueue response-channel
             {:status 200
              :headers {"Content-Type" "application/json"}
              :body event-channel})))

;; TODO move to bench/test namespace
(defn noop-handler
  []
  {:status 200
   :headers {}
   :body ""})

(def handlers
  (routes
   (GET "/" [] (index 123))
   (GET ["/:user-id/setup", :user-id #"[0-9]+"] [] (wrap-aleph-handler setup-handler))
   (GET ["/:user-id/where-is", :user-id #"[0-9]+"] [] (wrap-aleph-handler
                                                       where-is-handler))
   (GET ["/:user-id/events", :user-id #"[0-9]+"] [] (wrap-aleph-handler
                                                     session-events-handler))
   (POST ["/:user-id/:action/:verb", :user-id #"[0-9]+"] [] (wrap-aleph-handler
                                                             action-handler))
   (GET ["/stats-events"] [] (wrap-aleph-handler stats-events-handler))
   (GET ["/stats"] [] (stats-index))
   (GET "/bench" [] (wrap-aleph-handler bench-handler))
   (GET "/noop" [] (noop-handler))
   (route/resources "/")
   (route/not-found "Page not found")))

(def application
  (-> handlers
      wrap-bounce-favicon
      wrap-json-params
      wrap-error-handling))

(defn start
  [port]
  (let [wrapped-handler (wrap-ring-handler application)
        aleph-stop-fn (start-http-server wrapped-handler  {:port port})]
    (reset! *aleph-stop* aleph-stop-fn)))

(defn stop
  []
  (@*aleph-stop*))