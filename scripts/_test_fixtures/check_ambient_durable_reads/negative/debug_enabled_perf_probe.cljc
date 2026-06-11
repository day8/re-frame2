(ns fixtures.debug-enabled-perf-probe
  "NEGATIVE fixture: a performance probe gated on `interop/debug-enabled?`. The
  `now-ms` reads measure elapsed time for the dev-only trace, never a durable
  write. The `interop/debug-enabled?` wrapper in the window allowlists the
  pattern. Even though an `:updated-at` durable key sits nearby, the probe is
  exempt. Must stay GREEN (0 findings)."
  (:require [re-frame.interop :as interop]))

(defn reduce-with-probe
  [db reducer]
  (let [t0      (when interop/debug-enabled? (interop/now-ms))
        result  (reducer db)
        elapsed (when interop/debug-enabled? (- (interop/now-ms) t0))]
    {:result     result
     :updated-at (interop/now-ms)   ;; allowlisted by the debug-enabled? window
     :elapsed-ms elapsed}))
