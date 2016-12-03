(ns com.walmartlabs.active-status.workers
  "Manage a pool for worker CSPs, including representing the workers on a status board.

  This is a common use case for the status board and an asynchronous work flow: to spread
  some form of work across multiple CSPs to achieve better throughput than is possible
  using a single thread (because the CSPs will spend most of their time parked, waiting
  for some form of disk or network I/O to complete)."
  {:added "0.1.15"}
  (:require
    [clojure.core.async :as async :refer [chan put! close!]]
    [com.walmartlabs.active-status :as as]))

(defn run-workers
  "Runs a number of workers CSPs in parallel.

  The worker-constructor function is a constructor for CSPs.
  It is passed a worker id, and creates a worker CSP and returns a channel.
  Worker ids are numbered from 1.

  The channels are merged together; the merged channel is returned.

  Typically, each worker CSP is a `go` block that returns no value;
  the merge of that is a channel that closes only after every worker terminates.

  Alternately, the workers can convey a series of values through their channels
  before closing their individual channels.
  The order in which such values are conveyed through the merged channel is not deterministic."
  [worker-count worker-constructor]
  (async/merge
    (for [i (range worker-count)]
      (worker-constructor (inc i)))))

(defn ^:private side-effect-at-end
  "A transducer that does nothing, but has a side-effect at the end."
  [side-effect-fn]
  (fn [xf]
    (fn
      ([] xf)
      ([result input] (xf result input))
      ;; Cleanup!
      ([result]
       (side-effect-fn)
       (xf result)))))

(defn wrap-with-status
  "Wraps a worker constructor as a worker builder that reports status using the status-board.

  A worker constructor is passed a status-board job channel (instead of a worker id), and returns a result channel.
  wrap-with-status exists to create a constructor that's compatible with [[run-workers]].

  status-board is an Active Status Board; a job will be added for each worker,
  and ultimately closed (after the worker CSP completes).

  The job's channel is passed to the worker-constructor function, which returns a channel.
  The worker process may simply close the channel when work is complete, or may
  convey a series of values through the channel.

  The worker process can write its own updates to the status-board job channel passed to it;
  typically this identifies the work being performed.

  Returns a channel that conveys any result values from the worker process, and closes when the
  worker process result channel closes.

  Usage:

      (defn migrate-data
        [connection work-ch job-ch]
        (go
          (loop []
            (when-let [v (<! work-ch)]
              (>! job-ch v) ; Update status board with current work
              ...           ; do some work
              (recur)))))

      (defn driver
        \"Does all the work on the provided work-ch, using the connection, and 10
        worker processes. Returns a channel that closes when all work is complete.\"
        [status-board connection work-ch]
        (run-workers 10 (wrap-with-status status-board #(migrate-data connection work-ch %)))

  This is, of course, just a sketch, as it doesn't show where the channel of work comes
  from, or what the actual work is.

  options:

  :prefix (default `worker %2d:`)
  : Status job prefix, generated from the worker id (numbered from 1).

  :initial-text (default `waiting`)
  : Initial text for the worker (the worker receives the job channel and may make
    any other updates to it).

  :complete-text (default `done`)
  : Text written to the worker's job channel after the worker process closes its channel."
  ([status-board worker-constructor]
   (wrap-with-status status-board worker-constructor nil))
  ([status-board worker-constructor options]
   (let [{:keys [prefix initial-text complete-text]
          :or {prefix "worker %2d: "
               initial-text "waiting"
               complete-text "done"}} options]
     (fn [worker-id]
       (let [job-ch (doto
                      (as/add-job status-board {:prefix (format prefix worker-id)})
                      (put! initial-text))
             worker-ch (worker-constructor job-ch)
             finish #(do
                      (put! job-ch complete-text)
                      (close! job-ch))]
         (async/pipe worker-ch
                     (chan 1 (side-effect-at-end finish))
                     true))))))
