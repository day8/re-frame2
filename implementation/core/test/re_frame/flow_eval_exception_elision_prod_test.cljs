(ns re-frame.flow-eval-exception-elision-prod-test
  "Pins that `:rf.error/flow-eval-exception` rides the
  **always-on production error-emit substrate**, not the dev-only
  trace surface. Under CLJS `:advanced` + `goog.DEBUG=false` the trace
  surface compile-time elides, but the corpus-wide
  `register-error-listener!` callbacks MUST still fire when a flow's
  `:derive` throws.

  `trace/emit-error!` is gated by `interop/debug-enabled?` and DCEs to
  a no-op under `:advanced` + `goog.DEBUG=false`, so a flow-eval error
  riding the trace path only would be silently swallowed by a CLJS
  production build (no corpus-wide listener record for off-box
  monitors). The cascade-level `:rf.error/flow-eval-exception`
  therefore routes through `rf.error-emit/dispatch-on-error!` (the
  always-on substrate) in parallel with the dev-only trace emit. This
  file is the prod-elision proof of the contract in Spec 013 §Failure
  semantics rule 4 + Resolved decisions, exercising the genuine
  `:advanced` build.

  Companion to:
    - `re-frame.on-error-elision-prod-test` (handler-exception path)
    - `re-frame.trace-listener-elision-prod-test`
    - `re-frame.source-coord-dom-elision-prod-test`

  Shared runner: `re-frame.prod-elision-runner`. Shadow-cljs build:
  `:browser-test-prod-elision` (`:advanced` + `{goog.DEBUG false}`).

  Naming convention: files ending in `-elision-prod-test.cljs` are
  picked up ONLY by the `:browser-test-prod-elision` build."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            ;; Loading `re-frame.flows` registers the late-bind hooks
            ;; (`:flows/reg-flow`, `:flows/run-flows-on-db`) the router
            ;; reaches at dispatch time — keep the require even when
            ;; the test ns doesn't reach `flows/...` directly through
            ;; a public fn.
            [re-frame.flows]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn (fn []
                ;; Clear the listener registry between
                ;; tests — defonce means it would otherwise leak.
                (rf.error-emit/clear-error-listeners!))}))

;; ---- corpus-wide listener fires for flow-eval failures under prod -------

(deftest error-emit-listener-fires-under-prod-on-flow-eval-throw
  (testing "Under `:advanced` +
            `goog.DEBUG=false`, a registered corpus-wide error-emit
            listener MUST fire for every flow-eval throw — the trace
            surface is gone but the always-on error-emit substrate
            delivers the tight record so off-box observability shippers
            (Sentry / Honeybadger / Rollbar) still see every flow
            failure in production."
    (let [seen (atom [])]
      (rf.error-emit/register-error-listener!
        :prod/flow-recorder
        (fn [record] (swap! seen conj record)))
      (rf/reg-event :prod/flow-throw
                       (fn [{:keys [db]} _] {:db {:token "string-value"}}))
      (rf/reg-flow :str-len {:inputs [[:token]] :output-path [:str-len]} (fn [t]
                              (when (string? t)
                                (throw (ex-info "no strings allowed" {})))
                              (count t)))
      (rf/dispatch-sync [:prod/flow-throw])
      (is (= 1 (count @seen))
          "listener fired exactly once — prod-elision contract holds")
      (let [r (first @seen)]
        (is (= :rf.error/flow-eval-exception (:error r)))
        (is (= [:prod/flow-throw] (:event r)))
        (is (= :prod/flow-throw   (:event-id r)))
        (is (= :rf/default        (:frame r)))
        (is (number? (:time r)))
        (is (integer? (:elapsed-ms r))
            ":elapsed-ms is an integer under :advanced + goog.DEBUG=false
             — the substrate boundary rounds the CLJS float-precision
             performance.now() value")))))

