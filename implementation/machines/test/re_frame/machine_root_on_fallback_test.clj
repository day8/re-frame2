(ns re-frame.machine-root-on-fallback-test
  "The machine root's own `:on` is the fallback `pick-transition` consults last
  (Spec 005 §Transition resolution); when even it misses, a user event emits the
  benign `:rf.machine.event/unhandled-no-op`. The `machine-root-on-fallback`
  conformance fixture pins its target resolution and guard."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest root-on-wildcard-fires-for-unhandled-event
  (let [hits (atom 0)]
    (rf/reg-machine :rem/root-wild
      {:initial :a
       :actions {:tap (fn [_] (swap! hits inc) nil)}
       :on      {:* {:action :tap}}
       :states  {:a {}}})
    (rf/dispatch-sync [:rem/root-wild [:anything]])
    (is (= 1 @hits) "the root :* fired for the unhandled event")
    (is (= :a (rf.machines.test-support/machine-state :rem/root-wild))
        "targetless, so the state is unchanged")))

(deftest unhandled-event-emits-benign-no-op
  ;; Through the runtime the live actor instance addresses the no-op, and its
  ;; op-type is the machine-activity family, not an error severity.
  (rf/reg-machine :rem/unhandled {:initial :a :states {:a {:on {:known {:target :a}}}}})
  (rf.machines.test-support/with-trace-capture seen
    (rf/dispatch-sync [:rem/unhandled [:nope]])
    (is (= [[:rf.machine {:actor-id :rem/unhandled :event [:nope] :state :a}]]
           (->> @seen
                (filter #(= :rf.machine.event/unhandled-no-op (:operation %)))
                (mapv (juxt :op-type #(select-keys (:tags %) [:actor-id :event :state]))))))))

(deftest root-on-refs-validated-at-registration
  (let [e (try (rf.machines/validate-machine!
                 {:initial :a
                  :on      {:go {:target :a :guard :missing?}}
                  :states  {:a {}}})
               nil (catch clojure.lang.ExceptionInfo ex ex))]
    (is (= :rf.error/machine-unresolved-guard (:rf.error/id (ex-data e))))))
