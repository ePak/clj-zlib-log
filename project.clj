(defproject clj-zlib-log "0.1.0-SNAPSHOT"
  :description "Read a z-compressed and encoded log file and push logs into LogStash"
  :url "https://github.com/ePak/clj-zlib-log"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0-RC1"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [nio "1.0.3"]
                 [gloss "0.2.3"]
                 [clj-glob "1.0.0"]
                 [org.clojure/tools.cli "0.3.1"]
                 [org.clojure/data.json "0.2.5"]
                 [clj-time "0.9.0"]
                 [org.clojure/math.numeric-tower "0.0.4"]]
  :main ^:skip-aot zlog.core
  :target-path "target/%s"
  :java-source-paths ["src/java"]
  #_(:javac-options ["-Xlint:unchecked"])
  :profiles {:uberjar {:aot :all}}
  :user {:plugins [[cider/cider-nrepl "0.8.1"]]})
