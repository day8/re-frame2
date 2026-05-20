(ns re-frame.machines.lifecycle-fx.destroy
  "Destroy live-handler wiring: `:rf.machine/destroy` fx handler and its
  helpers.

  `apply-transition-once` emits `[:rf.machine/destroy actor-id]` into
  the fx vector whenever exit cascades cross a `:spawn`-bearing state.
  Per Spec 005 §Spawning, destroy unregisters the spawned actor's event
  handler, clears its snapshot at `[:rf/machines <id>]` in the spawning
  frame's app-db, and (if the actor was system-id-bound) clears the
  `[:rf/system-ids]` reverse index entry.

  Per rf2-t07u (Option A revised), `args` can be either:
    - a keyword `actor-id` — the legacy / imperative form (action emits
      `[:rf.machine/destroy actor-id]` directly with the recorded id), OR
    - a map `{:rf/parent-id ... :rf/spawn-id ...}` — the declarative-
      `:spawn` exit-cascade form, where the runtime resolves the actor
      id from `[:rf/spawned <parent-id> <invoke-id>]` in the frame's
      app-db.

  Per rf2-6vmw, the map form may also carry `:rf/spawn-all true` —
  the declarative-`:spawn-all` exit-cascade form. The slot at
  `[:rf/spawned <parent-id> <invoke-id>]` holds a join-state map whose
  `:children` sub-map has every spawned child id. The handler iterates
  `:children` and tears each one down, then clears the slot.

  Per rf2-lha2t the actor-teardown app-db dance lives in
  `re-frame.machines.lifecycle-fx.teardown` — one helper, three (now
  unified) call-sites."
  (:require [re-frame.frame :as frame]
            [re-frame.machines.lifecycle-fx.exit-cascade :as exit-cascade]
            [re-frame.machines.lifecycle-fx.finalize :as finalize]
            [re-frame.machines.lifecycle-fx.teardown :as teardown]
            [re-frame.machines.lifecycle-fx.traces :as traces]
            [re-frame.machines.spawn-order :as spawn-order]
            [re-frame.registrar :as registrar]))

#?(:clj (set! *warn-on-reflection* true))

(defn destroy-single-actor!
  "Destroy a single spawned actor against the frame's container: run
  the active configuration's `:exit` cascade (rf2-nahfm), apply the
  unified teardown projection (per
  `re-frame.machines.lifecycle-fx.teardown`), abort in-flight
  `:rf.http/managed` requests (rf2-wvkn), emit the
  `:system-id-released` trace, unregister the live event handler, and
  forget the actor from the per-frame spawn-order channel (rf2-vsigt).

  Used by `destroy-machine-fx` for the keyword-form legacy/imperative
  destroy AND iterated for each child in a `:spawn-all` teardown, AND
  by the frame-destroy cascade walker (`frame-destroy.cljc`).

  Per Spec 005 §Declarative `:spawn` §Composition with explicit
  `:entry` / `:exit`: the actor's `:exit` action runs BEFORE the
  teardown clears the snapshot, so `:exit`-time side effects (HTTP
  requests, logs, dispatches) execute against the live snapshot."
  [frame-id actor-id]
  (when actor-id
    ;; (rf2-nahfm) Run the active configuration's `:exit` cascade
    ;; BEFORE any teardown work. This fires `:exit`-emitted fx via
    ;; do-fx and writes any `:data` updates back to the snapshot —
    ;; both transient (the snapshot is dissoc'd two steps later).
    (exit-cascade/run-child-exit! frame-id actor-id)
    (finalize/abort-actor-in-flight-http! actor-id)
    ;; `teardown-actor` returns [new-db released-sid]; `swap-frame-db!`
    ;; expects a fn returning the new-db only. Capture sid via a side
    ;; channel so we keep a single read + single write.
    (let [sid (volatile! nil)
          db-swapped? (frame/swap-frame-db! frame-id
                                            (fn [db]
                                              (let [[new-db released-sid]
                                                    (teardown/teardown-actor db {:actor-id actor-id})]
                                                (vreset! sid released-sid)
                                                new-db)))]
      ;; (rf2-vsigt) Forget the actor regardless of whether the app-db
      ;; swap landed — by the time frame-destroy runs, the container
      ;; may already be nil but the spawn-order entry still needs
      ;; clearing.
      (spawn-order/forget! frame-id actor-id)
      (when db-swapped?
        (traces/emit-system-id-released! frame-id @sid actor-id)
        (registrar/unregister! :event actor-id)
        @sid))))

(defn- destroy-invoke-all-children!
  "Per rf2-6vmw — the declarative-`:spawn-all` exit-cascade form.
  Resolves the children map from `[:rf/spawned parent-id invoke-id]`,
  tears each child down via `destroy-single-actor!`, then clears the
  join-state slot via the unified teardown projection (slot-prune only:
  nil actor-id)."
  [frame-id parent-id invoke-id]
  (let [join-state (get-in (frame/frame-app-db-value frame-id)
                           [:rf/spawned parent-id invoke-id])
        children   (when (map? join-state) (:children join-state))]
    (doseq [[child-id spawned-id] children]
      ;; rf2-gn80 D6 — `:reason :explicit` discriminates "the parent cascade
      ;; tore the child down" from `:rf.machine/finished` (the auto-destroy
      ;; on `:final?`). Per-child fires omit `:system-id` (the join-state's
      ;; children aren't system-id-bound through the parent's slot).
      (traces/emit-destroyed! {:frame     frame-id
                               :actor-id  spawned-id
                               :parent-id parent-id
                               :spawn-id invoke-id
                               :child-id  child-id})
      (destroy-single-actor! frame-id spawned-id))
    ;; Clear the join-state slot via the unified projection (slot-only).
    (frame/swap-frame-db! frame-id
                          (fn [db]
                            (first (teardown/teardown-actor
                                     db {:parent-id parent-id
                                         :spawn-id invoke-id}))))
    nil))

(defn- destroy-single!
  "Per rf2-t07u — the keyword (legacy/imperative) form and the single-
  `:spawn` (tracked map) form of `:rf.machine/destroy`. Resolves the
  actor-id (keyword direct OR via the `[:rf/spawned ...]` slot), emits
  the `:rf.machine/destroyed` trace, then applies the unified teardown
  projection.

  Per rf2-lbjnz (Mike decision a, aligned with XState convention) —
  destroying an **already-destroyed** actor is a **silent idempotent
  no-op**. The actor's lifecycle has one observable transition
  (Active → Stopped); subsequent destroy attempts emit NO
  `:rf.machine/destroyed` trace, perform NO teardown, and raise NO
  error.

  The liveness probe must distinguish *already-destroyed* (the actor
  was alive, the teardown projection ran, the registrar slot was
  cleared) from *not-yet-materialised-snapshot* (the actor IS alive
  in this drain — spec-less spawn, or spawn + destroy back-to-back
  before the snapshot was even read — but its snapshot was never
  installed at `[:rf/machines actor-id]`). Snapshot-presence alone is
  not the right signal: a spec-less spawn (`:machine-id` resolved to
  no registered spec — SSR / platform-gated) never installs a
  snapshot, yet its destroy still owns legitimate cleanup work
  (spawn-order/forget + the observability trace).

  `live?` is true iff ANY of the following hold:

    - **Handler still registered** at `actor-id` in the event
      registrar. Final-state auto-destroy (finalize.cljc) and prior
      explicit destroys both unregister the handler; a still-
      registered handler reliably means \"not yet destroyed.\"
    - **Snapshot present** at `[:rf/machines actor-id]`. Covers the
      narrow window where a singleton's handler has been replaced
      mid-drain but the snapshot still lives, plus belt-and-braces
      for hand-crafted call sites.
    - **Spawn-order entry present** for `actor-id` in the per-frame
      spawn-order channel. The dedicated liveness signal for spec-
      less spawns whose handler+snapshot were both skipped at spawn
      time. `spawn-order/record!` runs unconditionally on spawn;
      `spawn-order/forget!` runs unconditionally on destroy — so the
      entry's presence/absence is the most reliable
      \"alive-or-gone\" bit for this category.
    - **Tracked-form slot present** at `[:rf/spawned parent-id
      invoke-id]`. Belt-and-braces for the declarative-`:spawn`
      tracked-map form — covers the spec-less spawn case under the
      tracked codepath even when the actor-id resolution above
      went via the slot lookup.

  A truly-already-destroyed actor has ALL FOUR gone — the unified
  teardown projection + `registrar/unregister!` + `spawn-order/forget!`
  run atomically per `destroy-single-actor!` and `finalize-machine`.

  See [Spec 005 §Destroy is silent-idempotent (rf2-lbjnz)] for the
  normative paragraph."
  [frame-id args]
  (let [tracked?  (map? args)
        parent-id (when tracked? (:rf/parent-id args))
        invoke-id (when tracked? (:rf/spawn-id args))
        old-db    (frame/frame-app-db-value frame-id)
        slot-id   (when (and tracked? old-db)
                    (get-in old-db [:rf/spawned parent-id invoke-id]))
        actor-id  (if tracked? slot-id args)
        ;; rf2-lbjnz — silent-idempotent guard. `live?` is true iff ANY
        ;; liveness signal survives (handler registered / snapshot
        ;; present / spawn-order entry / tracked-slot present). See
        ;; docstring for the rationale and what each signal covers.
        live?     (and actor-id
                       (or (some? (registrar/lookup :event actor-id))
                           (and (some? old-db)
                                (contains? (get old-db :rf/machines) actor-id))
                           (some #(= actor-id %)
                                 (spawn-order/frame-order frame-id))
                           (and tracked? (some? slot-id))))]
    (when live?
      (let [released-sid (teardown/find-system-id-for-actor old-db actor-id)]
        ;; rf2-gn80 D6 — `:reason :explicit` discriminates "an action / fx
        ;; tore the actor down" from `:rf.machine/finished` (the auto-destroy
        ;; on `:final?`). Always stamp `:system-id` (nil when not bound) per
        ;; the destroyed-trace-shape contract for the `destroy-single!` site.
        (traces/emit-destroyed! {:frame     frame-id
                                 :actor-id  actor-id
                                 :system-id released-sid
                                 :parent-id parent-id
                                 :spawn-id invoke-id})
        ;; (rf2-nahfm) Run the active configuration's `:exit` cascade
        ;; BEFORE the teardown projection clears the snapshot.
        (exit-cascade/run-child-exit! frame-id actor-id)
        (finalize/abort-actor-in-flight-http! actor-id)
        (frame/swap-frame-db! frame-id
                              (fn [db]
                                (first (teardown/teardown-actor
                                         db {:actor-id  actor-id
                                             :parent-id parent-id
                                             :spawn-id invoke-id}))))
        (traces/emit-system-id-released! frame-id released-sid actor-id)
        ;; Unregister the live handler. Last so any in-flight trace emit
        ;; against the actor still resolves before the slot disappears.
        (registrar/unregister! :event actor-id)
        ;; (rf2-vsigt) Forget the actor from the per-frame spawn-order
        ;; channel so frame destroy's reverse-creation walk doesn't trip
        ;; over a stale entry.
        (spawn-order/forget! frame-id actor-id)))
    nil))

(defn destroy-machine-fx
  "fx handler for `:rf.machine/destroy`. Dispatches to the keyword-form
  / single-`:spawn` teardown (`destroy-single!`) or the
  `:spawn-all` children-iteration teardown
  (`destroy-invoke-all-children!`) per the `args` shape. See the ns
  docstring for the form semantics."
  [{frame-id :frame :or {frame-id :rf/default}} args]
  (let [invoke-all? (and (map? args) (true? (:rf/spawn-all args)))]
    (if invoke-all?
      (destroy-invoke-all-children! frame-id
                                    (:rf/parent-id args)
                                    (:rf/spawn-id args))
      (destroy-single! frame-id args))))
