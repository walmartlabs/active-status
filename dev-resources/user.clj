(ns user
  (:use com.walmartlabs.active-status
        clojure.repl)
  (:require [clojure.core.async :refer [close! go go-loop timeout <! >! >!! <!!
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
  (let [t (console-status-board)
        j (add-job t {:status :success :pinned true})]
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
    (job (add-job t) "six" 250 100 :error true)
    (job (add-job t) "seven" 10 1000 :normal true)
    (Thread/sleep 2000)
    (>!! j "final (long) sleep")
    (Thread/sleep 15000)
    (>!! j "shutting down!")
    (Thread/sleep 1000)
    (close! j)
    (Thread/sleep 1000)
    (close! t)
    ;; Without this sleep, the repl outputs a line saying "nil",
    ;; which screws up the final output of the lines.
    (Thread/sleep 100)))

(defn reload []
  (load-file "src/com/walmartlabs/active_status.clj")
  (load-file "dev-resources/user.clj")
  (println "Reloaded."))


(defn progress-job
  [t message target delay]
  (let [job (add-job t)]
    (go
      (>! job message)
      (>! job (start-progress target))

      (dotimes [_ target]
        (<! (timeout delay))
        (>! job (progress-tick)))

      (>! job (complete-progress))
      (>! job (change-status :success))
      (<! (timeout 100))
      (close! job))))

(defn simple-job
  [t message delay]
  (let [job (add-job t)]
    (go
      (>! job (str message " ..."))
      (<! (timeout delay))
      (>! job (str message " \u2713"))
      (>! job (change-status :success))
      (<! (timeout delay))
      (close! job))))

(defn start-batmobile
  ([]
   (let [t (console-status-board)]
     (<!! (start-batmobile t))
     (close! t)
     (Thread/sleep 100)))
  ([t]
    (go
      (let [channels [(simple-job t "Atomic turbines to speed" 2000)
                      (progress-job t "Loading Bat Fuel" 15 250)
                      (progress-job t "Rotating Batmobile platform" 180 10)
                      (simple-job t "Initializing on-board Bat-computer" 1000)
                      (go
                        (<! (timeout 1000))
                        (doto (add-job t {:status :warning})
                            (>! "Please fasten your Bat-seatbelts")))]]
        (doseq [ch channels]
          (<! ch)                                           ; wait for each sub-job to finish
          )))))