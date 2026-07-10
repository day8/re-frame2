(ns re-frame.machines.lifecycle-fx.finalize
  "Final-state orchestration.

  Per Spec 005 §Final states: when a machine enters a `:final?`
  state, the runtime fires the parent's `:on-done` (if any) and auto-
  destroys the actor SYNCHRONOUSLY (D4). The orchestration:

    1. Resolve the final state-node from the post-transition snapshot
       (single / compound / parallel-all-regions-final).
    2. Read the child's `:data` slot designated by the final state's
       `:output-key` — call it `result`. Absent `:output-key` ⇒ nil.
    3. Look up the parent's spec at `[:rf.runtime/machines :snapshots <parent-id>]` and find
       the `:spawn` map at `:rf/invoke-id` (the invocation prefix-path the
       runtime stamped on the child's `:data` at spawn time). Extract
       `:on-done`.
    4. Run `:on-done` against the parent's `:data` with `result`.
    5. Emit `:rf.machine/done` trace (D6).
    6. Tear down the child: dissoc snapshot, clear `[:rf.runtime/machines :spawned ...]`
       slot, clear `[:rf.runtime/machines :system-ids <sid>]` (D8 — AFTER `:on-done` ran),
       emit `:rf.machine/destroyed` with `:reason :rf.machine/finished`
       (D6 enrichment), abort in-flight HTTP, unregister handler (D4).

  For singleton machines (no `:rf/parent-id` on `:data`): skip steps 3-4
  and emit a `:rf.machine/done` with `:parent-id nil` (D7 — singleton
  symmetry). The teardown still runs — the snapshot is dissoc'd, the
  `:system-id` reverse-index entry is cleared, and the handler is
  unregistered.

  This namespace also owns `abort-actor-in-flight-http!` — the late-bind
  hook into the http-managed artefact — the single home for
  the abort contract every destroy trigger shares: the finalize cascade,
  the spawn-destroy teardowns (`lifecycle-fx.destroy`), and the
  frame-destroy singleton-straggler pass (`lifecycle-fx.frame-destroy`).

  The actor-teardown app-db dance lives in
  `re-frame.machines.lifecycle-fx.teardown` — one helper, three
  call-sites."
  (:require [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.machines.error-emit :as machine-error-emit]
            [re-frame.machines.classification :as classification]
            [re-frame.machines.data-validation :as data-validation]
            [re-frame.machines.lifecycle-fx.resolver :as resolver]
            [re-frame.machines.lifecycle-fx.resource-release :as resource-release]
            [re-frame.machines.lifecycle-fx.spawn-error :as spawn-error]
            [re-frame.machines.lifecycle-fx.teardown :as teardown]
            [re-frame.machines.lifecycle-fx.traces :as traces]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.paths :as paths]
            [re-frame.machines.reply :as m-reply]
            [re-frame.machines.result :as result]
            [re-frame.machines.timer :as timer]
            [re-frame.machines.transition :as transition]
            [re-frame.registrar :as registrar]
            [re-frame.trace :as trace]))

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

(defn abort-actor-in-flight-http!
  "Fire the late-bind hook that aborts every in-flight `:rf.http/managed`
  request for the destroyed actor. Idempotent and safe to call when
  the http artefact is absent (returns nil)."
  [actor-id]
  (when actor-id
    (when-let [abort! (late-bind/get-fn :http/abort-on-actor-destroy)]
      (try (abort! actor-id)
           (catch #?(:clj Throwable :cljs :default) _ nil))))
  nil)

;; ---- per-instance [:schemas :data] marks cleanup ---------------------------
;;
;; A machine's `[:schemas :data]` schema is validation-only; it does not produce
;; a per-instance marks table. There is therefore no schema-marks entry to
;; clear at destroy — a destroyed actor leaves no schema-marks residue by
;; construction. A spawned actor's `:data` redaction (if any) rides the
;; frame's declared paths, the sole app-db redaction mechanism.

;; ---- final-state resolution -----------------------------------------------

;; Per Spec 005 §Final states §The done-state signal: the
;; `all-regions-final?` predicate lives in `re-frame.machines.parallel`
;; (shared by the parallel macrostep's done-signal / `:on-done` firing and
;; this whole-machine finalize path). Re-exported here so callers
;; (and the conformance corpus) reach it at the `finalize/all-regions-final?`
;; address. `finalize` requires `parallel`, never the reverse — no cycle.
(def all-regions-final? parallel/all-regions-final?)

(defn- region-final-leaf-nodes
  "Per Spec 005 §Final states + §Parallel regions: resolve
  every region's active final leaf-node from the post-transition `state`
  map (a `{region-name region-state}` map). Returns an ordered seq of
  `[region-name leaf-node]` in the state-map's iteration order — the basis
  for the cross-region `:output-key` scan below."
  [machine state]
  (map (fn [[region-name region-state]]
         [region-name
          (transition/node-at (get-in machine [:regions region-name])
                              (transition/state-path region-state))])
       state))

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
  arbitrary; first-in-state-order is the stable, documented tiebreak)."
  [machine state frame-id machine-id]
  (let [declared (->> (region-final-leaf-nodes machine state)
                      (filter (fn [[_ node]] (contains? node :output-key))))
        keys'    (distinct (map (fn [[_ node]] (:output-key node)) declared))]
    (when (> (count keys') 1)
      (trace/emit-error! :rf.error/machine-parallel-output-key-conflict
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
  first (state-map order) region final leaf that declares `:error? true`,
  or nil when no region's terminal is an error final.

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
  (let [exit-result   (parallel/run-active-exit-cascade machine next-snapshot)
        exit-ok?      (result/ok? exit-result)
        next-snapshot (if exit-ok? (result/snap exit-result) next-snapshot)
        exit-fx       (if exit-ok? (vec (result/fx exit-result)) [])
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
                        (assoc-in runtime-db (paths/snapshot-path machine-id) next-snapshot)
                        runtime-db)
        _             (when (not exit-ok?)
                        ;; Same destroy-exit failure shape as the explicit-
                        ;; destroy path (`exit-cascade/run-child-exit!`) —
                        ;; shared via `traces/emit-destroy-exit-failure!`.
                        (traces/emit-destroy-exit-failure!
                          machine-id frame-id (result/info exit-result)))]
  (let [child-data (:data next-snapshot)
        parallel?  (parallel/parallel? machine)
        ;; Resolve the ERROR-classifying leaf. For a PARALLEL
        ;; machine, scan EVERY region's final leaf — a parallel finish is an
        ;; ERROR when ANY region's reached final declares `:error? true`, not
        ;; only the first region's (Spec 005 §3060-§3061; XState v5 parallel
        ;; error-final semantics). For a flat machine the single leaf IS the
        ;; error-classifying leaf.
        error-node  (if parallel?
                      (parallel-error-leaf-node machine (:state next-snapshot))
                      (transition/node-at machine
                                          (transition/state-path (:state next-snapshot))))
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
        ;; EP-0029 A8 — validate the COMPLETION-OUTPUT payload against the
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
        ;; (`interop/debug-enabled?`-gated inside the validator).
        _           (data-validation/validate-completion-output! machine-id machine result)
        ;; Per Spec 005 §Final states §`:on-error` (XState v5 invoke
        ;; `onError`): a `:final?` leaf MAY also declare `:error? true` — a
        ;; designated ERROR terminal. When a `:spawn`-spawned child finishes
        ;; via an error leaf AND its spawning parent declares `:spawn :on-error`,
        ;; the runtime routes the failure to a PARENT TRANSITION (control flow,
        ;; not just observability) instead of the `:data`-only `:on-done`
        ;; callback. A plain `:final?` leaf keeps firing `:on-done`. `error-leaf?`
        ;; is computed above (cross-region scan for parallel).
        parent-id   (:rf/parent-id child-data)
        invoke-id   (:rf/invoke-id child-data)
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
                             :parent-id         parent-id
                             :work-bearing-path invoke-id
                             :frame             frame-id}
                      (some? completed-at) (assoc :completed-at completed-at))
        ;; (1) Find parent's `:on-done` / `:on-error`, if this is a `:spawn`-
        ;; spawned actor. The parent's spec carries the `:spawn` map at
        ;; `invoke-id`. Resolve the parent's spec from the registrar (a
        ;; singleton parent) OR, for a NESTED spawn whose parent is itself a
        ;; spawned actor (no per-instance registration), from the parent's own
        ;; snapshot `:rf/machine-type`.
        parent-path (paths/snapshot-path parent-id)
        parent-snap (when parent-id (get-in runtime-db parent-path))
        parent-reg  (when parent-id (registrar/lookup :event parent-id))
        parent-meta (when parent-id
                      (cond
                        (:rf/machine? parent-reg) (:rf/machine parent-reg)
                        :else                     (resolver/spec-from-snapshot parent-snap)))
        spawn-spec  (when (and parent-meta invoke-id)
                      (resolver/spawn-spec-at parent-meta invoke-id))
        on-done-fn  (:on-done spawn-spec)
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
        ;; LIVENESS is the gate (not `on-done-fn` resolvability): when the
        ;; parent was destroyed BOTH its snapshot AND — for a singleton
        ;; parent — its registrar handler are gone, so the `:spawn` spec
        ;; (and thus `on-done-fn`) is no longer resolvable AT ALL. Keying off
        ;; `on-done-fn` would miss the case entirely. The robust gate is: a
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
          (m-reply/actor-generation
            (get-in runtime-db (paths/spawned-path parent-id invoke-id))))
        reply       (cond
                      stale-spawn?
                      (m-reply/stale-spawn-reply
                        (assoc reply-ctx :current-generation current-generation))
                      error-leaf?
                      (m-reply/error-reply reply-ctx result)
                      :else
                      (m-reply/success-reply reply-ctx result))
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
        ;; `m-reply/trace-reply`.
        ;;
        ;; A STALE late completion (parent destroyed before the
        ;; child finished) additionally rides `:rf.reply/stale-reason`
        ;; (`:rf.machine/actor-not-live`) and `:rf.reply/correlation` (the
        ;; carried/current generation gate) — the parity counterpart of the
        ;; `:after` path's `:rf.machine.timer/stale-after` stale-suppression
        ;; trace. The summary is built via `m-reply/stale-spawn-trace` for a
        ;; stale reply so the spawn-stale path reads as the spawn analogue of
        ;; `after-stale-reply` → `trace-reply`.
        done-summary (if stale-spawn?
                       (m-reply/stale-spawn-trace reply {:frame frame-id})
                       (m-reply/trace-reply reply {:frame frame-id}))
        _ (trace/emit! :rf.machine :rf.machine/done
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
                                             :rf.reply/correlation  (:correlation done-summary))))
        ;; (3) Apply :on-done to the parent's `:data`. The parent's
        ;; snapshot lives at [:rf.runtime/machines :snapshots <parent-id>]; we read it,
        ;; pass the unified context-map (`{:data :result}`) to
        ;; `:on-done`, and write the new `:data` back. Per Spec 005
        ;; §Final states the callback receives
        ;; one context-map arg and returns the new `:data` map. An ERROR
        ;; leaf routing to `:on-error` SKIPS `:on-done` — the two spawn hooks
        ;; are mutually exclusive per finish (error-leaf → transition,
        ;; success-leaf → `:data` callback).
        ;;
        ;; When the parent was destroyed before the child
        ;; finished (`stale-spawn?` — no live `parent-snap`), the completion
        ;; is STALE: per Managed-Effects §Stale suppression the app target
        ;; (the `:on-done` callback) MUST NOT run, and there is nothing to
        ;; mutate. We suppress it explicitly here (rather than calling
        ;; `on-done-fn` against a nil parent `:data` and discarding the
        ;; result) so the suppression is a positive fact: the canonical
        ;; `:status :stale` reply was emitted on the done trace above, and
        ;; `runtime-db` rides through untouched.
        ;;
        ;; Per EP-0011 §Machine Completion the callback's `:result` is now
        ;; driven from the canonical reply's `:value` — the public callback
        ;; contract (`(fn [{data :data result :result}] new-data)`) is
        ;; unchanged; the value flows through the uniform reply envelope
        ;; internally. `(:value reply)` is `=` to the pre-lowering `result`
        ;; for a success leaf (behavioural parity), but it is sourced from
        ;; the one canonical reply map so the value the trace/ledger see and
        ;; the value the callback receives are the SAME fact.
        db-after-on-done
        (if (and on-done-fn parent-id (not on-error?) (not stale-spawn?))
          (let [parent-data     (:data parent-snap)
                new-parent-data (try
                                  (on-done-fn {:data parent-data :result (:value reply)})
                                  (catch #?(:clj Throwable :cljs :default) e
                                    ;; The `:on-done` callback IS a machine
                                    ;; action, so its throw rides the same
                                    ;; always-on-plus-dev-trace fan-out
                                    ;; (rf2-cprm0q): `:failing-id` is the
                                    ;; reserved `:rf.machine.spawn/on-done` slot id.
                                    ;; Axis 1 — STRUCTURAL-ONLY always-on
                                    ;; record (no prose; see error-emit).
                                    ;; `:state` is the final leaf the actor
                                    ;; rests on as its `:on-done` fired.
                                    (machine-error-emit/emit-machine-action-exception!
                                      {:actor-id   machine-id
                                       :failing-id :rf.machine.spawn/on-done
                                       :state      (:state next-snapshot)
                                       :frame      frame-id
                                       :recovery   :no-recovery
                                       :exception  e})
                                    ;; Axis 2 — dev-only trace. Wrapped in an
                                    ;; EXPLICIT `interop/debug-enabled?` call-site
                                    ;; gate so Closure constant-folds the whole
                                    ;; form — the PROSE `:reason` /
                                    ;; `:exception-message` included — away under
                                    ;; :advanced + goog.DEBUG=false. Routing
                                    ;; through the always-on helper (or leaving
                                    ;; the direct call ungated beside the live
                                    ;; always-on call above) would leak the prose.
                                    (when interop/debug-enabled?
                                      (trace/emit-error! :rf.error/machine-action-exception
                                        {:actor-id   machine-id
                                         :machine-id machine-id
                                         :action-id  :rf.machine.spawn/on-done
                                         :failing-id :rf.machine.spawn/on-done
                                         :state      (:state next-snapshot)
                                         :parent-id  parent-id
                                         :invoke-id  invoke-id
                                         :frame      frame-id
                                         :exception  e
                                         :exception-message
                                         #?(:clj  (.getMessage ^Throwable e)
                                            :cljs (.-message e))
                                         :reason     ":on-done callback threw."
                                         :recovery   :no-recovery}))
                                    parent-data))]
            (if (and parent-snap (some? new-parent-data))
              (assoc-in runtime-db (conj parent-path :data) new-parent-data)
              runtime-db))
          runtime-db)
        ;; (4) Apply the unified teardown projection:
        ;; dissoc the child's snapshot, release any `:system-id`
        ;; reverse-index entry (D8 — after on-done ran), and clear the
        ;; parent's `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` slot with
        ;; the lazy-allocation prune. Returns `[new-db released-sid]`;
        ;; `released-sid` is resolved against db-after-on-done before
        ;; the reverse index is mutated.
        [db-after-destroy released-sid]
        (teardown/teardown-actor db-after-on-done
                                 {:actor-id  machine-id
                                  :parent-id parent-id
                                  :invoke-id invoke-id})
        ;; (5) Emit :rf.machine/destroyed with :reason :rf.machine/finished
        ;; (D6 enrichment) BEFORE the registrar unregister so any in-flight
        ;; trace consumers see the destroy signal while the handler still
        ;; resolves.
        _ (traces/emit-destroyed! {:frame     frame-id
                                   :actor-id  machine-id
                                   :system-id released-sid
                                   :parent-id parent-id
                                   :invoke-id invoke-id
                                   :reason    :rf.machine/finished})]
    ;; (6) Synchronous side effects: abort in-flight HTTP, cancel
    ;; armed `:after` timers (`:reason :on-destroy`), emit
    ;; system-id-released trace (when applicable), unregister handler.
    (abort-actor-in-flight-http! machine-id)
    (timer/cancel-actor-timers! frame-id machine-id)
    ;; EP-0025 §subsystems (rf2-h3d8tf): DROP this actor's per-instance
    ;; classification declarations from the per-frame elision registry — the
    ;; teardown half of `classification/lower-at-spawn!`, on the final-state
    ;; AUTO-DESTROY path (which does NOT route through
    ;; `destroy/teardown-live-actor!`). `machine` is the finishing actor's
    ;; runtime-stamped spec; a spec that declared no classification is a clean
    ;; no-op, so the registry entry added at spawn dies with the instance (no
    ;; leak).
    (classification/drop-at-destroy! frame-id machine-id machine)
    (traces/emit-system-id-released! frame-id released-sid machine-id)
    (registrar/unregister! :event machine-id)
    ;; (7) Per Spec 005 §Final states §`:on-error`: when the child
    ;; finished via an ERROR leaf AND the spawning parent declares
    ;; `:spawn :on-error`, dispatch the synthetic reserved failure event into
    ;; the parent. It resolves natively through the parent's macrostep
    ;; (`pick-spawn-error-transition`), firing the declarative `:on-error`
    ;; transition (target / guard / actions) with the error payload on
    ;; `:event` — the XState `invoke onError` control flow. Dispatched (not
    ;; raised) because the parent is a SEPARATE actor with its own handler /
    ;; snapshot — symmetric with how the spawn fx dispatches `:start` into the
    ;; child. The teardown above already ran (the child auto-destroys on its
    ;; error leaf, like any `:final?`); this only ROUTES the failure.
    ;;
    ;; Per EP-0011 §Machine Completion this is the `:status :error` reply
    ;; driving the `:on-error` transition. The dispatched payload is the RAW
    ;; error (`result`) — the PUBLIC contract: the parent transition reads it
    ;; off `:event` (`(nth ev 2)`). The canonical reply map (`reply`, built
    ;; above) classifies the completion as `:status :error` for the trace /
    ;; ledger; the parent transition's observable payload is unchanged. The
    ;; reply's `:error` slot is `{:kind … :value result}` (the family-`:kind`
    ;; wrapping the reply-map schema requires) — but that wrapping is
    ;; internal vocabulary, not what the parent transition sees.
    (when on-error?
      (spawn-error/dispatch-spawn-error! frame-id parent-id invoke-id result))
    ;; Machine snapshots are durable runtime-db state (EP-0001):
    ;; the finalize teardown is a runtime-db write, returned under
    ;; `:rf.db/runtime` (the framework-authority partition effect), NOT `:db`.
    {:rf.db/runtime db-after-destroy
     ;; Append the destroy-time `:exit` cascade's fx to
     ;; the transition's fx vector so any `:exit`-emitted dispatches /
     ;; HTTP / etc. fire as part of the same epoch.
     ;;
     ;; Also append the resource-lease release for this actor's
     ;; `[:machine machine-id]` owner so the `:final?`-state auto-destroy
     ;; releases its leases too (the explicit-destroy / spawn-cascade /
     ;; frame-destroy paths release via `teardown-live-actor!`; finalize is the
     ;; one teardown that returns an `:fx` vector rather than firing fx
     ;; imperatively, so it appends the entry here). nil + filtered out when
     ;; resources is absent (the no-resources guard) — see
     ;; `resource-release/release-fx-entry`.
     :fx (vec (concat extra-fx exit-fx
                      (when-let [e (resource-release/release-fx-entry machine-id)]
                        [e])))})))
