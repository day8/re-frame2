(ns re-frame.cofx-router-stamp-clock-cljs-test
  "rf2-qz0uog (EP-0010 §Dispatch Envelope Stamping) — CLJS LIVE-path
  regression for the router's `:rf.cofx` `:rf/time-ms` stamp.

  WHY A NEW CLJS SUITE. The existing core `:rf.cofx` envelope tests
  (`re-frame.cofx-envelope-test`) are JVM-ONLY and assert only that an
  omitted `:time-ms` is stamped to a NUMBER. On the JVM `interop/now-ms`
  (elapsed) and `interop/epoch-now-ms` (wall-clock) are BOTH
  `System.currentTimeMillis`-ish, so a regression swapping the router's
  stamp source from `epoch-now-ms` -> `now-ms` (rf2-n1rh0f names
  `epoch-now-ms` as THE durable wall-clock source) stays GREEN on every
  JVM suite. On CLJS the two clocks are DISTINCT CLASSES:

    - `interop/now-ms`       = `performance.now()` — origin-relative,
                               for ELAPSED measurement (~1e4 in a fresh
                               process), NOT comparable with `js/Date`.
    - `interop/epoch-now-ms` = `js/Date.now()`     — wall-clock epoch ms
                               (~1.78e12 as of 2026), the durable causal
                               time `:rf.cofx` `:rf/time-ms` MUST be.

  A durable timestamp folded from `performance.now()` would be ~12 orders
  of magnitude too small and incomparable with the `js/Date`-based
  freshness / invalidation / staleness readers (resources `:stale-at`,
  `:invalidated-at`, epoch `:committed-at`). This is the EXACT clock-class
  bug the resources `:completed-at` perf-clock regression (rf2-2elcw3) and
  the epoch `:committed-at` regression (rf2-1t30y7) each needed a dedicated
  CLJS suite to catch. This suite closes it for the ROUTER ENVELOPE STAMP
  itself — the single causal-boundary read every durable write folds.

  It drives the REAL router: it dispatches an UNSCRIPTED event (NO
  `:rf.cofx` supplied), lets the genuine `build-envelope` stamp the
  causal `:time-ms` from the host clock, and reads the stamped map back two
  ways — (a) off the handler's `:rf.cofx` COEFFECT (the public
  handler-visible path, Spec 002 §Event Context And Coeffects), and (b) off
  the `:rf.event/dispatched` TRACE (the Xray-visible path, rf2-jt854w). Both
  must carry a WALL-CLOCK epoch ms (> 1e12, within seconds of
  `js/Date.now()`), NOT a perf-clock origin-relative value.

  ns ends in `-cljs-test` so shadow-cljs `:node-test` picks it up via
  `:ns-regexp \"cljs-test$\"`. Plain-atom adapter (the `:node-test` build
  has no DOM / Reagent reactive context); the clock read under test lives
  at the router boundary and is adapter-independent."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; Sanity floor: a wall-clock epoch ms is far above any plausible
;; `performance.now()` origin-relative value (a fresh Node process is well
;; under 1e12 ms of uptime — that is ~31 years). 1e12 is a generous,
;; host-independent discriminator between the two clock CLASSES.
(def ^:private wall-clock-floor 1e12)

(deftest router-stamps-omitted-time-ms-as-wall-clock-epoch-on-the-coeffect
  (testing "rf2-qz0uog — an UNSCRIPTED dispatch (no :rf.cofx) flows
            through the live router, which stamps the causal :time-ms from the
            host clock; the handler reads :rf.cofx off its coeffect and
            the stamped :time-ms MUST be a WALL-CLOCK epoch ms (close to
            js/Date.now), NOT a perf-clock (performance.now) origin-relative
            value. A regression swapping epoch-now-ms -> now-ms at the causal
            boundary is JVM-benign but lands ~12 orders of magnitude low here."
    (rf/reg-frame :wi.clk/cofx {:doc "ctx"})
    (let [captured (atom nil)]
      ;; A full-context interceptor :before so we read the FULL coeffect map the
      ;; handler saw — :rf.cofx is a framework coeffect alongside :db / :event.
      (rf/reg-interceptor :wi.clk/capture-probe
        {:before (fn [ctx] (reset! captured (:coeffects ctx)) ctx)})
      (rf/reg-event :wi.clk/capture
        {:interceptors [:wi.clk/capture-probe]}
        (fn [_ _] {}))
      (let [before (js/Date.now)]
        ;; intentionally UNSCRIPTED — no :rf.cofx opt — so the router's
        ;; build-envelope performs the live host-clock read under test.
        (rf/dispatch-sync [:wi.clk/capture] {:frame :wi.clk/cofx})
        (let [after    (js/Date.now)
              cofx     @captured
              rf-cofx  (:rf.cofx cofx)
              time-ms  (:rf/time-ms rf-cofx)]
          (is (some? cofx) "the handler ran and captured its coeffects")
          (is (contains? cofx :rf.cofx)
              ":rf.cofx is a framework coeffect on the handler ctx")
          (is (map? rf-cofx) ":rf.cofx is a map")
          (is (number? time-ms) ":rf/time-ms is a stamped number")
          ;; The CLASS assertion: wall-clock epoch ms, not a perf origin time.
          ;; Pre-swap ~1.78e12; a now-ms swap lands ~1e4 (performance.now) on
          ;; the CLJS runtime — far below the floor.
          (is (> time-ms wall-clock-floor)
              ":time-ms is a WALL-CLOCK epoch ms (> 1e12) — sourced from
               interop/epoch-now-ms (js/Date.now), NOT interop/now-ms
               (performance.now, origin-relative ~1e4)")
          ;; The TIGHT band: within the bracketing js/Date.now() reads (plus a
          ;; ~10s slack for slow CI). Confirms both the magnitude AND that the
          ;; stamp tracks the same wall clock the freshness readers compare
          ;; against.
          (is (and (>= time-ms (- before 10000))
                   (<= time-ms (+ after 10000)))
              ":time-ms lands in the bracketing js/Date.now() band (within
               10s) — it IS the live wall clock, not a stale or perf-relative
               read"))))))

(deftest router-stamps-omitted-time-ms-as-wall-clock-epoch-on-the-dispatched-trace
  (testing "rf2-qz0uog — the SAME omitted-time-ms stamp rides the
            :rf.event/dispatched trace (the Xray Event-lens :rf.cofx
            surface, rf2-jt854w): the trace-side :rf.cofx :time-ms is
            ALSO a wall-clock epoch ms (> 1e12), not a perf-clock value. Covers
            the router envelope-stamp path as seen by the trace stream, not
            just the handler coeffect."
    (rf/reg-frame :wi.clk/trace {:doc "ctx"})
    (rf/reg-event :wi.clk/noop (fn [{:keys [db]} _] {:db db}))
    (let [seen (atom [])]
      (rf/register-listener! :trace ::clk-probe (fn [ev] (swap! seen conj ev)))
      (let [before (js/Date.now)]
        (rf/dispatch-sync [:wi.clk/noop] {:frame :wi.clk/trace})
        (let [after     (js/Date.now)
              _         (rf/unregister-listener! :trace ::clk-probe)
              enqueue   (->> @seen
                             (filter #(= :rf.event/dispatched (:operation %)))
                             first)
              ;; the op-type-specific payload slots ride under :tags;
              ;; build-event hoists only :source to top-level (rf2-jt854w).
              rf-cofx   (get-in enqueue [:tags :rf.cofx])
              time-ms   (:rf/time-ms rf-cofx)]
          (is (some? enqueue) ":rf.event/dispatched fired")
          (is (map? rf-cofx)
              ":rf.cofx rides the dispatched trace under :tags")
          (is (number? time-ms) "the trace-side :rf/time-ms is a stamped number")
          (is (> time-ms wall-clock-floor)
              "the trace-side :rf/time-ms is a WALL-CLOCK epoch ms (> 1e12) —
               js/Date.now, NOT performance.now")
          (is (and (>= time-ms (- before 10000))
                   (<= time-ms (+ after 10000)))
              "the trace-side :time-ms lands in the bracketing js/Date.now()
               band — the same live wall clock"))))))

(deftest clock-class-discriminator-sanity-cljs
  (testing "rf2-qz0uog — corroborate the discriminator the regression
            assertions lean on: on the CLJS runtime epoch-now-ms is a
            wall-clock epoch ms (> 1e12) while now-ms (performance.now) is an
            origin-relative elapsed value BELOW the wall-clock floor. Pins that
            the two clock surfaces ARE distinguishable here — so the band tests
            above genuinely catch a swap (they would be vacuous if both clocks
            read the same class, as on the JVM)."
    (is (> (interop/epoch-now-ms) wall-clock-floor)
        "epoch-now-ms is a wall-clock epoch ms on CLJS (js/Date.now)")
    (is (< (interop/now-ms) wall-clock-floor)
        "now-ms is an origin-relative perf time on CLJS (performance.now),
         below the wall-clock floor — distinct CLASS from epoch-now-ms, so a
         swap is observable on this runtime (JVM-benign)")))
