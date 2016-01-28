(ns user
  (:use com.walmartlabs.active-status
        clojure.repl)
  (:require [clojure.core.async :refer [close! go go-loop timeout <! >! >!!
                                        ]]))


(defn- job
  ([ch name speed count]
   (job ch name speed count :normal false))
  ([ch name speed count status progress]
   (go
     (dotimes [i count]
       (when (= i 0)
         (>! ch (str name " - started"))
         (when progress
           (>! ch (start-progress count))))
       (<! (timeout speed))
       (when (= i 5)
         (>! ch (change-status status)))
       (when-not progress
         (>! ch (str name " - update " (inc i) "/" count)))
       (when progress
         (>! ch (progress-tick))))
     (>! ch (str name " - completed " count))
     (when progress
       (>! ch (complete-progress)))
     (close! ch))))

(defn demo []
  (let [t (status-tracker)
        j (add-job t)]
    (>!! j "adding one, two, three")
    (job (add-job t) "one" 100 50)
    (job (add-job t) "two" 500 100 :warning true)
    (job (add-job t) "three" 250 10 :normal true)
    (>!! j "first sleep")
    (Thread/sleep 3000)
    (>!! j "adding four, five")
    (job (add-job t) "four" 250 10 :success false)
    (job (add-job t) "five" 2000 30)
    (>!! j "second sleep")
    (Thread/sleep 3000)
    (>!! j "adding six, seven")
    (job (add-job t) "six" 250 100 :error true)
    (job (add-job t) "seven" 10 1000 :normal true)
    (>!! j "final (long) sleep")
    (Thread/sleep 15000)
    (>!! j "shutting down!")
    (close! t)
    ;; Without this sleep, the repl outputs a line saying "nil",
    ;; which screws up the final output of the lines.
    (Thread/sleep 100)))


(defn reload []
  (load-file "src/com/walmartlabs/active_status.clj")
  (load-file "dev-resources/user.clj")
  (println "Reloaded."))