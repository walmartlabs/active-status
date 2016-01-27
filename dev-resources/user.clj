(ns user
  (:use com.walmartlabs.active-status
        clojure.repl)
  (:require [clojure.core.async :refer [close! put! go go-loop timeout <! >!
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
       (>! ch (str name " - update " (inc i) "/" count))))))

(defn demo []
  (let [t (status-tracker {:dim-after-millis 200})]
    (job (add-job t) "one" 1000 5)
    (job (add-job t) "two" 500 100 :warning)
    (job (add-job t) "three" 250 200)
    (Thread/sleep 5000)
    (job (add-job t) "four" 1000 10 :success)
    (job (add-job t) "five" 2000 30)
    (Thread/sleep 5000)
    (job (add-job t) "six" 1000 10 :error)
    (job (add-job t) "seven" 500 30)
    (Thread/sleep 10000)
    (close! t)))


