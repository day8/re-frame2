(ns re-frame.on-error-test
  "Per rf2-hqbeh — exercise the per-frame `:on-error` slot end-to-end:

    - Policy fn fires when a registered handler throws.
    - Policy fn receives a structured error event with
      `:operation :rf.error/handler-exception`, `:op-type :error`,
      and `:tags {:event-id ..., :frame ..., :exception ..., ...}`.
    - Policy fn's own exceptions are caught inside the always-on
      substrate per Spec 009 §1052 — the drain settles, the cascade
      does not abort.
    - Re-registration of the frame replaces the `:on-error` slot;
      the old policy fn does NOT fire after replacement.

  Dev-side coverage (runs under `:node-test` / `:browser-test` /
  `clojure -M:test`). The CLJS production-mode counterpart lives in
  `re-frame.on-error-elision-prod-test` — that suite pins the same
  contract under `:advanced` + `goog.DEBUG=false`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter}))

(deftest on-error-fires-when-handler-throws
  (testing "Per rf2-hqbeh / Spec 009 §Error-handler policy: a frame
            with `:on-error` set sees the structured error event when a
            registered handler throws."
    (let [seen (atom [])]
      (rf/reg-frame :rf/default
                    {:on-error (fn [error-event]
                                 (swap! seen conj error-event)
                                 nil)})
      (rf/reg-event-db :on-error-test/throw
                       (fn [_db _]
                         (throw (ex-info "boom" {:cause :test}))))
      (rf/dispatch-sync [:on-error-test/throw])
      (is (= 1 (count @seen))
          ":on-error fired exactly once")
      (let [ev (first @seen)]
        (is (= :rf.error/handler-exception (:operation ev)))
        (is (= :error (:op-type ev)))
        (is (= :on-error-test/throw (get-in ev [:tags :event-id])))
        (is (= :rf/default (get-in ev [:tags :frame])))
        (is (= :no-recovery (get-in ev [:tags :recovery])))))))

(deftest on-error-policy-exception-does-not-propagate
  (testing "Per Spec 009 §1052: an `:on-error` policy fn that throws
            does NOT take down the drain — its exception is caught
            inside the always-on substrate."
    (rf/reg-frame :rf/default
                  {:on-error (fn [_error-event]
                               (throw (ex-info "policy threw" {})))})
    (rf/reg-event-db :on-error-test/policy-throw
                     (fn [_db _]
                       (throw (ex-info "handler threw" {}))))
    ;; The dispatch must return normally — the policy fn's exception is
    ;; caught by error-emit/dispatch-on-error!.
    (is (nil? (rf/dispatch-sync [:on-error-test/policy-throw])))))

(deftest re-registration-replaces-on-error
  (testing "Per Spec 002 §Re-registration — surgical update: re-
            registering the frame replaces the `:on-error` slot; the
            previous policy fn does NOT fire after replacement."
    (let [first-policy-fires  (atom 0)
          second-policy-fires (atom 0)]
      (rf/reg-frame :rf/default
                    {:on-error (fn [_ev] (swap! first-policy-fires inc))})
      (rf/reg-frame :rf/default
                    {:on-error (fn [_ev] (swap! second-policy-fires inc))})
      (rf/reg-event-db :on-error-test/throw-after-rereg
                       (fn [_db _]
                         (throw (ex-info "boom" {}))))
      (rf/dispatch-sync [:on-error-test/throw-after-rereg])
      (is (zero? @first-policy-fires)
          "old :on-error did not fire after re-registration")
      (is (= 1 @second-policy-fires)
          "new :on-error fired exactly once"))))

(deftest no-on-error-registered-is-a-no-op
  (testing "When the frame's config carries no `:on-error`, the always-
            on substrate quietly no-ops and the rest of the runtime
            proceeds with the documented per-category recovery."
    (rf/reg-event-db :on-error-test/no-policy-throw
                     (fn [_db _]
                       (throw (ex-info "boom" {}))))
    ;; :rf/default is registered by the fixture with no :on-error.
    (is (nil? (rf/dispatch-sync [:on-error-test/no-policy-throw])))))
