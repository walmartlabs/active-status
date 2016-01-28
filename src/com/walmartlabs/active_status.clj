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
;; :progress - a progress model, if non-nil, progress will be displayed

;; A progress model:
;; :target - target value to reach
;; :current - current value towards target
;; :created - time when progress model first created


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

(defrecord StartProgress [target]
  JobUpdater
  (update-job [_ job]
    (assoc job :active true
               :progress {:current 0
                          :target  target
                          :created (millis)})))

(defrecord ProgressTick [amount]

  JobUpdater
  (update-job [_ job]
    (-> job
        (assoc :active true)
        (update-in [:progress :current] + amount))))

(defrecord CompleteProgress []

  JobUpdater
  (update-job [_ job]
    (-> job
        (assoc :active true)
        (assoc-in [:progress :current] (get-in job [:progress :target])))))

(defrecord ClearProgress []

  JobUpdater
  (update-job [_ job]
    (dissoc job :progress)))

(extend-protocol JobUpdater

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
  [jobs composite-ch job-ch]
  (println)                                                 ; Add a new line for the new job
  ;; Convert each value into a tuple of job-ch and update value, and push that
  ;; value into the composite channel.
  (go-loop []
    (let [v (<! job-ch)]
      (>! composite-ch [job-ch v])
      (when v
        (recur))))
  (as-> jobs %
        (medley/map-vals increment-line %)
        (assoc % job-ch {:line    1
                         :active  true
                         :channel job-ch})))

(def ^:private bar-length 30)
(def ^:private bars (apply str (repeat bar-length "=")))
(def ^:private spaces (apply str (repeat bar-length " ")))

(defn- format-progress
  [updated {:keys [current target created]}]
  (let [displayable (and (pos? target) (pos? current))
        completed-ratio (if displayable (/ current target) 0)
        completed-length (int (* completed-ratio bar-length))]
    (str "["
         (subs bars 0 completed-length)
         (subs spaces 0 (- bar-length completed-length))
         "]"
         (when displayable
           (let [elapsed (- updated created)
                 remaining-millis (- (/ elapsed completed-ratio) elapsed)
                 seconds (mod (int (/ remaining-millis 1000)) 60)
                 minutes (int (/ remaining-millis 60000))]
             (format " ETA: %02d:%02d %3d%% %d/%d"
                     minutes
                     seconds
                     (int (* 100 completed-ratio))
                     current
                     target))))))

(defn- refresh-output
  [progress-column old-jobs new-jobs]
  (doseq [[job-ch job] new-jobs
          :when (not= job (get old-jobs job-ch))
          :let [{:keys [line summary active status updated progress]} job
                ]]
    (print (str ansi/csi "s"                                ; save cursor position
                ansi/csi line "A"                           ; cursor up
                ansi/csi "1G"                               ; cursor horizontal absolute
                (when active
                  ansi/bold-font)
                (status-to-ansi status)
                summary                                     ; Primary text for the job
                ansi/csi "K"                                ; clear to end-of-line
                (when progress
                  (str ansi/csi progress-column "G"
                       " "
                       (format-progress updated progress)))
                ansi/reset-font
                ansi/csi "u"                                ; restore cursor position
                ))
    (flush)))

(defn- complete-job
  "When a job completes, we want to clear its :active flag, but also bubble it up
  in terms of lines, so that any non-completed job is lower on the screen.

  We can then do a refresh, and finally, discard the completed job.

  Returns the updated jobs, with the completed channel removed."
  [jobs job-ch refresh-output]
  ; (println "complete-job" (-> (get jobs job-ch) (dissoc :channel)))
  (let [completed-line (get-in jobs [job-ch :line])
        ;; Any active lines between the completed job and the end of the list
        ;; need to move down one.
        fix-line (fn [j]
                   (let [line (:line j)]
                     (if (> line completed-line)
                       (assoc j :line (dec line))
                       j)))
        jobs' (as-> jobs %
                   (medley/map-vals fix-line %)
                   (update % job-ch
                              assoc :active false
                              :line (count jobs)))]
    (refresh-output jobs jobs')
    ;; And now we no longer need the job (or its channel)
    (dissoc jobs' job-ch)))

(defn- update-jobs-status
  [jobs job-ch value dim-after-millis refresh-output]
  ;; A nil indicates that the channel closed, so complete the job.
  (try
    (if (some? value)
      (let [jobs' (update jobs job-ch
                          (fn [job]
                            (-> (update-job value job)
                                (apply-dim dim-after-millis))))]
        (refresh-output jobs jobs')
        jobs')
      (complete-job jobs job-ch refresh-output))
    (catch Throwable t
      (throw (ex-info "Failure updating job status."
                      {:jobs         jobs
                       :job-ch       job-ch
                       :update-value value}
                      t)))))

(defn- start-process
  [new-jobs-ch configuration]
  ;; jobs is keyed on job channel, value is the data about that job
  (let [{:keys [dim-after-millis progress-column]} configuration
        composite-ch (chan 1)
        refresh-output' (partial refresh-output progress-column)]
    (go-loop [jobs {}]
      (alt!
        new-jobs-ch ([v]
                      (if (some? v)
                        (recur (setup-new-job jobs composite-ch v))
                        ;; Finalize the output and exit the go loop:
                        (let [jobs' (medley/map-vals #(assoc % :active false)
                                                     jobs)]
                          (refresh-output' jobs jobs'))))

        composite-ch ([[job-ch value]]
                       (recur (update-jobs-status jobs job-ch value dim-after-millis refresh-output')))))))

(def default-configuration
  "The configuration used (by default) for a status tracker.

  :dim-after-millis
  : Milliseconds after which the status dims from bold to normal; defaults to 1000 ms.

  :progress-column
  : Column in which to display progress (which may truncate the job's summary);
    defaults to 45."
  {:dim-after-millis 1000
   :progress-column  55})

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

  A terminated job will stay visible, but is moved up above any non-terminated jobs."
  [tracker-ch]
  (let [ch (chan (sliding-buffer 5))]
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

(defn start-progress
  "Returns a job update value that start progress torwards the indicated total."
  [target]
  (->StartProgress target))

(defn progress-tick
  "Returns an update value for a job to increment its progress. The default amount is 1."
  ([] (progress-tick 1))
  ([amount]
   (->ProgressTick amount)))

(defn complete-progress
  "Returns an update value for a job to complete its progress (setting its current
  amount to its target amount).

  This is especially useful given that, on a loaded system, the occasional job update
  value may be discarded."
  []
  (->CompleteProgress))

(defn clear-progress
  "Returns an update value for a job to clear the progress entirely, removing the progress bar."
  []
  (->ClearProgress))