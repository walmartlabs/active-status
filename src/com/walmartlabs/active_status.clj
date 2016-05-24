(ns com.walmartlabs.active-status
  "Present asynchronous status of multiple concurrent jobs within a command-line application."
  (:require [clojure.core.async :refer [chan close! sliding-buffer go-loop put! alt! pipeline go <! >! timeout]]
            [io.aviso.toolchest.macros :refer [cond-let]]
            [medley.core :as medley]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [io.aviso.ansi :as ansi])
  (:import (java.util.concurrent.atomic AtomicInteger)))

(defn- millis [] (System/currentTimeMillis))

;; The job model:
;;
;; :summary - text string to display
;; :prefix - optional prefix string before the summary
;; :pinned - if true, then updates to the model always move it to line 1 shifting others up
;; :status - optional status (:normal, :warning, etc.), affects color
;; :active - true if recently active (displayed bold), automatically cleared after a few ms
;; :closed - true once the channel is closed
;; :updated - millis of last update, used to determine when to clear :active
;; :line - lines up from cursor for the job (changes when new jobs are added, or existing jobs completed)
;; :progress - a progress model, if non-nil, progress will be displayed

;; A progress model:
;;
;; :target - target value to reach
;; :current - current value towards target
;; :created - time when progress model first created (used to compute ETA)


(defprotocol JobUpdater
  "A protocol that indicates how a particular job is updated by a particular type."
  (update-job [this job]))

(defrecord StartProgress [target]
  JobUpdater
  (update-job [_ job]
    (assoc job :progress {:current 0
                          :target  target
                          :created (millis)})))

(defrecord ProgressTick [amount]

  JobUpdater
  (update-job [_ job]
    (update-in job [:progress :current] + amount)))

(defrecord CompleteProgress []

  JobUpdater
  (update-job [_ job]
    (assoc-in job [:progress :current] (get-in job [:progress :target]))))

(defrecord ClearProgress []

  JobUpdater
  (update-job [_ job]
    (dissoc job :progress)))

(extend-protocol JobUpdater

  String
  (update-job [this job]
    (assoc job :summary this)))

(def ^:private status-to-ansi
  {:warning ansi/yellow-font
   :normal  nil
   :error   ansi/red-font
   :success ansi/green-font})

(defrecord ChangeStatus [new-status]
  JobUpdater
  (update-job [_ job]
    (assoc job :status new-status)))

(defrecord ^{:added "0.1.4"} SetPrefix [prefix]
  JobUpdater
  (update-job [_ job]
    (assoc job :prefix prefix)))

(defn- increment-line
  [job]
  (update job :line inc))

(defn- setup-new-job
  [jobs composite-ch job job-id]
  (println)                                                 ; Add a new line for the new job
  ;; Convert each value into a tuple of job-id and update value, and push that
  ;; value into the composite channel.
  (let [job-ch (:channel job)]
    (go-loop []
      (let [v (<! job-ch)]
        (>! composite-ch [job-id v])
        (when v
          (recur))))
    (as-> jobs %
          (medley/map-vals increment-line %)
          (assoc % job-id (assoc job :id job-id
                                     :line 1)))))

(def ^:private bar-length 30)
(def ^:private bars (apply str (repeat bar-length "=")))
(def ^:private spaces (apply str (repeat bar-length " ")))

(defn- format-progress
  [updated {:keys [current target created]}]
  (let [displayable      (and (pos? target) (pos? current))
        completed-ratio  (if displayable (/ current target) 0)
        completed-length (int (* completed-ratio bar-length))]
    (str "["
         (subs bars 0 completed-length)
         (subs spaces 0 (- bar-length completed-length))
         "]"
         (when displayable
           (let [elapsed          (- updated created)
                 remaining-millis (- (/ elapsed completed-ratio) elapsed)
                 seconds          (mod (int (/ remaining-millis 1000)) 60)
                 minutes          (int (/ remaining-millis 60000))]
             (format " ETA: %02d:%02d %3d%% %d/%d"
                     minutes
                     seconds
                     (int (* 100 completed-ratio))
                     current
                     target))))))

(def ^:dynamic ^{:added "0.1.2"} *terminal-type*
  "The terminal type used when invoking `tput`.  Defaults to the value of the
  TERM environment variable or, if not set, the explicit value 'term'."
  (or (System/getenv "TERM") "xterm"))

(defn- transmute-out
  ":out from sh can be byte[] or String."
  [s]
  (if (string? s)
    s
    (slurp s)))

(defn- tput*
  [args]
  (let [{:keys [exit out err]} (apply sh "tput" (str "-T" *terminal-type*) (mapv str args))]
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

(defn- refresh-status-board
  [progress-column old-jobs new-jobs]
  (locking [*out*]
    (doseq [[job-id job] new-jobs
            :when (not= job (get old-jobs job-id))
            :let [{:keys [line prefix summary active complete status updated progress]} job]]
      (print (str (tput "civis")                            ; make cursor invisible
                  (tput "sc")                               ; save cursor position
                  (tput "cuu" line)                         ; cursor up
                  (tput "hpa" 0)                            ; move to leftmost column
                  (tput "el")                               ; clear to end-of-line
                  (status-to-ansi status)
                  (cond
                    active
                    ansi/bold-font

                    ;; Few terminals seem to support italic out of the box, alas.
                    complete
                    ansi/italic-font)

                  prefix                                    ; when non-nil, you want a separator character
                  summary
                  (when progress
                    (str (tput "hpa" progress-column)
                         " "
                         (format-progress updated progress)))
                  ansi/reset-font
                  (tput "rc")                               ; restore cursor position
                  (tput "cvvis")                            ; make cursor visible
                  ))
      (flush))))

(defn- move-job-up
  [jobs job-id new-line]
  (cond-let
    [current-line (get-in jobs [job-id :line])]

    (= new-line current-line)
    jobs

    (= (count jobs) new-line)
    (medley/map-vals (fn [{:keys [id line] :as job}]
                       (cond
                         (= id job-id)
                         (assoc job :line new-line)

                         (< current-line line)
                         (update job :line dec)

                         :else
                         job))
                     jobs)

    :else
    (medley/map-vals (fn [{:keys [id line] :as job}]
                       (cond
                         (= job-id id)
                         ;; The dec accounts for the loss of the original
                         ;; line below the new line.
                         (assoc job :line
                                    (dec new-line))

                         (< current-line line new-line)
                         (update job :line dec)

                         ;; Line up to the new line position
                         ;; decrement, because a lower line was "deleted"
                         ;; in the move.
                         :else
                         job
                         ))
                     jobs)))

(defn- find-min-line
  [jobs pred]
  (let [matching-lines (->> jobs
                            vals
                            (filter pred)
                            (map :line))]
    (when (seq matching-lines)
      (reduce min matching-lines))))

(defn- complete-job
  "When a job completes (because the channel closed), we set its :complete flag and
  its :active flag.  We re-order it up the list, so that it is directly above
  any non-completed jobs.  Once the :active flag is cleared, the line will itself
  be removed from the jobs map entirely.

  Returns the new jobs map."
  [jobs job-id]
  (let [new-line (or
                   (find-min-line jobs :complete)
                   ;; Or move it to the last line
                   (count jobs))]
    (-> jobs
        (update job-id
                assoc :complete true :active true :updated (millis))
        (move-job-up job-id new-line))))

(def ^:private output-keys [:summary :status :progress :prefix])

(defn- adjust-if-pinned
  "Adjust the just-updated job to line 1 if necessary."
  [jobs job-pre job-id]
  (let [{:keys [pinned line active] :as job-post} (get jobs job-id)]
    (if (and pinned
             active
             (not= 1 line)
             ;; Have to be selective about comparing only things that affect the
             ;; line output.  :updated, :line, maybe :active are always changing
             ;; (and :channel never does).
             (not= (select-keys job-pre output-keys)
                   (select-keys job-post output-keys)))
      (as-> jobs %
            (medley/map-vals (fn [j]
                               (if (< (:line j) line)
                                 (update j :line inc)
                                 j))
                             %)
            (assoc % job-id (assoc job-post :line 1)))
      jobs)))

(defn- update-jobs-status
  [jobs job-id value]
  ;; A nil indicates that the channel closed, so complete the job.
  (try
    (if (some? value)
      (let [job-pre (get jobs job-id)]
        (-> jobs
            (update job-id
                    (fn [job]
                      (assoc (update-job value job)
                        :active true
                        :updated (millis))))
            (adjust-if-pinned job-pre job-id)))
      (complete-job jobs job-id))
    (catch Throwable t
      (throw (ex-info "Failure updating job status."
                      {:jobs         jobs
                       :job-id       job-id
                       :update-value value}
                      t)))))

(defn- start-refresh-process
  "A process that is periodically conveyed new jobs and figures out how to update the
  display to match.

  The jobs map is passed into the in-ch.  It is displayed, then completed jobs
  are removed and the resulting map conveyed on the returned channel."
  [configuration in-ch]
  (let [out-ch (chan)
        {:keys [progress-column]} configuration]
    (go-loop [prior-jobs {}]
      (when-let [new-jobs (<! in-ch)]
        (refresh-status-board progress-column prior-jobs new-jobs)
        (let [new-jobs' (medley/remove-vals #(and (:complete %)
                                                  (not (:active %)))
                                            new-jobs)]
          (>! out-ch new-jobs')
          ;; And back until the next jobs update is delivered
          (recur new-jobs'))))
    out-ch))

(defn- apply-dim
  [dim-after-millis job]
  (if (and (:active job)
           (< (+ (:updated job) dim-after-millis)
              (millis)))
    (assoc job :active false)
    job))


(defn- next-timeout
  "Create the next timeout after a refresh. This is nil when there are no
  active jobs (because the interval timeout is also used to mark active jobs
  inactive after a delay)."
  [jobs update-millis]
  (when (->> jobs
             vals
             (some :active))
    (timeout update-millis)))

(defn- start-process
  [new-jobs-ch configuration]
  ;; jobs is keyed on job channel, value is the data about that job
  (let [{:keys [update-millis dim-after-millis]} configuration
        composite-ch       (chan 10)
        forever-timeout-ch (chan)
        refresh-ch         (chan)
        key-source         (AtomicInteger.)
        refreshed-jobs-ch  (start-refresh-process configuration refresh-ch)]
    (go-loop [jobs {}
              interval-ch nil]
      (alt!
        new-jobs-ch
        ([v]
          (if (some? v)
            (recur (setup-new-job jobs composite-ch v (.incrementAndGet key-source))
                   ;; There's no need to update now (if there wasn't already)
                   ;; because new jobs are always just a blank line (until the first update),
                   ;; which has already been printed.
                   ;; Unfortunately, incrementing :line for all jobs means that
                   ;; the next status board refresh will be a full repaint of all jobs.
                   interval-ch)
            ;; Finalize the output and exit the go loop:
            (->> jobs
                 (medley/map-vals #(assoc % :active false
                                            :complete true))
                 (>! refresh-ch))))

        ;; We try to keep interval-ch as nil when there's no
        ;; need for an update.
        (or interval-ch forever-timeout-ch)
        (let [jobs'          (try
                               (medley/map-vals #(apply-dim dim-after-millis %) jobs)
                               (catch Throwable t
                                 (throw (ex-info "apply-dim failed"
                                                 {:jobs          jobs
                                                  :configuration configuration}
                                                 t))))
              ;; Ask it to refresh the output
              _              (>! refresh-ch jobs')
              ;; Continue after it finishes, with the revised job map
              ;; (reflecting the removal of inactive, complete jobs)
              refreshed-jobs (<! refreshed-jobs-ch)]
          (recur refreshed-jobs
                 (next-timeout refreshed-jobs update-millis)))

        composite-ch
        ([[job-ch value]]
          (recur (update-jobs-status jobs job-ch value)
                 (or interval-ch
                     (timeout update-millis))))))))

(def default-console-configuration
  "The configuration used (by default) for a console status board.

  :dim-after-millis
  : Milliseconds after which the status dims from bold to normal; defaults to 1000 ms.

  :update-millis
  : Interval at which the status board will be updated; default to 100ms.

  :progress-column
  : Column in which to display progress (which may truncate the job's summary);
    defaults to 55."
  {:dim-after-millis 1000
   :update-millis    100
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

  The job's status control the font color for the entire line.
  If progress state exists, then a progress bar is included for the job.

  The board is presented using simple text, and updates in place, using terminal capability
  strings to position the cursor, clear existing content on a line, and so forth.

  The status board runs as a core.async process.

  This function returns a channel; the channel can be passed to [[add-job]].

  Close the returned channel to shut down the status board immediately.

  Note that when testing in the REPL, you may see slightly odd output as the REPL
  will return and print nil *before* the final update to the status board. Adding
  a short sleep after closing the status board channel, but before returning, is
  useful in the REPL.

  configuration
  : The configuration to use for the status board, defaulting to [[default-configuration]].

  The console status board depends on the `tput` command to be present on the system path;
  it invokes `tput` in a sub-shell to obtain terminal capabilities.  In addition, if the `TERM`
  environment variable is not set, a default, `xterm`, is passed to `tput`."
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

  A terminated job will stay visible, but is moved up above any non-terminated jobs.
  It will highlight for a moment as well.


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
   {:pre [board-ch]}
   (let [ch (chan 3)]
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

(defn ^{:added "0.1.4"}
set-prefix
  "Returns an update value for a job to set its prefix.

  The prefix typically is used to provide an identity to a job.

  The prefix should typically end with a space, to separate it from the job's summary text.

  The prefix may be set to nil to remove it."
  [prefix]
  (->SetPrefix prefix))
