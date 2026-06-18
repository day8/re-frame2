(ns re-frame.flow-eval-exception-elision-prod-test
  "Per rf2-gmrks — pins that `:rf.error/flow-eval-exception` rides the
  **always-on production error-emit substrate**, not the dev-only
  trace surface. Under CLJS `:advanced` + `goog.DEBUG=false` the trace
  surface compile-time elides, but the corpus-wide
  `register-error-listener!` callbacks MUST still fire when a flow's
  `:output` throws.

  Pre-rf2-hrt5c, the cascade-level `:rf.error/flow-eval-exception`
  rode the trace path only. `trace/emit-error!` is gated by
  `interop/debug-enabled?` and DCEs to a no-op under `:advanced` +
  `goog.DEBUG=false` — so a CLJS production build silently swallowed
  flow-eval throws (no corpus-wide listener record for off-box
  monitors). The fix routes the error through
  `error-emit/dispatch-on-error!` (the always-on substrate) in
  parallel with the dev-only trace emit. This file is the
  prod-elision proof — rf2-0q0du pinned the contract in Spec 013
  §Failure semantics rule 4 + Resolved decisions, and this test
  exercises the genuine `:advanced` build.

  Companion to:
    - `re-frame.on-error-elision-prod-test` (rf2-bacs4, handler-
      exception path)
    - `re-frame.trace-listener-elision-prod-test` (rf2-2zdu)
    - `re-frame.source-coord-dom-elision-prod-test` (rf2-uwg5)

  Shared runner: `re-frame.prod-elision-runner`. Shadow-cljs build:
  `:browser-test-prod-elision` (`:advanced` + `{goog.DEBUG false}`).

  Naming convention: files ending in `-elision-prod-test.cljs` are
  picked up ONLY by the `:browser-test-prod-elision` build."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            ;; Loading `re-frame.flows` registers the late-bind hooks
            ;; (`:flows/reg-flow`, `:flows/run-flows-on-db`) the router
            ;; reaches at dispatch time — keep the require even when
            ;; the test ns doesn't reach `flows/...` directly through
            ;; a public fn.
            [re-frame.flows]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :init-fn (fn []
                ;; Per rf2-bacs4: clear the listener registry between
                ;; tests — defonce means it would otherwise leak.
                (error-emit/clear-error-listeners!))}))

;; ---- corpus-wide listener fires for flow-eval failures under prod -------

(deftest error-emit-listener-fires-under-prod-on-flow-eval-throw
  (testing "Per rf2-0q0du / rf2-bacs4: under `:advanced` +
            `goog.DEBUG=false`, a registered corpus-wide error-emit
            listener MUST fire for every flow-eval throw — the trace
            surface is gone but the always-on error-emit substrate
            delivers the tight record so off-box observability shippers
            (Sentry / Honeybadger / Rollbar) still see every flow
            failure in production."
    (let [seen (atom [])]
      (rf/register-listener! :errors
        :prod/flow-recorder
        (fn [record] (swap! seen conj record)))
      (rf/reg-event :prod/flow-throw
                       (fn [{:keys [db]} _] {:db {:token "string-value"}}))
      (rf/reg-flow {:id     :str-len
                    :inputs [[:token]]
                    :output (fn [t]
                              (when (string? t)
                                (throw (ex-info "no strings allowed" {})))
                              (count t))
                    :path   [:str-len]})
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

