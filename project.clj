(defproject walmartlabs/active-status "0.1.16"
  :description "Present status of mulitple 'jobs' in a command line tool, using terminal capability codes"
  :url "https://github.com/walmartlabs/active-status"
  :license {:name "Apache Sofware License 2.0"
            :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [clojure-future-spec "1.9.0-alpha17"]
                 [org.clojure/core.async "0.3.443"]
                 [io.aviso/pretty "0.1.35"]
                 [io.aviso/config "0.2.2" :optional true]]
  :aliases {"release" ["do"
                       "clean,"
                       "deploy" "clojars"]}
  :codox {:source-uri "https://github.com/walmartlabs/active-status/blob/master/{filepath}#L{line}"
          :metadata   {:doc/format :markdown}}
  :plugins [[lein-codox "0.10.2"]])
