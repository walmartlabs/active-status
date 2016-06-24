(defproject walmartlabs/active-status "0.1.7"
  :description "Present status of mulitple 'jobs' in a command line tool, using ANSI escape codes"
  :url "https://github.com/walmartlabs/active-status"
  :license {:name "Apache Sofware License 2.0"
            :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.2.385"]
                 [medley "0.8.2"]
                 [io.aviso/toolchest "0.1.4"]
                 [io.aviso/pretty "0.1.26"]
                 [io.aviso/config "0.1.13" :optional true]]
  :aliases {"release" ["do"
                       "clean,"
                       "deploy" "clojars"]}
  :codox {:source-uri "https://github.com/walmartlabs/active-status/blob/master/{filepath}#L{line}"
          :metadata   {:doc/format :markdown}}
  :plugins [[lein-codox "0.9.3"]])
