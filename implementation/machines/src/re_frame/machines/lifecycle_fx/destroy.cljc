(ns re-frame.machines.lifecycle-fx.destroy
  "Destroy live-handler wiring: `:rf.machine/destroy` fx handler and its
  helpers.

  `apply-transition-once` emits `[:rf.machine/destroy actor-id]` into
  the fx vector whenever exit cascades cross an `:invoke`-bearing state.
  Per Spec 005 §Spawning, destroy unregisters the spawned actor's event
  handler, clears its snapshot at `[:rf/machines <id>]` in the spawning
  frame's app-db, and (if the actor was system-id-bound) clears the
  `[:rf/system-ids]` reverse index entry.

  Per rf2-t07u (Option A revised), `args` can be either:
    - a keyword `actor-id` — the legacy / imperative form (action emits
      `[:rf.machine/destroy actor-id]` directly with the recorded id), OR
    - a map `{:rf/parent-id ... :rf/invoke-id ...}` — the declarative-
      `:invoke` exit-cascade form, where the runtime resolves the actor
      id from `[:rf/spawned <parent-id> <invoke-id>]` in the frame's
      app-db.

  Per rf2-6vmw, the map form may also carry `:rf/invoke-all true` —
  the declarative-`:invoke-all` exit-cascade form. The slot at
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
  destroy AND iterated for each child in an `:invoke-all` teardown, AND
  by the frame-destroy cascade walker (`frame-destroy.cljc`).

  Per Spec 005 §Declarative `:invoke` §Composition with explicit
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
  "Per rf2-6vmw — the declarative-`:invoke-all` exit-cascade form.
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
                               :invoke-id invoke-id
                               :child-id  child-id})
      (destroy-single-actor! frame-id spawned-id))
    ;; Clear the join-state slot via the unified projection (slot-only).
    (frame/swap-frame-db! frame-id
                          (fn [db]
                            (first (teardown/teardown-actor
                                     db {:parent-id parent-id
                                         :invoke-id invoke-id}))))
    nil))

(defn- destroy-single!
  "Per rf2-t07u — the keyword (legacy/imperative) form and the single-
  `:invoke` (tracked map) form of `:rf.machine/destroy`. Resolves the
  actor-id (keyword direct OR via the `[:rf/spawned ...]` slot), emits
  the `:rf.machine/destroyed` trace, then applies the unified teardown
  projection."
  [frame-id args]
  (let [tracked?  (map? args)
        parent-id (when tracked? (:rf/parent-id args))
        invoke-id (when tracked? (:rf/invoke-id args))
        old-db    (frame/frame-app-db-value frame-id)
        actor-id  (if tracked?
                    (when old-db (get-in old-db [:rf/spawned parent-id invoke-id]))
                    args)
        released-sid (teardown/find-system-id-for-actor old-db actor-id)]
    ;; rf2-gn80 D6 — `:reason :explicit` discriminates "an action / fx
    ;; tore the actor down" from `:rf.machine/finished` (the auto-destroy
    ;; on `:final?`). Always stamp `:system-id` (nil when not bound) per
    ;; the destroyed-trace-shape contract for the `destroy-single!` site.
    (traces/emit-destroyed! {:frame     frame-id
                             :actor-id  actor-id
                             :system-id released-sid
                             :parent-id parent-id
                             :invoke-id invoke-id})
    ;; Tracked-form destroy with no resolved actor-id is a benign no-op:
    ;; the spawn slot was already cleared (e.g. by an earlier explicit
    ;; destroy) or the spawn was suppressed (SSR / platform gating).
    (when actor-id
      ;; (rf2-nahfm) Run the active configuration's `:exit` cascade
      ;; BEFORE the teardown projection clears the snapshot.
      (exit-cascade/run-child-exit! frame-id actor-id)
      (finalize/abort-actor-in-flight-http! actor-id)
      (frame/swap-frame-db! frame-id
                            (fn [db]
                              (first (teardown/teardown-actor
                                       db {:actor-id  actor-id
                                           :parent-id parent-id
                                           :invoke-id invoke-id}))))
      (traces/emit-system-id-released! frame-id released-sid actor-id)
      ;; Unregister the live handler. Last so any in-flight trace emit
      ;; against the actor still resolves before the slot disappears.
      (registrar/unregister! :event actor-id)
      ;; (rf2-vsigt) Forget the actor from the per-frame spawn-order
      ;; channel so frame destroy's reverse-creation walk doesn't trip
      ;; over a stale entry.
      (spawn-order/forget! frame-id actor-id))
    nil))

(defn destroy-machine-fx
  "fx handler for `:rf.machine/destroy`. Dispatches to the keyword-form
  / single-`:invoke` teardown (`destroy-single!`) or the
  `:invoke-all` children-iteration teardown
  (`destroy-invoke-all-children!`) per the `args` shape. See the ns
  docstring for the form semantics."
  [{frame-id :frame :or {frame-id :rf/default}} args]
  (let [invoke-all? (and (map? args) (true? (:rf/invoke-all args)))]
    (if invoke-all?
      (destroy-invoke-all-children! frame-id
                                    (:rf/parent-id args)
                                    (:rf/invoke-id args))
      (destroy-single! frame-id args))))
