(ns com.walmartlabs.active-status.internal
  "Internal utilities, not for reuse. Subject to change withnout notice.")

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
