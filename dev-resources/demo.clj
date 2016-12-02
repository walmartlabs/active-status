(ns demo
  (:use clojure.repl)
  (:require [clojure.core.async :refer [close! go go-loop timeout <! >! >!! <!!]]
            [com.walmartlabs.active-status.minimal-board :refer [minimal-status-board]]
            [com.walmartlabs.active-status :refer :all :as as]
            [com.walmartlabs.active-status.progress :refer [elapsed-time-job]])
  (:import [java.util UUID]))

(defn- job
  ([ch name speed count]
   (job ch name speed count :normal false))
  ([ch name speed count status progress]
   (go
     (>! ch (-> (UUID/randomUUID) (str " ") set-prefix))
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

(defn demo
  ([]
    (demo (console-status-board)))
  ([t]
   (elapsed-time-job t)
   (let [j (add-job t {:status :success :pinned true})]
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
     (shutdown! t))))

(defn progress-job
  [t message target delay]
  (let [job (add-job t)]
    (go
      (>! job message)
      ;; Uncomment to test per-job progress formatting:
      #_ (>! job (set-progress-formatter (fn [{:keys [::as/current ::as/target]}]
                                        (str " " current "/" target))))
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
      #_ (when (.contains message "speed")
        (binding [*out* *err*] (println "Turbine go boom!"))
        (throw (RuntimeException. "Turbine failure.")))
      (>! job (str message " \u2713"))
      (>! job (change-status :success))
      #_ (println (Date.) "job" message "- completed")
      (<! (timeout delay))
      (close! job))))

(defn start-batmobile
  ([]
   (start-batmobile (console-status-board)))
  ([t]
   (with-output-redirected "batmobile"
     (<!! (go
            (let [channels [(simple-job t "Atomic turbines to speed" 2000)
                            (progress-job t "Loading Bat-fuel" 15 250)
                            (progress-job t "Rotating Batmobile platform" 180 10)
                            (simple-job t "Initializing on-board Bat-computer" 1000)
                            (go
                              (<! (timeout 1000))
                              (add-job t {:status :warning
                                          :prefix "Alert: "
                                          :summary "Please fasten your Bat-seatbelts"}))]]
              (doseq [ch channels]
                (<! ch)                                           ; wait for each sub-job to finish
                ))))
     (shutdown! t))))
