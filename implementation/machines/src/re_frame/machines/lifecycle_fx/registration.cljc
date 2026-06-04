(ns re-frame.machines.lifecycle-fx.registration
  "Registration boundary: handler factory + `reg-machine*`.

  `make-machine-handler` is the event-fx handler factory beneath the
  `reg-machine` macro; `reg-machine*` is the plain-fn surface used by
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
  (:require [re-frame.events :as events]
            [re-frame.frame :as frame]
            [re-frame.machines.lifecycle-fx.finalize :as finalize]
            [re-frame.machines.lifecycle-fx.join :as join]
            [re-frame.machines.lifecycle-fx.resolver :as resolver]
            [re-frame.machines.lifecycle-fx.validation :as validation]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.paths :as paths]
            [re-frame.machines.result :as result
             #?@(:cljs [:include-macros true])]
            [re-frame.machines.transition :as transition]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

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
  `::result/info` map. Returns `{}` so the handler short-circuits."
  [machine-id event frame-id info reason]
  (let [ex         (:exception info)
        ex-msg     #?(:clj  (when ex (.getMessage ^Throwable ex))
                      :cljs (when ex (.-message ex)))
        ex-data    (when ex (ex-data ex))
        action-ref (:action-ref info)]
    (trace/emit-error! :rf.error/machine-action-exception
                       {:machine-id        machine-id
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
    {}))

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
  path / parallel-region map) resolves through the machine's `:states`
  / `:regions` definition. For parallel machines every region's path
  must resolve; one region's failure invalidates the whole snapshot
  (the runtime can't safely run a parallel transition with a mismatched
  region — better to reset and re-enter)."
  [machine state]
  (cond
    ;; Parallel-region machine: every region key must be a declared
    ;; region AND every region's state-path must resolve through that
    ;; region's body.
    (parallel/parallel? machine)
    (and (map? state)
         (every? (fn [[region-name region-state]]
                   (let [region-body (get-in machine [:regions region-name])]
                     (and region-body
                          (some? (transition/node-at
                                   region-body
                                   (transition/state-path region-state))))))
                 state))

    ;; Flat / compound machine: the state-path must resolve to a node
    ;; in `:states`. `state-path` throws on a malformed shape; the
    ;; try/catch surfaces that as "doesn't resolve" so the same reset
    ;; path covers both shape-error AND missing-state cases.
    :else
    (try
      (some? (transition/node-at machine (transition/state-path state)))
      (catch #?(:clj Throwable :cljs :default) _ false))))

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
  `[:rf/runtime :machines :snapshots <machine-id>]`, decide `needs-bootstrap?`, route the
  inner event. Returns a `ctx` map the remaining three steps read.

  Per rf2-0z73: detect 'first event for this machine' so the initial
  state's `:entry` actions fire as part of bringing the machine to life.
  Two flavours:
    - Singleton path: `(get-in db path)` is `nil` — the snapshot is
      being lazily synthesised right now.
    - Spawn path: `spawn-fx` pre-seeded the snapshot at
      `[:rf/runtime :machines :snapshots <spawned-id>]` and stamped
      `:rf/bootstrap-pending? true` so the actor's first dispatch sees
      the marker and runs the cascade before processing the event.

  Per rf2-fasdp: when an existing snapshot is found, run the Spec 005
  §Snapshot shape stability invariants 3 & 4 reconciler before
  threading it onward — a hot-reload that dropped a state, or a
  `:rf/snapshot-version` bump, replaces the snapshot with a fresh
  initial-state derivative (with `:rf/bootstrap-pending? true` so
  `:entry` fires this same handler call) and emits the named
  `:rf.error/machine-state-not-in-definition` or
  `:rf.error/machine-snapshot-version-mismatch` event."
  [db frame event machine base-initial]
  (let [machine-id    (first event)
        frame-id      (or frame :rf/default)
        platform      (or (:platform (frame/frame-meta frame-id)) :client)
        machine       (assoc machine
                             :rf/frame     frame-id
                             :rf/platform  platform
                             :rf/parent-id machine-id)
        path          (paths/snapshot-path machine-id)
        existing-snap (get-in db path)
        snapshot      (cond
                        (nil? existing-snap)
                        (assoc @base-initial :rf/bootstrap-pending? true)

                        :else
                        (reconcile-snapshot machine machine-id frame-id
                                            existing-snap base-initial))]
    {:db               db
     :machine-id       machine-id
     :frame-id         frame-id
     :machine          machine
     :path             path
     :snapshot         snapshot
     ;; `:existing-snap?` records whether the handler found a snapshot
     ;; ALREADY in app-db at entry. It distinguishes the two FRESH
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

    - `:spawned`  — the handler found a snapshot ALREADY in app-db (the
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
  Restoration paths (SSR / `restore-epoch` / `reset-frame-db`) install a
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
    (let [{:keys [machine machine-id frame-id db path snapshot inner-event]} ctx
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
          finished? (or (and (not (parallel/parallel? machine))
                             (transition/final-on-leaf? machine (:state next-snapshot)))
                        (finalize/all-regions-final? machine (:state next-snapshot)))
          new-db    (assoc-in db path next-snapshot)]
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
                      :machine-id machine-id
                      :event      inner-event
                      :before     snapshot
                      :after      next-snapshot
                      :microsteps (result/microsteps step-result)
                      :cascade    cascade}))
      (when (not= snapshot next-snapshot)
        (trace/emit! :rf.machine :rf.machine/snapshot-updated
                     {:machine-id machine-id
                      :path       path
                      :before     snapshot
                      :after      next-snapshot
                      :frame      frame-id}))
      (if finished?
        (finalize/finalize-machine machine machine-id frame-id
                                   new-db next-snapshot inner-event merged-fx)
        {:db new-db
         :fx merged-fx}))))

(defn make-machine-handler
  "Returns a function suitable for registration with `reg-event-fx`.

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
  (validation/validate-machine! machine)
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
  ;; Pass `:bootstrap-pending? false` — the singleton path stamps the
  ;; marker lazily inside `prepare-machine-ctx` (when `existing-snap` is
  ;; nil); only the spawn path needs it stamped here.
  (let [base-initial (delay (parallel/build-initial-snapshot
                              machine {:bootstrap-pending? false}))]
    (fn [{:keys [db frame] :as _cofx} event]
      ;; Per Spec 009 §:op-type vocabulary: `:rf.machine/event-received`
      ;; fires at the top of the handler so consumers see the inbound
      ;; event before any state derivation.
      (trace/emit! :rf.machine :rf.machine/event-received
                   {:machine-id (first event)
                    :event      event
                    :frame      (or frame :rf/default)})
      (let [ctx         (prepare-machine-ctx db frame event machine base-initial)
            intercepted (join/intercept-spawn-all-event
                          (:machine ctx) db (:path ctx) (:snapshot ctx)
                          (:machine-id ctx) (:inner-event ctx))]
        (if intercepted
          intercepted
          (let [boot-result (maybe-boot ctx)]
            (if (result/fail? boot-result)
              (trace-action-failure! (:machine-id ctx) [transition/start-marker]
                                     (:frame-id ctx)
                                     (result/info boot-result)
                                     "Machine initial-entry action threw.")
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
                ;; just as it would on the lazy path.
                (if (= transition/start-marker (first (:inner-event ctx)))
                  (let [m (:machine ctx)]
                    (if (or (and (not (parallel/parallel? m))
                                 (transition/final-on-leaf? m (:state post-boot-snap)))
                            (finalize/all-regions-final? m (:state post-boot-snap)))
                      (finalize/finalize-machine m (:machine-id ctx) (:frame-id ctx)
                                                 (assoc-in db (:path ctx) post-boot-snap)
                                                 post-boot-snap
                                                 (:inner-event ctx)
                                                 (vec boot-fx))
                      {:db (assoc-in db (:path ctx) post-boot-snap)
                       :fx (vec boot-fx)}))
                  (let [step-result (run-step ctx post-boot-snap)]
                    (if (result/fail? step-result)
                      (trace-action-failure! (:machine-id ctx) (:inner-event ctx)
                                             (:frame-id ctx)
                                             (result/info step-result)
                                             "Machine action threw.")
                      ;; Per rf2-n9f4z: pass the full `boot-result` (fx +
                      ;; bootstrap-entry cascade), not just `boot-fx`, so a
                      ;; same-call bootstrap's entry cascade prepends the
                      ;; event-driven cascade on the `:rf.machine/transition`
                      ;; trace.
                      (commit-or-finalize ctx step-result boot-result))))))))))))

;; ---- reg-machine* — plain-fn surface (rf2-8bp3) ---------------------------

(defn reg-machine*
  "Plain-fn surface beneath the `reg-machine` macro. Registers a machine
  as an event handler under `machine-id`. Equivalent to
  `(reg-event-fx machine-id (make-machine-handler machine))`.

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
  metadata via the `reg-event-fx` defn's `merge-coords` call."
  [machine-id machine]
  ;; Per rf2-s83iu: install a per-machine region-machine cache before
  ;; the machine value is threaded through the handler closure and
  ;; published to the registrar. Re-registration replaces the machine
  ;; map and its attached cache atom, so no separate invalidation step
  ;; is needed.
  (let [machine    (parallel/install-region-cache machine)
        handler-fn (make-machine-handler machine)]
    (events/reg-event-fx machine-id
                         {:rf/machine? true
                          :rf/machine  machine}
                         handler-fn)
    (trace/emit! :rf.machine.lifecycle/created :rf.machine.lifecycle/created
                 {:machine-id machine-id
                  :initial    (:initial machine)})
    machine-id))

(defn handler-meta-for
  "Build the registrar-shaped handler-meta map for a machine `spec`
  WITHOUT registering it — the surface the lazy-actor-handler resolver
  (rf2-a2sn1) drives a spawned actor's cascade through. Equivalent in
  shape to what `reg-machine*` installs under the machine-id, but
  materialised on demand from the actor's (revertible) app-db snapshot
  rather than held in a per-instance registrar entry.

  The region-machine cache is installed (mirroring `reg-machine*`) so
  parallel-region transitions resolve, then `make-machine-handler` builds
  the handler-fn and `events/event-handler-meta` wraps it into the same
  `{:rf/machine? true :rf/machine <spec> :event/kind :fx :handler-fn …
  :interceptors […]}` shape the registrar holds for a registered
  machine. `process-event*` runs the returned meta unchanged — the
  handler reads the actor's own snapshot keyed on the dispatched event's
  first element, so a single materialised handler serves every instance
  of the type."
  [spec]
  (let [machine    (parallel/install-region-cache spec)
        handler-fn (make-machine-handler machine)]
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

  Resolution is purely app-db-derived (rf2-a2sn1): read the actor's live
  snapshot from the frame's app-db, resolve its TYPE spec via
  `resolver/spec-from-snapshot`, and materialise the handler-meta from
  the spec. No live snapshot (or no resolvable type) → nil: the actor
  isn't alive in this frame value, which is the correct
  `:no-such-handler` — and exactly the property `restore-epoch` reverts.

  The materialised handler-meta is shape-identical to a registered
  machine's, so the rest of `process-event*` is unchanged — it runs the
  handler against the actor's own snapshot (the machine handler reads
  `[:rf/runtime :machines :snapshots <actor-id>]` keyed on the dispatched
  event's first element). The handler-fn is built fresh per unresolved
  dispatch; this is the COLD path (a spawned actor whose per-instance
  handler is — by design — never registered), so the allocation is
  bounded by actual spawned-actor traffic, not by every dispatch."
  [event frame-id]
  (let [actor-id (first event)
        db       (frame/frame-app-db-value (or frame-id :rf/default))
        snapshot (when db (get-in db (paths/snapshot-path actor-id)))
        spec     (when snapshot (resolver/spec-from-snapshot snapshot))]
    (when spec
      (handler-meta-for spec))))
