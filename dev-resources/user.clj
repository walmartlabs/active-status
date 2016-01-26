(ns user
  (:use com.walmartlabs.active-status)
  (:require [clojure.core.async :refer [close! put! go go-loop timeout <! >!
                                        ]]))


(defn- job
  [ch name speed count]
  (go
    (dotimes [i count]
      (when (= i 0)
        (>! ch (str name " - started")))
      (<! (timeout speed))
      (>! ch (str name " - update " (inc i) "/" count)))))

(defn demo []
  (let [t (status-tracker)]
    (job (add-job t) "one" 1000 5)
    (job (add-job t) "two" 500 100)
    (job (add-job t) "three" 250 200)
    (Thread/sleep 5000)
    (job (add-job t) "four" 1000 10)
    (job (add-job t) "five" 500 30)
    (Thread/sleep 5000)
    (job (add-job t) "six" 1000 10)
    (job (add-job t) "seven" 500 30)
    (Thread/sleep 10000)
    (close! t)))


