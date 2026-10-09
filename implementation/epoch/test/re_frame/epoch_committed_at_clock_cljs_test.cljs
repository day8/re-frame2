(ns re-frame.epoch-committed-at-clock-cljs-test
  "CLJS coverage for the live clock feeding durable `:committed-at` values.

  Scripted JVM tests prove causal-time threading but cannot distinguish the
  CLJS wall clock (`js/Date.now`) from the origin-relative performance clock.
  This suite drives an unscripted event through the real router and asserts the
  stored causal time is near wall-clock epoch milliseconds. The plain-atom
  adapter is sufficient because the clock read occurs at envelope construction,
  independently of rendering. That the two CLJS clocks read different classes,
  which the band below relies on, is checked by
  `re-frame.cofx-router-stamp-clock-cljs-test/clock-class-discriminator-sanity-cljs`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Publishes the epoch late-bind hooks this test reads through.
            [re-frame.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest committed-at-unscripted-dispatch-is-wall-clock-epoch-cljs
  (testing "an UNSCRIPTED dispatch (no :rf.cofx) flows through the live
            router, which stamps the causal :time-ms from the host clock; the
            epoch record's :committed-at MUST be a wall-clock epoch ms (close
            to js/Date.now), NOT a perf-clock origin-relative number. A
            regression swapping interop/epoch-now-ms -> interop/now-ms at the
            causal boundary is JVM-benign but lands :committed-at ~1e4 here,
            far outside the band."
    (rf/reg-event :clk/init (fn [_ _] {:db {:n 0}}))
    (let [before (js/Date.now)]
      (rf/dispatch-sync [:clk/init])
      (let [after        (js/Date.now)
            committed-at (:committed-at (last (rf/epoch-history :rf/default)))]
        ;; ~10s slack either side for slow CI.
        (is (and (>= committed-at (- before 10000))
                 (<= committed-at (+ after 10000)))
            ":committed-at lands in the bracketing js/Date.now() band — it IS
             the live wall clock, not a stale or perf-relative read")))))
