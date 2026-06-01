(ns re-frame.spawn-registry-test
  "Per rf2-t07u (Option A revised). Verifies the runtime-tracked
  declarative-`:spawn` spawn registry at `[:rf/runtime :machines :spawned <parent-id>
  <invoke-id>]` — the slot the framework writes on every declarative
  `:spawn` spawn so the matching destroy cascade can locate the
  spawned id WITHOUT reading the user's `:data :pending` (the v1
  pre-rf2-t07u magic).

  The four invariants under test:

   1. **Spawn writes the slot.** Entering a `:spawn`-bearing state
      writes `[:rf/runtime :machines :spawned <parent> <invoke-id>] = <spawned-id>` in
      the frame's app-db, alongside the spawned actor's snapshot at
      `[:rf/runtime :machines :snapshots <spawned-id>]`.

   2. **Destroy reads the slot, tears down, clears.** Exiting the
      `:spawn`-bearing state destroys the spawned actor and dissocs
      the registry slot. Per the lazy-allocation invariant (sibling to
      `[:rf/runtime :machines :system-ids]`), the empty parent map is
      pruned and the empty `[:rf/runtime :machines :spawned]` slot is
      dissoc'd entirely.

   3. **Auth-flow scenario without `:data :pending` magic.** A spec
      whose `:on-spawn` does NOT record the id in any `:data` slot
      still has the spawned actor cleanly destroyed on state exit —
      the runtime no longer reads `:data` to find the id. (Pre-rf2-t07u
      this scenario silently leaked the actor.)

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
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- snapshot
  "Read the snapshot for `machine-id` from the default frame's app-db."
  [machine-id]
  (get-in (rf/frame-db :rf/default) [:rf/runtime :machines :snapshots machine-id]))

(defn- frame-db []
  (rf/frame-db :rf/default))

;; ---- (1) spawn writes [:rf/runtime :machines :spawned <parent> <invoke-id>] ------------------

(deftest spawn-writes-runtime-registry-slot
  (testing "entering a :spawn-bearing state binds [:rf/runtime :machines :spawned <parent> <invoke-id>] to the spawned-id"
    (let [;; Per rf2-grw4i / rf2-v0rrr the :on-spawn callback is purely
          ;; advisory — its return value is ignored. We capture the id
          ;; via a side-effect atom to verify the callback fires; the
          ;; runtime tracks the id at [:rf/runtime :machines :spawned ...] regardless.
          observed (atom nil)
          child  {:initial :running
                  :data    {}
                  :states  {:running {}}}
          parent {:initial :idle
                  :on-spawn-actions
                  ;; Observational body — side-effect into an atom, returns
                  ;; nil (the advisory contract drops the return; returning
                  ;; nil keeps the dev-warn quiet, rf2-dtth6).
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
            spawned-id  (get-in db [:rf/runtime :machines :spawned :sup/flow [:working]])]
        (is (= :worker/proc#1 spawned-id)
            "the spawn registry slot is bound to the deterministic actor id")
        (is (some? (get-in db [:rf/runtime :machines :snapshots spawned-id]))
            "the spawned actor's snapshot lives at [:rf/runtime :machines :snapshots <spawned-id>]")
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
        (is (= :worker/proc#1 (get-in db [:rf/runtime :machines :spawned :sup/flow [:working]]))
            "(precondition) the slot was bound on entry"))
      ;; Now leave :working.
      (rf/dispatch-sync [:sup/flow [:done]])
      (let [db (frame-db)]
        (is (nil? (get-in db [:rf/runtime :machines :snapshots :worker/proc#1]))
            "the spawned actor's snapshot was cleared on destroy")
        (is (nil? (get-in db [:rf/runtime :machines :spawned :sup/flow [:working]]))
            "the registry slot was cleared on destroy")
        ;; Lazy-allocation invariant: the now-empty parent submap is
        ;; pruned, and the now-empty [:rf/runtime :machines :spawned]
        ;; slot is dissoc'd entirely (sibling to :system-ids).
        (is (not (contains? (get-in db [:rf/runtime :machines]) :spawned))
            "the empty :spawned slot under [:rf/runtime :machines] is pruned to absent")))))

;; ---- (3) auth-flow scenario WITHOUT user-side :on-spawn bookkeeping -------
;;
;; The scenario the rf2-t07u DESIGN section calls out: a :spawn whose
;; user-supplied :on-spawn doesn't write the id under any :data slot.
;; Pre-rf2-t07u the runtime silently leaked the spawned actor on state-exit
;; (it had no `:data :pending` to read). With Option A revised, the runtime
;; tracks the id internally and the destroy cascade works correctly.

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
            spawned-id (get-in db [:rf/runtime :machines :spawned :auth/main [:authenticating]])]
        (is (= :http/post#1 spawned-id))
        (is (some? (get-in db [:rf/runtime :machines :snapshots spawned-id])))
        (is (= {} (get-in (snapshot :auth/main) [:data]))
            "user's :data is untouched — runtime no longer requires :on-spawn"))
      ;; Mid-flight abandon → :idle.
      (rf/dispatch-sync [:auth/main [:auth/failed]])
      (let [db (frame-db)]
        (is (nil? (get-in db [:rf/runtime :machines :snapshots :http/post#1]))
            "the spawned actor was destroyed despite no :on-spawn having recorded the id")
        (is (not (contains? (get-in db [:rf/runtime :machines]) :spawned))
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
        (is (= :child/a#1 (get-in db [:rf/runtime :machines :spawned :sup/multi [:a-running]])))
        (is (nil?           (get-in db [:rf/runtime :machines :spawned :sup/multi [:b-running]]))))
      ;; Tear A down, spawn B.
      (rf/dispatch-sync [:sup/multi [:back]])
      (rf/dispatch-sync [:sup/multi [:fork-b]])
      (let [db (frame-db)]
        (is (= :child/b#1 (get-in db [:rf/runtime :machines :spawned :sup/multi [:b-running]])))
        (is (nil?           (get-in db [:rf/runtime :machines :spawned :sup/multi [:a-running]]))
            "A's slot was cleared when A was destroyed")
        ;; A is gone.
        (is (nil? (get-in db [:rf/runtime :machines :snapshots :child/a#1])))
        ;; B is alive.
        (is (some? (get-in db [:rf/runtime :machines :snapshots :child/b#1]))))
      ;; Tear B down too — both slots cleared, root pruned.
      (rf/dispatch-sync [:sup/multi [:back]])
      (let [db (frame-db)]
        (is (not (contains? (get-in db [:rf/runtime :machines]) :spawned))
            "with both invokes torn down, the lazy-allocation slot is dissoc'd")))))

;; ---- (5) keyword-form [:rf.machine/destroy actor-id] still works ---------
;;
;; The legacy / imperative form (action emits `[:rf.machine/destroy actor-id]`
;; with the recorded id directly) MUST continue to work — only the
;; declarative-:spawn desugar adopts the runtime-resolved map form. This
;; pins the back-compat path so user-written destroys aren't silently
;; broken by the rf2-t07u change.

(deftest legacy-keyword-form-destroy-machine-still-works
  (testing "[:rf.machine/destroy actor-id] (keyword arg) preserves pre-rf2-t07u semantics"
    (let [child  {:initial :running :data {} :states {:running {}}}
          ;; Per rf2-grw4i / rf2-v0rrr `:on-spawn` is advisory only —
          ;; user code that needs the id at action time uses an atom
          ;; sidechannel or reads `[:rf/runtime :machines :spawned <parent> <invoke-id>]`
          ;; from the runtime-tracked slot. This test stashes the id in
          ;; an atom so the `:tear-down` action can emit the legacy
          ;; keyword-form destroy.
          recorded (atom nil)
          parent {:initial :idle
                  :actions
                  ;; User-written exit action emits the keyword form
                  ;; directly — same shape user code has always used.
                  {:tear-down (fn [_ctx]
                                {:fx [[:rf.machine/destroy @recorded]]})}
                  :on-spawn-actions
                  ;; Sidechannel-atom capture — returns nil (advisory; the
                  ;; runtime drops the return, rf2-dtth6).
                  {:record (fn [{:keys [id]}] (reset! recorded id) nil)}
                  :states
                  {:idle    {:on {:start :working}}
                   :working {:spawn {:machine-id :worker/proc
                                      :on-spawn   :record}
                             :on    {:done {:target :idle :action :tear-down}}}}}]
      (rf/reg-machine :worker/proc child)
      (rf/reg-machine :sup/legacy parent)
      (rf/dispatch-sync [:sup/legacy [:start]])
      (let [spawned-id (get-in (frame-db) [:rf/runtime :machines :spawned :sup/legacy [:working]])]
        (is (= :worker/proc#1 spawned-id)))
      (rf/dispatch-sync [:sup/legacy [:done]])
      (let [db (frame-db)]
        ;; Both the user's keyword-form destroy AND the runtime's
        ;; tracked-form destroy fired in this transition. The runtime's
        ;; destroy is idempotent (the spawn registry slot resolves to
        ;; either the same actor-id or nil after the user's destroy).
        (is (nil? (get-in db [:rf/runtime :machines :snapshots :worker/proc#1]))
            "the spawned actor is gone")))))

;; ---- (5) spawned actor's snapshot carries :rf/spawn-counter + :meta -------
;;
;; Per rf2-fgqs4 the unified build-initial-snapshot helper seeds
;; :rf/spawn-counter {} and propagates :meta on every snapshot it
;; builds — including spawned actors. Before rf2-fgqs4 the spawn path
;; called a stripped-down builder that omitted both keys; the runtime
;; then fell back to the defensive (fnil inc 0) backstop and the
;; spawned child's :meta was dropped.

(deftest spawned-actor-snapshot-carries-spawn-counter
  (testing "a spawned actor's initial snapshot has :rf/spawn-counter {}"
    (let [child  {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :states  {:idle    {:on {:start :working}}
                            :working {:spawn {:machine-id :worker/sc}}}}]
      (rf/reg-machine :worker/sc child)
      (rf/reg-machine :sup/sc parent)
      (rf/dispatch-sync [:sup/sc [:start]])
      (let [spawned-id (get-in (frame-db) [:rf/runtime :machines :spawned :sup/sc [:working]])
            snap       (get-in (frame-db) [:rf/runtime :machines :snapshots spawned-id])]
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
      (let [spawned-id (get-in (frame-db) [:rf/runtime :machines :spawned :sup/meta [:working]])
            snap       (get-in (frame-db) [:rf/runtime :machines :snapshots spawned-id])]
        (is (= {:foo :bar :version 7} (:meta snap))
            "spec-declared :meta is propagated to the spawned actor's snapshot")))))

;; ---- (rf2-dtth6) dev-warn when :on-spawn returns a dropped value ---------
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
  Returns the vector of operations seen during the body."
  [thunk]
  (let [seen (atom [])]
    (trace/register-listener! ::on-spawn-warn (fn [ev] (swap! seen conj (:operation ev))))
    (try (thunk) (finally (trace/unregister-listener! ::on-spawn-warn)))
    @seen))

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
      (let [child-id      (get-in (frame-db) [:rf/runtime :machines :spawned :sup/cascade [:working]])
            grandchild-id (get-in (frame-db) [:rf/runtime :machines :spawned child-id [:booting]])
            child-snap    (get-in (frame-db) [:rf/runtime :machines :snapshots child-id])]
        (is (= :child/wraps#1 child-id)
            "child's id from parent's allocator")
        (is (= :grand/proc#1 grandchild-id)
            "grandchild's id allocated as <machine-id>#1 — deterministic form, NOT the fnil-inc backstop")
        (is (= {:grand/proc 1} (:rf/spawn-counter child-snap))
            "child's :rf/spawn-counter bumped on grandchild spawn")))))
