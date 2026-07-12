(ns re-frame.machines.lifecycle-fx.destroy
  "Destroy live-handler wiring: `:rf.machine/destroy` fx handler and its
  helpers.

  `apply-transition-once` emits `[:rf.machine/destroy actor-id]` into
  the fx vector whenever exit cascades cross a `:spawn`-bearing state.
  Per Spec 005 §Spawning, destroy clears the actor's runtime-db snapshot at
  `[:rf.runtime/machines :snapshots <id>]` in the spawning
  frame's runtime-db, and (if the actor was system-id-bound) clears the
  `[:rf.runtime/machines :system-ids]` reverse index entry.

  `args` can be either:
    - a keyword `actor-id` — the IMPERATIVE form: an action emits
      `[:rf.machine/destroy actor-id]` directly with the actor id it
      holds. This is first-class current API — re-frame2's spelling of
      XState v5 `stopChild(actorId)` (the imperative
      teardown that sits alongside automatic exit-cascade teardown), OR
    - a map `{:rf/parent-id ... :rf/invoke-id ...}` — the declarative-
      `:spawn` exit-cascade form, where the runtime resolves the actor
      id from `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` in the frame's
      runtime-db.

  Both forms are canonical: the keyword form is the imperative
  entry-point, the map form is what the `:spawn` desugaring emits on state
  exit. They are parallel entry-points, not a paired alternative.

  The map form may also carry `:rf/spawn-all true` —
  the declarative-`:spawn-all` exit-cascade form. The slot at
  `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` holds a join-state map whose
  `:children` sub-map has every spawned child id. The handler iterates
  `:children` and tears each one down, then clears the slot.

  A fourth, INTERNAL map shape — the VERIFIED reap
  `{:rf/reap true :rf/parent-id <p> :rf/invoke-id <i> :rf/child-id <c>}` —
  is what a `:spawn-all` join emits to tear down an ALREADY-TERMINAL
  (completed / failed) child at resolution WITHOUT a contradictory
  cancellation reply (rf2-tj3l6a). It is the ONLY selector of the
  cancellation-suppressing `:rf.machine/join-reaped` destroyed reason, and it
  is AUTHENTICATED against the live join state before being honoured — the
  reason and the reaped actor-id are runtime-derived from durable join state,
  never caller-supplied, so the form cannot be forged at the public
  reserved-fx boundary (rf2-3lyqzu). See `destroy-join-reap!`. Any map that
  matches none of these shapes fails loud with
  `:rf.error/machine-destroy-bad-arg` and performs no teardown.

  The actor-teardown runtime-db dance lives in
  `re-frame.machines.lifecycle-fx.teardown` — one helper, three
  call-sites."
  (:require [re-frame.frame :as frame]
            [re-frame.machines.classification :as classification]
            [re-frame.machines.lifecycle-fx.exit-cascade :as exit-cascade]
            [re-frame.machines.lifecycle-fx.finalize :as finalize]
            [re-frame.machines.lifecycle-fx.resolver :as resolver]
            [re-frame.machines.lifecycle-fx.resource-release :as resource-release]
            [re-frame.machines.lifecycle-fx.teardown :as teardown]
            [re-frame.machines.lifecycle-fx.traces :as traces]
            [re-frame.machines.paths :as paths]
            [re-frame.machines.spawn-order :as spawn-order]
            [re-frame.machines.timer :as timer]
            [re-frame.registrar :as registrar]))

#?(:clj (set! *warn-on-reflection* true))

(defn- actor-live?
  "The silent-idempotent liveness probe, shared by the
  keyword/tracked `destroy-single!` path and the per-actor
  `destroy-single-actor!` path. An actor with a resolved
  `actor-id` is live iff ANY of the following survive:

    - **Registrar entry present** at `actor-id`. Normal spawned actors resolve
      lazily without a per-instance entry, but teardown still recognises and
      clears a stale or externally installed entry.
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
  teardown projection, registrar cleanup, and `spawn-order/forget!`
  run atomically per `destroy-single-actor!` / `destroy-single!` /
  `finalize-machine`. See Spec 005 §Destroy is silent-idempotent
  for the normative paragraph.

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

    1. run the active configuration's `:exit` cascade BEFORE
       any teardown work — fires `:exit`-emitted fx via do-fx and writes
       any `:data` updates back to the (about-to-be-dissoc'd) snapshot;
    2. abort in-flight `:rf.http/managed` requests;
    3. a machine's `[:schemas :data]` schema is validation-only and produces no
       per-instance marks table, so there is no marks-table residue to drop;
    4. cancel armed `:after` timers, one
       `:rf.machine.timer/cancelled :reason :on-destroy` trace per timer;
    5. apply the unified teardown projection (`teardown/teardown-actor`),
       capturing the released `:system-id` via a side channel so we keep a
       single runtime-db read + single write (machine snapshots are durable
       runtime-db state). `teardown-args` selects the slots the
       projection prunes (`{:actor-id …}` for the per-actor form; the
       tracked map adds `:parent-id` / `:invoke-id`);
    6. emit `:rf.machine/destroyed` via the optional `emit-destroyed!-fn`
       (called with the released sid) — `destroy-single!` emits here so the
       trace lands after `:exit`; `destroy-single-actor!`'s
       callers own the emit, so they pass nil;
    7. forget the actor from the per-frame spawn-order channel
       REGARDLESS of whether the runtime-db swap landed — by the time
       frame-destroy runs the container may already be nil but the
       spawn-order entry still needs clearing;
    8. when the swap landed, emit `:rf.machine/system-id-released` and clear
       any registrar entry (normal spawned actors have none);
    9. release the actor's resource leases — fire
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
  ;; Drop this actor's
  ;; per-instance classification declarations from the per-frame elision
  ;; registry — the teardown half of `classification/lower-at-spawn!`. A
  ;; subsystem instance's classification lives and dies with the instance,
  ;; so the absolute snapshot-rooted `:sensitive` / `:large` decls the spawn
  ;; lowered are dissoc'd here (no leak — an emptied axis slot is pruned).
  ;; Resolve the spec BEFORE the teardown projection clears the snapshot, via
  ;; the registered TYPE or the snapshot's `:rf/machine-type` (a `:spawn`
  ;; instance carries no registrar entry). A spec that declared no
  ;; classification is a clean no-op.
  (let [snapshot (get-in (frame/frame-runtime-db-value frame-id)
                         (paths/snapshot-path actor-id))]
    (when-let [spec (resolver/spec-from-id-or-snapshot actor-id snapshot)]
      (classification/drop-at-destroy! frame-id actor-id spec)))
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
    ;; (9) release the actor's resource leases once it is gone, so
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
  the active configuration's `:exit` cascade, apply the
  unified teardown projection (per
  `re-frame.machines.lifecycle-fx.teardown`), abort in-flight
  `:rf.http/managed` requests, emit the
  `:system-id-released` trace, clear any registrar entry, and
  forget the actor from the per-frame spawn-order channel.
  The ordered teardown pipeline is shared with `destroy-single!` via
  `teardown-live-actor!`.

  Used by `destroy-machine-fx` for the keyword-form imperative
  destroy AND iterated for each child in a `:spawn-all` teardown, AND
  by the frame-destroy cascade walker (`frame-destroy.cljc`).

  Per Spec 005 §Declarative `:spawn` §Composition with explicit
  `:entry` / `:exit`: the actor's `:exit` action runs BEFORE the
  teardown clears the snapshot, so `:exit`-time side effects (HTTP
  requests, logs, dispatches) execute against the live snapshot.

  Silent-idempotent guard: an already-destroyed actor
  (all liveness signals gone) is a no-op. Returns `true` iff the actor
  was live and torn down this call, and `nil` (the `when`'s falsey value)
  for the silent no-op — so callers (notably `destroy-spawn-all-children!`)
  can gate their `:rf.machine/destroyed` emit on the truthy live-and-torn-down
  return, preventing a double-destroyed trace for join-cancelled survivors
  the resolution cascade already tore down. Mirrors the `live?` gate
  `destroy-single!` carries."
  [frame-id actor-id]
  (when (actor-live? frame-id actor-id (frame/frame-runtime-db-value frame-id))
    ;; This site does NOT emit `:rf.machine/destroyed` — its callers
    ;; (`destroy-spawn-all-children!`, the frame-destroy walker) own that
    ;; emit, gating it on this fn's truthy return so each actor's destroyed
    ;; trace fires exactly once. So no emit-destroyed callback.
    (teardown-live-actor! frame-id actor-id {:actor-id actor-id} nil)
    ;; Signal to callers (`destroy-spawn-all-children!`) that
    ;; this actor was live and torn down this call, so they emit
    ;; `:rf.machine/destroyed` exactly once. The `when` returns nil for
    ;; an already-destroyed actor (silent no-op).
    true))

(defn- destroy-spawn-all-children!
  "The declarative-`:spawn-all` exit-cascade form.
  Resolves the children map from `[:rf.runtime/machines :spawned parent-id invoke-id]`,
  tears each child down via `destroy-single-actor!`, then clears the
  join-state slot via the unified teardown projection (slot-prune only:
  nil actor-id)."
  [frame-id parent-id invoke-id]
  (let [join-state (get-in (frame/frame-runtime-db-value frame-id)
                           (paths/spawned-path parent-id invoke-id))
        children   (when (map? join-state) (:children join-state))]
    (doseq [[child-id spawned-id] children]
      ;; `destroy-single-actor!` runs the child's `:exit`
      ;; cascade before teardown; we fire `:rf.machine/destroyed` AFTER it
      ;; so the trace lands after `:exit` — the same exit-then-destroyed
      ;; ordering `destroy-single!` and `finalize-machine` use.
      ;;
      ;; Silent-idempotent destroy contract:
      ;; join resolution (join.cljc/build-resolution-fx)
      ;; already tore down surviving children via the guarded
      ;; `destroy-single!` keyword form (one `:destroyed` each) BEFORE the
      ;; parent's exit cascade re-reads the still-uncleared join-state here.
      ;; `destroy-single-actor!` returns falsey for those
      ;; already-destroyed survivors (its liveness guard short-circuits), so
      ;; gating the emit on its return value keeps each survivor's
      ;; `:rf.machine/destroyed` to EXACTLY ONE — no phantom double-destroy.
      ;;
      ;; D6 — `:reason :explicit` discriminates "the parent cascade
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

(defn- destroy-resolved!
  "Shared tail of every `destroy-single!` shape: run the liveness-gated
  ordered teardown for an ALREADY-RESOLVED `actor-id` and emit its
  `:rf.machine/destroyed` trace with `reason`. The keyword / tracked-`:spawn`
  / verified-reap shapes differ ONLY in how they resolve `actor-id` +
  `reason` (+ the trace's `:parent-id` / `:invoke-id`); this is their
  common teardown-and-emit tail.

  Aligned with XState convention, destroying an **already-destroyed** actor
  is a **silent idempotent no-op**: subsequent destroy attempts emit NO
  `:rf.machine/destroyed` trace, perform NO teardown, and raise NO error.
  The liveness probe must distinguish *already-destroyed* (the actor was
  alive and the teardown projection ran) from *not-yet-materialised-snapshot*
  (the actor IS alive in this drain — a spawn + destroy back-to-back in the
  same `:fx` vector, before the snapshot swap landed). `live?` is true iff
  ANY of the following hold:

    - **Registrar entry present** at `actor-id`. Normal spawned actors have no
      per-instance entry, but teardown recognises stale or externally installed
      entries.
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
    - **`slot-live?`** — the tracked-form belt-and-braces liveness hint (the
      `[:rf.runtime/machines :spawned parent-id invoke-id]` slot's presence),
      passed true ONLY by the tracked-`:spawn` shape; false for the keyword
      and reap shapes.

  A truly-already-destroyed actor has ALL of these gone — the unified
  teardown projection, registrar cleanup, and `spawn-order/forget!`
  run atomically per `destroy-single-actor!` and `finalize-machine`.
  See Spec 005 §Destroy is silent-idempotent for the normative paragraph."
  [frame-id actor-id reason parent-id invoke-id old-db slot-live?]
  (when (or (actor-live? frame-id actor-id old-db) slot-live?)
    ;; Shared ordered teardown pipeline (see `teardown-live-actor!`). The
    ;; `:exit` cascade runs BEFORE the `:rf.machine/destroyed` trace:
    ;; per Spec 005 §Declarative `:spawn` §Composition with explicit `:entry`
    ;; / `:exit` (005:2138) the `:exit` action reads the actor's final
    ;; snapshot before the auto-destroy clears it, so a consumer observing the
    ;; db between `:exit` and `:rf.machine/destroyed` sees the live snapshot.
    ;; This mirrors `finalize-machine`'s order (exit cascade → teardown →
    ;; destroyed) so both destroy entry-points share one ordering convention.
    (teardown-live-actor!
      frame-id actor-id
      {:actor-id actor-id :parent-id parent-id :invoke-id invoke-id}
      ;; D6 — `:reason` discriminates "an action / fx tore the actor down"
      ;; (`:explicit`, a cancellation) from `:rf.machine/finished` (the
      ;; auto-destroy on `:final?`) and, for the VERIFIED reap shape,
      ;; `:rf.machine/join-reaped` (post-completion cleanup of an
      ;; already-terminal `:spawn-all` join child — NOT a cancellation, so
      ;; no second-terminal cancelled reply, rf2-tj3l6a). Always stamp
      ;; `:system-id` (nil when not bound) per the destroyed-trace-shape
      ;; contract for the `destroy-single!` site. `released-sid` is the
      ;; binding the teardown projection released — resolved from the reverse
      ;; index BEFORE the dissoc, symmetric with `finalize-machine`.
      (fn [released-sid]
        (traces/emit-destroyed! {:frame     frame-id
                                 :actor-id  actor-id
                                 :system-id released-sid
                                 :parent-id parent-id
                                 :invoke-id invoke-id
                                 :reason    reason}))))
  nil)

(defn- destroy-join-reap!
  "The VERIFIED reap form `{:rf/reap true :rf/parent-id p :rf/invoke-id i
  :rf/child-id c}` — the ONLY way `:rf.machine/join-reaped` (a
  cancellation-SUPPRESSING destroy reason) can be selected (rf2-tj3l6a,
  rf2-3lyqzu).

  A `:spawn-all` join reaps its ALREADY-TERMINAL (completed / failed)
  children at resolution. Their teardown must NOT re-classify as an
  `:explicit` cancellation: a completed / failed child already closed its
  attempt as a join-child completion (`join-child-reply`'s `:completed` /
  `:failed` terminal for the same canonical `[:rf.work/machine spawned-id
  invoke-id generation]`), so a `:cancelled` teardown reply would be a
  SECOND, contradictory terminal for that work-id. `:rf.machine/join-reaped`
  suppresses that second reply; the teardown itself is IDENTICAL to the
  keyword / imperative destroy (snapshot, timers, resource leases,
  spawn-order removal).

  Because the reason changes terminal semantics, the runtime AUTHENTICATES
  the reap against DURABLE join state before honouring it — the fix for the
  forgeable pre-auth `{:rf/actor-id … :rf/reason …}` shape (rf2-3lyqzu). The
  named `:spawn-all` join at `[:spawned p i]` MUST exist, OWN `child-id`,
  and have folded `child-id` into `:done ∪ :failed`. The actor-id is then
  RESOLVED from the live join state (`(get-in join-state [:children
  child-id])`), never carried by the caller — so the reap can neither point
  post-completion teardown at an arbitrary victim NOR suppress the
  cancellation of an in-progress (not-yet-completed) actor. An unverifiable
  reap FAILS LOUD (`:rf.error/machine-destroy-bad-arg`, `:cause
  :unverified-reap`) and performs no teardown.

  Verification against join state is INDEPENDENT of the liveness guard in
  `destroy-resolved!`: the composed final-child case (a child that reached a
  top-level `:final?` state auto-destroys synchronously with
  `:rf.machine/finished`, THEN its queued completion event resolves the
  parent's join) verifies OK — the join still records it as a completed
  child — yet is a silent liveness no-op, so no second destroyed trace fires
  for the already-dead actor."
  [frame-id args old-db]
  (let [parent-id  (:rf/parent-id args)
        invoke-id  (:rf/invoke-id args)
        child-id   (:rf/child-id args)
        join-state (when old-db (get-in old-db (paths/spawned-path parent-id invoke-id)))
        children   (when (map? join-state) (:children join-state))
        actor-id   (get children child-id)
        completed? (and (some? actor-id)
                        (or (contains? (:done   join-state) child-id)
                            (contains? (:failed join-state) child-id)))]
    (if completed?
      ;; Tear down ONLY the child actor — pass NIL parent/invoke so
      ;; `teardown-actor` does NOT prune the `[:spawned parent invoke]` slot,
      ;; which for a `:spawn-all` holds the WHOLE join-state map (every
      ;; child + the frozen `:done` / `:failed` record). That slot is cleared
      ;; later by the exit-cascade `destroy-spawn-all-children!` if the parent
      ;; exits the `:spawn-all` state; the reap must leave the join record
      ;; intact (matching the pre-auth reap, which likewise carried no
      ;; teardown slot keys). `parent-id` / `invoke-id` / `child-id` were used
      ;; ABOVE only to READ + verify the join state.
      (destroy-resolved! frame-id actor-id :rf.machine/join-reaped
                         nil nil old-db false)
      (traces/emit-destroy-bad-arg! frame-id :unverified-reap args))))

(defn- destroy-single!
  "Dispatch the single-actor `:rf.machine/destroy` shapes to the shared
  teardown tail (`destroy-resolved!`), resolving `actor-id` + `reason` per
  shape. See the ns docstring for the form grammar.

    - Keyword (imperative) form `[:rf.machine/destroy actor-id]` — the actor-id
      IS the arg; a live in-progress teardown is ALWAYS an `:explicit`
      cancellation.
    - VERIFIED reap form `{:rf/reap true …}` — the sole selector of
      `:rf.machine/join-reaped`, authenticated against live join state by
      `destroy-join-reap!`.
    - Tracked single-`:spawn` exit-cascade form `{:rf/parent-id p
      :rf/invoke-id i}` — resolve actor-id from the `[:spawned p i]` slot;
      the teardown is `:explicit`.
    - UNKNOWN / malformed map shape — e.g. the pre-auth forgery
      `{:rf/actor-id … :rf/reason …}` — fails loud
      (`:rf.error/machine-destroy-bad-arg`, `:cause :unknown-shape`) with no
      teardown, so an app action can never mint a caller-chosen destroyed
      reason at the public reserved-fx boundary (rf2-3lyqzu).

  (The `:rf/spawn-all` form is routed to `destroy-spawn-all-children!` by
  `destroy-machine-fx` before this fn is reached.)"
  [frame-id args]
  (let [old-db (frame/frame-runtime-db-value frame-id)]
    (cond
      (not (map? args))
      (destroy-resolved! frame-id args :explicit nil nil old-db false)

      (:rf/reap args)
      (destroy-join-reap! frame-id args old-db)

      (and (contains? args :rf/parent-id) (contains? args :rf/invoke-id))
      (let [parent-id (:rf/parent-id args)
            invoke-id (:rf/invoke-id args)
            slot-id   (when old-db (get-in old-db (paths/spawned-path parent-id invoke-id)))]
        (destroy-resolved! frame-id slot-id :explicit parent-id invoke-id old-db
                           (some? slot-id)))

      :else
      (traces/emit-destroy-bad-arg! frame-id :unknown-shape args))))

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
