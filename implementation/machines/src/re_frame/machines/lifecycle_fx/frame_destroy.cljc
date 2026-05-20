(ns re-frame.machines.lifecycle-fx.frame-destroy
  "Frame-destroy machine-cascade orchestrator (rf2-vsigt).

  Per Spec 005 §Cross-Spec Interactions §1 — Frame disposal with active
  machine instances — `destroy-frame!` must:

    1. Walk every active machine on the frame in **reverse-creation
       order** (most recently spawned disposes first).
    2. Run each machine's `:exit` cascade BEFORE clearing its snapshot
       (the same Spec 005 §Declarative `:spawn` §Composition rule
       enforced by `:rf.machine/destroy` — leaf-to-root exit, side
       effects fire against the live snapshot).
    3. Abort that actor's in-flight `:rf.http/managed` requests
       (preserves the rf2-wvkn `:http/abort-on-actor-destroy` contract
       across every destroy trigger including frame destroy).
    4. Apply the unified app-db teardown projection — dissoc
       `[:rf/machines <id>]`, release `[:rf/system-ids <sid>]` when the
       actor was system-id-bound, prune `:rf/spawned` slots.
    5. Unregister the live actor event handler so subsequent dispatch
       to the address surfaces `:rf.error/no-such-handler` cleanly.
    6. Emit `:rf.machine.lifecycle/destroyed` with
       `:reason :parent-frame-destroyed` per actor — the contract
       observable in `frame_lifecycle_test/destroy-frame-cascade-
       emits-per-active-machine`.

  After every machine has settled, sub-cache disposes / substrate
  releases / `:frame/destroyed` trace fires from `frame/destroy-frame!`
  itself.

  Surfaces:
   - `teardown-on-frame-destroy!` — late-bound at
     `:machines/teardown-on-frame-destroy!`; called from
     `frame/destroy-frame!`.

  Source-of-truth on order: a process-side
  `re-frame.machines.spawn-order` atom records each spawned actor at
  install time. Snapshots that landed via direct `:rf/machines` assoc
  (test fixtures, hydration payloads) are not tracked in the channel
  but DO live in app-db — the walker covers them as a stragglers pass
  after the recorded vector drains."
  (:require [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.machines.lifecycle-fx.destroy :as destroy]
            [re-frame.machines.lifecycle-fx.exit-cascade :as exit-cascade]
            [re-frame.machines.spawn-order :as spawn-order]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

(defn- frame-machines-snapshot
  "Snapshot of `[:rf/machines]` on `frame-id`'s app-db (a map of
  actor-id → snapshot), or an empty map. Read at the start of the walk
  so we have a stable view of which actors were live; the walk itself
  swaps the container and these reads are not re-evaluated."
  [frame-id]
  (let [container (frame/get-frame-db frame-id)
        db        (when container (adapter/read-container container))]
    (or (get db :rf/machines) {})))

(defn- emit-lifecycle-destroyed!
  "Emit the legacy `:rf.machine.lifecycle/destroyed` notification per
  actor, carrying the frame id, the machine id, the snapshot's
  `:state` (when present), and the unified discriminator
  `:reason :parent-frame-destroyed`. Preserves the trace contract
  observable in
  `frame_lifecycle_test/destroy-frame-cascade-emits-per-active-machine`."
  [frame-id actor-id snapshot]
  (trace/emit! :rf.machine.lifecycle/destroyed :rf.machine.lifecycle/destroyed
               {:frame      frame-id
                :machine-id actor-id
                :last-state (:state snapshot)
                :reason     :parent-frame-destroyed}))

(defn- safe-abort-http!
  "Best-effort HTTP-abort: fire the late-bind hook if registered. The
  hook is owned by `re-frame.http-managed` (rf2-wvkn). Idempotent —
  calling against an actor with no in-flight requests is a no-op."
  [actor-id]
  (when-let [abort! (late-bind/get-fn :http/abort-on-actor-destroy)]
    (try (abort! actor-id)
         (catch #?(:clj Throwable :cljs :default) _ nil)))
  nil)

(defn- run-singleton-exit-cascade!
  "Singleton-machine destroy on frame teardown: run the actor's `:exit`
  cascade so Spec 005 §Final states §Composition with `:entry` /
  `:exit` symmetry holds, then abort in-flight HTTP. Does NOT
  unregister the handler — singleton handlers live in the global
  registrar per Spec 005 §Spawning §v1-partial footnote: the handler
  outlives any particular frame. Snapshot dissoc is moot at this point
  (the frame's app-db is about to be released)."
  [frame-id actor-id]
  (exit-cascade/run-child-exit! frame-id actor-id)
  (safe-abort-http! actor-id)
  nil)

(defn teardown-on-frame-destroy!
  "Run the full machine-cascade teardown for `frame-id`. Idempotent
  against double-invocation, fail-soft against missing artefacts.

  Per rf2-vsigt the orchestration is:
   1. Snapshot `[:rf/machines]` once.
   2. Build the disposal order: recorded spawn-order reversed (newest
      first) + any straggler actor-ids that live in `[:rf/machines]`
      but were never recorded (singleton handlers seeded directly,
      hydration payloads, test fixtures). Stragglers run after the
      recorded actors in app-db iteration order — there is no
      reverse-creation contract to honour for never-tracked actors.
   3. For each actor in the disposal order:
      a. Emit `:rf.machine.lifecycle/destroyed` BEFORE the destroy
         work so trace consumers see the signal while the handler
         still resolves — same convention as Spec 005 §Cancellation
         cascade D6.
      b. Dynamically-spawned actors (recorded in spawn-order): run
         the full single-actor destroy — exit-cascade →
         http-abort → unified teardown projection → system-id-release
         trace → handler-unregister → spawn-order forget.
      c. Singletons (registered via `reg-machine`, snapshot present
         but actor not spawned via spawn-fx): run the `:exit` cascade
         + HTTP abort, but DO NOT unregister the handler — singleton
         handlers live in the global registrar per Spec 005
         §Spawning v1-partial footnote and outlive any particular
         frame.
   4. Clear the frame's spawn-order slot."
  [frame-id]
  (when frame-id
    (let [snapshots    (frame-machines-snapshot frame-id)
          recorded     (spawn-order/frame-order frame-id)
          ;; Reverse recorded vector → newest spawn first.
          newest-first (reverse recorded)
          recorded-set (set recorded)
          ;; Stragglers: actor-ids in app-db's :rf/machines but absent
          ;; from the recorded spawn-order vector. Covers singleton
          ;; machines (registered via `reg-machine`), hydrated SSR
          ;; payloads, and test fixtures that seed snapshots directly.
          stragglers   (remove recorded-set (keys snapshots))]
      ;; Spawned actors: full destroy in reverse-creation order.
      (doseq [actor-id newest-first]
        (let [snapshot (get snapshots actor-id)]
          (emit-lifecycle-destroyed! frame-id actor-id snapshot))
        ;; `destroy-single-actor!` is fail-soft against a vanished
        ;; container and missing artefacts; wrap defensively so one
        ;; bad actor can't strand the rest of the cascade.
        (try (destroy/destroy-single-actor! frame-id actor-id)
             (catch #?(:clj Throwable :cljs :default) _ nil)))
      ;; Singletons / stragglers: trace + exit cascade + HTTP abort
      ;; only. The handler stays registered (lives in the global
      ;; registrar, outlives the frame).
      (doseq [actor-id stragglers]
        (let [snapshot (get snapshots actor-id)]
          (emit-lifecycle-destroyed! frame-id actor-id snapshot))
        (try (run-singleton-exit-cascade! frame-id actor-id)
             (catch #?(:clj Throwable :cljs :default) _ nil)))
      ;; Drop the frame's spawn-order slot — every recorded actor is
      ;; gone, and a fresh `reg-frame` under the same id starts with a
      ;; clean order channel.
      (spawn-order/clear-frame! frame-id))
    nil))
