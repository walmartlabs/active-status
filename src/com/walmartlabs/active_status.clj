(ns com.walmartlabs.active-status
  (:require [clojure.core.async :refer [chan close! dropping-buffer go-loop put! alt! pipeline]]
            [io.aviso.toolchest.macros :refer [cond-let]]
            [medley.core :as medley]
            [io.aviso.ansi :as ansi]))

(defn- millis [] (System/currentTimeMillis))

(defn- increment-line
  [job]
  (update job :line inc))

(defn- setup-new-job
  [jobs composite-ch job-ch]
  (println)                                                 ; Add a new line for the new job
  ;; Convert each value into a tuple of job-ch and update value, and push that
  ;; value into the composite channel.
  (pipeline 1 composite-ch
            (map #(vector job-ch %))
            job-ch
            false)
  (as-> jobs %
        (medley/map-vals increment-line %)
        (assoc % job-ch {:line    1
                         :updated (millis)})))

(defn- update-jobs-status
  [jobs job-ch value]
  ;; A nil indicates that the channel closed, but we still keep the final
  ;; status message for the job around. For now, we also have a leak:
  ;; we keep a reference to the closed channel (until th(e tracker itself
  ;; is shutdown).
  (if value
    (update jobs job-ch
            assoc :status value
            :updated (millis))
    jobs))

(defn- output-jobs
  [dim-after-millis jobs]
  (let [now (millis)]
    (doseq [{:keys [line status updated]} (vals jobs)]
      (print (str ansi/csi "s"                              ; save cursor position
                  ansi/csi line "A"                         ; cursor up
                  ansi/csi "1G"                             ; cursor horizontal absolute
                  ansi/csi "K"                              ; clear to end-of-line
                  (if (>= (- now updated) dim-after-millis)
                    ansi/white-font
                    ansi/bold-white-font)
                  status
                  ansi/reset-font
                  ansi/csi "u"                              ; restore cursor position
                  ))
      (flush))))

(defn- start-process
  [new-jobs-ch configuration]
  ;; jobs is keyed on job channel, value is the data about that job
  (let [{:keys [dim-after-millis]} configuration
        composite-ch (chan 1)]
    (go-loop [jobs {}]
      (alt!
        new-jobs-ch ([v]
                      (when v
                        (recur (setup-new-job jobs composite-ch v))))

        composite-ch ([[job-ch value]]
                       (let [jobs' (update-jobs-status jobs job-ch value)]
                         (output-jobs dim-after-millis jobs')
                         (recur jobs')))))))

(def default-configuration
  "The configuration used (by default) for a status tracker.

  :dim-after-millis
  : Milliseconds after which the status dims from bold to normal; defaults to 1000 ms."
  {:dim-after-millis 1000})

(defn status-tracker
  "Creates a new status tracker ... you should only have one of these at a time
  (or they will interfere with each other).

  The tracker runs as a core.async process; a channel is returned.

  Close the returned channel to shut down the status tracker immediately.

  Otherwise, the returned channel is used when adding jobs to the tracker via
  [[add-job]].

  configuration
  : The configuration to use for the tracker, defaulting to [[default-configuration]]."
  ([]
   (status-tracker default-configuration))
  ([configuration]
   (let [ch (chan 1)]
     (start-process ch configuration)
     ch)))

(defn add-job
  "Adds a new job, returning a channel for updates to the job.

  You may push updates into the job, or close it (to terminate the job).

  A terminated job will stay visible"
  [tracker-ch]
  (let [ch (chan (dropping-buffer 1))]
    (put! tracker-ch ch)
    ch))


