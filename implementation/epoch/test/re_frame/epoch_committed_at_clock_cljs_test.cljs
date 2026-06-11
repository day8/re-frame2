(ns re-frame.epoch-committed-at-clock-cljs-test
  "rf2-1t30y7 — LIVE-path regression: the durable epoch record `:committed-at`
  must be WALL-CLOCK epoch ms (sourced from `interop/epoch-now-ms` =
  `js/Date.now()` on CLJS, ~1.78e12), NOT the perf clock (`interop/now-ms` =
  `performance.now()`, origin-relative ~1e4).

  WHY A NEW SUITE: every existing `:committed-at` test
  (`committed-at-comes-from-supplied-token-time-ms`,
  `committed-at-replay-stable-across-wall-clock-drift`,
  `committed-at-each-child-event-reads-its-own-token`, …) lives in
  `re-frame.epoch-test` — a `.clj` (JVM-ONLY) file — and SCRIPTS the
  committing token's `:rf.world/inputs` `:time-ms` to an explicit sentinel
  (pinning BOTH `now-ms` AND `epoch-now-ms` with `with-redefs`). That proves
  the causal-time THREADING (token `:time-ms` -> `:committed-at`, replayable,
  no ambient assembly-time read), but it deliberately bypasses the live
  router clock read that decides WHICH clock surface feeds an UNSCRIPTED
  dispatch's `:time-ms`.

  On the JVM `now-ms` and `epoch-now-ms` are both wall-clock-ish, so a
  regression that swaps `interop/epoch-now-ms` -> `interop/now-ms` at the
  router's causal boundary (`re-frame.router/build-envelope`, the one read
  that durable writes fold) stays GREEN on every JVM + scripted-token suite,
  and only breaks CLJS — where `now-ms` is `performance.now()`
  (origin-relative, ~1e4) and `epoch-now-ms` is `js/Date.now()` (~1.78e12).
  This is EXACTLY the gap that let the resources `:completed-at` perf-clock
  bug (rf2-2elcw3) slip through until a dedicated CLJS suite
  (`resources_http_completed_at_clock_cljs_test.cljs`) was added.

  This suite drives the REAL router: it dispatches an UNSCRIPTED event (no
  `:rf.world/inputs` supplied), lets the genuine `build-envelope` stamp the
  causal `:time-ms` from the host clock, and asserts the resulting durable
  epoch record `:committed-at` is a wall-clock epoch value (within ~10s of
  `js/Date.now()`), NOT a small perf-clock origin-relative number. A clock
  swap would land `:committed-at` ~12 orders of magnitude low (~1e4 vs
  ~1.78e12) and fail the band assertion on CLJS.

  ns ends in `-cljs-test` so shadow-cljs `:node-test` picks it up via
  `:ns-regexp \"cljs-test$\"`. The `:node-test` build has no DOM /
  Reagent reactive context, so this uses the plain-atom adapter — matching
  the JVM epoch fixture and `re-frame.epoch-cljs-test`. The clock read under
  test lives at the router boundary and is adapter-independent, so the choice
  of substrate does not weaken the pin."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; Sanity floor: a wall-clock epoch ms is far above any plausible
;; `performance.now()` origin-relative value. `js/Date.now()` is ~1.78e12 as
;; of 2026; `performance.now()` is at most the page/process uptime in ms (a
;; long-lived process is still well under 1e12 — that is ~31 years of
;; uptime). 1e12 is a generous, host-independent discriminator between the
;; two clock CLASSES.
(def ^:private wall-clock-floor 1e12)

(deftest committed-at-unscripted-dispatch-is-wall-clock-epoch-cljs
  (testing "rf2-1t30y7 — an UNSCRIPTED dispatch (no :rf.world/inputs) flows
            through the live router, which stamps the causal :time-ms from
            the host clock; the resulting epoch record :committed-at MUST be
            a wall-clock epoch ms (close to js/Date.now), NOT a perf-clock
            origin-relative number. A regression swapping
            interop/epoch-now-ms -> interop/now-ms at the causal boundary is
            JVM-benign but would land :committed-at ~12 orders of magnitude
            low here and fail this band — the blind spot rf2-2elcw3 exposed
            for resources, now closed for the durable :committed-at field."
    (rf/reg-event-db :clk/init (fn [_ _] {:n 0}))
    (rf/reg-event-db :clk/inc  (fn [db _] (update db :n inc)))

    ;; Capture js/Date.now() bracketing the dispatch so the comparison band
    ;; is tight and robust to scheduling. The dispatch is intentionally
    ;; UNSCRIPTED — no :rf.world/inputs — so build-envelope performs the live
    ;; clock read that is the unit under test.
    (let [before (js/Date.now)]
      (rf/dispatch-sync [:clk/init])
      (rf/dispatch-sync [:clk/inc])
      (let [after        (js/Date.now)
            history      (rf/epoch-history :rf/default)
            r            (last history)
            committed-at (:committed-at r)]
        (is (= 2 (count history))
            "one record per drain-settle (sanity: the dispatches landed)")
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
    (is (> (interop/epoch-now-ms) wall-clock-floor)
        "epoch-now-ms is a wall-clock epoch ms on CLJS (js/Date.now)")
    (is (< (interop/now-ms) wall-clock-floor)
        "now-ms is an origin-relative perf time on CLJS (performance.now),
         below the wall-clock floor — distinct CLASS from epoch-now-ms, so a
         swap is observable on this runtime (JVM-benign)")))
