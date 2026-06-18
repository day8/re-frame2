(ns re-frame.actor-revertibility-restore-test
  "Per rf2-a2sn1 — the genuine end-to-end `restore-epoch!` repros for the
  dynamically-spawned-actor revertibility leak (Goal 2).

  These are the two live repros the bead pinned as the acceptance bar,
  run against the REAL epoch `restore-epoch!` path (the epoch artefact
  test-deps machines):

    rewind-past-spawn  — register/spawn an actor, restore-epoch! to BEFORE
                         it existed; assert NO orphaned handler survives
                         (the `{:handler-survived-restore? true}` leak is
                         closed) and a dispatch to the gone actor is a
                         clean :rf.error/no-such-handler.

    rewind-past-destroy (the key fix) — spawn → destroy → restore-epoch! to
                         when it was alive; assert a dispatch to the actor
                         now RESOLVES via the lazy resolver and drives a
                         transition (NOT :rf.error/no-such-handler).

  Before the fix, a spawned actor's liveness lived in the registrar
  (outside the frame value), so `restore-epoch!` could not revert it. After
  the fix (LAZY-RESOLVER), spawn/destroy are pure frame-state writes and an
  actor's liveness IS its snapshot's presence in the (revertible) runtime-db
  partition — so `restore-epoch!` reverts it perfectly.

  EP-0001 (rf2-vzld77 / rf2-3aizt1): machine snapshots are runtime-db
  partition state at `[:rf.runtime/machines :snapshots <id>]`; the epoch now
  captures the whole frame-state (`:frame-state-before/-after`, decision #2)
  and `restore-epoch!` reinstalls both partitions via `replace-frame-state!`,
  so reverting a spawn / destroy reverts the runtime-db liveness. These three
  end-to-end repros (deferred by rf2-vzld77) are re-enabled here.

  Spec: [Spec 005 §Spawning §Liveness is derived from app-db] and
  [000-Vision Goal 2 — Frame state revertibility]."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.elision]
            [re-frame.epoch :as epoch]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            ;; Side-effect require — loads the machines late-bind hooks
            ;; (`:machines/reg-machine`, `:machines/resolve-actor-handler-meta`,
            ;; `:machines/actor-resolvable?`) and the `:rf.machine/spawn` /
            ;; `:rf.machine/destroy` fxs the tests exercise. The
            ;; capture/restore fixture preserves these ns-load-time
            ;; registrations across each test.
            [re-frame.machines]))

;; Use the canonical capture/restore fixture (NOT a clear-all! reset) so
;; the machines artefact's ns-load-time fx + sub registrations survive —
;; my tests use the `:rf.machine/spawn` fx, which `clear-all!` would drop.
;; Clear the epoch ring/listeners before each test via the :init-fn.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn []
                (epoch/clear-history!)
                (epoch/clear-epoch-listeners!))}))

(defn- snapshot [machine-id]
  ;; EP-0001 (rf2-vzld77 / rf2-3aizt1): machine snapshots are runtime-db
  ;; partition state at [:rf.runtime/machines :snapshots <id>].
  (get-in (rf/runtime-db-value :test/main)
          [:rf.runtime/machines :snapshots machine-id]))

(defn- last-epoch-id []
  (:epoch-id (last (rf/epoch-history :test/main))))

(defn- record-trace! []
  (let [recorded (atom [])]
    (rf/register-listener! :trace ::rec (fn [ev] (swap! recorded conj ev)))
    recorded))

(defn- counter-child []
  {:initial :live
   :data    {:n 0}
   :actions {:bump (fn [{data :data}] {:data (update data :n inc)})}
   :states  {:live {:on {:bump {:action :bump}}}}})

(defn- parent []
  {:initial :idle
   :data    {}
   :states  {:idle {:on {:go   {:action (fn [_]
                                          {:fx [[:rf.machine/spawn
                                                 {:machine-id :rev/child
                                                  :id-prefix  :rev/child}]]})}
                          :drop {:action (fn [_]
                                           {:fx [[:rf.machine/destroy :rev/child#1]]})}}}}})

;; ---- rewind PAST A SPAWN — no orphaned handler ----------------------------

;; EP-0001 (rf2-vzld77) re-enabled by bead 7 (rf2-3aizt1). These end-to-end
;; restore-epoch! repros revert actor LIVENESS, which is a spawned actor's
;; snapshot presence in the runtime-db partition (rf2-vzld77 moved snapshots
;; there). bead 7 made the epoch capture the whole frame-state
;; (`:frame-state-before/-after`) and `restore-epoch!` reinstall BOTH partitions
;; via `replace-frame-state!`, so reverting/restoring runtime-db state works
;; end-to-end.
(deftest restore-past-spawn-leaves-no-orphan
  (testing "rf2-a2sn1 — restore-epoch! to BEFORE a spawn reverts the
            actor's liveness; no orphaned handler survives (the
            {:handler-survived-restore? true} leak is closed)"
    (rf/reg-frame :test/main {})
    (rf/reg-machine :rev/child  (counter-child))
    (rf/reg-machine :rev/parent (parent))
    ;; Seed an epoch that PRE-DATES the spawn (a no-op self-event on a
    ;; trivial handler so there is a clean :ok epoch to rewind to).
    (rf/reg-event :test/noop (fn [{:keys [db]} _] {:db (assoc db :seeded true)}))
    (rf/dispatch-sync [:test/noop] {:frame :test/main})
    (let [pre-spawn-epoch (last-epoch-id)]
      ;; Spawn the actor.
      (rf/dispatch-sync [:rev/parent [:go]] {:frame :test/main})
      (is (some? (snapshot :rev/child#1)) "actor alive after spawn")
      (is (nil? (registrar/lookup :event :rev/child#1))
          "spawned actor never registered a per-instance handler")
      ;; Rewind PAST the spawn.
      (let [ok? (rf/restore-epoch! :test/main pre-spawn-epoch)]
        (is (true? ok?) "restore-epoch! to the pre-spawn epoch succeeded")
        (is (nil? (snapshot :rev/child#1))
            "rewind-past-spawn: the actor's snapshot is gone")
        ;; The repro's headline assertion — inverted to the post-fix value.
        (is (nil? (registrar/lookup :event :rev/child#1))
            "{:handler-survived-restore? false} — NO orphaned handler
             survives the revert")
        ;; Dispatch to the gone actor → clean no-such-handler.
        (let [errs (record-trace!)]
          (rf/dispatch-sync [:rev/child#1 [:bump]] {:frame :test/main})
          (rf/unregister-listener! :trace ::rec)
          (is (some #(= :rf.error/no-such-handler (:operation %)) @errs)
              "dispatch to the gone actor is a clean :rf.error/no-such-handler"))))))

;; ---- rewind PAST A DESTROY — liveness comes back (the key fix) ------------

;; EP-0001 (rf2-vzld77) re-enabled by bead 7 (rf2-3aizt1): see note above.
(deftest restore-past-destroy-rematerialises-liveness
  (testing "rf2-a2sn1 (the key fix) — restore-epoch! to when an actor was
            ALIVE re-materialises its liveness: a dispatch to it RESOLVES
            via the lazy resolver and drives a transition (NOT
            :rf.error/no-such-handler)"
    (rf/reg-frame :test/main {})
    (rf/reg-machine :rev/child  (counter-child))
    (rf/reg-machine :rev/parent (parent))
    ;; Spawn → the actor is alive. Capture the alive epoch.
    (rf/dispatch-sync [:rev/parent [:go]] {:frame :test/main})
    (is (some? (snapshot :rev/child#1)) "actor alive after spawn")
    (let [alive-epoch (last-epoch-id)]
      ;; Drive a transition so the alive epoch's :n is a known value.
      (rf/dispatch-sync [:rev/child#1 [:bump]] {:frame :test/main})
      (is (= 1 (:n (:data (snapshot :rev/child#1)))))
      ;; Destroy the actor.
      (rf/dispatch-sync [:rev/parent [:drop]] {:frame :test/main})
      (is (nil? (snapshot :rev/child#1)) "actor destroyed")
      ;; Confirm the BUG's other symptom is gone too: a dispatch to the
      ;; destroyed actor is a clean no-such-handler (no stale state).
      ;; Rewind to when the actor was alive (the snapshot recorded at the
      ;; spawn epoch carries :n 0).
      (let [ok? (rf/restore-epoch! :test/main alive-epoch)]
        (is (true? ok?)
            "restore-epoch! to the alive epoch SUCCEEDED — the spawned-actor
             snapshot is a valid restore target (its TYPE resolves), NOT a
             :rf.epoch/restore-missing-handler")
        (is (some? (snapshot :rev/child#1))
            "rewind-past-destroy: the actor's snapshot is restored")
        ;; THE FIX: dispatch resolves via the lazy resolver and transitions.
        (rf/dispatch-sync [:rev/child#1 [:bump]] {:frame :test/main})
        (is (= 1 (:n (:data (snapshot :rev/child#1))))
            "rewind-past-destroy: dispatch RESOLVED and drove a transition
             (the restored snapshot had :n 0, the bump made it 1) — NOT
             :rf.error/no-such-handler")))))

;; ---- a missing TYPE is still a genuine missing-handler --------------------

;; EP-0001 (rf2-vzld77) re-enabled by bead 7 (rf2-3aizt1): see note above.
(deftest restore-with-missing-type-still-fails-missing-handler
  (testing "rf2-a2sn1 — a spawned-actor snapshot whose TYPE was
            unregistered is NOT restorable: restore-epoch! fires
            :rf.epoch/restore-missing-handler (the singleton-style
            missing-reference contract still holds)"
    (rf/reg-frame :test/main {})
    (rf/reg-machine :rev/child  (counter-child))
    (rf/reg-machine :rev/parent (parent))
    (rf/dispatch-sync [:rev/parent [:go]] {:frame :test/main})
    (let [alive-epoch (last-epoch-id)]
      (is (some? (snapshot :rev/child#1)))
      ;; Unregister the TYPE so the recorded snapshot's :rf/machine-type
      ;; no longer resolves.
      (registrar/unregister! :event :rev/child)
      (let [errs (record-trace!)
            pre  (rf/app-db-value :test/main)
            ok?  (rf/restore-epoch! :test/main alive-epoch)]
        (rf/unregister-listener! :trace ::rec)
        (is (false? ok?) "restore refused — the actor's TYPE is gone")
        (is (= pre (rf/app-db-value :test/main)) "app-db unchanged on refusal")
        (let [ev (some #(when (= :rf.epoch/restore-missing-handler (:operation %)) %) @errs)]
          (is (some? ev) ":rf.epoch/restore-missing-handler fired")
          (is (some #(= :rev/child#1 (:id %)) (:missing (:tags ev)))
              "the unresolvable spawned actor surfaces in :missing"))))))

;; ---- rf2-rlt3sv — SPAWNED-actor snapshot VERSION drift --------------------
;;
;; The epoch restore version-drift probe (`machine-version-mismatch`) compared
;; the snapshot KEY against the machine registrar for every snapshot. For a
;; SPAWNED actor the key is an instance id (`:rev/child#1`) with NO per-instance
;; registration — the actor's TYPE rides the snapshot under `:rf/machine-type`.
;; So a hot-reloaded spawned-actor TYPE's `:rf/snapshot-version` bump was NEVER
;; observed: an older, incompatible snapshot was accepted by `restore-epoch!`
;; reporting success. The fix resolves the current definition the same way
;; dispatch does — singleton by key, spawned actor by `:rf/machine-type` —
;; so the drift fires `:rf.epoch/restore-version-mismatch` (false, frame-state
;; unchanged, documented trace with both the instance id and the TYPE).

(defn- versioned-child [v]
  {:initial :live
   :meta    {:rf/snapshot-version v}
   :data    {:n 0}
   :actions {:bump (fn [{data :data}] {:data (update data :n inc)})}
   :states  {:live {:on {:bump {:action :bump}}}}})

(defn- spawning-parent
  "A parent that spawns a child of `child-type` (a registered :machine-id
  keyword OR an inline :definition spec map) under id-prefix :rev/child."
  [child-type]
  {:initial :idle
   :data    {}
   :states  {:idle {:on {:go {:action (fn [_]
                                        {:fx [[:rf.machine/spawn
                                               (cond-> {:id-prefix :rev/child}
                                                 (keyword? child-type) (assoc :machine-id child-type)
                                                 (map? child-type)     (assoc :definition child-type))]]})}}}}})

(deftest restore-spawned-actor-version-match-succeeds
  (testing "rf2-rlt3sv — a registered-TYPE spawned actor whose TYPE version is
            UNCHANGED restores cleanly (no false version-mismatch)"
    (rf/reg-frame :test/main {})
    (rf/reg-machine :rev/child  (versioned-child 1))
    (rf/reg-machine :rev/parent (spawning-parent :rev/child))
    (rf/dispatch-sync [:rev/parent [:go]] {:frame :test/main})
    (let [alive-epoch (last-epoch-id)]
      (is (= :rev/child (:rf/machine-type (snapshot :rev/child#1)))
          "spawned actor's snapshot carries its registered TYPE keyword")
      (is (= 1 (get-in (snapshot :rev/child#1) [:meta :rf/snapshot-version])))
      (let [ok? (rf/restore-epoch! :test/main alive-epoch)]
        (is (true? ok?) "version matches → restore succeeds")
        (is (some? (snapshot :rev/child#1)) "actor snapshot restored")))))

(deftest restore-spawned-actor-version-mismatch-registered-type-fails
  (testing "rf2-rlt3sv — a registered-TYPE spawned actor whose TYPE was
            hot-reloaded forward fires :rf.epoch/restore-version-mismatch,
            returns false, leaves frame-state unchanged, and surfaces BOTH the
            instance id and the TYPE in the trace"
    (rf/reg-frame :test/main {})
    (rf/reg-machine :rev/child  (versioned-child 1))
    (rf/reg-machine :rev/parent (spawning-parent :rev/child))
    (rf/dispatch-sync [:rev/parent [:go]] {:frame :test/main})
    (let [alive-epoch (last-epoch-id)]
      (is (some? (snapshot :rev/child#1)))
      ;; Hot-reload bumps the TYPE's version — the recorded snapshot stays v1.
      (rf/reg-machine :rev/child (versioned-child 2))
      (let [errs (record-trace!)
            pre  (rf/app-db-value :test/main)
            ok?  (rf/restore-epoch! :test/main alive-epoch)]
        (rf/unregister-listener! :trace ::rec)
        (is (false? ok?) "restore refused — spawned-actor TYPE version drifted")
        (is (= pre (rf/app-db-value :test/main)) "frame-state unchanged on refusal")
        (let [ev (some #(when (= :rf.epoch/restore-version-mismatch (:operation %)) %) @errs)]
          (is (some? ev) ":rf.epoch/restore-version-mismatch fired")
          (is (= :rev/child#1 (:machine-id (:tags ev)))
              "the trace identifies the spawned actor's INSTANCE id")
          (is (= :rev/child (:machine-type (:tags ev)))
              "the trace identifies the spawned actor's registered TYPE")
          (is (= 1 (:version-recorded (:tags ev))))
          (is (= 2 (:version-current  (:tags ev)))))))))

(deftest restore-spawned-actor-inline-definition-version-match-succeeds
  (testing "rf2-rlt3sv — an inline-:definition spawned actor whose snapshot
            carries the spec map verbatim restores cleanly when its recorded
            version equals the carried definition's version (the snapshot IS
            the source of truth — no drift possible against itself)"
    (rf/reg-frame :test/main {})
    ;; No reg-machine for the child — the parent spawns an INLINE definition.
    (rf/reg-machine :rev/parent (spawning-parent (versioned-child 1)))
    (rf/dispatch-sync [:rev/parent [:go]] {:frame :test/main})
    (let [alive-epoch (last-epoch-id)
          snap        (snapshot :rev/child#1)]
      (is (map? (:rf/machine-type snap))
          "inline-definition spawn carries the spec MAP on the snapshot")
      (is (= 1 (get-in snap [:meta :rf/snapshot-version])))
      (let [ok? (rf/restore-epoch! :test/main alive-epoch)]
        (is (true? ok?)
            "inline definition's version == recorded → restore succeeds")
        (is (some? (snapshot :rev/child#1)))))))

(deftest restore-spawned-actor-inline-definition-version-mismatch-fails
  (testing "rf2-rlt3sv — an inline-:definition spawned actor whose CARRIED
            definition declares a higher version than the recorded snapshot's
            fires :rf.epoch/restore-version-mismatch (the inline map IS the
            current definition resolved via :rf/machine-type)"
    (rf/reg-frame :test/main {})
    (rf/reg-machine :rev/parent (spawning-parent (versioned-child 1)))
    (rf/dispatch-sync [:rev/parent [:go]] {:frame :test/main})
    (let [alive-epoch (last-epoch-id)]
      ;; Forge version drift WITHIN the snapshot: the recorded snapshot's :meta
      ;; version (the restore target) is older than the carried inline
      ;; definition's :rf/machine-type :meta version (the "current" def). This
      ;; mirrors a hot-reload where an inline-spawned actor's definition moved
      ;; forward while an older recorded snapshot is being restored.
      (rf/reg-event :forge-drift
        (fn [{rt :rf.db/runtime} _]
          (let [snap (get-in rt [:rf.runtime/machines :snapshots :rev/child#1])
                bumped (-> snap
                           ;; carried current definition moves to v2 ...
                           (assoc-in [:rf/machine-type :meta :rf/snapshot-version] 2)
                           ;; ... while the recorded snapshot version stays v1
                           (assoc-in [:meta :rf/snapshot-version] 1))]
            {:rf.db/runtime
             (assoc-in rt [:rf.runtime/machines :snapshots :rev/child#1] bumped)})))
      (rf/dispatch-sync [:forge-drift] {:frame :test/main})
      (let [drift-epoch (last-epoch-id)
            errs        (record-trace!)
            pre         (rf/app-db-value :test/main)
            ok?         (rf/restore-epoch! :test/main drift-epoch)]
        (rf/unregister-listener! :trace ::rec)
        (is (false? ok?) "inline-definition version drift refuses restore")
        (is (= pre (rf/app-db-value :test/main)) "frame-state unchanged on refusal")
        (let [ev (some #(when (= :rf.epoch/restore-version-mismatch (:operation %)) %) @errs)]
          (is (some? ev) ":rf.epoch/restore-version-mismatch fired")
          (is (= :rev/child#1 (:machine-id (:tags ev))))
          (is (map? (:machine-type (:tags ev)))
              "the trace carries the inline-definition map as the TYPE")
          (is (= 1 (:version-recorded (:tags ev))))
          (is (= 2 (:version-current  (:tags ev)))))))))

(deftest restore-spawned-actor-missing-type-not-version-mismatch
  (testing "rf2-rlt3sv — a spawned actor whose registered TYPE was CLEARED is a
            MISSING reference (caught upstream by missing-references), NOT a
            version mismatch — the version probe never resolves a definition,
            so it does not fire :rf.epoch/restore-version-mismatch"
    (rf/reg-frame :test/main {})
    (rf/reg-machine :rev/child  (versioned-child 1))
    (rf/reg-machine :rev/parent (spawning-parent :rev/child))
    (rf/dispatch-sync [:rev/parent [:go]] {:frame :test/main})
    (let [alive-epoch (last-epoch-id)]
      (is (some? (snapshot :rev/child#1)))
      (registrar/unregister! :event :rev/child)   ;; clear the TYPE
      (let [errs (record-trace!)
            ok?  (rf/restore-epoch! :test/main alive-epoch)]
        (rf/unregister-listener! :trace ::rec)
        (is (false? ok?) "restore refused")
        (is (some #(= :rf.epoch/restore-missing-handler (:operation %)) @errs)
            "a cleared TYPE is a MISSING handler, not a version mismatch")
        (is (not-any? #(= :rf.epoch/restore-version-mismatch (:operation %)) @errs)
            "no version-mismatch trace fires when no definition resolves")))))
