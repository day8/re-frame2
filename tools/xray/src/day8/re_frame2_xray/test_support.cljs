(ns day8.re-frame2-xray.test-support
  "Single test-fixture entry-point for Xray's process-global reset.

  Previously every test fixture called both `preload/reset-for-test!`
  and `registry/reset-for-test!` — adding a new sentinel-bearing ns
  meant updating ~30 fixtures. This ns folds those calls into one
  `reset-all!` so panel additions don't ripple through the test
  corpus.

  Targets the inert `day8.re-frame2-xray.install` ns for the collector
  sentinels (rf2-5w06uu) rather than `preload`, so a fixture that only
  needs `reset-all!` does not drag in `preload`'s side-effecting boot
  block.

  rf2-sdqsla — `reset-all!` now ALSO clears the trace-collector rings.
  Those are process-global `defonce` atoms that `registry`/`install`
  reset do NOT touch, so a test that omitted the separate
  `trace-collector/reset-for-test!` became order-dependent (cross-test
  trace bleed). Folding it in means one fixture call leaves EVERY
  Xray process-global in a clean, re-installable state. Settings /
  localStorage state is reset by the optional `reset-runtime!` (the
  settings atom is reset selectively because not every fixture wants
  its persisted-config seed cleared).

  **Test-only — never call from production code.** Per rf2-kmhvg
  cluster item 3e (audit rf2-i0veg §3e)."
  (:require [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.install :as install]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(defn reset-all!
  "Reset every process-global Xray reset surface in dependency order so
  a single fixture-call leaves the namespaces in a re-installable
  state. Calls (in order):

      install/reset-for-test!         ; trace-cb + epoch-cb reg flags
      registry/reset-for-test!        ; per-panel reg-event/reg-sub flags
      trace-collector/reset-for-test! ; frameless ring + per-frame rings

  The trace-collector rings are process-global `defonce` atoms the
  sentinel resets above do NOT touch — clearing them here closes the
  cross-test trace-bleed gap (rf2-sdqsla). Does NOT touch the settings
  atom — use `reset-runtime!` for that.

  Test-only. Returns nil."
  []
  (install/reset-for-test!)
  (registry/reset-for-test!)
  (trace-collector/reset-for-test!)
  nil)

(defn reset-runtime!
  "`reset-all!` PLUS the persisted Settings atom + localStorage payload
  (`config/reset-settings!`). The full clean-slate a runtime-mounting
  fixture wants: process-global sentinels, trace rings, AND config /
  storage state. Test-only. Returns nil."
  []
  (reset-all!)
  (config/reset-settings!)
  nil)
