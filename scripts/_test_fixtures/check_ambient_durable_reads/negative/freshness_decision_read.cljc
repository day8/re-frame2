(ns fixtures.freshness-decision-read
  "NEGATIVE fixture: a freshness DECISION read (rf2-95b0lc). The reducer reads
  the live clock to DECIDE whether an entry is stale — it COMPARES the clock to
  a stored `:stale-at`, it does not WRITE the clock into a durable field. The
  ambient read is a `(now-ms)` argument to `entry-stale?`, not the value of a
  durable map-entry key, so the gate's shape does not match. Must stay GREEN
  (0 findings)."
  (:require [re-frame.interop :as interop]))

(defn entry-stale?
  [entry now]
  (when-let [stale-at (:stale-at entry)]
    (>= now stale-at)))

(defn maybe-refetch
  [db entry]
  ;; The clock is read and COMPARED here, never written durably. `:stale-at`
  ;; appears, but as the entry's stored value being read, not assigned an
  ;; ambient read.
  (if (entry-stale? entry (interop/now-ms))
    (assoc db :status :refetching)
    db))
