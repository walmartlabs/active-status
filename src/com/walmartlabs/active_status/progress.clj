(ns com.walmartlabs.active-status.progress
  "Utilties to make it easier for long-running jobs to report progress."
  {:added "0.1.15"}
  (:require
    [clojure.core.async :refer [put! timeout go <! >!]]
    [com.walmartlabs.active-status :as as]
    [clojure.string :as str]))

(defn report-progress?
  "Returns true if n is non-zero and an even multiple of interval."
  [n interval]
  (and (pos? n)
       (-> n (mod interval) zero?)))

(defn report-progress
  "Returns a transducer that counts and periodically reports the progress
  (using a status board job), including a final summary at the end.

  The interval is in terms of values that pass through the tranducer.

  The formatter is generally a string passed to `format`, along with the
  current count.  Alternately, it can be a function that is passed the count
  and returns a string.

  Technically, this is a count of how many values have passed through this
  tranducer (but could be queued elsewhere after this tranducer),
  but that is generally accurate enough for reporting progress.

  Example:

      (let [rows-ch (query-db ...)           ;; returns channel that conveys rows
            job-ch (as/add-job status-board)
            work-ch (asyc/pipe rows-ch (chan 1 (report-progress 25 job-ch \"read %,d rows\")))]
        (go-loop []
          (when-let [row (<! work-ch)]
             ...                             ;; do work with the row
             (recur))))

  This will report on the job every 25 rows, and once more (with the final count)
  when the rows-ch channel closes."
  [interval job-ch formatter]
  (let [format-fn (if (string? formatter)
                    #(format formatter %)
                    formatter)]
    (fn [xf]
      (let [counter (volatile! 0)
            report #(put! job-ch (format-fn @counter))]
        (fn
          ([] xf)
          ([result input]
           (when (report-progress? (vswap! counter inc) interval)
             (report))
           (xf result input))
          ([result]
           (report)
           (xf result)))))))

(def ^:private duration-periods
  [[(* 1000 60 60 24) "day"]
   [(* 1000 60 60) "hour"]
   [(* 1000 60) "minute"]
   [1000 "second"]])

(defn- duration-terms
  "Converts a duration, in milliseconds, to a set of terms describing the duration.
  The terms are in descending order, largest period to smallest.

  Each term is a tuple of count and period name, e.g., `[5 \"second\"]`.

  After seconds are accounted for, remaining milliseconds are ignored."
  [duration-ms]
  {:pre [(<= 0 duration-ms)]}
  (loop [remainder duration-ms
         [[period-ms period-name] & more-periods] duration-periods
         terms []]
    (cond
      (nil? period-ms)
      terms

      (< remainder period-ms)
      (recur remainder more-periods terms)

      :else
      (let [period-count (int (/ remainder period-ms))
            next-remainder (mod remainder period-ms)]
        (recur next-remainder more-periods
               (conj terms [period-count period-name]))))))

(defn format-elapsed-time
  "Formats a number of milliseconds of elapsed time into units of days, hours, minutes, and seconds."
  [millis]
  (if-let [terms (seq (duration-terms millis))]
    (->> terms
         (map (fn [[period-count period-name]]
                (str period-count
                     " "
                     period-name
                     (when (not (= 1 period-name))
                       "s"))))
         (str/join ", "))
    "less than a second"))

(defn elapsed-time-job
  "Starts a job that presents elapsed time. This continues until the status board is
   shutdown.

   Options:
   :delay-millis (default 0)
   : Delay before the job is created and first updated.

   :interval-millis (default 1000)
   : Interval between updates.

   :prefix (default `elapsed time: `)
   : Prefix for job.

   :formatter (default [[format-elapsed-time]])
   : Formats elapsed time to a string."
  ([status-board]
   (elapsed-time-job status-board nil))
  ([status-board options]
   (let [{:keys [delay-millis interval-millis prefix formatter]
          :or {delay-millis 0
               interval-millis 1000
               prefix "elapsed time: "
               formatter format-elapsed-time}} options]
     (go
       (when (pos? delay-millis)
         (<! (timeout delay-millis)))
       (let [job-ch (as/add-job status-board {:prefix prefix})
             start-millis (System/currentTimeMillis)]
         (loop []
           ;; The job channel closes when the status board is itself shutdown.
           (when (>! job-ch (formatter (- (System/currentTimeMillis) start-millis)))
             (<! (timeout interval-millis))
             (recur))))))))
