(ns user
  (:use com.walmartlabs.active-status
        clojure.repl)
  (:require [clojure.core.async :refer [close! go go-loop timeout <! >! >!!
                                        ]]))


(defn- job
  ([ch name speed count]
   (job ch name speed count :normal))
  ([ch name speed count status]
   (go
     (dotimes [i count]
       (when (= i 0)
         (>! ch (str name " - started")))
       (<! (timeout speed))
       (when (= i 5)
         (>! ch (change-status status)))
       (>! ch (str name " - update " (inc i) "/" count)))
     (>! ch (str name " - completed " count))
     (close! ch))))

(defn demo []
  (let [t (status-tracker)
        j (add-job t)]
    (>!! j "adding one, two, three")
    (job (add-job t) "one" 100 5)
    (job (add-job t) "two" 500 100 :warning)
    (job (add-job t) "three" 250 10)
    (>!! j "first sleep")
    (Thread/sleep 3000)
    (>!! j "adding four, five")
    (job (add-job t) "four" 250 10 :success)
    (job (add-job t) "five" 2000 30)
    (>!! j "second sleep")
    (Thread/sleep 3000)
    (>!! j "adding six, seven")
    (job (add-job t) "six" 1000 10 :error)
    (job (add-job t) "seven" 10 10000)
    (>!! j "final (long) sleep")
    (Thread/sleep 5000)
    (>!! j "shutting down!")
    (close! t)
    ;; Without this sleep, the repl outputs a line saying "nil",
    ;; which screws up the final output of the lines.
    (Thread/sleep 100)))


(defn reload []
  (load-file "src/com/walmartlabs/active_status.clj")
  (load-file "dev-resources/user.clj")
  (println "Reloaded."))