(ns re-frame.machines.lifecycle-fx.registration
  "Registration boundary: handler factory + `reg-machine*`.

  `make-machine-handler` is the `reg-event` handler factory beneath the
  `reg-machine` macro (one-event surface — a single
  `(cofx, event) -> effects-map-or-nil` handler, no `reg-event-fx` /
  `reg-event-db` split); `reg-machine*` is the plain-fn surface used by
  the late-bind table and by REPL workflows. The factory decomposes
  into:

    - `validate-machine!` — every registration-time check (extracted to
      `re-frame.machines.lifecycle-fx.validation`: parallel shape,
      `:spawn-all` shape, dropped `:timeout-ms` slots, guard/action
      ref resolution, final-state shape).
    - `parallel/build-initial-snapshot` — initial-state
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
            [re-frame.interop :as interop]
            [re-frame.machines.classification :as classification]
            [re-frame.machines.cofx-attach :as cofx-attach]
            [re-frame.machines.error-emit :as machine-error-emit]
            [re-frame.machines.internal-events :as internal-events]
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

;; ---- single-registration-home flag ----------------------------------------
;;
;; A machine that carries a `[:schemas :data]` schema MUST flow through the
;; single registration home so the `:rf/machine?` / `:rf/machine` registration-
;; metadata stamp runs — the `:where :machine-data` post-commit walker resolves
;; the `[:schemas :data]` schema THROUGH `(machine-meta id)`, so without the
;; stamp the schema validates NOTHING.
;;
;; `register-machine-event!` below is the SINGLE HOME that stamps it (it is the
;; body of `reg-machine*` AND the event-`:schema` arity). The bare
;; `(reg-event id meta (make-machine-handler spec))` direct path does NOT stamp
;; it — which is exactly how a `[:schemas :data]` schema could go silently
;; inert. So `make-machine-handler` FAILS LOUD when it is handed a
;; `[:schemas :data]`-bearing spec OUTSIDE the home (`*in-registration-home?*`
;; unbound to true): a schema-bearing machine MUST flow through `reg-machine` /
;; `reg-machine*`. The home, plus the spawned-actor materialisation seams
;; (`handler-meta-for` / `resolve-actor-handler-meta`), bind the flag around
;; their `make-machine-handler` calls. A schema-LESS machine has nothing to
;; validate, so the bare direct path stays legal for it (the Story testbed /
;; schema-free examples rely on it).
;;
;; The home runs exactly one `[:schemas :data]` side-effect: the
;; validation-stamp. The machine `[:schemas :data]` schema is VALIDATION-ONLY —
;; per-slot props do not classify durable `:data` for snapshot egress. EP-0025:
;; durable `:data` classification rides the projection-relative subsystem
;; declaration (lowered per actor under `:source :effect`); the commit-plane
;; `:sensitive` / `:large` effects are the general app-db data-classification
;; mechanism.
(def ^:dynamic *in-registration-home?*
  "True while `make-machine-handler` is invoked from a registration site that
  ALSO stamps the `:rf/machine?` / `:rf/machine` meta (the single home, or the
  spawned-actor resolver seams). When false/unbound, a `[:schemas :data]`-bearing
  spec handed to `make-machine-handler` is an unstamped-schema misconfiguration
  — fail loud (the schema would validate nothing)."
  false)

;; The reserved creation marker is `:rf.machine/start`. It is defined once in
;; the leaf engine namespace as `transition/start-marker` so the handler here
;; and the cascade in `parallel` share one source of truth without a require
;; cycle. It is a PURE init-kick: `maybe-boot` runs the initial-entry
;; cascade, then the handler STOPS — the marker is NEVER fed into `run-step`
;; as a trigger (no `before == after` self-transition, no `:*`-wildcard throw).

;; The initial-snapshot builder lives in `parallel.cljc` as
;; `build-initial-snapshot` — single source of truth for both the
;; singleton-registration path (here) and the spawn path
;; (`lifecycle-fx.spawn/install-spawn!`), so both seed `:rf/spawn-counter` and
;; `:meta` identically. See `parallel/build-initial-snapshot` for the canonical
;; 6-step shape.

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

  Privacy (AI/MCP egress + logs threat model): the
  `:event` slot is redacted by the marks projection's `project-event-tags`
  and `:before`/`:after`/`:snapshot` by `project-machine-tags`, but the
  `:exception-data` slot below carries the thrown action's `ex-data` — the
  developer's arbitrary exception payload, which could embed the same app
  secrets the machine's `:data` marks gate. That slot is path-elided at the
  trace egress chokepoint by `re-frame.classification/project-machine-error-tags`:
  when the machine declares ANY `:sensitive`
  mark the whole `:exception-data` slot scrubs to `:rf/redacted` before the
  trace crosses the bus / epoch-capture / AI-MCP boundary or a log sink —
  the same egress seam (the marks projection) the core validation-failure
  class routes through. The redaction lives at
  the chokepoint (not here) so it covers EVERY consumer of the trace
  uniformly and stays the single place the `:sensitive` decision is made.

  Per Spec 005 §Final states §`:on-error` (XState v5 invoke
  `onError`): an uncaught child action exception is a control-flow `:on-error`
  trigger in addition to the observability trace. When the
  THROWING actor is a `:spawn`-spawned child whose spawning parent declares
  `:spawn :on-error`, route the failure to the parent's declarative
  `:on-error` transition via `spawn-error/dispatch-spawn-error!` — additive to
  (not a replacement for) the trace above, which still fires for every action
  exception. `ctx` carries the failing actor's `:db` + `:snapshot` (whose
  `:data` was stamped with `:rf/parent-id` / `:rf/invoke-id` at spawn time);
  the exception envelope rides as the parent transition's `:event` payload so
  a guard / action can branch on it. Singletons (no parent) and parents that
  declare no `:on-error` route nowhere — the trace IS the signal."
  [ctx event reason info]
  (let [{:keys [machine-id frame-id runtime-db snapshot]} ctx
        ex         (:exception info)
        ex-msg     #?(:clj  (when ex (.getMessage ^Throwable ex))
                      :cljs (when ex (.-message ex)))
        ex-data    (when ex (ex-data ex))
        action-ref (:action-ref info)
        state-path (:state-path info)
        ;; `:failing-id` names the failing CODE identifier — uniform with the
        ;; interceptor / cofx always-on categories (Spec 009 §What IS available
        ;; §Attribution rule): the throwing action / guard's KEYWORD when it is
        ;; a named `:actions` / `:guards` entry, else the live actor instance
        ;; address (an anonymous inline fn has no keyword identity — the
        ;; `:state` slot still attributes WHICH state's transition threw). It is
        ;; ALWAYS a keyword. (Previously stamped `machine-id` unconditionally —
        ;; the misattribution rf2-cprm0q defect 2 fixes: a machine throw
        ;; arrived as \"machine :auth/flow threw\" with no action attribution.)
        failing-id (if (keyword? action-ref) action-ref machine-id)]
    ;; Fan out BOTH error channels (rf2-cprm0q defect 1): the always-on
    ;; production-survivable record (surface #4, carrying `:failing-id` +
    ;; `:state`) AND the dev-only trace. Guard throws converge here too
    ;; (`transition/guard-threw->fail-info` projects the guard-ref onto
    ;; `:action-ref`), so this single site fixes BOTH action and guard throws.
    ;; Axis 1 — STRUCTURAL-ONLY always-on record (no prose / no value-bearing
    ;; slots; see error-emit). The throwing action ran in a LIVE actor's
    ;; transition; `machine-id` here is the event-handler key (the running
    ;; INSTANCE address), so it rides under `:actor-id` (reserved `:machine-id`
    ;; names the registered TYPE only). `:failing-id` is the throwing action /
    ;; guard keyword (else the actor instance); `:state` is the active state
    ;; path (the attribution slot `:rf.machine/guard-evaluated` also uses).
    (machine-error-emit/emit-machine-action-exception!
      {:actor-id          machine-id
       :failing-id        failing-id
       :state             state-path
       :frame             frame-id
       :exception         ex
       :recovery          :no-recovery})
    ;; Axis 2 — dev-only trace. Wrapped in an EXPLICIT `interop/debug-enabled?`
    ;; call-site gate so Closure constant-folds the whole form — the dev-only
    ;; PROSE `:reason` / `:exception-message` and the value-bearing `:event` /
    ;; `:exception-data` (redacted downstream at the marks chokepoint) all
    ;; included — away under :advanced + goog.DEBUG=false. Leaving the direct
    ;; call ungated beside the live always-on call above would leak the prose
    ;; (rf2-cprm0q). `:state-path` is retained for the existing dev-trace shape.
    (when interop/debug-enabled?
      (trace/emit-error! :rf.error/machine-action-exception
        {:actor-id          machine-id
         :action-id         action-ref
         :state-path        state-path
         :state             state-path
         :transition        (:transition info)
         :event             event
         :failing-id        failing-id
         :handler-id        machine-id
         :frame             frame-id
         :exception         ex
         :exception-message ex-msg
         :exception-data    ex-data
         :reason            reason
         :recovery          :no-recovery}))
    ;; Additive control-flow routing. Read the spawning
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
           ;; The failing child is a LIVE actor INSTANCE.
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

  A bounded-depth abort (`:always` / `:raise` depth limit
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

;; ---- 4-step pipeline ------------------------------------------------------
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

;; ---- snapshot/definition compatibility ------------------------------------
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
;; The reconciler verifies the snapshot's state still exists in the (possibly
;; hot-reloaded) definition AND that the version stamps agree before driving
;; it through the transition engine — so a vanished state or a version bump
;; surfaces as the spec's named warning + fallback behaviour rather than a
;; cryptic downstream error from an incompatible snapshot.

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
  (bz0ox.2 / x4s9t.2). A partial map like `{:left :done}` for a 2-region
  machine fails to resolve, so it can never run a partial configuration that
  vacuously fires root `:on-done` / auto-destroy.

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
  uses it as the basis for `needs-bootstrap?` / the transition.

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
  "Step 1 of 4. Stamp the live frame / platform / parent-id
  onto the machine def, look up the existing snapshot at
  `[:rf.runtime/machines :snapshots <machine-id>]` in the **runtime-db**
  partition (machine snapshots are durable runtime-db state),
  decide `needs-bootstrap?`, route the inner event. Returns a
  `ctx` map the remaining three steps read. `runtime-db` is the
  `:rf.db/runtime` coeffect the router injected by reference.

  Detect 'first event for this machine' so the initial
  state's `:entry` actions fire as part of bringing the machine to life.
  Two flavours:
    - Singleton path: `(get-in runtime-db path)` is `nil` — the snapshot is
      being lazily synthesised right now.
    - Spawn path: `spawn-fx` pre-seeded the snapshot at
      `[:rf.runtime/machines :snapshots <spawned-id>]` and stamped
      `:rf/bootstrap-pending? true` so the actor's first dispatch sees
      the marker and runs the cascade before processing the event.

  When an existing snapshot is found, run the Spec 005
  §Snapshot shape stability invariants 3 & 4 reconciler before
  threading it onward — a hot-reload that dropped a state, or a
  `:rf/snapshot-version` bump, replaces the snapshot with a fresh
  initial-state derivative (with `:rf/bootstrap-pending? true` so
  `:entry` fires this same handler call) and emits the named
  `:rf.error/machine-state-not-in-definition` or
  `:rf.error/machine-snapshot-version-mismatch` event.

  The event handler's causal
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
        ;; `frame` is the cascade-threaded envelope frame the
        ;; calling machine handler already asserted via
        ;; `require-frame-stamp!`; no `:rf/default` repair here.
        frame-id      frame
        platform      (or (:platform (frame/frame-meta frame-id)) :client)
        machine       (cond-> (assoc machine
                                     :rf/frame     frame-id
                                     :rf/platform  platform
                                     :rf/parent-id machine-id)
                        ;; Thread the causal recordable-coeffect token onto the
                        ;; machine def so `callback-ctx` exposes it as `:rf.cofx`.
                        ;; Stamp only when present so the absent-token path stays
                        ;; clean for pure-fn callers.
                        (some? cofx) (assoc :rf/cofx cofx)
                        ;; Thread the resolved effective cofx MINT
                        ;; POLICY (per-call opt ▸ frame config ▸ `:live`, the
                        ;; router stamped it as the `:rf.cofx/mint-policy`
                        ;; framework coeffect) onto the machine def under the
                        ;; reserved `:rf/cofx-mint-policy` key. The dispatch-time
                        ;; / bootstrap ensure steps read it so a `:strict`
                        ;; replay/`:test` machine refuses to mint a declared-
                        ;; absent generator-backed guard/action fact (surfacing
                        ;; missing-required) instead of always defaulting to
                        ;; `:live`; the in-engine raised-event ensure reads the
                        ;; SAME stamped policy. Stamped only alongside a
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
     ;; flavours for the `:rf.machine/started` `:cause`:
     ;;   - nil snapshot  → singleton (`:explicit` / `:lazy`, by trigger);
     ;;   - present + `:rf/bootstrap-pending?` → spawn-pre-seeded (`:spawned`).
     :existing-snap?   (some? existing-snap)
     :needs-bootstrap? (or (nil? existing-snap)
                           (true? (:rf/bootstrap-pending? snapshot)))
     :inner-event      (route-inner-event event)}))

(defn- start-cause
  "Compute the `:rf.machine.start/cause` enum for a `maybe-boot` that ran
  the initial-entry cascade. Three-way:

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
                    smell the Xray `[START]` badge surfaces)."
  [ctx]
  (cond
    (:existing-snap? ctx)                                  :spawned
    (= transition/start-marker (first (:inner-event ctx))) :explicit
    :else                                                  :lazy))

(defn- ensure-bootstrap-cofx
  "The BIRTH-time consumer-attachment
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
            ;; Ensure under the resolved effective mint policy the
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
  "Step 2 of 4. If `ctx` is bootstrap-pending, run
  `apply-initial-entry-cascade` once before processing the user event.
  Bootstrap fx flow OUT of the handler ahead of any fx the user event
  produces — entry happens-before user-event handling. Returns a Result
  whose `::snap` has `:rf/bootstrap-pending?` cleared on success.

  The bootstrap cascade fires the initial state's `:entry`
  actions; a Result `:fail` short-circuits the rest of the pipeline.

  This is the machine's SINGLE birth site — it runs in both
  axis-A paths (eager `:rf.machine/start` kick AND lazy first-real-event).
  So whenever the cascade succeeds it emits ONE `:rf.machine/started`
  trace carrying `{:machine-id :frame-id :state :data :cause}` — the
  signal Xray renders as the `[START]` badge in BOTH paths
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
            ;; Preserve the bootstrap-entry `::cascade` so
            ;; `commit-or-finalize` can prepend the initial-descent `:entry`
            ;; steps when bootstrap and the user event land in the same call.
            (result/with-cascade
              (result/ok booted fx)
              (result/cascade r))))))
    (result/ok (:snapshot ctx) [])))

(defn- run-step
  "Step 3 of 4. Run the pure macrostep against the post-boot
  snapshot + the routed inner event. Returns the Result from
  `parallel/machine-transition` — caller inspects `result/fail?` /
  `result/ok?` and projects accordingly."
  [ctx post-boot-snap]
  (parallel/machine-transition (:machine ctx) post-boot-snap (:inner-event ctx)))

(defn- ensure-ctx-cofx
  "The dispatch-time consumer-attachment
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

  Ensuring runs under the resolved effective mint policy the router stamped
  (`:rf/cofx-mint-policy`). A provided fact absent from the record is
  `:rf.error/missing-required-cofx`; a schema-invalid value is
  `:rf.error/cofx-value-invalid` (both production hard errors, propagated by
  `deliver-declared-cofx`)."
  [ctx post-boot-snap]
  (let [machine (:machine ctx)]
    ;; Only run when a causal token was threaded (the normal dispatch path
    ;; stamps `:rf/cofx`); pure-fn callers carry no token and consume no
    ;; recordable facts, so there is nothing to ensure.
    (if-not (contains? machine :rf/cofx)
      ctx
      (let [recorded  (:rf/cofx machine)
            ;; Ensure under the resolved effective mint policy the
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
  "Step 4 of 4. Emit `:rf.machine/transition` (and optional
  `:rf.machine/snapshot-updated`) traces, build the new app-db, and
  route to `finalize-machine` if the post-transition snapshot is on a
  final leaf / all regions final.

  Per Spec 005 §Final states: the finality flag is recomputed
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
          ;; When this handler call both bootstrapped the
          ;; machine AND processed a user event, the `:before`/`:after`
          ;; slots span both — so the cascade prepends the bootstrap entry
          ;; cascade (the initial-descent `:entry` steps) ahead of the
          ;; event-driven cascade, matching the macrostep the trace reports.
          ;; A no-bootstrap call's `boot-result` carries an empty cascade.
          cascade   (into (vec (result/cascade boot-result))
                          (result/cascade step-result))
          ;; A genuine no-op macrostep emits NO
          ;; `:rf.machine/transition`. An unhandled / guard-blocked event
          ;; already signals the benign `:rf.machine.event/unhandled-no-op`
          ;; (the engine's `:else` branch + the parallel aggregate); a
          ;; redundant `[:rf.machine/start]` on an already-booted machine
          ;; is the reserved-`:rf/*` carve-out that emits
          ;; nothing at all. In BOTH cases the macrostep changed
          ;; nothing — `:before` == `:after`, the combined (boot + step)
          ;; cascade is empty, and zero `:always` microsteps ran — so a
          ;; no-change transition trace (before = after, `:microsteps 0`,
          ;; `:cascade []`) adds no information and CONTRADICTS the no-op
          ;; signal (it borrows external-self-transition `{X}→{X}`
          ;; vocabulary that implies the `:exit`/`:entry` firing that did
          ;; NOT happen). Suppressing it makes the no-op
          ;; SINGLE-signalled + consistent across unhandled / blocked /
          ;; redundant-bootstrap.
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
          ;; Per Spec 005 §Final states §Embedded vs top-level — the D7
          ;; reconciliation. Whole-machine finality (route
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
          ;;     too — `:on-done` fires once on entry (the edge guard) yet
          ;;     the machine must not tear down on the later resting macrosteps
          ;;     where the flag is (correctly) absent.
          finished? (or (and (not (parallel/parallel? machine))
                             (transition/top-level-final? machine (:state next-snapshot)))
                        (and (finalize/all-regions-final? machine (:state next-snapshot))
                             (nil? (:on-done machine))))
          ;; Machine snapshots are durable runtime-db state:
          ;; write the new snapshot into the runtime-db partition value;
          ;; the handler returns it under `:rf.db/runtime`, NOT `:db`.
          new-runtime-db (assoc-in runtime-db path next-snapshot)]
      ;; The `:frame` tag is REQUIRED for epoch-capture
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
      ;; The trace ALSO carries `:cascade` — the structured,
      ;; ordered step vector explaining HOW the transition reached its
      ;; after-state: exit (deepest-first) → transition `:action` @ LCA →
      ;; entry (shallowest-first + initial-descent), each step with its
      ;; `:kind` / `:state` / `:region` / `:action` / `:data-delta`, plus a
      ;; `:microstep` step (carrying nested `:steps`) per `:always`
      ;; iteration, with per-region structure for parallel machines. This is
      ;; the contract Xray's epoch panel renders. It rides under
      ;; the same handler-scope `:sensitive?` stamp as `:before` / `:after`
      ;; (per Spec 005 §Privacy), so a sensitive machine's cascade
      ;; `:data-delta`s are scrubbed at egress alongside the snapshot slots.
      (when-not no-op?
        (trace/emit! :rf.machine :rf.machine/transition
                     {:frame      frame-id
                      ;; The LIVE actor instance address (the
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

(defn- reject-internal-event-external-dispatch
  "The public / private `:internal-events` BOUNDARY (EP-0029 A6), checked at
  the machine dispatch boundary. If the routed `inner-event` names a
  declared INTERNAL event of `machine`, this is an EXTERNAL dispatch of a
  private event — reject it: emit `:rf.error/machine-internal-event-external-dispatch`
  and return the benign no-op effect-map `{}` (NO state change — the
  committed snapshot is untouched, atomic, exactly the shape an unhandled
  event takes). Returns nil when the event is NOT a rejected internal
  dispatch, so the caller proceeds with normal handling.

  An internal `:raise` is drained INSIDE the macrostep through the FIFO
  internal-event queue and never re-enters via `reg-event` dispatch, so this
  boundary never refuses a self-raised event — only an outside caller's
  `rf/dispatch`. The reserved `:rf.machine/start` creation marker is
  framework lifecycle traffic, not a user internal event, so it is never an
  internal-event key (a user cannot declare a reserved `:rf/*` name as an
  internal event); the `internal-event-external?` membership check naturally
  excludes it."
  [machine machine-id frame-id inner-event]
  (when (internal-events/internal-event-external? machine inner-event)
    (trace/emit-error! :rf.error/machine-internal-event-external-dispatch
                       {;; The LIVE actor instance address that received the
                        ;; rejected external dispatch (the running INSTANCE —
                        ;; a singleton's registration id, or a spawned actor's
                        ;; `<type>#<n>` id). Reserved `:machine-id` names the
                        ;; registered TYPE only.
                        :actor-id   machine-id
                        :event      inner-event
                        :event-id   (first inner-event)
                        :frame      frame-id
                        ;; Registration is fine; the FIX is to not dispatch a
                        ;; private event from outside — raise it internally
                        ;; via `:raise`, or expose a public `:on` clause.
                        :recovery   :no-recovery})
    ;; Benign no-op: no snapshot write reaches runtime-db.
    {}))

(defn make-machine-handler
  "Returns a function suitable for registration with `reg-event`.

  Per Spec 005 §Registration — the machine IS the event handler. The
  machine spec MUST NOT carry `:id`; the machine's id is the surrounding
  registration's event-id, derived at handler-call time from the
  dispatched event vector's first element.

  The body is decomposed into:
    - `validation/validate-machine!` — every registration-time check.
    - `parallel/build-initial-snapshot` — initial-state cascade, `:data` /
      `:meta` seeding, `:rf/spawn-counter` seeding, tag union stamping
      (unified with the spawn path).
    - the returned handler fn — frame stamping, intercept-spawn-all-
      event branch (in `lifecycle-fx.join`), bootstrap-pending detection
      + initial-entry cascade, machine-transition dispatch, action-failure
      projection, finalize delegation (in `lifecycle-fx.finalize`).

  The returned handler fn is further decomposed into a
  four-step pipeline — `prepare-machine-ctx` → `maybe-boot` →
  `run-step` → `commit-or-finalize` — with the intercept-spawn-all-
  event short-circuit branching off after step 1."
  [machine]
  ;; Consumer attachment. Run FIRST so
  ;; the inline-fn restriction (`:rf.error/machine-cofx-requires-inline`) and
  ;; the named-entry `:rf.cofx/requires` parse (`:rf.error/cofx-request-invalid`
  ;; / `-name-collision`) surface as their OWN precise categories before
  ;; `validate-machine!`'s guard/action ref resolution would otherwise paint a
  ;; bare-entry-map-with-requires-no-`:fn` as the generic
  ;; `:rf.error/machine-unresolved-guard`. The indexed spec carries
  ;; `:rf/cofx-ensure-index` for the dispatch-time `ensure-ctx-cofx` to read.
  (let [machine (cofx-attach/index-ensure-sets machine)]
  (validation/validate-machine! machine)
  ;; Fail-loud guard. A `[:schemas :data]`-bearing spec MUST be
  ;; registered through the single home (`reg-machine` / `reg-machine*` / the
  ;; event-`:schema` arity), which is the ONLY place the `:rf/machine?` /
  ;; `:rf/machine` registration-metadata stamp runs — the `:where :machine-data`
  ;; post-commit walker resolves the `[:schemas :data]` schema THROUGH
  ;; `(machine-meta id)`, so without the stamp the schema validates NOTHING. The
  ;; bare `(reg-event id meta (make-machine-handler spec))` direct path does not
  ;; stamp it — so a `[:schemas :data]` schema reached here outside the home
  ;; would be silently inert. Surface it at the moment of construction rather
  ;; than letting it no-op. A schema-LESS spec is unaffected — the bare direct
  ;; path stays legal for it. The guard secures the validation-stamp: it ensures
  ;; a `[:schemas :data]` schema reaches the home that stamps the meta the
  ;; validator resolves through. (The schema's props do not classify `:data` for
  ;; egress — schema is validation-only.)
  (when (and (get-in machine [:schemas :data])
             (not *in-registration-home?*))
    (error/throw-error!
      :rf.error/machine-schema-requires-reg-machine
      'rf-machines/make-machine-handler
      (str "make-machine-handler was handed a machine spec carrying a "
           "[:schemas :data] schema via the bare (reg-event id meta "
           "(make-machine-handler spec)) direct path. That path does NOT "
           "stamp the :rf/machine? / :rf/machine registration metadata, so "
           "the [:schemas :data] schema validates NOTHING. Register the "
           "machine through reg-machine / reg-machine* — and when the machine "
           "ALSO validates its outer event vector, use the event-:schema "
           "arity (the opts metadata map is the canonical MIDDLE slot): "
           "(reg-machine id {:schema EventSchema} machine) or "
           "(reg-machine* id {:schema EventSchema} machine).")
      {:recovery :use-reg-machine
       :extra    {:schemas (:schemas machine)}}))
  ;; `build-initial-snapshot` runs lazily INSIDE the
  ;; returned handler, not at registration time. The initial-state
  ;; computation reaches through `:initial` / `:states` / `:regions`;
  ;; running it at registration time would force every registered spec
  ;; (including the stub child machines the conformance corpus declares
  ;; without `:initial` for `:spawn` targets, and any spec whose
  ;; `:initial` derives from a fn-form computed at dispatch time) to
  ;; satisfy the snapshot shape at reg-machine call time. Deferring the work
  ;; to the handler keeps registration tolerant of such specs.
  ;;
  ;; `machine` already carries the consumer-attachment index
  ;; (`:rf/cofx-ensure-index`, stamped above by `cofx-attach/index-ensure-sets`
  ;; ahead of `validate-machine!`), so the handler closure captures it and
  ;; `prepare-machine-ctx` threads it onto the machine def — the dispatch-time
  ;; `ensure-ctx-cofx` reads it to derive the per-(state × event-type)
  ;; ensure-set (incl. the `:always` closure).
  (let [base-initial (delay (parallel/build-initial-snapshot
                              machine {:bootstrap-pending? false}))]
    (fn [{:keys [db] frame :rf.frame/id rt :rf.db/runtime
          cofx :rf.cofx mint-policy :rf.cofx/mint-policy :as _cofx} event]
      ;; A machine handler is invoked inside an
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
      ;; Machine snapshots are durable runtime-db
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
          ;; EP-0029 A6 — the public / private `:internal-events` BOUNDARY.
          ;; If the routed inner event names a declared INTERNAL event, this
          ;; is an EXTERNAL dispatch of a private event: reject it (emit
          ;; `:rf.error/machine-internal-event-external-dispatch`, no-op `{}`
          ;; — no snapshot write). An internal `:raise` drains inside the
          ;; macrostep's FIFO queue and never reaches here, so a self-raised
          ;; event is unaffected. Checked BEFORE boot/step so a private event
          ;; can neither create the machine nor drive a transition.
          (or
            (reject-internal-event-external-dispatch
              (:machine ctx) (:machine-id ctx) (:frame-id ctx) (:inner-event ctx))
          ;; Ensure the BIRTH initial-entry
          ;; ensure-set onto the in-flight `:rf.cofx` record BEFORE `maybe-boot`
          ;; runs the bootstrap cascade (which fires the initial-state `:entry`
          ;; actions). The augmented `ctx` flows into both `maybe-boot` and the
          ;; downstream `ensure-ctx-cofx` / `run-step`, so a generated birth fact
          ;; is written back into the record the epoch captures.
          (let [ctx         (ensure-bootstrap-cofx ctx)
                boot-result (maybe-boot ctx)]
            (if (result/fail? boot-result)
              ;; Route both a thrown initial-entry action AND a
              ;; birth-time bounded-depth abort (a born-state `:always` / raise
              ;; runaway) through the shared failure handler: a depth-abort
              ;; skips the misleading action-exception re-emit (its precise
              ;; category fired in-engine) while both atomically roll back.
              (handle-step-failure! ctx [transition/start-marker]
                                    "Machine initial-entry action threw."
                                    boot-result)
              (do
                ;; EP-0025 §subsystems (rf2-h3d8tf): LOWER a SINGLETON's
                ;; projection-relative `:sensitive` / `:large` `:data`
                ;; declarations into the per-frame elision registry at its
                ;; birth (first-boot). A singleton's actor-id IS its
                ;; machine-id, installed lazily on first dispatch rather than
                ;; via `spawn-fx` (which lowers spawned actors). Gated on the
                ;; singleton-birth case — `:needs-bootstrap?` AND NOT
                ;; `:existing-snap?` (a spawned actor carries a pre-seeded
                ;; snapshot ⇒ `:existing-snap? true`, already lowered at spawn).
                ;; Value-independent + idempotent, dropped at destroy /
                ;; finalize. A spec declaring no classification is a no-op.
                (when (and (:needs-bootstrap? ctx) (not (:existing-snap? ctx)))
                  (classification/lower-at-spawn! (:frame-id ctx) (:machine-id ctx)
                                                  (:machine ctx)))
              (result/with-ok [post-boot-snap boot-fx] boot-result
                ;; PURE init-kick: when the routed inner event IS the
                ;; reserved `:rf.machine/start` marker, `maybe-boot` has
                ;; already run the INITIAL MACROSTEP — the initial-entry
                ;; cascade AND the birth-time `:always` + raise settle,
                ;; via `parallel/apply-initial-entry-cascade`
                ;; — and emitted `:rf.machine/started`. STOP here — never
                ;; feed the synthetic marker into `run-step` as a trigger.
                ;; This avoids a misleading `before == after` self-
                ;; transition for quiet states and a `:*`-wildcard throw
                ;; for `:fuse/box`-shaped initial states; the marker
                ;; means exactly "create + run the initial macrostep," like
                ;; `xstate.init`. (`commit-or-finalize` is bypassed: a pure
                ;; start emits no `:rf.machine/transition`;
                ;; `:rf.machine/started` is the sole birth signal.)
                ;;
                ;; The birth `:always` settle CAN land
                ;; the machine on a final leaf at start (an initial leaf
                ;; whose `:always` targets a final state) — XState v5 treats
                ;; such an actor as done immediately. So recompute finality
                ;; from the settled snapshot (mirroring `commit-or-finalize`)
                ;; and route to `finalize-machine` when finished, so the
                ;; `:on-done` + auto-destroy cascade fires on eager start
                ;; just as it would on the lazy path. The
                ;; embedded-vs-top-level reconciliation applies here too:
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
                  ;; Ensure the derived
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
                      ;; Route both a thrown transition action AND
                      ;; a bounded-depth abort (the runaway `:always` / `:raise`
                      ;; cycle, XState-v5-throws case) through the shared
                      ;; failure handler. A depth-abort surfaces as a FAILED
                      ;; macrostep (atomic rollback, the precise depth-exceeded
                      ;; category already emitted in-engine), so a runaway
                      ;; settle never silently swallows the event as a no-op.
                      (handle-step-failure! ctx (:inner-event ctx)
                                            "Machine action threw."
                                            step-result)
                      ;; Pass the full `boot-result` (fx +
                      ;; bootstrap-entry cascade), not just `boot-fx`, so a
                      ;; same-call bootstrap's entry cascade prepends the
                      ;; event-driven cascade on the `:rf.machine/transition`
                      ;; trace.
                      (commit-or-finalize ctx step-result boot-result)))))))))))))))

;; ---- [:schemas :data] is validation-only; classification is machine-owned --
;;
;; A machine's `[:schemas :data]` schema is an OPAQUE schema value that
;; VALIDATES the machine's `:data` slot (through the late-bound optional
;; validator adapter — machine core requires no schema library). It is a
;; validation contract only — its per-slot `:sensitive?` / `:large?` props do
;; NOT classify the machine's durable `:data` for trace / SSR egress.
;;
;; EP-0025 §subsystems (rf2-h3d8tf): durable `:data` egress classification is
;; MACHINE-OWNED and PROJECTION-RELATIVE — a machine declares its sensitive /
;; large `:data` slots via top-level `:sensitive` / `:large` keys on the spec
;; (rooted at one actor snapshot's `:data`), validated for shape at registration
;; (`re-frame.machines.classification/validate-machine-classification!`, above)
;; and LOWERED per actor instance into the per-frame elision registry at spawn /
;; first-boot (dropped at destroy). The `[:schemas :data]` schema is for
;; validation only.

;; ---- the single registration home -----------------------------------------

(defn- register-machine-event!
  "THE single home for registering a machine as an event handler. It
  stamps the `:rf/machine?` / `:rf/machine` registration metadata
  so the `:where :machine-data` post-commit walker resolves the
  `[:schemas :data]` schema through `(machine-meta id)` (without the stamp the
  schema validates nothing).

  `reg-machine*` (both arities) routes through here. The bare
  `(reg-event id meta (make-machine-handler spec))` direct path does
  not stamp the meta and so would leave a `[:schemas :data]` schema inert, so
  `make-machine-handler` fails loud when handed a `[:schemas :data]`-bearing
  spec outside this home (see its guard) — a schema-bearing machine MUST flow
  through the home.

  `opts` is an optional registration-metadata map. Its `:schema`
  key (when present) is the `:where :event` boundary validator for the
  dispatched OUTER event vector — the machine + event-vector-schema shape.
  Other opts keys ride onto the metadata verbatim. The framework-owned
  `:rf/machine?` / `:rf/machine` keys are stamped here and MUST NOT appear in
  `opts`.

  The home runs the `[:schemas :data]` validation-stamp plus the EP-0025
  projection-relative-classification shape check (rf2-h3d8tf). The
  `[:schemas :data]` schema is validation-only — its per-slot props do not
  classify durable `:data` for egress; the machine's top-level `:sensitive` /
  `:large` projection-relative declarations are the classification surface
  (lowered per actor instance at spawn / first-boot)."
  [machine-id machine opts]
  ;; The MIDDLE `opts` slot must be a map BEFORE
  ;; any `contains?`/`assoc` runs against it. The 2-arity passes `nil` (the
  ;; no-opts path) which normalises to `{}` below; a 3-arity caller that hands
  ;; a non-nil, non-map opts would
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
   ;; EP-0025 §subsystems (rf2-h3d8tf): fail LOUD at the registration
   ;; boundary on a malformed projection-relative `:sensitive` / `:large`
   ;; `:data`-classification declaration (a non-vector axis, a non-path
   ;; entry). The declaration travels with the machine def and is lowered
   ;; per actor instance at spawn / first-boot — so a shape fault must be
   ;; caught at definition, like every other registration-shape fault.
   (classification/validate-machine-classification! machine-id machine)
   ;; Install a per-machine region-machine cache before
   ;; the machine value is threaded through the handler closure and
   ;; published to the registrar. Re-registration replaces the machine
   ;; map and its attached cache atom, so no separate invalidation step
   ;; is needed.
   (let [machine    (parallel/install-region-cache machine)
        ;; The home is the legitimate `make-machine-handler` site for a
        ;; `[:schemas :data]`-bearing spec — bind the flag so the fail-loud
        ;; guard passes (the guard exists to catch the bare direct path, not us).
        handler-fn (binding [*in-registration-home?* true]
                     (make-machine-handler machine))
        ;; Stamp the framework-owned discriminator keys LAST so they win over
        ;; any (rejected-above, but defensive) opts collision.
        meta       (assoc opts
                          :rf/machine? true
                          :rf/machine  machine)]
    (events/reg-event machine-id meta handler-fn)
    ;; The `[:schemas :data]` schema VALIDATES `:data` (via the
    ;; `:where :machine-data` post-commit walker, resolved through the
    ;; `:rf/machine` meta stamped above); its per-slot props do not classify the
    ;; machine's durable `:data` for trace / SSR egress — frame-declared
    ;; `:sensitive` / `:large {:app-db …}` paths are the sole app-db mechanism.
    ;; The dev-only consumer-attachment
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

;; ---- reg-machine* — plain-fn surface --------------------------------------

(defn reg-machine*
  "Plain-fn surface beneath the `reg-machine` macro. Registers a machine
  as an event handler under `machine-id`. Equivalent to
  `(reg-event machine-id (make-machine-handler machine))`.

  Per Spec 005 §reg-machine vs reg-machine*: the macro `reg-machine`
  walks the literal spec form at expansion time and co-locates per-element
  source onto each `:guards` / `:actions` entry plus a reference-site
  `:source-coords` onto each `:states`-tree map node. This fn assumes no such
  walking — it accepts whatever spec
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

  ## Grammar — the metadata-map is the MIDDLE slot

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
  drives a spawned actor's cascade through. Equivalent in
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
        ;; This materialisation seam stamps the `:rf/machine?` /
        ;; `:rf/machine` meta below, so it is a legitimate `make-machine-handler`
        ;; home for a `[:schemas :data]`-bearing spec — bind the flag so the
        ;; fail-loud guard passes.
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

  Resolution is purely runtime-db-derived: read the actor's live
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

  Machine snapshots are durable runtime-db state, so
  the live snapshot is read off the frame's runtime-db partition.

  Schema-egress classification is frame-declared, not per-instance, so this
  seam has no per-instance schema marks to rehydrate; it materialises the
  handler-meta from the restored snapshot's spec only."
  [event frame-id]
  (let [actor-id   (first event)
        ;; `frame-id` is the cascade-threaded envelope frame the
        ;; router's no-handler diagnostics pass in; read its runtime-db
        ;; directly, no `:rf/default` repair. (A nil frame-id yields nil
        ;; runtime-db → the genuine `:no-such-handler`, never a read against
        ;; a synthesised default.)
        runtime-db (frame/frame-runtime-db-value frame-id)
        snapshot   (when runtime-db (get-in runtime-db (paths/snapshot-path actor-id)))
        spec       (when snapshot (resolver/spec-from-snapshot snapshot))]
    (when spec
      (handler-meta-for spec))))
