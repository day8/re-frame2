(ns fixtures.debug-enabled-perf-probe
  "NEGATIVE fixture: a performance probe gated on `interop/debug-enabled?`. The
  `now-ms` reads measure elapsed time for the dev-only trace; they bind to local
  names (`t0` / `elapsed`) and write NO durable field key, so they never match
  the violating `:durable-key <ambient-read>` shape in the first place. The
  durable `:updated-at` here is threaded from the reply token's causal
  `:rf.cofx` `:rf/time-ms` (the CORRECT pattern), NOT a fresh ambient read.

  There is no generic `interop/debug-enabled?` window allowlist — a debug
  probe being merely NEAR a durable write does not exempt that write. This
  fixture must stay GREEN (0 findings) on its own merits: the
  probe reads write no durable key, and the durable `:updated-at` reads the
  causal token. Re-writing `:updated-at (interop/now-ms)` here
  would correctly FLAG."
  (:require [re-frame.interop :as interop]))

(defn reduce-with-probe
  [db reducer reply-token]
  (let [t0      (when interop/debug-enabled? (interop/now-ms))
        result  (reducer db)
        elapsed (when interop/debug-enabled? (- (interop/now-ms) t0))]
    {:result     result
     ;; durable timestamp threaded from the causal token — NOT an ambient read
     :updated-at (get-in reply-token [:rf.cofx :rf/time-ms])
     :elapsed-ms elapsed}))
