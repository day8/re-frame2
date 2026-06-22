(ns re-frame.update-snapshot-schema-test
  "The `:rf.machine/update-snapshot` escape hatch must NOT bypass the
  `:where :machine-data` boundary. Spec 005 §Snapshot-level escape hatch:
  user error/status state lives under `:data` *where `[:schemas :data]`
  validation covers it*. The fx validates the would-be-merged snapshot's
  `:data` against the actor's `[:schemas :data]` schema BEFORE writing, and a
  violating patch is rejected — the invalid `:data` never installs.

  Contract under test:

   1. **Adversarial (singleton).** An escape-hatch `:data` patch that
      violates the schema is REJECTED: the fx skips the write, the prior
      valid `:data` survives, and one `:where :machine-data
      :phase :update-snapshot :rollback? false` trace fires.
   2. **Valid patch installs.** A conforming `:data` patch merges as
      before; no trace.
   3. **No-schema machine.** A machine without `[:schemas :data]` patches
      freely; no trace (the boundary is opt-in via the schema).
   4. **Spawned actor.** An escape-hatch `:data` patch on a SPAWNED actor
      (schema resolved off the snapshot's `:rf/machine-type`, not via a
      per-instance handler) is validated and rejected on violation.
   5. **Missing / destroyed actor.** A patch addressed to an actor with
      no snapshot is a no-op — no write, no trace.
   6. **`:db` hard-disallow.** A `:db` key surfaces
      `:rf.error/machine-action-wrote-db` and is dropped.

  Uses Malli (the framework default validator)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- collect-traces!
  "Run `f` while capturing every trace event; return the
  `:rf.error/schema-validation-failure` events as a vector."
  [f]
  (mtest/with-trace-capture traces
    (f)
    (filterv #(= :rf.error/schema-validation-failure (:operation %))
             @traces)))

(defn- collect-all-traces!
  "Run `f` while capturing every trace event; return ALL events with the
  given `op` as a vector."
  [op f]
  (mtest/with-trace-capture traces
    (f)
    (filterv #(= op (:operation %)) @traces)))

(defn- snapshot
  "The live snapshot for `machine-id` in the default frame's runtime-db."
  [machine-id]
  (get-in (rf/runtime-db-value :rf/default)
          [:rf.runtime/machines :snapshots machine-id]))

;; ---- (1) adversarial: a bad escape-hatch :data patch is rejected ----------

(deftest update-snapshot-bad-data-patch-rejected
  (testing "an :rf.machine/update-snapshot :data patch that violates the
            [:schemas :data] schema is rejected — the invalid :data never installs and
            a :phase :update-snapshot trace fires"
    (let [DataSchema [:map [:n pos-int?]]
          spec       {:initial :idle
                      :data    {:n 1}
                      :schemas {:data DataSchema}
                      :actions {:break
                                    (fn [_]
                                      ;; escape-hatch patch writes :n 0 — violates pos-int?
                                      {:fx [[:rf.machine/update-snapshot
                                             {:rf/machine-id :rf.upd-schema/singleton
                                              :rf/patch      {:data {:n 0}}}]]})}
                      :states      {:idle {:on {:go {:target :idle :action :break}}}}}]
      (rf/reg-machine :rf.upd-schema/singleton spec)
      ;; Bootstrap cleanly to {:n 1}.
      (rf/dispatch-sync [:rf.upd-schema/singleton [:noop]])
      (let [data-before (:data (snapshot :rf.upd-schema/singleton))
            traces      (collect-traces!
                          #(rf/dispatch-sync [:rf.upd-schema/singleton [:go]]))
            trace-ev    (first traces)
            tag         (:tags trace-ev)]
        (is (= {:n 1} data-before) "precondition: :data is the valid bootstrap value")
        (is (= 1 (count traces))
            "exactly one :where :machine-data trace fired on the rejected patch")
        (is (= :machine-data (:where tag)) "trace pins the machine-data boundary")
        (is (= :rf.upd-schema/singleton (:machine-id tag)) ":machine-id names the actor")
        (is (= :rf.upd-schema/singleton (:failing-id tag)) ":failing-id alias present")
        (is (= :update-snapshot (:phase tag))
            ":phase :update-snapshot distinguishes the escape-hatch boundary")
        (is (= {:n 0} (:value tag)) ":value carries the offending merged :data")
        (is (false? (:rollback? tag))
            ":rollback? false — a pre-write rejection commits nothing")
        (is (= :no-recovery (:recovery trace-ev))
            "trace envelope declares :no-recovery (parity with the other phases)")
        ;; The whole point: the invalid :data did NOT install.
        (is (= {:n 1} (:data (snapshot :rf.upd-schema/singleton)))
            "the invalid :data patch was NOT written — the prior valid :data survives")))))

;; ---- (2) a valid escape-hatch :data patch still installs ------------------

(deftest update-snapshot-good-data-patch-installs
  (testing "a conforming :data patch merges onto the snapshot with no trace"
    (let [DataSchema [:map [:n pos-int?]]
          spec       {:initial :idle
                      :data    {:n 1}
                      :schemas {:data DataSchema}
                      :actions {:bump
                                (fn [_]
                                  {:fx [[:rf.machine/update-snapshot
                                         {:rf/machine-id :rf.upd-schema/good
                                          :rf/patch      {:data {:n 42}}}]]})}
                      :states  {:idle {:on {:go {:target :idle :action :bump}}}}}]
      (rf/reg-machine :rf.upd-schema/good spec)
      (rf/dispatch-sync [:rf.upd-schema/good [:noop]])
      (let [traces (collect-traces!
                     #(rf/dispatch-sync [:rf.upd-schema/good [:go]]))]
        (is (empty? traces) "no :where :machine-data trace for a conforming patch")
        (is (= {:n 42} (:data (snapshot :rf.upd-schema/good)))
            "the valid :data patch installed normally")))))

;; ---- (3) a no-schema machine patches freely -------------------------------

(deftest update-snapshot-no-schema-no-validation
  (testing "a machine without [:schemas :data] patches :data with no trace"
    (let [spec {:initial :idle
                :data    {:n "anything"}
                :actions {:set
                          (fn [_]
                            {:fx [[:rf.machine/update-snapshot
                                   {:rf/machine-id :rf.upd-schema/no-schema
                                    :rf/patch      {:data {:n -999}}}]]})}
                :states  {:idle {:on {:go {:target :idle :action :set}}}}}]
      (rf/reg-machine :rf.upd-schema/no-schema spec)
      (rf/dispatch-sync [:rf.upd-schema/no-schema [:noop]])
      (let [traces (collect-traces!
                     #(rf/dispatch-sync [:rf.upd-schema/no-schema [:go]]))]
        (is (empty? traces) "no boundary trace for a no-schema machine")
        (is (= {:n -999} (:data (snapshot :rf.upd-schema/no-schema)))
            "the no-schema machine's :data patches freely")))))

;; ---- (4) spawned actor: schema resolved off :rf/machine-type --------------

(deftest update-snapshot-spawned-actor-bad-data-rejected
  (testing "an escape-hatch :data patch on a SPAWNED actor (no per-instance
            handler) resolves its [:schemas :data] schema off the snapshot's
            :rf/machine-type and rejects a violating patch"
    (let [ChildSchema [:map [:n pos-int?]]
          ;; The child patches its OWN snapshot via the escape hatch on entry
          ;; to :running — writing an invalid :n 0.
          child-spec  {:initial :booting
                       :data    {:n 1}                ;; valid at spawn
                       :schemas {:data ChildSchema}
                       :actions {:self-break
                                     (fn [_]
                                       {:fx [[:rf.machine/update-snapshot
                                              {:rf/machine-id :rf.upd-schema/child
                                               :rf/patch      {:data {:n 0}}}]]})}
                       :states      {:booting {:on {:tick {:target :running
                                                           :action :self-break}}}
                                     :running {}}}
          parent-spec {:initial :start
                       :data    {}
                       :states  {:start    {:on {:go :spawning}}
                                 :spawning {:entry
                                            (fn [_]
                                              {:fx [[:rf.machine/spawn
                                                     {:fixed-actor-id :rf.upd-schema/child
                                                      :definition     child-spec}]]})}}}]
      (rf/reg-machine :rf.upd-schema/spawn-parent parent-spec)
      (rf/dispatch-sync [:rf.upd-schema/spawn-parent [:noop]])
      ;; Spawn the child (valid :data {:n 1} → installs). The spawn machinery
      ;; stamps a reserved :rf/self-id into the child's :data, so assert on the
      ;; user key :n rather than full-map equality.
      (rf/dispatch-sync [:rf.upd-schema/spawn-parent [:go]])
      (is (= 1 (:n (:data (snapshot :rf.upd-schema/child))))
          "precondition: the spawned child installed with valid :data")
      (is (some? (:rf/machine-type (snapshot :rf.upd-schema/child)))
          "precondition: the spawned actor carries :rf/machine-type (schema resolves off it)")
      ;; Drive the child into :running via its own escape-hatch self-patch.
      (let [traces (collect-traces!
                     #(rf/dispatch-sync [:rf.upd-schema/child [:tick]]))
            tag    (-> traces first :tags)]
        (is (= 1 (count traces))
            "the spawned actor's bad escape-hatch :data is rejected via :rf/machine-type schema")
        (is (= :machine-data (:where tag)))
        (is (= :update-snapshot (:phase tag)))
        (is (= :rf.upd-schema/child (:machine-id tag)))
        (is (= 1 (:n (:data (snapshot :rf.upd-schema/child))))
            "the spawned actor's invalid :data patch was NOT written (:n stayed valid)")))))

;; ---- (5) missing / destroyed actor is a no-op -----------------------------

(deftest update-snapshot-missing-actor-noop
  (testing "an escape-hatch patch addressed to an actor with no snapshot is a
            no-op — no write, no boundary trace (even with a registered schema)"
    (let [DataSchema [:map [:n pos-int?]]
          ;; A live driver machine emits a patch addressed at a DIFFERENT,
          ;; never-bootstrapped machine id that carries a schema.
          target-spec {:initial :idle
                       :data    {:n 1}
                       :schemas {:data DataSchema}
                       :states  {:idle {}}}
          driver-spec {:initial :idle
                       :data    {}
                       :actions {:patch-ghost
                                 (fn [_]
                                   {:fx [[:rf.machine/update-snapshot
                                          {:rf/machine-id :rf.upd-schema/ghost-target
                                           :rf/patch      {:data {:n 0}}}]]})}
                       :states  {:idle {:on {:go {:target :idle :action :patch-ghost}}}}}]
      ;; Register the target so it HAS a schema, but never bootstrap it —
      ;; so no snapshot exists for it in runtime-db.
      (rf/reg-machine :rf.upd-schema/ghost-target target-spec)
      (rf/reg-machine :rf.upd-schema/ghost-driver driver-spec)
      (rf/dispatch-sync [:rf.upd-schema/ghost-driver [:noop]])
      (is (nil? (snapshot :rf.upd-schema/ghost-target))
          "precondition: the target has no installed snapshot")
      (let [traces (collect-traces!
                     #(rf/dispatch-sync [:rf.upd-schema/ghost-driver [:go]]))]
        (is (empty? traces)
            "no boundary trace — a missing actor is never validated (nothing to merge into)")
        (is (nil? (snapshot :rf.upd-schema/ghost-target))
            "no snapshot was conjured for the missing actor")))))

;; ---- (6) regression: :db in the patch still hard-disallowed ---------------

(deftest update-snapshot-db-key-still-rejected
  (testing "a :db key in the escape-hatch patch still surfaces
            :rf.error/machine-action-wrote-db and is dropped (the
            validation rewrite must not regress the :db hard-disallow);
            a valid :data alongside it still installs"
    (let [DataSchema [:map [:n pos-int?]]
          spec       {:initial :idle
                      :data    {:n 1}
                      :schemas {:data DataSchema}
                      :actions {:patch
                                (fn [_]
                                  {:fx [[:rf.machine/update-snapshot
                                         {:rf/machine-id :rf.upd-schema/db-key
                                          :rf/patch      {:data {:n 7}
                                                          :db   {:nope true}}}]]})}
                      :states  {:idle {:on {:go {:target :idle :action :patch}}}}}]
      (rf/reg-machine :rf.upd-schema/db-key spec)
      (rf/dispatch-sync [:rf.upd-schema/db-key [:noop]])
      (let [db-errs (collect-all-traces! :rf.error/machine-action-wrote-db
                                         #(rf/dispatch-sync [:rf.upd-schema/db-key [:go]]))]
        (is (= 1 (count db-errs)) ":db in the patch surfaces the hard-disallow")
        (is (= {:n 7} (:data (snapshot :rf.upd-schema/db-key)))
            "the valid :data sibling still installed (the :db key was stripped)")
        (is (nil? (:db (snapshot :rf.upd-schema/db-key)))
            "no :db key grafted onto the snapshot")))))
