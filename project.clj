(defproject walmartlabs/active-status "0.1.3"
  :description "Present status of mulitple 'jobs' in a command line tool, using ANSI escape codes"
  :url "https://github.com/walmartlabs/active-status"
  :license {:name "Apache Sofware License 2.0"
            :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.2.374"]
                 [medley "0.7.0"]
                 [io.aviso/toolchest "0.1.3"]
                 [io.aviso/pretty "0.1.22"]]

  :aliases {"release" ["do"
                       "clean,"
                       "codox,"
                       "deploy" "clojars"]}
  :codox {:source-uri "https://github.com/walmartlabs/active-status/master/{filepath}#L{line}"
          :metadata   {:doc/format :markdown}}
  :plugins [[lein-codox "0.9.0"]])
