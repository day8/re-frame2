(ns day8.re-frame2-xray.test-support-cljs-test
  "Regression coverage for the single-helper reset surface (rf2-sdqsla).

  `test-support/reset-all!` is the one fixture call Xray tests use to
  leave every process-global in a clean, re-installable state. The key
  regression this guards: BEFORE rf2-sdqsla `reset-all!` cleared only
  the install/registry idempotency sentinels — NOT the trace-collector
  rings (process-global `defonce` atoms). A fixture that forgot the
  separate `trace-collector/reset-for-test!` therefore leaked trace
  rows into the next test (order-dependent bleed). `reset-all!` now
  folds the trace-collector reset in; these tests assert it."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.test-support :as test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(use-fixtures :each
  (fn [test-fn]
    (test-support/reset-all!)
    (test-fn)
    (test-support/reset-all!)))

(defn- seed-trace-row!
  "Push one synthetic trace row into the frameless ring so the
  collector holds observable state (test-only ingest, bypasses gates)."
  []
  (trace-collector/seed-trace-for-test!
    {:rf.trace/op    :rf.event/run-start
     :rf.trace/event [:probe/event]}))

(deftest reset-all-clears-trace-collector-rings
  (testing "reset-all! clears the trace-collector rings — the
            process-global bleed the separate reset used to own"
    (seed-trace-row!)
    (is (seq (trace-collector/buffer-for-test))
        "precondition: the seeded row is present in the rings")
    (test-support/reset-all!)
    (is (empty? (trace-collector/buffer-for-test))
        "reset-all! left the trace rings empty (no cross-test bleed)")))

(deftest reset-runtime-also-clears-settings
  (testing "reset-runtime! = reset-all! PLUS the persisted settings atom"
    (config/update-setting! :general :panel-width-px 999)
    (is (= 999 (config/get-setting :general :panel-width-px))
        "precondition: the setting mutation took")
    (seed-trace-row!)
    (test-support/reset-runtime!)
    (is (empty? (trace-collector/buffer-for-test))
        "reset-runtime! cleared the trace rings (via reset-all!)")
    (is (not= 999 (config/get-setting :general :panel-width-px))
        "reset-runtime! reset the settings atom to defaults")))
