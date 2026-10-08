(ns re-frame.update-snapshot-schema-test
  "The `:rf.machine/update-snapshot` escape hatch validates the would-be-merged
  `:data` against the actor's `[:schemas :data]` before writing, and keeps the
  `:db` hard-disallow (Spec 005 §Snapshot-level escape hatch)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private snapshot rf.machines.test-support/snapshot)

(def ^:private n-schema [:map [:n pos-int?]])

(defn- traces-of [op f]
  (rf.machines.test-support/with-trace-capture traces
    (f)
    (filterv #(= op (:operation %)) @traces)))

(defn- reg-patcher!
  "Register and start machine `id` (merged with `spec`), whose `:go` emits the
  escape-hatch `patch` addressed to actor `target`."
  [id spec target patch]
  (rf/reg-machine id
    (merge {:initial :idle
            :actions {:patch (fn [_] {:fx [[:rf.machine/update-snapshot
                                            {:rf/machine-id target :rf/patch patch}]]})}
            :states  {:idle {:on {:go {:target :idle :action :patch}}}}}
           spec))
  (rf/dispatch-sync [id [:noop]]))

(deftest update-snapshot-bad-data-patch-rejected
  (testing "a patch whose merged :data violates [:schemas :data] is not written, and one
            :phase :update-snapshot trace fires"
    (reg-patcher! :upd/singleton {:data {:n 1} :schemas {:data n-schema}} :upd/singleton {:data {:n 0}})
    (let [traces (traces-of :rf.error/schema-validation-failure
                            #(rf/dispatch-sync [:upd/singleton [:go]]))]
      (is (= [{:where :machine-data :machine-id :upd/singleton :failing-id :upd/singleton
               :phase :update-snapshot :value {:n 0} :rollback? false :recovery :no-recovery}]
             (mapv #(assoc (select-keys (:tags %) [:where :machine-id :failing-id :phase :value :rollback?])
                           :recovery (:recovery %))
                   traces)))
      (is (= {:n 1} (:data (snapshot :upd/singleton)))))))

(deftest update-snapshot-spawned-actor-bad-data-rejected
  (testing "a spawned actor has no per-instance handler, so its schema resolves off
            :rf/machine-type and its violating patch is rejected too"
    (let [child {:initial :booting
                 :data    {:n 1}
                 :schemas {:data n-schema}
                 :actions {:self-break (fn [_] {:fx [[:rf.machine/update-snapshot
                                                      {:rf/machine-id :upd/child
                                                       :rf/patch      {:data {:n 0}}}]]})}
                 :states  {:booting {:on {:tick {:target :running :action :self-break}}}
                           :running {}}}]
      (rf/reg-machine :upd/spawn-parent
        {:initial :start
         :states  {:start    {:on {:go :spawning}}
                   :spawning {:entry (fn [_] {:fx [[:rf.machine/spawn {:fixed-actor-id :upd/child
                                                                       :definition     child}]]})}}})
      (rf/dispatch-sync [:upd/spawn-parent [:go]])
      (let [traces (traces-of :rf.error/schema-validation-failure
                              #(rf/dispatch-sync [:upd/child [:tick]]))]
        (is (= [{:where :machine-data :phase :update-snapshot :machine-id :upd/child}]
               (mapv #(select-keys (:tags %) [:where :phase :machine-id]) traces)))
        (is (= 1 (:n (:data (snapshot :upd/child)))))))))

(deftest update-snapshot-missing-actor-noop
  (testing "a patch addressed to an actor with no snapshot writes and validates nothing,
            even when that actor's type has a schema"
    (rf/reg-machine :upd/ghost {:initial :idle :data {:n 1} :schemas {:data n-schema} :states {:idle {}}})
    (reg-patcher! :upd/driver {} :upd/ghost {:data {:n 0}})
    (is (empty? (traces-of :rf.error/schema-validation-failure
                           #(rf/dispatch-sync [:upd/driver [:go]]))))
    (is (nil? (snapshot :upd/ghost)))))

(deftest update-snapshot-db-key-still-rejected
  (testing "a :db key in the patch surfaces :rf.error/machine-action-wrote-db and is dropped,
            while the valid :data beside it installs"
    (reg-patcher! :upd/db-key {:data {:n 1} :schemas {:data n-schema}}
                  :upd/db-key {:data {:n 7} :db {:nope true}})
    (is (= 1 (count (traces-of :rf.error/machine-action-wrote-db
                               #(rf/dispatch-sync [:upd/db-key [:go]])))))
    (is (= {:data {:n 7}} (select-keys (snapshot :upd/db-key) [:data :db])))))
