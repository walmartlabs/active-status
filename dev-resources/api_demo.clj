(ns api-demo
  (:use clojure.repl)
  (:require [clojure.core.async :refer [close! go go-loop timeout <! >! >!! <!! put!]]
            [com.walmartlabs.active-status.minimal-board :refer [minimal-status-board]]
            [com.walmartlabs.active-status.api :as api]
            [com.walmartlabs.active-status :refer :all :as as])
  (:import [java.util UUID]))

;;-------------------------------
;; demo

(defn- run-job
  ([job-id speed count]
   (run-job job-id speed count :normal false))
  ([job-id speed count status progress]
   (go
     (api/add-job job-id)
     (api/update-job job-id (-> (UUID/randomUUID) (str " ") set-prefix))
     (dotimes [i count]
       (when (= i 0)
         (api/update-job job-id (str job-id " - started"))
         (when progress
           (api/update-job job-id (start-progress count))))
       (<! (timeout speed))
       (when (= i 5)
         (api/update-job job-id (change-status status)))
       (when-not progress
         (api/update-job job-id (str job-id " - update " (inc i) "/" count)))
       (when progress
         (api/update-job job-id (progress-tick))))
     (api/update-job job-id (str job-id " - completed " count))
     (when progress
       (api/update-job job-id (complete-progress)))
     (api/stop-job job-id))))

(defn demo
  ([]
   (with-output-redirected "out"
     (let [_ (api/init-status-board (console-status-board))]
       (api/add-job :overall {:status :success :pinned true})
       (api/update-job :overall "adding one, two, three")
       (run-job "one" 100 50)
       (run-job "two" 500 100 :warning true)
       (run-job "three" 250 10 :normal true)
       (api/update-job :overall "first sleep")
       (Thread/sleep 3000)
       (api/update-job :overall "adding four, five")
       (run-job "four" 250 10 :success false)
       (run-job "five" 2000 30)
       (api/update-job :overall "second sleep")
       (Thread/sleep 3000)
       (run-job "six" 250 100 :error true)
       (run-job "seven" 10 1000 :normal true)
       (Thread/sleep 2000)
       (api/update-job :overall "final (long) sleep")
       (Thread/sleep 15000)
       (api/update-job :overall "shutting down!")
       (Thread/sleep 1000)
       (api/stop-job :overall)
       (Thread/sleep 1000)
       (api/shutdown)))))

(defn progress-job
  [job-id target delay]
  (let [job (api/add-job job-id)]
    (go
      (job job-id)
      ;; Uncomment to test per-job progress formatting:
      #_(job (set-progress-formatter (fn [{:keys [::as/current ::as/target]}]
                                                         (str " " current "/" target))))
      (job (start-progress target))

      (dotimes [_ target]
        (<! (timeout delay))
        (job (progress-tick)))

      (job (complete-progress))
      (job (change-status :success))
      (<! (timeout 100))
      (api/stop-job job-id))))

(defn simple-job
  [job-id delay]
  (let [job (api/add-job job-id)]
    (go
      (job (str job-id " ..."))
      (<! (timeout delay))
      #_(when (.contains job-id "speed")
          (binding [*out* *err*] (println "Turbine go boom!"))
          (throw (RuntimeException. "Turbine failure.")))
      (job (str job-id " \u2713"))
      (job (change-status :success))
      #_(println (Date.) "job" job-id "- completed")
      (<! (timeout delay))
      (api/stop-job job-id))))

(defn start-batmobile
  ([]
   (start-batmobile (console-status-board)))
  ([t]
   (api/init-status-board t)
   (with-output-redirected "batmobile"
     (<!! (go
            (let [channels [(simple-job "Atomic turbines to speed" 2000)
                            (progress-job "Loading Bat-fuel" 15 250)
                            (progress-job "Rotating Batmobile platform" 180 10)
                            (simple-job "Initializing on-board Bat-computer" 1000)
                            (go
                              (<! (timeout 1000))
                              (let [job (add-job t {:status :warning})]
                                (job "Please fasten your Bat-seatbelts")))]]
              (doseq [ch channels]
                (<! ch)                                     ; wait for each sub-job to finish
                ))))
     (shutdown! t))))

(comment
 (demo)
 (start-batmobile))
