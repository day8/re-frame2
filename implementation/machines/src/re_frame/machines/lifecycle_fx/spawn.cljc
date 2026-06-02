(ns re-frame.machines.lifecycle-fx.spawn
  "Spawn live-handler wiring: `:rf.machine/spawn` and
  `:rf.machine/spawn-all-init` fx handlers.

  `apply-transition-once` emits `[:rf.machine/spawn args]` into the fx
  vector whenever entry cascades cross a `:spawn`-bearing state. Per
  Spec 005 §Spawning, the spawned actor is itself an event handler
  whose id is the actor address; `spawn-fx` registers the live handler
  under the spawned id and seeds its initial snapshot at
  `[:rf/runtime :machines :snapshots <id>]`.

  The two-tier registry described in Spec 005 (frame-local handlers
  that revert with the frame's snapshot) is not yet built — for v1 the
  registration goes through the global registrar via
  `events/reg-event-fx`. Frame isolation is preserved by the snapshot
  living at `[:rf/runtime :machines :snapshots <id>]` inside the spawning frame's app-db.

  Per rf2-6vmw `spawn-all-init-fx` also lives here — the runtime
  emits `[:rf.machine/spawn-all-init args]` alongside per-child
  `:rf.machine/spawn` fxs on entry to a `:spawn-all`-bearing state to
  seed the join state at `[:rf/runtime :machines :spawned <parent> <invoke-id>]`."
  (:require [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.machines.data-validation :as data-validation]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.paths :as paths]
            [re-frame.machines.spawn-order :as spawn-order]
            [re-frame.registrar :as registrar]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- id allocation --------------------------------------------------------

(defn- pre-allocated-actor-id
  "Resolve the pre-allocated actor id carried on the spawn args. Per Spec
  005 §Declarative :spawn Spec-spec keys: `:spawn-id` is an explicit
  literal (per-state singleton); `:rf/spawned-id` is stamped by the
  transition reducer (rf2-gr8q — allocated from the parent snapshot's
  `:rf/spawn-counter`). Returns nil for hand-emitted
  `[:rf.machine/spawn args]` fxs that bypass the transition reducer —
  the caller (`spawn-fx`) allocates such ids from the frame's app-db
  spawn-counter slot at `[:rf/runtime :machines :spawn-counter]` inside
  the spawn's db-swap so the allocation shares the same write."
  [args]
  (or (:spawn-id args)
      (:rf/spawned-id args)))

(defn- allocate-actor-id-in-db
  "Hand-emitted-spawn fallback allocator (rf2-gr8q). When the spawn args
  carry no pre-allocated id (no `:spawn-id`, no `:rf/spawned-id`), this
  fn bumps the frame's app-db counter at
  `[:rf/runtime :machines :spawn-counter <machine-id>]` and returns
  `[new-db spawned-id]`. Per rf2-gr8q the global `spawn-counter` atom
  is gone; the allocator lives where the side-effect belongs — inside
  the fx-handler's app-db swap — so the pure transition layer stays
  effect-free. Per rf2-owvvr the counter sits under the
  `:rf/runtime :machines` sub-container alongside `:snapshots`,
  `:system-ids`, `:spawned` so the single-reserved-root contract per
  Conventions §Reserved app-db keys holds."
  [db machine-id]
  (let [db' (update-in db [:rf/runtime :machines :spawn-counter machine-id] (fnil inc 0))
        n   (get-in db' [:rf/runtime :machines :spawn-counter machine-id])]
    [db' (keyword (namespace machine-id)
                  (str (name machine-id) "#" n))]))

(defn- resolve-spawn-machine
  "Resolve the machine spec for a spawn. `:machine-id` references a
  registered machine — read its spec back from the registrar via the
  `:rf/machine` metadata. `:definition` carries an inline spec map.
  Returns the spec map or nil if neither resolves. Per Spec 005
  §Spawn-spec keys."
  [args]
  (let [machine-id (:machine-id args)
        defn       (:definition args)]
    (cond
      defn        defn
      machine-id  (let [m (registrar/lookup :event machine-id)]
                    (when (:rf/machine? m)
                      (:rf/machine m))))))

(defn- machine-type-ref
  "Per rf2-a2sn1 — the revertible TYPE reference stamped onto a spawned
  actor's snapshot root under `:rf/machine-type`, so the lazy resolver
  (`lifecycle-fx.resolver`) can re-materialise the actor's handler purely
  from app-db. A `:machine-id` spawn stores the registered TYPE keyword
  (the type outlives every instance — registered like a singleton). An
  inline `:definition` spawn stores the spec map verbatim (there is no
  registered type; the snapshot is the only source of truth, and it is
  fully revertible). Returns nil when the spawn names neither — a
  spec-less spawn installs no snapshot, so there is nothing to resolve."
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
                      invoke-id (assoc :rf/spawn-id invoke-id))]
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
  When the spawning machine's spec carries a `:schema`, the freshly-built
  `initial-snap`'s `:data` is validated against it. A failure emits
  `:rf.error/schema-validation-failure :where :machine-data :phase :spawn`
  (via `validate-spawn-data!`) and returns `true`; the caller then skips
  the trace, the handler registration, the snapshot/system-id/spawn-slot
  install, the spawn-order record, AND the `:start` dispatch — the
  rejected actor leaves NO half-installed bookkeeping (no registered
  handler, no actor state, no phantom `(rf/machines)` entry).

  Returns `false` for a no-schema / no-validator / conforming spawn (and
  for a spec-less spawn — nothing to validate)."
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

  `db-after-alloc` is the post-id-allocation db computed by the caller
  (see `spawn-fx`); `swap-frame-db!`'s fn arg is discarded — the merge
  is applied on top of `db-after-alloc` so the caller's counter bump
  survives. Under Spec 002's single-drainer invariant the discarded
  re-read is value-equal to the snapshot the caller already had."
  [frame-id db-after-alloc spec spawned-id initial-snap
   {:keys [system-id parent-id track? type-ref] invoke-id :spawn-id}]
  (let [existing (when system-id (get-in db-after-alloc (paths/system-id-path system-id)))
        ;; Per rf2-a2sn1 — stamp the revertible TYPE reference onto the
        ;; snapshot root so the lazy resolver can re-materialise the
        ;; handler from app-db alone. Only when a spec landed (a
        ;; spec-less spawn installs no snapshot).
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
    (frame/swap-frame-db! frame-id
                          (fn [_db]
                            (cond-> db-after-alloc
                              spec      (assoc-in (paths/snapshot-path spawned-id) initial-snap)
                              system-id (assoc-in (paths/system-id-path system-id) spawned-id)
                              track?    (assoc-in (paths/spawned-path parent-id invoke-id) spawned-id))))
    (when system-id
      (trace/emit! :rf.machine :rf.machine/system-id-bound
                   {:frame      frame-id
                    :system-id  system-id
                    :machine-id spawned-id}))
    :ok))

;; ---- :rf.machine/spawn -----------------------------------------------------

(defn spawn-fx
  "fx handler for `:rf.machine/spawn`. Per Spec 005 §Spawning, the spawned
  actor's snapshot lives at `[:rf/runtime :machines :snapshots
  <spawned-id>]` in the spawning frame's app-db, and its liveness IS that
  snapshot's presence in the (revertible) frame value — per rf2-a2sn1
  there is NO per-instance event-handler registration.

  Lifecycle wired here:
   1. Resolve the spawn's machine spec (`:machine-id` from the registrar
      OR an inline `:definition`).
   2. Initialise the actor's snapshot at `[:rf/runtime :machines
      :snapshots <spawned-id>]` using the spec's `:initial` / `:data`
      (overridden by the spawn args' `:data`), stamping the revertible
      TYPE reference at `:rf/machine-type` (the `:machine-id` keyword, or
      the inline `:definition` map) so the lazy resolver
      (`lifecycle-fx.resolver`) can re-materialise the actor's handler
      from app-db alone. Per rf2-ijm7 the runtime stamps `:rf/self-id`
      (the spawned actor's own address) and, when applicable,
      `:rf/parent-id` + `:rf/spawn-id` into the actor's initial `:data`.
      Re-spawn under the same id replaces — last-write-wins.
   3. If `:system-id` present, bind it in the per-frame
      `[:rf/runtime :machines :system-ids]` reverse index. Collisions emit
      `:rf.error/system-id-collision` and rebind (last-write-wins).
   4. If `:rf/parent-id` + `:rf/spawn-id` present (declarative `:spawn`
      desugar — rf2-t07u Option A revised), bind the spawned id at
      `[:rf/runtime :machines :spawned <parent-id> <invoke-id>]`.
   5. If `:start` event-vector present, dispatch
      `[<spawned-id> <start>]`. When `:start` is absent (per rf2-ijm7),
      the runtime dispatches a synthetic `[<spawned-id>
      [:rf.machine.spawn/spawned]]` so generic child machines may declare a
      leaf-level `:on :rf.machine.spawn/spawned :target ...` transition.
      The first dispatch lazy-resolves the just-installed snapshot into a
      live handler (no registration step).

  Per rf2-a2sn1 — eliminating the per-instance registration is what
  closes the Goal-2 revertibility leak: spawn writes only app-db, destroy
  removes only app-db, so `restore-epoch` (app-db-only) reverts an
  actor's liveness perfectly with ZERO registrar drift."
  [{frame-id :frame :or {frame-id :rf/default}} args]
  (let [;; Per rf2-gr8q: prefer the pre-allocated id (declarative :spawn
        ;; routes through the transition reducer which bumps the parent
        ;; snapshot's `:rf/spawn-counter`). Hand-emitted spawn fxs carry
        ;; no pre-allocated id; the frame's app-db spawn-counter slot
        ;; at `[:rf/runtime :machines :spawn-counter]` (rf2-owvvr) serves
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
        ;; declarative-:spawn spawn at [:rf/runtime :machines :spawned <parent-id>
        ;; <invoke-id>] — populated only when the spawn carries both.
        parent-id  (:rf/parent-id args)
        invoke-id  (:rf/spawn-id args)
        track?     (and parent-id invoke-id)
        ;; Resolve the final spawned id: pre-allocated when present;
        ;; else allocate from app-db inside the swap below. We pre-read
        ;; the db once so the trace event and reg-machine* call see the
        ;; same id the snapshot install / registry bind will use. The
        ;; db-swap re-applies the increment to the (potentially-newer)
        ;; db at write time — for the JVM atom container the read is
        ;; consistent because `frame/swap-frame-db!` is the only writer
        ;; during fx drain (Spec 002 §Single drainer per frame).
        old-db     (frame/frame-app-db-value frame-id)
        machine-id-for-alloc (or (:id-prefix args) (:machine-id args))
        [db-after-alloc spawned-id]
        (cond
          pre-id        [old-db pre-id]
          (and old-db machine-id-for-alloc)
                        (allocate-actor-id-in-db old-db machine-id-for-alloc)
          :else         [old-db nil])
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
    (when-not rejected?
      (trace/emit! :rf.machine :rf.machine.spawn/spawned
                   {:frame      frame-id
                    :machine-id (:machine-id args)
                    :spawned-id spawned-id
                    :id-prefix  (:id-prefix args)
                    :start      (:start args)
                    :on-spawn   (:on-spawn args)
                    :system-id  system-id
                    :parent-id  parent-id
                    :spawn-id  invoke-id})
      ;; Per rf2-a2sn1 — NO per-instance handler registration. The actor's
      ;; liveness IS its snapshot's presence in the (revertible) frame
      ;; value; the snapshot's `:rf/machine-type` (stamped by
      ;; `install-spawn!`) lets the lazy resolver re-materialise the
      ;; handler on dispatch. Spawn is a pure app-db write.
      ;;
      ;; (2) Initialise the snapshot + (3) bind :system-id + (4) bind the
      ;; runtime-owned spawn registry (atomically under one app-db swap so
      ;; observers see consistent state). When the spawned id was allocated
      ;; from the frame's app-db (the hand-emitted-spawn fallback path),
      ;; `db-after-alloc` already carries the bumped counter — install the
      ;; snapshot on top of that.
      (when old-db
        (install-spawn! frame-id db-after-alloc spec'' spawned-id initial-snap
                        {:system-id system-id
                         :parent-id parent-id
                         :spawn-id invoke-id
                         :track?    track?
                         :type-ref  type-ref})
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
                      :machine-id (:machine-id args)
                      :spawned-id spawned-id
                      :spawn-id   invoke-id
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
  fxs) to seed the join state at `[:rf/runtime :machines :spawned <parent> <invoke-id>]` in
  the frame's app-db. The seed map shape is:

    {:children {<child-id> <spawned-id>, ...}
     :done      #{}
     :failed    #{}
     :resolved? false
     :spec      <invoke-all-spec>}

  Subsequent `:on-child-done` / `:on-child-error` events arrive at the
  parent's `make-machine-handler` boundary and are intercepted by
  `intercept-spawn-all-event` (in `lifecycle-fx.join`)."
  [{frame-id :frame :or {frame-id :rf/default}} args]
  (let [parent-id  (:rf/parent-id args)
        invoke-id  (:rf/spawn-id args)
        join-state (:join-state args)
        children   (:children join-state)]
    (frame/swap-frame-db! frame-id assoc-in
                          (paths/spawned-path parent-id invoke-id) join-state)
    (trace/emit! :rf.machine :rf.machine.spawn-all/started
                 {:machine-id parent-id
                  :spawn-id  invoke-id
                  :child-ids  (set (keys children))
                  :children   children
                  :frame      frame-id})
    nil))
