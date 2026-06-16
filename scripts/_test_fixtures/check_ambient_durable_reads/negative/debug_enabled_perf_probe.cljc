(ns fixtures.debug-enabled-perf-probe
  "NEGATIVE fixture: a performance probe gated on `interop/debug-enabled?`. The
  `now-ms` reads measure elapsed time for the dev-only trace; they bind to local
  names (`t0` / `elapsed`) and write NO durable field key, so they never match
  the violating `:durable-key <ambient-read>` shape in the first place. The
  durable `:updated-at` here is threaded from the reply token's causal
  `:rf.cofx` `:rf/time-ms` (the CORRECT pattern), NOT a fresh ambient read.

  rf2-nftz2s §3: the generic `interop/debug-enabled?` window allowlist was
  REMOVED — a debug probe being merely NEAR a durable write no longer exempts
  that write. This fixture must stay GREEN (0 findings) on its own merits: the
  probe reads write no durable key, and the durable `:updated-at` reads the
  causal token. A regression that re-wrote `:updated-at (interop/now-ms)` here
  (the old false negative) would now correctly FLAG."
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
