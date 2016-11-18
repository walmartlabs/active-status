(ns com.walmartlabs.active-status.api
  (:require [clojure.core.async :as async]
            [clojure.spec :as s]
            [com.walmartlabs.active-status :as as]))

(def ^:private global-status-board (atom nil))

(def ^:private global-job-channels (atom {}))

(defn active-board? []
  (not (nil? @global-status-board)))

(defn init-status-board [board]
  (reset! global-status-board board))

(defn shutdown []
  (when (active-board?)
    (as/shutdown! @global-status-board)
    (reset! global-status-board nil)
    (reset! global-job-channels {})))

(defn update-job
  "Update job with id of job-id according to update-payload

   job-id: a string or keyword
   update-payload: either a String or an update function
   -  String: will update the :com.walmartlabs.active-status/summary field
   -  update function: will update job properties (eg. change-status, start-progress)"
  [job-id update-payload]
  (when (active-board?)
    (when-let [ch (get @global-job-channels job-id)]
      (async/>!! ch update-payload))))

(defn add-job
  "Add a job with the given job-id and options.

   job-id: a string or keyword
   options: a map with either of the following properties:
   - status: keyword; the initial status for the job (:normal, :success, :warning, :error)
   - pinned: boolean; whether to pin the output of this job to the top of the active list

   Returns a convenience function which wraps update-job with the current job-id.
   The usage of that function is similar to update-job. See the doc for update-job."
  ([job-id]
   (add-job job-id nil))
  ([job-id options]
   (when (active-board?)
     (let [ch (as/add-job @global-status-board options)]
       (swap! global-job-channels assoc job-id ch)))
   (partial update-job job-id)))

(defn stop-job [job-id]
  (when (active-board?)
    (when-let [ch (get @global-job-channels job-id)]
      (async/close! ch)
      (swap! global-job-channels dissoc job-id)))
  nil)
