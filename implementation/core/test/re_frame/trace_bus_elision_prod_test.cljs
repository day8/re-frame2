(ns re-frame.trace-bus-elision-prod-test
  "Per Spec 009 §Production builds (bead rf2-l7hlm) — `:advanced` +
  `goog.DEBUG=false` runtime contract for the `re-frame.trace.tooling`
  RING BUFFER (the 'trace bus'). Companion to
  `re-frame.trace-listener-elision-prod-test` (rf2-2zdu) which covers
  the listener fan-out side of the trace surface; this file pins the
  buffer side.

  Under prod-mode the buffer surface's bodies all sit inside `(when
  interop/debug-enabled? ...)` — `configure-trace-buffer!` /
  `clear-trace-buffer!` no-op silently; `trace-buffer` returns nil
  rather than the buffer vector. The defonce'd atom itself remains
  (it's a value-layer artefact, not gated), but no caller can populate
  it because every push site sits inside the gated `deliver!` /
  `emit!` chain.

  Per Spec 009 §Retain-N trace ring buffer (rf2-smee): the buffer is a
  dev-only inspection surface. Production observability rides the
  always-on event-emit / error-emit substrates instead.

  Naming convention: files ending in `-elision-prod-test.cljs` are
  picked up ONLY by the `:browser-test-prod-elision` build. Running
  this file under `goog.DEBUG=true` would FAIL by design — under dev
  the buffer accumulates events as designed."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace]
            ;; rf2-qwm0a — buffer + listener surface lives in
            ;; `re-frame.trace.tooling`.
            [re-frame.trace.tooling :as trace-tooling]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---- ring buffer is empty under prod -------------------------------------

(deftest dispatched-events-do-not-populate-trace-buffer-under-prod
  (testing "Per Spec 009 §Production builds (rf2-l7hlm): under
            `:advanced` + `goog.DEBUG=false` a registered handler
            fires through the router but NO events reach the trace
            ring buffer. Every emit site's body sits inside the
            `interop/debug-enabled?` gate; the buffer's push site
            (inside `deliver!`) does not run."
    (rf/reg-event-db :prod-bus/inc
                     (fn [db _] (update db :n (fnil inc 0))))
    (rf/dispatch-sync [:prod-bus/inc])
    (rf/dispatch-sync [:prod-bus/inc])
    (rf/dispatch-sync [:prod-bus/inc])
    ;; trace-buffer's body is gated; under prod it returns nil.
    (is (or (nil? (trace-tooling/trace-buffer))
            (empty? (trace-tooling/trace-buffer)))
        "trace-buffer is nil or empty under :advanced + goog.DEBUG=false
         — buffer surface elides while the handler still runs")
    ;; Cross-check: the handler DID run — only the trace surface elided.
    (is (= 3 (:n (rf/get-frame-db :rf/default)))
        "handler ran the expected number of times — only the trace
         surface (buffer + listener) elided")))

(deftest configure-trace-buffer-is-noop-under-prod
  (testing "Per Spec 009 §Production builds: `configure-trace-buffer!`
            is gated on `interop/debug-enabled?`. Under prod the call
            returns nil silently — apps that boot with a
            configure-trace-buffer call do not crash under :advanced,
            but the requested depth has no effect."
    (is (nil? (trace-tooling/configure-trace-buffer! {:depth 256}))
        "configure-trace-buffer! returns nil consistently under prod")
    ;; Subsequent dispatches still do not push to the buffer.
    (rf/reg-event-db :prod-bus/touch (fn [db _] db))
    (rf/dispatch-sync [:prod-bus/touch])
    (is (or (nil? (trace-tooling/trace-buffer))
            (empty? (trace-tooling/trace-buffer)))
        "buffer remains empty after configure + dispatch under prod")))

(deftest clear-trace-buffer-is-noop-under-prod
  (testing "Per Spec 009 §Production builds: `clear-trace-buffer!` is
            gated and a no-op under prod. Tools that call it as part of
            a session-reset (re-frame-10x's `Clear` button) do not
            crash on a production bundle that happens to load
            re-frame.trace.tooling for non-buffer surfaces."
    (is (nil? (trace-tooling/clear-trace-buffer!))
        "clear-trace-buffer! returns nil under prod")))

(deftest trace-emit-direct-call-does-not-populate-buffer-under-prod
  (testing "Per Spec 009 §Production builds: directly invoking
            `trace/emit!` does not push to the buffer. The emit body
            elides before any deliver-to-buffer call site is reached."
    (trace/emit! :info :rf.prod-bus/direct {:should "never appear"})
    (trace/emit! :event :rf.prod-bus/sample {:also "never appear"})
    (is (or (nil? (trace-tooling/trace-buffer))
            (empty? (trace-tooling/trace-buffer)))
        "buffer remains empty after direct emit calls under prod")))

(deftest trace-configure-is-noop-under-prod
  (testing "Per Spec 009 §Production builds: the generic `configure`
            dispatch (currently `:trace-buffer`) is also gated. Apps
            that boot via `(re-frame.core/configure :trace-buffer ...)`
            do not crash under :advanced; the requested config is
            silently dropped."
    (is (nil? (rf/configure :trace-buffer {:depth 64}))
        "configure :trace-buffer returns nil under prod")))
