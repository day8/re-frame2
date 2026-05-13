(ns re-frame.on-error-elision-prod-test
  "Per rf2-hqbeh — the per-frame `:on-error` slot is a runtime
  error-recovery surface and MUST fire even when the CLJS trace surface
  is compile-time elided in production builds (`:advanced` +
  `goog.DEBUG=false`).

  Before rf2-hqbeh the `:on-error` policy rode the trace surface
  exclusively; with the surface elided, registered `:on-error`
  callbacks did not fire in CLJS prod. A user-registered Sentry-shape
  forwarder went silent on the production-build path it was written
  for. This file pins the fix.

  Companion to `re-frame.trace-listener-elision-prod-test` (rf2-2zdu)
  and `re-frame.source-coord-dom-elision-prod-test` (rf2-uwg5). The
  shared runner is `re-frame.prod-elision-runner`; the shadow-cljs
  build is `:browser-test-prod-elision` (`:advanced` +
  `{goog.DEBUG false}`).

  Naming convention: files ending in `-elision-prod-test.cljs` are
  picked up ONLY by the `:browser-test-prod-elision` build. The default
  `:browser-test` / `:node-test` runners use regexes that do NOT match
  this suffix, so these tests run only under prod-mode compilation."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---- :on-error survives goog.DEBUG=false ----------------------------------

(deftest on-error-fires-under-prod-when-handler-throws
  (testing "Per rf2-hqbeh: under `:advanced` + `goog.DEBUG=false`, a
            frame's `:on-error` policy fn MUST fire when an event
            handler throws — the trace surface is gone, but the
            always-on error-emit substrate delivers the structured
            error event to the policy fn so production monitoring
            integrations still observe the failure."
    (let [seen (atom [])]
      ;; Re-register :rf/default with an :on-error policy. The fixture
      ;; reset-runtime-fixture installs :rf/default with no policy; we
      ;; re-register to attach one. Per Spec 002 §Re-registration —
      ;; surgical update, the existing app-db / sub-cache survive.
      (rf/reg-frame :rf/default
                    {:on-error (fn [error-event]
                                 (swap! seen conj error-event)
                                 nil)})
      (rf/reg-event-db :prod/throw
                       (fn [_db _]
                         (throw (ex-info "kaboom" {:cause :test}))))
      (rf/dispatch-sync [:prod/throw])
      (is (= 1 (count @seen))
          ":on-error fired exactly once for the thrown handler — prod-elision contract holds")
      (let [ev (first @seen)]
        (is (= :rf.error/handler-exception (:operation ev))
            ":on-error received the structured :operation discriminator")
        (is (= :error (:op-type ev))
            "and the :op-type :error discriminator")
        (is (= :prod/throw (get-in ev [:tags :event-id]))
            "and :event-id under :tags identifying the failing handler")
        (is (= :rf/default (get-in ev [:tags :frame]))
            "and :frame identifying the policy's host frame")))))

(deftest on-error-no-op-when-not-registered-under-prod
  (testing "When no `:on-error` policy is configured on the frame, the
            always-on substrate is a no-op and the handler exception
            still propagates through the rest of the runtime per the
            documented per-category recovery. The dispatch settles —
            the drain does not abort."
    (rf/reg-event-db :prod/quiet-throw
                     (fn [_db _]
                       (throw (ex-info "boom" {}))))
    ;; No :on-error registered; just confirm dispatch returns without
    ;; throwing. The runtime catches the handler exception (interceptor
    ;; chain wraps it in :rf/interceptor-error) and the drain settles.
    (is (nil? (rf/dispatch-sync [:prod/quiet-throw]))
        "dispatch-sync returns nil; the drain settled after the exception")))

(deftest on-error-policy-exception-is-swallowed-under-prod
  (testing "Per Spec 009 §1052: when the `:on-error` policy fn itself
            throws, the runtime MUST NOT recursively invoke the policy
            on its own exception. The always-on substrate catches the
            policy's throw silently — the cascade does not abort, the
            drain settles."
    (rf/reg-frame :rf/default
                  {:on-error (fn [_error-event]
                               (throw (ex-info "policy itself threw" {})))})
    (rf/reg-event-db :prod/policy-throw
                     (fn [_db _]
                       (throw (ex-info "handler threw" {}))))
    ;; Should NOT throw — the policy fn's exception is caught inside
    ;; error-emit/dispatch-on-error!.
    (is (nil? (rf/dispatch-sync [:prod/policy-throw]))
        "dispatch-sync returned nil despite both handler AND policy throwing")))
