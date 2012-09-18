(defproject grimoire "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.slf4j/slf4j-api "1.6.6"]
                 [org.slf4j/slf4j-log4j12 "1.6.6"]
                 [org.clojure/tools.logging "0.2.3"]
                 [aleph "0.3.0-beta4"]
                 [zookeeper-clj "0.9.2"]
                 [ring "1.1.1"]
                 [compojure "1.1.1"]
                 [hiccup "1.0.0"]
                 [ring/ring-jetty-adapter "1.1.0"]
                 [ring-json-params "0.1.3"]
                 [compojure "1.1.0"]
                 [slingshot "0.10.3"]
                 [cheshire "4.0.1"]
                 [clj-aws-s3 "0.3.2"]
                 [org.clojure/tools.cli "0.2.1"]
                 [protobuf "0.6.1"]
                 [org.clojure/tools.nrepl "0.2.0-beta9"]]
  :dev-dependencies [[lein-marginalia "0.7.1"]
                     [criterium "0.2.1"]]
  :plugins [[lein-ring "0.7.1"]
            [swank-clojure "1.2.1"]
            [lein-cljsbuild "0.2.5"]
            [lein-protobuf "0.2.0"]]
  :cljsbuild {:builds [{:source-path "src-cljs"
                        :compiler {:output-to "resources/public/js/main.js"
                                   :optimizations :whitespace
                                   :pretty-print true}}]}
  :jvm-opts ["-Djava.awt.headless=true" "-Dio.netty.epollBugWorkaround=true"
             "-server" "-XX:+UseConcMarkSweepGC" "-Xmx2g" "-XX:NewSize=1g"]
  :main grimoire.core)