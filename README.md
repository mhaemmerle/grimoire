## grimoire

An exercise in writing a stateful social game server in Clojure to
compare language and runtime environment against Erlang.

Main building blocks used are 
[Aleph](http://github.com/ztellman/aleph),
[Lamina](http://github.com/ztellman/lamina),
[Netty](http://github.com/netty/netty), [zookeeper-clj](http://github.com/liebke/zookeeper-clj) and
[ZooKeeper](http://http://zookeeper.apache.org/) itself.

Either add your AWS S3 credentials in src/grimoire/config.clj or
change the respective functions in src/grimoire/storage.clj to return nil.

Make sure you have ZooKeeper installed and running per
[these instructions](https://github.com/liebke/zookeeper-clj#running-zookeeper)
then do:

    $ lein deps
    $ lein protobuf
    $ lein run
    
    $ curl http://localhost:4000/123/setup
    $ curl -X POST -d '{"id":1,"x":7,"y":7}' http://localhost:4000/123/map/add

You will almost certainly need [Leiningen 2](http://github.com/technomancy/leiningen)
to run this project.

[Documentation](http://mhaemmerle.github.com/grimoire/index.html).

## License

grimoire is Copyright © 2012 Marc Haemmerle

Distributed under the Eclipse Public License, the same as Clojure.
