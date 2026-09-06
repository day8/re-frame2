(ns re-frame.epoch-committed-at-clock-cljs-test
  "CLJS coverage for the live clock feeding durable `:committed-at` values.

  Scripted JVM tests prove causal-time threading but cannot distinguish the
  CLJS wall clock (`js/Date.now`) from the origin-relative performance clock.
  This suite drives an unscripted event through the real router and asserts the
  stored causal time is near wall-clock epoch milliseconds. The plain-atom
  adapter is sufficient because the clock read occurs at envelope construction,
  independently of rendering."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; Sanity floor: a wall-clock epoch ms is far above any plausible
;; `performance.now()` origin-relative value. `js/Date.now()` is ~1.78e12 as
;; of 2026; `performance.now()` is at most the page/process uptime in ms (a
;; long-lived process is still well under 1e12 — that is ~31 years of
;; uptime). 1e12 is a generous, host-independent discriminator between the
;; two clock CLASSES.
(def ^:private wall-clock-floor 1e12)

(deftest committed-at-unscripted-dispatch-is-wall-clock-epoch-cljs
  (testing "rf2-1t30y7 — an UNSCRIPTED dispatch (no :rf.cofx) flows
            through the live router, which stamps the causal :time-ms from
            the host clock; the resulting epoch record :committed-at MUST be
            a wall-clock epoch ms (close to js/Date.now), NOT a perf-clock
            origin-relative number. A regression swapping
            interop/epoch-now-ms -> interop/now-ms at the causal boundary is
            JVM-benign but would land :committed-at ~12 orders of magnitude
            low here and fail this band — the blind spot rf2-2elcw3 exposed
            for resources, now closed for the durable :committed-at field."
    (rf/reg-event :clk/init (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :clk/inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    ;; Capture js/Date.now() bracketing the dispatch so the comparison band
    ;; is tight and robust to scheduling. The dispatch is intentionally
    ;; UNSCRIPTED — no :rf.cofx — so build-envelope performs the live
    ;; clock read that is the unit under test.
    (let [before (js/Date.now)]
      (rf/dispatch-sync [:clk/init])
      (rf/dispatch-sync [:clk/inc])
      (let [after        (js/Date.now)
            history      (rf/epoch-history :rf/default)
            r            (last history)
            committed-at (:committed-at r)]
        (is (= 2 (count history))
            "one record per dequeued event (sanity: the dispatches landed)")
        (is (= :clk/inc (:event-id r))
            "the last record is the :clk/inc dispatch")
        (is (number? committed-at)
            ":committed-at is present on the durable record")
        ;; The CLASS assertion: wall-clock epoch ms, not a perf origin time.
        ;; Pre-swap this is true (~1.78e12); a now-ms swap lands ~1e4 (CLJS
        ;; performance.now) — far below the floor.
        (is (> committed-at wall-clock-floor)
            ":committed-at is a WALL-CLOCK epoch ms (> 1e12) — sourced from
             interop/epoch-now-ms (js/Date.now), NOT interop/now-ms
             (performance.now, origin-relative ~1e4)")
        ;; The TIGHT band: within the bracketing js/Date.now() reads (plus a
        ;; ~10s slack for slow CI). This both confirms the magnitude AND that
        ;; the value tracks the same wall clock the freshness / invalidation
        ;; readers compare against.
        (is (and (>= committed-at (- before 10000))
                 (<= committed-at (+ after 10000)))
            ":committed-at lands in the bracketing js/Date.now() band
             (within 10s) — it IS the live wall clock, not a stale or
             perf-relative read")))))

(deftest committed-at-clock-class-discriminator-sanity-cljs
  (testing "rf2-1t30y7 — corroborate the discriminator the regression
            assertion leans on: on the CLJS runtime epoch-now-ms is a
            wall-clock epoch ms (> 1e12) while now-ms (performance.now) is an
            origin-relative elapsed value BELOW the wall-clock floor. Pins
            that the two clock surfaces ARE distinguishable here — so the
            band test above genuinely catches a swap (it would be vacuous if
            both clocks read the same class, as on the JVM)."
    (is (> (rf.interop/epoch-now-ms) wall-clock-floor)
        "epoch-now-ms is a wall-clock epoch ms on CLJS (js/Date.now)")
    (is (< (rf.interop/now-ms) wall-clock-floor)
        "now-ms is an origin-relative perf time on CLJS (performance.now),
         below the wall-clock floor — distinct CLASS from epoch-now-ms, so a
         swap is observable on this runtime (JVM-benign)")))
