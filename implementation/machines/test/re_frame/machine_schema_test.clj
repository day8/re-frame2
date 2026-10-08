(ns re-frame.machine-schema-test
  "Verifies the `[:schemas :data]` machine-data schema on `reg-machine` and the
  `:where :machine-data` validation boundary.

  The contract under test:

   1. **Macrostep boundary.** After a transition action returns a new
      `:data` that violates the schema, the runtime emits
      `:rf.error/schema-validation-failure :where :machine-data` and
      rolls back the entire cascade — runtime-db returns to its pre-event
      value (so the violating snapshot never sticks). The failure tag
      carries `:machine-id`, `:phase`, `:value`, `:explain`, `:rollback?`,
      `:reason` and the `:recovery` envelope, so the Xray per-step
      attachment and other downstream consumers have the full surface.

   2. **Initial-data validation (bootstrap).** A machine whose initial
      `:data` violates the schema emits the same trace on its first
      dispatch (the bootstrap writes the initial snapshot into the
      candidate runtime-db, so the pre-commit walker catches the typo).

   3. **Spawn-time validation.** A spawned actor whose initial `:data`
      violates the schema emits the same trace with `:phase :spawn`
      and the install is skipped — the actor never enters the runtime.

  That a machine without `[:schemas :data]` runs no machine-data validation is
  pinned in `update_snapshot_schema_test`.

  That `reg-machine` accepts `[:schemas :data]` and round-trips it through
  the `:rf/machine` projection is pinned in `machine_schemas_grammar_test`.

  Tests use Malli schemas (the framework default validator)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.registrar :as rf.registrar]
            ;; The schemas artefact ships the registered-validator hot
            ;; path the `:where :machine-data` boundary routes through;
            ;; the `.malli` adapter ns publishes Malli's validate/explain
            ;; into the late-bind table.
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- collect-traces!
  "Run `f` while collecting every `:rf.error/schema-validation-failure`
  trace event. Returns the captured trace events as a vector. Routed through
  the shared `rf.machines.test-support/with-trace-capture` — guaranteed unregister in a
  `finally`."
  [f]
  (rf.machines.test-support/with-trace-capture traces
    (f)
    (filterv #(= :rf.error/schema-validation-failure (:operation %))
             @traces)))

;; ---- (1) macrostep boundary: action returns bad :data → rollback + emit --

(deftest macrostep-violation-rolls-back-and-emits
  (testing "an action returning bad :data triggers a :where :machine-data
            trace AND rolls back the cascade"
    (rf/reg-machine :rf.machine-schema/macrostep
      {:initial :idle
       :data    {:n 1}
       :schemas {:data [:map [:n pos-int?]]}
       :actions {:break (fn [_] {:data {:n 0}})}
       :states  {:idle {:on {:go {:target :ran :action :break}}}
                 :ran  {}}})
    (rf/dispatch-sync [:rf.machine-schema/macrostep [:noop]])
    (let [db-before         (:rf.db/runtime (rf/frame-state-value :rf/default))
          [ev :as traces]   (collect-traces!
                              #(rf/dispatch-sync [:rf.machine-schema/macrostep [:go]]))
          tag               (:tags ev)]
      (is (= 1 (count traces)))
      (is (= {:where      :machine-data
              :machine-id :rf.machine-schema/macrostep
              :failing-id :rf.machine-schema/macrostep
              :phase      :macrostep
              :value      {:n 0}
              :rollback?  true}
             (select-keys tag [:where :machine-id :failing-id :phase :value :rollback?])))
      (is (= [true true :no-recovery]
             [(contains? tag :explain) (string? (:reason tag)) (:recovery ev)]))
      (is (= db-before (:rf.db/runtime (rf/frame-state-value :rf/default)))
          "post-rollback runtime-db (snapshot included) equals pre-handler runtime-db"))))

;; ---- (1b) macrostep boundary on a SPAWNED actor -------------

(deftest spawned-actor-macrostep-violation-rolls-back-and-emits
  (testing "a SPAWNED actor (no per-instance handler) whose transition action
            returns schema-violating :data must roll back the macrostep and
            emit :where :machine-data — the schema resolves off the snapshot's
            :rf/machine-type, not via the registry projection"
    (let [child-spec  {:initial :booting
                       :data    {:n 1}
                       :schemas {:data [:map [:n pos-int?]]}
                       :actions {:break (fn [_] {:data {:n 0}})}
                       :states  {:booting {:on {:tick {:target :running
                                                       :action :break}}}
                                 :running {}}}]
      (rf/reg-machine :rf.machine-schema/spawn-macrostep-parent
        {:initial :start
         :data    {}
         :states  {:start    {:on {:go :spawning}}
                   :spawning {:entry
                              (fn [_]
                                {:fx [[:rf.machine/spawn
                                       {:fixed-actor-id :rf.machine-schema/spawned-macrostep
                                        :definition     child-spec}]]})}}})
      (rf/dispatch-sync [:rf.machine-schema/spawn-macrostep-parent [:noop]])
      (rf/dispatch-sync [:rf.machine-schema/spawn-macrostep-parent [:go]])
      (let [db-before (:rf.db/runtime (rf/frame-state-value :rf/default))
            traces    (collect-traces!
                        #(rf/dispatch-sync [:rf.machine-schema/spawned-macrostep [:tick]]))]
        (is (= [{:where :machine-data :machine-id :rf.machine-schema/spawned-macrostep
                 :phase :macrostep :value 0 :rollback? true}]
               (mapv #(-> (:tags %)
                          (select-keys [:where :machine-id :phase :value :rollback?])
                          (update :value :n))
                     traces)))
        (is (= [1 db-before]
               [(get-in db-before [:rf.runtime/machines :snapshots
                                   :rf.machine-schema/spawned-macrostep :data :n])
                (:rf.db/runtime (rf/frame-state-value :rf/default))])
            "the child installed with valid :data, and the violating macrostep rolled back")))))

;; ---- (2) bootstrap-time validation: initial :data violates --------------

(deftest bootstrap-violation-emits-and-rolls-back
  (testing "an initial :data that violates [:schemas :data] emits + rolls back the
            first dispatch's bootstrap commit"
    (rf/reg-machine :rf.machine-schema/bootstrap
      {:initial :idle
       :data    {:n 0}
       :schemas {:data [:map [:n pos-int?]]}
       :states  {:idle {}}})
    (let [db-before (:rf.db/runtime (rf/frame-state-value :rf/default))
          traces    (collect-traces!
                      #(rf/dispatch-sync [:rf.machine-schema/bootstrap [:noop]]))]
      (is (= [{:where :machine-data :value {:n 0}}]
             (mapv #(select-keys (:tags %) [:where :value]) traces)))
      (is (= db-before (:rf.db/runtime (rf/frame-state-value :rf/default)))
          "rolled back: runtime-db unchanged, so no snapshot installed"))))

;; ---- (3) spawn-time validation: spawned actor's :data violates ----------

(deftest spawn-violation-emits-and-skips-install
  (testing "a spawned actor whose initial :data violates the schema is rejected
            at install time; the snapshot never lands in runtime-db"
    (let [child-spec {:initial :idle
                      :data    {:n 0}
                      :schemas {:data [:map [:n pos-int?]]}
                      :states  {:idle {}}}]
      (rf/reg-machine :rf.machine-schema/spawn-parent
        {:initial :starting
         :data    {}
         :states  {:starting {:on {:go :spawning}}
                   :spawning {:entry (fn [_]
                                       {:fx [[:rf.machine/spawn
                                              {:fixed-actor-id :rf.machine-schema/spawned
                                               :definition     child-spec}]]})}}})
      (rf/dispatch-sync [:rf.machine-schema/spawn-parent [:noop]])
      (let [traces (collect-traces!
                     #(rf/dispatch-sync [:rf.machine-schema/spawn-parent [:go]]))]
        (is (= [{:where :machine-data :phase :spawn :rollback? false}]
               (->> traces
                    (filter #(= :machine-data (-> % :tags :where)))
                    (mapv #(select-keys (:tags %) [:where :phase :rollback?])))))
        (is (= [nil nil]
               [(get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                        [:rf.runtime/machines :snapshots :rf.machine-schema/spawned])
                (rf.registrar/lookup :event :rf.machine-schema/spawned)])
            "rejected spawn: no snapshot and no event handler")))))

;; ---- (4) declaration presence is KEY-presence -----------------------------
;;
;; A schema value is OPAQUE to re-frame (Spec 010): an ABSENT [:schemas :data]
;; key means "no declaration", while a PRESENT key must hand
;; its exact value — nil included — to the registered validator. Seams that
;; tested the value for truthiness (if-let / `(and (continue?) schema)`)
;; would let `{:schemas {:data nil}}` silently validate nothing.

(deftest present-nil-data-schema-is-delegated-not-skipped
  (testing "a machine registered with {:schemas {:data nil}} delegates the
            exact nil token to a custom validator at the bootstrap commit;
            the false verdict emits :where :machine-data and rolls back"
    (let [seen (atom [])]
      (re-frame.schemas/set-schema-fns!
        {:validate (fn [schema _value] (swap! seen conj schema) false)})
      (try
        (rf/reg-machine :rf.machine-schema/nil-declared
          {:initial :idle
           :data    {:n 1}
           :schemas {:data nil}
           :states  {:idle {}}})
        (let [traces (collect-traces!
                       #(rf/dispatch-sync [:rf.machine-schema/nil-declared [:noop]]))]
          (is (= [[nil] [:machine-data] nil]
                 [@seen
                  (mapv (comp :where :tags) traces)
                  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                          [:rf.runtime/machines :snapshots :rf.machine-schema/nil-declared])])))
        (finally
          (re-frame.schemas/set-schema-fns! re-frame.schemas/default-schema-fns))))))

(deftest present-nil-data-schema-fails-closed-under-default-malli
  (testing "with the DEFAULT Malli validator a present nil [:schemas :data]
            fails CLOSED: the seam isolates Malli's throw to a false verdict,
            so the bootstrap commit is rejected, never installed unvalidated"
    (rf/reg-machine :rf.machine-schema/nil-malli
      {:initial :idle
       :data    {:n 1}
       :schemas {:data nil}
       :states  {:idle {}}})
    (let [traces (collect-traces!
                   #(rf/dispatch-sync [:rf.machine-schema/nil-malli [:noop]]))]
      (is (= [[:machine-data] nil]
             [(mapv (comp :where :tags) traces)
              (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                      [:rf.runtime/machines :snapshots :rf.machine-schema/nil-malli])])))))
