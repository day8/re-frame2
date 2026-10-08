(ns re-frame.after-dynamic-delay-report-test
  "A DYNAMIC `:after` delay — a subscription vector or a function — that
  resolves at runtime to anything but a positive number reports
  `:rf.error/machine-bad-after-delay`, the id a bad static key raises at
  registration. No timer is armed (`:recovery :skipped`), so the state waits
  for an event."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(defn- enter-running! [machine-id delay-key]
  (rf/reg-machine machine-id {:initial :idle
                              :data    {}
                              :states  {:idle    {:on {:go :running}}
                                        :running {:after {delay-key :timeout}}
                                        :timeout {}}})
  (rf/dispatch-sync [machine-id [:go]]))

(defn- bad-delay-reports
  "Each bad-delay report as `[op-type recovery (select-keys tags tag-keys)]`."
  [tag-keys]
  (for [ev (rf.machines.test-support/events-of :rf.error/machine-bad-after-delay)]
    [(:op-type ev) (:recovery ev) (select-keys (:tags ev) tag-keys)]))

(defn- armed-timers []
  (get @rf.machines.timer/after-timers :rf/default))

(deftest fn-delay-resolving-to-nil-reports-bad-after-delay
  (enter-running! :dyn/fn-nil (fn [_ctx] nil))
  (is (= [[:error :skipped {:actor-id       :dyn/fn-nil
                            :state          :running
                            :slot           :after
                            :delay-source   :fn
                            :resolved-delay nil
                            :frame          :rf/default}]]
         (bad-delay-reports [:actor-id :state :slot :delay-source :resolved-delay :frame])))
  (is (empty? (armed-timers)) "nothing armed"))

(deftest sub-delay-resolving-to-zero-reports-bad-after-delay
  (rf/reg-sub :dyn/zero (fn [_db _] 0))
  (enter-running! :dyn/sub-zero [:dyn/zero])
  (is (= [[:error :skipped {:delay-source :sub :delay-key [:dyn/zero] :resolved-delay 0}]]
         (bad-delay-reports [:delay-source :delay-key :resolved-delay])))
  (is (empty? (armed-timers)))
  ;; The subscribe that resolved the delay is paired with an unsubscribe on
  ;; this early return (Spec 006 §Reference counting and disposal).
  (is (not (contains? @(:sub-cache (rf.frame/frame :rf/default)) [:dyn/zero]))
      "the bad delay's subscription is released, not leaked"))

(deftest positive-dynamic-delay-arms-and-reports-nothing
  (enter-running! :dyn/fn-ok (fn [_ctx] 60000))
  (is (empty? (bad-delay-reports [])))
  (is (= 1 (count (armed-timers))) "the timer is armed"))
