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
  (:require [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines.classification :as rf.machines.classification]
            [re-frame.machines.data-validation :as rf.machines.data-validation]
            [re-frame.machines.lifecycle-fx.resolver :as rf.machines.lifecycle-fx.resolver]
            [re-frame.machines.parallel :as rf.machines.parallel]
            [re-frame.machines.paths :as rf.machines.paths]
            [re-frame.machines.reply :as rf.machines.reply]
            [re-frame.machines.spawn-order :as rf.machines.spawn-order]
            [re-frame.trace :as rf.trace]))

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
      machine-id  (rf.machines.lifecycle-fx.resolver/spec-from-registry machine-id))))

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
  trace (`rf.trace/emit-error!`, DCE'd in production) ALSO fires for the
  in-process tooling surface — the same always-on-plus-dev-trace shape
  `:rf.error/write-after-destroy` / `:rf.error/on-destroy-handler-exception`
  carry.

  Privacy: the record carries STRUCTURAL context ONLY — `:machine-id`,
  `:frame`, `:reason`, `:recovery`. The full spawn `args` are NEVER carried:
  `:start` payloads / `:data` may hold application data (auth tokens, PII),
  and this record is production-surviving and NOT privacy-gated. `:reason`
  names the id and the fix without echoing any value-bearing slot."
  [frame-id machine-id]
  (let [reason (rf.error/human-message
                 :rf.error/machine-spawn-unregistered-type
                 (str "Cannot spawn machine " machine-id
                      ": no machine TYPE is registered under that :machine-id "
                      "(and the spawn carries no inline :definition). Register the "
                      "machine with rf/reg-machine before spawning it, or supply an "
                      "inline :definition."))]
    ;; Always-on (surface #4): the non-event union record. Structural-only —
    ;; no spawn args. Late-bound: machines ships above core's require graph;
    ;; the hook is bound once `re-frame.error-emit` loads.
    (when-let [dispatch-record! (rf.late-bind/get-fn :error-emit/dispatch-error-record)]
      (dispatch-record! {:error      :rf.error/machine-spawn-unregistered-type
                         :frame      frame-id
                         :machine-id machine-id
                         :recovery   :no-recovery
                         :reason     reason
                         :time       (rf.interop/now-ms)}))
    ;; Dev trace (DCE'd in production) for the in-process tooling surface.
    (rf.trace/emit-error! :rf.error/machine-spawn-unregistered-type
                       {:machine-id machine-id
                        :failing-id machine-id
                        :frame      frame-id
                        :recovery   :no-recovery
                        :reason     reason})
    nil))

(defn- reject-address-collision!
  "Emit the `:rf.error/machine-spawn-all-duplicate-id` dev trace for a
  `:spawn-all` invoke whose children RESOLVE to ALIASING actor addresses
  (rf2-qlzh9), naming every aliased address and its offending logical
  child ids. The registration-time `validate-spawn-all!` variant guards LOGICAL
  `:id` uniqueness (and throws); THIS is the RUNTIME resolved-address guard:
  two DISTINCT logical children whose `:fixed-actor-id` literals — or a fixed id
  colliding with a generated `<type>#n` — collapse to ONE actor id would
  otherwise silently overwrite one `{<spawned-id> prepared}` entry / one live
  actor (the first child consuming the other's spec/snapshot and dropping the
  only entry, the later falling back to a second resolution/validator verdict).

  DIAGNOSTIC channel (Spec 009), like its sibling runtime `:spawn-all`
  diagnostic `:rf.error/machine-spawn-all-bad-child-id`: the fail-closed reject
  itself runs in production (the caller seeds the childless reject sentinel
  regardless), but the observability trace rides the dev-only surface DCE'd
  under `goog.DEBUG=false`. STRUCTURAL context ONLY — parent/invoke identity, the
  offending logical child ids, the resolved actor addresses; never the spawn
  `args` / `:data` (which may hold application PII).

  `collisions` is the deterministic `[[<resolved-address> [<logical-child-id> …]]
  …]` vector `spawn-all-address-collisions` returns (first-appearance order,
  stable on CLJ and CLJS); one trace fans per invoke, and its `:collisions` tag
  is THAT VECTOR, carried through unchanged (rf2-hys95) — both the GROUP order
  (first appearance in the declaration) and each group's per-address child-id
  order are read positionally, identically on both hosts.

  It is deliberately NOT re-presented as a `{<resolved-address>
  [<logical-child-id> …]}` map: past the 8-entry array-map threshold that
  degrades to a hash map whose seq order is neither declaration order nor
  host-stable, so a developer reading the reject to find WHICH declarations
  collided would get a shuffled list. The human `reason` text derives from the
  same vector, so message and tag can never disagree.

  This is the FIRST diagnostic an aliased invoke can fan and, because the alias
  short-circuits ahead of preparation, its ONLY one (rf2-ri19s): no child TYPE
  was resolved and no `[:schemas :data]` validator ran, so there is no
  unregistered-type or schema-validation reject to order against."
  [frame-id parent-id invoke-id collisions]
  (let [reason (rf.error/human-message
                 :rf.error/machine-spawn-all-duplicate-id
                 (str "Cannot start :spawn-all invoke " invoke-id " on " parent-id
                      ": distinct logical children resolve to the SAME actor "
                      "address " (pr-str collisions)
                      " — two children would collapse to one live actor (a silent "
                      "overwrite), so the whole invoke is rejected fail-closed. "
                      "Give each colliding child a distinct :fixed-actor-id, or "
                      "drop the fixed id so the runtime allocates a unique "
                      "generated address."))]
    (rf.trace/emit-error! :rf.error/machine-spawn-all-duplicate-id
                       {:parent-id  parent-id
                        :invoke-id  invoke-id
                        :collisions collisions
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
    (let [slot (get-in (rf.frame/frame-runtime-db-value frame-id)
                       (rf.machines.paths/spawned-path (:rf/parent-id args) invoke-id))]
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

(defn- join-child-record-from-state
  "`join-child-record`'s pure core — build the record from an already-in-hand
  join-state VALUE rather than reading it back out of runtime-db.

  Split out (rf2-7u8gen) so `spawn-all-init-fx`'s invoke-level PREFLIGHT can
  build the candidate child `:data` it validates against the join-state it is
  ABOUT to seed: that state is not in runtime-db yet, and for a REJECTED
  invoke it never will be. Sharing this core is what makes the preflight's
  `:rf/join-child` stamp byte-identical to the one the later per-child install
  writes, so the two paths can never reach different schema verdicts for the
  same child.

  Returns nil unless `join-state` is a live child-bearing join (the reject
  sentinel carries no `:children`)."
  [join-state args spawned-id]
  (when (and (map? join-state) (contains? join-state :children))
    {:parent-id       (:rf/parent-id args)
     :invoke-id       (:rf/spawn-all-id args)
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
                        (rf.machines.reply/actor-generation spawned-id))}))

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
  with the exact-attempt coordinate the parent's join interceptor verifies
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
    (join-child-record-from-state
      (get-in runtime-db (rf.machines.paths/spawned-path (:rf/parent-id args) invoke-id))
      args spawned-id)))

(defn- stamp-spawn-spec
  "Apply the spawn's `:data` override and the framework's `:rf/self-id` /
  `:rf/parent-id` / `:rf/invoke-id` / `:rf/join-child` stamps to an
  ALREADY-RESOLVED TYPE spec, yielding the exact spec a spawn of `args` would
  install. Returns nil for a nil `spec`.

  Split out from `candidate-spawn-spec` (rf2-rxjy3) so `prepare-spawn-all-child`
  can resolve the child's TYPE spec ONCE and retain BOTH derivatives: the
  stamped spec the install lands, and the RAW resolved definition the install
  needs to keep the child handler-resolvable (see `prepared-type-ref`). Without
  the split the preflight would have to resolve the registry a second time to
  recover the raw definition."
  [spec args spawned-id join-child]
  (let [spec' (if (and spec (contains? args :data))
                (assoc spec :data (:data args))
                spec)]
    (stamp-framework-data spec' spawned-id
                          (:rf/parent-id args)
                          (:rf/invoke-id args)
                          join-child)))

(defn- candidate-spawn-spec
  "The fully framework-stamped machine spec a spawn of `args` WOULD install:
  the resolved TYPE spec (registry `:machine-id` xor inline `:definition`),
  the args' materialised `:data` override applied, and the framework's
  `:rf/self-id` / `:rf/parent-id` / `:rf/invoke-id` / `:rf/join-child` keys
  stamped into `:data`. Returns nil when the type resolves to no spec (an
  unregistered `:machine-id` — the `unregistered-spawn-type?` gate's child).

  The SINGLE source of truth shared by `spawn-all-init-fx`'s invoke-level
  PREFLIGHT and `spawn-fx*`'s per-child install (rf2-7u8gen), so the two can
  never disagree about the value that gets schema-validated. Pure: the
  transition reducer already materialised any user `:data` fn into a VALUE on
  the args (`spawn-one`), so this evaluates no application code and allocates
  no id — it may be called on both paths without double-running user code."
  [args spawned-id join-child]
  (stamp-spawn-spec (resolve-spawn-machine args) args spawned-id join-child))

;; The spawned actor's initial snapshot is built by
;; `rf.machines.parallel/build-initial-snapshot` — the single source of truth shared
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
  handler, no actor state, no phantom `(rf.machines/machines)` entry).

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
       (false? (rf.machines.data-validation/validate-spawn-data!
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
  `rf.machines.spawn-order/record!` otherwise resolves to the CURRENT incarnation B.

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
  snapshots are durable runtime-db state (EP-0001).

  `type-ref-fn` is a THUNK, not a value (rf2-zo5n9). The revertible TYPE
  reference a PREPARED `:spawn-all` child stamps is registrar-DERIVED —
  `prepared-type-ref` compares the prepared definition against what the
  registrar currently holds — so the moment it is chosen is a correctness
  property, not a scheduling detail. Forcing it here, inside the `(continue?)`
  fence and immediately before the physical write, is the LAST point ahead of
  the swap, so every pre-install emission and every in-drain mutation it may
  have provoked is already reflected in the reference this stamps.

  Note the premise has NARROWED (rf2-wxy1c). The pre-install emissions named
  above — the caller's `:rf.machine.spawn/spawned` trace and this fn's own
  `:rf.error/system-id-collision` trace — no longer fan synchronously to
  application listeners: trace listeners are OBSERVERS, and internal
  drain-owned emits deliver at the POST-DRAIN boundary, so no listener body
  can unregister or replace the child's TYPE between the caller's bindings and
  this write. That window is now closed BY CONSTRUCTION. This placement is kept
  regardless: it costs nothing, it is the honest place to read a registrar-derived
  value, and it remains load-bearing for in-drain application code that CAN run
  ahead of the write — a prepared child's own `[:schemas :data]` validator runs
  after its `:type-spec` was retained, so the definition-lifetime rule must still
  decide against the registrar as it stands at COMMIT."
  [frame-id rt-after-alloc spec spawned-id initial-snap
   {:keys [system-id parent-id invoke-id track? type-ref-fn continue? owner-token]}]
  (let [existing (when system-id (get-in rt-after-alloc (rf.machines.paths/system-id-path system-id)))]
    (when (and system-id existing (not= existing spawned-id))
      (rf.trace/emit-error! :rf.error/system-id-collision
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
      (let [;; rf2-zo5n9 — CHOOSE the revertible TYPE reference HERE, at the last
            ;; point before the write, and stamp it onto the snapshot root so the
            ;; lazy resolver can re-materialise the handler from runtime-db alone.
            ;;
            ;; THE WINDOW THIS CLOSES. rf2-rxjy3 made a prepared `:spawn-all`
            ;; child's reference follow the definition-lifetime rule — keep the
            ;; `:machine-id` keyword while the registrar still holds the prepared
            ;; definition, pin that definition once the registrar has diverged —
            ;; but the CHOICE was taken in `spawn-fx*`'s `let` bindings, ahead of
            ;; the two emissions that still run before this write: the caller's
            ;; `:rf.machine.spawn/spawned` trace and the
            ;; `:rf.error/system-id-collision` trace above. Those then fanned
            ;; synchronously to application listeners, so a listener that
            ;; UNREGISTERED or REPLACED the admitted child's TYPE diverged the
            ;; registrar AFTER `prepared-type-ref` had observed it intact and
            ;; returned the keyword — the install stamped a now-STALE keyword and
            ;; the child came up INERT (nothing resolves; it never leaves
            ;; `:initial`, and every event raises `:rf.error/no-such-handler`) or
            ;; SPLIT (a prepared-v1 snapshot driven by an unrelated current-v2
            ;; handler). The very failure modes rxjy3 removed, reached through a
            ;; later door.
            ;;
            ;; rf2-wxy1c CLOSED that door from the other side: those two emits are
            ;; internal and drain-owned, so their listeners now run at the
            ;; post-drain boundary and no listener body can act here at all. The
            ;; late force is retained because it is still the correct reading
            ;; point for a registrar-derived value, and because in-drain
            ;; application code — notably a prepared child's own
            ;; `[:schemas :data]` validator, which `prepare-spawn-all-child` runs
            ;; AFTER retaining `:type-spec` — can still diverge the registrar
            ;; ahead of this write.
            ;;
            ;; Deferring the CHOICE — rather than re-checking anything — is the
            ;; whole fix: `type-ref-fn` reads the registrar as it stands at
            ;; commit, so whatever the last pre-install callback did to it is
            ;; what the definition-lifetime rule decides against. It is NOT a
            ;; re-verdict: an admitted child ALWAYS installs (rf2-v4oqd), and
            ;; this can only select the FORM of its reference.
            type-ref     (type-ref-fn)
            ;; The spawn is known-accepted by the time `install-spawn!` runs (an
            ;; unregistered `:machine-id` was rejected fail-closed upstream), so
            ;; `spec` is always present; the `spec`/`type-ref` guards are
            ;; belt-and-braces.
            initial-snap (cond-> initial-snap
                           (and spec type-ref) (assoc :rf/machine-type type-ref))
            install-fn (fn [_rt]
                         (cond-> rt-after-alloc
                           spec      (assoc-in (rf.machines.paths/snapshot-path spawned-id) initial-snap)
                           ;; rf2-1vlyg — append the actor to the DURABLE
                           ;; spawn-order vector in the SAME swap that lands its
                           ;; snapshot, so the frame's total creation order is
                           ;; recorded rather than reconstructed. The per-prefix
                           ;; `#<n>` suffix of `spawned-id` cannot order actors
                           ;; of different machine types and is absent entirely
                           ;; on a `:fixed-actor-id`, so frame destroy has no
                           ;; other durable fact to read; the transient
                           ;; `rf.machines.spawn-order/record!` below is a cache that any
                           ;; restore / hydration / `replace-runtime-db!` wipes.
                           ;; Gated on the same `spec` as the snapshot assoc: the
                           ;; order tracks exactly the actors that have snapshots.
                           spec      (rf.machines.spawn-order/record-in-runtime-db spawned-id)
                           system-id (assoc-in (rf.machines.paths/system-id-path system-id) spawned-id)
                           track?    (assoc-in (rf.machines.paths/spawned-path parent-id invoke-id) spawned-id)))
            written    (if owner-token
                         (rf.frame/swap-runtime-db-exact! frame-id owner-token install-fn)
                         (rf.frame/swap-runtime-db! frame-id install-fn))]
        (if (some? written)
          (do
            (when system-id
              (rf.trace/emit! :rf.machine :rf.machine/system-id-bound
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

;; ---- authoritative :spawn-all prepared-children handoff -------------------

(def ^:private prepared-children-key
  "The reserved join-state slot under which `spawn-all-init-fx`'s admission
  preflight retains the AUTHORITATIVE prepared per-child result on the accept
  path (rf2-ek435): a `{<spawned-id> {:spec <stamped-spec> :snap <initial-snap>
  :type-spec <raw-resolved-definition>}}` map, one entry per admitted child.
  Each child's own `:rf.machine/spawn` fx —
  a SEPARATE entry later in the same entry vector — consumes its entry
  (`spawn-all-prepared-child`) rather than re-resolving the type, rebuilding the
  snapshot, or re-running its `[:schemas :data]` validator. Consuming rather than
  recomputing is what makes the preflight's verdict authoritative: a type
  re-registration or a second validator pass between the preflight and the
  install can no longer flip an admitted child into a rejected one (which would
  strand an impossible half-live join), and each child is resolved / prepared /
  validated EXACTLY ONCE per attempt.

  `:type-spec` — the RAW resolved definition, before the `:data` override and
  the framework stamps — is what keeps the admitted child HANDLER-RESOLVABLE
  (rf2-rxjy3). Consuming the verdict installs the child's snapshot, but the
  actor's HANDLER is materialised lazily from the snapshot's `:rf/machine-type`
  reference, so a mid-drain registry mutation could still leave an installed
  child pointing at a definition that is gone (or at an unrelated successor).
  Carrying the prepared definition alongside the prepared snapshot is what lets
  `prepared-type-ref` keep the two in one authority.

  The slot is EPHEMERAL install-time scratch, not durable join state: each
  consuming child drops its own entry inside the SAME runtime-db swap that
  installs its snapshot (`drop-prepared-entry`), and the key vanishes once the
  last child has installed — so the join state the `join.cljc` interceptor and
  epoch capture see carries no scratch. It rides under a reserved `:rf/*` key,
  is never application-facing, and — unlike the retired public boolean verdict —
  cannot be forged: the value the install lands is the framework-built snapshot,
  not a re-derivation a public flag could bypass."
  :rf/prepared)

(defn- spawn-all-prepared-child
  "The framework-owned prepared result `spawn-all-init-fx` retained for THIS
  `:spawn-all` child (rf2-ek435), read from the join slot's `:rf/prepared`
  scratch by the child's own spawned-id. Returns `{:spec <stamped-spec>
  :snap <initial-snap>}` for an admitted `:spawn-all` child, or nil when there
  is no invoke (`:rf/spawn-all-id` absent — a plain single `:spawn`) or no
  prepared entry (a hand-emitted `:spawn-all` child fx with no preceding init
  fx, which falls back to the resolve/build/validate path). `spawn-all-init-fx`
  is the FIRST fx in the entry vector, so on the normal declarative path the
  entry is always present by the time this child's spawn fx runs."
  [runtime-db args spawned-id]
  (when-let [invoke-id (:rf/spawn-all-id args)]
    (get-in runtime-db (conj (rf.machines.paths/spawned-path (:rf/parent-id args) invoke-id)
                             prepared-children-key spawned-id))))

(defn- drop-prepared-entry
  "Remove THIS child's consumed `:rf/prepared` entry from the join slot, and
  drop the `:rf/prepared` key entirely once the last admitted child has
  consumed its entry — so the durable join state carries no install-time
  scratch (rf2-ek435). Applied to the install's `rt-after-alloc` base so the
  drop rides the SAME runtime-db swap that lands the snapshot; a no-op if the
  join slot is not a live join (belt-and-braces)."
  [runtime-db args spawned-id]
  (update-in runtime-db (rf.machines.paths/spawned-path (:rf/parent-id args) (:rf/spawn-all-id args))
             (fn [join-state]
               (if (map? join-state)
                 (let [remaining (dissoc (get join-state prepared-children-key) spawned-id)]
                   (if (seq remaining)
                     (assoc join-state prepared-children-key remaining)
                     (dissoc join-state prepared-children-key)))
                 join-state))))

(defn- spawn-all-prepared?
  "True iff THIS `:spawn-all` per-child carries an AUTHORITATIVE prepared entry
  its invoke's `spawn-all-init-fx` preflight retained (rf2-ek435), keyed by the
  child's pre-allocated spawned-id in the live join slot's `:rf/prepared`
  scratch. `spawn-fx` consults this to CONSUME the preflight verdict rather than
  re-running its child-local `unregistered-spawn-type?` registry recheck against
  an already-ADMITTED+prepared child (rf2-v4oqd): a
  `:rf.machine.spawn-all/started` listener that UNREGISTERED the child TYPE
  between the preflight and this per-child install would otherwise flip the
  admitted child to rejected, leaving prepared scratch and no child snapshot —
  the impossible half-live join (a live join naming a child whose snapshot a
  second verdict omitted) the authoritative handoff exists to make impossible.
  Re-registering to another still-present type was already covered (the recheck
  saw a spec and passed through), but UNREGISTERING was not; consuming the
  prepared verdict closes both, and each admitted child is resolved / prepared /
  validated EXACTLY once.

  Returns false for a standalone single `:spawn` (no `:rf/spawn-all-id`) and a
  hand-emitted `:spawn-all` child fx with no preceding init fx (no prepared
  entry): both keep the child-local `unregistered-spawn-type?` fail-closed gate."
  [frame-id args]
  (some? (spawn-all-prepared-child
           (rf.frame/frame-runtime-db-value frame-id)
           args
           (pre-allocated-actor-id args))))

(defn- prepared-type-ref
  "The revertible TYPE reference an ADMITTED+prepared `:spawn-all` child's
  install stamps at `:rf/machine-type` — the slot the lazy resolver
  (`lifecycle-fx.resolver/spec-from-snapshot`) reads to re-materialise the
  actor's handler on every dispatch (rf2-rxjy3).

  THE WINDOW THIS CLOSES. Consuming the prepared verdict (rf2-v4oqd) installs an
  admitted child's snapshot unconditionally — but the snapshot alone is not a
  LIVE actor. A `:machine-id` spawn stamps the registered TYPE KEYWORD, and the
  resolver reads that keyword back through the registrar on every dispatch. So a
  `:rf.machine.spawn-all/started` listener that mutates the registrar between the
  preflight and this install left the child installed-but-INERT: UNREGISTERED
  the TYPE and the keyword resolves to nothing — the child never runs its
  synthetic `[:rf.machine.spawn/spawned]` bootstrap, sits at its `:initial` with
  `:rf/bootstrap-pending? true`, and every event it is sent raises
  `:rf.error/no-such-handler`; RE-REGISTERED the TYPE to another spec and the
  keyword resolves to a definition the child's PREPARED snapshot was never built
  from — a prepared-v1 state driven by an unrelated current-v2 handler.

  THE DEFINITION-LIFETIME RULE. A prepared child's definition authority is the
  definition its invoke PREPARED (`:type-spec`, retained by
  `prepare-spawn-all-child`). This returns:

    - the `:machine-id` KEYWORD while the registrar still holds EXACTLY that
      definition — the reference is equivalent to the definition, so nothing
      changes: the child stays a normal registry-tracking actor and ordinary
      HOT-RELOAD semantics are preserved (re-registering the TYPE later reaches
      every live child, exactly as it does for a single `:spawn`);
    - the prepared definition VERBATIM once the registrar has DIVERGED from it,
      pinned onto the snapshot exactly as an inline `:definition` spawn carries
      its own spec — fully revertible, resolvable with no registrar entry at
      all, and coherent with the prepared snapshot installed beside it.

  NOT a re-verdict (rf2-v4oqd is PRESERVED). The registry read here decides only
  the FORM of the reference; it can never reject, suppress, or alter the child's
  admission. An admitted child ALWAYS installs — that is exactly what v4oqd
  established, and re-instating a fail-closed recheck here would restore the
  impossible half-live join it removed. The divergent case simply stops
  depending on a registrar entry the preflight's verdict no longer speaks for.

  WHEN this runs is part of the contract (rf2-zo5n9). Because the rule is
  decided by comparing the prepared definition against the registrar's CURRENT
  contents, a divergence that happens after the comparison is a divergence the
  installed snapshot does not reflect — and the callbacks between the child's
  admission and its install (the `:rf.machine.spawn/spawned` trace, and
  `install-spawn!`'s `:rf.error/system-id-collision` trace) are exactly where a
  listener can cause one. `spawn-fx*` therefore passes this as a THUNK and
  `install-spawn!` forces it at the last point before the runtime-db swap, so
  the comparison is made against the registrar as it stands at COMMIT."
  [args prepared]
  (let [type-spec  (:type-spec prepared)
        machine-id (:machine-id args)]
    (cond
      ;; Belt-and-braces: a prepared child always resolved to a definition (an
      ;; unregistered TYPE is rejected at the preflight), so this is unreachable
      ;; on the declarative path.
      (nil? type-spec)
      (machine-type-ref args)

      ;; The registrar still holds the prepared definition — keep the revertible
      ;; keyword so the child tracks its TYPE like every other spawned actor.
      (and (some? machine-id)
           (= type-spec (rf.machines.lifecycle-fx.resolver/spec-from-registry machine-id)))
      machine-id

      ;; Diverged (unregistered, or replaced by another spec) — or an inline
      ;; `:definition` child, which has no TYPE keyword to track. Pin the
      ;; prepared definition.
      :else
      type-spec)))

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
        frame-id   (rf.frame/require-frame-stamp!
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
      ;;
      ;; rf2-v4oqd — but an ADMITTED+prepared `:spawn-all` child CONSUMES its
      ;; invoke's authoritative preflight verdict: skip this registry recheck
      ;; when a keyed `:rf/prepared` entry exists (`spawn-all-prepared?`), so a
      ;; `:rf.machine.spawn-all/started` listener that unregistered the child
      ;; TYPE between the preflight and this install can no longer flip the
      ;; already-admitted child to rejected (stranding a half-live join whose
      ;; snapshot the recheck omitted). `spawn-fx*` then installs the exact
      ;; prepared spec + snapshot. The gate still fires for a standalone
      ;; `:spawn` and a no-preparation hand-emitted child (neither has a
      ;; prepared entry).
      (and (not (spawn-all-prepared? frame-id args))
           (unregistered-spawn-type? args))
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
        continue?  (rf.machines.data-validation/owner-continuation frame-id)
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
        owner-token (rf.frame/current-event-owner-token)
        ;; Prefer the pre-allocated id (declarative :spawn
        ;; routes through the transition reducer which bumps the parent
        ;; snapshot's `:rf/spawn-counter`). Hand-emitted spawn fxs carry
        ;; no pre-allocated id; the frame's runtime-db spawn-counter slot
        ;; at `[:rf.runtime/machines :spawn-counter]` serves
        ;; as the fallback allocator, bumped inside the same db-swap as
        ;; the snapshot install / registry bind below.
        pre-id     (pre-allocated-actor-id args)
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
        ;; read is consistent because `rf.frame/swap-runtime-db!` is the only
        ;; writer during fx drain (Spec 002 §Single drainer per frame).
        ;; Machine snapshots are durable runtime-db state.
        old-rt     (rf.frame/frame-runtime-db-value frame-id)
        machine-id-for-alloc (or (:id-prefix args) (:machine-id args))
        [rt-after-alloc-raw spawned-id]
        (cond
          pre-id        [old-rt pre-id]
          (and old-rt machine-id-for-alloc)
                        (allocate-actor-id-in-runtime-db old-rt machine-id-for-alloc)
          :else         [old-rt nil])
        ;; A `:spawn-all` child consumes the AUTHORITATIVE prepared result its
        ;; invoke's `spawn-all-init-fx` preflight resolved, stamped, built, and
        ;; validated ONCE (rf2-ek435) — never re-resolving the type, rebuilding
        ;; the snapshot, or re-running its `[:schemas :data]` validator here. So
        ;; a type re-registration or a second validator pass BETWEEN the
        ;; preflight and this install can no longer flip an admitted child into
        ;; a rejected one (which stranded an impossible half-live join naming a
        ;; child whose snapshot a second verdict omitted), and the validator
        ;; runs exactly once per attempt. nil for a plain single `:spawn` or a
        ;; hand-emitted child with no preceding init fx — those keep the
        ;; resolve/build/validate path below.
        prepared   (spawn-all-prepared-child old-rt args spawned-id)
        ;; Consuming the entry drops it from the join slot's `:rf/prepared`
        ;; scratch in the SAME runtime-db swap that installs the snapshot, so
        ;; the durable join state carries none once every child has installed.
        rt-after-alloc (if prepared
                         (drop-prepared-entry rt-after-alloc-raw args spawned-id)
                         rt-after-alloc-raw)
        ;; The stamped spec this install lands. For a `:spawn-all` child it is
        ;; the framework-owned spec the preflight already resolved + stamped
        ;; (consumed, not recomputed); otherwise it is built through the SAME
        ;; `candidate-spawn-spec` path the preflight uses, so the fallback
        ;; per-child verdict is still decided over an identical value.
        spec''     (if prepared
                     (:spec prepared)
                     (candidate-spawn-spec args spawned-id
                                           (join-child-record old-rt args spawned-id)))
        ;; The revertible TYPE reference the lazy resolver reads back off the
        ;; installed snapshot to re-materialise the actor's handler. A prepared
        ;; `:spawn-all` child selects it from its prepared entry so it can never
        ;; install against a definition the registrar no longer holds, or against
        ;; an unrelated successor definition its prepared snapshot was not built
        ;; from (rf2-rxjy3 — see `prepared-type-ref` for the definition-lifetime
        ;; rule; the undisturbed case still stamps the plain `:machine-id`
        ;; keyword, so hot-reload semantics are unchanged).
        ;;
        ;; rf2-zo5n9 — a THUNK, deliberately unforced here. `prepared-type-ref`
        ;; is the one registrar-DERIVED input the install still needs, so it is
        ;; read as late as possible: `install-spawn!` forces it at the last point
        ;; before the swap, and the definition-lifetime rule therefore decides
        ;; against the registrar as it stands at COMMIT rather than at this
        ;; binding.
        ;;
        ;; This originally guarded against TRACE LISTENERS — the
        ;; `:rf.machine.spawn/spawned` trace below and `install-spawn!`'s
        ;; `:rf.error/system-id-collision` trace fanned synchronously to
        ;; application listeners that could unregister or replace the child's
        ;; TYPE mid-drain. rf2-wxy1c retired that premise: both are internal
        ;; drain-owned emits, delivered at the post-drain boundary, so no
        ;; listener body runs between here and the write. The thunk stays because
        ;; the late read is still correct and still load-bearing for ordinary
        ;; in-drain application code — a prepared child's `[:schemas :data]`
        ;; validator runs after `prepare-spawn-all-child` retained `:type-spec`,
        ;; so a registrar it mutates must be the one the rule sees.
        ;; `machine-type-ref` is pure over `args` and reads no registrar, so the
        ;; non-prepared path is timing-independent either way.
        type-ref-fn (if prepared
                      #(prepared-type-ref args prepared)
                      (constantly (machine-type-ref args)))
        ;; The initial snapshot the install lands. Consumed verbatim from the
        ;; preflight for a `:spawn-all` child (built ONCE there); built ONCE
        ;; here otherwise, so the schema-rejection decision can gate every side
        ;; effect below and `install-spawn!` threads the same value.
        initial-snap (if prepared
                       (:snap prepared)
                       (when spec''
                         (rf.machines.parallel/build-initial-snapshot
                           spec'' {:bootstrap-pending? true})))
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
        ;;
        ;; A `:spawn-all` child that reached here was ADMITTED by the
        ;; authoritative preflight (a rejected invoke is suppressed upstream by
        ;; `spawn-all-invoke-rejected?` before `spawn-fx*` runs), and its
        ;; validator ALREADY ran once there — so it is never re-validated here.
        rejected?  (if prepared
                     false
                     (spawn-rejected? spec'' spawned-id initial-snap continue?))]
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
      (rf.trace/emit! :rf.machine :rf.machine.spawn/spawned
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
                                         :type-ref-fn type-ref-fn
                                         :continue?   continue?
                                         :owner-token owner-token})]
        ;; rf2-hloj0g — `install-spawn!`'s `:rf.error/system-id-collision`
        ;; (pre-swap → `:skipped`) and `:rf.machine/system-id-bound` (post-swap)
        ;; traces are callback-bearing: a listener can destroy A / publish
        ;; same-id B on the trace's own stack. Run the framework-owned tail —
        ;; per-instance classification, the spawn-order record, and the
        ;; `:rf.machine.lifecycle/spawned` trace — ONLY when install COMMITTED
        ;; AND the exact owner is STILL current after those callbacks. Otherwise
        ;; the bare-id `rf.machines.spawn-order/record!` / classification writes + the
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
        (rf.machines.classification/lower-at-spawn! frame-id spawned-id spec'' owner-token)
        ;; rf2-rbxdxa — `lower-at-spawn!` writes through the EXACT elision swap, a
        ;; container-write boundary: a synchronous watch can destroy A / publish
        ;; same-id B DURING the classification lowering. Recheck `(continue?)`
        ;; AFTER it before the spawn-order record / lifecycle trace / `:start`
        ;; dispatch tail — otherwise the bare-id `rf.machines.spawn-order/record!` records A's
        ;; ghost child into B's spawn-order channel and the lifecycle-spawned
        ;; trace / bootstrap dispatch land against B. The install itself is already
        ;; exact (`install-spawn!`); this fences the classification-lowering seam
        ;; the earlier `(= :committed installed) (continue?)` gate ran ahead of.
        (when (continue?)
        ;; Record the spawned actor in the frame's spawn-order channel so
        ;; frame-destroy can walk in reverse-creation order per Spec 005
        ;; §Cross-Spec Interactions §1.
        (rf.machines.spawn-order/record! frame-id spawned-id)
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
        (rf.trace/emit! :rf.machine.lifecycle/spawned :rf.machine.lifecycle/spawned
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
        (when-let [dispatch! (rf.late-bind/get-fn :router/dispatch!)]
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
            (dispatch! [spawned-id [:rf.machine.spawn/spawned]] opts))))))))))
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

(defn- prepare-spawn-all-child
  "The AUTHORITATIVE single preparation of ONE `:spawn-all` per-child spawn for
  an attempt (rf2-ek435 / rf2-7u8gen). Resolves the child's TYPE, stamps its
  framework `:data`, builds its initial snapshot, and runs its `[:schemas :data]`
  validator EXACTLY ONCE — against the join-state about to be seeded — and
  returns the prepared result the per-child install then consumes verbatim:

    {:args <spawn-args> :spawned-id <id> :spec <stamped-spec> :snap <initial-snap>
     :type-spec <raw-resolved-definition>
     :unregistered? <bool> :schema-reject? <bool> :rejected? <bool>}

  The child is resolved ONCE — `resolve-spawn-machine` below — and BOTH
  derivatives are retained: `:spec`, the framework-stamped spec the install
  lands, and `:type-spec`, the RAW definition that resolution returned. The raw
  definition is what keeps an admitted child HANDLER-resolvable when the
  registrar mutates between here and the install (rf2-rxjy3 — the install's
  `prepared-type-ref` pins it onto the snapshot once the registrar has diverged
  from it, so the actor's lazily-materialised handler and its prepared snapshot
  stay one authority).

  `resolve-spawn-machine` returns nil exactly when
  the TYPE is unregistered (an inline `:definition` always resolves to itself),
  so `:unregistered?` is derived from that one resolution rather than a second
  registry read. `:schema-reject?` runs the application `[:schemas :data]`
  validator (via `spawn-rejected?`), emitting that child's
  `:rf.error/schema-validation-failure :phase :spawn` exactly once as it
  decides. The two reject reasons are DISJOINT — an unregistered TYPE resolves
  to no spec, so it can never also be a schema reject — so each offending child
  is reported under exactly one of them.

  The validator is application code fenced to A's exact incarnation via
  `continue?`; the invoke-level caller rechecks `(continue?)` after the whole
  preflight before any durable write.

  `:spawned-id` is nil (nothing prepared) for a child with no pre-allocated id:
  its `:rf/self-id` stamp — and therefore its `:data` — is unknowable until an
  install allocates one, so there is nothing faithful to prepare. The transition
  reducer pre-allocates every declarative `:spawn-all` child; the runtime-db
  fallback allocator serves only hand-emitted `:rf.machine/spawn` fxs, which
  belong to no invoke."
  [args join-state continue?]
  (let [spawned-id (pre-allocated-actor-id args)
        ;; ONE resolution, feeding the verdict, the snapshot the install
        ;; consumes, AND the definition reference the install stamps.
        type-spec  (when spawned-id (resolve-spawn-machine args))
        ;; The framework stamp runs through the SAME `stamp-spawn-spec` path the
        ;; fallback per-child install's `candidate-spawn-spec` runs, so the two
        ;; can never disagree about the value that gets validated.
        spec       (when spawned-id
                     (stamp-spawn-spec
                       type-spec args spawned-id
                       (join-child-record-from-state join-state args spawned-id)))
        ;; Unregistered ⟺ a `:machine-id` (no inline `:definition`) that resolved
        ;; to no spec — read off the SAME resolution above, not a second lookup.
        unregistered? (boolean (and (some? spawned-id)
                                    (some? (:machine-id args))
                                    (nil? (:definition args))
                                    (nil? spec)))
        snap       (when spec
                     (rf.machines.parallel/build-initial-snapshot spec {:bootstrap-pending? true}))
        schema-reject? (boolean (and spec (some? spawned-id)
                                     (spawn-rejected? spec spawned-id snap continue?)))]
    {:args           args
     :spawned-id     spawned-id
     :spec           spec
     :snap           snap
     :type-spec      type-spec
     :unregistered?  unregistered?
     :schema-reject? schema-reject?
     :rejected?      (or unregistered? schema-reject?)}))

(defn- spawn-all-address-collisions
  "Detect `:spawn-all` children whose RESOLVED actor addresses ALIAS
  (rf2-qlzh9). `:spawn-all` permits two DISTINCT logical children to resolve to
  the SAME actor id — a `:fixed-actor-id` literal shared by two children, or a
  fixed id colliding with a generated `<type>#n` — because registration guards
  only LOGICAL `:id` uniqueness (`validate-spawn-all!`), not the resolved
  address. Such a set would store as `{<spawned-id> prepared}` that SILENTLY
  overwrites one entry / one live actor: the first child consumes the other's
  spec/snapshot and drops the only entry, while the later child falls back to a
  second resolution/validator verdict.

  Reads the RAW per-child spawn args, NOT prepared children (rf2-ri19s). An
  address is `pre-allocated-actor-id` — the child's own `:fixed-actor-id` or the
  `:rf/spawned-id` the transition reducer stamped — so aliasing is a purely
  STRUCTURAL property of the args, decidable without resolving a TYPE, building a
  snapshot, or running an application `[:schemas :data]` validator. That is what
  lets the caller decide it FIRST: an already-aliased batch is rejected before
  any of that work is done or any application callback is reached.

  Returns a deterministic vector of `[<resolved-address> [<logical-child-id> …]]`
  pairs, one per ALIASED address (2+ children), or an empty vector when every
  resolved address is unique. Order follows first appearance in `child-args` (the
  transition reducer's declaration order), stable across CLJ and CLJS — never map
  hash order — so the reject the caller fans is deterministic."
  [child-args]
  (let [addressed (into []
                        (keep (fn [args]
                                (when-let [addr (pre-allocated-actor-id args)]
                                  [addr (:rf/spawn-all-child-id args)])))
                        child-args)
        by-addr   (group-by first addressed)]
    (into []
          (comp (map first)
                (distinct)
                (filter #(> (count (get by-addr %)) 1))
                (map (fn [addr] [addr (mapv second (get by-addr addr))])))
          addressed)))

(defn- write-spawned-slot!
  "Write `value` into the frame's join slot at
  `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]`, bound to A's RAW
  owner token (rf2-8nxsh). `spawn-all-init-fx` reaches this write only AFTER
  its admission preflight has run application `[:schemas :data]` validators and
  (on the reject path) fanned callback-bearing reject records — either of which
  can synchronously destroy owner frame A and publish a same-id successor B on
  its own stack. A bare-id `rf.frame/swap-runtime-db!` resolves to the CURRENT
  incarnation, so it would land A's join-state / reject sentinel on B (and bump
  B's commit epoch), leaving B holding an IMPOSSIBLE join it never spawned.

  With an event owner, route the write through `swap-runtime-db-exact!`: it
  binds the install to A's own container, so a same-id B can never redirect it,
  and on mid-write owner loss (a synchronous container watch that destroys A
  during the physical install) it returns nil WITHOUT bumping B's epoch — B
  stays byte-identical. Without an owner (a conformance / pure-fn caller with no
  router event owner), fall back to the historical bare-id write; that path has
  no incarnation to lose, symmetric with `continue?`'s `(constantly true)`.
  Returns the new runtime-db slice, or nil on owner loss."
  [frame-id owner-token parent-id invoke-id value]
  (let [swap-fn #(assoc-in % (rf.machines.paths/spawned-path parent-id invoke-id) value)]
    (if owner-token
      (rf.frame/swap-runtime-db-exact! frame-id owner-token swap-fn)
      (rf.frame/swap-runtime-db! frame-id swap-fn))))

(defn- seed-reject-sentinel!
  "Seed the childless `spawn-all-reject-sentinel` at the invoke's join slot,
  making a rejected `:spawn-all` ATOMIC (rf2-qb1j5z): every per-child
  `:rf.machine/spawn` fx later in THIS entry vector reads it
  (`spawn-all-invoke-rejected?`) and suppresses itself, so a rejected invoke
  spawns NOTHING rather than orphaning registered siblings under no live join.

  Fenced on `(continue?)` and bound to A's raw owner token (rf2-8nxsh): the
  reject path fans callback-bearing records whose listeners can synchronously
  destroy owner frame A and publish a same-id successor B, and no A-derived
  sentinel may land on B. Returns nil — the fx's reject-path return value.

  Shared by BOTH reject branches (rf2-ri19s): the structural alias reject, which
  short-circuits ahead of preparation, and the prepared-child reject for an
  unregistered TYPE / schema rejection."
  [frame-id owner-token parent-id invoke-id continue?]
  (when (continue?)
    (write-spawned-slot! frame-id owner-token parent-id invoke-id
                         spawn-all-reject-sentinel))
  nil)

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
     :rf/attempt <opaque-attempt-token>   ;; minted HERE per live seed (rf2-nvxehu)
     :rf/prepared {<spawned-id> {:spec      <stamped-spec>
                                 :snap      <initial-snap>
                                 :type-spec <raw-resolved-definition>}, ...}}
       ;; EPHEMERAL — the authoritative prepared children each per-child install
       ;; CONSUMES then drops (rf2-ek435); gone once every child has installed.
       ;; `:type-spec` is the definition reference the install stamps at
       ;; `:rf/machine-type`, keeping the admitted child handler-resolvable
       ;; across a mid-drain registrar mutation (rf2-rxjy3).

  Subsequent `:on-child-done` / `:on-child-error` events arrive at the
  parent's `make-machine-handler` boundary and are intercepted by
  `intercept-spawn-all-event` (in `lifecycle-fx.join`).

  This fx fires FIRST in the entry `:fx` vector — BEFORE the per-child
  `:rf.machine/spawn` fxs — and its PREFLIGHT is the AUTHORITATIVE decision
  for every fail-closed child-admission condition. If ANY child in the set
  fails admission, the WHOLE invoke is REJECTED ATOMICALLY here: instead of a
  live child-bearing join state, this fx seeds `spawn-all-reject-sentinel` at
  the join slot — a slot that is PHYSICALLY present in runtime-db but carries
  NO `:children`, so it is a reject marker, NOT a live join.

  Three admission conditions, all decided over the per-child spawn args
  (`:child-args`) before anything is published — STRUCTURE first, then the
  conditions that need resolution / application callbacks (rf2-ri19s):

   0. **Resolved-address ALIASING** — rf2-qlzh9. Two DISTINCT logical children
      resolving to ONE actor address (a shared `:fixed-actor-id`, or a fixed id
      colliding with a generated `<type>#n`) would collapse to one
      `:rf/prepared` entry / one live actor — a silent overwrite that leaves an
      `:all` join waiting forever on the child that never installed.
      Registration guards only LOGICAL `:id` uniqueness, so this is a runtime
      condition. It is checked FIRST and short-circuits: an address is read
      straight off the args (`pre-allocated-actor-id`), so it needs no TYPE
      resolution, no snapshot, and no application validator — and an
      already-aliased batch must not pay for, nor be pre-empted by, any of them.
   1. **Unregistered child TYPE** (no inline `:definition`) — rf2-qb1j5z. A
      never-running spec-less child would never dispatch its
      `:on-child-done`, blocking an `:all` join FOREVER (`join.cljc`
      `(= n-done n-total)` can never hold).
   2. **Spawn-time `[:schemas :data]` rejection** — rf2-7u8gen. Validation
      used to run only inside each per-child spawn, so a mixed
      valid/schema-invalid invoke published a live join naming EVERY child,
      installed the valid siblings, and left the rejected child in that join
      forever: it has no snapshot and can never emit completion, so the
      parent waits on it eternally while its valid siblings are real live
      actors owned by an impossible join. That is the same dead-join/orphan
      class the unregistered-type sentinel exists to remove, and it violates
      Spec 005's promise that a parent never observes a half-installed child.
      Preflighting it HERE composes the two guarantees.

  (1) and (2) are read off the PREPARED children — one
  `prepare-spawn-all-child` per child, resolving its TYPE, building its
  snapshot, and running its validator exactly once. Only a batch that cleared
  (0) is prepared at all, so a structurally invalid invoke fans exactly one
  diagnostic (its alias) and reaches no application code.

  An all-valid invoke keeps the existing fast path. Registration-time SHAPE
  rejection stays where it already belongs (`reg-machine`).
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
  finds nothing to tear down and clears the sentinel on parent exit.

  EXACT-INCARNATION FENCE (rf2-8nxsh). The preflight's `[:schemas :data]`
  validators are application code, and the reject path fans callback-bearing
  always-on records + dev traces; either can synchronously destroy owner frame
  A and publish a same-id successor B on its own stack. Both durable writes —
  the live join AND the reject sentinel — therefore ride A's raw owner token
  through `write-spawned-slot!` (`swap-runtime-db-exact!`): a same-id B can
  never redirect the write, and a mid-write container watch that loses A no-ops
  it (nil return) without bumping B's epoch, so B stays byte-identical. `continue?`
  is rechecked after the preflight (before either write) and between reject
  emissions, and the `:rf.machine.spawn-all/started` trace fires only when the
  join actually committed into A. A validator / listener that swaps A for B thus
  lands no A-derived join, sentinel, or started trace on B."
  [{frame-id :frame} args]
  (let [;; EP-0002 carried invariant — the fx context carries the cascade
        ;; envelope frame; a nil stamp is an invariant failure
        ;; (`:rf.error/no-frame-context`), never a synthesised `:rf/default`.
        frame-id   (rf.frame/require-frame-stamp!
                     frame-id :rf.machine/spawn-all-init
                     {:where 'rf.machine/spawn-all-init
                      :event-id (:rf/parent-id args)})
        parent-id  (:rf/parent-id args)
        invoke-id  (:rf/invoke-id args)
        children   (:children (:join-state args))
        ;; The PREPARED per-child spawn args — the exact `[:rf.machine/spawn
        ;; args]` payloads the sibling fxs later in THIS entry vector will run,
        ;; threaded here by the transition reducer (this fx's single producer).
        ;; Preflighting THESE, rather than the raw invoke-specs off
        ;; `[:spec :children]`, is what lets the invoke-level decision see
        ;; everything a per-child effect would: the materialised `:data`, the
        ;; pre-allocated id, and hence the child's real `[:schemas :data]`
        ;; verdict (rf2-7u8gen).
        ;;
        ;; rf2-ek435 — re-stamp each child-arg's `:rf/spawn-all-id` (and
        ;; `:rf/parent-id`) to THIS fx's own `invoke-id` / `parent-id`. For a
        ;; PARALLEL-region invoke, `rf.machines.parallel/prefix-region-invoke-id` region-
        ;; prefixes the init-fx's top-level `:rf/invoke-id` AND every per-child
        ;; `:rf.machine/spawn` fx's top-level `:rf/spawn-all-id`, but NOT the
        ;; `:child-args` nested here — so their `:rf/spawn-all-id` is the
        ;; UN-prefixed path. The preflight now RETAINS the `:rf/join-child`
        ;; membership record built from these args (rf2-nvxehu `:invoke-id`), and
        ;; the per-child install consumes it, so that record must carry the SAME
        ;; region-prefixed invoke-id the per-child spawn fxs (and hence the join
        ;; slot) use — else a parallel child's completion coordinate names a slot
        ;; the region never seeded and its join never folds. `invoke-id` here IS
        ;; the region-prefixed value (the fx's own top-level `:rf/invoke-id`); in
        ;; the flat case this is a no-op (they already match).
        child-args (mapv #(assoc % :rf/parent-id parent-id
                                   :rf/spawn-all-id invoke-id)
                         (:child-args args))
        ;; Mint the attempt token BEFORE the preflight so the candidate
        ;; snapshots validated below carry the very `:rf/join-child` record the
        ;; per-child installs will stamp — identical values, identical
        ;; verdicts. A REJECTED invoke simply discards the token: it is an
        ;; opaque monotonic counter whose only contract is uniqueness, so a gap
        ;; costs nothing and a rejected invoke has no attempt to authenticate
        ;; against.
        join-state (assoc (:join-state args)
                          :cancelled #{}
                          :rf/attempt (next-join-attempt-token))
        continue?  (rf.machines.data-validation/owner-continuation frame-id)
        ;; rf2-8nxsh — the RAW exact owner token (`continue?` closes over the
        ;; same authority). The admission preflight below runs application
        ;; `[:schemas :data]` validators (`prepare-spawn-all-child`), and the
        ;; reject path fans callback-bearing always-on reject records + dev
        ;; traces (`reject-unregistered-spawn!`): each is application / listener
        ;; code that can synchronously destroy owner frame A and publish same-id
        ;; successor B. Binding the durable join / reject-sentinel write to A's
        ;; own token (`write-spawned-slot!` → `swap-runtime-db-exact!`) keeps B
        ;; byte-identical even under a mid-write container watch; nil for a
        ;; non-router caller falls back to the bare write, symmetric with
        ;; `continue?`'s `(constantly true)`.
        owner-token (rf.frame/current-event-owner-token)
        ;; ---- The invoke-level admission preflight ------------------------
        ;; ONE authoritative preparation per child, then ONE decision — both
        ;; taken BEFORE a live join or ANY child side effect is published
        ;; (rf2-qb1j5z for unregistered types, rf2-7u8gen for spawn-time schema
        ;; validity, rf2-ek435 for retaining the result). `prepare-spawn-all-child`
        ;; resolves each child's type, stamps its `:data`, builds its snapshot,
        ;; and runs its `[:schemas :data]` validator EXACTLY ONCE, returning the
        ;; prepared result. The accept path retains those prepared children so
        ;; each per-child install CONSUMES its resolved spec + built snapshot
        ;; rather than recomputing them — the preflight's verdict AND its
        ;; prepared set are authoritative, not re-derived downstream. The two
        ;; reject reasons are DISJOINT — an unregistered type resolves to no
        ;; spec, so it can never also be a schema reject — hence each offending
        ;; child is reported exactly once under exactly one of them.
        ;; Registration-time SHAPE rejection stays where it belongs
        ;; (`reg-machine`).
        ;;
        ;; rf2-qlzh9 — resolved actor-address ALIASING is a THIRD fail-closed
        ;; admission condition, alongside unregistered TYPE and spawn-time
        ;; `[:schemas :data]` rejection. Registration guards only LOGICAL `:id`
        ;; uniqueness, so two distinct logical children can resolve to the SAME
        ;; actor address (a shared `:fixed-actor-id`, or a fixed id colliding
        ;; with a generated `<type>#n`) and silently collapse to one
        ;; `:rf/prepared` entry / one live actor.
        ;;
        ;; rf2-ri19s — it is decided FIRST, over the RAW `child-args`, because it
        ;; is the only admission condition that is purely STRUCTURAL: an address
        ;; is the child's own `:fixed-actor-id` / reducer-stamped
        ;; `:rf/spawned-id`, so aliasing is knowable without resolving a TYPE,
        ;; building a snapshot, or running an application validator. Preparing
        ;; first would (a) run application `[:schemas :data]` callbacks — and emit
        ;; their `:rf.error/schema-validation-failure` traces — for a batch
        ;; ALREADY known structurally invalid, so the structural fault did not
        ;; fail first, and (b) let one of those validators / its listeners destroy
        ;; owner frame A mid-preflight and PRE-EMPT the collision reject through
        ;; the `(continue?)` fence, losing the diagnostic entirely. Only a
        ;; structurally unique batch earns registry resolution, snapshot building
        ;; or schema validation.
        collisions (spawn-all-address-collisions child-args)]
    (if (seq collisions)
      ;; Structural reject — nothing was prepared, so this is the ONLY
      ;; diagnostic the invoke fans. Atomic via the same childless sentinel
      ;; every reject seeds; fenced on `(continue?)` because the trace's
      ;; listeners are application code that can swap A for a same-id B.
      (do (when (continue?)
            (reject-address-collision! frame-id parent-id invoke-id collisions))
          (seed-reject-sentinel! frame-id owner-token parent-id invoke-id continue?))
      (let [prepared       (mapv #(prepare-spawn-all-child % join-state continue?) child-args)
            unregistered   (mapv :args (filterv :unregistered? prepared))
            schema-invalid (filterv :schema-reject? prepared)]
        (if (or (seq unregistered) (seq schema-invalid))
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
          ;; Each unregistered child reports here; each schema-invalid child
          ;; already emitted its own `:rf.error/schema-validation-failure
          ;; :phase :spawn` as the preflight decided it. Both axes: exactly one
          ;; error per offending child, and no child effect runs at all.
          ;;
          ;; The resolved-address alias is NOT decided here — it is structural
          ;; and already rejected upstream, before this branch's `prepared` was
          ;; ever computed (rf2-ri19s). Reaching this branch therefore means the
          ;; batch's addresses are all distinct.
          ;;
          ;; rf2-8nxsh — every reject record + dev trace `reject-unregistered-spawn!`
          ;; fans is callback-bearing (an always-on `:errors` listener can destroy A
          ;; / publish same-id B), and the preflight's schema validators above may
          ;; ALREADY have lost A. Fence between emissions on `(continue?)` so a
          ;; listener that replaces A with B terminates the remaining rejects rather
          ;; than attributing stale diagnostics to a dead frame / successor B; then
          ;; seed the childless sentinel ONLY while A still owns, bound to A's raw
          ;; token so a same-id B stays byte-identical (no A-derived sentinel lands
          ;; on B). Under a live owner with no destroyer this is the historical
          ;; behaviour: one reject per unregistered child, one sentinel seeded.
          (do (loop [remaining unregistered]
                (when (and (seq remaining) (continue?))
                  (reject-unregistered-spawn! frame-id (:machine-id (first remaining)))
                  (recur (rest remaining))))
              (seed-reject-sentinel! frame-id owner-token parent-id invoke-id continue?))
          ;; The all-valid fast path. `join-state` already carries the opaque
          ;; per-attempt token (rf2-nvxehu) minted above: one LIVE seed = one join
          ;; ATTEMPT, and the token rides both the join state and each child's
          ;; `:rf/join-child` membership record (stamped by the per-child spawn
          ;; fxs that run AFTER this fx in the same entry vector), binding every
          ;; completion carrier to this exact attempt.
          ;;
          ;; Machine spawn-registry state is durable runtime-db state.
          ;;
          ;; rf2-8nxsh — the preflight's `[:schemas :data]` validators are
          ;; application code that may have destroyed A / published same-id B even
          ;; when every child CONFORMED (a conforming validator returning true still
          ;; ran on A's stack). Seed the live join ONLY while A still owns and bind
          ;; it to A's raw token: `write-spawned-slot!` no-ops (returns nil) when A
          ;; is already gone AND when a mid-write watch loses A, so A's join-state
          ;; can never land on B. The `:rf.machine.spawn-all/started` trace is
          ;; framework-owned tail — fire it ONLY when the join actually committed
          ;; into A (a nil return means B, or a dead frame, and gets no started
          ;; trace).
          ;;
          ;; rf2-ek435 — retain the AUTHORITATIVE prepared children on the seeded
          ;; join under `:rf/prepared` (`{<spawned-id> {:spec … :snap …}}`, one
          ;; entry per admitted child). Every child was admitted here, so each has a
          ;; resolved spec + built snapshot; each per-child install then CONSUMES its
          ;; entry (`spawn-all-prepared-child`) instead of re-resolving / rebuilding /
          ;; re-validating, and drops it in the same swap that lands its snapshot —
          ;; so the scratch never outlives the drain. Every `:spawned-id` here is
          ;; DISTINCT — the upstream structural alias guard (rf2-ri19s) rejected the
          ;; invoke outright otherwise — so this map can never silently overwrite.
          (do (when (continue?)
                (when (some? (write-spawned-slot!
                               frame-id owner-token parent-id invoke-id
                               (assoc join-state prepared-children-key
                                      (into {}
                                            (comp (filter :spawned-id)
                                                  (map (juxt :spawned-id
                                                             #(select-keys % [:spec :snap :type-spec]))))
                                            prepared))))
                  (rf.trace/emit! :rf.machine :rf.machine.spawn-all/started
                               {;; The parent's live actor INSTANCE address;
                                ;; `:invoke-id` is the declarative invocation path.
                                :actor-id   parent-id
                                :invoke-id  invoke-id
                                :child-ids  (set (keys children))
                                :children   children
                                :frame      frame-id})))
              nil))))))
