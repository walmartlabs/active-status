(ns com.walmartlabs.active-status.internal
  "Internal utilities, not for reuse. Subject to change withnout notice."
  {:no-doc true}
  (:require [clojure.core.async :refer [put! chan]]))

(defn map-vals
  [f coll]
  (reduce-kv (fn [coll k v]
               (assoc coll k (f v)))
             (empty coll)
             coll))

(defn remove-vals
  [f coll]
  (reduce-kv (fn [coll k v]
               (if (f v)
                 (dissoc coll k)
                 coll))
             coll
             coll))

(defn add-job-to-board
  [board-ch options]
  (let [job-ch (chan 3)]
    (put! board-ch (-> options
                       (select-keys [:status :pinned])
                       (assoc ::channel job-ch)))
    job-ch))

(def channel-for-job ::channel)
