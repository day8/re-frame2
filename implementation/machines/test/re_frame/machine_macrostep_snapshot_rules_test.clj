(ns re-frame.machine-macrostep-snapshot-rules-test
  "What a macrostep reports about its `:always` microsteps (Spec 005 §Trace
  events), and that an action's effect map may write `:data` but never `:db`
  (Spec 005:463). The escape-hatch patch is `update_snapshot_schema_test`'s."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- record-traces! [f]
  (rf.machines.test-support/with-trace-capture seen
    (f)
    @seen))

(defn- tags-of
  "The `ks` of each `op` trace's tags, in emission order."
  [evs op ks]
  (into [] (comp (filter #(= op (:operation %))) (map #(select-keys (:tags %) ks))) evs))

(deftest always-emits-microstep-traces-and-count
  (testing "one :rf.machine.microstep/transition per :always microstep, counted on the outer trace"
    (rf/reg-machine :rem/quiz
      {:initial :asking
       :data    {:correct 9}
       :guards  {:enough? (fn [{d :data}] (>= (:correct d) 10))}
       :actions {:count   (fn [{d :data}] {:data {:correct (inc (:correct d))}})}
       :states  {:asking {:always [{:guard :enough? :target :winner}]
                          :on     {:answer {:action :count}}}
                 :winner {}}})
    (let [evs (record-traces! #(rf/dispatch-sync [:rem/quiz [:answer]]))]
      (is (= [{:from :asking :to :winner :microstep-index 0}]
             (tags-of evs :rf.machine.microstep/transition [:from :to :microstep-index])))
      (is (= [{:microsteps 1}] (tags-of evs :rf.machine/transition [:microsteps]))))))

(deftest no-always-stamps-zero-microsteps
  (rf/reg-machine :rem/plain {:initial :a :states {:a {:on {:go {:target :b}}} :b {}}})
  (is (= [{:microsteps 0}]
         (tags-of (record-traces! #(rf/dispatch-sync [:rem/plain [:go]]))
                  :rf.machine/transition [:microsteps]))))

(deftest always-inside-a-raised-event-counts-toward-microsteps
  (testing "an :always step taken while handling a raised event counts in the outer :microsteps"
    ;; `:go` enters `:a`, whose entry raises `:next`; handling `:next` lands
    ;; on `:b`, whose `:always` moves to `:c` inside that raise's settle.
    (rf/reg-machine :rem/raised-always
      {:initial :start
       :actions {:raise-next (fn [{:keys [data]}] {:data data :fx [[:raise [:next]]]})}
       :states  {:start {:on {:go :a}}
                 :a     {:entry :raise-next :on {:next :b}}
                 :b     {:always :c}
                 :c     {:entry :raise-next :on {:next :d}}
                 :d     {}}})
    (rf/dispatch-sync [:rem/raised-always [:rf.machine/start]])
    (let [evs (record-traces! #(rf/dispatch-sync [:rem/raised-always [:go]]))]
      (is (= :d (rf.machines.test-support/machine-state :rem/raised-always)))
      (is (= 1 (count (tags-of evs :rf.machine.microstep/transition []))))
      (is (= [{:microsteps 1}] (tags-of evs :rf.machine/transition [:microsteps]))))))

(deftest action-returning-db-emits-error-and-drops-db
  (testing "an action effect map carrying :db emits :rf.error/machine-action-wrote-db with the
            offending value redacted at egress; :data still flows and :db is dropped"
    (rf/reg-machine :rem/wrote-db
      {:initial :a
       :actions {:bad (fn [_] {:db {:auth {:token "super-secret-jwt"}} :data {:legit 1}})}
       :states  {:a {:on {:go {:target :b :action :bad}}} :b {}}})
    (let [errs (filterv #(= :rf.error/machine-action-wrote-db (:operation %))
                        (record-traces! #(rf/dispatch-sync [:rem/wrote-db [:go]])))]
      (is (= [{:action-id :bad :offending-value :rf/redacted}]
             (tags-of errs :rf.error/machine-action-wrote-db [:action-id :offending-value])))
      (is (not (re-find #"super-secret-jwt" (pr-str (mapv :tags errs)))))
      (is (= {:state :b :data {:legit 1}}
             (select-keys (rf.machines.test-support/snapshot :rem/wrote-db) [:state :data :db]))))))
