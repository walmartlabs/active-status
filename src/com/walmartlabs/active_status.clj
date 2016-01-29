(ns com.walmartlabs.active-status
  "Manage asynchronous status of multiple concurrent jobs within a command-line application."
  (:require [clojure.core.async :refer [chan close! sliding-buffer go-loop put! alt! pipeline go <! >! timeout]]
            [io.aviso.toolchest.macros :refer [cond-let]]
            [medley.core :as medley]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [io.aviso.ansi :as ansi]))

(defn- millis [] (System/currentTimeMillis))

;; The job model:
;;
;; :summary - text string to display
;; :pinned - if true, then updates to the model always move it to line 1 shifting others up
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
  [jobs composite-ch job]
  (println)                                                 ; Add a new line for the new job
  ;; Convert each value into a tuple of job-ch and update value, and push that
  ;; value into the composite channel.
  (let [job-ch (:channel job)]
    (go-loop []
      (let [v (<! job-ch)]
        (>! composite-ch [job-ch v])
        (when v
          (recur))))
    (as-> jobs %
          (medley/map-vals increment-line %)
          (assoc % job-ch (assoc job :line 1
                                     :active true)))))

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

(defn- transmute-out
  ":out from sh can be byte[] or String."
  [s]
  (if (string? s)
    s
    (slurp s)))

(defn- tput*
  [args]
  (let [terminal-type (or (System/getenv "TERM") "xterm")
        {:keys [exit out err]} (apply sh "tput" (str "-T" terminal-type) (mapv str args))]
    (if (= 0 exit)
      (transmute-out out)
      (binding [*out* *err*]
        (format "Unable to invoke tput %s: %s"
                (str/join " " args)
                err)
        ""))))

(def ^:private tput
  "Invoke tput in a shell, capturing the result.  Memoized."
  (memoize
    (fn [& args]
      (tput* args))))

(defn- refresh-output
  [progress-column old-jobs new-jobs]
  (doseq [[job-ch job] new-jobs
          :when (not= job (get old-jobs job-ch))
          :let [{:keys [line summary active status updated progress]} job]]
    (print (str (tput "civis")                              ; make cursor invisible
                (tput "sc")                                 ; save cursor position
                (tput "UP" line)                            ; cursor up
                (tput "hpa" 1)                              ; move to columns
                (when active
                  ansi/bold-font)
                (status-to-ansi status)
                summary                                     ; Primary text for the job
                (tput "ce")                                 ; clear to end-of-line
                (when progress
                  (str (tput "hpa" progress-column)
                       " "
                       (format-progress updated progress)))
                ansi/reset-font
                (tput "rc")                                 ; restore cursor position
                (tput "cvvis")                              ; make cursor visible
                ))
    (flush)))

(defn- complete-job
  "When a job completes, we want to clear its :active flag, but also bubble it up
  in terms of lines, so that any non-completed job is lower on the screen.

  We can then do a refresh, and finally, discard the completed job.

  Returns the updated jobs, with the completed channel removed."
  [jobs job-ch refresh-output]
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
                    (update % job-ch assoc
                            :active false
                            :pinned false
                            :line (count jobs)))]
    (refresh-output jobs jobs')
    ;; And now we no longer need the job (or its channel)
    (dissoc jobs' job-ch)))

(defn- adjust-if-pinned
  "Adjust the just-updated job to line 1 if necessary."
  [jobs job-pre job-ch]
  (let [{:keys [pinned line active] :as job-post} (get jobs job-ch)]
    (if (and pinned
             active
             (not= 1 line)
             ;; Have to be selective about comparing only things that affect the
             ;; line output.  :updated, :line, maybe :active are always changing
             ;; (and :channel never does).
             (not= (select-keys job-pre [:summary :status :progress])
                   (select-keys job-post [:summary :status :progress])))
      (as-> jobs %
            (medley/map-vals (fn [j]
                               (if (< (:line j) line)
                                 (update j :line inc)
                                 j))
                             %)
            (assoc % job-ch (assoc job-post :line 1)))
      jobs)))

(defn- update-jobs-status
  [jobs job-ch value dim-after-millis refresh-output]
  ;; A nil indicates that the channel closed, so complete the job.
  (try
    (if (some? value)
      (let [job-pre (get jobs job-ch)
            jobs' (-> jobs
                      (update job-ch
                              (fn [job]
                                (-> (update-job value job)
                                    (apply-dim dim-after-millis))))
                      (adjust-if-pinned job-pre job-ch))]
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

(def default-console-configuration
  "The configuration used (by default) for a console status board.

  :dim-after-millis
  : Milliseconds after which the status dims from bold to normal; defaults to 1000 ms.

  :progress-column
  : Column in which to display progress (which may truncate the job's summary);
    defaults to 45."
  {:dim-after-millis 1000
   :progress-column  55})

(defn console-status-board
  "Creates a new status board suitable for use in a command-line application.

  Only a single console status board should be active at any one time; they will
  interfere with each other.

  The status board presents the status of any number of jobs.
  In this implementation, each job has a line in the status board, and
  can be updated individually and asynchronously.

  Each job has a summary message,
  an optional status (:normal, :success, :warning, :error),
  and optional progress state.

  The board is presented using simple text, and updates in place, using terminal capability
  strings (to, for example, position the cursor and clear existing content on a line).

  The presentation includes the summary message; the job's status controls
  the font (that is, the color) for the message.  If progress state
  exists, then a progress bar is included for the job.

  The status board runs as a core.async process.

  This function returns a channel; the channel can be passed to [[add-job]].

  Close the returned channel to shut down the status board immediately.

  configuration
  : The configuration to use for the tracker, defaulting to [[default-configuration]].

  The console status board depends on the `tput` command to be present on the system path;
  it invokes `tput` in a sub-shell to obtain terminal capabilities.  In addition, if the `TERM`
  environment variable is not set, a default, `xterm`, is pass to `tput`."
  ([]
   (console-status-board default-console-configuration))
  ([configuration]
   (let [ch (chan 1)]
     (start-process ch configuration)
     ch)))

(defn add-job
  "Adds a new job, returning a channel for updates to the job.

  You may put updates into the job's channel, or close the channel, to terminate the job.

  The primary job update is just a String, which changes the summary text for the job.

  Other update objects can change the visible status of the job, or enable and update
  a progress bar specific to the job. Functions such as [[change-status]] and
  [[start-progress]] return job update values that can be put into the job's channel.

  Example:

      (require '[com.walmartlabs.active-status :as as]
               '[clojure.core.async :refer [close! >!!]])

      (defn process-files [board-ch files]
        (let [job-ch (as/add-job board-ch)]
            (>!! job-ch (as/start-progress (count files)))
            (doseq [f files]
              (>!! job-ch (str \"Processing: \" f))
              (process-single-file f)
              (>!! job-ch (as/progress-tick)))
            (close! job-ch)))

  When a job is changed in any way, it will briefly be highlighted (in bold font).
  If not updated for a set period of time (by default, 1 second) it will then dim (normal font).

  On a heavily loaded system, updates into the channel may be discarded (using a sliding buffer).
  This is preferable to having jobs block or park just to report their status.

  A terminated job will stay visible, but is moved up above any non-terminated jobs.

  board-ch
  : The channel for the score board, as returned by [[console-status-board]].

  options
  : A map of additional options:

  :status
  : The initial status for the job (:normal, :success, :warning, :error).

  :pinned
  : If true, then any update to the job will move it to the first line, shifting others up.
    You rarely want more than one pinned job."
  ([board-ch]
   (add-job board-ch nil))
  ([board-ch options]
   (let [ch (chan (sliding-buffer 5))]
     (put! board-ch (-> options
                        (select-keys [:status :pinned])
                        (assoc :channel ch)))
     ch)))

(defn change-status
  "Returns a job update value that changes the status of the job (this does not affect
  its summary message or other properties).

  The status of a job will affect the overall color of the job's line on the status board.

  value
  : One of: :normal (the default), :success, :warning, :error."
  [value]
  (->ChangeStatus value))

(defn start-progress
  "Returns a job update value that starts progress torwards the indicated total.

  The job's status line will include a progress bar."
  [target]
  (->StartProgress target))

(defn progress-tick
  "Returns a job update value for a job to increment its progress. The default amount is 1."
  ([] (progress-tick 1))
  ([amount]
   (->ProgressTick amount)))

(defn complete-progress
  "Returns a job update value for a job to complete its progress (setting its current
  amount to its target amount).

  This is especially useful given that, on a very busy system, the occasional job update
  value may be discarded."
  []
  (->CompleteProgress))

(defn clear-progress
  "Returns an update value for a job to clear the progress entirely, removing the progress bar."
  []
  (->ClearProgress))