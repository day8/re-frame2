(ns re-frame.spawn-registry-test
  "Verifies the runtime-tracked declarative-`:spawn` spawn registry at
  `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` — the slot the
  framework writes on every declarative `:spawn` spawn so the matching
  destroy cascade can locate the spawned id WITHOUT reading the user's
  `:data :pending`.

  The four invariants under test:

   1. **Spawn writes the slot.** Entering a `:spawn`-bearing state
      writes `[:rf.runtime/machines :spawned <parent> <invoke-id>] = <spawned-id>` in
      the frame's runtime-db, alongside the spawned actor's snapshot at
      `[:rf.runtime/machines :snapshots <spawned-id>]`.

   2. **Destroy reads the slot, tears down, clears.** Exiting the
      `:spawn`-bearing state destroys the spawned actor and dissocs
      the registry slot. Per the lazy-allocation invariant, the empty
      parent map is
      pruned and the empty `[:rf.runtime/machines :spawned]` slot is
      dissoc'd entirely.

   3. **No user-side bookkeeping.** The runtime writes only the reserved
      `:rf/spawned` capture into the parent's `:data` (keyed by invoke-id,
      mirroring the registry slot) and never reads user `:data` to find the
      id it destroys.

   4. **Multi-child independent tracking.** A parent that has two
      different `:spawn`-bearing states (different invoke-ids) tracks
      and tears them down independently — each slot keys on the full
      prefix-path so two states named `:loading` in different parents
      do not collide either.

  The CLJS-side coverage of the same invariants lives in
  machines_spawn_cljs_test.cljs (machine-spawn-cljs and friends); these
  JVM-side tests run on the plain-atom substrate and assert against
  the runtime-db slot directly."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; runtime-db / snapshot lookup via the shared machines test-support
;; — no hardcoded `[:rf.runtime/machines …]` path.
(def ^:private snapshot rf.machines.test-support/snapshot)
(def ^:private frame-db rf.machines.test-support/runtime-db)

;; ---- (1) spawn writes [:rf.runtime/machines :spawned <parent> <invoke-id>] ------------------

(deftest spawn-writes-runtime-registry-slot
  (testing "entering a :spawn-bearing state binds [:rf.runtime/machines :spawned <parent> <invoke-id>] to the spawned-id"
    (let [child  {:initial :running
                  :data    {}
                  :states  {:running {}}}
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :working}}
                   :working   {:spawn {:machine-id :worker/proc}
                               :on    {:done :idle}}}}]
      (rf/reg-machine :worker/proc child)
      (rf/reg-machine :sup/flow parent)
      (rf/dispatch-sync [:sup/flow [:start]])
      ;; The runtime allocated :worker/proc#1 for the spawn.
      (let [db          (frame-db)
            spawned-id  (get-in db [:rf.runtime/machines :spawned :sup/flow [:working]])]
        (is (= :worker/proc#1 spawned-id)
            "the spawn registry slot is bound to the deterministic actor id")
        (is (some? (get-in db [:rf.runtime/machines :snapshots spawned-id]))
            "the spawned actor's snapshot lives at [:rf.runtime/machines :snapshots <spawned-id>]")))))

;; ---- (2) destroy clears the slot AND prunes lazy-allocation roots ---------

(deftest destroy-clears-runtime-registry-slot
  (testing "exiting the :spawn-bearing state destroys the actor AND clears the registry slot"
    (let [child  {:initial :running
                  :data    {}
                  :states  {:running {}}}
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :working}}
                   :working   {:spawn {:machine-id :worker/proc}
                               :on    {:done :idle}}}}]
      (rf/reg-machine :worker/proc child)
      (rf/reg-machine :sup/flow parent)
      (rf/dispatch-sync [:sup/flow [:start]])
      (let [db (frame-db)]
        (is (= :worker/proc#1 (get-in db [:rf.runtime/machines :spawned :sup/flow [:working]]))
            "(precondition) the slot was bound on entry"))
      ;; Now leave :working.
      (rf/dispatch-sync [:sup/flow [:done]])
      (let [db (frame-db)]
        (is (nil? (get-in db [:rf.runtime/machines :snapshots :worker/proc#1]))
            "the spawned actor's snapshot was cleared on destroy")
        (is (nil? (get-in db [:rf.runtime/machines :spawned :sup/flow [:working]]))
            "the registry slot was cleared on destroy")
        ;; Lazy-allocation invariant: the now-empty parent submap is
        ;; pruned, and the now-empty [:rf.runtime/machines :spawned]
        ;; slot is dissoc'd entirely.
        (is (not (contains? (get-in db [:rf.runtime/machines]) :spawned))
            "the empty :spawned slot under [:rf.runtime/machines] is pruned to absent")))))

;; ---- (2b) ADVERSARIAL: the parent's own :rf/spawned DATA slot is cleared ---
;; on teardown too, so the in-snapshot data slot (mechanism 1 — XState-context
;; parity) mirrors the runtime registry EXACTLY (Spec 005:2938). That closes a
;; stale-id footgun: were the data slot to outlive the actor, an action reading
;; `[:data :rf/spawned <invoke-id>]` AFTER the child completed would get a DEAD
;; id. The exit-cascade route is pinned by `spawn_reentry_mirror_cljs_test`
;; (`plain-exit-still-clears-the-mirror`); this pins the finalize route.

(deftest finalize-auto-destroy-clears-parent-data-rf-spawned-slot
  (testing "a child self-completing to :final? auto-destroys AND clears the parent's :data :rf/spawned slot"
    (let [;; The child drives straight to a :final? state on its :start, so the
          ;; runtime auto-destroys it via the finalize path (NOT the parent's
          ;; exit cascade) — the OTHER teardown route through teardown-actor.
          child  {:initial :running
                  :data    {}
                  :states  {:running {:on {:go :done}}
                            :done    {:final? true}}}
          parent {:initial :idle
                  :states  {:idle    {:on {:start :working}}
                            :working {:spawn {:machine-id :fin/kid
                                              :start      [:go]}
                                      :on    {:next :working}}}}]
      (rf/reg-machine :fin/kid child)
      (rf/reg-machine :sup/finalize parent)
      ;; The child self-completes DURING this dispatch (its :start [:go] reaches
      ;; :done/:final? synchronously), so by the time it returns the child is
      ;; already auto-destroyed. We assert the parent's :data slot is clean.
      (rf/dispatch-sync [:sup/finalize [:start]])
      (let [db (frame-db)]
        (is (nil? (get-in db [:rf.runtime/machines :snapshots :fin/kid#1]))
            "(precondition) the child auto-destroyed on reaching its :final? state")
        ;; ADVERSARIAL: the finalize auto-destroy path ALSO clears the parent's
        ;; own :data :rf/spawned capture — no dead id survives the finalize.
        (is (nil? (get-in (snapshot :sup/finalize) [:data :rf/spawned [:working]]))
            "parent's :data :rf/spawned slot cleared on finalize auto-destroy — NO dead id")
        (is (= (get-in db [:rf.runtime/machines :spawned :sup/finalize [:working]])
               (get-in (snapshot :sup/finalize) [:data :rf/spawned [:working]]))
            "registry slot and :data slot mirror exactly after finalize — both absent")))))

;; ---- (4) multi-child — two :spawn-bearing states tracked independently ---

(deftest multi-child-independent-tracking
  (testing "a parent with two different :spawn-bearing states tracks each independently"
    (let [child-a {:initial :running :data {} :states {:running {}}}
          child-b {:initial :running :data {} :states {:running {}}}
          parent  {:initial :idle
                   :states
                   {:idle  {:on {:fork-a :a-running
                                 :fork-b :b-running}}
                    :a-running {:spawn {:machine-id :child/a}
                                :on    {:back :idle}}
                    :b-running {:spawn {:machine-id :child/b}
                                :on    {:back :idle}}}}]
      (rf/reg-machine :child/a child-a)
      (rf/reg-machine :child/b child-b)
      (rf/reg-machine :sup/multi parent)
      ;; Spawn child A.
      (rf/dispatch-sync [:sup/multi [:fork-a]])
      (let [db (frame-db)]
        (is (= :child/a#1 (get-in db [:rf.runtime/machines :spawned :sup/multi [:a-running]])))
        (is (nil?           (get-in db [:rf.runtime/machines :spawned :sup/multi [:b-running]]))))
      ;; Tear A down, spawn B.
      (rf/dispatch-sync [:sup/multi [:back]])
      (rf/dispatch-sync [:sup/multi [:fork-b]])
      (let [db (frame-db)]
        (is (= :child/b#1 (get-in db [:rf.runtime/machines :spawned :sup/multi [:b-running]])))
        (is (nil?           (get-in db [:rf.runtime/machines :spawned :sup/multi [:a-running]]))
            "A's slot was cleared when A was destroyed")
        ;; A is gone.
        (is (nil? (get-in db [:rf.runtime/machines :snapshots :child/a#1])))
        ;; B is alive.
        (is (some? (get-in db [:rf.runtime/machines :snapshots :child/b#1]))))
      ;; Tear B down too — both slots cleared, root pruned.
      (rf/dispatch-sync [:sup/multi [:back]])
      (let [db (frame-db)]
        (is (not (contains? (get-in db [:rf.runtime/machines]) :spawned))
            "with both invokes torn down, the lazy-allocation slot is dissoc'd")))))

;; ---- (3) spawned-id bound into the parent's own :data -------
;;
;; The declarative `:spawn` binds the assigned actor id into the SPAWNING
;; (parent) machine's own `:data` under the reserved per-invoke map
;; `:rf/spawned` — `{:rf/spawned {<invoke-id> <spawned-id>}}`. This is the
;; re-frame2 spelling of XState v5's `spawn(...)`-into-`context` capture: an
;; action reads its OWN `:data` to obtain the id of an actor it spawned and
;; emits `[:rf.machine/destroy <id>]` with NO external-atom side-channel and
;; no runtime-db reverse-index coupling. It is the REVERSE direction of the
;; child-lineage stamps (`:rf/self-id` / `:rf/parent-id` / `:rf/invoke-id`)
;; the spawn-fx writes onto the spawned CHILD's own `:data` — here the PARENT
;; captures the CHILD's id, keyed by the SAME `<invoke-id>` the child records
;; under `:rf/invoke-id` and the runtime tracks at
;; `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]`.

(deftest spawned-id-bound-into-parent-data
  (testing "a declarative :spawn binds the assigned id into the parent's :data under [:rf/spawned <invoke-id>]"
    (let [child  {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :states  {:idle    {:on {:start :working}}
                            :working {:spawn {:machine-id :worker/proc}
                                      :on    {:done :idle}}}}]
      (rf/reg-machine :worker/proc child)
      (rf/reg-machine :sup/captures parent)
      (rf/dispatch-sync [:sup/captures [:start]])
      (let [parent-data (:data (snapshot :sup/captures))
            spawned-id  (get-in parent-data [:rf/spawned [:working]])]
        (is (= :worker/proc#1 spawned-id)
            "parent's :data carries the spawned id under [:rf/spawned <invoke-id>] — XState-context parity")
        (is (= {:rf/spawned {[:working] :worker/proc#1}} parent-data)
            "the runtime writes only the reserved :rf/spawned capture — no user-domain :data key")
        ;; SYMMETRY: the parent's :data slot equals the runtime registry slot
        ;; (the runtime-db reverse index); they key on the SAME
        ;; <invoke-id>. The :data read is the in-snapshot, no-coupling view.
        (is (= spawned-id
               (get-in (frame-db) [:rf.runtime/machines :spawned :sup/captures [:working]]))
            "parent's :data :rf/spawned mirrors the runtime registry slot exactly")
        ;; SYMMETRY: the child's own :rf/invoke-id lineage stamp is the SAME
        ;; <invoke-id> the parent keys its :data map under — the two halves
        ;; of the lineage point at each other.
        (is (= [:working] (get-in (snapshot spawned-id) [:data :rf/invoke-id]))
            "child's :rf/invoke-id == the parent's :rf/spawned key (the reverse direction)")))))

(deftest multi-spawn-parent-data-per-invoke-id-key
  (testing "multiple :spawn-bearing states + a :spawn-all each record under their own invoke-id key — keyed-map shape, cleared on exit"
    (let [child-a {:initial :running :data {} :states {:running {}}}
          child-b {:initial :running :data {} :states {:running {}}}
          gc-x    {:initial :running :data {} :states {:running {}}}
          gc-y    {:initial :running :data {} :states {:running {}}}
          ;; Parent has two distinct :spawn-bearing states (a-running /
          ;; b-running, different invoke-ids) plus a :spawn-all state
          ;; (forking, one shared invoke-id with two children).
          parent  {:initial :idle
                   :states
                   {:idle      {:on {:fork-a  :a-running
                                     :fork-b  :b-running
                                     :fork-all :forking}}
                    :a-running {:spawn {:machine-id :child/a} :on {:back :idle}}
                    :b-running {:spawn {:machine-id :child/b} :on {:back :idle}}
                    :forking   {:spawn-all
                                {:children       [{:id :x :machine-id :gc/x}
                                                  {:id :y :machine-id :gc/y}]
                                 :join           :all
                                 :on-all-complete [:all/done]}
                                :on {:back :idle :all/done :idle}}}}]
      (rf/reg-machine :child/a child-a)
      (rf/reg-machine :child/b child-b)
      (rf/reg-machine :gc/x gc-x)
      (rf/reg-machine :gc/y gc-y)
      (rf/reg-machine :sup/many parent)
      ;; Spawn A.
      (rf/dispatch-sync [:sup/many [:fork-a]])
      (let [d (:data (snapshot :sup/many))]
        (is (= :child/a#1 (get-in d [:rf/spawned [:a-running]]))
            "A's id recorded under its own invoke-id key"))
      ;; Tear A (leaving :a-running exits the :spawn-bearing state), spawn B.
      (rf/dispatch-sync [:sup/many [:back]])
      (rf/dispatch-sync [:sup/many [:fork-b]])
      (let [d (:data (snapshot :sup/many))]
        (is (= :child/b#1 (get-in d [:rf/spawned [:b-running]]))
            "B's id recorded under its own distinct invoke-id key — no collision with A's key")
        ;; A's earlier binding was CLEARED when A was torn down on :a-running
        ;; exit — the :data slot mirrors the runtime registry exactly
        ;; so no dead id lingers. The keyed-map shape
        ;; is what keeps B's live capture and A's absence INDEPENDENT (a single
        ;; 'last-spawned' slot would have been overwritten, not cleared).
        (is (nil? (get-in d [:rf/spawned [:a-running]]))
            "A's :data binding cleared on A's teardown — mirrors the registry, no dead id")
        (is (= :child/b#1 (get-in (frame-db) [:rf.runtime/machines :spawned :sup/many [:b-running]]))
            "B's registry slot is bound; A's registry slot is gone — data mirrors registry")
        (is (nil? (get-in (frame-db) [:rf.runtime/machines :spawned :sup/many [:a-running]]))
            "A's registry slot cleared too — both views agree A is gone"))
      ;; :spawn-all records the WHOLE {<child-id> <spawned-id>} map under the
      ;; one shared invoke-id — both children, no clobber under the single key.
      ;; Leaving :b-running first tears B down (clearing B's :data slot).
      (rf/dispatch-sync [:sup/many [:back]])
      (rf/dispatch-sync [:sup/many [:fork-all]])
      (let [d (:data (snapshot :sup/many))]
        (is (nil? (get-in d [:rf/spawned [:b-running]]))
            "B's :data binding cleared on B's teardown — clear-on-exit holds for :spawn-all's siblings too")
        (is (= {:x :gc/x#1 :y :gc/y#1} (get-in d [:rf/spawned [:forking]]))
            ":spawn-all records the full children id-map under the shared invoke-id — both children, no clobber")))))

;; ---- (5) a grandchild allocates from the child's own :rf/spawn-counter -----
;;
;; The unified build-initial-snapshot helper seeds :rf/spawn-counter {} on
;; every snapshot it builds, spawned actors included (pinned with :meta in
;; `initial_snapshot_unification_test`). A spawned child's grandchild id
;; therefore allocates from the child's own counter.

(deftest grandchild-spawn-allocates-from-childs-snapshot-counter
  (testing "a grandchild's id allocates from the child's :rf/spawn-counter, not the defensive fnil-inc backstop"
    (let [grandchild {:initial :running :data {} :states {:running {}}}
          child      {:initial :booting
                      :states  {:booting {:spawn {:machine-id :grand/proc}}}}
          parent     {:initial :idle
                      :states  {:idle    {:on {:start :working}}
                                :working {:spawn {:machine-id :child/wraps}}}}]
      (rf/reg-machine :grand/proc grandchild)
      (rf/reg-machine :child/wraps child)
      (rf/reg-machine :sup/cascade parent)
      (rf/dispatch-sync [:sup/cascade [:start]])
      (let [child-id      (get-in (frame-db) [:rf.runtime/machines :spawned :sup/cascade [:working]])
            grandchild-id (get-in (frame-db) [:rf.runtime/machines :spawned child-id [:booting]])
            child-snap    (get-in (frame-db) [:rf.runtime/machines :snapshots child-id])]
        (is (= :child/wraps#1 child-id)
            "child's id from parent's allocator")
        (is (= :grand/proc#1 grandchild-id)
            "grandchild's id allocated as <machine-id>#1 — deterministic form, NOT the fnil-inc backstop")
        (is (= {:grand/proc 1} (:rf/spawn-counter child-snap))
            "child's :rf/spawn-counter bumped on grandchild spawn")))))
