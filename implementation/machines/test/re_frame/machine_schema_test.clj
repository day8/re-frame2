(ns re-frame.machine-schema-test
  "Verifies the `[:schemas :data]` machine-data schema on `reg-machine` and the
  `:where :machine-data` validation boundary.

  The contract under test:

   1. **Acceptance.** `reg-machine` accepts a machine-level `[:schemas :data]`
      schema on the machine spec; registration completes without error and the
      registered spec carries the schema through `(rf/machine-meta id)`.

   2. **Macrostep boundary.** After a transition action returns a new
      `:data` that violates the schema, the runtime emits
      `:rf.error/schema-validation-failure :where :machine-data` and
      rolls back the entire cascade — `app-db` returns to its pre-event
      value (so the violating snapshot never sticks).

   3. **Initial-data validation (bootstrap).** A machine whose initial
      `:data` violates the schema emits the same trace on its first
      dispatch (the bootstrap commits the initial snapshot to app-db,
      so the post-commit walker catches the typo).

   4. **Spawn-time validation.** A spawned actor whose initial `:data`
      violates the schema emits the same trace with `:phase :spawn`
      and the install is skipped — the actor never enters the runtime.

   5. **No schema → no validation.** Machines without `[:schemas :data]` are
      unaffected; the framework's existing behaviour is preserved.

   6. **Tag payload.** Failures carry `:machine-id`, `:phase`, `:value`,
      `:explain`, `:rollback?`, `:recovery` so the Xray per-step attachment
      and other downstream consumers have the full surface.

  Tests use Malli schemas (the framework default validator)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as machines]
            [re-frame.registrar :as registrar]
            ;; The schemas artefact ships the registered-validator hot
            ;; path the `:where :machine-data` boundary routes through;
            ;; the `.malli` adapter ns publishes Malli's validate/explain
            ;; into the late-bind table.
            [re-frame.machines.test-support :as mtest]
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- collect-traces!
  "Run `f` while collecting every `:rf.error/schema-validation-failure`
  trace event. Returns the captured trace events as a vector. Routed through
  the shared `mtest/with-trace-capture` — guaranteed unregister in a
  `finally`."
  [f]
  (mtest/with-trace-capture traces
    (f)
    (filterv #(= :rf.error/schema-validation-failure (:operation %))
             @traces)))

;; ---- (1) acceptance: `[:schemas :data]` is accepted on reg-machine -------

(deftest reg-machine-accepts-schema-key
  (testing "reg-machine completes registration when the spec carries [:schemas :data]"
    (let [DataSchema [:map [:n :int]]
          spec       {:initial :idle
                      :data    {:n 0}
                      :schemas {:data DataSchema}
                      :states  {:idle {}}}]
      (rf/reg-machine :rf.machine-schema/accepted spec)
      (let [meta (machines/machine-meta :rf.machine-schema/accepted)]
        (is (some? meta) "machine-meta returns the registered spec")
        (is (= DataSchema (get-in meta [:schemas :data]))
            "the [:schemas :data] schema round-trips through machine-meta")))))

;; ---- (2) macrostep boundary: action returns bad :data → rollback + emit --

(deftest macrostep-violation-rolls-back-and-emits
  (testing "an action returning bad :data triggers a :where :machine-data
            trace AND rolls back the cascade"
    (let [DataSchema [:map [:n pos-int?]]
          spec       {:initial :idle
                      :data    {:n 1}
                      :schemas {:data DataSchema}
                      :actions {:break (fn [_] {:data {:n 0}})}     ;; 0 violates pos-int?
                      :states  {:idle {:on {:go {:target :ran
                                                 :action :break}}}
                                :ran  {}}}]
      (rf/reg-machine :rf.machine-schema/macrostep spec)
      ;; Bring the machine to life with an event that doesn't violate the schema —
      ;; bootstrap settles to {:n 1} cleanly.
      (rf/dispatch-sync [:rf.machine-schema/macrostep [:noop]])
      (let [snap-before (get-in (rf/runtime-db-value :rf/default)
                                [:rf.runtime/machines :snapshots :rf.machine-schema/macrostep])
            db-before   (rf/runtime-db-value :rf/default)
            traces      (collect-traces!
                          #(rf/dispatch-sync [:rf.machine-schema/macrostep [:go]]))
            trace-ev    (first traces)
            tag         (:tags trace-ev)]
        (is (= 1 (count traces))
            "exactly one :where :machine-data trace fired on the violating macrostep")
        (is (= :machine-data (:where tag))
            "trace's :where tag pins the new boundary")
        (is (= :rf.machine-schema/macrostep (:machine-id tag))
            "tag carries :machine-id identifying the failing machine")
        (is (= :rf.machine-schema/macrostep (:failing-id tag))
            "tag carries :failing-id alias for uniform error-emit projection")
        (is (= :macrostep (:phase tag))
            "tag's :phase pins the lifecycle position")
        (is (= {:n 0} (:value tag))
            "tag carries the offending :data value")
        (is (true? (:rollback? tag))
            "tag declares :rollback? true (commit was rolled back)")
        ;; `:recovery` is hoisted onto the trace envelope, not inside
        ;; `:tags` — mirrors the `:where :app-db` projection.
        (is (= :no-recovery (:recovery trace-ev))
            "trace envelope declares :no-recovery (consistent with :where :app-db)")
        ;; Rollback restores pre-handler app-db.
        (is (= db-before (rf/runtime-db-value :rf/default))
            "post-rollback app-db equals pre-handler app-db")
        (is (= snap-before
               (get-in (rf/runtime-db-value :rf/default)
                       [:rf.runtime/machines :snapshots :rf.machine-schema/macrostep]))
            "the machine's snapshot returns to its pre-handler value")))))

;; ---- (2b) macrostep boundary on a SPAWNED actor -------------

(deftest spawned-actor-macrostep-violation-rolls-back-and-emits
  (testing "a SPAWNED actor (no per-instance handler) whose transition action
            returns schema-violating :data must roll back the macrostep and
            emit :where :machine-data — the schema resolves off the snapshot's
            :rf/machine-type, not via machine-meta (rf2-2t9xn3)"
    (let [ChildSchema [:map [:n pos-int?]]
          ;; The child boots with valid :data {:n 1}; a :tick transition runs
          ;; the :break action returning {:data {:n 0}} (violates pos-int?) via
          ;; the ordinary macrostep path (NOT the escape hatch).
          child-spec  {:initial :booting
                       :data    {:n 1}                ;; valid at spawn
                       :schemas {:data ChildSchema}
                       :actions {:break (fn [_] {:data {:n 0}})}
                       :states  {:booting {:on {:tick {:target :running
                                                       :action :break}}}
                                 :running {}}}
          parent-spec {:initial :start
                       :data    {}
                       :states  {:start    {:on {:go :spawning}}
                                 :spawning {:entry
                                            (fn [_]
                                              {:fx [[:rf.machine/spawn
                                                     {:fixed-actor-id :rf.machine-schema/spawned-macrostep
                                                      :definition     child-spec}]]})}}}]
      (rf/reg-machine :rf.machine-schema/spawn-macrostep-parent parent-spec)
      (rf/dispatch-sync [:rf.machine-schema/spawn-macrostep-parent [:noop]])
      ;; Spawn the child (valid :data {:n 1} → installs).
      (rf/dispatch-sync [:rf.machine-schema/spawn-macrostep-parent [:go]])
      (is (= 1 (:n (:data (get-in (rf/runtime-db-value :rf/default)
                                  [:rf.runtime/machines :snapshots
                                   :rf.machine-schema/spawned-macrostep]))))
          "precondition: the spawned child installed with valid :data {:n 1}")
      (let [snap-before (get-in (rf/runtime-db-value :rf/default)
                                [:rf.runtime/machines :snapshots
                                 :rf.machine-schema/spawned-macrostep])
            db-before   (rf/runtime-db-value :rf/default)
            traces      (collect-traces!
                          #(rf/dispatch-sync [:rf.machine-schema/spawned-macrostep [:tick]]))
            trace-ev    (first traces)
            tag         (:tags trace-ev)]
        (is (= 1 (count traces))
            "exactly one :where :machine-data trace fired on the violating macrostep")
        (is (= :machine-data (:where tag))
            "trace's :where tag pins the boundary")
        (is (= :rf.machine-schema/spawned-macrostep (:machine-id tag))
            "tag carries :machine-id identifying the failing spawned actor")
        (is (= :macrostep (:phase tag))
            "tag's :phase pins the macrostep lifecycle position")
        (is (zero? (:n (:value tag)))
            "tag carries the offending :data value (:n 0)")
        (is (true? (:rollback? tag))
            "tag declares :rollback? true (commit must be rolled back)")
        ;; The whole point: the macrostep ROLLS BACK — the violating :data
        ;; does not commit. The schema resolves off the snapshot's
        ;; :rf/machine-type (not via machine-meta, which returns nil for a
        ;; spawned actor), so the validation runs and the rollback fires.
        (is (= snap-before
               (get-in (rf/runtime-db-value :rf/default)
                       [:rf.runtime/machines :snapshots
                        :rf.machine-schema/spawned-macrostep]))
            "the spawned actor's snapshot returns to its pre-handler value (rolled back)")
        (is (= 1 (:n (:data (get-in (rf/runtime-db-value :rf/default)
                                    [:rf.runtime/machines :snapshots
                                     :rf.machine-schema/spawned-macrostep]))))
            "the violating :data {:n 0} did NOT commit — :n stays the valid 1")
        (is (= db-before (rf/runtime-db-value :rf/default))
            "post-rollback runtime-db equals pre-handler runtime-db")))))

;; ---- (3) bootstrap-time validation: initial :data violates --------------

(deftest bootstrap-violation-emits-and-rolls-back
  (testing "an initial :data that violates [:schemas :data] emits + rolls back the
            first dispatch's bootstrap commit"
    (let [DataSchema [:map [:n pos-int?]]
          ;; Typo: :n is 0 — violates pos-int? on bootstrap.
          spec       {:initial :idle
                      :data    {:n 0}
                      :schemas {:data DataSchema}
                      :states  {:idle {}}}]
      (rf/reg-machine :rf.machine-schema/bootstrap spec)
      (let [db-before (rf/runtime-db-value :rf/default)
            traces    (collect-traces!
                        #(rf/dispatch-sync [:rf.machine-schema/bootstrap [:noop]]))
            tag       (-> traces first :tags)]
        (is (= 1 (count traces))
            "exactly one boundary trace fires for the bootstrap violation")
        (is (= :machine-data (:where tag)))
        (is (= {:n 0} (:value tag)))
        ;; Rollback drops the violating snapshot.
        (is (nil? (get-in (rf/runtime-db-value :rf/default)
                          [:rf.runtime/machines :snapshots :rf.machine-schema/bootstrap]))
            "rolled back: the machine snapshot is not installed in app-db")
        (is (= db-before (rf/runtime-db-value :rf/default))
            "rolled back: app-db unchanged")))))

;; ---- (4) spawn-time validation: spawned actor's :data violates ----------

(deftest spawn-violation-emits-and-skips-install
  (testing "a spawned actor whose initial :data violates the schema is rejected
            at install time; the snapshot never lands in app-db"
    (let [ChildSchema [:map [:n pos-int?]]
          ;; Child spec violates its own schema at bootstrap.
          child-spec  {:initial :idle
                       :data    {:n 0}
                       :schemas {:data ChildSchema}
                       :states  {:idle {}}}
          parent-spec {:initial :starting
                       :data    {}
                       :states  {:starting
                                 {:on {:go :spawning}}
                                 :spawning
                                 {:entry (fn [_]
                                           {:fx [[:rf.machine/spawn
                                                  {:fixed-actor-id :rf.machine-schema/spawned
                                                   :definition     child-spec}]]})}}}]
      (rf/reg-machine :rf.machine-schema/spawn-parent parent-spec)
      (rf/dispatch-sync [:rf.machine-schema/spawn-parent [:noop]])
      (let [traces (collect-traces!
                     #(rf/dispatch-sync [:rf.machine-schema/spawn-parent [:go]]))
            tag    (-> traces first :tags)]
        ;; The spawn-time validation emits exactly one :where :machine-data
        ;; trace with :phase :spawn — no macrostep-phase trace fires for the
        ;; rejected actor because its snapshot never lands in app-db.
        (let [machine-data-traces (filter #(= :machine-data (-> % :tags :where)) traces)]
          (is (= 1 (count machine-data-traces))
              "exactly one :where :machine-data trace fires on spawn rejection"))
        (is (= :machine-data (:where tag)))
        (is (= :spawn (:phase tag))
            ":phase :spawn distinguishes spawn-time from macrostep failures")
        (is (false? (:rollback? tag))
            "spawn-phase failure carries :rollback? false (nothing was committed)")
        ;; The spawned actor's snapshot was never installed.
        (is (nil? (get-in (rf/runtime-db-value :rf/default)
                          [:rf.runtime/machines :snapshots :rf.machine-schema/spawned]))
            "rejected spawn: snapshot is not in app-db")
        ;; Atomic reject: a schema-rejected spawn registers NOTHING —
        ;; no event handler, no `(rf/machines)` entry — the install gate
        ;; and registration are in lockstep.
        (is (nil? (registrar/lookup :event :rf.machine-schema/spawned))
            "rejected spawn: NO event handler is registered (rf2-f3kp7)")
        (is (not (contains? (set (machines/machines)) :rf.machine-schema/spawned))
            "rejected spawn: the actor does NOT appear in (rf/machines) (rf2-f3kp7)")))))

;; ---- (5) no schema → no validation (control) ------------------------------

(deftest no-schema-no-validation
  (testing "a machine without [:schemas :data] runs without any :where :machine-data trace"
    (let [spec {:initial :idle
                :data    {:n "anything goes"}
                :actions {:set-x (fn [_] {:data {:n 42}})}
                :states  {:idle {:on {:go {:target :idle :action :set-x}}}}}]
      (rf/reg-machine :rf.machine-schema/no-schema spec)
      (let [traces (collect-traces!
                     (fn []
                       (rf/dispatch-sync [:rf.machine-schema/no-schema [:noop]])
                       (rf/dispatch-sync [:rf.machine-schema/no-schema [:go]])))]
        (is (empty? traces)
            "no :where :machine-data trace for a no-schema machine")
        ;; Sanity: the action ran and updated :data.
        (is (= 42 (get-in (rf/runtime-db-value :rf/default)
                          [:rf.runtime/machines :snapshots :rf.machine-schema/no-schema :data :n]))
            "the no-schema machine's :data updates normally")))))

;; ---- (6) tag payload completeness for downstream consumers ----------------

(deftest tag-payload-carries-downstream-required-keys
  (testing "the failure tag carries every key Xray's per-step attachment
            consumes (rf2-xgeag) — :machine-id, :phase, :value, :explain,
            :rollback?, :recovery, :reason"
    (let [DataSchema [:map [:n pos-int?]]
          spec       {:initial :idle
                      :data    {:n 1}
                      :schemas {:data DataSchema}
                      :actions {:bad (fn [_] {:data {:n 0}})}
                      :states  {:idle {:on {:go {:target :idle :action :bad}}}}}]
      (rf/reg-machine :rf.machine-schema/payload spec)
      (rf/dispatch-sync [:rf.machine-schema/payload [:noop]])
      (let [traces   (collect-traces!
                       #(rf/dispatch-sync [:rf.machine-schema/payload [:go]]))
            trace-ev (first traces)
            tag      (:tags trace-ev)]
        (is (= :machine-data (:where tag)))
        (is (= :rf.machine-schema/payload (:machine-id tag)))
        (is (= :rf.machine-schema/payload (:failing-id tag)))
        (is (= :macrostep (:phase tag)))
        (is (contains? tag :value)        ":value present (Xray reads the failing slot)")
        (is (contains? tag :explain)      ":explain present (Xray renders Malli explanation)")
        (is (contains? tag :rollback?)    ":rollback? present (Xray's blast-radius muting)")
        ;; `:recovery` rides the trace envelope, not :tags.
        (is (contains? trace-ev :recovery) ":recovery present on trace envelope")
        (is (string? (:reason tag))       ":reason is a human-readable string")))))
