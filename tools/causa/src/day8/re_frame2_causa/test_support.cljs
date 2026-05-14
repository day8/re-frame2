(ns day8.re-frame2-causa.test-support
  "Single test-fixture entry-point for Causa's idempotency sentinels.

  Previously every test fixture called both `preload/reset-for-test!`
  and `registry/reset-for-test!` — adding a new sentinel-bearing ns
  meant updating ~30 fixtures. This ns folds those calls into one
  `reset-all!` so panel additions don't ripple through the test
  corpus.

  **Test-only — never call from production code.** Per rf2-kmhvg
  cluster item 3e (audit rf2-i0veg §3e)."
  (:require [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]))

(defn reset-all!
  "Reset every Causa idempotency sentinel in dependency order so a
  single fixture-call leaves the namespaces in a re-installable state.
  Calls (in order):

      preload/reset-for-test!  ; trace-cb + epoch-cb registration flags
      registry/reset-for-test! ; per-panel reg-event/reg-sub flags

  Test-only. Returns nil."
  []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  nil)
