(ns re-frame.after-dynamic-delay-report-test
  "A DYNAMIC `:after` delay — a subscription vector or a function — that
  resolves at runtime to anything but a positive number is the fault a
  static key raises at registration, so it reports the same id,
  `:rf.error/machine-bad-after-delay`, on the same diagnostic channel.
  The runtime arms no timer (`:recovery :skipped`), so the state waits for
  an event."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- mk-machine [delay-key]
  {:initial :idle
   :data    {}
   :states  {:idle    {:on {:go :running}}
             :running {:after {delay-key :timeout}}
             :timeout {}}})

(defn- ops [captured id]
  (filter #(= id (:operation %)) captured))

(defn- enter-running! [machine-id delay-key]
  (rf.machines.test-support/with-trace-capture captured
    (rf/reg-machine machine-id (mk-machine delay-key))
    (rf/dispatch-sync [machine-id [:go]])
    @captured))

(defn- armed-timers []
  (get @rf.machines.timer/after-timers :rf/default))

(deftest fn-delay-resolving-to-nil-reports-bad-after-delay
  (testing "a fn delay returning nil reports :rf.error/machine-bad-after-delay"
    (let [captured (enter-running! :dyn/fn-nil (fn [_ctx] nil))
          [ev :as evs] (ops captured :rf.error/machine-bad-after-delay)]
      (is (= 1 (count evs)) "one report for the one skipped timer")
      (is (= :error (:op-type ev)))
      (is (= :skipped (:recovery ev)) "no timer is armed")
      (is (= {:actor-id :dyn/fn-nil :state :running :slot :after
              :delay-source :fn :resolved-delay nil :frame :rf/default}
             (select-keys (:tags ev) [:actor-id :state :slot :delay-source
                                      :resolved-delay :frame])))
      (is (empty? (ops captured :rf.warning/no-clock-configured))
          "a bad delay is not a missing clock")
      (is (empty? (armed-timers)) "nothing armed")
      (is (= :running (:state (rf.machines.test-support/snapshot :dyn/fn-nil)))
          "the state waits for an event"))))

(deftest sub-delay-resolving-to-zero-reports-bad-after-delay
  (testing "a subscription delay resolving to 0 reports :rf.error/machine-bad-after-delay"
    (rf/reg-sub :dyn/zero (fn [_db _] 0))
    (let [captured (enter-running! :dyn/sub-zero [:dyn/zero])
          [ev :as evs] (ops captured :rf.error/machine-bad-after-delay)]
      (is (= 1 (count evs)))
      (is (= :skipped (:recovery ev)))
      (is (= [:sub [:dyn/zero] 0]
             ((juxt :delay-source :delay-key :resolved-delay) (:tags ev))))
      (is (empty? (ops captured :rf.warning/no-clock-configured)))
      (is (empty? (armed-timers))))))

(deftest dynamic-report-shares-the-static-id
  (testing "the static key's registration throw and the dynamic report name one id"
    (let [static-id (try (rf/reg-machine :dyn/static-bad (mk-machine -1)) nil
                         (catch clojure.lang.ExceptionInfo e
                           (:rf.error/id (ex-data e))))
          captured  (enter-running! :dyn/fn-neg (fn [_ctx] -5))]
      (is (= :rf.error/machine-bad-after-delay static-id))
      (is (= [static-id]
             (map :operation (ops captured static-id)))))))

(deftest positive-dynamic-delay-arms-and-reports-nothing
  (testing "control: a fn delay resolving to a positive number arms a timer"
    (let [captured (enter-running! :dyn/fn-ok (fn [_ctx] 60000))]
      (is (empty? (ops captured :rf.error/machine-bad-after-delay)))
      (is (= 1 (count (armed-timers))) "the timer is armed"))))
