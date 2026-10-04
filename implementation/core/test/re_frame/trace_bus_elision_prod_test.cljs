(ns re-frame.trace-bus-elision-prod-test
  "Per Spec 009 §Production builds — `:advanced` +
  `goog.DEBUG=false` runtime contract for the `re-frame.trace.tooling`
  RING BUFFER (the 'trace bus'). Companion to
  `re-frame.trace-listener-elision-prod-test` which covers
  the listener fan-out side of the trace surface; this file pins the
  buffer side.

  Under prod-mode the buffer surface's bodies all sit inside `(when
  interop/debug-enabled? ...)` — `configure-trace-buffer!` /
  `clear-trace-buffer!` no-op silently; `trace-buffer` returns nil
  rather than the buffer vector. The defonce'd atom itself remains
  (it's a value-layer artefact, not gated), but no caller can populate
  it because every push site sits inside the gated `deliver!` /
  `emit!` chain.

  Per Spec 009 §Per-frame trace rings (event-keyed, dev-only): the buffer is a
  dev-only inspection surface. Production observability rides the
  always-on event-emit / error-emit substrates instead.

  Naming convention: files ending in `-elision-prod-test.cljs` are
  picked up ONLY by the `:browser-test-prod-elision` build. Running
  this file under `goog.DEBUG=true` would FAIL by design — under dev
  the buffer accumulates events as designed."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            ;; The buffer + listener surface lives in
            ;; `re-frame.trace.tooling`.
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; ---- ring buffer is empty under prod -------------------------------------

(deftest dispatched-events-do-not-populate-trace-buffer-under-prod
  (testing "Per Spec 009 §Production builds: under
            `:advanced` + `goog.DEBUG=false` a registered handler
            fires through the router but NO events reach the trace
            ring buffer. Every emit site's body sits inside the
            `interop/debug-enabled?` gate; the buffer's push site
            (inside `deliver!`) does not run."
    (rf/reg-event :prod-bus/inc
                     (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/dispatch-sync [:prod-bus/inc])
    (rf/dispatch-sync [:prod-bus/inc])
    (rf/dispatch-sync [:prod-bus/inc])
    ;; trace-buffer's body is gated; under prod it returns nil.
    (is (or (nil? (rf.trace.tooling/trace-buffer :rf/default))
            (empty? (rf.trace.tooling/trace-buffer :rf/default)))
        "trace-buffer is nil or empty under :advanced + goog.DEBUG=false
         — buffer surface elides while the handler still runs")
    ;; Cross-check: the handler DID run — only the trace surface elided.
    (is (= 3 (:n (rf/app-db-value :rf/default)))
        "handler ran the expected number of times — only the trace
         surface (buffer + listener) elided")))

(deftest trace-configure-is-noop-under-prod
  (testing "Per Spec 009 §Production builds: the generic `configure!`
            dispatch's `:trace-buffer` key is also gated. Apps
            that boot via `(re-frame.core/configure! {:trace-buffer ...})`
            do not crash under :advanced; the requested config is
            silently dropped."
    (is (nil? (rf/configure! {:trace-buffer {:events-retained 64}}))
        "configure :trace-buffer returns nil under prod")))
