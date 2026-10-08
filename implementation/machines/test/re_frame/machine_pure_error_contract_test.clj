(ns re-frame.machine-pure-error-contract-test
  "The pure macrostep (`machine-transition`, which never runs `validate-machine!`):
  only an unknown DOMAIN event emits the benign unhandled no-op, and a malformed
  or dangling ref, slot value or `:raise` entry throws its own category."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]))

(deftest only-a-domain-event-emits-the-benign-no-op
  ;; The reserved `:rf/*` root is framework lifecycle traffic, not an unknown user event.
  (let [definition {:id :probe/unhandled :initial :a :states {:a {:on {:known :a}}}}]
    (doseq [[event expected]
            [[[:nope]                    [{:op-type :rf.machine :actor-id :probe/unhandled :event [:nope] :state :a}]]
             [[:rf.machine.spawn/spawned] []]
             [[:rf/anything]              []]]]
      (is (= expected
             (rf.machines.test-support/with-trace-capture seen
               (rf.machines/machine-transition definition {:state :a :data {}} event)
               (into []
                     (comp (filter #(= :rf.machine.event/unhandled-no-op (:operation %)))
                           (map #(assoc (select-keys (:tags %) [:actor-id :event :state])
                                        :op-type (:op-type %))))
                     @seen)))
          (pr-str event)))))

(def ^:private raise-with-options [:raise [:search] {:delay 300 :id :deb}])

(def ^:private raising-actions {:go (fn [_] {:fx [raise-with-options]})})

(defn- on-go
  "A machine whose `:a` takes `t` on `[:go]`, with `extra` merged in."
  ([t] (on-go t {}))
  ([t extra] (merge {:id :probe/m :initial :a :data {} :states {:a {:on {:go t}} :b {}}} extra)))

(deftest malformed-runtime-input-throws-its-category-out-of-the-macrostep
  ;; Refs resolve outside the guard / action try, so a bad form is never swallowed.
  (doseq [[definition state expected]
          [[(on-go {:target :b :guard "not-a-guard"}) :a
            {:rf.error/id :rf.error/machine-bad-guard-form :guard "not-a-guard"}]
           [(on-go {:target :b :action 99}) :a
            {:rf.error/id :rf.error/machine-bad-action-form :action 99}]
           [(on-go {:target :b :guard :nope}) :a
            {:rf.error/id :rf.error/machine-unresolved-guard :guard :nope :machine-id :probe/m}]
           [(on-go {:target :b :action :nope}) :a
            {:rf.error/id :rf.error/machine-unresolved-action :action :nope :machine-id :probe/m}]
           [{:id :probe/m :initial :a :data {} :states {:a {:on {:go :b}} :b {:always 42}}} :a
            {:rf.error/id :rf.error/machine-bad-always :value 42}]
           [(on-go {:action :go} {:actions raising-actions}) :a
            {:rf.error/id :rf.error/machine-bad-raise :value raise-with-options}]
           [{:id :probe/p :type :parallel :data {} :actions raising-actions
             :regions {:r {:initial :a :states {:a {:on {:go {:action :go}}}}}}}
            {:r :a}
            {:rf.error/id :rf.error/machine-bad-raise :value raise-with-options}]]]
    (is (= (assoc expected :where 'rf/reg-machine)
           (select-keys (try (rf.machines/machine-transition definition {:state state :data {}} [:go])
                             nil
                             (catch clojure.lang.ExceptionInfo ex (ex-data ex)))
                        (conj (keys expected) :where)))
        (pr-str definition))))
