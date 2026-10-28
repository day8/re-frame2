(ns re-frame.machines.lifecycle-fx.finalize
  "Final-state orchestration — where a machine's COMPLETION is produced.

  Per Spec 005 §Final states: when a SPAWNED actor enters whole-machine
  finality the runtime reaps it SYNCHRONOUSLY (D4) and routes its result to
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
    4. Tear down the child INSTANCE: dissoc snapshot, clear
       `[:rf.runtime/machines :spawned ...]` slot,
       emit `:rf.machine/destroyed` with `:reason :rf.machine/finished`
       (D6 enrichment), abort in-flight HTTP, and clear any PER-INSTANCE
       registrar entry — never a `reg-machine` DEFINITION, which is a
       load-time TYPE outliving every instance.
    5. Mint the completion carrier into the parent — the reserved event
       `[<parent-id> [:rf.machine.spawn/done <invoke-id> <completion>]]`
       (`dispatch-spawn-done!`), or, for the ERROR leaf of a single-`:spawn`
       child, the reserved failure event instead — whether or not the parent
       declares `:on-error`. The parent's handler boundary
       routes the completion to its `:spawn :on-done` fold or to the
       `:spawn-all` join fold; because it is an EVENT, the parent may also
       ADVANCE on it (`:always`, or an explicit `:on` clause) rather than only
       folding data.

  The carrier is minted LAST, after teardown, so the parent never observes a
  completed child that is still alive.

  A singleton (an actor no spawn installed, so its `:rf/runtime` carries no
  `:machine-type`) is never reaped: `rest-machine` emits its
  `:rf.machine/done` with `:parent-id nil` on the transition into finality and
  leaves the snapshot resting at its address, readable and inert unless its
  definition declares a way out (D7).

  This namespace also owns `abort-actor-in-flight-http!` — the late-bind
  hook into the http-managed artefact — the single home for
  the abort contract every destroy trigger shares: the finalize cascade,
  the spawn-destroy teardowns (`lifecycle-fx.destroy`), and the
  frame-destroy singleton-straggler pass (`lifecycle-fx.frame-destroy`).
  It takes the DESTROYING FRAME as well as the actor address;
  all three call sites already hold one.

  The actor-teardown runtime-db projection lives in
  `re-frame.machines.lifecycle-fx.teardown` — one helper, three
  call-sites."
  (:require [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines.error-emit :as rf.machines.error-emit]
            [re-frame.machines.data-validation :as rf.machines.data-validation]
            [re-frame.machines.lifecycle-fx.resolver :as rf.machines.lifecycle-fx.resolver]
            [re-frame.machines.lifecycle-fx.resource-release :as rf.machines.lifecycle-fx.resource-release]
            [re-frame.machines.lifecycle-fx.spawn-error :as rf.machines.lifecycle-fx.spawn-error]
            [re-frame.machines.lifecycle-fx.teardown :as rf.machines.lifecycle-fx.teardown]
            [re-frame.machines.lifecycle-fx.traces :as rf.machines.lifecycle-fx.traces]
            [re-frame.machines.parallel :as rf.machines.parallel]
            [re-frame.machines.paths :as rf.machines.paths]
            [re-frame.machines.result :as rf.machines.result]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.machines.transition :as rf.machines.transition]
            [re-frame.machines.work :as rf.machines.work]
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
;; The abort is FRAME-EXACT. A spawned actor's address is
;; frame-LOCAL (Spec 014 §Abort on actor destroy §Frame scope), so two
;; isolated frames running the same application code spawn actors under the
;; same address. The hook's frame-bearing arity narrows the sweep to the
;; destroying frame's slot; passing an address alone would take the hook's
;; ANY-FRAME arity and reach a sibling frame's live request. This helper
;; therefore REQUIRES the frame rather than defaulting it — every destroy
;; trigger in this artefact already has one in scope, so there is no
;; frame-less call to make, and the correct call is the only one that
;; compiles.

(defn abort-actor-in-flight-http!
  "Fire the late-bind hook that aborts every in-flight `:rf.http/managed`
  request the destroyed actor issued IN `frame-id`. A same-named actor in a
  sibling frame is untouched. Idempotent and safe to call when the http
  artefact is absent (returns nil).

  Both ids are required. The hook call is wrapped in a
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
;; construction. A spawned actor's `:data` redaction is its machine's
;; declaration, applied at egress to the snapshot while it is present.

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
  off its `[:rf/runtime :join-child]` membership record (`:child-id` / `:spawned-id` /
  `:attempt` / `:work-generation`), which is what lets the parent's join fold
  reject a straggler from a superseded attempt.

  `:error?` is true only for a JOIN child: the join counts
  failures itself, into `:failed` and `:on-any-failed`. A single-`:spawn`
  child's failure rides `dispatch-spawn-error!` instead, so what this carrier
  hands a single-`:spawn` parent — at its `:on-done` fold and at any
  `:rf.machine.spawn/done` transition — is always a success.

  Dispatched (not raised) because the parent is a SEPARATE actor — symmetric
  with `dispatch-spawn-error!` and with how the spawn fx dispatches `:start`
  into a newborn child. It queues through the shared carrier seam
  `spawn-error/dispatch-carrier!`, so it is a child of the event that FINISHED
  the child actor and inherits that envelope's run propagation (Spec 002 §Run
  propagation). It keeps `:source :machine-spawn` so the Epoch panel
  attributes it to the spawn lifecycle. No-op when the `:router/dispatch!` hook
  is absent (pure-fn / conformance callers).

  `attempt` is a single-`:spawn` child's `[:rf/runtime :invoke-attempt]`.
  When present it rides as a FOURTH element, leaving `<completion>` unchanged,
  so the parent's boundary folds `:on-done` only for the CURRENT attempt."
  ([frame-id parent-id invoke-id completion]
   (dispatch-spawn-done! frame-id parent-id invoke-id completion nil))
  ([frame-id parent-id invoke-id completion attempt]
   (rf.machines.lifecycle-fx.spawn-error/dispatch-carrier!
     frame-id
     [parent-id (cond-> [rf.machines.transition/spawn-done-event-id invoke-id completion]
                  (some? attempt) (conj attempt))])))

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
  the first region's. Returns the first (canonical region-declaration
  order) region leaf that declares an `:output-key`, or nil when no region
  designates output.

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

;; ---- completion ---------------------------------------------------------

(defn- completion-output
  "Classify a finished machine's terminal and select its result, from the
  finished `snapshot`. Returns `{:error-leaf? <bool> :result <value>}`.

  The ERROR-classifying leaf of a PARALLEL machine is ANY region's final leaf
  declaring `:error? true` (XState v5 parallel error-final semantics); a flat
  machine's single leaf is its own. Its `:output-key` may likewise live on any
  region's leaf: an error finish reads the error region's, so the error
  payload is the one that routes to `:on-error`; a success finish scans every
  region (`parallel-output-key`, which reports a conflict). No `:output-key`
  means a nil result."
  [machine machine-id frame-id snapshot]
  (let [state       (:state snapshot)
        parallel?   (rf.machines.parallel/parallel? machine)
        error-node  (if parallel?
                      (parallel-error-leaf-node machine state)
                      (rf.machines.transition/node-at machine
                                                      (rf.machines.transition/state-path state)))
        error-leaf? (true? (:error? error-node))
        output-key  (cond
                      (and parallel? error-leaf?) (:output-key error-node)
                      parallel?                   (parallel-output-key machine state frame-id machine-id)
                      :else                       (:output-key error-node))]
    {:error-leaf? error-leaf?
     :result      (when output-key (get (:data snapshot) output-key))}))

(defn- emit-done!
  "Emit `:rf.machine/done`, the actor-finality signal, for every finish:
  success, error and stale alike, since `:on-error` is additive. The public
  slots in `done` (`:actor-id` the finishing INSTANCE address, `:output`,
  `:parent-id`, `:error?`, `:frame`) ride beside the facts of the completion's
  `work` record (Spec 005 §Machine completions emit work records), every key
  spelled at this one emit site, so the wire-vocabulary conformance suite finds
  each as data here. The `:rf.reply/work-id` joins the completion into Xray's
  work rows. The causal `:rf.reply/completed-at` rides only when the trigger
  recorded a time, and a STALE completion additionally carries
  `:rf.reply/stale-reason` and the carried/current `:rf.reply/correlation`."
  [{:keys [actor-id output parent-id error? frame]} work]
  (rf.trace/emit! :rf.machine :rf.machine/done
                  (cond-> {:actor-id             actor-id
                           :output               output
                           :parent-id            parent-id
                           :error?               error?
                           :frame                frame
                           :rf.reply/work-kind   (:work-kind work)
                           :rf.reply/status      (:status work)
                           :rf.reply/work-id     (:work-id work)
                           :rf.reply/work-status (:work-status work)}
                    (contains? work :completed-at)
                    (assoc :rf.reply/completed-at (:completed-at work))
                    (contains? work :stale-reason)
                    (assoc :rf.reply/stale-reason (:stale-reason work)
                           :rf.reply/correlation  (:correlation work)))))

(defn rest-machine
  "Per Spec 005 §Final states: a SINGLETON that enters whole-machine finality
  RESTS there. Only a spawned actor is reaped at finality (`finalize-machine`);
  a singleton's snapshot stays at its address, so `[:rf/machine id]` keeps
  reading the final configuration, and an event its definition declares no
  way out for is an ordinary unhandled no-op rather than a re-birth.

  Completion is the `:rf.machine/done` trace with `:parent-id nil`, emitted
  here, on the transition INTO finality — the caller routes a macrostep that
  starts final elsewhere, so completion fires once per entry into finality.
  Its result is validated against `[:schemas :output]` exactly as a spawned
  child's is. Nothing is torn down and no carrier is minted: a singleton has
  no parent. Returns the ordinary commit `{:rf.db/runtime runtime-db :fx fx}`."
  [machine machine-id frame-id runtime-db next-snapshot fx]
  (let [{:keys [error-leaf? result]} (completion-output machine machine-id frame-id next-snapshot)
        _            (rf.machines.data-validation/validate-completion-output! machine-id machine result)
        completed-at (get-in machine [:rf/cofx :rf/time-ms])]
    (emit-done! {:actor-id  machine-id
                 :output    result
                 :parent-id nil
                 :error?    error-leaf?
                 :frame     frame-id}
                (rf.machines.work/record (rf.machines.work/spawn-work-id machine-id nil)
                                         (if error-leaf? :error :ok)
                                         {:completed-at completed-at}))
    {:rf.db/runtime runtime-db
     :fx            fx}))

;; ---- the orchestrator ------------------------------------------------------

(defn finalize-machine
  "Per Spec 005 §Final states: orchestrate the `:on-done` +
  auto-destroy cascade. Returns `{:rf.db/runtime new-runtime-db :fx fx}` —
  the snapshot teardown is a durable runtime-db write (EP-0001),
  returned under the framework-authority `:rf.db/runtime` partition, NOT
  `:db` (see the return at `finalize-machine!`'s tail). It is a SPAWNED
  actor's handler return value when the post-transition snapshot has finished (its active
  leaf is a top-level `:final?` state, or — for a parallel machine — every
  region's active leaf is `:final?`). Finality is RECOMPUTED by the caller
  (`commit-or-finalize`) from the post-transition `:state` via
  `top-level-final?` / `all-regions-final?`; it is NOT carried on the
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
  (let [;; A destroy of the owning frame requested by the completion-output
        ;; validator or the parent `:on-done` callback waits for the event
        ;; boundary, so this whole tail runs against the incarnation it
        ;; started in.
        child-rt   (:rf/runtime next-snapshot)
        {:keys [error-leaf? result]} (completion-output machine machine-id frame-id next-snapshot)
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
        ;; Routes through the SAME late-bound `:schemas/check!` seam the
        ;; `:where :machine-data` boundary uses (Spec 010 §The validation
        ;; seam); an app with no schemas artefact pays zero cost. `machine` is
        ;; the finishing actor's runtime-stamped spec held directly by this
        ;; cascade, so the `[:schemas :output]` schema resolves off it without
        ;; a registrar / snapshot lookup. Production-elided
        ;; (`rf.interop/debug-enabled?`-gated inside the validator).
        _           (rf.machines.data-validation/validate-completion-output! machine-id machine result)
        ;; Per Spec 005 §Final states §`:on-error` (XState v5 invoke
        ;; `onError`): a `:final?` leaf MAY also declare `:error? true` — a
        ;; designated ERROR terminal. When a `:spawn`-spawned child finishes
        ;; via an error leaf, the runtime routes the failure to the parent as
        ;; the FAILURE event, resolved by the parent's `:spawn :on-error` or an
        ;; explicit `:on {:rf.machine.spawn/error …}` (control flow, not just
        ;; observability), and never to the `:data`-only `:on-done` callback.
        ;; A plain `:final?` leaf fires `:on-done`. `error-leaf?` is
        ;; computed above (cross-region scan for parallel).
        ;; A `:spawn-all` child's private membership is the canonical WORK
        ;; source of its parent/invoke coordinates (per-child spawn args use the
        ;; distinct `:rf/spawn-all-id`, so no public single-spawn `:invoke-id`
        ;; stamp is required) and the runtime-minted exact-attempt coordinate.
        ;; Keep the ordinary parent/invoke locals unchanged: they also govern
        ;; single-`:spawn` callbacks + teardown-slot pruning, and a join child
        ;; must not enter either path merely because work identity needs the
        ;; membership coordinates.
        join-child  (:join-child child-rt)
        parent-id   (:parent-id child-rt)
        invoke-id   (:invoke-id child-rt)
        work-parent-id (or parent-id (:parent-id join-child))
        work-invoke-id (or invoke-id (:invoke-id join-child))
        ;; Thread that coordinate into the SAME
        ;; canonical machine work-id generation slot used by its join fold, so
        ;; a fixed-id final leaf cannot publish a parallel generation-1 arc.
        ;; A single-`:spawn` child has no membership record; its spawn
        ;; stamped the attempt's exact generation as `:work-generation`.
        ;; A hand-emitted spawn carries neither and keeps the actor-id
        ;; convention.
        work-generation (if-some [g (or (:work-generation join-child)
                                        (:work-generation child-rt))]
                          g
                          (rf.machines.work/actor-generation machine-id))
        ;; The completion's work id: `[:rf.work/machine actor-id
        ;; work-bearing-path generation]` (Spec 005 §Machine completions emit
        ;; work records).
        work-id      (rf.machines.work/spawn-work-id machine-id work-invoke-id work-generation)
        ;; The CAUSAL completion timestamp: the one host-clock read the router
        ;; captured for the finishing event, threaded onto the machine def
        ;; under `:rf/cofx` (lifecycle-fx.registration) — never an ambient
        ;; `(.now)` (Managed-Effects §Causal completion metadata). nil when the
        ;; trigger was unscripted (no recordable cofx), and then the record
        ;; omits it.
        completed-at (get-in machine [:rf/cofx :rf/time-ms])
        ;; (1) Choose the carrier. The ERROR leaf of a single-`:spawn` child
        ;; (it carries an `:invoke-id`) rides the FAILURE carrier whatever the
        ;; parent declared. The parent's engine resolves it
        ;; natively (`pick-spawn-error-transition`): its `:spawn :on-error`,
        ;; else an explicit `:on {:rf.machine.spawn/error …}` walked leaf to
        ;; root and then the root `:on`, else nothing. So the done carrier
        ;; only ever carries a success, and the `:on-done` fold and any
        ;; `:on {:rf.machine.spawn/done …}` need no failure guard.
        ;;
        ;; The gate does not ask whether the parent's `:spawn` map declares
        ;; `:on-error`: a failure without one would then ride the DONE carrier
        ;; into the success fold and the success transitions. A `:spawn-all` join
        ;; child carries no `:invoke-id`, so its error leaf stays on the
        ;; done carrier with `:error? true`, which the join counts itself.
        failure-carrier? (and error-leaf? parent-id invoke-id)
        ;; Per Managed-Effects §Stale suppression: the one machine-supersession
        ;; case reachable here is a `:spawn`-spawned child reaching `:final?`
        ;; AFTER its spawning parent was already DESTROYED. The completion is
        ;; then STALE — its `:on-done` / `:on-error` routing has no live parent
        ;; to drive, so the app target MUST NOT run, and the completion's work
        ;; record is `:status :stale` / `:work-status :suppressed` with its
        ;; stale reason, never a bare `:ok`.
        ;;
        ;; LIVENESS is the gate (not `:on-done` resolvability): keying off the
        ;; callback would miss the case entirely. The robust gate is: a
        ;; declaratively-spawned child (it carries both a `:parent-id` and
        ;; an `:invoke-id`) whose parent is NO LONGER LIVE. It gates BOTH
        ;; carriers — the `:on-done` completion event AND the `:on-error`
        ;; failure event. A stale error leaf has no void to dispatch into:
        ;; because the DEFINITION survives teardown (below), the dispatch
        ;; would RESOLVE at the dead parent's address and D5 lazy re-creation
        ;; would synthesise a fresh initial snapshot, RESURRECTING a destroyed
        ;; parent to fold a dead child's failure into it. Spec 005
        ;; §Spawned-actor completion names `:on-error` routing explicitly
        ;; among the app targets a stale completion MUST NOT run.
        ;;
        ;; A DEFINITION-BEARING REGISTRAR ENTRY IS NOT LIVENESS, and
        ;; that is load-bearing here rather than a tidy-up. A destroyed
        ;; SINGLETON parent keeps its `reg-machine` DEFINITION (the registration
        ;; is the load-time PROGRAM; the snapshot was the INSTANCE), so reading
        ;; the bare registrar entry as liveness would report a destroyed parent
        ;; LIVE, skip the stale gate, and dispatch the completion carrier at its
        ;; address — where the surviving definition would synthesise a fresh
        ;; initial snapshot and RESURRECT the parent to fold a dead child's
        ;; result into it. Spec 005 §Destroy is silent-idempotent forbids
        ;; exactly this reading; the same rule stands at `destroy/
        ;; actor-live?`. Resolving the parent's `:spawn` spec is what the
        ;; DEFINITION is FOR, and the parent's own engine does that when the
        ;; carrier arrives; only the liveness question is asked here. A
        ;; non-machine entry squatting at the parent address counts.
        ;;
        ;; The predicate is SHARED with the action-exception producer
        ;; (`registration`'s child-action-failure projection), which asks the
        ;; identical question about the identical address. Two spellings of
        ;; "is the parent live?" could disagree, so both failure routes read
        ;; ONE predicate, and `parent-instance-live?` reads exactly the two
        ;; signals named above.
        ;;
        ;; The gate reads the WORK coordinates, which fall
        ;; back to the `:join-child` membership record. A `:spawn-all` join
        ;; child never carries a public `:invoke-id`, so a gate keyed on
        ;; `invoke-id` would always be false for it: a join child finishing after
        ;; its parent's explicit destroy would dispatch its carrier at the dead
        ;; address, and the surviving definition would RESURRECT the parent.
        parent-live?  (rf.machines.lifecycle-fx.spawn-error/parent-instance-live?
                        runtime-db work-parent-id)
        stale-spawn?  (and work-parent-id work-invoke-id (not parent-live?))
        ;; The carried generation is THIS finishing attempt's work-id
        ;; generation; the CURRENT generation is the LIVE counterpart — the
        ;; generation of the actor currently occupying the spawn slot at
        ;; `[:rf.runtime/machines :spawned parent-id invoke-id]`, read ONLY
        ;; when the parent is still live. With the parent gone there is no
        ;; live counterpart, so `:current` is nil — exactly the supersession
        ;; the gate records (the parity counterpart of the `:after` path's
        ;; exited-node → nil declaring path). The carried/current pair rides
        ;; the stale record's correlation.
        current-generation
        (when (and parent-live? parent-id invoke-id)
          (rf.machines.work/actor-generation
            (get-in runtime-db (rf.machines.paths/spawned-path parent-id invoke-id))))
        work        (if stale-spawn?
                      (rf.machines.work/record
                        work-id :stale
                        {:stale-reason :rf.machine/actor-not-live
                         :correlation  (assoc (rf.machines.work/actor-correlation
                                                machine-id work-parent-id work-invoke-id)
                                              :generation {:carried work-generation
                                                           :current current-generation})
                         :completed-at completed-at})
                      (rf.machines.work/record
                        work-id (if error-leaf? :error :ok)
                        {:completed-at completed-at}))
        ;; (2) Emit `:rf.machine/done` trace BEFORE the destroy cascade
        ;; (D6 ordering). Fired for EVERY finish (success / error / STALE) —
        ;; `:on-error` is additive, the done trace is the actor-finality
        ;; signal regardless of which spawn hook routes the result. The work
        ;; record's facts ride beside the public `:output` / `:parent-id` /
        ;; `:error?` slots, so the trace stream classifies the completion the
        ;; same way it does HTTP and resources. A STALE completion's
        ;; `:rf.reply/stale-reason` and carried/current generation
        ;; `:rf.reply/correlation` are the spawn counterpart of the `:after`
        ;; path's `:rf.machine.timer/stale-after` trace.
        _ (emit-done! {:actor-id  machine-id
                       :output    result
                       :parent-id parent-id
                       :error?    error-leaf?
                       :frame     frame-id}
                      work)
        ;; (3) The completion carrier. `:on-done` is NOT applied here.
        ;; Completion is delivered to the parent as ONE reserved runtime-minted
        ;; event (Spec 005 §Child completion protocol) — the same carrier for
        ;; both spawn forms — and the parent's own handler boundary routes it:
        ;;
        ;;   - a `:spawn` child's completion runs the parent's `:spawn :on-done`
        ;;     fold against the parent's `:data` and then flows into the
        ;;     parent's ORDINARY macrostep, so the parent may ADVANCE on it
        ;;     through `:always` (a guard over the folded `:data`) or an
        ;;     explicit `:on` clause. That advancement is the whole reason the
        ;;     carrier is an event rather than a direct parent-snapshot write:
        ;;     with a direct write a parent could fold a child's
        ;;     result but could not react to it, so every sequencing parent would
        ;;     have to make its child hand-dispatch — which is exactly the parent
        ;;     vocabulary in the child that this protocol keeps out;
        ;;   - a `:spawn-all` join child's completion runs its per-child
        ;;     `:on-done` fold for a `:done` completion only (a failed child
        ;;     skips it) and then folds into the join
        ;;     (`lifecycle-fx.join/intercept-spawn-done-event`).
        ;;
        ;; The carrier is minted AFTER the teardown below, so the parent never
        ;; observes a completed child that is still alive: finality means
        ;; teardown (D4), and the result rides the event rather than the
        ;; child's snapshot.
        ;;
        ;; When the parent was destroyed before the child finished
        ;; (`stale-spawn?` — no live parent INSTANCE), the completion is STALE:
        ;; per Managed-Effects §Stale suppression the app target MUST NOT run,
        ;; so NEITHER carrier is minted — not the completion event below, and
        ;; not the `:on-error` failure event beside it. The suppression is a
        ;; positive fact — the `:status :stale` work record was emitted on the
        ;; done trace above, and `runtime-db` rides through untouched.
        ;;
        ;; The delivered `:result` is the child's `:output-key` value for a
        ;; success and an error leaf alike, so under a `:spawn-all` parent the
        ;; resolution carries "the decisive child's `:output-key` value — its
        ;; error payload on `:on-any-failed`" (Spec 005 §Spawn-and-join).
        ;; (4) Apply the unified teardown projection: dissoc the child's
        ;; snapshot and clear the parent's
        ;; `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` slot
        ;; with the lazy-allocation prune.
        db-after-destroy
        (rf.machines.lifecycle-fx.teardown/teardown-actor runtime-db
                                 {:actor-id  machine-id
                                  :parent-id parent-id
                                  :invoke-id invoke-id})]
    ;; (5) Emit :rf.machine/destroyed with :reason :rf.machine/finished
    ;; (D6 enrichment) before registrar cleanup.
    (rf.machines.lifecycle-fx.traces/emit-destroyed! {:frame     frame-id
                             :actor-id  machine-id
                             :parent-id parent-id
                             :invoke-id invoke-id
                             :reason    :rf.machine/finished})
    ;; (6) Abort in-flight HTTP (late-bound hook — callback-bearing).
    ;; Frame-exact: `frame-id` is this cascade's own frame, so
    ;; a same-named actor in a sibling frame keeps its in-flight work.
    (abort-actor-in-flight-http! frame-id machine-id)
    ;; Cancel armed `:after` timers (`:reason :on-destroy`).
    (rf.machines.timer/cancel-actor-timers! frame-id machine-id)
    ;; `rf.registrar/unregister!` emits a synchronous callback-bearing
    ;; `:rf.registry/handler-cleared` trace.
    ;;
    ;; EXCEPT when the address carries a machine DEFINITION.
    ;; That entry is the shared load-time TYPE every
    ;; `[:rf.machine/spawn {:machine-id <id>}]` resolves through — not this
    ;; instance's own. Finality ends the INSTANCE; the definition
    ;; is the PROGRAM that made the address creatable and outlives it
    ;; (Spec 005 §Liveness is derived from runtime-db, D4/D7). `rf/clear`
    ;; remains the public spelling for permanent removal. The identical
    ;; gate stands at `destroy/teardown-live-actor!` step (7); a
    ;; non-machine entry squatting here still gets cleared.
    (when (nil? (rf.machines.lifecycle-fx.resolver/spec-from-registry machine-id))
      (rf.registrar/unregister! :event machine-id))
    ;; (7) ROUTE THE COMPLETION TO THE PARENT. The child is fully torn
    ;; down by now — finality means teardown (D4) — so what reaches the
    ;; parent is a value, never a live child.
    ;;
    ;; Two carriers, chosen by how the child finished and which spawn
    ;; form spawned it — never by which hooks the parent declared:
    ;;
    ;;   - Per Spec 005 §Final states §`:on-error`: an ERROR leaf under a
    ;;     single `:spawn` routes to the reserved FAILURE event, which
    ;;     resolves natively through the parent's macrostep
    ;;     (`pick-spawn-error-transition`) — its declarative `:on-error`
    ;;     transition (the XState `invoke onError` control flow), else an
    ;;     explicit `:on {:rf.machine.spawn/error …}`, else nothing. That
    ;;     carrier keeps its own id because an uncaught child ACTION
    ;;     EXCEPTION produces it too, and an exception is not a
    ;;     completion. The dispatched payload is the RAW error (`result`);
    ;;     the parent transition reads it off `:event`.
    ;;
    ;;   - Otherwise the reserved COMPLETION event (Spec 005 §Child
    ;;     completion protocol) — the one carrier both spawn forms share.
    ;;     A `:spawn` child sends its `:invoke-id` and its SUCCESS result,
    ;;     and the parent folds `:on-done` then advances on its ordinary
    ;;     macrostep. A `:spawn-all` join child additionally sends the
    ;;     exact-attempt coordinate off its `:join-child` membership
    ;;     record, and the parent folds it into the join. An ERROR leaf
    ;;     under a `:spawn-all` parent rides this same carrier with
    ;;     `:error? true` — failure control flow under a join is
    ;;     `:on-any-failed`, not a per-child transition, and the join
    ;;     skips that child's `:on-done` fold.
    ;;
    ;; A STALE completion (the parent died first) mints NEITHER: there is
    ;; no live parent to receive it, and §Stale suppression says the app
    ;; target must not run. A SINGLETON (no parent at all) mints neither
    ;; either — its finality is its own.
    ;;
    ;; That "NEITHER" is enforced ONCE, around the whole
    ;; choice, rather than on the completion arm alone: a failure arm
    ;; outside the stale guard would re-create a dead parent from its
    ;; initial snapshot to receive its child's failure. It takes both
    ;; halves — the guard around both arms, and a classification that
    ;; calls such a finish `:stale` rather than `:error`.
    ;;
    ;; A single-`:spawn` child also hands back its
    ;; `:invoke-attempt` on EITHER carrier, so the parent delivers it
    ;; only while that spawn attempt is still current.
    (when-not stale-spawn?
      (cond
        failure-carrier?
        (rf.machines.lifecycle-fx.spawn-error/dispatch-spawn-error!
          frame-id parent-id invoke-id result (:invoke-attempt child-rt))

        parent-id
        (when-let [target-invoke-id (or invoke-id (:invoke-id join-child))]
          (dispatch-spawn-done!
            frame-id parent-id target-invoke-id
            (cond-> {:result       result
                     :error?       error-leaf?
                     :completed-at completed-at}
              join-child (merge (select-keys join-child
                                             [:parent-id :invoke-id :child-id
                                              :spawned-id :attempt
                                              :work-generation])))
            (:invoke-attempt child-rt)))))
    ;; Machine snapshots are durable runtime-db state (EP-0001): the finalize
    ;; teardown is a runtime-db write, returned under `:rf.db/runtime` (the
    ;; framework-authority partition effect), NOT `:db`. Append the
    ;; destroy-time `:exit` cascade's fx, then the destroy of the root's
    ;; own `:spawn` child — the one child no transition exits, so it ends
    ;; here, after the root's `:exit` has read it live, whether or not
    ;; that `:exit` threw — then the resource-owner release for this
    ;; actor's `[:machine machine-id]` owner (nil + filtered out when
    ;; resources is absent — see `rf.machines.lifecycle-fx.resource-release/release-fx-entry`).
    {:rf.db/runtime db-after-destroy
     :fx (vec (concat extra-fx exit-fx
                      (rf.machines.transition/root-destroy-fx machine machine-id)
                      (when-let [e (rf.machines.lifecycle-fx.resource-release/release-fx-entry machine-id)]
                        [e])))})))
