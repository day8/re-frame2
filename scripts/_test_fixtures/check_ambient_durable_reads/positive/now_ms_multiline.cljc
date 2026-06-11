(ns fixtures.now-ms-multiline
  "POSITIVE fixture: the durable `:stale-at` key and its ambient `now-ms` value
  are on separate lines (the common multi-line map-entry layout). The gate's
  cross-line window must still FLAG it (1 finding)."
  (:require [re-frame.interop :as interop]))

(defn mark-stale
  [entry]
  (assoc entry
         :stale-at
         (interop/now-ms)))               ;; FLAGGED across the line break
