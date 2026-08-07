(ns re-frame.schema-reject-notification-cljs-test
  "Reagent leg of the rf2-uhk9ko cross-adapter contract (Mike-ruled
  Option B): the router validates the COMPLETE candidate frame
  transition BEFORE installing it, so a schema-rejected dispatch never
  touches the container — the Reagent reaction graph is never dirtied,
  no tracking reaction re-runs, and a listener-triggered SYNCHRONOUS
  `r/flush` during the rejection still reads the OLD value.

  Under the retired commit-then-rollback pair the forward commit
  dirtied every dependent reaction with the INVALID candidate; a
  synchronous `r/flush` from a trace listener (exactly what a dev tool
  or test harness may do) recomputed and notified with the invalid
  value before the rollback write restored it. This file is the
  Reagent tooth that keeps that window closed; the UIx spine leg
  lives in `re-frame.adapter.react-shared-suite/
  assert-schema-rejection-zero-sub-notifications`.

  The observer is an `r/track!` auto-runner — the SAME activation path
  a mounted Reagent view uses (a bare `add-watch` on a Reagent Reaction
  never activates it headlessly, so it would vacuously see nothing on
  ANY commit).

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
            listener-triggered sync r/flush still reads the OLD value
            (rf2-uhk9ko — the candidate is validated before install)"
    (let [fid  :reagent.schema-reject/main
          runs (atom 0)]
      (rf/make-frame {:id fid})
      (rf/reg-app-schema [:n] {:frame fid} [:int])
      (rf/reg-event :reject.test/seed  (fn [_ _] {:db {:n 0}}))
      (rf/reg-event :reject.test/break (fn [{:keys [db]} _]
                                         {:db (assoc db :n "boom")}))
      (rf/reg-event :reject.test/ok    (fn [{:keys [db]} _]
                                         {:db (assoc db :n 1)}))
      (rf/reg-sub :reject.test/n (fn [db _] (swap! runs inc) (:n db)))
      (rf/dispatch-sync [:reject.test/seed] {:frame fid})
      (let [sub           (rf/subscribe [:reject.test/n] {:frame fid})
            ;; The view-model observer: an auto-running tracker (the same
            ;; activation path a mounted view uses). Every re-run appends
            ;; the value it observed — the notification log.
            seen          (atom [])
            tracker       (r/track! (fn [] (swap! seen conj @sub)))
            during-reject (atom ::never-fired)]
        (is (= [0] @seen)
            "precondition: the tracker primed to the seeded value")
        (let [after-prime @runs]
          ;; The listener-triggered sync flush: during the rejection emit,
          ;; force Reagent's batch queue and read the reaction. Under the
          ;; retired forward-commit the container held the invalid
          ;; candidate here, the flush re-ran the tracker with it, and
          ;; this deref exposed it.
          (rf/register-listener! :trace ::reject-probe
            (fn [ev]
              (when (= :rf.error/schema-validation-failure (:operation ev))
                (r/flush)
                (reset! during-reject @sub))))
          (rf/dispatch-sync [:reject.test/break] {:frame fid})
          (rf/unregister-listener! :trace ::reject-probe)
          (r/flush)
          (is (= 0 @during-reject)
              "the listener-triggered sync flush read the OLD value — the
               invalid candidate was never installed")
          (is (= [0] @seen)
              "ZERO tracker re-runs for the rejected dispatch — the
               reaction graph was never dirtied (and \"boom\" was never
               observable)")
          (is (= after-prime @runs)
              "the sub body did not re-run either")
          (is (= 0 @sub) "the reaction still reads the pre-handler value")
          (is (= {:n 0} (rf/app-db-value fid))
              "the container holds the pre-handler value")
          ;; Sanity: the tracker is live — a valid commit re-runs it
          ;; exactly once with the new value.
          (rf/dispatch-sync [:reject.test/ok] {:frame fid})
          (r/flush)
          (is (= [0 1] @seen)
              "exactly one tracker re-run for the following valid commit"))
        (r/dispose! tracker)
        (rf/unsubscribe fid [:reject.test/n])))))
