(ns re-frame.machines.lifecycle-fx.spawn
  "Spawn live-handler wiring: `:rf.machine/spawn` and
  `:rf.machine/spawn-all-init` fx handlers.

  `apply-transition-once` emits `[:rf.machine/spawn args]` into the fx
  vector whenever entry cascades cross a `:spawn`-bearing state. Per
  Spec 005 §Spawning, a spawned actor's liveness is runtime-db state:
  `spawn-fx` seeds the actor's initial snapshot at
  `[:rf.runtime/machines :snapshots <spawned-id>]` in the spawning
  frame's runtime-db, stamping the revertible type reference at
  `:rf/machine-type`. There is NO per-instance event-handler
  registration — the actor's liveness IS that snapshot's presence in the
  (revertible) frame value, and the snapshot's `:rf/machine-type` lets
  the lazy resolver (`lifecycle-fx.resolver`) re-materialise the actor's
  handler on dispatch. Spawn and destroy update runtime-db only. Epoch restore
  replaces the whole captured frame state, so actor liveness rewinds without
  registrar drift. Frame isolation follows from the snapshot living inside
  the spawning frame's runtime-db.

  `spawn-all-init-fx` also lives here — the runtime
  emits `[:rf.machine/spawn-all-init args]` alongside per-child
  `:rf.machine/spawn` fxs on entry to a `:spawn-all`-bearing state to
  seed the join state at `[:rf.runtime/machines :spawned <parent> <invoke-id>]`."
  (:require [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.machines.classification :as classification]
            [re-frame.machines.data-validation :as data-validation]
            [re-frame.machines.lifecycle-fx.resolver :as resolver]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.paths :as paths]
            [re-frame.machines.reply :as m-reply]
            [re-frame.machines.spawn-order :as spawn-order]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; `spawn-fx` (the fx handler / fail-closed gate) delegates the accepted-spawn
;; body to `spawn-fx*`, defined below it — forward-declared so the gate can
;; reference it.
(declare spawn-fx*)

;; ---- id allocation --------------------------------------------------------

(defn- pre-allocated-actor-id
  "Resolve the pre-allocated actor id carried on the spawn args. Per Spec
  005 §Declarative :spawn Spec-spec keys: `:fixed-actor-id` is an explicit
  actor-address literal (per-state singleton); `:rf/spawned-id` is stamped
  by the transition reducer (allocated from the parent snapshot's
  `:rf/spawn-counter`). Returns nil for hand-emitted
  `[:rf.machine/spawn args]` fxs that bypass the transition reducer —
  the caller (`spawn-fx`) allocates such ids from the frame's runtime-db
  spawn-counter slot at `[:rf.runtime/machines :spawn-counter]` inside
  the spawn's db-swap so the allocation shares the same write."
  [args]
  (or (:fixed-actor-id args)
      (:rf/spawned-id args)))

(defn- allocate-actor-id-in-runtime-db
  "Hand-emitted-spawn fallback allocator. When the spawn args
  carry no pre-allocated id (no `:fixed-actor-id`, no `:rf/spawned-id`), this
  fn bumps the frame's runtime-db counter at
  `[:rf.runtime/machines :spawn-counter <machine-id>]` and returns
  `[new-runtime-db spawned-id]`. The allocator lives where the side-effect
  belongs — inside the fx-handler's runtime-db swap — so the pure transition
  layer stays effect-free. The counter sits under the
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

  A nil return from a `:machine-id`-bearing spawn means the
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
  "A `:spawn` / `:spawn-all` per-child whose `:machine-id` names an
  UNREGISTERED machine TYPE — and which carries no inline `:definition` — is
  REJECTED fail-closed. True iff the args name a `:machine-id`, carry no
  `:definition`, and no registered machine spec resolves for that id. There
  is no implicit \"spec-less spawn\" lifecycle: a `:machine-id` that resolves
  to no registered spec is rejected rather than materialising an actor. A
  `:definition` spawn never trips this (the spec IS the args)."
  [args]
  (boolean
    (and (:machine-id args)
         (not (:definition args))
         (nil? (resolve-spawn-machine args)))))

(defn- reject-unregistered-spawn!
  "Emit the always-on `:rf.error/machine-spawn-unregistered-type` and reject
  the spawn. A `:machine-id` that resolves to no registered spec is rejected
  outright (there is no implicit \"spec-less spawn\" lifecycle): a rejected
  spawn installs NO snapshot, NO slot, NO system-id binding, allocates NO
  spawned-id, records NO spawn-order entry, fires NO trace, and dispatches
  NO `:start`.

  ALWAYS-ON (EP-0008 non-event union-record axis, surface #4): the reject is a
  production-reachable fail-closed boundary fact — an off-box shipper on a
  `goog.DEBUG=false` build must still see it — so it rides the always-on
  `:error-emit/dispatch-error-record` late-bind hook (the non-event sibling of
  `dispatch-on-error!`, shared with the frame-teardown report). A dev error
  trace (`trace/emit-error!`, DCE'd in production) ALSO fires for the
  in-process tooling surface — the same always-on-plus-dev-trace shape
  `:rf.error/write-after-destroy` / `:rf.error/on-destroy-handler-exception`
  carry.

  Privacy: the record carries STRUCTURAL context ONLY — `:machine-id`,
  `:frame`, `:reason`, `:recovery`. The full spawn `args` are NEVER carried:
  `:start` payloads / `:data` may hold application data (auth tokens, PII),
  and this record is production-surviving and NOT privacy-gated. `:reason`
  names the id and the fix without echoing any value-bearing slot."
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

(def ^:private spawn-all-reject-sentinel
  "The join-slot value `spawn-all-init-fx` writes when it REJECTS a
  `:spawn-all` invoke (some child names an UNREGISTERED TYPE). It carries NO
  `:children`, so `join.cljc`'s interceptor treats it as no live child-bearing
  join (a no-op) and `destroy.cljc`'s `destroy-spawn-all-children!` finds nothing to tear
  down and clears the slot on parent exit.

  Its sole purpose is to make the reject ATOMIC: the registered siblings'
  per-child `:rf.machine/spawn` fxs — SEPARATE entries later in the SAME
  entry `:fx` vector — read this sentinel (via `spawn-all-invoke-rejected?`)
  and suppress themselves, so a malformed `:spawn-all` spawns NOTHING rather
  than orphaning the registered siblings with no seeded join to ever tear
  them down (rf2-qb1j5z)."
  {:rf/spawn-all-rejected? true})

(defn- spawn-all-invoke-rejected?
  "True iff `args` is a `:spawn-all` per-child spawn whose invoke was
  REJECTED by `spawn-all-init-fx`'s preflight (an unregistered child TYPE, or
  a child that fails spawn-time `[:schemas :data]` validation). The reject
  seeds `spawn-all-reject-sentinel` at the join slot BEFORE the per-child
  spawns run — `spawn-all-init-fx` is the FIRST fx in the entry vector, ahead
  of every per-child `:rf.machine/spawn` — so every child spawn reads it here
  and suppresses itself. Keyed on the child's `:rf/spawn-all-id` (== the
  parent's invoke-id / join-slot key), so it fires ONLY for `:spawn-all`
  children, never a single `:spawn`.

  `spawn-fx` consults this AHEAD of its child-local `unregistered-spawn-type?`
  gate so an OFFENDING child suppresses too rather than re-emitting the reject
  the preflight already fanned (rf2-smya7a)."
  [frame-id args]
  (when-let [invoke-id (:rf/spawn-all-id args)]
    (let [slot (get-in (frame/frame-runtime-db-value frame-id)
                       (paths/spawned-path (:rf/parent-id args) invoke-id))]
      (boolean (and (map? slot) (:rf/spawn-all-rejected? slot))))))

(defn- machine-type-ref
  "The revertible TYPE reference stamped onto a spawned actor's snapshot root
  under `:rf/machine-type`, so the lazy resolver (`lifecycle-fx.resolver`)
  can re-materialise the actor's handler purely from runtime-db. A `:machine-id`
  spawn stores the registered TYPE keyword (the type outlives every
  instance — registered like a singleton). An inline `:definition` spawn
  stores the spec map verbatim (there is no registered type; the snapshot is
  the only source of truth, and it is fully revertible). A spawn always names
  exactly one of the two — an unregistered `:machine-id` is rejected
  fail-closed BEFORE this is reached (see `reject-unregistered-spawn!`), so
  this never returns nil on the accepted path."
  [args]
  (or (:machine-id args)
      (:definition args)))

(defn- stamp-framework-data
  "Stamp framework-reserved keys into the spawned actor's
  initial `:data` so the actor knows its own address (`:rf/self-id`)
  and, for declarative-`:spawn` spawns, its parent's address +
  invoke-id. `join-child` (a `:spawn-all` child's private join-membership
  record — see `join-child-record`) rides under `:rf/join-child` when
  present."
  [spec spawned-id parent-id invoke-id join-child]
  (when spec
    (let [base-data (or (:data spec) {})
          data'     (cond-> (assoc base-data :rf/self-id spawned-id)
                      parent-id  (assoc :rf/parent-id parent-id)
                      invoke-id  (assoc :rf/invoke-id invoke-id)
                      join-child (assoc :rf/join-child join-child))]
      (assoc spec :data data'))))

(defn- join-child-record
  "Build the PRIVATE join-membership record the runtime stamps into a
  `:spawn-all` child's `:data` under `:rf/join-child` (rf2-nvxehu). It
  carries the exact coordinates of the join ATTEMPT this child instance
  belongs to — the parent/invoke identity, the logical child id, the
  child's own spawned instance address, the join's completion event
  keywords, the opaque per-attempt token the seeding `spawn-all-init-fx`
  minted into the join state, and the canonical private work generation
  selected from explicit spawn provenance. The child's
  machine-handler boundary reads it to stamp outbound completion carriers
  with the attempt authentication the parent's join interceptor verifies
  (`join/stamp-join-completion-fx` / `intercept-spawn-all-event`), so a
  stale completion from a PRIOR attempt / prior actor incarnation can
  never fold into a successor join, while fixed-vs-generated work provenance
  never depends on the actor keyword's spelling. Framework-reserved, never
  application-facing.

  Returns nil for non-`:spawn-all` spawns and for a rejected invoke (the
  reject sentinel carries no `:children`).

  Ordering note: `spawn-all-init-fx` is the FIRST fx in the entry vector,
  ahead of every per-child `:rf.machine/spawn`, so the per-attempt token is
  ALWAYS in runtime-db by the time this reads it (the same ordering
  `spawn-all-invoke-rejected?` relies on)."
  [runtime-db args spawned-id]
  (when-let [invoke-id (:rf/spawn-all-id args)]
    (let [parent-id  (:rf/parent-id args)
          join-state (get-in runtime-db (paths/spawned-path parent-id invoke-id))]
      (when (and (map? join-state) (contains? join-state :children))
        {:parent-id       parent-id
         :invoke-id       invoke-id
         :child-id        (:rf/spawn-all-child-id args)
         :spawned-id      spawned-id
         :done-kw         (get-in join-state [:spec :on-child-done])
         :error-kw        (get-in join-state [:spec :on-child-error])
         :attempt         (:rf/attempt join-state)
         ;; Exact private work discriminator. The authored fixed-id presence
         ;; is explicit provenance: fixed children use the join attempt even
         ;; when their literal happens to end in `#<digits>`. Only after the
         ;; runtime has established that a child is generated do we extract
         ;; the allocator's established `<type>#<n>` counter. Actor-id spelling
         ;; therefore never decides fixed-vs-generated provenance, and the
         ;; public/pure spawn effect shape remains unchanged.
         :work-generation (if (contains? args :fixed-actor-id)
                            (:rf/attempt join-state)
                            (m-reply/actor-generation spawned-id))}))))

;; The spawned actor's initial snapshot is built by
;; `parallel/build-initial-snapshot` — the single source of truth shared
;; with the singleton-registration path
;; (`lifecycle-fx.registration/make-machine-handler`); the shared builder
;; seeds `:rf/spawn-counter` and `:meta` consistently across both paths.
;; The spawn path passes `:bootstrap-pending? true` because the actor's
;; first dispatch must fire the initial-entry cascade.

(defn- spawn-rejected?
  "Decide whether a spawn must be rejected by
  schema validation BEFORE any registration or install side effect runs.
  When the spawning machine's spec carries a `[:schemas :data]` schema, the
  freshly-built `initial-snap`'s `:data` is validated against it. A failure emits
  `:rf.error/schema-validation-failure :where :machine-data :phase :spawn`
  (via `validate-spawn-data!`) and returns `true`; the caller then skips
  the trace, the handler registration, the snapshot/system-id/spawn-slot
  install, the spawn-order record, AND the `:start` dispatch — the
  rejected actor leaves NO half-installed bookkeeping (no registered
  handler, no actor state, no phantom `(rf/machines)` entry).

  `continue?` is A's exact-frame-incarnation continuation predicate
  (rf2-vxgfnd.153). The schema validator is application code that can destroy
  A / publish same-id B; when it does, `validate-spawn-data!` returns
  `:rf/stale-incarnation` (NOT a schema verdict), which is `(false? …)` → this
  fn returns `false` (not a schema reject) and the ENCLOSING cascade fence in
  `spawn-fx*` — gated on `(continue?)` — is what suppresses the install. Only a
  literal `false` (a genuine schema violation while A still owns) is a reject.

  Returns `false` for a no-schema / no-validator / conforming / owner-lost
  spawn; `true` only on a genuine schema violation."
  [spec spawned-id initial-snap continue?]
  (and (some? spec)
       (false? (data-validation/validate-spawn-data!
                 spawned-id spec initial-snap continue?))))

(defn- install-spawn!
  "Atomically install the spawned actor's `initial-snap` (with its
  revertible `:rf/machine-type` TYPE reference stamped at the root),
  system-id binding, and runtime-owned spawn registry slot
  into the frame's runtime-db. Emits the collision and system-id-bound
  traces when applicable.

  Returns an EXPLICIT committed/live result (rf2-hloj0g): `:committed`
  when the runtime-db swap landed, `:skipped` when the exact-owner recheck
  fenced the swap (a `:rf.error/system-id-collision` listener destroyed A /
  published same-id B on the trace's own stack). The caller (`spawn-fx*`)
  runs NO post-install tail (classification / spawn-order /
  `:rf.machine.lifecycle/spawned` / `:start`) unless install COMMITTED and
  the exact owner is STILL current — a bare-id `swap-runtime-db!` /
  `spawn-order/record!` otherwise resolves to the CURRENT incarnation B.

  There is NO per-instance handler registration — the
  actor's liveness IS the presence of this snapshot in the (revertible)
  frame value, and the snapshot's `:rf/machine-type` lets the lazy
  resolver re-materialise the handler on dispatch. Spawn is therefore a
  pure runtime-db write.

  Schema-rejection is decided by the caller (`spawn-fx`)
  via `spawn-rejected?` BEFORE this fn runs, so by the time
  `install-spawn!` is reached the spawn is known-accepted and
  `initial-snap` (built once by the caller) is threaded in rather than
  re-built here.

  `rt-after-alloc` is the post-id-allocation runtime-db computed by the
  caller (see `spawn-fx`); `swap-runtime-db!`'s fn arg is discarded — the
  merge is applied on top of `rt-after-alloc` so the caller's counter bump
  survives. Under Spec 002's single-drainer invariant the discarded
  re-read is value-equal to the snapshot the caller already had. Machine
  snapshots are durable runtime-db state (EP-0001)."
  [frame-id rt-after-alloc spec spawned-id initial-snap
   {:keys [system-id parent-id invoke-id track? type-ref continue? owner-token]}]
  (let [existing (when system-id (get-in rt-after-alloc (paths/system-id-path system-id)))
        ;; Stamp the revertible TYPE reference onto the snapshot root so the
        ;; lazy resolver can re-materialise the handler from runtime-db alone. The
        ;; spawn is known-accepted by the time `install-spawn!` runs (an
        ;; unregistered `:machine-id` was rejected fail-closed upstream), so
        ;; `spec` is always present; the `spec`/`type-ref` guards are
        ;; belt-and-braces.
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
    ;; rf2-3evq0x — the `:rf.error/system-id-collision` trace above is
    ;; callback-bearing; recheck the exact-incarnation continuation before the
    ;; runtime-db swap so a listener that destroyed A / published same-id B
    ;; cannot land the A-derived snapshot / system-id / spawn-slot install (a
    ;; bare-id `swap-runtime-db!` resolves to the CURRENT incarnation B) or fire
    ;; the `:rf.machine/system-id-bound` trace for it. `continue?` is nil only
    ;; for a hypothetical caller that did not thread it — treated as live.
    ;; rf2-hloj0g — return the swap outcome EXPLICITLY (`:committed` /
    ;; `:skipped`) so the caller can fence its own post-install tail on it (the
    ;; earlier `:ok`/nil return was IGNORED, so a `:skipped` install still let
    ;; classification / spawn-order / lifecycle-spawned run against B).
    ;; rf2-4ipqe4 — the pre-swap `(continue?)` check fences a system-id-collision
    ;; LISTENER, but the WRITE ITSELF is callback-bearing: a synchronous
    ;; container watch can destroy A / publish same-id B DURING the physical
    ;; install. The bare `swap-runtime-db!` would still bump the id-keyed commit
    ;; epoch (now B's) and let `:rf.machine/system-id-bound` fire for B. With an
    ;; event owner, route the install through `swap-runtime-db-exact!`: it binds
    ;; the write to A's own container, and on mid-write loss returns nil WITHOUT
    ;; bumping B's epoch — so we report `:skipped` and suppress the system-id-bound
    ;; trace. Without an owner (conformance / pure-fn), fall back to the bare
    ;; write (its non-nil return marks `:committed`).
    (if (or (nil? continue?) (continue?))
      (let [install-fn (fn [_rt]
                         (cond-> rt-after-alloc
                           spec      (assoc-in (paths/snapshot-path spawned-id) initial-snap)
                           system-id (assoc-in (paths/system-id-path system-id) spawned-id)
                           track?    (assoc-in (paths/spawned-path parent-id invoke-id) spawned-id)))
            written    (if owner-token
                         (frame/swap-runtime-db-exact! frame-id owner-token install-fn)
                         (frame/swap-runtime-db! frame-id install-fn))]
        (if (some? written)
          (do
            (when system-id
              (trace/emit! :rf.machine :rf.machine/system-id-bound
                           {:frame      frame-id
                            :system-id  system-id
                            ;; The live actor INSTANCE address (the spawned id),
                            ;; not the registered TYPE; `:machine-id` is reserved
                            ;; for the type.
                            :actor-id   spawned-id}))
            :committed)
          ;; The exact write reported mid-write owner loss (a container watch
          ;; published same-id B): no snapshot / system-id / spawn-slot landed on
          ;; B, no commit epoch bumped for B, and no system-id-bound trace fires.
          :skipped))
      :skipped)))

;; ---- :rf.machine/spawn -----------------------------------------------------

(defn spawn-fx
  "fx handler for `:rf.machine/spawn`. Per Spec 005 §Spawning, the spawned
  actor's snapshot lives at `[:rf.runtime/machines :snapshots
  <spawned-id>]` in the spawning frame's runtime-db, and its liveness is that
  snapshot's presence in the (revertible) frame value — there is NO
  per-instance event-handler registration.

  Lifecycle wired here:
   0. **Fail-closed gate.** If `:machine-id` names an
      UNREGISTERED machine TYPE and the spawn carries no inline
      `:definition`, REJECT the spawn: emit the always-on
      `:rf.error/machine-spawn-unregistered-type` and return without
      installing anything — no snapshot, no slot, no system-id, no
      spawned-id allocation, no spawn-order record, no trace, no `:start`
      dispatch. There is no implicit \"spec-less spawn\" lifecycle.
   1. Resolve the spawn's machine spec (`:machine-id` from the registrar
      OR an inline `:definition`).
   2. Initialise the actor's snapshot at `[:rf.runtime/machines
      :snapshots <spawned-id>]` using the spec's `:initial` / `:data`
      (overridden by the spawn args' `:data`), stamping the revertible
      TYPE reference at `:rf/machine-type` (the `:machine-id` keyword, or
      the inline `:definition` map) so the lazy resolver
      (`lifecycle-fx.resolver`) can re-materialise the actor's handler
      from runtime-db alone. The runtime stamps `:rf/self-id`
      (the spawned actor's own address) and, when applicable,
      `:rf/parent-id` + `:rf/invoke-id` into the actor's initial `:data`.
      Re-spawn under the same id replaces — last-write-wins.
   3. If `:system-id` present, bind it in the per-frame
      `[:rf.runtime/machines :system-ids]` reverse index. Collisions emit
      `:rf.error/system-id-collision` and rebind (last-write-wins).
   4. If `:rf/parent-id` + `:rf/invoke-id` present (declarative `:spawn`
      desugar), bind the spawned id at
      `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]`.
   5. If `:start` event-vector present, dispatch
      `[<spawned-id> <start>]`. When `:start` is absent,
      the runtime dispatches a synthetic `[<spawned-id>
      [:rf.machine.spawn/spawned]]` so generic child machines may declare a
      leaf-level `:on :rf.machine.spawn/spawned :target ...` transition.
      The first dispatch lazy-resolves the just-installed snapshot into a
      live handler (no registration step).

  Because no per-instance registration exists, replacing a captured frame
  state also restores actor liveness without registrar drift."
  [{frame-id :frame} args]
  (let [;; EP-0002 carried invariant: `:rf.machine/spawn` runs inside a
        ;; pipeline run, so the fx context ALWAYS carries the envelope
        ;; frame as `:frame` (the HELD stamp). A nil stamp is an invariant
        ;; failure — surface `:rf.error/no-frame-context`, never repair to
        ;; a synthesised `:rf/default`.
        frame-id   (frame/require-frame-stamp!
                     frame-id :rf.machine/spawn
                     {:where 'rf.machine/spawn :event-id (:system-id args)})]
    ;; Step 0 — the two fail-closed gates, INVOKE-level before CHILD-local.
    ;; Both reject BEFORE any id allocation, spec resolution,
    ;; snapshot/slot/system-id install, spawn-order record, trace, or
    ;; `:start` dispatch — the strongest atomicity (there is no spec-less
    ;; spawn path, so there is no half-installed bookkeeping the next op
    ;; could trip over).
    (cond
      ;; Step 0a — atomic `:spawn-all` reject. `spawn-all-init-fx` (the FIRST
      ;; fx in this entry vector) preflights the whole child set and, on any
      ;; fail-closed admission failure, seeds `spawn-all-reject-sentinel` at
      ;; the join slot. EVERY per-child effect of a rejected invoke suppresses
      ;; here — registered, unregistered, and schema-invalid alike — so the
      ;; reject is ATOMIC: no live orphan with no seeded join to ever tear it
      ;; down (rf2-qb1j5z).
      ;;
      ;; This gate is FIRST, ahead of the child-local unregistered check
      ;; below, and the order is the CONTRACT (rf2-smya7a). The invoke-level
      ;; preflight is the SOLE emitter for a rejected invoke: it already
      ;; emitted exactly one reject per offending child. Were the child-local
      ;; gate tested first, each offending child would bypass this suppression
      ;; and emit a SECOND, duplicate record + dev trace — doubling the
      ;; production error cardinality and making one malformed invoke look
      ;; like two independent boundary failures per child. Silent by design.
      (spawn-all-invoke-rejected? frame-id args)
      nil

      ;; Step 0b — child-local fail-closed gate for an unregistered
      ;; `:machine-id` (no inline `:definition`). This is the SOLE emitter for
      ;; a standalone single `:spawn`, which has no invoke sentinel to hide
      ;; behind — it must still reject exactly once and fail closed. It also
      ;; still covers a hand-emitted `:spawn-all` child fx that reaches the
      ;; runtime with no preceding init fx (no sentinel, no live join).
      (unregistered-spawn-type? args)
      (reject-unregistered-spawn! frame-id (:machine-id args))

      :else
      (spawn-fx* frame-id args))))

(defn- spawn-fx*
  "The accepted-spawn body of `spawn-fx` — runs only after the
  `unregistered-spawn-type?` fail-closed gate has let the spawn
  through. `frame-id` is the resolved (non-nil-stamped) frame; `args` the
  spawn args. Returns the allocated `spawned-id`."
  [frame-id args]
  (let [;; A's exact-frame-incarnation continuation predicate (rf2-vxgfnd.153).
        ;; The `[:schemas :data]` spawn validator (`validate-spawn-data!`, run
        ;; inside `spawn-rejected?` below) is APPLICATION code that can
        ;; synchronously destroy this frame incarnation A and publish a same-id
        ;; successor B before returning. Everything after that callback — the
        ;; snapshot / system-id / spawn-slot install, per-instance
        ;; classification lowering, the spawn-order record, the two spawned
        ;; traces, and the `:start` (or synthetic) dispatch — is framework-owned
        ;; tail computed from A's already-read runtime-db. Re-checking
        ;; `(continue?)` at the cascade gate fences that whole tail so no
        ;; A-derived allocation or bookkeeping lands on B (whose bare-id
        ;; `swap-runtime-db!` would otherwise resolve to B). Built ONCE here and
        ;; threaded into `spawn-rejected?` so the callback and the cascade fence
        ;; against the SAME token.
        continue?  (data-validation/owner-continuation frame-id)
        ;; rf2-4ipqe4 — the RAW exact owner token (`continue?` closes over it),
        ;; threaded into `install-spawn!` so the snapshot / system-id / spawn-slot
        ;; install rides `swap-runtime-db-exact!`: a synchronous container watch
        ;; that destroys A / publishes same-id B DURING the physical write neither
        ;; redirects the write into B nor bumps B's commit epoch (a bare
        ;; `swap-runtime-db!` bumps the id-keyed epoch — now B's — and emits
        ;; `:rf.machine/system-id-bound` before the later owner check). nil for a
        ;; non-router pure-fn / conformance caller (no event owner) — the install
        ;; falls back to the historical bare-id write and that path stays
        ;; unaffected, symmetric with `continue?`'s `(constantly true)`.
        owner-token (frame/current-event-owner-token)
        ;; Prefer the pre-allocated id (declarative :spawn
        ;; routes through the transition reducer which bumps the parent
        ;; snapshot's `:rf/spawn-counter`). Hand-emitted spawn fxs carry
        ;; no pre-allocated id; the frame's runtime-db spawn-counter slot
        ;; at `[:rf.runtime/machines :spawn-counter]` serves
        ;; as the fallback allocator, bumped inside the same db-swap as
        ;; the snapshot install / registry bind below.
        pre-id     (pre-allocated-actor-id args)
        spec       (resolve-spawn-machine args)
        spec'      (if (and spec (contains? args :data))
                     (assoc spec :data (:data args))
                     spec)
        ;; The revertible TYPE reference the lazy resolver reads back off
        ;; the installed snapshot.
        type-ref   (machine-type-ref args)
        system-id  (:system-id args)
        ;; The runtime tracks each declarative-:spawn spawn at
        ;; [:rf.runtime/machines :spawned <parent-id> <invoke-id>] —
        ;; populated only when the spawn carries both.
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
        ;; Machine snapshots are durable runtime-db state.
        old-rt     (frame/frame-runtime-db-value frame-id)
        machine-id-for-alloc (or (:id-prefix args) (:machine-id args))
        [rt-after-alloc spawned-id]
        (cond
          pre-id        [old-rt pre-id]
          (and old-rt machine-id-for-alloc)
                        (allocate-actor-id-in-runtime-db old-rt machine-id-for-alloc)
          :else         [old-rt nil])
        spec''     (stamp-framework-data spec' spawned-id parent-id invoke-id
                                         (join-child-record old-rt args spawned-id))
        ;; Build the initial snapshot ONCE here so the schema-rejection
        ;; decision can gate every side effect below; `install-spawn!`
        ;; threads the same value rather than re-building it.
        initial-snap (when spec''
                       (parallel/build-initial-snapshot
                         spec'' {:bootstrap-pending? true}))
        ;; Decide schema rejection BEFORE the trace and the
        ;; install. Gating both on `(not rejected?)` makes a rejected spawn
        ;; FULLY atomic — it installs no snapshot, records no spawn-order
        ;; entry, dispatches no `:start`, and announces no
        ;; `:rf.machine.spawn/spawned` (only the
        ;; `:rf.error/schema-validation-failure :phase :spawn` that
        ;; `validate-spawn-data!` already emitted). There is no per-instance
        ;; handler registration to gate — a rejected spawn simply writes
        ;; nothing to runtime-db, so no liveness exists for the rejected actor
        ;; (the strongest form of atomicity: an actor's liveness IS its
        ;; snapshot, and the snapshot was never installed).
        rejected?  (spawn-rejected? spec'' spawned-id initial-snap continue?)]
    ;; Gate the ENTIRE accepted-spawn cascade — the
    ;; `:rf.machine.spawn/spawned` trace, the snapshot/system-id/spawn-slot
    ;; install, AND the `:start` (or synthetic) dispatch — on THREE conditions:
    ;;   (1) the spawn being accepted (`not rejected?` — no schema violation),
    ;;   (2) the frame being LIVE at read time (`old-rt` non-nil — a
    ;;       destroyed / never-created frame reads nil and allocates a nil
    ;;       `spawned-id`; firing a phantom spawned trace + `[nil <start>]`
    ;;       dispatch would be an atomicity violation), and
    ;;   (3) the exact frame incarnation that owns the event STILL being live
    ;;       AFTER the schema callback (`(continue?)` — rf2-vxgfnd.153). A
    ;;       validator that destroyed A / published same-id B loses the token;
    ;;       none of the A-derived install / classification / spawn-order /
    ;;       trace / dispatch tail may land on B.
    ;; A destroyed-frame or owner-lost spawn is a clean no-op — no trace, no
    ;; install, no dispatch — symmetric with the schema-reject path's atomicity.
    (when (and (not rejected?) old-rt (continue?))
      (trace/emit! :rf.machine :rf.machine.spawn/spawned
                   {:frame      frame-id
                    ;; `:machine-id` is the spec-time registered TYPE (xor
                    ;; an inline `:definition`); `:spawned-id` is the live
                    ;; instance address; `:invoke-id` is the declarative
                    ;; invocation path.
                    :machine-id (:machine-id args)
                    :spawned-id spawned-id
                    :id-prefix  (:id-prefix args)
                    :start      (:start args)
                    :on-spawn   (:on-spawn args)
                    :system-id  system-id
                    :parent-id  parent-id
                    :invoke-id  invoke-id})
      ;; rf2-3evq0x — the `:rf.machine.spawn/spawned` trace above is
      ;; callback-bearing: a trace LISTENER can synchronously destroy A /
      ;; publish same-id B before returning. Recheck the exact-incarnation
      ;; continuation HERE, after the trace fanout and before ANY framework-
      ;; owned bookkeeping — the install / classification / spawn-order record /
      ;; `:rf.machine.lifecycle/spawned` trace / `:start` dispatch tail is all
      ;; A-derived and would otherwise commit into B's name (the bare-id
      ;; `swap-runtime-db!` resolves to the CURRENT incarnation B). The initial
      ;; `(continue?)` cascade gate only fenced the SCHEMA-validator callback;
      ;; this fences the trace-listener callback the earlier gate ran ahead of.
      ;;
      ;; NO per-instance handler registration. The actor's liveness IS its
      ;; snapshot's presence in the (revertible) frame value; the snapshot's
      ;; `:rf/machine-type` (stamped by `install-spawn!`) lets the lazy resolver
      ;; re-materialise the handler on dispatch. Spawn is a pure runtime-db
      ;; write.
      ;;
      ;; (2) Initialise the snapshot + (3) bind :system-id + (4) bind the
      ;; runtime-owned spawn registry (atomically under one runtime-db swap
      ;; so observers see consistent state). When the spawned id was
      ;; allocated from the frame's runtime-db (the hand-emitted-spawn
      ;; fallback path), `rt-after-alloc` already carries the bumped counter —
      ;; install the snapshot on top of that. `continue?` is threaded into
      ;; `install-spawn!` so the callback-bearing `:rf.error/system-id-collision`
      ;; trace it may emit gets a recheck before the runtime-db swap too.
      (when (continue?)
        (let [installed (install-spawn! frame-id rt-after-alloc spec'' spawned-id initial-snap
                                        {:system-id   system-id
                                         :parent-id   parent-id
                                         :invoke-id   invoke-id
                                         :track?      track?
                                         :type-ref    type-ref
                                         :continue?   continue?
                                         :owner-token owner-token})]
        ;; rf2-hloj0g — `install-spawn!`'s `:rf.error/system-id-collision`
        ;; (pre-swap → `:skipped`) and `:rf.machine/system-id-bound` (post-swap)
        ;; traces are callback-bearing: a listener can destroy A / publish
        ;; same-id B on the trace's own stack. Run the framework-owned tail —
        ;; per-instance classification, the spawn-order record, and the
        ;; `:rf.machine.lifecycle/spawned` trace — ONLY when install COMMITTED
        ;; AND the exact owner is STILL current after those callbacks. Otherwise
        ;; the bare-id `spawn-order/record!` / classification writes + the
        ;; lifecycle-spawned trace would commit into B's name. The initial
        ;; `(continue?)` gate at the cascade top fenced only the earlier
        ;; `:rf.machine.spawn/spawned` trace-listener callback; this fences the
        ;; install's own two callbacks that gate ran ahead of.
        (when (and (= :committed installed) (continue?))
        ;; Lower the machine spec's projection-relative `:sensitive` / `:large`
        ;; `:data` declarations
        ;; into the per-frame elision registry PER ACTOR INSTANCE — re-rooting
        ;; each snapshot-relative `[:data …]` path to this instance's absolute
        ;; snapshot path. The classification travels with the machine def and
        ;; applies to every generated `<type>#n`, dropped on destroy
        ;; (`teardown-live-actor!`). The egress READ path
        ;; (`re-frame.classification/frame-snapshot-classification`, SSR, trace) is unchanged —
        ;; this writes the registry-entry source `:source :machine`, a peer of
        ;; the general commit-plane `:source :effect` route. A spec declaring
        ;; no classification is a no-op. rf2-i4aj9c — thread A's `owner-token`
        ;; so the per-instance classification lowering rides the EXACT elision
        ;; write: a container watch that destroys A / publishes same-id B DURING
        ;; the registry write cannot re-root the lowered `:sensitive` / `:large`
        ;; declarations onto B's snapshot path or bump B's commit epoch (the
        ;; install itself is already exact; this closes the sibling classification
        ;; write the earlier fences ran ahead of).
        (classification/lower-at-spawn! frame-id spawned-id spec'' owner-token)
        ;; Record the spawned actor in the frame's spawn-order channel so
        ;; frame-destroy can walk in reverse-creation order per Spec 005
        ;; §Cross-Spec Interactions §1.
        (spawn-order/record! frame-id spawned-id)
        ;; The REGISTRAR-substrate "instance appeared" observation, the
        ;; symmetric partner of
        ;; `:rf.machine.lifecycle/created` (handler registered) and
        ;; `:rf.machine.lifecycle/destroyed` (handler/snapshot reaped).
        ;; The fx-substrate emit above (`:rf.machine.spawn/spawned`) says
        ;; "the spawn fx ran"; this emit says "a spawned actor's snapshot
        ;; landed in runtime-db". Spec 009 §Two-axis machine
        ;; observation: tools that just want "did an actor appear?"
        ;; subscribe to the `:rf.machine.lifecycle/*` channel; causal-graph
        ;; builders subscribe to both and disambiguate by the naming axis.
        ;; The `:state` tag carries the actor's initial state so the Xray
        ;; managed-fx invoke adapter can render it without re-reading
        ;; runtime-db (`managed_fx_helpers/machine-invoke-adapter`).
        (trace/emit! :rf.machine.lifecycle/spawned :rf.machine.lifecycle/spawned
                     {:frame      frame-id
                      ;; `:machine-id` = spec-time registered TYPE;
                      ;; `:spawned-id` = live instance address;
                      ;; `:invoke-id` = declarative invocation path.
                      :machine-id (:machine-id args)
                      :spawned-id spawned-id
                      :invoke-id  invoke-id
                      :system-id  system-id
                      :parent-id  parent-id
                      :state      (:state initial-snap)})
      ;; rf2-3evq0x — the `:rf.machine.lifecycle/spawned` trace above is
      ;; likewise callback-bearing; recheck ownership once more before the
      ;; `:start` (or synthetic) actor-bootstrap dispatch so a listener that
      ;; just replaced A with B cannot kick B's bootstrap under A's authority.
      (when (continue?)
        ;; (6) Fire the :start event into the new actor. Spawns that don't
        ;; supply :start receive a synthetic [:rf.machine.spawn/spawned] so
        ;; generic child machines can declare their first transition out of an
        ;; :initial state at spec-write time.
        (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
        ;; Stamp `:source :machine-spawn` on the actor-bootstrap dispatch so
        ;; the Epoch panel's DISPATCH step labels it "from machine spawn"
        ;; rather than `:unknown` or `:fx-dispatch` (which would be the value
        ;; if the spawn fx routed through `:dispatch`), and Xray's L2 timeline
        ;; can prefix the row + per-source filter pills can discriminate
        ;; actor-bootstrap cascades. `:source` is the single functional-origin
        ;; discriminator (closed-enum per Spec-Schemas
        ;; §`:rf/dispatch-envelope`).
        (let [start (:start args)
              opts  {:frame              frame-id
                     :source             :machine-spawn}]
          (if (some? start)
            (dispatch! [spawned-id start] opts)
            (dispatch! [spawned-id [:rf.machine.spawn/spawned]] opts)))))))))
    spawned-id))

;; ---- :rf.machine/spawn-all-init -------------------------------------------

(defonce ^:private join-attempt-counter
  ;; Monotonic per-seed attempt-token source (rf2-nvxehu; mirrors
  ;; `timer/after-attempt-counter`). Every LIVE `:spawn-all` seed mints one
  ;; opaque token into the join state under `:rf/attempt`, and the same token
  ;; is stamped into each child's private `:rf/join-child` membership record —
  ;; so the parent's join interceptor can bind every completion carrier to the
  ;; exact join ATTEMPT that spawned it. Actor ids alone cannot discriminate a
  ;; re-entry respawn (`:fixed-actor-id` children reuse the SAME id across
  ;; attempts; `actor-generation` is always 1 for them), hence the token.
  ;; Uniqueness is per-session accident-gating (the machines security stance:
  ;; trust the explicit invoker, gate accidents), not cryptographic.
  (atom 0))

(defn- next-join-attempt-token []
  (swap! join-attempt-counter inc))

(defn spawn-all-init-fx
  "fx handler for `:rf.machine/spawn-all-init`. Per Spec 005
  §Spawn-and-join via `:spawn-all`, on entry to a `:spawn-all`-bearing
  state the runtime emits this fx (alongside per-child `:rf.machine/spawn`
  fxs) to seed the join state at `[:rf.runtime/machines :spawned <parent> <invoke-id>]` in
  the frame's runtime-db. The seed map shape is:

    {:children   {<child-id> <spawned-id>, ...}
     :done       #{}
     :failed     #{}
     :cancelled  #{}   ;; authenticated explicit-teardown tombstones for THIS attempt
     :resolved?  false
     :spec       <invoke-all-spec>
     :rf/attempt <opaque-attempt-token>}   ;; minted HERE per live seed (rf2-nvxehu)

  Subsequent `:on-child-done` / `:on-child-error` events arrive at the
  parent's `make-machine-handler` boundary and are intercepted by
  `intercept-spawn-all-event` (in `lifecycle-fx.join`).

  This fx fires FIRST in the entry `:fx` vector — BEFORE the per-child
  `:rf.machine/spawn` fxs. If ANY child in the set names an UNREGISTERED
  machine TYPE (no inline `:definition`), the WHOLE invoke is REJECTED
  ATOMICALLY here (rf2-qb1j5z): instead of a live child-bearing join
  state, this fx seeds `spawn-all-reject-sentinel` at the join slot — a
  slot that is PHYSICALLY present in runtime-db but carries NO `:children`,
  so it is a reject marker, NOT a live join. A never-running spec-less
  child would otherwise never dispatch its `:on-child-done`, blocking an
  `:all` join FOREVER (`join.cljc` `(= n-done n-total)` can never hold).
  EVERY child's per-child `:rf.machine/spawn` fx — SEPARATE entries later in
  THIS same entry vector — reads the sentinel (`spawn-all-invoke-rejected?`)
  and SUPPRESSES itself BEFORE installing, so a malformed set spawns NOTHING
  rather than orphaning the registered siblings under no live join. That
  suppression covers the OFFENDING children too, not just their registered
  siblings: this fx is the SOLE emitter for a rejected invoke and emits
  exactly ONE reject per offending child (rf2-smya7a).
  Because the sentinel is childless, no actor is ever spawned to complete:
  a stray / forged `:on-child-done` hits `join.cljc`'s childless-slot
  guard and is a no-op (no deadlock), and `destroy-spawn-all-children!`
  finds nothing to tear down and clears the sentinel on parent exit."
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
        ;; The original child invoke-specs (carry `:machine-id` /
        ;; `:definition`) ride the seeded join-state's `:spec`. Detect any
        ;; unregistered child TYPE up front.
        unregistered (->> (get-in join-state [:spec :children])
                          (filterv unregistered-spawn-type?))]
    (if (seq unregistered)
      ;; Fail-closed: reject the join so the never-running spec-less child
      ;; cannot hang the `:all` join forever. Emit EXACTLY one reject per
      ;; offending child (structural-only tags, per the privacy contract) —
      ;; this fx is the sole emitter, and each offending child's own
      ;; `:rf.machine/spawn` fx suppresses silently under the sentinel it
      ;; seeds below rather than emitting a duplicate (rf2-smya7a).
      ;;
      ;; ATOMIC reject (rf2-qb1j5z): seed `spawn-all-reject-sentinel` at the
      ;; join slot rather than seeding NO join-state. The registered
      ;; siblings' per-child `:rf.machine/spawn` fxs — SEPARATE entries later
      ;; in THIS entry `:fx` vector — would otherwise install live orphan
      ;; actors that no seeded join ever tears down (they carry
      ;; `:rf/spawn-all-id`, `track? false`, are recorded only in
      ;; spawn-order, and leak until frame-destroy). The sentinel signals the
      ;; rejected invoke so those siblings suppress themselves
      ;; (`spawn-all-invoke-rejected?`) — the whole malformed invoke spawns
      ;; NOTHING, the consistent analogue of a single `:spawn`'s atomic
      ;; reject. The sentinel carries no `:children`, so the join interceptor
      ;; treats it as no live child-bearing join (no deadlock) and `destroy-spawn-all-children!`
      ;; finds nothing to tear down and clears the slot on parent exit.
      (do (doseq [child unregistered]
            (reject-unregistered-spawn! frame-id (:machine-id child)))
          (frame/swap-runtime-db! frame-id assoc-in
                                  (paths/spawned-path parent-id invoke-id)
                                  spawn-all-reject-sentinel)
          nil)
      (let [;; Mint the opaque per-attempt token (rf2-nvxehu). One LIVE seed =
            ;; one join ATTEMPT; the token rides the join state AND each
            ;; child's `:rf/join-child` membership record (stamped by the
            ;; per-child spawn fxs that run AFTER this fx in the same entry
            ;; vector), binding every completion carrier to this exact
            ;; attempt. The reject sentinel branch above mints NO token —
            ;; a rejected invoke has no attempt to authenticate against.
            join-state (assoc join-state
                              :cancelled #{}
                              :rf/attempt (next-join-attempt-token))]
        ;; Machine spawn-registry state is durable runtime-db state.
        (frame/swap-runtime-db! frame-id assoc-in
                                (paths/spawned-path parent-id invoke-id) join-state)
        (trace/emit! :rf.machine :rf.machine.spawn-all/started
                     {;; The parent's live actor INSTANCE address;
                      ;; `:invoke-id` is the declarative invocation path.
                      :actor-id   parent-id
                      :invoke-id  invoke-id
                      :child-ids  (set (keys children))
                      :children   children
                      :frame      frame-id})
        nil))))
