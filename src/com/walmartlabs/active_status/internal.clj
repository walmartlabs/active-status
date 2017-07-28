(ns ^:no-doc com.walmartlabs.active-status.internal
  "Internal utilities, not for reuse. Subject to change with out notice."
  (:require
    [clojure.core.async :refer [put! chan]]))

(defn map-vals
  [f coll]
  (reduce-kv (fn [coll k v]
               (assoc coll k (f v)))
             (empty coll)
             coll))

(defn map-keys
  [f coll]
  (when coll
    (reduce-kv (fn [coll k v]
                 (assoc coll (f k) v))
               (empty coll)
               coll)))

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
  (let [job-ch (chan 3)
        fix-keys (fn [m]
                   (map-keys #(->> %
                                   name
                                   (keyword "com.walmartlabs.active-status")) m))]
    (put! board-ch (-> options
                       (select-keys [:status :pinned :prefix :summary])
                       fix-keys
                       (assoc ::channel job-ch)))
    job-ch))

(def channel-for-job ::channel)
