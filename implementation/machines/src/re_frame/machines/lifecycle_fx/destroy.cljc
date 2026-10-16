(ns re-frame.machines.lifecycle-fx.destroy
  "Destroy live-handler wiring: `:rf.machine/destroy` fx handler and its
  helpers.

  `apply-transition-once` emits `[:rf.machine/destroy actor-id]` into
  the fx vector whenever exit cascades cross a `:spawn`-bearing state.
  Per Spec 005 §Spawning, destroy clears the actor's runtime-db snapshot at
  `[:rf.runtime/machines :snapshots <id>]` in the spawning
  frame's runtime-db.

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
  `:children`, tears down each child the join owns, then clears the slot.

  A `:spawn-all` join needs no teardown shape for a COMPLETED child: under
  the child-completion protocol completion IS finality, so a child that
  folded into `:done` / `:failed` destroyed itself at that moment with
  `:reason :rf.machine/finished` and published its own closed terminal on the
  way out. Only SURVIVORS are torn down at resolution, and their teardown is
  a genuine `:explicit` cancellation.

  The map grammar is a CLOSED discriminated union. PRESENCE of
  the discriminator key (`:rf/spawn-all`) SELECTS that shape —
  never its truthiness — and the selected shape then requires the
  discriminator's value to be exactly `true` plus the exact coordinate
  key-set and types (`:rf/parent-id` keyword, `:rf/invoke-id` path vector,
  `:rf/child-id` keyword). The tracked single-`:spawn` form is admitted only
  for its exact two-key shape, and only when the resolved slot is an actor-id
  KEYWORD — never a `:spawn-all` join-state map. Every malformed, overlapping,
  or unknown shape fails loud with `:rf.error/machine-destroy-bad-arg` and
  performs ZERO mutation — no slot, actor, child, trace, terminal-reply, or
  ownership change. There is no permissive coercion, no truthy alias, and no
  compatibility fallback.

  The actor-teardown runtime-db dance lives in
  `re-frame.machines.lifecycle-fx.teardown` — one helper, three
  call-sites."
  (:require [re-frame.frame :as rf.frame]
            [re-frame.machines.classification :as rf.machines.classification]
            [re-frame.machines.lifecycle-fx.exit-cascade :as rf.machines.lifecycle-fx.exit-cascade]
            [re-frame.machines.lifecycle-fx.finalize :as rf.machines.lifecycle-fx.finalize]
            [re-frame.machines.lifecycle-fx.resolver :as rf.machines.lifecycle-fx.resolver]
            [re-frame.machines.lifecycle-fx.resource-release :as rf.machines.lifecycle-fx.resource-release]
            [re-frame.machines.lifecycle-fx.teardown :as rf.machines.lifecycle-fx.teardown]
            [re-frame.machines.lifecycle-fx.traces :as rf.machines.lifecycle-fx.traces]
            [re-frame.machines.paths :as rf.machines.paths]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.registrar :as rf.registrar]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the destroy owner token ----------------------------------------------
;;
;; The ordinary `:rf.machine/destroy` effect runs inside the destroying event's
;; fx drain, so `*event-owner*` names the frame incarnation that owns the
;; in-flight event. A destroy of that frame requested on this stack waits for
;; the event boundary (Spec 002 §Destroy), so the incarnation stays live through
;; the whole teardown. The durable writes still ride its token, binding each to
;; the container the effect entered against; an eventless caller (the
;; frame-destroy walker) has no token and takes the bare write.

(defn- snapshot-present?
  "True iff `runtime-db` carries a machine snapshot at `actor-id`. A nil
  `runtime-db` (unknown / destroyed frame) carries nothing."
  [runtime-db actor-id]
  (and (some? runtime-db)
       (contains? (get-in runtime-db (rf.machines.paths/snapshot-path)) actor-id)))

(defn- actor-live?
  "The silent-idempotent liveness probe, shared by the
  keyword/tracked destroy paths (`destroy-machine-fx` /
  `destroy-tracked!`) and the per-actor
  `destroy-single-actor!` path. An actor with a resolved
  `actor-id` is live iff EITHER of the following survives:

    - **A NON-MACHINE registrar entry present** at `actor-id`. Normal spawned
      actors resolve lazily without a per-instance entry, but teardown still
      recognises and clears a stale or externally installed PER-INSTANCE entry.
      A DEFINITION-bearing entry (`:rf/machine? true` — what `reg-machine`
      installs) is deliberately NOT a liveness signal: it is a shared load-time
      TYPE whose lifetime is the registrar's, never any one instance's (Spec 005
      §Destroy is silent-idempotent). Without that exclusion a destroyed
      singleton — whose definition survives its teardown — would still read
      live, so a second `[:rf.machine/destroy <id>]` would re-run the whole
      pipeline: phantom `:rf.machine/destroyed` trace plus re-fired resource
      release. Destroying a NEVER-STARTED singleton is a genuine no-op for
      the same reason.
    - **Snapshot present** at `[:rf.runtime/machines :snapshots actor-id]`
      in `runtime-db`.

  `runtime-db` is the frame's runtime-db as the caller read it, and no write
  lands between that read and this probe, so it IS the live value: the
  `:rf.machine/destroy` effect reads it at entry, after every earlier effect
  in the same `:fx` vector has committed (an actor a spawn there installed is
  in it); `reap-tracked-children!` re-reads it per slot; and
  `destroy-single-actor!` reads it as the argument. It is durable state, so
  an in-process runtime-state install (`restore-epoch!`,
  `replace-frame-state!`) that rewinds PAST a spawn leaves the discarded
  actor absent, and destroying it is the silent no-op Spec 005 §Destroy is
  silent-idempotent requires.

  A truly-already-destroyed actor has both signals gone — the unified
  teardown projection and registrar cleanup run atomically per
  `destroy-single-actor!` / `destroy-resolved!` / `finalize-machine`.
  See Spec 005 §Destroy is silent-idempotent for the normative paragraph.

  This is the ONE liveness probe every destroy shape consults.
  The tracked form pairs it with the exact-incarnation
  predicate (`slot-owned-incarnation?`); tracked-slot PRESENCE is
  ownership bookkeeping, never a liveness signal."
  [actor-id runtime-db]
  (and actor-id
       (or (and (some? (rf.registrar/lookup :event actor-id))
                ;; ... but a DEFINITION-bearing entry is not this actor's own —
                ;; it is the shared TYPE, and it outlives every instance.
                (nil? (rf.machines.lifecycle-fx.resolver/spec-from-registry actor-id)))
           (snapshot-present? runtime-db actor-id))))

(declare reap-tracked-children!)

(defn- teardown-live-actor!
  "The shared ordered teardown pipeline for a LIVE actor (the caller has
  already confirmed liveness). Both destroy entry-points — the per-actor
  `destroy-single-actor!` and the keyword/tracked `destroy-resolved!` — run
  this IDENTICAL ordered sequence; they differ only in actor-id resolution
  (done in the caller) and whether they emit the `:rf.machine/destroyed`
  trace (passed as `emit-destroyed!-fn`).

  Ordered steps (Spec 005 §Declarative `:spawn` §Composition with explicit
  `:entry` / `:exit`; §Cancellation cascade D6-D8):

    1. run the active configuration's `:exit` cascade BEFORE
       any teardown work — fires `:exit`-emitted fx via do-fx and writes
       any `:data` updates back to the (about-to-be-dissoc'd) snapshot;
    1b. destroy the children the actor's own `:spawn` / `:spawn-all` slots
       track (`reap-tracked-children!`), after its `:exit` and while its
       snapshot is still present;
    2. abort in-flight `:rf.http/managed` requests;
    3. a machine's `[:schemas :data]` schema is validation-only and produces no
       per-instance marks table, so there is no marks-table residue to drop;
    4. cancel armed `:after` timers, one
       `:rf.machine.timer/cancelled :reason :on-destroy` trace per timer;
    5. apply the unified teardown projection (`rf.machines.lifecycle-fx.teardown/teardown-actor`)
       as a single runtime-db read + single write (machine snapshots are
       durable runtime-db state). `teardown-args` selects the slots the
       projection prunes (`{:actor-id …}` for the per-actor form; the
       tracked map adds `:parent-id` / `:invoke-id`);
    6. emit `:rf.machine/destroyed` via the optional `emit-destroyed!-fn`
       — `destroy-resolved!` emits here so the
       trace lands after `:exit`; `destroy-single-actor!`'s
       callers own the emit, so they pass nil;
    7. when the swap landed, clear any PER-INSTANCE registrar entry (normal
       spawned actors have none) — never a machine DEFINITION;
    8. release the actor's resource owners — fire
       `:rf.resource/release-owner` for owner `[:machine actor-id]` (Spec 016
       §Release authority is per owner kind) so a resource the actor
       `ensure`d under its machine-owner key does not leak the owner (keep
       refetching/polling) past the actor's death. Fired LAST, once the actor
       is gone; guarded on resources being loaded (machines never depends on
       resources), so a no-resources app is a clean no-op. Runs on BOTH explicit
       destroy (`destroy-resolved!`) and the frame-destroy cascade
       (`destroy-single-actor!`) since both route through here.

  Returns the `db-swapped?` flag from the teardown projection.

  The durable writes ride `owner-token` — the token of the incarnation whose
  event entered the destroy effect, or nil for an eventless caller, which takes
  the bare write."
  [frame-id actor-id teardown-args emit-destroyed!-fn owner-token]
  ;; (1) run the active configuration's `:exit` cascade.
  (rf.machines.lifecycle-fx.exit-cascade/run-child-exit!
    frame-id actor-id {:owner-gone? (constantly false) :owner-token owner-token})
  ;; (1b) destroy the children this actor's `:spawn` / `:spawn-all` slots
  ;; track.
  (reap-tracked-children! frame-id actor-id owner-token)
  ;; (2) abort in-flight HTTP through the late-bound
  ;; `:http/abort-on-actor-destroy` hook. Frame-exact: an actor address is
  ;; frame-LOCAL, so the destroying frame is threaded through and a same-named
  ;; actor in a sibling frame keeps its in-flight requests.
  (rf.machines.lifecycle-fx.finalize/abort-actor-in-flight-http! frame-id actor-id)
  ;; (3) Drop this actor's
  ;; per-instance classification declarations from the per-frame elision
  ;; registry — the teardown half of `rf.machines.classification/lower-at-spawn!`. A
  ;; subsystem instance's classification lives and dies with the instance,
  ;; so the absolute snapshot-rooted `:sensitive` / `:large` decls the spawn
  ;; lowered are dissoc'd here (no leak — an emptied axis slot is pruned).
  ;; Resolve the spec BEFORE the teardown projection clears the snapshot, via
  ;; the registered TYPE or the snapshot's `:rf/machine-type` (a `:spawn`
  ;; instance carries no registrar entry). A spec that declared no
  ;; classification is a clean no-op.
  (let [snapshot (get-in (rf.frame/frame-runtime-db-value frame-id)
                         (rf.machines.paths/snapshot-path actor-id))]
    (when-let [spec (rf.machines.lifecycle-fx.resolver/spec-from-id-or-snapshot actor-id snapshot)]
      (rf.machines.classification/drop-at-destroy! frame-id actor-id spec owner-token)))
  ;; (4) cancel armed `:after` timers.
  (rf.machines.timer/cancel-actor-timers! frame-id actor-id)
  ;; (5) apply the unified teardown projection through the EXACT durable write:
  ;; `swap-runtime-db-exact!` binds the write to the owner's own container and
  ;; returns nil once that incarnation is gone; the eventless caller (nil
  ;; token) takes the bare write.
  (let [swap-fn     (fn [runtime-db]
                      (rf.machines.lifecycle-fx.teardown/teardown-actor runtime-db teardown-args))
        new-rt      (if owner-token
                      (rf.frame/swap-runtime-db-exact! frame-id owner-token swap-fn)
                      (rf.frame/swap-runtime-db! frame-id swap-fn))
        db-swapped? (some? new-rt)]
    ;; (6) emit `:rf.machine/destroyed`.
    (when emit-destroyed!-fn
      (emit-destroyed!-fn))
    ;; (7) when the projection landed, clear any registrar entry
    ;; (`:rf.registry/handler-cleared`).
    ;; EXCEPT when the address carries a machine DEFINITION. A
    ;; `reg-machine` registration is a shared load-time TYPE, not this actor's
    ;; own per-instance entry: the registration is the PROGRAM that makes the
    ;; address creatable, the snapshot is the INSTANCE, and teardown ends the
    ;; instance and never the program (Spec 005 §Liveness is derived from
    ;; runtime-db). Clearing it would strand every later spawn of that type
    ;; with `:rf.error/machine-spawn-unregistered-type`, and — for a singleton
    ;; — silently delete an authoring artefact the destroy never named.
    ;; `rf/clear` remains the public spelling for permanent removal. A plain
    ;; `reg-event` handler squatting here carries no `:rf/machine? true`, so
    ;; `spec-from-registry` returns nil and step (7) still clears it — which
    ;; is the stale/externally-installed case this step exists for.
    (when (and db-swapped?
               (nil? (rf.machines.lifecycle-fx.resolver/spec-from-registry actor-id)))
      (rf.registrar/unregister! :event actor-id))
    ;; (8) release the actor's resource owners once it is gone, so
    ;; a `[:machine actor-id]`-owned resource does not outlive the actor and
    ;; keep refetching/polling.
    ;; Guarded on resources being loaded (machines never depends on resources).
    ;; The `:final?`-state auto-destroy path (`finalize-machine`) does NOT route
    ;; through here; it appends the symmetric `rf.machines.lifecycle-fx.resource-release/release-fx-entry`
    ;; to its returned `:fx` instead — together they cover every destroy cause.
    (rf.machines.lifecycle-fx.resource-release/release-actor-resource-owners! frame-id actor-id)
    db-swapped?))

(defn destroy-single-actor!
  "Destroy a single spawned actor against the frame's container: run
  the active configuration's `:exit` cascade, apply the
  unified teardown projection (per
  `re-frame.machines.lifecycle-fx.teardown`), abort in-flight
  `:rf.http/managed` requests, and clear any registrar entry.
  The ordered teardown pipeline is shared with `destroy-resolved!` via
  `teardown-live-actor!`.

  Used by `destroy-machine-fx` for the keyword-form imperative
  destroy AND iterated for each child in a `:spawn-all` teardown, AND
  by the frame-destroy cascade walker (`frame-destroy.cljc`).

  Per Spec 005 §Declarative `:spawn` §Composition with explicit
  `:entry` / `:exit`: the actor's `:exit` action runs BEFORE the
  teardown clears the snapshot, so `:exit`-time side effects (HTTP
  requests, logs, dispatches) execute against the live snapshot.

  Silent-idempotent guard: an already-destroyed actor
  (all liveness signals gone) is a no-op. Returns the teardown's own
  `db-swapped?` commit flag when the actor was live — `true` when the teardown
  projection committed, `false` when the durable write did not land — and `nil`
  (the `when`'s falsey value) for the silent no-op. So callers (notably
  `destroy-spawn-all-children!`) can gate their `:rf.machine/destroyed` emit /
  slot clear / iteration on a GENUINE teardown, preventing a double-destroyed
  trace for join-cancelled survivors the resolution cascade already tore down.
  Mirrors the `live?` gate `destroy-resolved!` carries.

  The 2-arity is the EVENTLESS entry (frame-destroy walker), which takes the
  bare durable writes. The 3-arity carries the destroy effect's `owner-token`
  for the `:spawn-all` children iteration (which runs inside
  `destroy-machine-fx`)."
  ([frame-id actor-id] (destroy-single-actor! frame-id actor-id nil))
  ([frame-id actor-id owner-token]
   (when (actor-live? actor-id (rf.frame/frame-runtime-db-value frame-id))
     ;; This site does NOT emit `:rf.machine/destroyed` — its callers
     ;; (`destroy-spawn-all-children!`, the frame-destroy walker) own that
     ;; emit, gating it on this fn's truthy return so each actor's destroyed
     ;; trace fires exactly once. So no emit-destroyed callback.
     (teardown-live-actor! frame-id actor-id {:actor-id actor-id} nil owner-token))))

(defn- authenticated-join-child
  "Return a private join-child teardown context only when the actor snapshot's
  membership is authenticated against the CURRENT durable join state.

  Every coordinate must agree: parent, invoke path, logical child id, spawned
  actor id, and opaque attempt token. A stale/replaced/forged membership is not
  join authority and returns nil."
  [runtime-db actor-id]
  (let [join-child (get-in runtime-db
                           (conj (rf.machines.paths/snapshot-path actor-id)
                                 :data :rf/join-child))
        parent-id  (:parent-id join-child)
        invoke-id  (:invoke-id join-child)
        child-id   (:child-id join-child)
        join-state (when (and (keyword? parent-id) (vector? invoke-id))
                     (get-in runtime-db
                             (rf.machines.paths/spawned-path parent-id invoke-id)))]
    (when (and (map? join-child)
               (map? join-state)
               (some? child-id)
               (= actor-id (:spawned-id join-child))
               (= actor-id (get-in join-state [:children child-id]))
               (some? (:attempt join-child))
               (= (:attempt join-child) (:rf/attempt join-state)))
      {:join-child join-child
       :join-state join-state
       :parent-id  parent-id
       :invoke-id  invoke-id
       :child-id   child-id})))

(defn- owned-join-child?
  "True iff the actor at `actor-id` is logical child `child-id` of the join at
  `[:spawned parent-id invoke-id]`: its membership authenticates against the
  CURRENT durable join state and attempt (`authenticated-join-child`) and names
  exactly this parent, invoke path and logical child.

  A join's `:children` entry records the address the join spawned a child at,
  never who occupies it: a spawn at a supplied `:fixed-actor-id` replaces the
  child with an actor carrying no membership, or another join's. So an entry
  alone never authorises a teardown."
  [runtime-db actor-id parent-id invoke-id child-id]
  (let [current (authenticated-join-child runtime-db actor-id)]
    (and (some? current)
         (= parent-id (:parent-id current))
         (= invoke-id (:invoke-id current))
         (= child-id  (:child-id current)))))

(defn- prepare-join-child-teardown!
  "Classify one teardown from authenticated runtime state before teardown.

  An authenticated in-progress explicit teardown is a
  cancellation and atomically closes the attempt by adding its logical child
  id to the live join state's private `:cancelled` set BEFORE exit callbacks,
  snapshot removal, or the terminal destroyed trace. Consequently an already
  queued/delayed exact-attempt completion observes a closed attempt and cannot
  fold a contradictory terminal. A new join attempt seeds a new join-state, so
  no tombstone crosses re-entry.

  Returns `{:reason ... :join-child ...}` for the caller's trace, or nil when
  the durable write did not land."
  [frame-id actor-id requested-reason owner-token]
  (let [runtime-db     (rf.frame/frame-runtime-db-value frame-id)
        current        (authenticated-join-child runtime-db actor-id)
        classification (if current
                         (assoc current :reason requested-reason)
                         {:reason requested-reason})
        cancel-current? (and current (= :explicit requested-reason))]
    (if-not cancel-current?
      classification
      (let [prepared (volatile! nil)
            mark-fn  (fn [latest-db]
                       ;; Re-authenticate inside the exact durable write: the
                       ;; decision and tombstone must describe the same live
                       ;; join attempt that is committed.
                       (if-let [latest (authenticated-join-child
                                        latest-db actor-id)]
                         (let [reason requested-reason]
                           (vreset! prepared (assoc latest :reason reason))
                           (if (= :explicit reason)
                             (update-in
                               latest-db
                               (conj (rf.machines.paths/spawned-path
                                       (:parent-id latest)
                                       (:invoke-id latest))
                                     :cancelled)
                               (fnil conj #{})
                               (:child-id latest))
                             latest-db))
                         (do
                           (vreset! prepared {:reason requested-reason})
                           latest-db)))
            written  (if owner-token
                       (rf.frame/swap-runtime-db-exact!
                         frame-id owner-token mark-fn)
                       (rf.frame/swap-runtime-db! frame-id mark-fn))]
        (when (some? written)
          @prepared)))))

(declare destroy-spawn-all-children!*)

(defn- destroy-spawn-all-children!
  "The declarative-`:spawn-all` exit-cascade form.
  Resolves the children map from `[:rf.runtime/machines :spawned parent-id invoke-id]`,
  tears down via `destroy-single-actor!` each child whose current occupant
  is this join's own (`owned-join-child?`), then clears the join-state slot
  via the unified teardown projection (slot-prune only: nil actor-id). A
  replacement occupying a child's address is left live and untraced.

  Slot-shape fence (the mirror of the tracked-form fence): the
  addressed slot must hold a `:spawn-all` JOIN-STATE MAP (or nothing — a
  repeat exit against an already-cleared slot is a silent no-op). A slot
  holding a single-`:spawn` actor-id KEYWORD means the spawn-all form was
  mis-addressed; clearing it would orphan the live tracked child without
  teardown, so the form fails loud (`:cause :slot-shape-mismatch`) and
  mutates nothing."
  [frame-id parent-id invoke-id args owner-token]
  (let [join-state (get-in (rf.frame/frame-runtime-db-value frame-id)
                           (rf.machines.paths/spawned-path parent-id invoke-id))]
    (if-not (or (nil? join-state) (map? join-state))
      (rf.machines.lifecycle-fx.traces/emit-destroy-bad-arg! frame-id :slot-shape-mismatch args)
      (destroy-spawn-all-children!* frame-id parent-id invoke-id join-state owner-token))))

(defn- destroy-spawn-all-children!*
  "The verified body of `destroy-spawn-all-children!` — `join-state` is
  known to be a join-state map (or nil, the repeat-exit no-op). The final
  join-slot clear rides the EXACT durable write."
  [frame-id parent-id invoke-id join-state owner-token]
  (let [children (when (map? join-state) (:children join-state))]
    (doseq [[child-id spawned-id] children]
      ;; `destroy-single-actor!` runs the child's `:exit`
      ;; cascade before teardown; we fire `:rf.machine/destroyed` AFTER it
      ;; so the trace lands after `:exit` — the same exit-then-destroyed
      ;; ordering `destroy-single!` and `finalize-machine` use.
      ;;
      ;; Only an occupant that is this join's own child is torn down
      ;; (`owned-join-child?`). Anything else at the address — a same-id
      ;; replacement the join does not own, or nothing — is left alone and
      ;; untraced; the slot clear below prunes its stale `:children` entry.
      ;;
      ;; Silent-idempotent destroy contract:
      ;; join resolution (join.cljc/build-resolution-fx)
      ;; already tore down surviving children via the guarded
      ;; `destroy-single!` keyword form (one `:destroyed` each) BEFORE the
      ;; parent's exit cascade re-reads the still-uncleared join-state here.
      ;; Those already-destroyed survivors have no snapshot, so their
      ;; membership fails `owned-join-child?` and they are skipped, keeping each
      ;; survivor's `:rf.machine/destroyed` to EXACTLY ONE — no phantom
      ;; double-destroy. The emit is also gated on `destroy-single-actor!`'s
      ;; return, so a child whose teardown write did not land gets none.
      ;;
      ;; A child that completed destroyed itself with
      ;; `:rf.machine/finished` when it folded, so it has no snapshot and
      ;; fails `owned-join-child?`. Every child torn down here is an
      ;; in-progress sibling, and its teardown is an `:explicit` cancellation.
      (when (owned-join-child? (rf.frame/frame-runtime-db-value frame-id)
                               spawned-id parent-id invoke-id child-id)
        (when-let [{:keys [reason join-child]}
                   (prepare-join-child-teardown!
                     frame-id spawned-id :explicit owner-token)]
          (when (destroy-single-actor! frame-id spawned-id owner-token)
            (rf.machines.lifecycle-fx.traces/emit-destroyed!
              {:frame           frame-id
               :actor-id        spawned-id
               :parent-id       parent-id
               :invoke-id       invoke-id
               :work-generation (:work-generation join-child)
               :child-id        child-id
               :reason          reason})))))
    ;; Clear the join-state slot via the unified projection (slot-only),
    ;; routed through the EXACT durable write.
    (let [clear-fn (fn [runtime-db]
                     (rf.machines.lifecycle-fx.teardown/teardown-actor
                       runtime-db {:parent-id parent-id
                                   :invoke-id invoke-id}))]
      (if owner-token
        (rf.frame/swap-runtime-db-exact! frame-id owner-token clear-fn)
        (rf.frame/swap-runtime-db! frame-id clear-fn)))
    nil))

(defn- destroy-resolved!
  "Shared tail of the keyword / tracked-`:spawn` shapes: run the
  liveness-gated ordered teardown for an ALREADY-RESOLVED `actor-id` and emit
  its `:rf.machine/destroyed` trace with `reason`. The two shapes differ ONLY
  in how they resolve `actor-id` + `reason` (+ the trace's `:parent-id` /
  `:invoke-id`, which a keyword-form destroy of a declarative single-`:spawn`
  child reads from the child's own stamps); this is their common
  teardown-and-emit tail.

  Aligned with XState convention, destroying an **already-destroyed** actor
  is a **silent idempotent no-op**: subsequent destroy attempts emit NO
  `:rf.machine/destroyed` trace, perform NO teardown, and raise NO error.
  The liveness probe must distinguish *already-destroyed* (the actor was
  alive and the teardown projection ran) from *alive* — including an actor
  a spawn earlier in the same `:fx` vector has just installed. Liveness is
  the shared `actor-live?` probe — registrar entry / snapshot in `old-db`,
  identical for every shape (a tracked slot naming a dead actor is STALE
  OWNERSHIP BOOKKEEPING, pruned by `destroy-tracked!` without re-running
  lifecycle teardown, never a liveness signal).

  A truly-already-destroyed actor has ALL the probe's signals gone — the
  unified teardown projection and registrar cleanup run atomically per
  `destroy-single-actor!` and `finalize-machine`.
  See Spec 005 §Destroy is silent-idempotent for the normative paragraph.

  `owner-token` is threaded into `teardown-live-actor!` so the durable writes
  ride the effect owner's token. Before teardown,
  `prepare-join-child-teardown!` authenticates the
  child's private membership against the durable live join. Its exact work
  generation feeds the reply identity; a single-`:spawn` child's is the
  `:rf/work-generation` its spawn stamped, read from its `old-db` snapshot,
  so the cancel row closes the same work id the attempt's done or error
  reply would carry. The trace carries the requested
  reason, and an `:explicit` teardown of an authenticated child durably closes
  the attempt before callbacks can release a queued completion."
  [frame-id actor-id requested-reason parent-id invoke-id old-db owner-token]
  (when (actor-live? actor-id old-db)
    (when-let [teardown (prepare-join-child-teardown!
                          frame-id actor-id requested-reason owner-token)]
      (let [reason     (:reason teardown)
            join-child (:join-child teardown)
            child-data (get-in old-db (rf.machines.paths/snapshot-path actor-id :data))
            ;; The keyword form names only the actor. A declarative
            ;; single-`:spawn` child names its spawn in its own
            ;; `:rf/parent-id` / `:rf/invoke-id` stamps, so its trace and
            ;; cancel row carry the coordinates its done or error reply
            ;; carries. The teardown below still prunes only the slots the
            ;; caller named: a keyword destroy leaves the tracked slot to
            ;; the parent's exit (`destroy-tracked!`).
            [spawn-parent-id spawn-invoke-id]
            (if (and (nil? invoke-id) (some? (:rf/invoke-id child-data)))
              [(:rf/parent-id child-data) (:rf/invoke-id child-data)]
              [parent-id invoke-id])
            ;; A single-`:spawn` child has no membership record; the
            ;; `:rf/work-generation` its spawn stamped sets its generation,
            ;; exactly as `finalize-machine` reads it for the attempt's done
            ;; or error reply.
            work-generation (or (:work-generation join-child)
                                (:rf/work-generation child-data))]
      ;; Shared ordered teardown pipeline (see `teardown-live-actor!`). The
      ;; `:exit` cascade runs BEFORE the `:rf.machine/destroyed` trace:
      ;; per Spec 005 §Declarative `:spawn` §Composition with explicit `:entry`
      ;; / `:exit` the `:exit` action reads the actor's final snapshot before
      ;; the auto-destroy clears it, and so do the fx it emits, which run before
      ;; the teardown.
      ;; This mirrors `finalize-machine`'s order (exit cascade → teardown →
      ;; destroyed) so both destroy entry-points share one ordering convention.
      (teardown-live-actor!
        frame-id actor-id
        {:actor-id actor-id :parent-id parent-id :invoke-id invoke-id}
        ;; D6 — `:reason` discriminates "an action / fx tore the actor down"
        ;; (`:explicit`, a cancellation) from `:rf.machine/finished` (the
        ;; auto-destroy on `:final?`).
        (fn []
          (rf.machines.lifecycle-fx.traces/emit-destroyed!
            (cond-> {:frame             frame-id
                     :actor-id          actor-id
                     :parent-id         spawn-parent-id
                     :invoke-id         spawn-invoke-id
                     :work-bearing-path (or spawn-invoke-id (:invoke-id join-child))
                     :reason            reason}
              (some? work-generation)
              (assoc :work-generation work-generation))))
        owner-token))))
  nil)

;; ---- the closed map-form grammar -------------------------------------------
;;
;; Each map shape is admitted by its EXACT key-set — presence of a
;; discriminator key selects the shape (never its truthiness), and the
;; selected shape then requires the discriminator value to be exactly `true`
;; plus exact coordinate types. Anything else fails loud with
;; `:rf.error/machine-destroy-bad-arg` and performs zero mutation. No
;; permissive coercion, no truthy aliases, no compatibility fallback.

(def ^:private spawn-all-form-keys
  "The declarative-`:spawn-all` exit-cascade shape's exact key-set."
  #{:rf/spawn-all :rf/parent-id :rf/invoke-id})

(def ^:private tracked-form-keys
  "The tracked single-`:spawn` exit-cascade shape's exact key-set."
  #{:rf/parent-id :rf/invoke-id})

(defn- join-coordinates-ok?
  "Exact coordinate types shared by every map form: `:rf/parent-id` is an
  actor-id keyword; `:rf/invoke-id` is the declaring-path vector the
  transition reducer stamps (`(vec prefix)`)."
  [args]
  (and (keyword? (:rf/parent-id args))
       (vector?  (:rf/invoke-id args))))

(defn- slot-owned-incarnation?
  "The exact-incarnation half of the tracked form's shared
  liveness/incarnation predicate. True iff the actor at
  `actor-id` is the incarnation THIS `[:spawned parent-id invoke-id]` slot
  owns: its snapshot's `:data` carries the framework-reserved
  `:rf/parent-id` + `:rf/invoke-id` ownership stamps (written by
  `stamp-framework-data` at spawn — the same reserved keys the
  spawn-`:on-error` routing reads) matching the slot coordinates exactly.

  A same-id REPLACEMENT actor spawned through another path (a hand-emitted
  `:fixed-actor-id` re-spawn, a different parent) carries different — or
  no — ownership stamps, so a STALE slot can never destroy it: exact
  incarnation identity is respected. A live actor with no snapshot — live
  only through a per-instance registrar entry at its id — has no stamps to
  contradict the slot, so it counts as owned and the tracked destroy
  proceeds."
  [runtime-db actor-id parent-id invoke-id]
  (let [snap (when runtime-db (get-in runtime-db (rf.machines.paths/snapshot-path actor-id)))]
    (if (map? snap)
      (let [d (:data snap)]
        (and (= parent-id (:rf/parent-id d))
             (= invoke-id (:rf/invoke-id d))))
      true)))

(defn- prune-tracked-slot!
  "Stale ownership-slot cleanup, SEPARATED from actor lifecycle teardown:
  clear the `[:spawned parent-id invoke-id]` slot and the
  parent snapshot's mirroring `[:data :rf/spawned <invoke-id>]` entry via
  the unified projection (slot-only: nil actor-id), WITHOUT re-running exit
  handlers, child cascades, rf.machines.timer/resource cleanup, terminal replies, or a
  `:rf.machine/destroyed` trace — the actor the slot names is either
  already dead for this incarnation (its own destroy ran the full pipeline
  once) or a live replacement this slot does not own.

  Routed through the EXACT durable write when an event owns the
  frame (`owner-token` non-nil), so a container watch that destroys A / publishes
  same-id B mid-write cannot vacate B's slot or bump B's commit epoch. A nil
  token (eventless caller) falls back to the bare write."
  [frame-id parent-id invoke-id owner-token]
  (let [prune-fn (fn [runtime-db]
                   (rf.machines.lifecycle-fx.teardown/teardown-actor
                     runtime-db {:parent-id parent-id
                                 :invoke-id invoke-id}))]
    (if owner-token
      (rf.frame/swap-runtime-db-exact! frame-id owner-token prune-fn)
      (rf.frame/swap-runtime-db! frame-id prune-fn)))
  nil)

(defn- destroy-tracked!
  "The tracked single-`:spawn` exit-cascade form `{:rf/parent-id p
  :rf/invoke-id i}` (already shape-validated by `destroy-machine-fx`).
  Resolves the actor id from the `[:spawned p i]` slot; a live owned
  child's teardown is `:explicit`.

  Slot-shape fence: the tracked form is admitted only when the
  resolved slot is an actor-id KEYWORD. A `:spawn-all` join-state MAP at the
  slot means the tracked form was mis-addressed — consuming it as an actor id
  would clear the join slot and orphan every live child — so it fails loud
  (`:cause :slot-shape-mismatch`) with zero mutation. A nil slot (already
  cleared — a repeat exit) stays a silent no-op.

  Stale-slot fence: slot presence is OWNERSHIP BOOKKEEPING,
  never liveness. An imperative keyword destroy tears the actor down but
  leaves the tracked slot naming the now-dead id (its teardown-args carry
  no parent/invoke); the later declarative exit must therefore split on the
  shared liveness/incarnation predicate (`actor-live?` +
  `slot-owned-incarnation?`):

    - dead for this incarnation → `prune-tracked-slot!` (slot + parent
      mirror only; NO second exit cascade / teardown / destroyed trace —
      Spec 005's silent-idempotent destroy law);
    - live but a same-id REPLACEMENT this slot does not own → prune the
      stale slot only, leaving the replacement untouched;
    - live and owned → the normal exact-incarnation `:explicit` destroy."
  [frame-id args old-db owner-token]
  (let [parent-id (:rf/parent-id args)
        invoke-id (:rf/invoke-id args)
        slot-id   (when old-db (get-in old-db (rf.machines.paths/spawned-path parent-id invoke-id)))]
    (cond
      (nil? slot-id)
      nil

      (not (keyword? slot-id))
      (rf.machines.lifecycle-fx.traces/emit-destroy-bad-arg! frame-id :slot-shape-mismatch args)

      (and (actor-live? slot-id old-db)
           (slot-owned-incarnation? old-db slot-id parent-id invoke-id))
      (destroy-resolved! frame-id slot-id :explicit parent-id invoke-id old-db owner-token)

      :else
      (prune-tracked-slot! frame-id parent-id invoke-id owner-token))))

(defn- reap-tracked-children!
  "Destroy every child that one of `actor-id`'s own `:spawn` / `:spawn-all`
  slots at `[:rf.runtime/machines :spawned actor-id]` tracks — the children
  whose lifetime the author bound to a state of `actor-id`, and which
  therefore end with `actor-id` whatever ends it (Spec 005 §Declarative
  `:spawn`). An actor no slot tracks (a hand-emitted spawn) is untouched.

  Each slot goes through the entry `destroy-machine-fx` dispatches
  `build-destroy-fx`'s map forms to, so a reaped child is treated exactly
  as one its parent's transition exits: a keyword slot through
  `destroy-tracked!` (a live owned child is destroyed `:explicit`; a dead
  or replaced occupant only has its slot pruned), a join map through
  `destroy-spawn-all-children!` (the same split, per child, on the child's
  join membership). Each child's own teardown runs this step
  for its own slots, so nested children end with it.

  Slots are taken deepest invoke-id first, mirroring the leaf-to-root exit
  order, and each is re-read from the live runtime-db because an `:exit`
  or an earlier child's teardown may already have destroyed a child or
  pruned a slot."
  [frame-id actor-id owner-token]
  (let [invoke-ids (->> (get-in (rf.frame/frame-runtime-db-value frame-id)
                                (rf.machines.paths/spawned-path actor-id))
                        keys
                        (sort-by (juxt (comp - count) identity)))]
    (doseq [invoke-id invoke-ids]
      (let [runtime-db (rf.frame/frame-runtime-db-value frame-id)
            slot       (get-in runtime-db (rf.machines.paths/spawned-path actor-id invoke-id))]
        (cond
          (keyword? slot)
          (destroy-tracked! frame-id
                            {:rf/parent-id actor-id :rf/invoke-id invoke-id}
                            runtime-db owner-token)

          (map? slot)
          (destroy-spawn-all-children! frame-id actor-id invoke-id
                                       {:rf/spawn-all  true
                                        :rf/parent-id  actor-id
                                        :rf/invoke-id  invoke-id}
                                       owner-token))))))

(defn destroy-machine-fx
  "fx handler for `:rf.machine/destroy`. Parses the CLOSED destroy grammar
  (see the ns docstring) and dispatches:

    - keyword `actor-id` — the imperative form (`:explicit` cancellation);
    - `{:rf/spawn-all true :rf/parent-id p :rf/invoke-id i}` — the
      `:spawn-all` children-iteration teardown;
    - `{:rf/parent-id p :rf/invoke-id i}` — the tracked single-`:spawn`
      exit-cascade teardown;
    - anything else — `:rf.error/machine-destroy-bad-arg` (`:cause
      :unknown-shape`) with ZERO mutation. Notably the pre-auth forgery
      `{:rf/actor-id … :rf/reason …}`, false-valued / wrong-typed
      discriminators, missing or wrongly-typed coordinates, and overlapping
      discriminator sets."
  [{frame-id :frame} args]
  (let [;; EP-0002 carried invariant — the cascade envelope frame is the
        ;; fx-context `:frame`; a nil stamp is an invariant failure
        ;; (`:rf.error/no-frame-context`), never a synthesised `:rf/default`.
        frame-id (rf.frame/require-frame-stamp!
                   frame-id :rf.machine/destroy
                   {:where 'rf.machine/destroy
                    :event-id (when (map? args) (:rf/parent-id args))})
        old-db   (rf.frame/frame-runtime-db-value frame-id)
        ;; The `:rf.machine/destroy` fx runs inside the destroying event's
        ;; drain, so this names the incarnation that owns the in-flight event;
        ;; every teardown path below threads it so the durable writes bind to
        ;; that incarnation's own container. An eventless call has none and
        ;; takes the bare writes.
        owner-token (rf.frame/current-event-owner-token)]
    (cond
      ;; Imperative form — the actor-id IS the arg; a live in-progress
      ;; teardown is ALWAYS an `:explicit` cancellation.
      (keyword? args)
      (destroy-resolved! frame-id args :explicit nil nil old-db owner-token)

      (map? args)
      (let [shape (set (keys args))]
        (cond
          (contains? args :rf/spawn-all)
          (if (and (= shape spawn-all-form-keys)
                   (true? (:rf/spawn-all args))
                   (join-coordinates-ok? args))
            (destroy-spawn-all-children! frame-id
                                         (:rf/parent-id args)
                                         (:rf/invoke-id args)
                                         args
                                         owner-token)
            (rf.machines.lifecycle-fx.traces/emit-destroy-bad-arg! frame-id :unknown-shape args))

          (= shape tracked-form-keys)
          (if (join-coordinates-ok? args)
            (destroy-tracked! frame-id args old-db owner-token)
            (rf.machines.lifecycle-fx.traces/emit-destroy-bad-arg! frame-id :unknown-shape args))

          :else
          (rf.machines.lifecycle-fx.traces/emit-destroy-bad-arg! frame-id :unknown-shape args)))

      :else
      (rf.machines.lifecycle-fx.traces/emit-destroy-bad-arg! frame-id :unknown-shape args))))

;; ---- occupied-address replacement ------------------------------------------

(defn occupant-actor-live?
  "Is `actor-id` OCCUPIED by a live spawned ACTOR, for the purpose of deciding
  what a spawn arriving there means? True iff the frame's LIVE `runtime-db`
  carries a snapshot there.

  DELIBERATELY NOT `actor-live?`, and that difference is the whole of this
  predicate. `actor-live?` is the shared silent-idempotent DESTROY probe,
  and it also counts a bare non-machine registrar entry because
  `[:rf.machine/destroy <id>]` must still recognise and clear a stale or
  externally installed one. That disjunct answers \"is there anything here to
  clean up?\" — the right question for destroy and the WRONG one here, where the
  question is \"is a live ACTOR occupying this address?\". A plain event handler
  registered at the address has an `:event` entry and no actor at all, so
  `actor-live?` would report the address occupied and a spawn at that
  `:fixed-actor-id` would tear the handler down as if it were an occupant.
  Spec 005 §Liveness is derived from runtime-db makes a spawned
  actor's liveness identical to its snapshot's presence and nothing else, which
  is exactly this predicate.

  TWO CALLERS, ONE PROBE, OPPOSITE VERDICTS. Occupancy at a SUPPLIED
  `:fixed-actor-id` means REPLACE — `destroy-occupant-for-replacement!` below.
  Occupancy at a GENERATED `<type>#<n>` address means REJECT: nobody named that
  address, so nobody asked for a replacement, and the runtime allocated it only
  because its counter did not know the occupant existed. That is
  `lifecycle-fx.spawn/generated-address-collision?`. The predicate is shared
  deliberately — the two paths must never disagree about what \"occupied\"
  means — and it is PUBLIC for that second caller alone."
  [frame-id actor-id]
  (snapshot-present? (rf.frame/frame-runtime-db-value frame-id) actor-id))

(defn destroy-occupant-for-replacement!
  "Tear a LIVE occupant of `actor-id` down through the ORDINARY destroy path so
  a `:rf.machine/spawn` arriving at an OCCUPIED `:fixed-actor-id` REPLACES it
  cleanly instead of overwriting its snapshot. Called by
  `lifecycle-fx.spawn/spawn-fx*` immediately before its install; see Spec 005
  §`:spawn` map keys §Spawning onto an OCCUPIED fixed address.

  WHY IT EXISTS, AND WHY IT DELEGATES rather than reaching for the pipeline
  directly. Because a spawned actor's liveness IS its snapshot's presence
  (Spec 005 §Liveness is derived from runtime-db), a bare `assoc-in` at the
  snapshot path would be simultaneously the new actor's
  BIRTH and the occupant's unannounced DEATH — none of the eight ordered
  teardown steps would run and all three of Spec 005 §What auto-cancels on
  destroy's named guarantees would be silently void (the occupant's authored
  `:exit` would never fire, its armed `:after` timers would stay armed on the
  host clock, its in-flight `:rf.http/managed` requests would keep flying, its
  `[:machine <actor-id>]` resource owners would keep polling). Routing through `destroy-machine-fx`'s KEYWORD branch —
  rather than calling the private `teardown-live-actor!` — is deliberate: the
  keyword form is what `[:rf.machine/destroy <actor-id>]` itself takes, so the
  replacement gets `prepare-join-child-teardown!`'s join preparation, the event
  owner's token, and the `:rf.machine/destroyed` emit for free, and it cannot
  drift from the ordinary path because it IS the ordinary path.

  `:reason` is `:explicit`, never a bespoke enum member: both
  `prepare-join-child-teardown!`'s live-join-child cancellation (its
  `cancel-current?` gate) and `traces/emit-destroyed!`'s cancelled-reply facts
  (its `cancelled?` gate) test for exactly `:explicit`, so a bespoke reason
  would SKIP both and leave a replaced join child's attempt uncancelled with its
  reply facts unemitted.

  OCCUPANCY IS `occupant-actor-live?`, NEVER `actor-live?`. Only a live spawned
  ACTOR is an occupant; an uninstantiated registered TYPE or plain event handler
  sitting at the same keyword is not — see that predicate for why the shared
  destroy probe cannot answer this question.

  AND THE DEFINITION SURVIVES. When the address IS a
  registered machine's own registration key, a registrar cleanup there
  would delete that shared DEFINITION, stranding the replacement (whose snapshot
  resolves its handler back through exactly that key) and every sibling actor of
  the type with it. `teardown-live-actor!` step (7) preserves EVERY
  definition-bearing address on EVERY destroy path, so this call site needs no
  exception of its own. Preservation costs no re-registration: a re-registration
  would fire the hot-reload hooks and a `:rf.registry/handler-cleared` +
  `:rf.registry/handler-registered` pair for a definition that never changed, and
  would open a fresh callback-bearing boundary between the teardown and the
  install.

  Returns `true` when the address was occupied and the teardown ran, `false`
  when it was EMPTY. False is the overwhelmingly common answer — ordinary
  re-entry at a `:fixed-actor-id` arrives after the exit cascade has already
  destroyed the previous incarnation — and one snapshot read is its whole cost.
  A `true` tells the caller its pre-teardown `runtime-db` snapshot is STALE and
  the install must be rebuilt from the post-teardown value."
  [frame-id actor-id]
  (boolean
    (when (and actor-id (occupant-actor-live? frame-id actor-id))
      (destroy-machine-fx {:frame frame-id} actor-id)
      true)))
