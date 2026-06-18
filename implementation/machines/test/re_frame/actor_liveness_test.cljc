(ns re-frame.actor-liveness-test
  "Per rf2-a2sn1 — a dynamically-spawned machine actor's LIVENESS is
  derived from its (revertible) runtime-db snapshot, NOT from a per-instance
  registrar entry.

  The design defect this pins the fix for: machine STATE lived in the frame
  value (revertible) but a spawned actor's LIVENESS — its per-instance
  event-handler registration — lived OUTSIDE the frame value, in the
  registrar. `restore-epoch!` reverts the frame value, not the registrar, so
  it could never revert the registration: rewinding past a spawn orphaned
  the handler; rewinding past a destroy left the snapshot inspectable but the
  handler gone → `:rf.error/no-such-handler`. The fix (Mike-ruled
  LAZY-RESOLVER): spawn/destroy become PURE frame-value writes (install/remove
  the snapshot + the spawn-registry slot in runtime-db, stamping the
  revertible `:rf/machine-type`), and a dispatch to an unregistered actor-id
  lazily resolves the actor's TYPE handler from its snapshot. Liveness ==
  snapshot presence. Post-EP-0001 (rf2-vzld77) the snapshot is durable
  runtime-db state, so liveness lives in the runtime-db partition.

  These JVM+CLJS unit tests pin the machines-side invariants WITHOUT the
  epoch artefact: spawn/destroy mutate ZERO registrar state, dispatch
  lazy-resolves through the snapshot, and a runtime-db revert (the partition
  `restore-epoch!` walks machine snapshots back through, as part of its
  whole-frame-state rewind) reverts an actor's liveness perfectly. The
  genuine end-to-end `restore-epoch!` repros live in
  `implementation/epoch/test/.../actor_revertibility_restore_test.clj`
  (the epoch artefact already test-deps machines).

  Spec contract: [Spec 005 §Spawning §Liveness is derived from runtime-db]."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.frame :as frame]
   [re-frame.machines.test-support :as mtest]
   [re-frame.registrar :as registrar]
   [re-frame.substrate.adapter :as adapter]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter})))

;; snapshot lookup via the shared machines test-support (rf2-3l8lqe finding #4)
;; — no hardcoded `[:rf.runtime/machines :snapshots …]` path.
(def ^:private snapshot mtest/snapshot)

(defn- registrar-event-snapshot
  "A value-snapshot of every registered :event id — the bit we assert is
  UNCHANGED across a spawn/destroy (no registrar drift)."
  []
  (set (keys (registrar/registrations :event))))

(defn- revert-app-db!
  "Reset `frame-id`'s RUNTIME-DB to `runtime-db` — the partition a
  `restore-epoch!` / frame-state revert walks machine snapshots back through.
  Used here so the machines unit test exercises the revertibility property
  without test-dep'ing the epoch artefact.

  EP-0001 (rf2-vzld77): a spawned actor's LIVENESS is its snapshot's presence
  in the **runtime-db** partition (machine snapshots are durable runtime-db
  state), so reverting liveness means reverting runtime-db — written via
  `frame/swap-runtime-db!`. (The full frame-state restore PROJECTIONS are
  bead 7 — rf2-3aizt1; this helper installs just the runtime-db partition.)"
  [frame-id runtime-db]
  (frame/swap-runtime-db! frame-id (constantly runtime-db)))

;; A parent that spawns one child of a registered TYPE on `:go` and
;; destroys it on `:drop`. The child increments a counter on `:bump` so
;; we can prove the lazy-resolved handler actually drives a transition.
(defn- counter-child []
  {:initial :live
   :data    {:n 0}
   :actions {:bump (fn [{data :data}] {:data (update data :n inc)})}
   :states  {:live {:on {:bump {:action :bump}}}}})

(defn- spawning-parent [child-type]
  {:initial :idle
   :data    {}
   :states  {:idle {:on {:go   {:action (fn [_]
                                          {:fx [[:rf.machine/spawn
                                                 {:machine-id child-type
                                                  :id-prefix  child-type}]]})}}}}})

;; ---- spawn / destroy perform ZERO registrar mutation ----------------------

(deftest spawn-and-destroy-are-pure-app-db-writes
  (testing "rf2-a2sn1 — a spawned actor never registers a per-instance
            handler; spawn and destroy leave the :event registrar
            value-identical (liveness lives in the snapshot)"
    (rf/reg-machine :al/child  (counter-child))
    (rf/reg-machine :al/parent (assoc-in (spawning-parent :al/child)
                                         [:states :idle :on :drop]
                                         {:action (fn [_]
                                                    {:fx [[:rf.machine/destroy :al/child#1]]})}))
    (let [reg-before (registrar-event-snapshot)]
      ;; Spawn.
      (rf/dispatch-sync [:al/parent [:go]])
      (is (some? (snapshot :al/child#1))
          "spawned actor's snapshot is installed (it is alive)")
      (is (nil? (registrar/lookup :event :al/child#1))
          "spawn registered NO per-instance handler")
      (is (= reg-before (registrar-event-snapshot))
          "spawn mutated the :event registrar by exactly nothing")
      ;; The spawned snapshot carries its revertible TYPE reference.
      (is (= :al/child (:rf/machine-type (snapshot :al/child#1)))
          "snapshot carries :rf/machine-type so liveness is app-db-derived")
      ;; Destroy.
      (rf/dispatch-sync [:al/parent [:drop]])
      (is (nil? (snapshot :al/child#1))
          "destroy removed the snapshot (the actor is no longer alive)")
      (is (= reg-before (registrar-event-snapshot))
          "destroy mutated the :event registrar by exactly nothing"))))

;; ---- dispatch lazily resolves a spawned actor through its snapshot --------

(deftest dispatch-to-spawned-actor-lazy-resolves-via-snapshot
  (testing "rf2-a2sn1 — a dispatch to a spawned actor-id (no registrar
            entry) lazy-resolves the actor's TYPE handler from its
            snapshot and drives a real transition"
    (rf/reg-machine :al2/child  (counter-child))
    (rf/reg-machine :al2/parent (spawning-parent :al2/child))
    (rf/dispatch-sync [:al2/parent [:go]])
    (is (nil? (registrar/lookup :event :al2/child#1))
        "no per-instance handler is registered for the spawned actor")
    (is (= 0 (:n (:data (snapshot :al2/child#1)))))
    ;; Dispatch straight at the spawned actor-id — this only resolves via
    ;; the lazy resolver (there is no registrar entry to find).
    (rf/dispatch-sync [:al2/child#1 [:bump]])
    (rf/dispatch-sync [:al2/child#1 [:bump]])
    (is (= 2 (:n (:data (snapshot :al2/child#1))))
        "the lazy-resolved handler ran and bumped the actor's own snapshot")))

;; ---- genuine no-such-handler when no live snapshot ------------------------

(deftest dispatch-to-gone-actor-is-clean-no-such-handler
  (testing "rf2-a2sn1 — dispatching to an actor-id with NO live snapshot
            resolves to nothing (genuine no-such-handler; the resolver
            does not fabricate a handler)"
    (rf/reg-machine :al3/child  (counter-child))
    (rf/reg-machine :al3/parent (spawning-parent :al3/child))
    ;; Never spawned: :al3/child#1 has no snapshot.
    (is (nil? (snapshot :al3/child#1)))
    (let [errors (atom [])]
      (rf/register-listener! :trace ::al3 (fn [ev]
                                     (when (= :rf.error/no-such-handler (:operation ev))
                                       (swap! errors conj ev))))
      (rf/dispatch-sync [:al3/child#1 [:bump]])
      (rf/unregister-listener! :trace ::al3)
      (is (seq @errors)
          ":rf.error/no-such-handler fired — the resolver declined (no live snapshot)")
      (is (nil? (snapshot :al3/child#1))
          "no snapshot was fabricated"))))

;; ---- liveness reverts with a runtime-db reset (what restore-epoch! does) ----

(deftest actor-liveness-reverts-with-runtime-db
  (testing "rf2-a2sn1 / rf2-vzld77 — resetting the frame's runtime-db to a
            captured value (a frame-state revert walks machine snapshots back
            through the runtime-db partition) reverts an actor's liveness
            perfectly, with NO registrar drift"
    (rf/reg-machine :al4/child  (counter-child))
    (rf/reg-machine :al4/parent (assoc-in (spawning-parent :al4/child)
                                          [:states :idle :on :drop]
                                          {:action (fn [_]
                                                     {:fx [[:rf.machine/destroy :al4/child#1]]})}))
    ;; Capture app-db BEFORE the actor exists (rewind-past-spawn target).
    (let [db-before-spawn (rf/runtime-db-value :rf/default)]
      (rf/dispatch-sync [:al4/parent [:go]])
      (is (some? (snapshot :al4/child#1)) "actor alive after spawn")
      ;; Capture app-db while the actor IS alive (rewind-past-destroy
      ;; target).
      (let [db-while-alive (rf/runtime-db-value :rf/default)]
        (rf/dispatch-sync [:al4/child#1 [:bump]])
        (is (= 1 (:n (:data (snapshot :al4/child#1)))))
        (rf/dispatch-sync [:al4/parent [:drop]])
        (is (nil? (snapshot :al4/child#1)) "actor destroyed")

        ;; (a) Revert to BEFORE the spawn (rewind-past-spawn). The actor's
        ;; snapshot vanishes — no orphaned handler survives, because there
        ;; never was a per-instance registration. A dispatch to the gone
        ;; actor is a clean no-such-handler.
        (revert-app-db! :rf/default db-before-spawn)
        (is (nil? (snapshot :al4/child#1))
            "rewind-past-spawn: the actor's snapshot is gone")
        (is (nil? (registrar/lookup :event :al4/child#1))
            "rewind-past-spawn: NO orphaned handler survives the revert
             (the {:handler-survived-restore? true} leak is closed)")

        ;; (b) Revert to WHILE-ALIVE (rewind-past-destroy — the key fix).
        ;; The snapshot comes back AND a dispatch to the actor RESOLVES via
        ;; the lazy resolver (not :no-such-handler).
        (revert-app-db! :rf/default db-while-alive)
        (is (some? (snapshot :al4/child#1))
            "rewind-past-destroy: the actor's snapshot is restored")
        (rf/dispatch-sync [:al4/child#1 [:bump]])
        (is (= 1 (:n (:data (snapshot :al4/child#1))))
            "rewind-past-destroy: dispatch RESOLVED and drove a transition —
             liveness reverted with the snapshot (NOT :no-such-handler)")))))

;; ---- singleton negative guard ---------------------------------------------

(deftest singletons-still-register-and-dispatch-unchanged
  (testing "rf2-a2sn1 — a boot-registered (singleton) machine still
            registers a handler and dispatches normally; the lazy
            resolver does not change the singleton path"
    (rf/reg-machine :al5/single (counter-child))
    (is (some? (registrar/lookup :event :al5/single))
        "a reg-machine singleton IS registered in the :event registrar")
    (rf/dispatch-sync [:al5/single [:bump]])
    (is (= 1 (:n (:data (snapshot :al5/single))))
        "the singleton's registered handler drove the transition")
    ;; A singleton snapshot carries NO :rf/machine-type — it is resolved
    ;; through the registrar, not the lazy resolver.
    (is (nil? (:rf/machine-type (snapshot :al5/single)))
        "singleton snapshots carry no :rf/machine-type (registrar-resolved)")))

;; ---- nested / parallel spawned actors resolve correctly -------------------

(deftest parallel-spawned-actors-each-resolve-independently
  (testing "rf2-a2sn1 — multiple live spawned actors of the same TYPE each
            lazy-resolve to their OWN snapshot"
    (rf/reg-machine :al6/child  (counter-child))
    (rf/reg-machine :al6/parent
      {:initial :idle
       :data    {}
       :states  {:idle {:on {:go {:action (fn [_]
                                            {:fx [[:rf.machine/spawn
                                                   {:machine-id :al6/child :id-prefix :al6/child}]
                                                  [:rf.machine/spawn
                                                   {:machine-id :al6/child :id-prefix :al6/child}]]})}}}}})
    (rf/dispatch-sync [:al6/parent [:go]])
    (is (some? (snapshot :al6/child#1)))
    (is (some? (snapshot :al6/child#2)))
    ;; Bump #1 twice, #2 once — each dispatch lazy-resolves to the right
    ;; per-actor snapshot.
    (rf/dispatch-sync [:al6/child#1 [:bump]])
    (rf/dispatch-sync [:al6/child#1 [:bump]])
    (rf/dispatch-sync [:al6/child#2 [:bump]])
    (is (= 2 (:n (:data (snapshot :al6/child#1)))) "actor #1 has its own count")
    (is (= 1 (:n (:data (snapshot :al6/child#2)))) "actor #2 has its own count")))
