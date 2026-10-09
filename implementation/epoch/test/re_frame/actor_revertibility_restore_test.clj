(ns re-frame.actor-revertibility-restore-test
  "Restoring frame state reverts actor liveness. A spawned actor has no
  per-instance registration: it is alive while its snapshot sits in the
  runtime-db partition, and its TYPE rides that snapshot under
  `:rf/machine-type`. So `restore-epoch!` must remove an actor when rewinding
  past its spawn, revive it when rewinding past its destroy, and resolve its
  TYPE for the missing-handler and version-drift preconditions."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.registrar :as rf.registrar]
            [re-frame.elision]
            [re-frame.epoch :as rf.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            ;; Publishes the machine hooks and the `:rf.machine/spawn` /
            ;; `:rf.machine/destroy` fxs; the fixture keeps those ns-load
            ;; registrations across tests.
            [re-frame.machines]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (rf.epoch/clear-history!)
                (rf.epoch/clear-epoch-listeners!))}))

(defn- snapshot [machine-id]
  (get-in (:rf.db/runtime (rf/frame-state-value :test/main))
          [:rf.runtime/machines :snapshots machine-id]))

(defn- last-epoch-id []
  (:epoch-id (last (rf/epoch-history :test/main))))

(defn- traces-of
  "Run `f` and return the trace events it emitted."
  [f]
  (let [recorded (atom [])]
    (rf/register-listener! :trace ::rec (fn [ev] (swap! recorded conj ev)))
    (try (f) (finally (rf/unregister-listener! :trace ::rec)))
    @recorded))

(defn- op [operation traces]
  (some #(when (= operation (:operation %)) %) traces))

(defn- child
  ([] (child nil))
  ([version]
   (cond-> {:initial :live
            :data    {:n 0}
            :actions {:bump (fn [{data :data}] {:data (update data :n inc)})}
            :states  {:live {:on {:bump {:action :bump}}}}}
     version (assoc :meta {:rf/snapshot-version version}))))

(defn- parent
  "Spawns a `child-type` (a registered machine-id, or an inline definition)
  as `:rev/child#1` on `:go`, and destroys it on `:drop`."
  [child-type]
  {:initial :idle
   :data    {}
   :states  {:idle {:on {:go   {:action (fn [_]
                                          {:fx [[:rf.machine/spawn
                                                 (if (map? child-type)
                                                   {:id-prefix :rev/child :definition child-type}
                                                   {:id-prefix :rev/child :machine-id child-type})]]})}
                         :drop {:action (fn [_]
                                          {:fx [[:rf.machine/destroy :rev/child#1]]})}}}}})

(defn- spawn-child!
  "Spawn `:rev/child#1` of `child-type` on `:test/main` (which must exist);
  return the epoch id the spawn settled."
  [child-type]
  (rf/reg-machine :rev/parent (parent child-type))
  (rf/dispatch-sync [:rev/parent [:go]] {:frame :test/main})
  (last-epoch-id))

(deftest restore-past-spawn-leaves-no-orphan
  (rf/make-frame {:id :test/main})
  (rf/reg-machine :rev/child (child))
  (rf/reg-event :test/noop (fn [{:keys [db]} _] {:db (assoc db :seeded true)}))
  (rf/dispatch-sync [:test/noop] {:frame :test/main})
  (let [pre-spawn-epoch (last-epoch-id)]
    (spawn-child! :rev/child)
    (is (some? (snapshot :rev/child#1)) "precondition: the actor is alive")
    (is (true? (rf/restore-epoch! :test/main pre-spawn-epoch)))
    (is (nil? (snapshot :rev/child#1)) "the actor's snapshot is gone")
    (is (op :rf.error/no-such-handler
            (traces-of #(rf/dispatch-sync [:rev/child#1 [:bump]] {:frame :test/main})))
        "no orphaned handler survives: a dispatch to the gone actor is no-such-handler")))

(deftest restore-past-destroy-rematerialises-liveness
  (rf/make-frame {:id :test/main})
  (rf/reg-machine :rev/child (child))
  (let [alive-epoch (spawn-child! :rev/child)]
    (rf/dispatch-sync [:rev/child#1 [:bump]] {:frame :test/main})
    (rf/dispatch-sync [:rev/parent [:drop]] {:frame :test/main})
    (is (nil? (snapshot :rev/child#1)) "precondition: the actor is destroyed")
    (is (true? (rf/restore-epoch! :test/main alive-epoch)))
    (rf/dispatch-sync [:rev/child#1 [:bump]] {:frame :test/main})
    (is (= 1 (:n (:data (snapshot :rev/child#1))))
        "the restored snapshot (:n 0) resolves lazily and transitions")))

(deftest restore-with-missing-type-still-fails-missing-handler
  (rf/make-frame {:id :test/main})
  (rf/reg-machine :rev/child (child 1))
  (let [alive-epoch (spawn-child! :rev/child)]
    (rf.registrar/unregister! :event :rev/child)
    (let [ok?    (atom nil)
          traces (traces-of #(reset! ok? (rf/restore-epoch! :test/main alive-epoch)))]
      (is (false? @ok?) "restore refused: the actor's TYPE is gone")
      (is (some #(= :rev/child#1 (:id %))
                (-> (op :rf.epoch/restore-missing-handler traces) :tags :missing))
          "the unresolvable spawned actor surfaces in :missing"))))

(deftest restore-spawned-actor-version-mismatch-registered-type-fails
  (rf/make-frame {:id :test/main})
  (rf/reg-machine :rev/child (child 1))
  (let [alive-epoch (spawn-child! :rev/child)]
    (is (true? (rf/restore-epoch! :test/main alive-epoch))
        "control: an unchanged TYPE version restores")
    (rf/reg-machine :rev/child (child 2))
    (let [ok?    (atom nil)
          traces (traces-of #(reset! ok? (rf/restore-epoch! :test/main alive-epoch)))]
      (is (false? @ok?) "a hot-reloaded TYPE version refuses the restore")
      (is (= {:machine-id :rev/child#1 :machine-type :rev/child
              :version-recorded 1 :version-current 2}
             (-> (op :rf.epoch/restore-version-mismatch traces)
                 :tags
                 (select-keys [:machine-id :machine-type :version-recorded :version-current])))
          "the trace names the instance id and the TYPE"))))

(deftest restore-spawned-actor-inline-definition-version-match-succeeds
  (rf/make-frame {:id :test/main})
  (let [alive-epoch (spawn-child! (child 1))]
    (is (map? (:rf/machine-type (snapshot :rev/child#1)))
        "precondition: the snapshot carries the inline definition")
    (is (true? (rf/restore-epoch! :test/main alive-epoch))
        "an inline definition resolves from the snapshot itself")))
