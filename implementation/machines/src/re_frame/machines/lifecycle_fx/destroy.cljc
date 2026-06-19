(ns re-frame.machines.lifecycle-fx.destroy
  "Destroy live-handler wiring: `:rf.machine/destroy` fx handler and its
  helpers.

  `apply-transition-once` emits `[:rf.machine/destroy actor-id]` into
  the fx vector whenever exit cascades cross a `:spawn`-bearing state.
  Per Spec 005 §Spawning, destroy unregisters the spawned actor's event
  handler, clears its snapshot at `[:rf.runtime/machines :snapshots <id>]` in the spawning
  frame's runtime-db, and (if the actor was system-id-bound) clears the
  `[:rf.runtime/machines :system-ids]` reverse index entry.

  Per rf2-t07u (Option A revised), `args` can be either:
    - a keyword `actor-id` — the IMPERATIVE form: an action emits
      `[:rf.machine/destroy actor-id]` directly with the actor id it
      holds. This is first-class current API — re-frame2's spelling of
      XState v5 `stopChild(actorId)` (the gold-standard imperative
      teardown that sits alongside automatic exit-cascade teardown), OR
    - a map `{:rf/parent-id ... :rf/invoke-id ...}` — the declarative-
      `:spawn` exit-cascade form, where the runtime resolves the actor
      id from `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` in the frame's
      runtime-db.

  Both forms are canonical and current (pre-alpha, no compatibility
  shims): the keyword form is the imperative entry-point, the map form
  is what the `:spawn` desugaring emits on state exit. They are not a
  new-vs-old pair.

  Per rf2-6vmw, the map form may also carry `:rf/spawn-all true` —
  the declarative-`:spawn-all` exit-cascade form. The slot at
  `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` holds a join-state map whose
  `:children` sub-map has every spawned child id. The handler iterates
  `:children` and tears each one down, then clears the slot.

  The actor-teardown runtime-db dance lives in
  `re-frame.machines.lifecycle-fx.teardown` — one helper, three
  call-sites."
  (:require [re-frame.frame :as frame]
            [re-frame.machines.lifecycle-fx.exit-cascade :as exit-cascade]
            [re-frame.machines.lifecycle-fx.finalize :as finalize]
            [re-frame.machines.lifecycle-fx.resource-release :as resource-release]
            [re-frame.machines.lifecycle-fx.teardown :as teardown]
            [re-frame.machines.lifecycle-fx.traces :as traces]
            [re-frame.machines.paths :as paths]
            [re-frame.machines.spawn-order :as spawn-order]
            [re-frame.machines.timer :as timer]
            [re-frame.registrar :as registrar]))

#?(:clj (set! *warn-on-reflection* true))

(defn- actor-live?
  "Per rf2-lbjnz — the silent-idempotent liveness probe, shared by the
  keyword/tracked `destroy-single!` path and the per-actor
  `destroy-single-actor!` path (rf2-ndfjo). An actor with a resolved
  `actor-id` is live iff ANY of the following survive:

    - **Handler still registered** at `actor-id` in the event
      registrar (final-state auto-destroy + prior explicit destroys
      both unregister; a still-registered handler reliably means
      \"not yet destroyed\").
    - **Snapshot present** at `[:rf.runtime/machines :snapshots actor-id]`
      (covers the mid-drain handler-replaced window + hand-crafted call
      sites).
    - **Spawn-order entry present** for `actor-id` in the per-frame
      spawn-order channel. `spawn-order/record!` runs unconditionally on
      spawn and `spawn-order/forget!` unconditionally on destroy, so the
      entry's presence/absence is the most reliable \"alive-or-gone\" bit —
      notably it covers the back-to-back spawn-then-destroy in the same
      `:fx` vector, where the snapshot swap may not yet have landed.

  A truly-already-destroyed actor has all three gone — the unified
  teardown projection + `registrar/unregister!` + `spawn-order/forget!`
  run atomically per `destroy-single-actor!` / `destroy-single!` /
  `finalize-machine`. See [Spec 005 §Destroy is silent-idempotent
  (rf2-lbjnz)] for the normative paragraph.

  The tracked-slot signal `destroy-single!` additionally consults is
  resolved-actor-id agnostic and so stays local to that call site."
  [frame-id actor-id runtime-db]
  (and actor-id
       (or (some? (registrar/lookup :event actor-id))
           (and (some? runtime-db)
                (contains? (get-in runtime-db (paths/snapshot-path)) actor-id))
           (some #(= actor-id %) (spawn-order/frame-order frame-id)))))

(defn- teardown-live-actor!
  "The shared ordered teardown pipeline for a LIVE actor (the caller has
  already confirmed liveness). Both destroy entry-points — the per-actor
  `destroy-single-actor!` and the keyword/tracked `destroy-single!` — run
  this IDENTICAL ordered sequence; they differ only in actor-id resolution
  (done in the caller) and whether they emit the `:rf.machine/destroyed`
  trace (passed as `emit-destroyed!-fn`).

  Ordered steps (Spec 005 §Declarative `:spawn` §Composition with explicit
  `:entry` / `:exit`; §Cancellation cascade D6-D8):

    1. (rf2-nahfm) run the active configuration's `:exit` cascade BEFORE
       any teardown work — fires `:exit`-emitted fx via do-fx and writes
       any `:data` updates back to the (about-to-be-dissoc'd) snapshot;
    2. (rf2-wvkn) abort in-flight `:rf.http/managed` requests;
    3. (rf2-egvm4t) drop the actor's per-instance `:data-schema` marks so
       no marks-table residue survives (symmetric with the snapshot dissoc
       + registrar unregister below);
    4. (rf2-82a0u) cancel armed `:after` timers, one
       `:rf.machine.timer/cancelled :reason :on-destroy` trace per timer;
    5. apply the unified teardown projection (`teardown/teardown-actor`),
       capturing the released `:system-id` via a side channel so we keep a
       single runtime-db read + single write (machine snapshots are durable
       runtime-db state, rf2-vzld77). `teardown-args` selects the slots the
       projection prunes (`{:actor-id …}` for the per-actor form; the
       tracked map adds `:parent-id` / `:invoke-id`);
    6. emit `:rf.machine/destroyed` via the optional `emit-destroyed!-fn`
       (called with the released sid) — `destroy-single!` emits here so the
       trace lands after `:exit` (rf2-iilco); `destroy-single-actor!`'s
       callers own the emit, so they pass nil;
    7. (rf2-vsigt) forget the actor from the per-frame spawn-order channel
       REGARDLESS of whether the runtime-db swap landed — by the time
       frame-destroy runs the container may already be nil but the
       spawn-order entry still needs clearing;
    8. when the swap landed, emit `:rf.machine/system-id-released` and
       unregister the live event handler (last, so an in-flight trace emit
       against the actor still resolves before the slot disappears);
    9. (rf2-xw5t0y) release the actor's resource leases — fire
       `:rf.resource/release-owner` for owner `[:machine actor-id]` (Spec 016
       §Release authority is per owner kind, 016:290) so a resource the actor
       `ensure`d under its machine-owner key does not leak the lease (keep
       refetching/polling) past the actor's death. Fired LAST, once the actor
       is gone; guarded on resources being loaded (machines never depends on
       resources), so a no-resources app is a clean no-op. Runs on BOTH explicit
       destroy (`destroy-single!`) and the frame-destroy cascade
       (`destroy-single-actor!`) since both route through here.

  Returns the `db-swapped?` flag from the teardown projection."
  [frame-id actor-id teardown-args emit-destroyed!-fn]
  (exit-cascade/run-child-exit! frame-id actor-id)
  (finalize/abort-actor-in-flight-http! actor-id)
  (finalize/clear-actor-schema-marks! actor-id)
  (timer/cancel-actor-timers! frame-id actor-id)
  ;; `teardown-actor` returns [new-runtime-db released-sid]; `swap-runtime-db!`
  ;; expects a fn returning the new runtime-db only, so capture the sid via a
  ;; volatile side channel.
  (let [sid         (volatile! nil)
        db-swapped? (frame/swap-runtime-db! frame-id
                                            (fn [runtime-db]
                                              (let [[new-rt released-sid]
                                                    (teardown/teardown-actor runtime-db teardown-args)]
                                                (vreset! sid released-sid)
                                                new-rt)))]
    (when emit-destroyed!-fn
      (emit-destroyed!-fn @sid))
    (spawn-order/forget! frame-id actor-id)
    (when db-swapped?
      (traces/emit-system-id-released! frame-id @sid actor-id)
      (registrar/unregister! :event actor-id))
    ;; (9) rf2-xw5t0y — release the actor's resource leases once it is gone, so
    ;; a `[:machine actor-id]`-owned resource does not outlive the actor and
    ;; keep refetching/polling. Fired regardless of `db-swapped?`: a re-destroy
    ;; whose teardown projection no-op'd may still have a live owner-pinned
    ;; resource entry to release, and the release effect is itself idempotent.
    ;; Guarded on resources being loaded (machines never depends on resources).
    ;; The `:final?`-state auto-destroy path (`finalize-machine`) does NOT route
    ;; through here; it appends the symmetric `resource-release/release-fx-entry`
    ;; to its returned `:fx` instead — together they cover every destroy cause.
    (resource-release/release-actor-resource-leases! frame-id actor-id)
    db-swapped?))

(defn destroy-single-actor!
  "Destroy a single spawned actor against the frame's container: run
  the active configuration's `:exit` cascade (rf2-nahfm), apply the
  unified teardown projection (per
  `re-frame.machines.lifecycle-fx.teardown`), abort in-flight
  `:rf.http/managed` requests (rf2-wvkn), emit the
  `:system-id-released` trace, unregister the live event handler, and
  forget the actor from the per-frame spawn-order channel (rf2-vsigt).
  The ordered teardown pipeline is shared with `destroy-single!` via
  `teardown-live-actor!`.

  Used by `destroy-machine-fx` for the keyword-form imperative
  destroy AND iterated for each child in a `:spawn-all` teardown, AND
  by the frame-destroy cascade walker (`frame-destroy.cljc`).

  Per Spec 005 §Declarative `:spawn` §Composition with explicit
  `:entry` / `:exit`: the actor's `:exit` action runs BEFORE the
  teardown clears the snapshot, so `:exit`-time side effects (HTTP
  requests, logs, dispatches) execute against the live snapshot.

  Per rf2-ndfjo — silent-idempotent guard: an already-destroyed actor
  (all liveness signals gone) is a no-op. Returns `true` iff the actor
  was live and torn down this call, and `nil` (the `when`'s falsey value;
  rf2-ny0yrz CL4 — narrowed from the earlier `false` wording) for the
  silent no-op — so callers (notably `destroy-spawn-all-children!`) can
  gate their `:rf.machine/destroyed` emit on the truthy live-and-torn-down
  return, preventing a double-destroyed trace for join-cancelled survivors
  the resolution cascade already tore down. Mirrors the `live?` gate
  `destroy-single!` carries (rf2-lbjnz)."
  [frame-id actor-id]
  (when (actor-live? frame-id actor-id (frame/frame-runtime-db-value frame-id))
    ;; This site does NOT emit `:rf.machine/destroyed` — its callers
    ;; (`destroy-spawn-all-children!`, the frame-destroy walker) own that
    ;; emit, gating it on this fn's truthy return so each actor's destroyed
    ;; trace fires exactly once (rf2-ndfjo). So no emit-destroyed callback.
    (teardown-live-actor! frame-id actor-id {:actor-id actor-id} nil)
    ;; rf2-ndfjo — signal to callers (`destroy-spawn-all-children!`) that
    ;; this actor was live and torn down this call, so they emit
    ;; `:rf.machine/destroyed` exactly once. The `when` returns nil for
    ;; an already-destroyed actor (silent no-op).
    true))

(defn- destroy-spawn-all-children!
  "Per rf2-6vmw — the declarative-`:spawn-all` exit-cascade form.
  Resolves the children map from `[:rf.runtime/machines :spawned parent-id invoke-id]`,
  tears each child down via `destroy-single-actor!`, then clears the
  join-state slot via the unified teardown projection (slot-prune only:
  nil actor-id)."
  [frame-id parent-id invoke-id]
  (let [join-state (get-in (frame/frame-runtime-db-value frame-id)
                           (paths/spawned-path parent-id invoke-id))
        children   (when (map? join-state) (:children join-state))]
    (doseq [[child-id spawned-id] children]
      ;; (rf2-iilco) `destroy-single-actor!` runs the child's `:exit`
      ;; cascade before teardown; we fire `:rf.machine/destroyed` AFTER it
      ;; so the trace lands after `:exit` — the same exit-then-destroyed
      ;; ordering `destroy-single!` and `finalize-machine` use.
      ;;
      ;; rf2-ndfjo — silent-idempotent destroy contract (rf2-lbjnz):
      ;; `:cancel-on-decision?` join resolution (join.cljc/build-resolution-fx)
      ;; already tore down surviving children via the guarded
      ;; `destroy-single!` keyword form (one `:destroyed` each) BEFORE the
      ;; parent's exit cascade re-reads the still-uncleared join-state here.
      ;; `destroy-single-actor!` now returns falsey for those
      ;; already-destroyed survivors (its liveness guard short-circuits), so
      ;; gating the emit on its return value keeps each survivor's
      ;; `:rf.machine/destroyed` to EXACTLY ONE — no phantom double-destroy.
      ;;
      ;; rf2-gn80 D6 — `:reason :explicit` discriminates "the parent cascade
      ;; tore the child down" from `:rf.machine/finished` (the auto-destroy
      ;; on `:final?`). Per-child fires omit `:system-id` (the join-state's
      ;; children aren't system-id-bound through the parent's slot).
      (when (destroy-single-actor! frame-id spawned-id)
        (traces/emit-destroyed! {:frame     frame-id
                                 :actor-id  spawned-id
                                 :parent-id parent-id
                                 :invoke-id invoke-id
                                 :child-id  child-id})))
    ;; Clear the join-state slot via the unified projection (slot-only).
    (frame/swap-runtime-db! frame-id
                            (fn [runtime-db]
                              (first (teardown/teardown-actor
                                       runtime-db {:parent-id parent-id
                                                   :invoke-id invoke-id}))))
    nil))

(defn- destroy-single!
  "Per rf2-t07u — the keyword (imperative) form and the single-
  `:spawn` (tracked map) form of `:rf.machine/destroy`. Resolves the
  actor-id (keyword direct OR via the `[:rf.runtime/machines :spawned ...]` slot), emits
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
  in this drain — a spawn + destroy back-to-back in the same `:fx`
  vector, before the snapshot swap landed — but its snapshot is not
  yet installed at `[:rf.runtime/machines :snapshots actor-id]`).
  Snapshot-presence alone is not the right signal: the spawn-order
  entry (recorded unconditionally on every spawn) is the reliable
  \"alive\" bit when the snapshot swap is still in flight.

  `live?` is true iff ANY of the following hold:

    - **Handler still registered** at `actor-id` in the event
      registrar. Final-state auto-destroy (finalize.cljc) and prior
      explicit destroys both unregister the handler; a still-
      registered handler reliably means \"not yet destroyed.\"
    - **Snapshot present** at `[:rf.runtime/machines :snapshots actor-id]`. Covers the
      narrow window where a singleton's handler has been replaced
      mid-drain but the snapshot still lives, plus belt-and-braces
      for hand-crafted call sites.
    - **Spawn-order entry present** for `actor-id` in the per-frame
      spawn-order channel. `spawn-order/record!` runs unconditionally
      on spawn; `spawn-order/forget!` runs unconditionally on destroy —
      so the entry's presence/absence is the most reliable
      \"alive-or-gone\" bit, covering the back-to-back spawn-then-destroy
      window before the snapshot swap lands.
    - **Tracked-form slot present** at `[:rf.runtime/machines :spawned parent-id
      invoke-id]`. Belt-and-braces for the declarative-`:spawn`
      tracked-map form — covers the tracked codepath even when the
      actor-id resolution above went via the slot lookup.

  A truly-already-destroyed actor has ALL FOUR gone — the unified
  teardown projection + `registrar/unregister!` + `spawn-order/forget!`
  run atomically per `destroy-single-actor!` and `finalize-machine`.

  See [Spec 005 §Destroy is silent-idempotent (rf2-lbjnz)] for the
  normative paragraph."
  [frame-id args]
  (let [tracked?  (map? args)
        parent-id (when tracked? (:rf/parent-id args))
        invoke-id (when tracked? (:rf/invoke-id args))
        old-db    (frame/frame-runtime-db-value frame-id)
        slot-id   (when (and tracked? old-db)
                    (get-in old-db (paths/spawned-path parent-id invoke-id)))
        actor-id  (if tracked? slot-id args)
        ;; rf2-lbjnz — silent-idempotent guard. `live?` is true iff ANY
        ;; liveness signal survives. The first three (handler registered /
        ;; snapshot present / spawn-order entry) are the resolved-actor-id
        ;; signals shared with `destroy-single-actor!` via `actor-live?`
        ;; (rf2-ndfjo extracted them so both destroy paths apply the
        ;; identical probe). The tracked-slot signal is local to this site —
        ;; belt-and-braces for the declarative-`:spawn` tracked-map form.
        ;; See docstring for what each signal covers.
        live?     (or (actor-live? frame-id actor-id old-db)
                      (and tracked? (some? slot-id)))]
    (when live?
      ;; Shared ordered teardown pipeline (see `teardown-live-actor!`). The
      ;; `:exit` cascade runs BEFORE the `:rf.machine/destroyed` trace
      ;; (rf2-iilco): per Spec 005 §Declarative `:spawn` §Composition with
      ;; explicit `:entry` / `:exit` (005:2138) the `:exit` action reads the
      ;; actor's final snapshot before the auto-destroy clears it, so a
      ;; consumer observing the db between `:exit` and `:rf.machine/destroyed`
      ;; sees the live snapshot. This mirrors `finalize-machine`'s order
      ;; (exit cascade → teardown → destroyed) so both destroy entry-points
      ;; share one ordering convention.
      (teardown-live-actor!
        frame-id actor-id
        {:actor-id actor-id :parent-id parent-id :invoke-id invoke-id}
        ;; rf2-gn80 D6 — `:reason :explicit` discriminates "an action / fx
        ;; tore the actor down" from `:rf.machine/finished` (the auto-destroy
        ;; on `:final?`). Always stamp `:system-id` (nil when not bound) per
        ;; the destroyed-trace-shape contract for the `destroy-single!` site.
        ;; `released-sid` is the binding the teardown projection released —
        ;; resolved from the reverse index BEFORE the dissoc, symmetric with
        ;; `finalize-machine` and with the pre-teardown `old-db` read it
        ;; replaces (the `:system-ids` index is untouched by the exit cascade
        ;; / abort / mark-clear / timer-cancel steps, so the value is equal).
        (fn [released-sid]
          (traces/emit-destroyed! {:frame     frame-id
                                   :actor-id  actor-id
                                   :system-id released-sid
                                   :parent-id parent-id
                                   :invoke-id invoke-id}))))
    nil))

(defn destroy-machine-fx
  "fx handler for `:rf.machine/destroy`. Dispatches to the keyword-form
  / single-`:spawn` teardown (`destroy-single!`) or the
  `:spawn-all` children-iteration teardown
  (`destroy-spawn-all-children!`) per the `args` shape. See the ns
  docstring for the form semantics."
  [{frame-id :frame} args]
  (let [;; EP-0002 carried invariant — the cascade envelope frame is the
        ;; fx-context `:frame`; a nil stamp is an invariant failure
        ;; (`:rf.error/no-frame-context`), never a synthesised `:rf/default`.
        frame-id   (frame/require-frame-stamp!
                     frame-id :rf.machine/destroy
                     {:where 'rf.machine/destroy
                      :event-id (when (map? args) (:rf/parent-id args))})
        spawn-all? (and (map? args) (true? (:rf/spawn-all args)))]
    (if spawn-all?
      (destroy-spawn-all-children! frame-id
                                   (:rf/parent-id args)
                                   (:rf/invoke-id args))
      (destroy-single! frame-id args))))
