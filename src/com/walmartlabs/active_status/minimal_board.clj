(ns com.walmartlabs.active-status.minimal-board
  "Simplified status board, used for testing in the Cursive REPL, which is unable to handle the
complicated terminal capabilities based cursor motion of the full console status board."
  {:added "0.1.4"}
  (:require [clojure.core.async :refer [chan go go-loop <! >! pipe close!]]
            [com.walmartlabs.active-status :as as]
            [com.walmartlabs.active-status.internal :refer [add-job-to-board channel-for-job]])
  (:import (com.walmartlabs.active_status SetPrefix)))

(defn- print-loop
  "Runs a loop that consumes [prefix summary] pairs and prints them."
  []
  (let [ch (chan)]
    (go-loop []
      (when-let [{:keys [prefix summary]} (<! ch)]
        (when summary
          (println (str prefix summary)))
        (recur)))
    ch))

(defprotocol ^:no-doc UpdateModel

  (update-model [this model]
    "Use this value to upodate the simplified job model."))

(extend-protocol UpdateModel

  String
  (update-model [value model]
    (assoc model :summary value))

  SetPrefix
  (update-model [value model]
    (assoc model :prefix (:prefix value)))

  ;; Ignore everything else:
  Object
  (update-model [_ model]
    model))

(defn- job-loop
  "Runs a loop for an individual job that keeps a simplified model
  (just :prefix and :summary) and notifies the print-loop-ch
  when the model changes."
  [print-loop-ch]
  (let [job-ch (chan 10)]
    (go-loop [model nil]
      (when-let [v (<! job-ch)]
        (let [model' (update-model v model)]
          (if (= model model')
            (recur model)
            (do
              (>! print-loop-ch model')
              (recur model'))))))
    job-ch))

(defn- start-process
  [new-jobs-ch print-loop-ch]
  (go
    (loop []
      (when-let [job-def (<! new-jobs-ch)]
        (pipe (channel-for-job job-def) (job-loop print-loop-ch))
        (recur)))
    (close! print-loop-ch)))

(defn minimal-status-board
  "Creates a minimal status board.

  Jobs can be added to the minimal status board, just as with the standard status board.

  However, only a limited number of job channel values are accepted: Strings and
  prefixes (via [[set-prefix]]).  Other values are ignored.  When the prefix or summary
  changes, a fresh output line is printed to `*out*`.

  This is suitable for development use, when inside a REPL or terminal that doesn't support
  terminal capabilities; this includes, unfortunately, the Cursive IDE (at least, at the
  time of writing)."
  []
  (let [new-job-ch (chan 1)
        print-loop-ch (print-loop)]
    (start-process new-job-ch print-loop-ch)

    (reify
      as/StatusBoardTermination

      (shutdown! [_]
        (close! new-job-ch)
        ;; Technically, we're not 100% sure that all output has been written at this point.
        ;; But we can let that slide for ths minimal status board.
        nil)

      as/StatusJobInitiation

      (add-job [_]
        (add-job-to-board new-job-ch nil))

      (add-job [_ options]
        (add-job-to-board new-job-ch options)))))
