(ns re-frame.machines.lifecycle-fx.registration
  "Registration boundary: handler factory + `reg-machine*`.

  `make-machine-handler` is the `reg-event` handler factory beneath the
  `reg-machine` macro (EP-0018 one-event surface — a single
  `(cofx, event) -> effects-map-or-nil` handler, no `reg-event-fx` /
  `reg-event-db` split); `reg-machine*` is the plain-fn surface used by
  the late-bind table and by REPL workflows. The factory decomposes
  into:

    - `validate-machine!` — every registration-time check (extracted to
      `re-frame.machines.lifecycle-fx.validation`: parallel shape,
      `:spawn-all` shape, dropped `:timeout-ms` slots, guard/action
      ref resolution, final-state shape).
    - `parallel/build-initial-snapshot` (rf2-fgqs4) — initial-state
      cascade, `:data` / `:meta` / `:rf/spawn-counter` seeding, tag union
      stamping (lazily, on first event). Single source of truth shared
      with the spawn path.
    - the returned handler fn — frame stamping, `intercept-spawn-all-
      event` branch (in `lifecycle-fx.join`), bootstrap-pending
      detection + initial-entry cascade, machine-transition dispatch,
      action-failure projection, finalize delegation (in
      `lifecycle-fx.finalize`)."
  (:require [re-frame.cofx :as cofx]
            [re-frame.error :as error]
            [re-frame.events :as events]
            [re-frame.frame :as frame]
            [re-frame.machines.cofx-attach :as cofx-attach]
            [re-frame.machines.lifecycle-fx.finalize :as finalize]
            [re-frame.machines.lifecycle-fx.join :as join]
            [re-frame.machines.lifecycle-fx.resolver :as resolver]
            [re-frame.machines.lifecycle-fx.spawn-error :as spawn-error]
            [re-frame.machines.lifecycle-fx.validation :as validation]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.paths :as paths]
            [re-frame.machines.result :as result
             #?@(:cljs [:include-macros true])]
            [re-frame.machines.transition :as transition]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- single-registration-home flag (rf2-genufr) ---------------------------
;;
;; A machine that carries a `:data-schema` MUST flow through the single
;; registration home so the `:rf/machine?` / `:rf/machine` registration-
;; metadata stamp runs — the `:where :machine-data` post-commit walker resolves
;; the `:data-schema` THROUGH `(machine-meta id)`, so without the stamp the
;; schema validates NOTHING.
;;
;; `register-machine-event!` below is the SINGLE HOME that stamps it (it is the
;; body of `reg-machine*` AND the new event-`:schema` arity). The bare
;; `(reg-event id meta (make-machine-handler spec))` direct path does NOT stamp
;; it — which is exactly how a `:data-schema` could go silently inert. So
;; `make-machine-handler` FAILS LOUD when it is handed a `:data-schema`-bearing
;; spec OUTSIDE the home (`*in-registration-home?*` unbound to true): a
;; schema-bearing machine MUST flow through `reg-machine` / `reg-machine*`. The
;; home, plus the spawned-actor materialisation seams (`handler-meta-for` /
;; `resolve-actor-handler-meta`), bind the flag around their
;; `make-machine-handler` calls. A schema-LESS machine has nothing to validate,
;; so the bare direct path stays legal for it (the Story testbed / schema-free
;; examples rely on it).
;;
;; EP-0025 (rf2-398kql): the home formerly ran a SECOND `:data-schema` side-
;; effect — `register-data-schema-marks!`, the schema→marks redaction bridge.
;; That bridge is REMOVED (schema-field classification killed in favour of
;; frame-declared paths), so the home now runs only the validation-stamp.
(def ^:dynamic *in-registration-home?*
  "True while `make-machine-handler` is invoked from a registration site that
  ALSO stamps the `:rf/machine?` / `:rf/machine` meta (the single home, or the
  spawned-actor resolver seams). When false/unbound, a `:data-schema`-bearing
  spec handed to `make-machine-handler` is an unstamped-schema misconfiguration
  — fail loud (the schema would validate nothing)."
  false)

;; The reserved creation marker `:rf.machine/start` (renamed from
;; `:rf.machine/bootstrap` — rf2-gl588, pre-alpha no shim) is defined once in
;; the leaf engine namespace as `transition/start-marker` so the handler here
;; and the cascade in `parallel` share one source of truth without a require
;; cycle. Per F‴ it is a PURE init-kick: `maybe-boot` runs the initial-entry
;; cascade, then the handler STOPS — the marker is NEVER fed into `run-step`
;; as a trigger (no `before == after` self-transition, no `:*`-wildcard throw).

;; Per rf2-fgqs4 the initial-snapshot builder lives in `parallel.cljc` as
;; `build-initial-snapshot` — single source of truth for both the
;; singleton-registration path (here) and the spawn path
;; (`lifecycle-fx.spawn/install-spawn!`). The two used to drift: the spawn
;; path silently omitted `:rf/spawn-counter` and `:meta`. See
;; `parallel/build-initial-snapshot` for the canonical 6-step shape.

;; ---- handler factory ------------------------------------------------------

(defn- route-inner-event
  "Sub-event routing per Spec 005 §Registration. The outer event is
  `[:machine-id <inner-event> & extra-args]`. Extra args are conj'd onto
  the inner event — the convention http-style fx callbacks rely on:

    :on-success [:machine-id [:inner-id]]   →
    (conj on-success response) yields
    [:machine-id [:inner-id] response]      →
    inner-event = [:inner-id response]"
  [event]
  (if (and (vector? event)
           (>= (count event) 2)
           (vector? (second event)))
    (let [inner (second event)
          extra (drop 2 event)]
      (if (seq extra)
        (vec (concat inner extra))
        inner))
    event))

(defn- trace-action-failure!
  "Emit `:rf.error/machine-action-exception` for a `result/fail` Result's
  `::result/info` map. Returns `{}` so the handler short-circuits.

  Per rf2-zsm03 (privacy — AI/MCP egress + logs threat model): the
  `:event` slot is redacted by the marks projection's `project-event-tags`
  and `:before`/`:after`/`:snapshot` by `project-machine-tags`, but the
  `:exception-data` slot below carries the thrown action's `ex-data` — the
  developer's arbitrary exception payload, which could embed the same app
  secrets the machine's `:data` marks gate. That slot is path-elided at the
  trace egress chokepoint by `re-frame.marks/project-machine-error-tags`
  (a NEW projection clause): when the machine declares ANY `:sensitive`
  mark the whole `:exception-data` slot scrubs to `:rf/redacted` before the
  trace crosses the bus / epoch-capture / AI-MCP boundary or a log sink —
  the same egress seam (the marks projection) the rf2-o69h5 class sweep
  routed the core validation-failure class through. The redaction lives at
  the chokepoint (not here) so it covers EVERY consumer of the trace
  uniformly and stays the single place the `:sensitive` decision is made.

  Per Spec 005 §Final states §`:on-error` (rf2-5hlsh; XState v5 invoke
  `onError`): an uncaught child action exception is the SECOND `:on-error`
  trigger (the control-flow case — formerly observability-only). When the
  THROWING actor is a `:spawn`-spawned child whose spawning parent declares
  `:spawn :on-error`, route the failure to the parent's declarative
  `:on-error` transition via `spawn-error/dispatch-spawn-error!` — additive to
  (not a replacement for) the trace above, which still fires for every action
  exception. `ctx` carries the failing actor's `:db` + `:snapshot` (whose
  `:data` was stamped with `:rf/parent-id` / `:rf/invoke-id` at spawn time);
  the exception envelope rides as the parent transition's `:event` payload so
  a guard / action can branch on it. Singletons (no parent) and parents that
  declare no `:on-error` route nowhere — the trace IS the signal, unchanged."
  [ctx event reason info]
  (let [{:keys [machine-id frame-id runtime-db snapshot]} ctx
        ex         (:exception info)
        ex-msg     #?(:clj  (when ex (.getMessage ^Throwable ex))
                      :cljs (when ex (.-message ex)))
        ex-data    (when ex (ex-data ex))
        action-ref (:action-ref info)]
    (trace/emit-error! :rf.error/machine-action-exception
                       ;; rf2-yyvtk5 — the throwing action ran in a LIVE
                       ;; actor's transition; `machine-id` here is the
                       ;; event-handler key (the running INSTANCE address —
                       ;; a singleton's registration id, or a spawned
                       ;; actor's `<type>#<n>` / fixed id), so it rides
                       ;; under `:actor-id`. Reserved `:machine-id` names
                       ;; the registered TYPE only. `:failing-id` /
                       ;; `:handler-id` are already distinctly named.
                       {:actor-id          machine-id
                        :action-id         action-ref
                        :state-path        (:state-path info)
                        :transition        (:transition info)
                        :event             event
                        :failing-id        machine-id
                        :handler-id        machine-id
                        :frame             frame-id
                        :exception         ex
                        :exception-message ex-msg
                        :exception-data    ex-data
                        :reason            reason
                        :recovery          :no-recovery})
    ;; (rf2-5hlsh) — additive control-flow routing. Read the spawning
    ;; parent / invoke-id off the child's stamped `:data`; if the parent
    ;; declares `:spawn :on-error`, dispatch the failure into it. The error
    ;; payload carries the exception envelope so the parent transition's
    ;; guard / action can branch on it.
    (let [child-data (:data snapshot)
          parent-id  (:rf/parent-id child-data)
          invoke-id  (:rf/invoke-id child-data)]
      (when (spawn-error/parent-declares-on-error? runtime-db parent-id invoke-id)
        (spawn-error/dispatch-spawn-error!
          frame-id parent-id invoke-id
          {:rf.error/id       :rf.error/machine-action-exception
           ;; rf2-yyvtk5 — the failing child is a LIVE actor INSTANCE.
           :actor-id          machine-id
           :action-id         action-ref
           :event             event
           :exception-message ex-msg
           :exception-data    ex-data
           :reason            reason})))
    {}))

(defn- handle-step-failure!
  "Route a `result/fail` macrostep to the handler's `{}` short-circuit
  (atomic rollback — no snapshot write reaches runtime-db) per Spec 005
  §Bounded depth / §Final states §`:on-error`.

  rf2-y3jv8q — a bounded-depth abort (`:always` / `:raise` depth limit
  tripped on a runaway cycle, the XState-v5-throws case) is a DISTINCT
  failure from a thrown action: the engine already emitted the precise
  `:rf.error/machine-{always,raise}-depth-exceeded` category at the abort
  site (the single trace for the trip), so re-emitting the generic
  `:rf.error/machine-action-exception` here would be a misleading second
  signal. For a depth-abort we therefore SKIP `trace-action-failure!`
  (which also routes the spawn-`:on-error` control flow — irrelevant to a
  depth trip) and return `{}` directly. A thrown-action `:fail` keeps the
  existing `trace-action-failure!` routing (the action-exception trace +
  the spawn-`:on-error` dispatch). Both paths short-circuit the handler to
  `{}`, so neither writes the post-event snapshot — the pre-event snapshot
  stays committed in runtime-db (the atomic-rollback contract)."
  [ctx event reason r]
  (if (result/depth-abort? r)
    {}
    (trace-action-failure! ctx event reason (result/info r))))

;; ---- 4-step pipeline (rf2-2zzyg) ------------------------------------------
;;
;; The handler-fn returned by `make-machine-handler` decomposes into four
;; named pure-fn steps, each ≤ 30 LoC, that read onto Spec 005 §Drain
;; semantics §Level 3 directly:
;;
;;   1. `prepare-machine-ctx`  — stamp frame / platform / parent-id, look
;;      up the existing snapshot, decide `needs-bootstrap?`, route the
;;      inner event. Returns a `ctx` map carrying everything downstream
;;      steps read.
;;   2. `maybe-boot`           — if bootstrap-pending and not intercepted,
;;      run `apply-initial-entry-cascade`. Returns a Result whose `::snap`
;;      is the post-boot snapshot with `:rf/bootstrap-pending?` cleared.
;;   3. `run-step`             — call `machine-transition` on the
;;      post-boot snapshot + inner event. Returns the Result from the
;;      pure engine.
;;   4. `commit-or-finalize`   — emit transition / snapshot-updated traces,
;;      build new-db, route to `finalize-machine` if `finished?`.
;;
;; The intercept-spawn-all-event short-circuit is the visible top-level
;; branch in `make-machine-handler` itself — it must short-circuit before
;; boot / step / commit run.

;; ---- snapshot/definition compatibility (rf2-fasdp) ------------------------
;;
;; Per Spec 005 §Snapshot shape stability invariants 3 & 4:
;;
;;   3. Snapshots whose `:state` is no longer present in the machine's
;;      definition transition to the new `:initial` and emit
;;      `:rf.error/machine-state-not-in-definition`.
;;   4. `:rf/snapshot-version` mismatch between snapshot and definition
;;      emits `:rf.error/machine-snapshot-version-mismatch`.
;;
;; Both checks fire at machine-handler entry against an existing
;; snapshot — singleton-bootstrap path has no snapshot to validate. On
;; trip, the snapshot is rebuilt from `@base-initial` (with the
;; bootstrap-pending marker stamped so the new initial state's `:entry`
;; actions fire). The transient runtime-internal slots
;; (`:rf/spawn-counter`, `:rf/after-epoch`, region-scoped epochs) reset
;; with the snapshot — restoring an incompatible snapshot means
;; restarting the machine, not patching it.
;;
;; Per rf2-fasdp this closes the audit-confirmed drift: the prior code
;; only distinguished "no snapshot" from "snapshot present", without
;; verifying the snapshot's state still exists in the (possibly
;; hot-reloaded) definition or that the version stamps agree. Both
;; failures previously kept driving the incompatible snapshot through
;; the transition engine, where they surfaced as cryptic downstream
;; errors instead of the spec's named warnings + fallback behaviour.

(defn- state-resolves?
  "True iff the snapshot's `:state` (flat keyword / compound vector
  path / parallel-region map) is a VALID, OCCUPIABLE configuration of the
  machine's `:states` / `:regions` definition. A false result routes
  `reconcile-snapshot` through the `:rf.error/machine-state-not-in-definition`
  reset (the runtime can't safely drive a mismatched snapshot — better to
  reset and re-enter).

  For PARALLEL machines this requires EXACTLY the declared region key set
  (no missing region, no extra/stale region) AND every region's path to
  resolve to a real non-history leaf — `parallel/parallel-state-valid?`
  (bz0ox.2 / x4s9t.2). The prior check validated only the regions PRESENT in
  the snapshot, so a partial map like `{:left :done}` for a 2-region machine
  validated, then silently ran a partial configuration that could vacuously
  fire root `:on-done` / auto-destroy.

  For FLAT / COMPOUND machines the path must resolve to a real, OCCUPIABLE
  leaf — `transition/state-occupiable?` rejects both a missing state AND a
  `:type :history` pseudo-state, which is targetable but never occupied
  (bz0ox.2). A malformed `:state` shape is caught inside `state-occupiable?`
  and surfaces as not-resolving, so the same reset path covers shape-error,
  missing-state, AND occupied-history alike."
  [machine state]
  (if (parallel/parallel? machine)
    (parallel/parallel-state-valid? machine state)
    (transition/state-occupiable? machine state)))

(defn- snapshot-version
  "Read the `:rf/snapshot-version` int from `(get-in m [:meta :rf/snapshot-version])`.
  Returns nil if absent. Per Spec 005 §Reserved snapshot-internal keys."
  [m]
  (get-in m [:meta :rf/snapshot-version]))

(defn- version-compatible?
  "True iff the snapshot's `:rf/snapshot-version` matches the
  definition's. Both absent matches; both present-and-equal matches;
  any other shape is a mismatch."
  [machine snapshot]
  (= (snapshot-version machine) (snapshot-version snapshot)))

(defn- rebuild-incompatible-snapshot
  "Emit the named `:rf.error/*` trace and return the freshly-derived
  initial snapshot (with `:rf/bootstrap-pending? true` so the new
  initial state's `:entry` cascade fires on this same handler call).
  `kind` is `:state-not-in-definition` or `:version-mismatch`."
  [kind machine-id frame-id machine existing-snap base-initial]
  (case kind
    :state-not-in-definition
    (trace/emit-error! :rf.error/machine-state-not-in-definition
                       {:machine-id machine-id
                        :state      (:state existing-snap)
                        :frame      frame-id
                        :recovery   :reset-to-initial})

    :version-mismatch
    (trace/emit-error! :rf.error/machine-snapshot-version-mismatch
                       {:machine-id       machine-id
                        :version-recorded (snapshot-version existing-snap)
                        :version-current  (snapshot-version machine)
                        :frame            frame-id
                        :recovery         :reset-to-initial}))
  ;; Per Spec 005 §Snapshot shape stability invariants the snapshot is
  ;; replaced — there is no merge-with-old-data path. The reset stamps
  ;; bootstrap-pending so `:entry` fires on this same handler call.
  (assoc @base-initial :rf/bootstrap-pending? true))

(defn- reconcile-snapshot
  "Apply the Spec 005 §Snapshot shape stability invariants 3 & 4 at
  handler-entry. Returns the (possibly replaced) snapshot — the caller
  uses it as the basis for `needs-bootstrap?` / the transition. Per
  rf2-fasdp.

  Version check runs FIRST (an explicit version bump is a stronger
  signal than an opportunistic state-vanished detection — if the
  author bumped the version they want a reset regardless of whether
  the old state still exists in the new definition). Both checks
  silently pass when the snapshot is compatible; only a trip emits
  the named `:rf.error/*` event."
  [machine machine-id frame-id existing-snap base-initial]
  (cond
    (not (version-compatible? machine existing-snap))
    (rebuild-incompatible-snapshot :version-mismatch machine-id frame-id
                                   machine existing-snap base-initial)

    (not (state-resolves? machine (:state existing-snap)))
    (rebuild-incompatible-snapshot :state-not-in-definition machine-id frame-id
                                   machine existing-snap base-initial)

    :else existing-snap))

(defn- prepare-machine-ctx
  "Step 1 of 4 (rf2-2zzyg). Stamp the live frame / platform / parent-id
  onto the machine def, look up the existing snapshot at
  `[:rf.runtime/machines :snapshots <machine-id>]` in the **runtime-db**
  partition (machine snapshots are durable runtime-db state — EP-0001
  rf2-vzld77), decide `needs-bootstrap?`, route the inner event. Returns a
  `ctx` map the remaining three steps read. `runtime-db` is the
  `:rf.db/runtime` coeffect the router injected by reference.

  Per rf2-0z73: detect 'first event for this machine' so the initial
  state's `:entry` actions fire as part of bringing the machine to life.
  Two flavours:
    - Singleton path: `(get-in runtime-db path)` is `nil` — the snapshot is
      being lazily synthesised right now.
    - Spawn path: `spawn-fx` pre-seeded the snapshot at
      `[:rf.runtime/machines :snapshots <spawned-id>]` and stamped
      `:rf/bootstrap-pending? true` so the actor's first dispatch sees
      the marker and runs the cascade before processing the event.

  Per rf2-fasdp: when an existing snapshot is found, run the Spec 005
  §Snapshot shape stability invariants 3 & 4 reconciler before
  threading it onward — a hot-reload that dropped a state, or a
  `:rf/snapshot-version` bump, replaces the snapshot with a fresh
  initial-state derivative (with `:rf/bootstrap-pending? true` so
  `:entry` fires this same handler call) and emits the named
  `:rf.error/machine-state-not-in-definition` or
  `:rf.error/machine-snapshot-version-mismatch` event.

  Per EP-0010 / EP-0017 (rf2-g0m4p5 / rf2-alc1lf): the event handler's causal
  recordable-coeffect token (`cofx` — the router's `:rf.cofx` coeffect, Spec
  002 §Event Context And Coeffects) is stamped onto the machine def under
  `:rf/cofx` alongside `:rf/frame` / `:rf/platform` / `:rf/parent-id`,
  so the transition engine's `callback-ctx` can surface it to guards /
  actions / entry / exit under `:rf.cofx`. A durable machine
  decision (time / random / UUID host fact) reads the causal token rather
  than an ambient clock, so the decision and any snapshot write replay
  deterministically. `nil` (the router always supplies the coeffect, but a
  REPL `reg-machine*` dispatch through a non-router path may not) leaves the
  slot absent — `callback-ctx` then surfaces no key and the pure-fn callers
  (conformance corpus) are unaffected."
  [db runtime-db frame cofx mint-policy event machine base-initial]
  (let [machine-id    (first event)
        ;; EP-0002 — `frame` is the cascade-threaded envelope frame the
        ;; calling machine handler already asserted via
        ;; `require-frame-stamp!`; no `:rf/default` repair here.
        frame-id      frame
        platform      (or (:platform (frame/frame-meta frame-id)) :client)
        machine       (cond-> (assoc machine
                                     :rf/frame     frame-id
                                     :rf/platform  platform
                                     :rf/parent-id machine-id)
                        ;; EP-0010 / EP-0017 (rf2-g0m4p5 / rf2-alc1lf): thread
                        ;; the causal recordable-coeffect token onto the machine
                        ;; def so `callback-ctx` exposes it as `:rf.cofx`. Stamp
                        ;; only when present so the absent-token path stays clean
                        ;; for pure-fn callers.
                        (some? cofx) (assoc :rf/cofx cofx)
                        ;; rf2-n0myjq — thread the resolved effective cofx MINT
                        ;; POLICY (per-call opt ▸ frame config ▸ `:live`, the
                        ;; router stamped it as the `:rf.cofx/mint-policy`
                        ;; framework coeffect) onto the machine def under the
                        ;; reserved `:rf/cofx-mint-policy` key. The dispatch-time
                        ;; / bootstrap ensure steps read it so a `:strict`
                        ;; replay/`:test` machine refuses to mint a declared-
                        ;; absent generator-backed guard/action fact (surfacing
                        ;; missing-required) instead of always defaulting to
                        ;; `:live`; the in-engine raised-event ensure (rf2-xsdn5h)
                        ;; reads the SAME stamped policy. Stamped only alongside a
                        ;; threaded token so pure-fn callers stay untouched.
                        (some? cofx) (assoc :rf/cofx-mint-policy
                                            (or mint-policy cofx/default-mint-policy)))
        path          (paths/snapshot-path machine-id)
        existing-snap (get-in runtime-db path)
        snapshot      (cond
                        (nil? existing-snap)
                        (assoc @base-initial :rf/bootstrap-pending? true)

                        :else
                        (reconcile-snapshot machine machine-id frame-id
                                            existing-snap base-initial))]
    {:db               db
     :runtime-db       runtime-db
     :machine-id       machine-id
     :frame-id         frame-id
     :machine          machine
     :path             path
     :snapshot         snapshot
     ;; `:existing-snap?` records whether the handler found a snapshot
     ;; ALREADY in runtime-db at entry. It distinguishes the two FRESH
     ;; flavours for the `:rf.machine/started` `:cause` (rf2-gl588):
     ;;   - nil snapshot  → singleton (`:explicit` / `:lazy`, by trigger);
     ;;   - present + `:rf/bootstrap-pending?` → spawn-pre-seeded (`:spawned`).
     :existing-snap?   (some? existing-snap)
     :needs-bootstrap? (or (nil? existing-snap)
                           (true? (:rf/bootstrap-pending? snapshot)))
     :inner-event      (route-inner-event event)}))

(defn- start-cause
  "Compute the `:rf.machine.start/cause` enum for a `maybe-boot` that ran
  the initial-entry cascade (rf2-gl588). Three-way, per Mike 2026-06-03:

    - `:spawned`  — the handler found a snapshot ALREADY in runtime-db (the
                    spawn fx pre-seeded it + stamped `:rf/bootstrap-pending?`);
                    init ran on the actor's first dispatch.
    - `:explicit` — no pre-existing snapshot (singleton) AND the dispatched
                    trigger WAS the reserved `:rf.machine/start` marker — a
                    deliberate eager kick (`createActor(m).start()`).
    - `:lazy`     — no pre-existing snapshot (singleton) AND the trigger was
                    a REAL first event — init folded into that event's epoch.
                    The `:lazy` cause flags that something dispatched to the
                    machine before it was explicitly started (an ordering
                    smell the Xray `[START]` badge surfaces — rf2-it4vt)."
  [ctx]
  (cond
    (:existing-snap? ctx)                                  :spawned
    (= transition/start-marker (first (:inner-event ctx))) :explicit
    :else                                                  :lazy))

(defn- ensure-bootstrap-cofx
  "Per EP-0017 slice-B.9 (rf2-knxbok) — the BIRTH-time consumer-attachment
  ensure step, run BEFORE `maybe-boot`. The bootstrap cascade
  (`apply-initial-entry-cascade`, inside `maybe-boot`) fires the initial-state
  descent's `:entry` actions and the birth `:always` settle, so a named `:entry`
  action (or birth-`:always`-reached action) declaring `:rf.cofx/requires` must
  have its facts ensured BEFORE the cascade runs — otherwise it reads an
  unensured token (the bootstrap hole). Computes
  `cofx-attach/bootstrap-ensure-set-for` and ensures it onto the in-flight
  `:rf.cofx` record (re-stamped onto the machine def under `:rf/cofx` so
  `callback-ctx` surfaces it to the bootstrap `:entry` callbacks).

  A no-op (returns `ctx` unchanged) when the machine is not bootstrapping, when
  no causal token was threaded (`:rf/cofx` absent — pure-fn / no-router
  callers), or when the birth ensure-set is empty."
  [ctx]
  (let [machine (:machine ctx)]
    (if-not (and (:needs-bootstrap? ctx) (contains? machine :rf/cofx))
      ctx
      (let [recorded  (:rf/cofx machine)
            ;; rf2-n0myjq — ensure under the resolved effective mint policy the
            ;; router stamped (`:rf/cofx-mint-policy`), so a `:strict` replay /
            ;; `:test` birth refuses to mint a declared-absent generator-backed
            ;; `:entry` / birth-`:always` fact (surfacing missing-required).
            augmented (cofx-attach/bootstrap-ensure-cofx
                        machine recorded (:frame-id ctx) (:machine-id ctx)
                        (:rf/cofx-mint-policy machine))]
        (if (identical? augmented recorded)
          ctx
          (assoc ctx :machine (assoc machine :rf/cofx augmented)))))))

(defn- maybe-boot
  "Step 2 of 4 (rf2-2zzyg). If `ctx` is bootstrap-pending, run
  `apply-initial-entry-cascade` once before processing the user event.
  Bootstrap fx flow OUT of the handler ahead of any fx the user event
  produces — entry happens-before user-event handling. Returns a Result
  whose `::snap` has `:rf/bootstrap-pending?` cleared on success.

  Per rf2-0z73 the bootstrap cascade fires the initial state's `:entry`
  actions; per the Result ADT (rf2-aa2rw) a `:fail` short-circuits the
  rest of the pipeline.

  Per rf2-gl588 this is the machine's SINGLE birth site — it runs in both
  axis-A paths (eager `:rf.machine/start` kick AND lazy first-real-event).
  So whenever the cascade succeeds it emits ONE `:rf.machine/started`
  trace carrying `{:machine-id :frame-id :state :data :cause}` — the
  signal Xray renders as the `[START]` badge (rf2-it4vt) in BOTH paths
  automatically. The trace is emitted only on success (a thrown `:entry`
  action short-circuits to `:fail` → `trace-action-failure!` instead).
  Restoration paths (SSR / `restore-epoch!` / `replace-app-db`) install a
  present, non-pending snapshot, so `:needs-bootstrap?` is false and NO
  `:rf.machine/started` fires — the snapshot IS the state."
  [ctx]
  (if (:needs-bootstrap? ctx)
    (let [r (parallel/apply-initial-entry-cascade (:machine ctx) (:snapshot ctx))]
      (if (result/fail? r)
        r
        (result/with-ok [snap fx] r
          (let [booted (dissoc snap :rf/bootstrap-pending?)]
            (trace/emit! :rf.machine :rf.machine/started
                         {:machine-id (:machine-id ctx)
                          :frame      (:frame-id ctx)
                          :state      (:state booted)
                          :data       (:data booted)
                          :cause      (start-cause ctx)})
            ;; Per rf2-n9f4z: preserve the bootstrap-entry `::cascade` so
            ;; `commit-or-finalize` can prepend the initial-descent `:entry`
            ;; steps when bootstrap and the user event land in the same call.
            (result/with-cascade
              (result/ok booted fx)
              (result/cascade r))))))
    (result/ok (:snapshot ctx) [])))

(defn- run-step
  "Step 3 of 4 (rf2-2zzyg). Run the pure macrostep against the post-boot
  snapshot + the routed inner event. Returns the Result from
  `parallel/machine-transition` — caller inspects `result/fail?` /
  `result/ok?` and projects accordingly."
  [ctx post-boot-snap]
  (parallel/machine-transition (:machine ctx) post-boot-snap (:inner-event ctx)))

(defn- ensure-ctx-cofx
  "Per EP-0017 slice-B.9 (rf2-mjmxgb) — the dispatch-time consumer-attachment
  ensure step, run between `maybe-boot` and `run-step`. Computes the derived
  per-(state × event-type) ensure-set for the POST-BOOT snapshot's active
  state + the routed inner event (`cofx-attach/ensure-set-for`, incl. the
  `:always` closure) and ensures it onto the in-flight `:rf.cofx` record
  carried under the machine def's `:rf/cofx`, BEFORE transition selection.

  Returns `ctx` with `:machine`'s `:rf/cofx` updated to the augmented record
  (so `callback-ctx` surfaces every ensured fact to guards / actions during
  selection). A no-op when the machine declares no requires, when no token
  was threaded (`:rf/cofx` absent — pure-fn / no-router callers), or when the
  ensure-set is empty — `ctx` is returned unchanged in all three.

  Ensuring runs under the default `:live` mint policy (the router default);
  the `:strict` binding points for replay / the `:test` preset are slice-B.8's
  surface. A provided fact absent from the record is `:rf.error/missing-
  required-cofx`; a schema-invalid value is `:rf.error/cofx-value-invalid`
  (both production hard errors, propagated by `deliver-declared-cofx`)."
  [ctx post-boot-snap]
  (let [machine (:machine ctx)]
    ;; Only run when a causal token was threaded (the normal dispatch path
    ;; stamps `:rf/cofx`); pure-fn callers carry no token and consume no
    ;; recordable facts, so there is nothing to ensure.
    (if-not (contains? machine :rf/cofx)
      ctx
      (let [recorded  (:rf/cofx machine)
            ;; rf2-n0myjq — ensure under the resolved effective mint policy the
            ;; router stamped (`:rf/cofx-mint-policy`); `:strict` replay / `:test`
            ;; refuses to mint a declared-absent generator-backed guard/action
            ;; fact (surfacing missing-required) rather than defaulting `:live`.
            augmented (cofx-attach/ensure-cofx
                        machine post-boot-snap (:inner-event ctx)
                        recorded (:frame-id ctx) (:machine-id ctx)
                        (:rf/cofx-mint-policy machine))]
        (if (identical? augmented recorded)
          ctx
          (assoc ctx :machine (assoc machine :rf/cofx augmented)))))))

(defn- commit-or-finalize
  "Step 4 of 4 (rf2-2zzyg). Emit `:rf.machine/transition` (and optional
  `:rf.machine/snapshot-updated`) traces, build the new app-db, and
  route to `finalize-machine` if the post-transition snapshot is on a
  final leaf / all regions final.

  Per Spec 005 §Final states (rf2-gn80): the finality flag is recomputed
  at the lifecycle-handler boundary against the post-transition
  snapshot. For single / compound machines, look up the leaf node and
  check `:final?`. For parallel-region machines, the parent is `:final?`
  only when every region's leaf is `:final?`. The pure-transition
  surface stays free of runtime-only metadata."
  [ctx step-result boot-result]
  (result/with-ok [next-snapshot fx] step-result
    (let [{:keys [machine machine-id frame-id runtime-db path snapshot inner-event]} ctx
          boot-fx   (result/fx boot-result)
          merged-fx (vec (concat boot-fx fx))
          ;; Per rf2-n9f4z: when this handler call both bootstrapped the
          ;; machine AND processed a user event, the `:before`/`:after`
          ;; slots span both — so the cascade prepends the bootstrap entry
          ;; cascade (the initial-descent `:entry` steps) ahead of the
          ;; event-driven cascade, matching the macrostep the trace reports.
          ;; A no-bootstrap call's `boot-result` carries an empty cascade.
          cascade   (into (vec (result/cascade boot-result))
                          (result/cascade step-result))
          ;; Per rf2-coozg: a genuine no-op macrostep emits NO
          ;; `:rf.machine/transition`. An unhandled / guard-blocked event
          ;; already signals the benign `:rf.machine.event/unhandled-no-op`
          ;; (the engine's `:else` branch + the parallel aggregate); a
          ;; redundant `[:rf.machine/start]` on an already-booted machine
          ;; is the reserved-`:rf/*` carve-out (rf2-t4582) that emits
          ;; nothing at all. In BOTH cases the macrostep changed
          ;; nothing — `:before` == `:after`, the combined (boot + step)
          ;; cascade is empty, and zero `:always` microsteps ran — so a
          ;; no-change transition trace (before = after, `:microsteps 0`,
          ;; `:cascade []`) adds no information and CONTRADICTS the no-op
          ;; signal (it borrows external-self-transition `{X}→{X}`
          ;; vocabulary that implies the `:exit`/`:entry` firing that did
          ;; NOT happen — rf2-e6q97). Suppressing it makes the no-op
          ;; SINGLE-signalled + consistent across unhandled / blocked /
          ;; redundant-bootstrap, and lets the Xray projection's
          ;; `drop-spurious-no-op-transition` band-aid wither (nothing to
          ;; drop once the row is never emitted).
          ;;
          ;; The legitimate FIRST bootstrap (`:initial-entry`) is NOT a
          ;; no-op: it installs the initial state via a non-empty
          ;; initial-descent `:cascade`, so the empty-cascade guard keeps
          ;; its transition trace. An internal self-transition (action
          ;; runs, no `:target`, `:before` == `:after`) likewise carries an
          ;; `:action` cascade step, so it is never classified a no-op.
          no-op?    (and (= snapshot next-snapshot)
                         (empty? cascade)
                         (zero? (result/microsteps step-result)))
          ;; Per Spec 005 §Final states §Embedded vs top-level (rf2-bnjb3 /
          ;; rf2-zlmz7) — the D7 reconciliation. Whole-machine finality (route
          ;; to `finalize-machine`: singleton auto-destroy / spawning parent's
          ;; `:spawn :on-done`) is gated on:
          ;;   - flat / compound: a `:final?` leaf that is a DIRECT CHILD of
          ;;     the root (`top-level-final?`). A `:final?` leaf EMBEDDED in a
          ;;     compound is NOT whole-machine finality — the engine already
          ;;     raised `[:rf.machine/done <compound>]`, the enclosing
          ;;     `:on-done` fired in the same macrostep, and the machine keeps
          ;;     running.
          ;;   - parallel: all regions final AND the parallel root declared no
          ;;     `:on-done`. When it DID declare `:on-done`, that is the
          ;;     transitionable completion signal — the machine RESTS in the
          ;;     all-final config (the natural stable "complete" state) and is
          ;;     never auto-destroyed, regardless of whether THIS macrostep was
          ;;     the one that fired it. Gating on `(nil? :on-done)` (not on the
          ;;     per-macrostep `parallel-done-handled?` edge flag) keeps a
          ;;     resting all-final machine alive on every subsequent no-op event
          ;;     too — `:on-done` fires once on entry (h3wca.1 edge guard) yet
          ;;     the machine must not tear down on the later resting macrosteps
          ;;     where the flag is (correctly) absent.
          finished? (or (and (not (parallel/parallel? machine))
                             (transition/top-level-final? machine (:state next-snapshot)))
                        (and (finalize/all-regions-final? machine (:state next-snapshot))
                             (nil? (:on-done machine))))
          ;; Machine snapshots are durable runtime-db state (rf2-vzld77):
          ;; write the new snapshot into the runtime-db partition value;
          ;; the handler returns it under `:rf.db/runtime`, NOT `:db`.
          new-runtime-db (assoc-in runtime-db path next-snapshot)]
      ;; Per rf2-hwuki: `:frame` tag is REQUIRED for epoch-capture
      ;; admission (`re-frame.epoch.capture/capture-event!` silently
      ;; drops trace events whose tags lack `:frame`). Without this
      ;; tag the headline machine-transition trace never reaches the
      ;; cascade's `:trace-events` slot, leaving the Xray Machine
      ;; Inspector chart blank for cascades that DID drive a transition.
      ;; Per Spec 005 §Trace events: the outer macrostep trace carries
      ;; `:microsteps <count>` — the total number of `:always` microsteps
      ;; the macrostep ran (0 when the event drove no `:always` cascade).
      ;; The per-microstep `:rf.machine.microstep/transition` stream is
      ;; emitted inside the engine; this is the macrostep-level rollup.
      ;;
      ;; Per rf2-n9f4z the trace ALSO carries `:cascade` — the structured,
      ;; ordered step vector explaining HOW the transition reached its
      ;; after-state: exit (deepest-first) → transition `:action` @ LCA →
      ;; entry (shallowest-first + initial-descent), each step with its
      ;; `:kind` / `:state` / `:region` / `:action` / `:data-delta`, plus a
      ;; `:microstep` step (carrying nested `:steps`) per `:always`
      ;; iteration, with per-region structure for parallel machines. This
      ;; replaces the need for app-level `:data :trail` workarounds and is
      ;; the contract Xray's epoch panel renders (rf2-52u5n). It rides under
      ;; the same handler-scope `:sensitive?` stamp as `:before` / `:after`
      ;; (per Spec 005 §Privacy), so a sensitive machine's cascade
      ;; `:data-delta`s are scrubbed at egress alongside the snapshot slots.
      (when-not no-op?
        (trace/emit! :rf.machine :rf.machine/transition
                     {:frame      frame-id
                      ;; rf2-ws5thu — the LIVE actor instance address (the
                      ;; event-handler key: a singleton's registration id, or
                      ;; a spawned actor's `<type>#<n>` / fixed instance id).
                      ;; Reserved `:machine-id` names the registered TYPE only.
                      :actor-id   machine-id
                      :event      inner-event
                      :before     snapshot
                      :after      next-snapshot
                      :microsteps (result/microsteps step-result)
                      :cascade    cascade}))
      (when (not= snapshot next-snapshot)
        (trace/emit! :rf.machine :rf.machine/snapshot-updated
                     {:actor-id   machine-id
                      :path       path
                      :before     snapshot
                      :after      next-snapshot
                      :frame      frame-id}))
      (if finished?
        (finalize/finalize-machine machine machine-id frame-id
                                   new-runtime-db next-snapshot inner-event merged-fx)
        {:rf.db/runtime new-runtime-db
         :fx            merged-fx}))))

(defn make-machine-handler
  "Returns a function suitable for registration with `reg-event`.

  Per Spec 005 §Registration — the machine IS the event handler. The
  machine spec MUST NOT carry `:id`; the machine's id is the surrounding
  registration's event-id, derived at handler-call time from the
  dispatched event vector's first element.

  Per rf2-f9tu the body is decomposed into:
    - `validation/validate-machine!` — every registration-time check.
    - `parallel/build-initial-snapshot` — initial-state cascade, `:data` /
      `:meta` seeding, `:rf/spawn-counter` seeding, tag union stamping
      (rf2-fgqs4 unified this with the spawn path).
    - the returned handler fn — frame stamping, intercept-spawn-all-
      event branch (in `lifecycle-fx.join`), bootstrap-pending detection
      + initial-entry cascade, machine-transition dispatch, action-failure
      projection, finalize delegation (in `lifecycle-fx.finalize`).

  Per rf2-2zzyg the returned handler fn is further decomposed into a
  four-step pipeline — `prepare-machine-ctx` → `maybe-boot` →
  `run-step` → `commit-or-finalize` — with the intercept-spawn-all-
  event short-circuit branching off after step 1."
  [machine]
  ;; Per EP-0017 slice-B.9 (rf2-mjmxgb) — consumer attachment. Run FIRST so
  ;; the inline-fn restriction (`:rf.error/machine-cofx-requires-inline`) and
  ;; the named-entry `:rf.cofx/requires` parse (`:rf.error/cofx-request-invalid`
  ;; / `-name-collision`) surface as their OWN precise categories before
  ;; `validate-machine!`'s guard/action ref resolution would otherwise paint a
  ;; bare-entry-map-with-requires-no-`:fn` as the generic
  ;; `:rf.error/machine-unresolved-guard`. The indexed spec carries
  ;; `:rf/cofx-ensure-index` for the dispatch-time `ensure-ctx-cofx` to read.
  (let [machine (cofx-attach/index-ensure-sets machine)]
  (validation/validate-machine! machine)
  ;; Per rf2-genufr — fail-loud guard. A `:data-schema`-bearing spec MUST be
  ;; registered through the single home (`reg-machine` / `reg-machine*` / the
  ;; event-`:schema` arity), which is the ONLY place the `:rf/machine?` /
  ;; `:rf/machine` registration-metadata stamp runs — the `:where :machine-data`
  ;; post-commit walker resolves the `:data-schema` THROUGH `(machine-meta id)`,
  ;; so without the stamp the schema validates NOTHING. The bare `(reg-event id
  ;; meta (make-machine-handler spec))` direct path does not stamp it — so a
  ;; `:data-schema` reached here outside the home would be silently inert.
  ;; Surface it at the moment of construction rather than letting it no-op. A
  ;; schema-LESS spec is unaffected — the bare direct path stays legal for it.
  ;; (EP-0025, rf2-398kql: the schema→marks redaction half of this guard's
  ;; rationale is gone — schema props no longer classify `:data` for egress —
  ;; but the validation-stamp half still requires the home, so the guard stays.)
  (when (and (:data-schema machine)
             (not *in-registration-home?*))
    (error/throw-error!
      :rf.error/machine-schema-requires-reg-machine
      'rf-machines/make-machine-handler
      (str "make-machine-handler was handed a machine spec carrying a "
           ":data-schema via the bare (reg-event id meta "
           "(make-machine-handler spec)) direct path. That path does NOT "
           "stamp the :rf/machine? / :rf/machine registration metadata, so "
           "the :data-schema validates NOTHING. Register the machine through "
           "reg-machine / reg-machine* — and when the machine ALSO validates "
           "its outer event vector, use the event-:schema arity (the opts "
           "metadata map is the canonical MIDDLE slot, rf2-wvh95f F2): "
           "(reg-machine id {:schema EventSchema} machine) or "
           "(reg-machine* id {:schema EventSchema} machine).")
      {:recovery :use-reg-machine
       :extra    {:data-schema (:data-schema machine)}}))
  ;; Per rf2-f9tu — `build-initial-snapshot` runs lazily INSIDE the
  ;; returned handler, not at registration time. The initial-state
  ;; computation reaches through `:initial` / `:states` / `:regions`;
  ;; running it at registration time would force every registered spec
  ;; (including the stub child machines the conformance corpus declares
  ;; without `:initial` for `:spawn` targets, and any spec whose
  ;; `:initial` derives from a fn-form computed at dispatch time) to
  ;; satisfy the snapshot shape at reg-machine call time. The original
  ;; (pre-split) implementation deferred this work; preserve that.
  ;;
  ;; `machine` already carries the EP-0017 slice-B.9 consumer-attachment index
  ;; (`:rf/cofx-ensure-index`, stamped above by `cofx-attach/index-ensure-sets`
  ;; ahead of `validate-machine!`), so the handler closure captures it and
  ;; `prepare-machine-ctx` threads it onto the machine def — the dispatch-time
  ;; `ensure-ctx-cofx` reads it to derive the per-(state × event-type)
  ;; ensure-set (incl. the `:always` closure).
  (let [base-initial (delay (parallel/build-initial-snapshot
                              machine {:bootstrap-pending? false}))]
    (fn [{:keys [db] frame :rf.frame/id rt :rf.db/runtime
          cofx :rf.cofx mint-policy :rf.cofx/mint-policy :as _cofx} event]
      ;; EP-0002 carried invariant: a machine handler is invoked inside an
      ;; event cascade, so the cofx ALWAYS carries the frame stamp under
      ;; `:rf.frame/id` (the HELD stamp). A nil stamp is an invariant
      ;; failure — surface `:rf.error/no-frame-context`, never repair to a
      ;; synthesised `:rf/default`.
      (frame/require-frame-stamp!
        frame :rf.machine/event-received
        {:where 'rf-machines/make-machine-handler :event-id (first event)})
      ;; Per Spec 009 §:op-type vocabulary: `:rf.machine/event-received`
      ;; fires at the top of the handler so consumers see the inbound
      ;; event before any state derivation.
      (trace/emit! :rf.machine :rf.machine/event-received
                   {:machine-id (first event)
                    :event      event
                    :frame      frame})
      ;; EP-0001 (rf2-vzld77): machine snapshots are durable runtime-db
      ;; state. The handler reads the snapshot from the `:rf.db/runtime`
      ;; coeffect (a fresh frame's runtime-db is `nil` until first write —
      ;; default it to `{}` so the snapshot lookup / install paths see a map)
      ;; and returns its snapshot write under `:rf.db/runtime`. `db` (app-db)
      ;; is still threaded for the spawn-`:on-error` parent lookup +
      ;; action-failure diagnostics.
      (let [runtime-db  (or rt {})
            ctx         (prepare-machine-ctx db runtime-db frame cofx mint-policy event machine base-initial)
            intercepted (join/intercept-spawn-all-event
                          (:machine ctx) runtime-db (:path ctx) (:snapshot ctx)
                          (:machine-id ctx) (:inner-event ctx))]
        (if intercepted
          intercepted
          ;; Per EP-0017 slice-B.9 (rf2-knxbok) — ensure the BIRTH initial-entry
          ;; ensure-set onto the in-flight `:rf.cofx` record BEFORE `maybe-boot`
          ;; runs the bootstrap cascade (which fires the initial-state `:entry`
          ;; actions). The augmented `ctx` flows into both `maybe-boot` and the
          ;; downstream `ensure-ctx-cofx` / `run-step`, so a generated birth fact
          ;; is written back into the record the epoch captures.
          (let [ctx         (ensure-bootstrap-cofx ctx)
                boot-result (maybe-boot ctx)]
            (if (result/fail? boot-result)
              ;; rf2-y3jv8q — route both a thrown initial-entry action AND a
              ;; birth-time bounded-depth abort (a born-state `:always` / raise
              ;; runaway) through the shared failure handler: a depth-abort
              ;; skips the misleading action-exception re-emit (its precise
              ;; category fired in-engine) while both atomically roll back.
              (handle-step-failure! ctx [transition/start-marker]
                                    "Machine initial-entry action threw."
                                    boot-result)
              (result/with-ok [post-boot-snap boot-fx] boot-result
                ;; Per rf2-gl588 — PURE init-kick (the Job-B cut-point,
                ;; sub-decision (b)): when the routed inner event IS the
                ;; reserved `:rf.machine/start` marker, `maybe-boot` has
                ;; already run the INITIAL MACROSTEP — the initial-entry
                ;; cascade AND the birth-time `:always` + raise settle
                ;; (rf2-505ic), via `parallel/apply-initial-entry-cascade`
                ;; — and emitted `:rf.machine/started`. STOP here — never
                ;; feed the synthetic marker into `run-step` as a trigger.
                ;; This removes the misleading `before == after` self-
                ;; transition for quiet states and the `:*`-wildcard throw
                ;; for `:fuse/box`-shaped initial states; the marker now
                ;; means exactly "create + run the initial macrostep," like
                ;; `xstate.init`. (`commit-or-finalize` is bypassed: a pure
                ;; start emits no `:rf.machine/transition`;
                ;; `:rf.machine/started` is the sole birth signal.)
                ;;
                ;; Per rf2-505ic the birth `:always` settle CAN now land
                ;; the machine on a final leaf at start (an initial leaf
                ;; whose `:always` targets a final state) — XState v5 treats
                ;; such an actor as done immediately. So recompute finality
                ;; from the settled snapshot (mirroring `commit-or-finalize`)
                ;; and route to `finalize-machine` when finished, so the
                ;; `:on-done` + auto-destroy cascade fires on eager start
                ;; just as it would on the lazy path. Per rf2-bnjb3 / rf2-zlmz7
                ;; the embedded-vs-top-level reconciliation applies here too:
                ;; only a TOP-LEVEL final (flat/compound) or an all-regions-
                ;; final parallel that declares NO root `:on-done` tears the
                ;; actor down; a parallel root WITH `:on-done` rests in the
                ;; all-final config (the `:on-done` fired once at birth — the
                ;; birth macrostep is the entry edge — and keeps the machine
                ;; alive), and an embedded compound born final already raised
                ;; `[:rf.machine/done …]` through the birth settle's queue.
                (if (= transition/start-marker (first (:inner-event ctx)))
                  (let [m (:machine ctx)]
                    (if (or (and (not (parallel/parallel? m))
                                 (transition/top-level-final? m (:state post-boot-snap)))
                            (and (finalize/all-regions-final? m (:state post-boot-snap))
                                 (nil? (:on-done m))))
                      (finalize/finalize-machine m (:machine-id ctx) (:frame-id ctx)
                                                 (assoc-in runtime-db (:path ctx) post-boot-snap)
                                                 post-boot-snap
                                                 (:inner-event ctx)
                                                 (vec boot-fx))
                      {:rf.db/runtime (assoc-in runtime-db (:path ctx) post-boot-snap)
                       :fx            (vec boot-fx)}))
                  ;; Per EP-0017 slice-B.9 (rf2-mjmxgb) — ensure the derived
                  ;; per-(state × event-type) cofx ensure-set onto the
                  ;; in-flight `:rf.cofx` record BEFORE transition selection
                  ;; (`run-step`) runs. A guard-consumed fact MUST be present
                  ;; when guards execute during selection, so a mid-selection
                  ;; host read can never put nondeterminism in the fold's most
                  ;; sensitive spot (replay selecting a DIFFERENT transition).
                  ;; Generated values are written back into the record the
                  ;; epoch captures (so replay re-presents them); the augmented
                  ;; record is re-stamped onto the machine def under `:rf/cofx`
                  ;; so `callback-ctx` surfaces it to guards / actions / entry /
                  ;; exit. A no-requires machine no-ops (returns the record
                  ;; unchanged) and `ctx` is untouched.
                  (let [ctx (ensure-ctx-cofx ctx post-boot-snap)
                        step-result (run-step ctx post-boot-snap)]
                    (if (result/fail? step-result)
                      ;; rf2-y3jv8q — route both a thrown transition action AND
                      ;; a bounded-depth abort (the runaway `:always` / `:raise`
                      ;; cycle, XState-v5-throws case) through the shared
                      ;; failure handler. A depth-abort surfaces as a FAILED
                      ;; macrostep (atomic rollback, the precise depth-exceeded
                      ;; category already emitted in-engine) rather than the
                      ;; pre-fix silent no-op that swallowed the event.
                      (handle-step-failure! ctx (:inner-event ctx)
                                            "Machine action threw."
                                            step-result)
                      ;; Per rf2-n9f4z: pass the full `boot-result` (fx +
                      ;; bootstrap-entry cascade), not just `boot-fx`, so a
                      ;; same-call bootstrap's entry cascade prepends the
                      ;; event-driven cascade on the `:rf.machine/transition`
                      ;; trace.
                      (commit-or-finalize ctx step-result boot-result)))))))))))))

;; ---- :data-schema redaction bridge: REMOVED (EP-0025, rf2-398kql) ---------
;;
;; A machine's `:data-schema` (the renamed `:schema` key — rf2-rcim4m) is a
;; Malli EDN form that VALIDATES the machine's `:data` slot. That validation is
;; UNTOUCHED. What is gone is the EP-0005 schema→MARKS redaction bridge
;; (`register-data-schema-marks!`, rf2-w46fpt / rf2-qpibk0): it extracted each
;; `:sensitive?` / `:large?` per-`:data`-slot prop from the `:data-schema`,
;; rooted it under `[:data …]`, and recorded it in core's schema-sourced
;; marks side-table so `project-machine-tags` / the SSR projector would redact
;; the marked snapshot slot at egress.
;;
;; EP-0025 makes frame-declared `:sensitive` / `:large {:app-db …}` paths
;; (`reg-frame`, EP-0015) the SOLE app-db data-classification mechanism and
;; KILLS the schema-field classification axis. A machine that needs a durable
;; `:data` slot redacted at egress now declares it on the frame (the snapshot
;; lives in the frame's runtime-db partition); the `:data-schema` is for
;; validation only. The spawn-time / restore-time per-instance bridge and the
;; destroy-time clear (rf2-fm1cpl / rf2-egvm4t) are removed with it.

;; ---- the single registration home (rf2-genufr) ----------------------------

(defn- register-machine-event!
  "THE single home for registering a machine as an event handler. Per
  rf2-genufr it stamps the `:rf/machine?` / `:rf/machine` registration metadata
  so the `:where :machine-data` post-commit walker resolves the `:data-schema`
  through `(machine-meta id)` (without the stamp the schema validates nothing).

  `reg-machine*` (both arities) routes through here, so the bare
  `(reg-event id meta (make-machine-handler spec))` direct path — which does
  not stamp the meta and so would leave a `:data-schema` inert — is no longer
  needed (and `make-machine-handler` fails loud when handed a `:data-schema`-
  bearing spec outside this home; see its guard).

  `opts` is an optional registration-metadata map (rf2-wgmipl). Its `:schema`
  key (when present) is the `:where :event` boundary validator for the
  dispatched OUTER event vector — the machine + event-vector-schema shape.
  Other opts keys ride onto the metadata verbatim. The framework-owned
  `:rf/machine?` / `:rf/machine` keys are stamped here and MUST NOT appear in
  `opts`.

  EP-0025 (rf2-398kql): the home formerly ran a SECOND `:data-schema` side-
  effect — `register-data-schema-marks!`, the schema→marks redaction bridge.
  That bridge is REMOVED; schema-field classification is killed in favour of
  frame-declared `:sensitive` / `:large {:app-db …}` paths as the sole app-db
  mechanism. The home now runs only the validation-stamp."
  [machine-id machine opts]
  ;; rf2-t65lqt — the MIDDLE `opts` slot (rf2-wvh95f F2) must be a map BEFORE
  ;; any `contains?`/`assoc` runs against it. The 2-arity passes `nil` (the
  ;; no-opts path) which normalises to `{}` below; a 3-arity caller that hands
  ;; a non-nil, non-map opts (the slip after the F2 grammar change) would
  ;; otherwise leak a raw host `IllegalArgumentException` ("Key must be
  ;; integer") from the reserved-key `contains?` instead of a public
  ;; diagnostic. Mirrors reg-route's `invalid-route-metadata` non-map guard +
  ;; reg-resource/reg-mutation's metadata-slot map gate.
  (when (and (some? opts) (not (map? opts)))
    (error/throw-error!
      :rf.error/invalid-machine-opts
      'rf-machines/reg-machine*
      (str "reg-machine " machine-id "'s opts (the MIDDLE slot) must be a "
           "registration-metadata map, got " (pr-str (type opts)) ". Per "
           "rf2-wvh95f F2 the grammar is (reg-machine* " machine-id " {…} "
           "machine): the opts metadata map is the SECOND slot, the machine "
           "spec is the THIRD. The 2-arity (reg-machine* " machine-id
           " machine) has no opts.")
      {:recovery :fix-registration
       :extra    {:machine-id machine-id
                  :value      opts}}))
  (let [opts (or opts {})]
   (when (or (contains? opts :rf/machine?) (contains? opts :rf/machine))
    (error/throw-error!
      :rf.error/machine-reserved-meta-in-opts
      'rf-machines/reg-machine*
      (str "reg-machine opts must not carry the "
           "framework-owned :rf/machine? / :rf/machine "
           "keys — the registration home stamps them.")
      {:recovery :drop-reserved-keys
       :extra    {:machine-id machine-id
                  :opts       opts}}))
   ;; Per rf2-s83iu: install a per-machine region-machine cache before
   ;; the machine value is threaded through the handler closure and
   ;; published to the registrar. Re-registration replaces the machine
   ;; map and its attached cache atom, so no separate invalidation step
   ;; is needed.
   (let [machine    (parallel/install-region-cache machine)
        ;; The home is the legitimate `make-machine-handler` site for a
        ;; `:data-schema`-bearing spec — bind the flag so the fail-loud guard
        ;; passes (the guard exists to catch the bare direct path, not us).
        handler-fn (binding [*in-registration-home?* true]
                     (make-machine-handler machine))
        ;; Stamp the framework-owned discriminator keys LAST so they win over
        ;; any (rejected-above, but defensive) opts collision.
        meta       (assoc opts
                          :rf/machine? true
                          :rf/machine  machine)]
    (events/reg-event machine-id meta handler-fn)
    ;; EP-0025 (rf2-398kql): the `:data-schema`→marks redaction bridge is GONE.
    ;; The `:data-schema` still VALIDATES `:data` (via the `:where :machine-data`
    ;; post-commit walker, resolved through the `:rf/machine` meta stamped above),
    ;; but its per-slot `:sensitive?` / `:large?` props no longer classify the
    ;; machine's durable `:data` for trace / SSR egress — frame-declared
    ;; `:sensitive` / `:large {:app-db …}` paths are the sole app-db mechanism.
    ;; Per EP-0017 slice-B.9 (rf2-mjmxgb) — the dev-only consumer-attachment
    ;; lints (consume-without-declaring + ambient-durable). Run here in the
    ;; home (with the machine-id known) over a locally-indexed copy so the
    ;; lints read the parsed diets without altering the stored `:rf/machine`
    ;; meta shape. DCE'd in production (`interop/debug-enabled?`-gated inside
    ;; `lint-machine!`). Idempotent — `index-ensure-sets` is pure.
    (cofx-attach/lint-machine! machine-id (cofx-attach/index-ensure-sets machine))
    (trace/emit! :rf.machine.lifecycle/created :rf.machine.lifecycle/created
                 {:machine-id machine-id
                  :initial    (:initial machine)})
    machine-id)))

;; ---- reg-machine* — plain-fn surface (rf2-8bp3) ---------------------------

(defn reg-machine*
  "Plain-fn surface beneath the `reg-machine` macro. Registers a machine
  as an event handler under `machine-id`. Equivalent to
  `(reg-event machine-id (make-machine-handler machine))`.

  Per Spec 005 §reg-machine vs reg-machine*: the macro `reg-machine`
  walks the literal spec form at expansion time and co-locates per-element
  source onto each `:guards` / `:actions` entry plus a reference-site
  `:source-coords` onto each `:states`-tree map node (rf2-npvsx /
  rf2-vqja2). This fn assumes no such walking — it accepts whatever spec
  map the caller has already constructed. Use this fn for runtime
  registration with computed ids, fixture-synthesised specs, or REPL
  workflows.

  Per Spec 005 §Querying machines, the registration metadata is stamped
  with `:rf/machine? true` and `:rf/machine` (the spec map).
  `(rf/machines)` filters the `:event` registry by `:rf/machine?`;
  `(rf/machine-meta id)` reads the spec back out via the standard
  registrar query API.

  Per Spec 001 §Source-coordinate capture, the call-site `:ns` /
  `:line` / `:file` carried by `re-frame.source-coords/*pending-coords*`
  (set by the `reg-machine` macro) is merged into the registration
  metadata via the `reg-event` defn's `merge-coords` call.

  ## Grammar — the metadata-map is the MIDDLE slot (rf2-wvh95f F2)

  The 3-arity is `(reg-machine* machine-id opts machine)` — the optional
  `opts` registration-metadata map sits in the canonical Spec 001 MIDDLE
  slot, exactly as the `reg-machine` macro's `(reg-machine id opts machine)`
  surface does. The event-vector `:schema` (the `:where :event` boundary
  validator for the dispatched OUTER vector — the machine + event-vector-
  schema shape login / realworld auth need) therefore lives in the metadata
  map, NOT a trailing slot; any other opts keys (`:doc`, …) ride onto the
  registration metadata verbatim. The reserved `:rf/machine?` / `:rf/machine`
  keys are framework-owned and stamped by the home — they MUST NOT be
  supplied in `opts`. The 2-arity `(reg-machine* machine-id machine)` has no
  opts."
  ([machine-id machine]
   (register-machine-event! machine-id machine nil))
  ([machine-id opts machine]
   (register-machine-event! machine-id machine opts)))

(defn handler-meta-for
  "Build the registrar-shaped handler-meta map for a machine `spec`
  WITHOUT registering it — the surface the lazy-actor-handler resolver
  (rf2-a2sn1) drives a spawned actor's cascade through. Equivalent in
  shape to what `reg-machine*` installs under the machine-id, but
  materialised on demand from the actor's (revertible) runtime-db snapshot
  rather than held in a per-instance registrar entry.

  The region-machine cache is installed (mirroring `reg-machine*`) so
  parallel-region transitions resolve, then `make-machine-handler` builds
  the handler-fn and `events/event-handler-meta` wraps it into the same
  `{:rf/machine? true :rf/machine <spec> :handler-fn …
  :interceptors […]}` shape the registrar holds for a registered
  machine. `process-event*` runs the returned meta unchanged — the
  handler reads the actor's own snapshot keyed on the dispatched event's
  first element, so a single materialised handler serves every instance
  of the type."
  [spec]
  (let [machine    (parallel/install-region-cache spec)
        ;; rf2-genufr — this materialisation seam stamps the `:rf/machine?` /
        ;; `:rf/machine` meta below, so it is a legitimate `make-machine-handler`
        ;; home for a `:data-schema`-bearing spec — bind the flag so the
        ;; fail-loud guard passes. (EP-0025, rf2-398kql: the per-instance
        ;; schema→marks bridge its caller once re-ran is gone.)
        handler-fn (binding [*in-registration-home?* true]
                     (make-machine-handler machine))]
    (events/event-handler-meta {:rf/machine? true :rf/machine machine}
                               []
                               handler-fn)))

(defn resolve-actor-handler-meta
  "Lazy-resolver hook body (late-bound at
  `:machines/resolve-actor-handler-meta`, consulted by core's
  `re-frame.router.diagnostics/handle-no-handler!` BEFORE it surfaces
  `:rf.error/no-such-handler`). Given an `event` whose first element is
  an unresolved actor-id and the target `frame-id`, return a handler-meta
  map the router can drive the cascade with — or nil to let core surface
  the genuine `:rf.error/no-such-handler`.

  Resolution is purely runtime-db-derived (rf2-a2sn1): read the actor's live
  snapshot from the frame's runtime-db, resolve its TYPE spec via
  `resolver/spec-from-snapshot`, and materialise the handler-meta from
  the spec. No live snapshot (or no resolvable type) → nil: the actor
  isn't alive in this frame value, which is the correct
  `:no-such-handler` — and exactly the property `restore-epoch!` reverts.

  The materialised handler-meta is shape-identical to a registered
  machine's, so the rest of `process-event*` is unchanged — it runs the
  handler against the actor's own snapshot (the machine handler reads
  `[:rf.runtime/machines :snapshots <actor-id>]` from runtime-db, keyed on
  the dispatched event's first element). The handler-fn is built fresh per
  unresolved dispatch; this is the COLD path (a spawned actor whose
  per-instance handler is — by design — never registered), so the allocation
  is bounded by actual spawned-actor traffic, not by every dispatch.

  EP-0001 (rf2-vzld77): machine snapshots are durable runtime-db state, so
  the live snapshot is read off the frame's runtime-db partition.

  EP-0025 (rf2-398kql): this seam formerly ALSO re-ran the per-instance
  `:data-schema`→marks bridge (rf2-egvm4t) to rehydrate a restored / replayed
  actor's schema-derived snapshot-egress marks. That bridge is REMOVED —
  schema-field classification is killed in favour of frame-declared paths — so
  there are no per-instance schema marks to rehydrate here; the seam now only
  materialises the handler-meta from the restored snapshot's spec."
  [event frame-id]
  (let [actor-id   (first event)
        ;; EP-0002 — `frame-id` is the cascade-threaded envelope frame the
        ;; router's no-handler diagnostics pass in; read its runtime-db
        ;; directly, no `:rf/default` repair. (A nil frame-id yields nil
        ;; runtime-db → the genuine `:no-such-handler`, never a read against
        ;; a synthesised default.)
        runtime-db (frame/frame-runtime-db-value frame-id)
        snapshot   (when runtime-db (get-in runtime-db (paths/snapshot-path actor-id)))
        spec       (when snapshot (resolver/spec-from-snapshot snapshot))]
    (when spec
      (handler-meta-for spec))))
