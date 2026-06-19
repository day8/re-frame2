(ns re-frame.machines.lifecycle-fx.spawn
  "Spawn live-handler wiring: `:rf.machine/spawn` and
  `:rf.machine/spawn-all-init` fx handlers.

  `apply-transition-once` emits `[:rf.machine/spawn args]` into the fx
  vector whenever entry cascades cross a `:spawn`-bearing state. Per
  Spec 005 §Spawning + rf2-a2sn1, a spawned actor's liveness is
  APP-DB-ONLY: `spawn-fx` seeds the actor's initial snapshot at
  `[:rf.runtime/machines :snapshots <spawned-id>]` in the spawning
  frame's app-db, stamping the revertible TYPE reference at
  `:rf/machine-type`. There is NO per-instance event-handler
  registration — the actor's liveness IS that snapshot's presence in the
  (revertible) frame value, and the snapshot's `:rf/machine-type` lets
  the lazy resolver (`lifecycle-fx.resolver`) re-materialise the actor's
  handler on dispatch. Spawn is therefore a pure app-db write; destroy
  removes only app-db, so `restore-epoch!` (app-db-only) reverts an
  actor's liveness perfectly with zero registrar drift (the Goal-2
  revertibility invariant). Frame isolation follows from the snapshot
  living inside the spawning frame's app-db.

  Per rf2-6vmw `spawn-all-init-fx` also lives here — the runtime
  emits `[:rf.machine/spawn-all-init args]` alongside per-child
  `:rf.machine/spawn` fxs on entry to a `:spawn-all`-bearing state to
  seed the join state at `[:rf.runtime/machines :spawned <parent> <invoke-id>]`."
  (:require [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.machines.data-validation :as data-validation]
            [re-frame.machines.lifecycle-fx.registration :as registration]
            [re-frame.machines.lifecycle-fx.resolver :as resolver]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.paths :as paths]
            [re-frame.machines.spawn-order :as spawn-order]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; `spawn-fx` (the fx handler / fail-closed gate) delegates the accepted-spawn
;; body to `spawn-fx*`, defined below it — forward-declared so the gate can
;; reference it (rf2-ywv74m).
(declare spawn-fx*)

;; ---- id allocation --------------------------------------------------------

(defn- pre-allocated-actor-id
  "Resolve the pre-allocated actor id carried on the spawn args. Per Spec
  005 §Declarative :spawn Spec-spec keys: `:fixed-actor-id` is an explicit
  actor-address literal (per-state singleton; rf2-0ggtr5 — was the
  overloaded `:spawn-id`); `:rf/spawned-id` is stamped by the transition
  reducer (rf2-gr8q — allocated from the parent snapshot's
  `:rf/spawn-counter`). Returns nil for hand-emitted
  `[:rf.machine/spawn args]` fxs that bypass the transition reducer —
  the caller (`spawn-fx`) allocates such ids from the frame's app-db
  spawn-counter slot at `[:rf.runtime/machines :spawn-counter]` inside
  the spawn's db-swap so the allocation shares the same write."
  [args]
  (or (:fixed-actor-id args)
      (:rf/spawned-id args)))

(defn- allocate-actor-id-in-runtime-db
  "Hand-emitted-spawn fallback allocator (rf2-gr8q). When the spawn args
  carry no pre-allocated id (no `:fixed-actor-id`, no `:rf/spawned-id`), this
  fn bumps the frame's runtime-db counter at
  `[:rf.runtime/machines :spawn-counter <machine-id>]` and returns
  `[new-runtime-db spawned-id]`. Per rf2-gr8q the global `spawn-counter`
  atom is gone; the allocator lives where the side-effect belongs —
  inside the fx-handler's runtime-db swap — so the pure transition layer
  stays effect-free. Per EP-0001 rf2-vzld77 the counter sits under the
  `:rf.runtime/machines` sub-container of the durable runtime-db partition
  alongside `:snapshots`, `:system-ids`, `:spawned` (Conventions §Reserved
  runtime-db keys)."
  [runtime-db machine-id]
  (let [rt' (update-in runtime-db [:rf.runtime/machines :spawn-counter machine-id] (fnil inc 0))
        n   (get-in rt' [:rf.runtime/machines :spawn-counter machine-id])]
    [rt' (keyword (namespace machine-id)
                  (str (name machine-id) "#" n))]))

(defn- resolve-spawn-machine
  "Resolve the machine spec for a spawn. `:machine-id` references a
  registered machine — read its spec back from the registrar via the
  `:rf/machine` metadata. `:definition` carries an inline spec map.
  Returns the spec map or nil if neither resolves. Per Spec 005
  §Spawn-spec keys.

  Per rf2-ywv74m a nil return from a `:machine-id`-bearing spawn means the
  named TYPE is UNREGISTERED — the fail-closed reject path (see
  `unregistered-spawn-type?` / `reject-unregistered-spawn!`). An inline
  `:definition` always resolves to itself."
  [args]
  (let [machine-id (:machine-id args)
        defn       (:definition args)]
    (cond
      defn        defn
      machine-id  (resolver/spec-from-registry machine-id))))

(defn- unregistered-spawn-type?
  "Per rf2-ywv74m (Mike ruling, 2026-06-15): a `:spawn` / `:spawn-all`
  per-child whose `:machine-id` names an UNREGISTERED machine TYPE — and
  which carries no inline `:definition` — is REJECTED fail-closed. True iff
  the args name a `:machine-id`, carry no `:definition`, and no registered
  machine spec resolves for that id (the implicit \"spec-less spawn\" path,
  now REMOVED as a supported lifecycle). A `:definition` spawn never trips
  this (the spec IS the args)."
  [args]
  (boolean
    (and (:machine-id args)
         (not (:definition args))
         (nil? (resolve-spawn-machine args)))))

(defn- reject-unregistered-spawn!
  "Per rf2-ywv74m — emit the always-on `:rf.error/machine-spawn-unregistered-type`
  and reject the spawn. The implicit \"spec-less spawn\" path (a `:machine-id`
  that resolves to no registered spec) is REMOVED: a rejected spawn installs
  NO snapshot, NO slot, NO system-id binding, allocates NO spawned-id, records
  NO spawn-order entry, fires NO trace, and dispatches NO `:start`.

  ALWAYS-ON (EP-0008 non-event union-record axis, surface #4): the reject is a
  production-reachable fail-closed boundary fact — an off-box shipper on a
  `goog.DEBUG=false` build must still see it — so it rides the always-on
  `:error-emit/dispatch-error-record` late-bind hook (the non-event sibling of
  `dispatch-on-error!`, shared with the frame-teardown report). A dev error
  trace (`trace/emit-error!`, DCE'd in production) ALSO fires for the
  in-process tooling surface — the same always-on-plus-dev-trace shape
  `:rf.error/write-after-destroy` / `:rf.error/on-destroy-handler-exception`
  carry.

  Privacy (rf2-ywv74m correctness call-out): the record carries STRUCTURAL
  context ONLY — `:machine-id`, `:frame`, `:reason`, `:recovery`. The full
  spawn `args` are NEVER carried: `:start` payloads / `:data` may hold
  application data (auth tokens, PII), and this record is production-surviving
  and NOT privacy-gated. `:reason` names the id and the fix without echoing
  any value-bearing slot."
  [frame-id machine-id]
  (let [reason (error/human-message
                 :rf.error/machine-spawn-unregistered-type
                 (str "Cannot spawn machine " machine-id
                      ": no machine TYPE is registered under that :machine-id "
                      "(and the spawn carries no inline :definition). Register the "
                      "machine with rf/reg-machine before spawning it, or supply an "
                      "inline :definition."))]
    ;; Always-on (surface #4): the non-event union record. Structural-only —
    ;; no spawn args. Late-bound: machines ships above core's require graph;
    ;; the hook is bound once `re-frame.error-emit` loads.
    (when-let [dispatch-record! (late-bind/get-fn :error-emit/dispatch-error-record)]
      (dispatch-record! {:error      :rf.error/machine-spawn-unregistered-type
                         :frame      frame-id
                         :machine-id machine-id
                         :recovery   :no-recovery
                         :reason     reason
                         :time       (interop/now-ms)}))
    ;; Dev trace (DCE'd in production) for the in-process tooling surface.
    (trace/emit-error! :rf.error/machine-spawn-unregistered-type
                       {:machine-id machine-id
                        :failing-id machine-id
                        :frame      frame-id
                        :recovery   :no-recovery
                        :reason     reason})
    nil))

(defn- machine-type-ref
  "Per rf2-a2sn1 — the revertible TYPE reference stamped onto a spawned
  actor's snapshot root under `:rf/machine-type`, so the lazy resolver
  (`lifecycle-fx.resolver`) can re-materialise the actor's handler purely
  from app-db. A `:machine-id` spawn stores the registered TYPE keyword
  (the type outlives every instance — registered like a singleton). An
  inline `:definition` spawn stores the spec map verbatim (there is no
  registered type; the snapshot is the only source of truth, and it is
  fully revertible). Per rf2-ywv74m a spawn always names exactly one of the
  two — an unregistered `:machine-id` is rejected fail-closed BEFORE this is
  reached (see `reject-unregistered-spawn!`), so this never returns nil on
  the accepted path."
  [args]
  (or (:machine-id args)
      (:definition args)))

(defn- stamp-framework-data
  "Per rf2-ijm7: stamp framework-reserved keys into the spawned actor's
  initial `:data` so the actor knows its own address (`:rf/self-id`)
  and, for declarative-`:spawn` spawns, its parent's address +
  invoke-id."
  [spec spawned-id parent-id invoke-id]
  (when spec
    (let [base-data (or (:data spec) {})
          data'     (cond-> (assoc base-data :rf/self-id spawned-id)
                      parent-id (assoc :rf/parent-id parent-id)
                      invoke-id (assoc :rf/invoke-id invoke-id))]
      (assoc spec :data data'))))

;; The spawned actor's initial snapshot is built by
;; `parallel/build-initial-snapshot` — the single source of truth shared
;; with the singleton-registration path
;; (`lifecycle-fx.registration/make-machine-handler`); the shared builder
;; seeds `:rf/spawn-counter` and `:meta` consistently across both paths.
;; The spawn path passes `:bootstrap-pending? true` because the actor's
;; first dispatch must fire the initial-entry cascade (rf2-0z73).

(defn- spawn-rejected?
  "Per rf2-jbbp7 + rf2-f3kp7: decide whether a spawn must be rejected by
  schema validation BEFORE any registration or install side effect runs.
  When the spawning machine's spec carries a `:data-schema`, the freshly-built
  `initial-snap`'s `:data` is validated against it. A failure emits
  `:rf.error/schema-validation-failure :where :machine-data :phase :spawn`
  (via `validate-spawn-data!`) and returns `true`; the caller then skips
  the trace, the handler registration, the snapshot/system-id/spawn-slot
  install, the spawn-order record, AND the `:start` dispatch — the
  rejected actor leaves NO half-installed bookkeeping (no registered
  handler, no actor state, no phantom `(rf/machines)` entry).

  Returns `false` for a no-schema / no-validator / conforming spawn."
  [spec spawned-id initial-snap]
  (and (some? spec)
       (not (data-validation/validate-spawn-data!
              spawned-id spec initial-snap))))

(defn- install-spawn!
  "Atomically install the spawned actor's `initial-snap` (with its
  revertible `:rf/machine-type` TYPE reference stamped at the root — per
  rf2-a2sn1), system-id binding, and runtime-owned spawn registry slot
  into the frame's app-db. Returns `:ok`. Emits the collision and
  system-id-bound traces when applicable.

  Per rf2-a2sn1 there is NO per-instance handler registration — the
  actor's liveness IS the presence of this snapshot in the (revertible)
  frame value, and the snapshot's `:rf/machine-type` lets the lazy
  resolver re-materialise the handler on dispatch. Spawn is therefore a
  pure app-db write.

  Per rf2-f3kp7 schema-rejection is decided by the caller (`spawn-fx`)
  via `spawn-rejected?` BEFORE this fn runs, so by the time
  `install-spawn!` is reached the spawn is known-accepted and
  `initial-snap` (built once by the caller) is threaded in rather than
  re-built here.

  `rt-after-alloc` is the post-id-allocation runtime-db computed by the
  caller (see `spawn-fx`); `swap-runtime-db!`'s fn arg is discarded — the
  merge is applied on top of `rt-after-alloc` so the caller's counter bump
  survives. Under Spec 002's single-drainer invariant the discarded
  re-read is value-equal to the snapshot the caller already had. Machine
  snapshots are durable runtime-db state (EP-0001 rf2-vzld77)."
  [frame-id rt-after-alloc spec spawned-id initial-snap
   {:keys [system-id parent-id invoke-id track? type-ref]}]
  (let [existing (when system-id (get-in rt-after-alloc (paths/system-id-path system-id)))
        ;; Per rf2-a2sn1 — stamp the revertible TYPE reference onto the
        ;; snapshot root so the lazy resolver can re-materialise the
        ;; handler from app-db alone. Per rf2-ywv74m the spawn is known-
        ;; accepted by the time `install-spawn!` runs (an unregistered
        ;; `:machine-id` was rejected fail-closed upstream), so `spec` is
        ;; always present; the `spec`/`type-ref` guards are belt-and-braces.
        initial-snap (cond-> initial-snap
                       (and spec type-ref) (assoc :rf/machine-type type-ref))]
    (when (and system-id existing (not= existing spawned-id))
      (trace/emit-error! :rf.error/system-id-collision
                         {:frame             frame-id
                          :system-id         system-id
                          :existing-machine  existing
                          :rebound-to        spawned-id
                          :reason            (str ":system-id " system-id
                                                  " was already bound to "
                                                  existing
                                                  "; rebinding to " spawned-id
                                                  " (last-write-wins).")
                          :recovery          :warned-and-replaced}))
    (frame/swap-runtime-db! frame-id
                            (fn [_rt]
                              (cond-> rt-after-alloc
                                spec      (assoc-in (paths/snapshot-path spawned-id) initial-snap)
                                system-id (assoc-in (paths/system-id-path system-id) spawned-id)
                                track?    (assoc-in (paths/spawned-path parent-id invoke-id) spawned-id))))
    (when system-id
      (trace/emit! :rf.machine :rf.machine/system-id-bound
                   {:frame      frame-id
                    :system-id  system-id
                    ;; rf2-ws5thu — the live actor INSTANCE address (the
                    ;; spawned id), not the registered TYPE; `:machine-id`
                    ;; is reserved for the type.
                    :actor-id   spawned-id}))
    :ok))

;; ---- :rf.machine/spawn -----------------------------------------------------

(defn spawn-fx
  "fx handler for `:rf.machine/spawn`. Per Spec 005 §Spawning, the spawned
  actor's snapshot lives at `[:rf.runtime/machines :snapshots
  <spawned-id>]` in the spawning frame's app-db, and its liveness IS that
  snapshot's presence in the (revertible) frame value — per rf2-a2sn1
  there is NO per-instance event-handler registration.

  Lifecycle wired here:
   0. **Fail-closed gate (rf2-ywv74m).** If `:machine-id` names an
      UNREGISTERED machine TYPE and the spawn carries no inline
      `:definition`, REJECT the spawn: emit the always-on
      `:rf.error/machine-spawn-unregistered-type` and return without
      installing anything — no snapshot, no slot, no system-id, no
      spawned-id allocation, no spawn-order record, no trace, no `:start`
      dispatch. The implicit \"spec-less spawn\" path is REMOVED.
   1. Resolve the spawn's machine spec (`:machine-id` from the registrar
      OR an inline `:definition`).
   2. Initialise the actor's snapshot at `[:rf.runtime/machines
      :snapshots <spawned-id>]` using the spec's `:initial` / `:data`
      (overridden by the spawn args' `:data`), stamping the revertible
      TYPE reference at `:rf/machine-type` (the `:machine-id` keyword, or
      the inline `:definition` map) so the lazy resolver
      (`lifecycle-fx.resolver`) can re-materialise the actor's handler
      from app-db alone. Per rf2-ijm7 the runtime stamps `:rf/self-id`
      (the spawned actor's own address) and, when applicable,
      `:rf/parent-id` + `:rf/invoke-id` into the actor's initial `:data`.
      Re-spawn under the same id replaces — last-write-wins.
   3. If `:system-id` present, bind it in the per-frame
      `[:rf.runtime/machines :system-ids]` reverse index. Collisions emit
      `:rf.error/system-id-collision` and rebind (last-write-wins).
   4. If `:rf/parent-id` + `:rf/invoke-id` present (declarative `:spawn`
      desugar — rf2-t07u Option A revised), bind the spawned id at
      `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]`.
   5. If `:start` event-vector present, dispatch
      `[<spawned-id> <start>]`. When `:start` is absent (per rf2-ijm7),
      the runtime dispatches a synthetic `[<spawned-id>
      [:rf.machine.spawn/spawned]]` so generic child machines may declare a
      leaf-level `:on :rf.machine.spawn/spawned :target ...` transition.
      The first dispatch lazy-resolves the just-installed snapshot into a
      live handler (no registration step).

  Per rf2-a2sn1 — eliminating the per-instance registration is what
  closes the Goal-2 revertibility leak: spawn writes only app-db, destroy
  removes only app-db, so `restore-epoch!` (app-db-only) reverts an
  actor's liveness perfectly with ZERO registrar drift."
  [{frame-id :frame} args]
  (let [;; EP-0002 carried invariant: `:rf.machine/spawn` runs inside an
        ;; event cascade, so the fx context ALWAYS carries the envelope
        ;; frame as `:frame` (the HELD stamp). A nil stamp is an invariant
        ;; failure — surface `:rf.error/no-frame-context`, never repair to
        ;; a synthesised `:rf/default`.
        frame-id   (frame/require-frame-stamp!
                     frame-id :rf.machine/spawn
                     {:where 'rf.machine/spawn :event-id (:system-id args)})]
    ;; Step 0 (rf2-ywv74m) — fail-closed gate. An unregistered `:machine-id`
    ;; (no inline `:definition`) is REJECTED here, BEFORE any id allocation,
    ;; spec resolution, snapshot/slot/system-id install, spawn-order record,
    ;; trace, or `:start` dispatch. The reject emits the always-on
    ;; `:rf.error/machine-spawn-unregistered-type` and returns nil — the
    ;; strongest atomicity (the implicit spec-less spawn path is removed, so
    ;; there is no half-installed bookkeeping the next op could trip over).
    (if (unregistered-spawn-type? args)
      (reject-unregistered-spawn! frame-id (:machine-id args))
      (spawn-fx* frame-id args))))

(defn- spawn-fx*
  "The accepted-spawn body of `spawn-fx` — runs only after the
  `unregistered-spawn-type?` fail-closed gate (rf2-ywv74m) has let the spawn
  through. `frame-id` is the resolved (non-nil-stamped) frame; `args` the
  spawn args. Returns the allocated `spawned-id`."
  [frame-id args]
  (let [;; Per rf2-gr8q: prefer the pre-allocated id (declarative :spawn
        ;; routes through the transition reducer which bumps the parent
        ;; snapshot's `:rf/spawn-counter`). Hand-emitted spawn fxs carry
        ;; no pre-allocated id; the frame's runtime-db spawn-counter slot
        ;; at `[:rf.runtime/machines :spawn-counter]` (rf2-owvvr) serves
        ;; as the fallback allocator, bumped inside the same db-swap as
        ;; the snapshot install / registry bind below.
        pre-id     (pre-allocated-actor-id args)
        spec       (resolve-spawn-machine args)
        spec'      (if (and spec (contains? args :data))
                     (assoc spec :data (:data args))
                     spec)
        ;; Per rf2-a2sn1 — the revertible TYPE reference the lazy
        ;; resolver reads back off the installed snapshot.
        type-ref   (machine-type-ref args)
        system-id  (:system-id args)
        ;; Per rf2-t07u (Option A revised): the runtime tracks each
        ;; declarative-:spawn spawn at [:rf.runtime/machines :spawned <parent-id>
        ;; <invoke-id>] — populated only when the spawn carries both.
        parent-id  (:rf/parent-id args)
        invoke-id  (:rf/invoke-id args)
        track?     (and parent-id invoke-id)
        ;; Resolve the final spawned id: pre-allocated when present;
        ;; else allocate from runtime-db inside the swap below. We pre-read
        ;; the runtime-db once so the trace event and reg-machine* call see
        ;; the same id the snapshot install / registry bind will use. The
        ;; runtime-db swap re-applies the increment to the (potentially-
        ;; newer) runtime-db at write time — for the JVM atom container the
        ;; read is consistent because `frame/swap-runtime-db!` is the only
        ;; writer during fx drain (Spec 002 §Single drainer per frame).
        ;; Machine snapshots are durable runtime-db state (rf2-vzld77).
        old-rt     (frame/frame-runtime-db-value frame-id)
        machine-id-for-alloc (or (:id-prefix args) (:machine-id args))
        [rt-after-alloc spawned-id]
        (cond
          pre-id        [old-rt pre-id]
          (and old-rt machine-id-for-alloc)
                        (allocate-actor-id-in-runtime-db old-rt machine-id-for-alloc)
          :else         [old-rt nil])
        spec''     (stamp-framework-data spec' spawned-id parent-id invoke-id)
        ;; Build the initial snapshot ONCE here so the schema-rejection
        ;; decision can gate every side effect below; `install-spawn!`
        ;; threads the same value rather than re-building it.
        initial-snap (when spec''
                       (parallel/build-initial-snapshot
                         spec'' {:bootstrap-pending? true}))
        ;; Per rf2-f3kp7: decide schema rejection BEFORE the trace and the
        ;; install. Gating both on `(not rejected?)` makes a rejected spawn
        ;; FULLY atomic — it installs no snapshot, records no spawn-order
        ;; entry, dispatches no `:start`, and announces no
        ;; `:rf.machine.spawn/spawned` (only the
        ;; `:rf.error/schema-validation-failure :phase :spawn` that
        ;; `validate-spawn-data!` already emitted). Per rf2-a2sn1 there is
        ;; no longer any per-instance handler registration to gate — a
        ;; rejected spawn simply writes nothing to app-db, so no liveness
        ;; exists for the rejected actor (the strongest form of atomicity:
        ;; an actor's liveness IS its snapshot, and the snapshot was never
        ;; installed).
        rejected?  (spawn-rejected? spec'' spawned-id initial-snap)]
    ;; (rf2-g13nm2 C3) Gate the ENTIRE accepted-spawn cascade — the
    ;; `:rf.machine.spawn/spawned` trace, the snapshot/system-id/spawn-slot
    ;; install, AND the `:start` (or synthetic) dispatch — on BOTH the spawn
    ;; being accepted (`not rejected?`) AND the frame being LIVE (`old-rt`).
    ;; When the frame was destroyed / never existed, `old-rt` is nil and
    ;; `spawned-id` is nil (the `:else` allocator branch above): firing the
    ;; spawned trace and dispatching `[nil <start>]` for an actor that was
    ;; NEVER installed is an atomicity violation (a phantom spawn signal +
    ;; a dispatch into the void). The pre-fix code gated only the install on
    ;; `old-rt`, leaving the trace + dispatch to fire regardless. Now a
    ;; destroyed-frame spawn is a clean no-op — no trace, no install, no
    ;; dispatch — symmetric with the schema-reject path's atomicity.
    (when (and (not rejected?) old-rt)
      (trace/emit! :rf.machine :rf.machine.spawn/spawned
                   {:frame      frame-id
                    ;; `:machine-id` is the spec-time registered TYPE (xor
                    ;; an inline `:definition`); `:spawned-id` is the live
                    ;; instance address; `:invoke-id` (rf2-0ggtr5 — was
                    ;; `:spawn-id`) is the declarative invocation path.
                    :machine-id (:machine-id args)
                    :spawned-id spawned-id
                    :id-prefix  (:id-prefix args)
                    :start      (:start args)
                    :on-spawn   (:on-spawn args)
                    :system-id  system-id
                    :parent-id  parent-id
                    :invoke-id  invoke-id})
      ;; Per rf2-a2sn1 — NO per-instance handler registration. The actor's
      ;; liveness IS its snapshot's presence in the (revertible) frame
      ;; value; the snapshot's `:rf/machine-type` (stamped by
      ;; `install-spawn!`) lets the lazy resolver re-materialise the
      ;; handler on dispatch. Spawn is a pure app-db write.
      ;;
      ;; (2) Initialise the snapshot + (3) bind :system-id + (4) bind the
      ;; runtime-owned spawn registry (atomically under one runtime-db swap
      ;; so observers see consistent state). When the spawned id was
      ;; allocated from the frame's runtime-db (the hand-emitted-spawn
      ;; fallback path), `rt-after-alloc` already carries the bumped counter —
      ;; install the snapshot on top of that.
      (do
        (install-spawn! frame-id rt-after-alloc spec'' spawned-id initial-snap
                        {:system-id system-id
                         :parent-id parent-id
                         :invoke-id invoke-id
                         :track?    track?
                         :type-ref  type-ref})
        ;; Per rf2-fm1cpl — bridge the spawned actor's `:data-schema`
        ;; `:sensitive?` / `:large?` markers into snapshot-egress redaction
        ;; KEYED UNDER THE INSTANCE ID. A spawned actor's
        ;; `:rf.machine/transition` / `:rf.machine/snapshot-updated` trace
        ;; carries `:actor-id` = the INSTANCE id (`<type>#<n>` or the
        ;; explicit `:fixed-actor-id`), and `re-frame.marks/project-machine-tags`
        ;; resolves marks via `(marks-for :event <actor-id>)`. The TYPE's
        ;; `:data-schema` marks (bridged at `reg-machine*` time) key under the
        ;; TYPE id, so an instance-id trace's lookup would MISS and a
        ;; `:sensitive?` `:data` slot would egress RAW. Re-running the SAME
        ;; bridge (`registration/register-data-schema-marks!`) under
        ;; `spawned-id` keys the schema-derived marks under the id the trace
        ;; actually carries — covering both registered-type (`:machine-id`)
        ;; and inline (`:definition`) spawns via the resolved `spec''`'s
        ;; `:data-schema`. The bridge itself rides `interop/debug-enabled?`
        ;; (the egress surface it feeds is gated), so this is dead-elided in
        ;; production builds. Per rf2-qpibk0 the per-instance schema marks land
        ;; in the separate schema-sourced table (unioned at read time), and per
        ;; rf2-egvm4t the matching destroy/finalize/frame-teardown lifecycle
        ;; clears them via `:marks/clear-machine-schema-marks!` so a destroyed
        ;; actor leaves no marks residue and epoch restore/replay re-runs this
        ;; bridge to rehydrate them.
        (registration/register-data-schema-marks! spawned-id spec'')
        ;; Per rf2-vsigt — record the spawned actor in the frame's
        ;; spawn-order channel so frame-destroy can walk in reverse-
        ;; creation order per Spec 005 §Cross-Spec Interactions §1.
        (spawn-order/record! frame-id spawned-id)
        ;; Per rf2-qpuk4 — the REGISTRAR-substrate "instance appeared"
        ;; observation, the symmetric partner of
        ;; `:rf.machine.lifecycle/created` (handler registered) and
        ;; `:rf.machine.lifecycle/destroyed` (handler/snapshot reaped).
        ;; The fx-substrate emit above (`:rf.machine.spawn/spawned`) says
        ;; "the spawn fx ran"; THIS emit says "a spawned actor's snapshot
        ;; landed in the registrar". Spec 009 §Two-axis machine
        ;; observation: tools that just want "did an actor appear?"
        ;; subscribe to the `:rf.machine.lifecycle/*` channel; causal-graph
        ;; builders subscribe to both and disambiguate by the naming axis.
        ;; The `:state` tag carries the actor's initial state so the Xray
        ;; managed-fx INVOKE adapter can render it without re-reading
        ;; app-db (`managed_fx_helpers/machine-invoke-adapter`).
        (trace/emit! :rf.machine.lifecycle/spawned :rf.machine.lifecycle/spawned
                     {:frame      frame-id
                      ;; `:machine-id` = spec-time registered TYPE;
                      ;; `:spawned-id` = live instance address;
                      ;; `:invoke-id` (rf2-0ggtr5) = declarative invocation path.
                      :machine-id (:machine-id args)
                      :spawned-id spawned-id
                      :invoke-id  invoke-id
                      :system-id  system-id
                      :parent-id  parent-id
                      :state      (:state initial-snap)}))
      ;; (6) Fire the :start event into the new actor. Per rf2-ijm7,
      ;; spawns that don't supply :start receive a synthetic
      ;; [:rf.machine.spawn/spawned] so generic child machines can declare their
      ;; first transition out of an :initial state at spec-write time.
      (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
        ;; Per rf2-ejtpd + rf2-1ve9h: stamp `:source :machine-spawn`
        ;; on the actor-bootstrap dispatch so the Epoch panel's
        ;; DISPATCH step labels it "from machine spawn" rather than
        ;; `:unknown` (rf2-hxj0d residual default) or `:fx-dispatch`
        ;; (which would be the value if the spawn fx routed through
        ;; `:dispatch`), and Xray's L2 timeline can prefix the row +
        ;; per-source filter pills can discriminate actor-bootstrap
        ;; cascades. Per rf2-1ve9h (Mike-approved Option A,
        ;; 2026-05-28) the prior parallel `:rf/dispatch-origin
        ;; :internal` axis was collapsed into `:source` —
        ;; `:machine-spawn` is now the single functional-origin
        ;; discriminator (closed-enum per Spec-Schemas
        ;; §`:rf/dispatch-envelope`).
        (let [start (:start args)
              opts  {:frame              frame-id
                     :source             :machine-spawn}]
          (if (some? start)
            (dispatch! [spawned-id start] opts)
            (dispatch! [spawned-id [:rf.machine.spawn/spawned]] opts)))))
    spawned-id))

;; ---- :rf.machine/spawn-all-init -------------------------------------------

(defn spawn-all-init-fx
  "fx handler for `:rf.machine/spawn-all-init` (rf2-6vmw). Per Spec 005
  §Spawn-and-join via `:spawn-all`, on entry to a `:spawn-all`-bearing
  state the runtime emits this fx (alongside per-child `:rf.machine/spawn`
  fxs) to seed the join state at `[:rf.runtime/machines :spawned <parent> <invoke-id>]` in
  the frame's app-db. The seed map shape is:

    {:children {<child-id> <spawned-id>, ...}
     :done      #{}
     :failed    #{}
     :resolved? false
     :spec      <invoke-all-spec>}

  Subsequent `:on-child-done` / `:on-child-error` events arrive at the
  parent's `make-machine-handler` boundary and are intercepted by
  `intercept-spawn-all-event` (in `lifecycle-fx.join`).

  Per rf2-ywv74m (the `:spawn-all` join-hang correctness fix): this fx
  fires FIRST in the entry `:fx` vector — BEFORE the per-child
  `:rf.machine/spawn` fxs. If ANY child in the set names an UNREGISTERED
  machine TYPE (no inline `:definition`), the join is REJECTED here: NO
  join-state is seeded. A never-running spec-less child would otherwise
  never dispatch its `:on-child-done`, blocking an `:all` join FOREVER
  (`join.cljc` `(= n-done n-total)` can never hold). With no seeded
  join-state, a registered sibling's later `:on-child-done` finds no slot
  and falls through to the documented no-op (`join.cljc` \"no runtime-db
  join state seeded yet\"), so the join cannot deadlock. The per-child
  `:rf.machine/spawn` fx for each unregistered child ALSO rejects
  independently in `spawn-fx`."
  [{frame-id :frame} args]
  (let [;; EP-0002 carried invariant — the fx context carries the cascade
        ;; envelope frame; a nil stamp is an invariant failure
        ;; (`:rf.error/no-frame-context`), never a synthesised `:rf/default`.
        frame-id   (frame/require-frame-stamp!
                     frame-id :rf.machine/spawn-all-init
                     {:where 'rf.machine/spawn-all-init
                      :event-id (:rf/parent-id args)})
        parent-id  (:rf/parent-id args)
        invoke-id  (:rf/invoke-id args)
        join-state (:join-state args)
        children   (:children join-state)
        ;; Per rf2-ywv74m — the original child invoke-specs (carry
        ;; `:machine-id` / `:definition`) ride the seeded join-state's
        ;; `:spec`. Detect any unregistered child TYPE up front.
        unregistered (->> (get-in join-state [:spec :children])
                          (filterv unregistered-spawn-type?))]
    (if (seq unregistered)
      ;; Fail-closed: reject the join — seed NO join-state — so the never-
      ;; running spec-less child cannot hang the `:all` join forever. Emit
      ;; one reject per offending child (structural-only tags, per the
      ;; privacy contract). The per-child `:rf.machine/spawn` fx rejects too.
      (do (doseq [child unregistered]
            (reject-unregistered-spawn! frame-id (:machine-id child)))
          nil)
      (do
        ;; Machine spawn-registry state is durable runtime-db state (rf2-vzld77).
        (frame/swap-runtime-db! frame-id assoc-in
                                (paths/spawned-path parent-id invoke-id) join-state)
        (trace/emit! :rf.machine :rf.machine.spawn-all/started
                     {;; rf2-ws5thu — the parent's live actor INSTANCE address;
                      ;; rf2-0ggtr5 — `:invoke-id` is the declarative invocation path.
                      :actor-id   parent-id
                      :invoke-id  invoke-id
                      :child-ids  (set (keys children))
                      :children   children
                      :frame      frame-id})
        nil))))
