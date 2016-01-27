(ns com.walmartlabs.active-status
  "Manage asynchronous status of multiple concurrent jobs within a command-line application."
  (:require [clojure.core.async :refer [chan close! sliding-buffer go-loop put! alt! pipeline go <! >! timeout]]
            [io.aviso.toolchest.macros :refer [cond-let]]
            [medley.core :as medley]
            [io.aviso.ansi :as ansi]))

(defn- millis [] (System/currentTimeMillis))

;; The job model:
;;
;; :summary - text string to display
;; :status - optional status (:normal, :warning, etc.), affects color
;; :active - true if recently active (displayed bold), automatically cleared after a few ms
;; :updated - millis of last update, used to determine when to clear :active
;; :line - lines up from cursor for the job (changes when new jobs are added)

(defprotocol JobUpdater
  "A protocol that indicates how a particular job is updated by a particular type."
  (update-job [this job]))

;; Capture the updated time for a job and, if not changed at a later date,
;; clear it's active flag.
(defrecord DimAfterDelay [job-updated]

  JobUpdater
  (update-job [_ job]
    (if (= job-updated (:updated job))
      (assoc job :active false)
      job)))

(extend-protocol JobUpdater

  nil
  (update-job [_ job]
    (assoc job :active false))

  String
  (update-job [this job]
    (assoc job :active true
               :summary this)))

(def ^:private status-to-ansi
  {:warning ansi/yellow-font
   :normal  nil
   :error   ansi/red-font
   :success ansi/green-font})

(defrecord ChangeStatus [new-status]
  JobUpdater
  (update-job [_ job]
    (assoc job :active true
               :status new-status)))

(defn- increment-line
  [job]
  (update job :line inc))

(defn- apply-dim
  [job dim-after-millis]
  (if (:active job)
    (let [updated (millis)
          job-ch (:channel job)]
      (go
        (<! (timeout dim-after-millis))
        (>! job-ch (->DimAfterDelay updated)))
      (assoc job :updated updated))
    job))

(defn- setup-new-job
  [jobs composite-ch job-ch dim-after-millis]
  (println)                                                 ; Add a new line for the new job
  ;; Convert each value into a tuple of job-ch and update value, and push that
  ;; value into the composite channel.
  (go-loop []
    (when-let [v (<! job-ch)]
      (>! composite-ch [job-ch v])
      (recur)))
  (as-> jobs %
        (medley/map-vals increment-line %)
        (assoc % job-ch (-> {:line    1
                             :active  true
                             :channel job-ch}
                            (apply-dim dim-after-millis)))))

(defn- update-jobs-status
  [jobs job-ch value dim-after-millis]
  ;; A nil indicates that the channel closed, but we still keep the final
  ;; status message for the job around. For now, we also have a leak:
  ;; we keep a reference to the closed channel (until the tracker itself
  ;; is shutdown).
  (update jobs job-ch
          (fn [job]
            (-> (update-job value job)
                (apply-dim dim-after-millis)))))

(defn- refresh-output
  [old-jobs new-jobs]
  (doseq [[job-ch job] new-jobs
          :when (not= job (get old-jobs job-ch))
          :let [{:keys [line summary active status]} job]]
    (print (str ansi/csi "s"                                ; save cursor position
                ansi/csi line "A"                           ; cursor up
                ansi/csi "1G"                               ; cursor horizontal absolute
                (when active
                  ansi/bold-font)
                (status-to-ansi status)
                summary                                     ; Primary text for the job
                ansi/reset-font
                ansi/csi "K"                                ; clear to end-of-line
                ansi/csi "u"                                ; restore cursor position
                ))
    (flush)))

(defn- start-process
  [new-jobs-ch configuration]
  ;; jobs is keyed on job channel, value is the data about that job
  (let [{:keys [dim-after-millis]} configuration
        composite-ch (chan 1)]
    (go-loop [jobs {}]
      (alt!
        new-jobs-ch ([v]
                      (if (some? v)
                        (recur (setup-new-job jobs composite-ch v dim-after-millis))
                        ;; Finalize the output and exit the go loop:
                        (let [jobs' (medley/map-vals #(assoc % :active false)
                                                     jobs)]
                          (refresh-output jobs jobs'))))

        composite-ch ([[job-ch value]]
                       (let [jobs' (update-jobs-status jobs job-ch value dim-after-millis)]
                         (refresh-output jobs jobs')
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

  You may push updates into the job's channel, or close the channel, to terminate the job.

  The primary job update is just a String, which changes the summary text for the job.

  When a job is changed in any way, it will briefly be highlighted (in bold font).
  If not updated for a set period of time (by default, 1 second) it will then dim (normal font).

  A terminated job will stay visible"
  [tracker-ch]
  (let [ch (chan (sliding-buffer 2))]
    (put! tracker-ch ch)
    ch))

(defn change-status
  "Returns a job update value that changes the status of the job (this does not affect
  its summary message or other properties).

  The status of a job will affect the overall color of the job's line.

  value
  : One of: :normal (the default), :success, :warning, :error."
  [value]
  (->ChangeStatus value))
