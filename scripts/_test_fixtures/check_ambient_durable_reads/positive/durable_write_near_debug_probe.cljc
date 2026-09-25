(ns fixtures.durable-write-near-debug-probe
  "POSITIVE fixture: a REAL durable `:updated-at (interop/now-ms)`
  write sits within a few lines of an unrelated `(when interop/debug-enabled?
  ...)` perf probe. A debug-window allowlist would let this slip past
  CI — a genuine false negative: the durable write is NOT diagnostic, it
  is merely NEAR a debug probe. With no such allowlist, the
  durable ambient read FLAGS (1 finding) as it should. A debug probe being
  nearby is not a structural guarantee the write is diagnostic."
  (:require [re-frame.interop :as interop]))

(defn install-with-nearby-probe
  [db reducer]
  (let [t0      (when interop/debug-enabled? (interop/now-ms))  ;; perf probe (no durable key)
        result  (reducer db)
        elapsed (when interop/debug-enabled? (- (interop/now-ms) t0))]
    {:result     result
     :elapsed-ms elapsed
     :updated-at (interop/now-ms)}))   ;; FLAGGED: real durable write, not the probe
