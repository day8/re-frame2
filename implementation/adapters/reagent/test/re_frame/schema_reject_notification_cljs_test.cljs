(ns re-frame.schema-reject-notification-cljs-test
  "Reagent leg of the rf2-uhk9ko cross-adapter contract (Mike-ruled
  Option B): the router validates the COMPLETE candidate frame
  transition BEFORE installing it, so a schema-rejected dispatch never
  touches the container — the Reagent reaction graph is never dirtied,
  no reaction recomputes, no watcher notifies, and a listener-triggered
  SYNCHRONOUS `r/flush!` during the rejection still reads the OLD
  value.

  Under the retired commit-then-rollback pair the forward commit
  dirtied every dependent reaction with the INVALID candidate; a
  synchronous `r/flush!` from a trace listener (exactly what a dev tool
  or test harness may do) recomputed and notified with the invalid
  value before the rollback write restored it. This file is the
  Reagent tooth that keeps that window closed; the UIx/Helix spine leg
  lives in `re-frame.adapter.react-shared-suite/
  assert-schema-rejection-zero-sub-notifications`.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; Publishes the Malli validator into the late-bind table so
            ;; reg-app-schema validation actually runs.
            [re-frame.schemas.malli]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(deftest schema-rejection-zero-reaction-notifications
  (testing "a schema-rejected dispatch dirties NO Reagent reaction and a
            listener-triggered sync r/flush! still reads the OLD value
            (rf2-uhk9ko — the candidate is validated before install)"
    (let [fid :reagent.schema-reject/main
          runs (atom 0)]
      (rf/reg-frame fid {})
      (rf/reg-app-schema [:n] {:frame fid} [:int])
      (rf/reg-event :reject.test/seed  (fn [_ _] {:db {:n 0}}))
      (rf/reg-event :reject.test/break (fn [{:keys [db]} _]
                                         {:db (assoc db :n "boom")}))
      (rf/reg-event :reject.test/ok    (fn [{:keys [db]} _]
                                         {:db (assoc db :n 1)}))
      (rf/reg-sub :reject.test/n (fn [db _] (swap! runs inc) (:n db)))
      (rf/dispatch-sync [:reject.test/seed] {:frame fid})
      (let [sub           (rf/subscribe [:reject.test/n] {:frame fid})
            notifications (atom [])
            during-reject (atom ::never-fired)]
        (is (= 0 @sub) "precondition: the reaction primes to the seeded value")
        (add-watch sub ::probe
                   (fn [_ _ old new] (swap! notifications conj [old new])))
        (let [after-prime @runs]
          ;; The listener-triggered sync flush: during the rejection emit,
          ;; force Reagent's batch queue and read the reaction. Under the
          ;; retired forward-commit the container held the invalid
          ;; candidate here and the flush recomputed with it.
          (rf/register-listener! :trace ::reject-probe
            (fn [ev]
              (when (= :rf.error/schema-validation-failure (:operation ev))
                (r/flush!)
                (reset! during-reject @sub))))
          (rf/dispatch-sync [:reject.test/break] {:frame fid})
          (rf/unregister-listener! :trace ::reject-probe)
          (r/flush!)
          (is (= 0 @during-reject)
              "the listener-triggered sync flush read the OLD value — the
               invalid candidate was never installed")
          (is (= [] @notifications)
              "ZERO reaction notifications for the rejected dispatch")
          (is (= after-prime @runs)
              "the sub body did not re-run — the reaction graph was never
               dirtied")
          (is (= 0 @sub) "the reaction still reads the pre-handler value")
          (is (= {:n 0} (rf/app-db-value fid))
              "the container holds the pre-handler value")
          ;; Sanity: the watch + reaction are live — a valid commit
          ;; notifies exactly once.
          (rf/dispatch-sync [:reject.test/ok] {:frame fid})
          (r/flush!)
          (is (= [[0 1]] @notifications)
              "exactly one notification for the following valid commit"))
        (remove-watch sub ::probe)
        (rf/unsubscribe fid [:reject.test/n])))))
