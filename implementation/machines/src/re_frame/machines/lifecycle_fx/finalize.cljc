(ns re-frame.machines.lifecycle-fx.finalize
  "Final-state orchestration — where a machine's COMPLETION is produced.

  Per Spec 005 §Final states: when a machine enters a `:final?` state the
  runtime auto-destroys the actor SYNCHRONOUSLY (D4) and routes its result to
  the parent that spawned it. Completion IS finality, for every spawn form — a
  child never dispatches to its parent and carries no parent vocabulary, so one
  child machine composes unchanged under `:spawn` and under `:spawn-all`. The
  orchestration:

    1. Resolve the final state-node from the post-transition snapshot
       (single / compound / parallel-all-regions-final).
    2. Read the child's `:data` slot designated by the final state's
       `:output-key` — call it `result`. Absent `:output-key` ⇒ nil. An
       `:error? true` leaf marks the terminal a FAILURE.
    3. Emit the `:rf.machine/done` trace (D6).
    4. Tear down the child: dissoc snapshot, clear `[:rf.runtime/machines :spawned ...]`
       slot, clear `[:rf.runtime/machines :system-ids <sid>]` (D8),
       emit `:rf.machine/destroyed` with `:reason :rf.machine/finished`
       (D6 enrichment), abort in-flight HTTP, and clear any registrar entry.
    5. Mint the completion carrier into the parent — the reserved event
       `[<parent-id> [:rf.machine.spawn/done <invoke-id> <completion>]]`
       (`dispatch-spawn-done!`), or, for an ERROR leaf whose `:spawn` parent
       declares `:on-error`, the reserved failure event instead. The parent's
       handler boundary routes the completion to its `:spawn :on-done` fold or
       to the `:spawn-all` join fold; because it is an EVENT, the parent may
       also ADVANCE on it (`:always`, or an explicit `:on` clause) rather than
       only folding data.

  The carrier is minted LAST, after teardown, so the parent never observes a
  completed child that is still alive.

  For singleton machines (no `:rf/parent-id` on `:data`): skip step 5
  and emit a `:rf.machine/done` with `:parent-id nil` (D7 — singleton
  symmetry). The teardown still runs — the snapshot is dissoc'd, the
  `:system-id` reverse-index entry and any registrar entry are cleared.

  This namespace also owns `abort-actor-in-flight-http!` — the late-bind
  hook into the http-managed artefact — the single home for
  the abort contract every destroy trigger shares: the finalize cascade,
  the spawn-destroy teardowns (`lifecycle-fx.destroy`), and the
  frame-destroy singleton-straggler pass (`lifecycle-fx.frame-destroy`).
  It takes the DESTROYING FRAME as well as the actor address (rf2-wjfm);
  all three call sites already hold one.

  The actor-teardown runtime-db projection lives in
  `re-frame.machines.lifecycle-fx.teardown` — one helper, three
  call-sites."
  (:require [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines.error-emit :as rf.machines.error-emit]
            [re-frame.machines.classification :as rf.machines.classification]
            [re-frame.machines.data-validation :as rf.machines.data-validation]
            [re-frame.machines.lifecycle-fx.resolver :as rf.machines.lifecycle-fx.resolver]
            [re-frame.machines.lifecycle-fx.resource-release :as rf.machines.lifecycle-fx.resource-release]
            [re-frame.machines.lifecycle-fx.spawn-error :as rf.machines.lifecycle-fx.spawn-error]
            [re-frame.machines.lifecycle-fx.teardown :as rf.machines.lifecycle-fx.teardown]
            [re-frame.machines.lifecycle-fx.traces :as rf.machines.lifecycle-fx.traces]
            [re-frame.machines.parallel :as rf.machines.parallel]
            [re-frame.machines.paths :as rf.machines.paths]
            [re-frame.machines.reply :as rf.machines.reply]
            [re-frame.machines.result :as rf.machines.result]
            [re-frame.machines.spawn-order :as rf.machines.spawn-order]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.machines.transition :as rf.machines.transition]
            [re-frame.registrar :as rf.registrar]
            [re-frame.trace :as rf.trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- in-flight HTTP abort cascade -----------------------------------------
;;
;; Per Spec 005 §Cancellation cascade — in-flight `:rf.http/managed`
;; aborts: when a spawned state-machine actor is destroyed, every
;; in-flight `:rf.http/managed` request the actor had issued is
;; aborted. The abort is fired through the late-bind hook
;; `:http/abort-on-actor-destroy` — re-frame.machines does NOT
;; statically `:require` re-frame.http.managed; the destroy path
;; looks up this fn at call time.
;;
;; rf2-wjfm — the abort is FRAME-EXACT. A spawned actor's address is
;; frame-LOCAL (Spec 014 §Abort on actor destroy §Frame scope), so two
;; isolated frames running the same application code spawn actors under the
;; same address. The hook's frame-bearing arity narrows the sweep to the
;; destroying frame's slot; passing an address alone would take the hook's
;; ANY-FRAME arity and reach a sibling frame's live request. This helper
;; therefore REQUIRES the frame rather than defaulting it — every destroy
;; trigger in this artefact already has one in scope, so there is no
;; frame-less call left to make, and the correct call is the only one that
;; compiles.

(defn abort-actor-in-flight-http!
  "Fire the late-bind hook that aborts every in-flight `:rf.http/managed`
  request the destroyed actor issued IN `frame-id`. A same-named actor in a
  sibling frame is untouched. Idempotent and safe to call when the http
  artefact is absent (returns nil).

  Both ids are required (rf2-wjfm). The hook call is wrapped in a
  catch-all, so an arity or contract mismatch here fails SILENTLY — which is
  precisely why the frame is a positional parameter and not an option."
  [frame-id actor-id]
  (when actor-id
    (when-let [abort! (rf.late-bind/get-fn :http/abort-on-actor-destroy)]
      (try (abort! frame-id actor-id)
           (catch #?(:clj Throwable :cljs :default) _ nil))))
  nil)

;; ---- per-instance [:schemas :data] marks cleanup ---------------------------
;;
;; A machine's `[:schemas :data]` schema is validation-only; it does not produce
;; a per-instance marks table. There is therefore no schema-marks entry to
;; clear at destroy — a destroyed actor leaves no schema-marks residue by
;; construction. A spawned actor's `:data` redaction rides the frame's merged
;; classification registry, including per-actor `:source :machine` entries.

;; ---- the completion carrier ------------------------------------------------

(defn dispatch-spawn-done!
  "Dispatch the reserved completion event

      [<parent-id> [:rf.machine.spawn/done <invoke-id> <completion>]]

  into the parent that spawned the finished child. ONE carrier serves both
  spawn forms (Spec 005 §Child completion protocol): `<invoke-id>` is the
  absolute prefix-path of the parent's `:spawn` / `:spawn-all`-bearing state,
  and `<completion>` is the runtime-built completion map —

      {:result       <the child's :output-key value>
       :error?       <true iff the reached final leaf declared :error? true>
       :completed-at <the causal :rf/time-ms of the finishing macrostep>}

  plus, for a `:spawn-all` join child, the exact-attempt coordinates copied
  off its `:rf/join-child` membership record (`:child-id` / `:spawned-id` /
  `:attempt` / `:work-generation`), which is what lets the parent's join fold
  reject a straggler from a superseded attempt.

  Dispatched (not raised) because the parent is a SEPARATE actor — symmetric
  with `dispatch-spawn-error!` and with how the spawn fx dispatches `:start`
  into a newborn child. No-op when the `:router/dispatch!` hook is absent
  (pure-fn / conformance callers). `:source :machine-spawn` labels the
  dispatch so the Epoch panel attributes it to the spawn lifecycle."
  [frame-id parent-id invoke-id completion]
  (when-let [dispatch! (rf.late-bind/get-fn :router/dispatch!)]
    (dispatch! [parent-id [rf.machines.transition/spawn-done-event-id invoke-id completion]]
               {:frame frame-id :source :machine-spawn}))
  nil)

;; ---- final-state resolution -----------------------------------------------

;; Per Spec 005 §Final states §The done-state signal: the
;; `all-regions-final?` predicate lives in `re-frame.machines.parallel`
;; (shared by the parallel macrostep's done-signal / `:on-done` firing and
;; this whole-machine finalize path). Re-exported here so callers
;; (and the conformance corpus) reach it at the `finalize/all-regions-final?`
;; address. `finalize` requires `parallel`, never the reverse — no cycle.
(def all-regions-final? rf.machines.parallel/all-regions-final?)

(defn- region-final-leaf-nodes
  "Per Spec 005 §Final states + §Parallel regions: resolve
  every region's active final leaf-node from the post-transition `state`
  map (a `{region-name region-state}` map). Returns an ordered seq of
  `[region-name leaf-node]` in canonical region DECLARATION order
  (`rf.machines.parallel/region-order`) — the basis for the cross-region `:output-key` /
  `:error?` scans below, whose first-region tie-break must follow authored
  order, NEVER state-map hash iteration (>8 regions). `state` is a valid
  all-final configuration here (gated by `all-regions-final?`), so every
  declared region is present."
  [machine state]
  (map (fn [region-name]
         [region-name
          (rf.machines.transition/node-at (get-in machine [:regions region-name])
                              (rf.machines.transition/state-path (get state region-name)))])
       (rf.machines.parallel/region-order machine)))

(defn- parallel-output-key
  "Per Spec 005 §Final states: resolve a finishing PARALLEL
  machine's `:output-key` by scanning EVERY region's final leaf — not just
  the first region's. Returns the first (state-map order) region leaf that
  declares an `:output-key`, or nil when no region designates output.

  `:output-key` works on ANY region (the spec never restricted it to the
  first). A genuine CONFLICT — two regions declaring DIFFERENT
  `:output-key`s — emits
  `:rf.error/machine-parallel-output-key-conflict` and deterministically
  keeps the first-region declaration (last-region-loses would be just as
  arbitrary; first-in-declaration-order — canonical `region-order` — is the
  stable, documented tiebreak)."
  [machine state frame-id machine-id]
  (let [declared (->> (region-final-leaf-nodes machine state)
                      (filter (fn [[_ node]] (contains? node :output-key))))
        keys'    (distinct (map (fn [[_ node]] (:output-key node)) declared))]
    (when (> (count keys') 1)
      (rf.trace/emit-error! :rf.error/machine-parallel-output-key-conflict
                         {:actor-id    machine-id
                          :frame       frame-id
                          :output-keys (vec keys')
                          :chosen      (first keys')
                          :reason      (str "A parallel machine declares :output-key on "
                                            "more than one region with DIFFERENT keys "
                                            (pr-str (vec keys'))
                                            "; the first region's :output-key wins. "
                                            "Declare :output-key on a single region (or the "
                                            "same key consistently) to make the reported "
                                            "result unambiguous.")
                          :recovery    :first-region-output-key-used}))
    (some (fn [[_ node]] (:output-key node)) declared)))

(defn- parallel-error-leaf-node
  "Per Spec 005 §Final states §`:on-error` + §Parallel regions:
  classify a finishing PARALLEL machine's terminal as ERROR by scanning
  EVERY region's final leaf — not just the first region's. Returns the
  first (canonical region-declaration order) region final leaf that declares
  `:error? true`, or nil when no region's terminal is an error final.

  Spec 005 §3061 says a parallel machine finishes when EVERY region's active
  leaf is `:final?` and never restricts the error terminal to the first
  region; §3060 lets ANY `:final?` leaf declare `:error? true`. XState v5's
  parallel `onDone`/error semantics treat the completion as an error when a
  region ends in an error final. So a parallel finish routes as ERROR if ANY
  region's reached final leaf declares `:error? true`; the error payload's
  `:output-key` is sourced from that error region's leaf."
  [machine state]
  (some (fn [[_ node]] (when (true? (:error? node)) node))
        (region-final-leaf-nodes machine state)))

;; ---- the orchestrator ------------------------------------------------------

(defn finalize-machine
  "Per Spec 005 §Final states: orchestrate the `:on-done` +
  auto-destroy cascade. Returns `{:rf.db/runtime new-runtime-db :fx fx}` —
  the snapshot teardown is a durable runtime-db write (EP-0001),
  returned under the framework-authority `:rf.db/runtime` partition, NOT
  `:db` (see the return at `finalize-machine!`'s tail). It is the handler's
  return value when the post-transition snapshot has finished (its active
  leaf is `:final?`, or — for a parallel machine — every region's active
  leaf is `:final?`). Finality is RECOMPUTED by the caller
  (`commit-or-finalize`) from the post-transition `:state` via
  `final-on-leaf?` / `all-regions-final?`; it is NOT carried on the
  snapshot (there is no `:rf/finished?` slot — per Spec 005 §Persistence
  posture the pure surface stays free of runtime-only bookkeeping).

  Arguments:
    machine        — the runtime-stamped machine spec (the finishing actor's)
    machine-id     — the finishing actor's id (its event-handler key)
    frame-id       — the frame the actor runs in
    runtime-db     — the runtime-db partition value AT the time the handler
                     was invoked (machine snapshots are durable runtime-db
                     state — EP-0001); returned under `:rf.db/runtime`
    next-snapshot  — the post-transition snapshot (the caller already
                     determined it is final by recomputing from `:state`)
    _inner-event   — the event that caused the finish (for diagnostics)
    extra-fx       — the fx vector from the transition (passed through)"
  [machine machine-id frame-id runtime-db next-snapshot _inner-event extra-fx]
  ;; Run the actor's active configuration `:exit` cascade
  ;; FIRST so the final state's `:exit` actions fire from the auto-
  ;; destroy teardown (Spec 005 §Final states §Composition with
  ;; `:entry` / `:exit`). The cascade is pure — it returns a new
  ;; snapshot + fx vector; we project the `:data` writes onto
  ;; `next-snapshot` so `:on-done`'s `result` computation observes
  ;; them, and we append `exit-fx` to the returned `:fx` so any
  ;; `:exit`-emitted dispatches / HTTP / etc. run.
  ;;
  ;; If any `:exit` action threw, we emit the standard exit-cascade
  ;; failure trace and proceed with the pre-cascade snapshot (fail-
  ;; soft — the destroy must complete to keep the registrar +
  ;; spawn-slot consistent).
  (let [exit-result   (rf.machines.parallel/run-active-exit-cascade machine next-snapshot)
        exit-ok?      (rf.machines.result/ok? exit-result)
        next-snapshot (if exit-ok? (rf.machines.result/snap exit-result) next-snapshot)
        exit-fx       (if exit-ok? (vec (rf.machines.result/fx exit-result)) [])
        runtime-db    (if exit-ok?
                        ;; Project the post-`:exit` CHILD snapshot back into
                        ;; runtime-db so any reader of the runtime-db between
                        ;; here and the teardown dissoc observes the final
                        ;; state's `:exit`-time `:data` writes on the FINISHING
                        ;; child. (The child's `result` is computed directly
                        ;; from `next-snapshot` below, NOT re-read from
                        ;; runtime-db, and `:on-done` reads the PARENT's
                        ;; `:data`; the projection's purpose is runtime-db
                        ;; consistency for the finalize cascade's own reads.)
                        (assoc-in runtime-db (rf.machines.paths/snapshot-path machine-id) next-snapshot)
                        runtime-db)
        _             (when (not exit-ok?)
                        ;; Same destroy-exit failure shape as the explicit-
                        ;; destroy path (`exit-cascade/run-child-exit!`) —
                        ;; shared via `rf.machines.lifecycle-fx.traces/emit-destroy-exit-failure!`.
                        (rf.machines.lifecycle-fx.traces/emit-destroy-exit-failure!
                          machine-id frame-id (rf.machines.result/info exit-result)))]
  (let [;; A's exact-frame-incarnation continuation predicate (rf2-3evq0x —
        ;; the completion-tail half of the incarnation-fencing family
        ;; established by #5849). The completion-output validator, the
        ;; `:rf.machine/done` trace fan-out, and the parent `:on-done` callback
        ;; are all APPLICATION / listener code that can synchronously destroy
        ;; the frame incarnation A that owns the in-flight event and publish a
        ;; same-id successor B. Every subsequent framework-owned action —
        ;; teardown, registrar unregister, HTTP/timer cancellation,
        ;; rf.machines.classification/spawn-order drop, the `:on-error` dispatch, and the
        ;; runtime-db / fx publication — is A-derived tail; running it after A
        ;; is lost erases or mutates B. `owner-continuation` yields
        ;; `(constantly true)` for a non-router pure-fn / conformance caller
        ;; (no event owner), so that path stays unaffected.
        continue?  (rf.machines.data-validation/owner-continuation frame-id)
        ;; rf2-i4aj9c — the RAW exact owner token (`continue?` closes over it),
        ;; threaded into the teardown classification drop so it rides the EXACT
        ;; elision write: a container watch that destroys A / publishes same-id B
        ;; DURING the drop cannot re-root the removal onto B's registry or bump
        ;; B's commit epoch. nil for a non-router pure-fn / conformance caller
        ;; (no event owner) — the drop falls back to the historical bare write.
        owner-token (rf.frame/current-event-owner-token)
        child-data (:data next-snapshot)
        parallel?  (rf.machines.parallel/parallel? machine)
        ;; Resolve the ERROR-classifying leaf. For a PARALLEL
        ;; machine, scan EVERY region's final leaf — a parallel finish is an
        ;; ERROR when ANY region's reached final declares `:error? true`, not
        ;; only the first region's (Spec 005 §3060-§3061; XState v5 parallel
        ;; error-final semantics). For a flat machine the single leaf IS the
        ;; error-classifying leaf.
        error-node  (if parallel?
                      (parallel-error-leaf-node machine (:state next-snapshot))
                      (rf.machines.transition/node-at machine
                                          (rf.machines.transition/state-path (:state next-snapshot))))
        error-leaf? (true? (:error? error-node))
        ;; A PARALLEL machine's `:output-key` may live on ANY region's
        ;; terminal leaf, not just the first. Scan all regions (error on
        ;; conflict); a flat machine reads its single leaf. When the finish is
        ;; an ERROR, the error payload's `:output-key` is sourced from the
        ;; ERROR region's leaf so the right error `:data` slot routes to
        ;; `:on-error`.
        output-key  (cond
                      (and parallel? error-leaf?) (:output-key error-node)
                      parallel?                   (parallel-output-key machine (:state next-snapshot) frame-id machine-id)
                      :else                       (:output-key error-node))
        result      (when output-key (get child-data output-key))
        ;; Validate the completion-output payload against the
        ;; finishing machine's `[:schemas :output]` schema, if declared. The
        ;; `result` selected from the final state's `:data` via `:output-key`
        ;; IS the completion-event payload (the value the parent's `:on-done`
        ;; receives) — there is no long-lived `:output` snapshot slot, so the
        ;; validation happens HERE at finalize time, when the value is
        ;; computed and BEFORE it rides the `:rf.machine/done` trace / the
        ;; parent `:on-done` callback below. Best-effort fail-loud: the
        ;; machine has already finished (post-completion observation), so a
        ;; violation emits the `:where :machine-output` boundary trace but the
        ;; completion still flows — nothing to roll back (`:rollback? false`).
        ;; Routes through the SAME late-bound `:schemas/validate-with-
        ;; registered-fn` adapter the `:where :machine-data` boundary uses; an
        ;; app with no schema adapter pays zero cost. `machine` is the
        ;; finishing actor's runtime-stamped spec held directly by this
        ;; cascade, so the `[:schemas :output]` schema resolves off it without
        ;; a registrar / snapshot lookup. Production-elided
        ;; (`rf.interop/debug-enabled?`-gated inside the validator).
        ;; The completion-output validator is APPLICATION code (rf2-3evq0x): a
        ;; validator that destroys A / publishes same-id B returns
        ;; `:rf/stale-incarnation` (NOT a schema verdict). Capture it — the
        ;; original `_` binding dropped the verdict and let the whole finalize
        ;; tail run against B. A stale return terminally fences every
        ;; subsequent framework-owned action below (the `owner-gone?` gate).
        output-validation (rf.machines.data-validation/validate-completion-output! machine-id machine result)
        stale-output?     (= :rf/stale-incarnation output-validation)
        ;; Re-checked (live) after each callback-bearing completion trace /
        ;; fanout and before every framework-owned action: true once the
        ;; completion-output validator, a `:rf.machine/done` trace listener, or
        ;; the parent `:on-done` callback has destroyed A / published same-id B.
        owner-gone?       (fn [] (or stale-output? (not (continue?))))
        ;; Per Spec 005 §Final states §`:on-error` (XState v5 invoke
        ;; `onError`): a `:final?` leaf MAY also declare `:error? true` — a
        ;; designated ERROR terminal. When a `:spawn`-spawned child finishes
        ;; via an error leaf AND its spawning parent declares `:spawn :on-error`,
        ;; the runtime routes the failure to a PARENT TRANSITION (control flow,
        ;; not just observability) instead of the `:data`-only `:on-done`
        ;; callback. A plain `:final?` leaf keeps firing `:on-done`. `error-leaf?`
        ;; is computed above (cross-region scan for parallel).
        ;; A `:spawn-all` child's private membership is the canonical REPLY
        ;; source of its parent/invoke coordinates (per-child spawn args use the
        ;; distinct `:rf/spawn-all-id`, so no public single-spawn `:rf/invoke-id`
        ;; stamp is required) and the runtime-minted exact-attempt coordinate.
        ;; Keep the ordinary parent/invoke locals unchanged: they also govern
        ;; single-`:spawn` callbacks + teardown-slot pruning, and a join child
        ;; must not enter either path merely because reply identity needs the
        ;; membership coordinates.
        join-child  (:rf/join-child child-data)
        parent-id   (:rf/parent-id child-data)
        invoke-id   (:rf/invoke-id child-data)
        reply-parent-id (or parent-id (:parent-id join-child))
        reply-invoke-id (or invoke-id (:invoke-id join-child))
        ;; Thread that coordinate into the SAME
        ;; canonical machine work-id generation slot used by its join fold, so
        ;; a fixed-id final leaf cannot publish a parallel generation-1 arc.
        ;; Ordinary `:spawn` data has no membership record and stays unchanged.
        join-work-generation (:work-generation join-child)
        ;; Per EP-0011 §Machine Completion / Managed-Effects §The uniform
        ;; reply envelope: form the canonical machine reply map INTERNALLY
        ;; (work-id `[:rf.work/machine actor-id work-bearing-path
        ;; generation]`, one closed `:status` — `:ok` for a plain final
        ;; leaf, `:error` for an `:error?` error terminal — the child's
        ;; `:output-key` result under `:value`, frame + correlation). The
        ;; PUBLIC `:on-done` / `:on-error` semantics are PRESERVED: the
        ;; `:on-done` `:data` callback is still driven with the child's
        ;; result (now derived as `(:value reply)`), and `:on-error` still
        ;; routes the raw failure payload to the parent transition. This is
        ;; internal lowering only — the reply map is what the trace stream,
        ;; ledger, and future work-correlation read uniformly (m-reply).
        ;; Thread the CAUSAL completion timestamp into the
        ;; reply so `:completed-at` carries the one host-clock read the
        ;; router captured for the finishing event — NOT an ambient
        ;; `(.now)`. Per spec/Managed-Effects.md §155/§231 a machine
        ;; completion that affects durable state (a spawned child's
        ;; `:on-done` mutating the parent's `:data`) MUST carry causal
        ;; completion metadata. The router's `:rf.cofx` `:rf/time-ms`
        ;; (EP-0010 / EP-0017 — the single causal-boundary clock read) is
        ;; threaded onto the machine def under `:rf/cofx`
        ;; (lifecycle-fx.registration); read it here so the done reply +
        ;; trace carry the causal time instead of silently losing it. nil
        ;; when the trigger was unscripted (no recordable cofx) — omitted, not
        ;; nil-filled (Managed-Effects §The reply map).
        completed-at (get-in machine [:rf/cofx :rf/time-ms])
        reply-ctx   (cond-> {:actor-id          machine-id
                             :parent-id         reply-parent-id
                             :work-bearing-path reply-invoke-id
                             :frame             frame-id}
                      (some? join-work-generation)
                      (assoc :work-generation join-work-generation)
                      (some? completed-at) (assoc :completed-at completed-at))
        ;; (1) Find parent's `:on-done` / `:on-error`, if this is a `:spawn`-
        ;; spawned actor. The parent's spec carries the `:spawn` map at
        ;; `invoke-id`. Resolve the parent's spec from the registrar (a
        ;; singleton parent) OR, for a NESTED spawn whose parent is itself a
        ;; spawned actor (no per-instance registration), from the parent's own
        ;; snapshot `:rf/machine-type`.
        parent-path (rf.machines.paths/snapshot-path parent-id)
        parent-snap (when parent-id (get-in runtime-db parent-path))
        parent-reg  (when parent-id (rf.registrar/lookup :event parent-id))
        parent-meta (when parent-id
                      (cond
                        (:rf/machine? parent-reg) (:rf/machine parent-reg)
                        :else                     (rf.machines.lifecycle-fx.resolver/spec-from-snapshot parent-snap)))
        spawn-spec  (when (and parent-meta invoke-id)
                      (rf.machines.lifecycle-fx.resolver/spawn-spec-at parent-meta invoke-id))
        ;; `:on-error` is a transition spec (not a fn) — its PRESENCE on the
        ;; resolved `:spawn` map decides whether the error-leaf trigger routes
        ;; to a parent transition. The transition itself is resolved natively
        ;; by the parent's engine (`pick-spawn-error-transition`) when the
        ;; dispatched `[:rf.machine.spawn/error …]` event arrives, so finalize
        ;; only decides "fire the dispatch?" here.
        on-error?   (and error-leaf?
                         parent-id
                         (some? (:on-error spawn-spec)))
        ;; Per EP-0011 §Machine Completion / Managed-Effects §Stale
        ;; suppression: the one reachable machine-supersession
        ;; case is a `:spawn`-spawned child reaching `:final?` AFTER its
        ;; spawning parent was already DESTROYED. The completion is then
        ;; STALE — its `:on-done` / `:on-error` routing has no live parent to
        ;; drive, so per §Stale suppression the app target MUST NOT run and
        ;; the completion is recorded `:status :stale` / `:rf.reply/work-status
        ;; :suppressed` via the shared substrate, carrying the full stale
        ;; vocabulary rather than a bare `:ok` reply.
        ;;
        ;; LIVENESS is the gate (not `:on-done` resolvability): when the
        ;; parent was destroyed BOTH its snapshot AND — for a singleton
        ;; parent — its registrar handler are gone, so the `:spawn` spec
        ;; (and thus its `:on-done`) is no longer resolvable AT ALL. Keying
        ;; off the callback would miss the case entirely. The robust gate is: a
        ;; declaratively-spawned child (it carries both `:rf/parent-id` and
        ;; `:rf/invoke-id`) whose parent is NO LONGER LIVE (no snapshot AND no
        ;; registered handler). `:on-error` routing (the error-leaf control-
        ;; flow case) is left to its own dispatch path — a stale error leaf
        ;; with a dead parent simply dispatches into the void, harmlessly.
        parent-live?  (or (some? parent-snap) (some? parent-reg))
        stale-spawn?  (and parent-id invoke-id (not on-error?)
                           (not parent-live?))
        ;; The carried generation is parsed off THIS finishing actor's id;
        ;; the CURRENT generation is the LIVE counterpart — the generation of
        ;; the actor currently occupying the spawn slot at
        ;; `[:rf.runtime/machines :spawned parent-id invoke-id]`, read ONLY
        ;; when the parent is still live. With the parent gone there is no
        ;; live counterpart, so `:current` is nil — exactly the supersession
        ;; the gate records (the parity counterpart of the `:after` path's
        ;; exited-node → nil declaring path). The carried/current pair rides
        ;; the trace via `stale-spawn-reply`'s correlation.
        current-generation
        (when (and parent-live? parent-id invoke-id)
          (rf.machines.reply/actor-generation
            (get-in runtime-db (rf.machines.paths/spawned-path parent-id invoke-id))))
        reply       (cond
                      stale-spawn?
                      (rf.machines.reply/stale-spawn-reply
                        (assoc reply-ctx :current-generation current-generation))
                      error-leaf?
                      (rf.machines.reply/error-reply reply-ctx result)
                      :else
                      (rf.machines.reply/success-reply reply-ctx result))
        ;; (2) Emit `:rf.machine/done` trace BEFORE the destroy cascade
        ;; (D6 ordering). Fired for EVERY finish (success / error / STALE) —
        ;; `:on-error` is additive, the done trace is the actor-finality
        ;; signal regardless of which spawn hook routes the result. Per
        ;; EP-0011 the reply-envelope facts (`:rf.reply/status`,
        ;; `:rf.reply/work-id`, `:rf.reply/work-status`) ride ADDITIVELY so
        ;; the trace stream classifies the completion the same way HTTP /
        ;; resources do; the public `:output` / `:parent-id` / `:error?`
        ;; shape is preserved. Wire-bearing slots (`:value` / `:error` /
        ;; `:correlation`) route through the shared elision walker via
        ;; `rf.machines.reply/trace-reply`.
        ;;
        ;; A STALE late completion (parent destroyed before the
        ;; child finished) additionally rides `:rf.reply/stale-reason`
        ;; (`:rf.machine/actor-not-live`) and `:rf.reply/correlation` (the
        ;; carried/current generation gate) — the parity counterpart of the
        ;; `:after` path's `:rf.machine.timer/stale-after` stale-suppression
        ;; trace. The summary is built via `rf.machines.reply/stale-spawn-trace` for a
        ;; stale reply so the spawn-stale path reads as the spawn analogue of
        ;; `after-stale-reply` → `trace-reply`.
        done-summary (if stale-spawn?
                       (rf.machines.reply/stale-spawn-trace reply {:frame frame-id})
                       (rf.machines.reply/trace-reply reply {:frame frame-id}))
        ;; The `:rf.machine/done` trace fan-out is callback-bearing
        ;; (rf2-3evq0x): a listener can destroy A / publish same-id B. Skip it
        ;; when A is already gone (a stale completion-output validator), and
        ;; re-check liveness after it before any framework-owned action below.
        _ (when-not (owner-gone?)
            (rf.trace/emit! :rf.machine :rf.machine/done
                       ;; `:actor-id` is the finishing actor's
                       ;; live INSTANCE address (singleton: its registration
                       ;; id; spawned: the `<type>#<n>` / fixed instance id).
                       ;; `:machine-id` is reserved for the registered TYPE.
                       (cond-> {:actor-id   machine-id
                                :output     result
                                :parent-id  parent-id
                                :error?     error-leaf?
                                :frame      frame-id
                                ;; reply-envelope vocabulary (Managed-Effects §9)
                                ;; The CANONICAL `:rf.reply/work-id` joins
                                ;; this spawned-actor completion into Xray's
                                ;; uniform work/reply rows + stale-race
                                ;; grouping. `:rf.reply/work-kind` rides alongside as the
                                ;; work-family tag (no reply-envelope twin).
                                :rf.reply/work-kind            (:rf.reply/work-kind done-summary)
                                :rf.reply/status      (:status done-summary)
                                :rf.reply/work-id     (:rf.reply/work-id done-summary)
                                :rf.reply/work-status (:rf.reply/work-status done-summary)}
                         ;; the causal completion timestamp — the
                         ;; router's `:rf.cofx` `:rf/time-ms` threaded
                         ;; into the reply (Managed-Effects §155/§231: a
                         ;; completion affecting durable state carries causal
                         ;; completion metadata). Additive + present-only so
                         ;; the unscripted (no-world-input) path stays clean.
                         (some? (:completed-at done-summary))
                         (assoc :rf.reply/completed-at (:completed-at done-summary))
                         ;; stale-suppression vocabulary — carried
                         ;; ADDITIVELY only for a stale late completion, joined
                         ;; to `:rf.reply/work-id` via the shared `:rf.reply/*` facts.
                         stale-spawn? (assoc :rf.reply/stale-reason (:rf.reply/stale-reason done-summary)
                                             :rf.reply/correlation  (:correlation done-summary)))))
        ;; (3) The completion carrier. `:on-done` is NO LONGER applied here.
        ;; Completion is delivered to the parent as ONE reserved runtime-minted
        ;; event (Spec 005 §Child completion protocol) — the same carrier for
        ;; both spawn forms — and the parent's own handler boundary routes it:
        ;;
        ;;   - a `:spawn` child's completion runs the parent's `:spawn :on-done`
        ;;     fold against the parent's `:data` and then flows into the
        ;;     parent's ORDINARY macrostep, so the parent may ADVANCE on it
        ;;     through `:always` (a guard over the folded `:data`) or an
        ;;     explicit `:on` clause. That advancement is the whole reason the
        ;;     carrier is an event rather than the direct parent-snapshot write
        ;;     this cascade used to perform: a parent could fold a child's
        ;;     result but could not react to it, so every sequencing parent had
        ;;     to make its child hand-dispatch — which is exactly the parent
        ;;     vocabulary in the child that this protocol removes;
        ;;   - a `:spawn-all` join child's completion runs its per-child
        ;;     `:on-done` fold and then folds into the join
        ;;     (`lifecycle-fx.join/intercept-spawn-done-event`).
        ;;
        ;; The carrier is minted AFTER the teardown below, so the parent never
        ;; observes a completed child that is still alive: finality means
        ;; teardown (D4), and the result rides the event rather than the
        ;; child's snapshot.
        ;;
        ;; When the parent was destroyed before the child finished
        ;; (`stale-spawn?` — no live `parent-snap`), the completion is STALE:
        ;; per Managed-Effects §Stale suppression the app target MUST NOT run,
        ;; so NO carrier is minted at all. The suppression is a positive fact —
        ;; the canonical `:status :stale` reply was emitted on the done trace
        ;; above, and `runtime-db` rides through untouched.
        ;;
        ;; Per EP-0011 §Machine Completion the delivered `:result` is driven
        ;; from the canonical reply's `:value`, so the value the trace / ledger
        ;; see and the value the parent's callback receives are the SAME fact.
        ;; `(:value reply)` is `=` to the pre-lowering `result` for a success
        ;; leaf (behavioural parity).
        completion-value (:value reply)]
    ;; rf2-3evq0x — terminal incarnation fence for the completion tail. If a
    ;; completion-output validator, a `:rf.machine/done` trace listener, or the
    ;; parent `:on-done` callback destroyed A / published same-id B, EVERY action
    ;; below is A-derived framework-owned tail that would erase or mutate B: the
    ;; teardown projection (dissoc B's snapshot / clear its slot), the
    ;; `:rf.machine/destroyed` trace, the HTTP/timer cancellation, the
    ;; classification + spawn-order drop, the registrar unregister, the
    ;; `:on-error` dispatch, and the runtime-db / fx publication. Return the
    ;; inert outcome — runtime-db untouched, no fx — before ANY of them.
    ;; Already-delivered callbacks stand (no rollback); the router's own
    ;; candidate fence drops the inert runtime-db rather than committing it onto
    ;; B. (`stale-spawn?` — a child finishing after its PARENT was destroyed — is
    ;; a DIFFERENT staleness and still tears down normally; `owner-gone?` fires
    ;; only on A→B frame-incarnation loss.)
    (if (owner-gone?)
      {:rf.db/runtime runtime-db :fx []}
      (let [;; (4) Apply the unified teardown projection: dissoc the child's
            ;; snapshot, release any `:system-id` reverse-index entry (D8 —
            ;; after on-done ran), and clear the parent's
            ;; `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` slot
            ;; with the lazy-allocation prune. Returns `[new-db released-sid]`;
            ;; `released-sid` is resolved against runtime-db before the
            ;; reverse index is mutated.
            [db-after-destroy released-sid]
            (rf.machines.lifecycle-fx.teardown/teardown-actor runtime-db
                                     {:actor-id  machine-id
                                      :parent-id parent-id
                                      :invoke-id invoke-id})]
        ;; (5) Emit :rf.machine/destroyed with :reason :rf.machine/finished
        ;; (D6 enrichment) before registrar cleanup.
        (rf.machines.lifecycle-fx.traces/emit-destroyed! {:frame     frame-id
                                 :actor-id  machine-id
                                 :system-id released-sid
                                 :parent-id parent-id
                                 :invoke-id invoke-id
                                 :reason    :rf.machine/finished})
        ;; rf2-hloj0g — the teardown tail's callback-bearing hooks — the
        ;; `:rf.machine/destroyed` trace above, the late-bound HTTP abort hook,
        ;; and the `:rf.machine/system-id-released` trace — can EACH destroy A /
        ;; publish same-id B on their own stack. #5856 fenced the earlier
        ;; completion callbacks (validator / done trace / `:on-done`) with the
        ;; top-level `owner-gone?` gate, but NOTHING rechecked ownership between
        ;; these LATER teardown callbacks and the framework-owned actions that
        ;; follow — so the HTTP/timer cancellation, rf.machines.classification/spawn-order
        ;; drop, registrar unregister, and `:on-error` dispatch could all run
        ;; against B (a bare frame-id / machine-id resolves to the CURRENT
        ;; incarnation). Recheck `owner-gone?` before each next framework action;
        ;; it is MONOTONIC (once A→B it stays gone), so a per-action guard
        ;; short-circuits the whole tail. Already-delivered rf.machines.lifecycle-fx.traces/hooks stand —
        ;; the ruled unwind posture, no rollback.
        (when-not (owner-gone?)
          ;; (6) Abort in-flight HTTP (late-bound hook — callback-bearing).
          ;; rf2-wjfm — frame-exact: `frame-id` is this cascade's own frame, so
          ;; a same-named actor in a sibling frame keeps its in-flight work.
          (abort-actor-in-flight-http! frame-id machine-id))
        (when-not (owner-gone?)
          ;; Cancel armed `:after` timers (`:reason :on-destroy`). rf2-4ipqe4 —
          ;; each cancellation emits a callback-bearing `:rf.machine.timer/
          ;; cancelled` trace; a listener can destroy A / publish same-id B on
          ;; the FIRST cancellation's stack, after which the loop would cancel B's
          ;; timer under a later snapshotted key. Thread `owner-gone?` so the
          ;; cancellation loop short-circuits the instant A is lost (it is
          ;; MONOTONIC), leaving B's re-armed timers untouched.
          (rf.machines.timer/cancel-actor-timers! frame-id machine-id owner-gone?))
        ;; rf2-4ipqe4 — RECHECK after the timer-cancellation callbacks before the
        ;; classification / spawn-order / system-id-release work. #5856/#5873
        ;; grouped these under the timer cancel with NO recheck, so a
        ;; `:rf.machine.timer/cancelled` listener that published same-id B let the
        ;; A-derived classification drop, spawn-order forget, and system-id-released
        ;; trace resolve their bare rf.frame/actor ids to the CURRENT incarnation B.
        (when-not (owner-gone?)
          ;; Drop this actor's per-instance classification declarations from the
          ;; per-frame elision registry — the teardown half of
          ;; `rf.machines.classification/lower-at-spawn!` on the final-state AUTO-DESTROY path
          ;; (which does NOT route through `destroy/teardown-live-actor!`). A spec
          ;; that declared no classification is a clean no-op, so the registry
          ;; entry added at spawn dies with the instance (no leak). rf2-i4aj9c —
          ;; thread `owner-token` so the drop rides the EXACT elision write (a
          ;; mid-write watch cannot re-root the removal onto same-id B).
          (rf.machines.classification/drop-at-destroy! frame-id machine-id machine owner-token))
        ;; rf2-rbxdxa — `drop-at-destroy!` writes through the EXACT elision swap, a
        ;; container-write boundary: a synchronous watch can destroy A / publish
        ;; same-id B DURING the drop. Recheck ownership AFTER it before the
        ;; spawn-order forget + system-id release — grouping them under the SAME
        ;; precheck let a mid-drop successor B see the bare-id `rf.machines.spawn-order/forget!`
        ;; erase B's freshly-recorded entry and the system-id-released trace resolve
        ;; against B (the finalize sibling of the ordinary-destroy drop seam). The
        ;; drop write is already exact; this fences the forget/release the grouped
        ;; precheck ran ahead of.
        (when-not (owner-gone?)
          ;; Forget the finished actor from the per-frame spawn-order channel — the
          ;; ONE synchronous teardown side-effect the `:final?`-auto-destroy path
          ;; shares with `destroy/teardown-live-actor!` (destroy.cljc step 7).
          ;; Without it a finished actor stays recorded → a later stale
          ;; `[:rf.machine/destroy id]` sees it live → `teardown-live-actor!`
          ;; RE-RUNS (phantom destroyed trace + re-fired resource release,
          ;; violating silent-idempotent destroy) and frame-destroy emits a
          ;; phantom straggler destroy for it.
          (rf.machines.spawn-order/forget! frame-id machine-id)
          ;; The `:rf.machine/system-id-released` trace is callback-bearing and is
          ;; the LAST action in this group, so the recheck below fences the
          ;; registrar unregister + `:on-error` dispatch against a B it published.
          (rf.machines.lifecycle-fx.traces/emit-system-id-released! frame-id released-sid machine-id))
        (when-not (owner-gone?)
          ;; `rf.registrar/unregister!` emits a synchronous callback-bearing
          ;; `:rf.registry/handler-cleared` trace.
          (rf.registrar/unregister! :event machine-id))
        ;; rf2-4ipqe4 — RECHECK after the registrar unregister before the
        ;; completion dispatch. #5856/#5873 grouped the unregister with the
        ;; dispatch under ONE check, so a `:rf.registry/handler-cleared` listener
        ;; that published same-id B let the stale A-derived dispatch route into
        ;; B. The unregister is an already-delivered callback (it stands); the
        ;; dispatch it enables must be fenced.
        (when-not (owner-gone?)
          ;; (7) ROUTE THE COMPLETION TO THE PARENT. The child is fully torn
          ;; down by now — finality means teardown (D4) — so what reaches the
          ;; parent is a value, never a live child.
          ;;
          ;; Two carriers, chosen by which hook the parent declared:
          ;;
          ;;   - Per Spec 005 §Final states §`:on-error`: an ERROR leaf under a
          ;;     `:spawn` parent that declares `:on-error` routes to the
          ;;     reserved FAILURE event, which resolves natively through the
          ;;     parent's macrostep (`pick-spawn-error-transition`), firing the
          ;;     declarative `:on-error` transition — the XState `invoke onError`
          ;;     control flow. That carrier keeps its own id because an uncaught
          ;;     child ACTION EXCEPTION produces it too, and an exception is not
          ;;     a completion. The dispatched payload is the RAW error
          ;;     (`result`); the parent transition reads it off `:event`.
          ;;
          ;;   - Otherwise the reserved COMPLETION event (Spec 005 §Child
          ;;     completion protocol) — the one carrier both spawn forms share.
          ;;     A `:spawn` child sends its `:invoke-id` and result, and the
          ;;     parent folds `:on-done` then advances on its ordinary
          ;;     macrostep. A `:spawn-all` join child additionally sends the
          ;;     exact-attempt coordinate off its `:rf/join-child` membership
          ;;     record, and the parent folds it into the join. An ERROR leaf
          ;;     under a `:spawn-all` parent rides this same carrier with
          ;;     `:error? true` — failure control flow under a join is
          ;;     `:on-any-failed`, not a per-child transition.
          ;;
          ;; A STALE completion (the parent died first) mints NEITHER: there is
          ;; no live parent to receive it, and §Stale suppression says the app
          ;; target must not run. A SINGLETON (no parent at all) mints neither
          ;; either — its finality is its own.
          (cond
            on-error?
            (rf.machines.lifecycle-fx.spawn-error/dispatch-spawn-error! frame-id parent-id invoke-id result)

            (and parent-id (not stale-spawn?))
            (when-let [target-invoke-id (or invoke-id (:invoke-id join-child))]
              (dispatch-spawn-done!
                frame-id parent-id target-invoke-id
                (cond-> {:result       completion-value
                         :error?       error-leaf?
                         :completed-at completed-at}
                  join-child (merge (select-keys join-child
                                                 [:parent-id :invoke-id :child-id
                                                  :spawned-id :attempt
                                                  :work-generation])))))))
        ;; Publish the teardown runtime-db + fx ONLY if the exact owner survived
        ;; the WHOLE tail (rf2-hloj0g). If any post-`emit-destroyed!` callback
        ;; published same-id B, return the inert outcome — the A-derived
        ;; `db-after-destroy` is DROPPED rather than committed onto B (the
        ;; router's candidate fence would drop it too; returning inert is the
        ;; explicit contract, matching the top-level `owner-gone?` branch).
        ;; Machine snapshots are durable runtime-db state (EP-0001): the finalize
        ;; teardown is a runtime-db write, returned under `:rf.db/runtime` (the
        ;; framework-authority partition effect), NOT `:db`. Append the
        ;; destroy-time `:exit` cascade's fx + the resource-owner release for
        ;; this actor's `[:machine machine-id]` owner (nil + filtered out when
        ;; resources is absent — see `rf.machines.lifecycle-fx.resource-release/release-fx-entry`).
        (if (owner-gone?)
          {:rf.db/runtime runtime-db :fx []}
          {:rf.db/runtime db-after-destroy
           :fx (vec (concat extra-fx exit-fx
                            (when-let [e (rf.machines.lifecycle-fx.resource-release/release-fx-entry machine-id)]
                              [e])))}))))))
