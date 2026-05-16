(ns re-frame.flow-eval-exception-elision-prod-test
  "Per rf2-gmrks — pins that `:rf.error/flow-eval-exception` rides the
  **always-on production error-emit substrate**, not the dev-only
  trace surface. Under CLJS `:advanced` + `goog.DEBUG=false` the trace
  surface compile-time elides, but the per-frame `:on-error` policy
  fn and corpus-wide `register-error-emit-listener!` callbacks MUST
  still fire when a flow's `:output` throws.

  Pre-rf2-hrt5c, the cascade-level `:rf.error/flow-eval-exception`
  rode the trace path only. `trace/emit-error!` is gated by
  `interop/debug-enabled?` and DCEs to a no-op under `:advanced` +
  `goog.DEBUG=false` — so a CLJS production build silently swallowed
  flow-eval throws (no `:on-error` policy fire, no corpus-wide
  listener record for off-box monitors). The fix routes the error
  through `error-emit/dispatch-on-error!` (the always-on substrate)
  in parallel with the dev-only trace emit. This file is the
  prod-elision proof — rf2-0q0du pinned the contract in Spec 013
  §Failure semantics rule 4 + Resolved decisions, and this test
  exercises the genuine `:advanced` build.

  Companion to:
    - `re-frame.on-error-elision-prod-test` (rf2-hqbeh, handler-
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
            ;; (`:flows/reg-flow`, `:flows/run-flows!`) the router
            ;; reaches at dispatch time — keep the require even when
            ;; the test ns doesn't reach `flows/...` directly through
            ;; a public fn.
            [re-frame.flows]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :init-fn (fn []
                ;; Per rf2-bacs4: clear the listener registry between
                ;; tests — defonce means it would otherwise leak.
                (error-emit/clear-error-emit-listeners!))}))

;; ---- :on-error fires for flow-eval failures under prod -------------------

(deftest on-error-fires-under-prod-when-flow-output-throws
  (testing "Per rf2-0q0du / rf2-hrt5c: under `:advanced` +
            `goog.DEBUG=false`, a frame's `:on-error` policy fn MUST
            fire when a flow's `:output` throws. The trace surface is
            gone, but the always-on error-emit substrate delivers
            the structured `:rf.error/flow-eval-exception` event to the
            policy fn so production monitoring integrations still
            observe flow failures."
    (let [seen (atom [])]
      (rf/reg-frame :rf/default
                    {:on-error (fn [error-event]
                                 (swap! seen conj error-event)
                                 nil)})
      (rf/reg-event-db :prod/seed-bad-token
                       (fn [_db _]
                         {:token "this-is-a-string"}))
      ;; The flow's :output calls `count` on :token. A string input is
      ;; legal Clojure (count of a String is its length), but to make
      ;; the throw deterministic we wrap in a fn that rejects strings.
      (rf/reg-flow {:id     :tries
                    :inputs [[:token]]
                    :output (fn [token]
                              (when (string? token)
                                (throw (ex-info "no strings allowed" {})))
                              (count token))
                    :path   [:tries]})
      (rf/dispatch-sync [:prod/seed-bad-token])
      (is (= 1 (count @seen))
          ":on-error fired exactly once for the thrown flow — prod-elision contract holds")
      (let [ev (first @seen)]
        (is (= :rf.error/flow-eval-exception (:operation ev))
            ":on-error received the structured :operation discriminator")
        (is (= :error (:op-type ev))
            "and the :op-type :error discriminator")
        (is (= :rf/default (get-in ev [:tags :frame]))
            "and :frame identifying the policy's host frame")
        (is (= :flow-eval (get-in ev [:tags :where]))
            "and :where :flow-eval discriminating from handler-exception path")
        (is (= :tries (get-in ev [:tags :flow-id]))
            "and :flow-id attributing the failure to the specific flow")))))

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
      (rf/register-error-emit-listener!
        :prod/flow-recorder
        (fn [record] (swap! seen conj record)))
      (rf/reg-event-db :prod/flow-throw
                       (fn [_db _] {:token "string-value"}))
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

;; ---- both paths fire independently under prod ---------------------------

(deftest flow-eval-listener-and-policy-fire-independently-under-prod
  (testing "Per rf2-0q0du / rf2-bacs4 §independent paths under prod:
            when both a per-frame `:on-error` policy fn AND a
            corpus-wide listener are registered, BOTH fire on a
            flow-eval throw. Neither blocks the other under
            `:advanced` + `goog.DEBUG=false`."
    (let [listener-saw (atom nil)
          policy-saw   (atom nil)]
      (rf/reg-frame :rf/default
                    {:on-error (fn [ev] (reset! policy-saw ev) nil)})
      (rf/register-error-emit-listener!
        :prod/dual-recorder
        (fn [record] (reset! listener-saw record)))
      (rf/reg-event-db :prod/dual-throw (fn [_db _] {:token "go"}))
      (rf/reg-flow {:id     :dual
                    :inputs [[:token]]
                    :output (fn [_t] (throw (ex-info "always-throws" {})))
                    :path   [:dual]})
      (rf/dispatch-sync [:prod/dual-throw])
      (is (some? @policy-saw)
          "per-frame :on-error fired under prod for the flow throw")
      (is (some? @listener-saw)
          "corpus-wide listener fired under prod — both paths survive elision")
      (is (= :rf.error/flow-eval-exception (:error @listener-saw)))
      (is (= :prod/dual-throw              (:event-id @listener-saw)))
      (is (= :rf.error/flow-eval-exception (:operation @policy-saw))))))
