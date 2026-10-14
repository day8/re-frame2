(ns re-frame.machine-root-on-fallback-test
  "The machine root's own `:on` is the fallback `pick-transition` consults last
  (Spec 005 §Transition resolution). The `machine-root-on-fallback` conformance
  fixture pins its target resolution and guard."
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

(deftest root-on-refs-validated-at-registration
  (let [e (try (rf.machines/validate-machine!
                 {:initial :a
                  :on      {:go {:target :a :guard :missing?}}
                  :states  {:a {}}})
               nil (catch clojure.lang.ExceptionInfo ex ex))]
    (is (= :rf.error/machine-unresolved-guard (:rf.error/id (ex-data e))))))
