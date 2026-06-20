(ns re-frame.spawn-registry-test
  "Verifies the runtime-tracked declarative-`:spawn` spawn registry at
  `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` — the slot the
  framework writes on every declarative `:spawn` spawn so the matching
  destroy cascade can locate the spawned id WITHOUT reading the user's
  `:data :pending`.

  The four invariants under test:

   1. **Spawn writes the slot.** Entering a `:spawn`-bearing state
      writes `[:rf.runtime/machines :spawned <parent> <invoke-id>] = <spawned-id>` in
      the frame's app-db, alongside the spawned actor's snapshot at
      `[:rf.runtime/machines :snapshots <spawned-id>]`.

   2. **Destroy reads the slot, tears down, clears.** Exiting the
      `:spawn`-bearing state destroys the spawned actor and dissocs
      the registry slot. Per the lazy-allocation invariant (sibling to
      `[:rf.runtime/machines :system-ids]`), the empty parent map is
      pruned and the empty `[:rf.runtime/machines :spawned]` slot is
      dissoc'd entirely.

   3. **Auth-flow scenario without `:data :pending` bookkeeping.** A spec
      whose `:on-spawn` does NOT record the id in any `:data` slot
      still has the spawned actor cleanly destroyed on state exit —
      the runtime tracks the id internally rather than reading `:data`
      to find it.

   4. **Multi-child independent tracking.** A parent that has two
      different `:spawn`-bearing states (different invoke-ids) tracks
      and tears them down independently — each slot keys on the full
      prefix-path so two states named `:loading` in different parents
      do not collide either.

  The CLJS-side coverage of the same invariants lives in
  machines_cljs_test.cljs (machine-spawn-cljs and friends); these
  JVM-side tests run on the plain-atom substrate and assert against
  the in-app-db slot directly."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; runtime-db / snapshot lookup via the shared machines test-support
;; — no hardcoded `[:rf.runtime/machines …]` path.
(def ^:private snapshot mtest/snapshot)
(def ^:private frame-db mtest/runtime-db)

;; ---- (1) spawn writes [:rf.runtime/machines :spawned <parent> <invoke-id>] ------------------

(deftest spawn-writes-runtime-registry-slot
  (testing "entering a :spawn-bearing state binds [:rf.runtime/machines :spawned <parent> <invoke-id>] to the spawned-id"
    (let [;; The :on-spawn callback is purely advisory — its return value
          ;; is ignored. We capture the id via a side-effect atom to verify
          ;; the callback fires; the runtime tracks the id at
          ;; [:rf.runtime/machines :spawned ...] regardless.
          observed (atom nil)
          child  {:initial :running
                  :data    {}
                  :states  {:running {}}}
          parent {:initial :idle
                  :on-spawn-actions
                  ;; Observational body — side-effect into an atom, returns
                  ;; nil (the advisory contract drops the return; returning
                  ;; nil keeps the dev-warn quiet).
                  {:record (fn [{:keys [id]}] (reset! observed id) nil)}
                  :states
                  {:idle      {:on {:start :working}}
                   :working   {:spawn {:machine-id :worker/proc
                                        :on-spawn   :record}
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
            "the spawned actor's snapshot lives at [:rf.runtime/machines :snapshots <spawned-id>]")
        (is (= :worker/proc#1 @observed)
            ":on-spawn callback still fires (advisory) and observed the id via side-effect")))))

;; ---- (2) destroy clears the slot AND prunes lazy-allocation roots ---------

(deftest destroy-clears-runtime-registry-slot
  (testing "exiting the :spawn-bearing state destroys the actor AND clears the registry slot"
    (let [child  {:initial :running
                  :data    {}
                  :states  {:running {}}}
          parent {:initial :idle
                  :on-spawn-actions
                  {:record (fn [{:keys [id]}] (tap> [::recorded id]))}
                  :states
                  {:idle      {:on {:start :working}}
                   :working   {:spawn {:machine-id :worker/proc
                                        :on-spawn   :record}
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
        ;; slot is dissoc'd entirely (sibling to :system-ids).
        (is (not (contains? (get-in db [:rf.runtime/machines]) :spawned))
            "the empty :spawned slot under [:rf.runtime/machines] is pruned to absent")))))

;; ---- (3) auth-flow scenario WITHOUT user-side :on-spawn bookkeeping -------
;;
;; A :spawn whose user-supplied :on-spawn doesn't write the id under any
;; :data slot still has the spawned actor cleanly destroyed on state-exit:
;; the runtime tracks the id internally, so the destroy cascade works
;; correctly without any `:data :pending` to read.

(deftest auth-flow-without-data-pending-magic
  (testing "a :spawn without any :on-spawn :data write still has the actor destroyed on state-exit"
    (let [child  {:initial :running :data {} :states {:running {}}}
          ;; Note: NO :on-spawn-actions and NO :on-spawn key. The user
          ;; doesn't care about the id user-side; the runtime tracks it.
          parent {:initial :idle
                  :states
                  {:idle           {:on {:submit :authenticating}}
                   :authenticating {:spawn {:machine-id :http/post}
                                    :on    {:auth/succeeded :authenticated
                                            :auth/failed    :idle}}
                   :authenticated  {}}}]
      (rf/reg-machine :http/post child)
      (rf/reg-machine :auth/main parent)
      (rf/dispatch-sync [:auth/main [:submit]])
      ;; Spawn happened — actor live, registry slot set, parent's :data
      ;; untouched (no :on-spawn callback to write to it).
      (let [db (frame-db)
            spawned-id (get-in db [:rf.runtime/machines :spawned :auth/main [:authenticating]])]
        (is (= :http/post#1 spawned-id))
        (is (some? (get-in db [:rf.runtime/machines :snapshots spawned-id])))
        ;; The runtime binds the spawned id into the parent's `:data` under
        ;; `[:rf/spawned <invoke-id>]` (XState-context parity) — so the
        ;; user's domain `:data` keys are untouched, but the reserved
        ;; `:rf/spawned` capture is present.
        (is (= {:rf/spawned {[:authenticating] :http/post#1}}
               (get-in (snapshot :auth/main) [:data]))
            "user domain :data untouched; runtime stamps only the reserved :rf/spawned id capture")
        (is (empty? (dissoc (get-in (snapshot :auth/main) [:data]) :rf/spawned))
            "no user-domain :data key was written — :on-spawn still not required"))
      ;; Mid-flight abandon → :idle.
      (rf/dispatch-sync [:auth/main [:auth/failed]])
      (let [db (frame-db)]
        (is (nil? (get-in db [:rf.runtime/machines :snapshots :http/post#1]))
            "the spawned actor was destroyed despite no :on-spawn having recorded the id")
        (is (not (contains? (get-in db [:rf.runtime/machines]) :spawned))
            "the registry slot is cleared")))))

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

;; ---- spawned-id bound into the parent's own :data -----------
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
        ;; SYMMETRY: the parent's :data slot equals the runtime registry slot
        ;; (the reverse-index that already existed); they key on the SAME
        ;; <invoke-id>. The :data read is the in-snapshot, no-coupling view.
        (is (= spawned-id
               (get-in (frame-db) [:rf.runtime/machines :spawned :sup/captures [:working]]))
            "parent's :data :rf/spawned mirrors the runtime registry slot exactly")
        ;; SYMMETRY: the child's own :rf/invoke-id lineage stamp is the SAME
        ;; <invoke-id> the parent keys its :data map under — the two halves
        ;; of the lineage point at each other.
        (is (= [:working] (get-in (snapshot spawned-id) [:data :rf/invoke-id]))
            "child's :rf/invoke-id == the parent's :rf/spawned key (the reverse direction)")))))

(deftest action-reads-parent-data-id-and-destroys-no-atom
  (testing "an action reads the id from its own :data and emits [:rf.machine/destroy <id>] — full round-trip, NO external atom"
    (let [child  {:initial :running :data {} :states {:running {}}}
          ;; The exit action reads the spawned id from the parent's OWN :data
          ;; (the unified context-map's :data key) — no atom, no runtime-db
          ;; path coupling — and emits the imperative keyword-form destroy.
          parent {:initial :idle
                  :actions
                  {:tear-down (fn [{data :data}]
                                (let [id (get-in data [:rf/spawned [:working]])]
                                  {:fx [[:rf.machine/destroy id]]}))}
                  :states
                  {:idle    {:on {:start :working}}
                   :working {:spawn {:machine-id :worker/proc}
                             ;; The :tear-down action fires on the :done
                             ;; transition and emits the imperative destroy
                             ;; using the id it read from the parent's own
                             ;; :data — exercising the full clean round-trip.
                             :on    {:done {:target :idle :action :tear-down}}}}}]
      (rf/reg-machine :worker/proc child)
      (rf/reg-machine :sup/clean parent)
      (rf/dispatch-sync [:sup/clean [:start]])
      (let [spawned-id (get-in (snapshot :sup/clean) [:data :rf/spawned [:working]])]
        (is (= :worker/proc#1 spawned-id)
            "(precondition) id captured into parent :data with no atom")
        (is (some? (get-in (frame-db) [:rf.runtime/machines :snapshots spawned-id]))
            "(precondition) the actor is alive"))
      ;; The :done transition runs :tear-down, which read the id from :data
      ;; and emitted the imperative destroy.
      (rf/dispatch-sync [:sup/clean [:done]])
      (is (nil? (get-in (frame-db) [:rf.runtime/machines :snapshots :worker/proc#1]))
          "the actor was torn down via an id read from the parent's own :data — no external atom involved"))))

(deftest multi-spawn-parent-data-no-clobber
  (testing "multiple :spawn-bearing states + a :spawn-all each record under their own invoke-id key — no lossy clobber"
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
                                 :on-child-done  :gc/done
                                 :on-child-error :gc/failed
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
      ;; Tear A, spawn B — distinct invoke-id, A's slot does not collide.
      (rf/dispatch-sync [:sup/many [:back]])
      (rf/dispatch-sync [:sup/many [:fork-b]])
      (let [d (:data (snapshot :sup/many))]
        (is (= :child/b#1 (get-in d [:rf/spawned [:b-running]]))
            "B's id recorded under its own distinct invoke-id key — no clobber")
        ;; A's earlier binding is still present in the :data map (the parent
        ;; never clears its own :data captures — they accumulate per invoke-id,
        ;; which is exactly why a single 'last-spawned' slot would be lossy).
        (is (= :child/a#1 (get-in d [:rf/spawned [:a-running]]))
            "A's earlier :data binding survived the B spawn — keyed-map shape is non-lossy"))
      ;; :spawn-all records the WHOLE {<child-id> <spawned-id>} map under the
      ;; one shared invoke-id — both children, no clobber under the single key.
      (rf/dispatch-sync [:sup/many [:back]])
      (rf/dispatch-sync [:sup/many [:fork-all]])
      (let [d (:data (snapshot :sup/many))]
        (is (= {:x :gc/x#1 :y :gc/y#1} (get-in d [:rf/spawned [:forking]]))
            ":spawn-all records the full children id-map under the shared invoke-id — both children, no clobber")))))

(deftest runtime-db-reverse-index-still-works
  (testing "the runtime-db reverse-index read still resolves the id (no regression alongside the new :data read)"
    (let [child  {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :states  {:idle    {:on {:start :working}}
                            :working {:spawn {:machine-id :worker/proc}
                                      :on    {:done :idle}}}}]
      (rf/reg-machine :worker/proc child)
      (rf/reg-machine :sup/reverse parent)
      (rf/dispatch-sync [:sup/reverse [:start]])
      (let [db          (frame-db)
            via-registry (get-in db [:rf.runtime/machines :spawned :sup/reverse [:working]])
            via-data     (get-in (snapshot :sup/reverse) [:data :rf/spawned [:working]])]
        (is (= :worker/proc#1 via-registry)
            "the runtime-db reverse-index slot still resolves the spawned id (unchanged)")
        (is (= via-registry via-data)
            "the new :data read and the pre-existing reverse-index agree on the id")
        (is (some? (get-in db [:rf.runtime/machines :snapshots via-registry]))
            "the actor's snapshot is reachable from the reverse-index id, as before")))))

;; ---- (5) keyword-form [:rf.machine/destroy actor-id] imperative destroy --
;;
;; The IMPERATIVE form (an action emits `[:rf.machine/destroy actor-id]`
;; with the actor id it holds) is first-class current API — re-frame2's
;; spelling of XState v5 `stopChild(actorId)`. The declarative-:spawn
;; desugar uses the runtime-resolved map form; this keyword form is the
;; canonical imperative entry-point, not a compatibility path. This pins
;; that the imperative form drives a full teardown.

(deftest keyword-form-imperative-destroy-machine
  (testing "[:rf.machine/destroy actor-id] (keyword arg) tears the actor down"
    (let [child  {:initial :running :data {} :states {:running {}}}
          ;; This test pins the keyword-form imperative destroy MECHANISM
          ;; in isolation, so it feeds the id via a test-local atom. NOTE:
          ;; the atom is NOT the idiomatic way to obtain the id — the
          ;; first-class path is to read it from the parent's own `:data`
          ;; under `[:rf/spawned <invoke-id>]` (see
          ;; `action-reads-parent-data-id-and-destroys-no-atom` above for
          ;; the no-atom round-trip). The atom here only isolates the
          ;; `[:rf.machine/destroy <id>]` keyword-arg shape under test.
          recorded (atom nil)
          parent {:initial :idle
                  :actions
                  ;; User-written exit action emits the keyword form
                  ;; directly — the canonical imperative destroy shape.
                  {:tear-down (fn [_ctx]
                                {:fx [[:rf.machine/destroy @recorded]]})}
                  :on-spawn-actions
                  ;; Sidechannel-atom capture — returns nil (advisory; the
                  ;; runtime drops the return).
                  {:record (fn [{:keys [id]}] (reset! recorded id) nil)}
                  :states
                  {:idle    {:on {:start :working}}
                   :working {:spawn {:machine-id :worker/proc
                                      :on-spawn   :record}
                             :on    {:done {:target :idle :action :tear-down}}}}}]
      (rf/reg-machine :worker/proc child)
      (rf/reg-machine :sup/imperative parent)
      (rf/dispatch-sync [:sup/imperative [:start]])
      (let [spawned-id (get-in (frame-db) [:rf.runtime/machines :spawned :sup/imperative [:working]])]
        (is (= :worker/proc#1 spawned-id)))
      (rf/dispatch-sync [:sup/imperative [:done]])
      (let [db (frame-db)]
        ;; Both the user's keyword-form destroy AND the runtime's
        ;; tracked-form destroy fired in this transition. The runtime's
        ;; destroy is idempotent (the spawn registry slot resolves to
        ;; either the same actor-id or nil after the user's destroy).
        (is (nil? (get-in db [:rf.runtime/machines :snapshots :worker/proc#1]))
            "the spawned actor is gone")))))

;; ---- (5) spawned actor's snapshot carries :rf/spawn-counter + :meta -------
;;
;; The unified build-initial-snapshot helper seeds :rf/spawn-counter {} and
;; propagates :meta on every snapshot it builds — including spawned actors.
;; A spawned child's grandchild id therefore allocates from the child's own
;; :rf/spawn-counter, and the child's spec-declared :meta flows through to
;; its snapshot.

(deftest spawned-actor-snapshot-carries-spawn-counter
  (testing "a spawned actor's initial snapshot has :rf/spawn-counter {}"
    (let [child  {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :states  {:idle    {:on {:start :working}}
                            :working {:spawn {:machine-id :worker/sc}}}}]
      (rf/reg-machine :worker/sc child)
      (rf/reg-machine :sup/sc parent)
      (rf/dispatch-sync [:sup/sc [:start]])
      (let [spawned-id (get-in (frame-db) [:rf.runtime/machines :spawned :sup/sc [:working]])
            snap       (get-in (frame-db) [:rf.runtime/machines :snapshots spawned-id])]
        (is (= {} (:rf/spawn-counter snap))
            "spawned actor's initial snapshot seeds :rf/spawn-counter to {}")))))

(deftest spawned-actor-snapshot-propagates-meta
  (testing ":meta declared on the child spec flows through to the spawned snapshot"
    (let [child  {:initial :running
                  :data    {}
                  :meta    {:foo :bar :version 7}
                  :states  {:running {}}}
          parent {:initial :idle
                  :states  {:idle    {:on {:start :working}}
                            :working {:spawn {:machine-id :worker/meta}}}}]
      (rf/reg-machine :worker/meta child)
      (rf/reg-machine :sup/meta parent)
      (rf/dispatch-sync [:sup/meta [:start]])
      (let [spawned-id (get-in (frame-db) [:rf.runtime/machines :spawned :sup/meta [:working]])
            snap       (get-in (frame-db) [:rf.runtime/machines :snapshots spawned-id])]
        (is (= {:foo :bar :version 7} (:meta snap))
            "spec-declared :meta is propagated to the spawned actor's snapshot")))))

;; ---- dev-warn when :on-spawn returns a dropped value ---------
;;
;; `:on-spawn` is advisory: its return is DROPPED. A callback that returns a
;; non-nil value (the canonical-looking `(assoc data :pending id)` trap) has
;; that value silently swallowed. Per the no-silent-swallow principle the
;; runtime turns the silent drop into a loud one — a dev-only
;; `:rf.warning/on-spawn-return-ignored` trace naming the working
;; id-recording alternatives. `trace/emit!` is gated on
;; `interop/debug-enabled?` (true on the JVM by default) so the warn fires
;; here; production CLJS bundles DCE it entirely.

(defn- capture-warn-ops!
  "Run `thunk` while a trace listener records every emitted `:operation`.
  Returns the vector of operations seen during the body. Routed through the
  shared `mtest/with-trace-capture` — guaranteed unregister in a `finally`
  — then projects each envelope to its `:operation`."
  [thunk]
  (mtest/with-trace-capture seen
    (thunk)
    (mapv :operation @seen)))

(deftest on-spawn-returning-a-value-warns-exactly-once
  (testing "an :on-spawn callback that returns a non-nil (dropped) value
   emits exactly one :rf.warning/on-spawn-return-ignored"
    (let [child  {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :on-spawn-actions
                  ;; The canonical TRAP body — returns a :data map that the
                  ;; runtime drops. The warn is what flags it.
                  {:record (fn [{data :data id :id}] (assoc data :pending id))}
                  :states
                  {:idle    {:on {:start :working}}
                   :working {:spawn {:machine-id :worker/warn
                                      :on-spawn   :record}}}}]
      (rf/reg-machine :worker/warn child)
      (rf/reg-machine :sup/warn parent)
      (let [ops (capture-warn-ops!
                  #(rf/dispatch-sync [:sup/warn [:start]]))]
        (is (= 1 (count (filter #{:rf.warning/on-spawn-return-ignored} ops)))
            "exactly one on-spawn-return-ignored warn for a non-nil return")))))

(deftest on-spawn-returning-nil-is-silent
  (testing "an :on-spawn callback that returns nil (observation-only) emits
   NO :rf.warning/on-spawn-return-ignored — no false positive"
    (let [child  {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :on-spawn-actions
                  ;; Honest observational body — side-effect, returns nil.
                  {:observe (fn [{:keys [id]}] (tap> [::spawned id]) nil)}
                  :states
                  {:idle    {:on {:start :working}}
                   :working {:spawn {:machine-id :worker/quiet
                                      :on-spawn   :observe}}}}]
      (rf/reg-machine :worker/quiet child)
      (rf/reg-machine :sup/quiet parent)
      (let [ops (capture-warn-ops!
                  #(rf/dispatch-sync [:sup/quiet [:start]]))]
        (is (zero? (count (filter #{:rf.warning/on-spawn-return-ignored} ops)))
            "no warn when :on-spawn returns nil")))))

(deftest no-on-spawn-callback-is-silent
  (testing "a :spawn with NO :on-spawn at all emits no on-spawn-return-ignored warn"
    (let [child  {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :states  {:idle    {:on {:start :working}}
                            :working {:spawn {:machine-id :worker/none}}}}]
      (rf/reg-machine :worker/none child)
      (rf/reg-machine :sup/none parent)
      (let [ops (capture-warn-ops!
                  #(rf/dispatch-sync [:sup/none [:start]]))]
        (is (zero? (count (filter #{:rf.warning/on-spawn-return-ignored} ops)))
            "no callback ⇒ no warn")))))

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
