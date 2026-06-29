(ns day8.re-frame2-xray.panels.machine-inspector-helpers
  "Pure-data helpers for Xray's Machine Inspector panel
  (Phase 5+, rf2-r9f9u, parent rf2-5aw5v).

  ## Why a separate `.cljc` ns

  Same dual-target pattern every other panel uses (subscriptions,
  routes, ...). The panel view in
  `machine_inspector.cljs` builds the hiccup; the *logic* — projecting
  the registered-machine set + the live snapshots + the trace-buffer's
  `:rf.machine/transition` slice into per-machine row + chart-prop
  data — is pure data → data and runs under the JVM unit-test target
  (`clojure -M:test`).

  ## What this panel surfaces (per tools/xray/spec/003-Machine-Inspector.md)

  Per spec/003-Machine-Inspector.md the panel's minimum surface is:

    1. **Machine picker** — a dropdown over `(rf/machines)` (Spec 005
       §Querying machines). Switching the selection re-binds the chart
       + transition-history ribbon to the new machine. Each picker
       option shows machine-id + current-state.

    2. **MachineChart placeholder** — a `[viz/MachineChart {...}]`
       embed per `tools/machines-viz/spec/API.md`. **At v1 the
       `tools/machines-viz/` implementation does not exist** (only the
       spec scaffold landed via rf2-x50eu). The panel mounts a
       placeholder that renders the prop summary as text — the
       contract is what matters here. When the impl lands the
       placeholder swaps for the real component without touching the
       panel chrome.

    3. **Active state** — read off `[:rf/machine <id>]` (Spec 005
       §Subscribing to machines via the :rf/machine sub), surfaced on
       the picker row + in the placeholder's prop summary.

    4. **Transition history ribbon** — filter the Xray trace buffer
       to `:rf.machine/transition` events for the selected machine and
       render them as a scrubbable horizontal list. Click a row →
       `:rf.xray/select-dispatch-id` (pivots focus, parity with every
       other Xray cross-panel jump).

  ## What v1 does NOT include

    - No source-coord jumps (the cross-panel jump API hasn't
      stabilised yet — same as the routes panel's v1 deferral).
    - No `:spawn-all` viz / `:after` countdown rings — those live in
      `tools/machines-viz/` per Spec 003 §Embedding posture. This panel
      embeds the component; the rendering is the component's job. The
      placeholder simply surfaces the props the real component would
      consume.
    - No share affordance (rf2-nugvv removed it — the Machine panel was
      the sole UI entry point to the Xray share modal).

  ## Inputs to the projection

  The composite sub `:rf.xray/machine-inspector-data` feeds three
  sources into `project-data`:

    1. **`machines`** — vector of registered machine-ids
       (`(rf/machines)`). Empty when the machines artefact is not on
       the classpath (Spec 005 §Querying machines: returns `[]`).

    2. **`snapshots`** — `{machine-id snapshot-or-nil}` map; each
       value is the result of `(rf/machine-meta-via-sub machine-id)`
       (i.e. `(get-in runtime-db [:rf.runtime/machines :snapshots <id>])`
       against the target frame's RUNTIME-DB partition — EP-0001 rf2-vzld77
       moved machine snapshots out of app-db into runtime-db). nil when the
       machine is registered but not yet initialised.

    3. **`trace-buffer`** — Xray's trace ring buffer. The helper
       filters it to `:rf.machine/transition` events for the selected
       machine.

  ## Helper output

  `project-data` returns a map shaped:

      {:machines      [<machine-row> ...]    ;; one per registered id
       :total         <int>
       :selected-id   <keyword-or-nil>
       :selected      <machine-row-or-nil>   ;; the picker focus
       :chart-props   {<MachineChart props>} ;; per machines-viz API
       :transitions   [<transition-row> ...] ;; ribbon entries
       :empty-kind    <:no-machines / nil>}

  Each `machine-row` is `{:machine-id :state :data :registered?}`. The
  `:state` slot is the snapshot's `:state` keyword (nil for
  uninitialised machines).

  Each `transition-row` is `{:id :time :from :to :event :dispatch-id
  :microstep?}`. Microstep events (`:rf.machine.microstep/transition`)
  are folded in at v1 with the `:microstep?` flag so the view can
  indent them.

  ## What this doesn't do

  Pure data. No subscription, no atom, no `js/` interop — the same
  fn runs under CLJ and CLJS. The CLJS-only surfaces (`rf/machines`
  on a populated registrar, `rf/app-db-value` on a frame) are read
  by the composite sub in `registry.cljs`; the result is handed to
  this ns as a plain map."
  (:require [clojure.string :as str]
            [day8.re-frame2-xray.panels.shared.focus-resolver :as focus]))

;; ---- canonical operation taxonomy ---------------------------------------

(def transition-operations
  "Trace operations the transition-history ribbon surfaces. Per
  spec/005-StateMachines.md + spec/009-Instrumentation.md the runtime
  emits `:rf.machine/transition` for outer transitions and
  `:rf.machine.microstep/transition` for `:always`-driven
  microsteps. The set is the v1 vocabulary; future additions (timer
  fired, invoke-all join resolved) ride follow-on beads."
  #{:rf.machine/transition
    :rf.machine.microstep/transition})

(defn transition-event?
  "True iff `ev` is one of the transition-history operations. Pure
  predicate. Tolerant of an event without a `:operation` key (returns
  false rather than throwing)."
  [ev]
  (and (map? ev)
       (contains? transition-operations (:operation ev))))

;; ---- machine BIRTH (`:rf.machine/started`) — rf2-eldze ------------------
;;
;; A machine's BIRTH (the `[:rf.machine/start]` kick, or the lazy first-
;; event fold, or a spawn) runs the initial-entry cascade and emits ONE
;; `:rf.machine/started` trace — NOT a `:rf.machine/transition`. The pure
;; start is an entry INTO the initial state, not a from→to transition, so
;; `commit-or-finalize` deliberately suppresses the transition trace
;; (machines · lifecycle_fx · registration.cljc — "a pure start emits no
;; `:rf.machine/transition`; `:rf.machine/started` is the sole birth
;; signal", rf2-gl588 / rf2-coozg). The started trace carries
;; `{:machine-id :state :data :cause}` — `:state` / `:data` are the
;; INITIAL snapshot slots (`(:state booted)` / `(:data booted)`).
;;
;; Before rf2-eldze the focused-event lens only projected
;; `transition-event?` traces, so a focused start epoch produced zero
;; records and the Machine tab rendered the "does not target a state
;; machine" empty state — even though the machine DID just come up into
;; its initial state. This is the bug rf2-eldze fixes: surface the start
;; as a first-class focused-event record (no from-state; to-state = the
;; resulting initial state) so the topology renders with the initial
;; state highlighted.

(def started-operation
  "The machine-birth trace op (Spec 009 §:op-type vocabulary; rf2-it4vt /
  rf2-gl588). `maybe-boot` emits exactly one per successful initial-entry
  cascade in BOTH creation paths (eager start-kick + lazy first-event)."
  :rf.machine/started)

(def start-marker-event
  "The reserved synthetic creation-marker event vector, surfaced on a
  birth record's `:event` slot so the section header / lens read it as
  the machine's birth (xstate parity with `createActor(m).start()`). Per
  Spec 005 §reserved `:rf/*` lifecycle (the `[:rf.machine/start]` kick).
  Held as a literal here so the pure-data helper does not reach into the
  runtime `machines` artefact (tools must not `:require` implementation)."
  [:rf.machine/start])

(defn started-event?
  "True iff `ev` is the machine-birth (`:rf.machine/started`) trace.
  Pure predicate; tolerant of a missing `:operation` key."
  [ev]
  (and (map? ev)
       (= started-operation (:operation ev))))

;; ---- no-op / guard-blocked machine event (`:rf.machine.event/unhandled-no-op`) — rf2-skmc7 -----
;;
;; A machine event that matched no transition — an UNHANDLED user event, OR
;; a transition whose GUARD failed (`match-on-clause` returns the first
;; candidate WHOSE GUARD PASSES, so a guard-blocked clause is indistinguishable
;; from no clause at the resolution layer) — produces NO `:rf.machine/transition`
;; (Spec 009: "A no-op macrostep emits NO `:rf.machine/transition` … the benign
;; `:rf.machine.event/unhandled-no-op` is the SOLE signal for an unhandled /
;; guard-blocked event"). The machine stays in its current state as a no-op.
;;
;; The event STILL targeted a registered machine: the substrate dispatched the
;; event to the machine's handler and ran its guards. So per spec/003
;; §Empty state ("Unhandled-event no-op is NOT this empty state") the Machine
;; tab MUST render the machine's topology with the CURRENT state highlighted —
;; NOT the "does not target a state machine" placeholder.
;;
;; This is the SAME underlying gap rf2-eldze fixed for the machine-START case:
;; the focused-event lens keyed off a from→to TRANSITION, so any event that
;; targeted a machine but produced no transition (start, guard-block, unhandled
;; no-op) was wrongly classified as "does not target a state machine". eldze
;; folded in `:rf.machine/started`; this folds in
;; `:rf.machine.event/unhandled-no-op`.
;;
;; The no-op trace carries `{:machine-id :event :state :frame}` where `:state`
;; is the machine's CURRENT state (`(:state snapshot)` at resolution time —
;; unchanged by the no-op). We project it into the same record shape as a
;; transition, with `:from-state` == `:to-state` == the current state (a no-op
;; is a stationary self-loop) and a `:no-op? true` flag so the view renders a
;; `[NO-OP]` marker + a single highlighted current state rather than a
;; from→to edge.

(def no-op-operation
  "The benign no-op / guard-blocked / unhandled trace op (Spec 009
  §`:op-type` vocabulary). Emitted from `machines/transition.cljc` (flat /
  compound) and `machines/parallel.cljc` (the parallel-region aggregate)
  exactly once when an event matched no transition at any level — including
  a clause whose guard failed. Op-type `:rf.machine` (machine-activity
  family, NOT an error / warning)."
  :rf.machine.event/unhandled-no-op)

(defn no-op-event?
  "True iff `ev` is the benign machine no-op (`:rf.machine.event/unhandled-no-op`)
  trace. Pure predicate; tolerant of a missing `:operation` key."
  [ev]
  (and (map? ev)
       (= no-op-operation (:operation ev))))

;; ---- machine-id resolution ----------------------------------------------

(defn machine-id-of
  "Resolve the addressed machine/actor id off a trace event. Per rf2-ws5thu
  the live-actor lifecycle rows (`:rf.machine/transition`,
  `:rf.machine/snapshot-updated`, `:rf.machine/done`, the timer rows,
  `:rf.machine/system-id-*`) carry the live INSTANCE address under
  `:tags :actor-id`; the older diagnostic rows (`:rf.machine/started`,
  `:rf.machine/guard-evaluated`, `:rf.machine/action-ran`,
  `:rf.machine/event-received`) and the registrar `:created` row carry it
  under `:tags :machine-id` (or `:tags :handler-id`, the same value). Prefer
  `:actor-id`, then fall back to `:machine-id` / `:handler-id`. Returns nil
  when no slot is present — the caller filters those out before sorting."
  [ev]
  (or (get-in ev [:tags :actor-id])
      (get-in ev [:tags :machine-id])
      (get-in ev [:tags :handler-id])))

;; ---- projection ---------------------------------------------------------

(defn project-machine-row
  "One row per registered machine. `machine-id` is the keyword the
  machine is registered under (per Spec 005); `snapshot` is the
  current `{:state :data}` map (or nil for uninitialised machines).
  `definition` is the registered machine spec (`(rf/machine-meta
  machine-id)`); nil when the spec is not yet introspectable.

  Per the panel's minimum-viable contract the row carries:

    :machine-id   — the registered id (keyword)
    :state        — the snapshot's :state keyword (nil if uninit)
    :data         — the snapshot's :data map (nil if uninit)
    :definition   — the registered machine spec (nil if not available)
    :registered?  — always true (the row exists because the id is
                    registered); included so the view can render the
                    fact distinctly from `:state` (an uninitialised
                    registered machine still appears in the picker)."
  ([machine-id snapshot] (project-machine-row machine-id snapshot nil))
  ([machine-id snapshot definition]
   {:machine-id  machine-id
    :state       (when (map? snapshot) (:state snapshot))
    :data        (when (map? snapshot) (:data snapshot))
    :definition  definition
    :registered? true}))

(defn project-machine-rows
  "Project the registered-machine set + the live snapshots map (+
  the per-id definition map, when available) into one row per id.
  Sorted alphabetically by `(name machine-id)` for deterministic
  test output. Pure fn — JVM-runnable."
  ([machines snapshots] (project-machine-rows machines snapshots nil))
  ([machines snapshots definitions]
   (->> (or machines [])
        (map (fn [id]
               (project-machine-row id
                                    (get snapshots id)
                                    (get definitions id))))
        (sort-by (fn [{:keys [machine-id]}]
                   (str machine-id)))
        vec)))

(defn pick-selected
  "Resolve the row for the selected machine-id. When `selected-id` is
  nil the first row is the default focus (so the panel renders
  *something* on first open). Returns nil when there are no rows."
  [rows selected-id]
  (or (some #(when (= (:machine-id %) selected-id) %) rows)
      (first rows)))

(defn chart-props
  "Build the MachineChart prop map for the selected machine. Per
  `tools/machines-viz/spec/API.md` §Props the chart accepts:

      :machine-id  (required)
      :frame-id    (required)
      :on-state-click
      :on-edge-click   ;; rf2-qo5xy retired :on-transition-click — a
                       ;; clickable event-node label fires this
      :read-only?
      :show-microsteps? / :show-after-rings? / :show-invoke-all?
      :auto-pan?
      :current-state-override

  At v1 the panel only fills the required props + the live-snapshot
  override (so the placeholder can render the active state without
  reaching back through the framework). Callback wiring (jump to
  source, scrub transition history) lands when machines-viz ships.

  Returns nil when there is no selected machine — the placeholder
  renders the empty-chart state in that case."
  [selected-row frame-id]
  (when selected-row
    (let [{:keys [machine-id state data definition]} selected-row]
      (cond-> {:machine-id machine-id
               :frame-id   frame-id}
        (some? state)
        (assoc :current-state-override
               (cond-> {:state state}
                 (some? data) (assoc :data data)))
        (some? definition)
        (assoc :definition definition)))))

;; ---- transition history --------------------------------------------------

(defn- transition-row
  "One transition-history row from a trace event. Pure data."
  [ev]
  (let [tags (get ev :tags {})]
    {:id          (:id ev)
     :time        (:time ev)
     :operation   (:operation ev)
     :from        (or (:from tags) (:from-state tags))
     :to          (or (:to tags)   (:to-state   tags))
     :event       (or (:event tags) (:event-v tags))
     :dispatch-id (:rf.trace/dispatch-id tags)
     :microstep?  (= :rf.machine.microstep/transition (:operation ev))}))

(defn project-transitions
  "Filter `trace-buffer` to transition events for `machine-id`. Pure
  fn — JVM-runnable so the JVM test target can drive it without a
  CLJS runtime.

  Per spec/003-Machine-Inspector.md §Transition history ribbon the
  ribbon is *newest-first* (the most recent transition sits at the
  far right of the strip; the view renders the projection in display
  order). v1 caps the rendered ribbon at 200 entries — the helper
  returns the full filtered vector and the view applies the cap so
  tests can assert the unbounded shape.

  Returns `[]` when `machine-id` is nil (nothing focused → nothing
  to project)."
  [trace-buffer machine-id]
  (if (nil? machine-id)
    []
    (->> (or trace-buffer [])
         (filter (fn [ev]
                   (and (transition-event? ev)
                        (= machine-id (machine-id-of ev)))))
         (map transition-row)
         ;; Newest first — the ribbon scrolls right-to-left in the
         ;; view, but the data side leads with the most recent so
         ;; the head element is the freshest transition.
         (sort-by (fn [{:keys [id time]}]
                    ;; Prefer :id when present (stable, monotonic per
                    ;; Spec 009); fall back to :time. Negate for
                    ;; newest-first.
                    (- (or id time 0))))
         vec)))

(defn cap-transitions
  "Apply the v1 200-entry cap. Pure fn. The view calls this before
  rendering; the helper returns the unbounded projection so tests can
  exercise it."
  ([rows] (cap-transitions rows 200))
  ([rows n]
   (if (<= (count rows) n)
     (vec rows)
     (vec (take n rows)))))

;; ---- top-level composite -------------------------------------------------

(defn project-data
  "Fold the registered-machine set + the snapshots map + the trace
  buffer + the user's selection into the data shape the panel view
  consumes.

  Inputs:

    `machines`     — vector / seq of registered machine-ids
                     (`(rf/machines)`). nil-safe.
    `snapshots`    — `{machine-id snapshot-or-nil}`. nil-safe.
    `trace-buffer` — Xray's trace ring buffer. nil-safe.
    `selected-id`  — the user's picker focus (keyword) or nil. nil
                     defaults to the first row.
    `frame-id`     — the frame the chart should resolve the machine
                     against. Passed through to `chart-props`.

  Returns:

      {:machines     [<machine-row> ...]
       :total        <int>
       :selected-id  <id-or-nil>          ;; effective selection
       :selected     <row-or-nil>
       :chart-props  <props-or-nil>
       :transitions  [<transition-row> ...]
       :empty-kind   <:no-machines / nil>}"
  ([machines snapshots trace-buffer selected-id frame-id]
   (project-data machines snapshots nil trace-buffer selected-id frame-id))
  ([machines snapshots definitions trace-buffer selected-id frame-id]
   (let [rows         (project-machine-rows machines snapshots definitions)
         total        (count rows)
         selected     (pick-selected rows selected-id)
         effective-id (:machine-id selected)
         props        (chart-props selected frame-id)
         transitions  (project-transitions trace-buffer effective-id)
         empty-kind   (when (zero? total) :no-machines)]
     {:machines    rows
      :total       total
      :selected-id effective-id
      :selected    selected
      :chart-props props
      :transitions transitions
      :empty-kind  empty-kind})))

;; ---- formatting helpers (consumed by the view) --------------------------

(defn format-machine-id
  "Pretty-print a machine-id for display. Keywords keep their `:`
  prefix; everything else falls back to `str`."
  [id]
  (cond
    (nil? id)         ""
    (keyword? id)     (str id)
    :else             (str/trim (str id))))

(defn format-state
  "Render a snapshot `:state` for display. nil → `(uninit)` so the
  picker / placeholder render *something* rather than a blank for
  registered-but-uninitialised machines."
  [state]
  (cond
    (nil? state)      "(uninit)"
    (keyword? state)  (str state)
    :else             (str/trim (str state))))

(defn format-event
  "Compact event-vector formatter for the transition row. Falls back
  to `str` if `pr-str` throws (mirrors the format-edn idiom the
  retired event_detail.cljs used; relocated here so the test suite
  can assert against the formatted output without booting the view)."
  [event]
  (if (nil? event)
    ""
    (try
      (pr-str event)
      (catch #?(:clj Throwable :cljs :default) _
        (str event)))))

;; ---- focused-event lens (rf2-a9cke) -------------------------------------
;;
;; Per Mike's canonical Machines design (rf2-si9o5): when the user focuses
;; an L2 event, the Machines panel becomes a lens on THAT event's machine
;; activity:
;;
;;   - If the event triggered no machine transitions → render nothing
;;     (silent-by-default per rf2-g3ghh).
;;   - If the event triggered ≥1 machine transitions → render one section
;;     per machine, each carrying the topology + current state + new
;;     state + the transition-edge + any guards + any actions that ran.
;;
;; The helper folds the focused epoch record's `:trace-events` (per
;; spec/Spec-Schemas §`:rf/epoch-record`) into a vector of per-machine
;; transition records the view consumes. Each record carries enough data
;; for the view to highlight the chart + render the guards/actions lists
;; without further trace-walking.
;;
;; ## Trace-shape coverage
;;
;; Today's substrate (implementation/machines/) emits:
;;
;;   - `:rf.machine/transition` — outer transition; tags carry
;;     `:before` + `:after` snapshots + `:event` + `:machine-id`.
;;   - `:rf.machine.microstep/transition` — `:always`-driven microstep;
;;     same tag shape (some tests use `:from`/`:to` tag fallbacks).
;;
;; The substrate emits `:rf.machine/guard-evaluated` /
;; `:rf.machine/action-ran` per rf2-2nwfd (see spec/009 §Trace event
;; vocabulary). Before rf2-ko8jb (#1601) those emits carried no
;; `:frame` tag and were silently dropped by epoch-capture; post-#1601
;; they reach the focused epoch's `:trace-events` and flow through
;; this projection.
;;
;; The `:guard-id` / `:action-id` slot is the user-declared ref AS-IS
;; (per spec/Spec-Schemas: "keyword OR inline fn"). When a transition
;; declares `:guard :user-has-credentials?` the slot is a keyword;
;; when it inlines `:guard (fn [{data :data ev :event}] ...)` OR the state-node's
;; `:entry` slot points at a raw fn, the slot is the fn itself. The
;; view renders the id via `(name ...)` to build a `data-testid`
;; suffix — and `cljs.core/name` blows up on fn values (`Doesn't
;; support name: function ...`). `ref-display-id` coerces the
;; ref into a renderable keyword (named via `:name` meta when
;; available, `:rf.machine/anonymous-fn` otherwise) so the view
;; contract stays simple. (rf2-ujra6.)
;;
;; Per spec/005-StateMachines.md the guard / action functions are
;; resolved off the transition object on the machine definition; we
;; surface their refs so the view can label them as `:guards` /
;; `:actions` lists even when no per-step trace fired.

(def guard-operations
  "Trace operations the focused-event lens treats as guard evaluations.
  Today's substrate doesn't emit any of these; the set is the
  forward-compatible vocabulary so when the runtime gains the trace
  shape the lens lights up without code changes (rf2-a9cke
  divergence-allowance note)."
  #{:rf.machine/guard-evaluated
    :rf.machine.guard/evaluated})

(def action-operations
  "Trace operations the focused-event lens treats as action runs.
  Forward-compatible vocabulary — same posture as `guard-operations`."
  #{:rf.machine/action-ran
    :rf.machine.action/ran
    :rf.machine/action-executed})

(defn guard-event?
  "True iff `ev` is one of the guard-evaluation operations."
  [ev]
  (and (map? ev)
       (contains? guard-operations (:operation ev))))

(defn action-event?
  "True iff `ev` is one of the action-run operations."
  [ev]
  (and (map? ev)
       (contains? action-operations (:operation ev))))

(defn- transition-record-from-trace
  "Project a single `:rf.machine/transition` trace event into the
  view-consumed record shape. The trace carries `:before` / `:after`
  snapshots (per registration.cljc's commit-or-finalize) so we can read
  the from-state / to-state directly off the snapshot pair. Falls back
  to the legacy `:from`/`:to` tag slots when present (test fixtures).

  The full `:before` / `:after` snapshot maps (`{:state X :data Y}`) are
  carried through on the record so the panel's snapshot drill-in
  surface (rf2-lxvn6, spec/021 §10) can render them via the first-class
  edn-inspector widget. nil when the trace pre-dates the snapshot
  tagging contract (legacy `:from`/`:to`-only fixtures)."
  [ev]
  (let [tags  (get ev :tags {})
        before (:before tags)
        after  (:after  tags)
        from-state (cond
                     (some? before) (:state before)
                     :else          (or (:from tags) (:from-state tags)))
        to-state   (cond
                     (some? after)  (:state after)
                     :else          (or (:to tags) (:to-state tags)))
        event-v    (or (:event tags) (:event-v tags))
        ;; The on-event keyword that fired the transition is the
        ;; head of the event vector. nil-safe so a microstep that
        ;; lacks the event tag still surfaces a stable record.
        on-event   (when (vector? event-v) (first event-v))]
    {:machine-id   (machine-id-of ev)
     :from-state   from-state
     :to-state     to-state
     ;; Full snapshot maps for the drill-in surface (rf2-lxvn6 — spec/021
     ;; §10 widget contract). nil when the trace tags lack the
     ;; commit-or-finalize snapshot pair (legacy fixtures).
     :before       before
     :after        after
     :on-event     on-event
     :event        event-v
     :time         (:time ev)
     :id           (:id ev)
     :dispatch-id  (get-in ev [:tags :rf.trace/dispatch-id])
     :microstep?   (= :rf.machine.microstep/transition (:operation ev))
     ;; Guards/actions filled in by `attach-guards-and-actions` —
     ;; default empty so the record shape is stable.
     :guards       []
     :actions      []}))

(defn- started-record-from-trace
  "Project a `:rf.machine/started` (machine BIRTH) trace into the same
  view-consumed record shape `transition-record-from-trace` produces, so
  the focused-event lens renders a start exactly like a transition —
  except there is NO from-state (a birth is an entry into the initial
  state, not a from→to). rf2-eldze.

  The started trace carries `{:machine-id :state :data :cause}` (Spec 009;
  machines · lifecycle_fx · registration.cljc) where `:state` / `:data`
  are the INITIAL snapshot slots. We map:

    :from-state nil           — no prior state (the birth marker)
    :to-state   (:state tag)  — the resulting INITIAL state (highlighted)
    :before     nil           — the machine did not exist before
    :after      {:state :data} — the initial snapshot (for the drill-in)
    :start?     true          — flags the birth case for the view
    :cause      (:cause tag)  — :explicit / :lazy / :spawned
    :event      [:rf.machine/start] — the synthetic creation marker

  Guards / actions default empty — the initial-entry cascade's actions
  are not traced as `:rf.machine/action-ran` (rf2-n9f4z), so there is
  nothing to attach. The record shape is otherwise identical to a
  transition record so every downstream consumer (the section view, the
  snapshot drill-in, the chart highlight) treats it uniformly."
  [ev]
  (let [tags  (get ev :tags {})
        state (:state tags)
        data  (:data tags)]
    {:machine-id  (machine-id-of ev)
     :from-state  nil
     :to-state    state
     :before      nil
     ;; Synthesize the initial snapshot from the started tags so the
     ;; snapshot drill-in renders the just-born machine's `{:state :data}`.
     :after       {:state state :data data}
     :start?      true
     :cause       (:cause tags)
     :on-event    (first start-marker-event)
     :event       start-marker-event
     :time        (:time ev)
     :id          (:id ev)
     :dispatch-id (get-in ev [:tags :rf.trace/dispatch-id])
     :microstep?  false
     :guards      []
     :actions     []}))

(defn- no-op-record-from-trace
  "Project a `:rf.machine.event/unhandled-no-op` (guard-blocked / unhandled /
  no-op) trace into the same view-consumed record shape the transition / start
  projectors produce, so the focused-event lens renders the topology with the
  CURRENT state highlighted — rather than the 'does not target a state machine'
  empty state. rf2-skmc7.

  The no-op trace carries `{:machine-id :event :state :frame}` (Spec 009;
  machines · transition.cljc / parallel.cljc) where `:state` is the machine's
  CURRENT state at resolution time (unchanged — that's what makes it a no-op).
  We map:

    :from-state (:state tag)  — the current state == to-state (a stationary
                                self-loop; the state did not change)
    :to-state   (:state tag)  — same current state (highlighted on the chart)
    :before     nil           — the no-op trace carries no snapshot pair; the
    :after      nil             drill-in suppresses cleanly (no `:data` change)
    :no-op?     true           — flags the no-op case for the view (the header
                                renders a `[NO-OP]` marker, the lens reads
                                NO TRANSITION, the chart highlights the one
                                current state)
    :event      (:event tag)  — the inbound user event that produced the no-op

  Guards / actions default empty: a guard-blocked transition's guard DOES run,
  but today's substrate does not emit `:rf.machine/guard-evaluated` for the
  declining candidate (the no-op trace is the sole signal), so there is nothing
  to attach. When the substrate gains guard traces on the no-op path a follow-on
  bead surfaces the failing guard in the lens; the record shape stays stable.

  The record shape is otherwise identical to a transition record so every
  downstream consumer (section view, chart highlight, prev/next nav) treats it
  uniformly."
  [ev]
  (let [tags  (get ev :tags {})
        state (:state tags)
        event (:event tags)]
    {:machine-id  (machine-id-of ev)
     :from-state  state
     :to-state    state
     :before      nil
     :after       nil
     :no-op?      true
     :on-event    (when (vector? event) (first event))
     :event       event
     :time        (:time ev)
     :id          (:id ev)
     :dispatch-id (get-in ev [:tags :rf.trace/dispatch-id])
     :microstep?  false
     :guards      []
     :actions     []}))

(defn- ref-display-id
  "Coerce a guard/action ref to a renderable id for the per-transition
  Guards / Actions list.

  Per spec/Spec-Schemas §`:rf.machine/guard-evaluated` and
  §`:rf.machine/action-ran`, the trace's `:guard-id` / `:action-id` slot
  carries the user-declared ref AS-IS — a keyword when the transition
  declared `:guard :foo` / `:action :foo`, or the fn itself when the
  transition inlined `:guard (fn [...] ...)` / `:action (fn [...] ...)`
  or pulled a raw fn from a state-node's `:entry` / `:exit` slot. The
  view renders the id via `(name ...)` to build a stable data-testid
  suffix, which blows up on fn values (`Doesn't support name:
  function ...`).

  Coerce to a renderable form here so the view contract is simple:

    - keyword / string / symbol → return as-is (the view's `name`/`str`
      both work).
    - fn → return either `:rf.machine/<the fn's :name meta>` when the
      fn carries `:name` metadata (named `(defn ...)` or `(fn name
      [...] ...)`) or `:rf.machine/anonymous-fn` for truly anonymous
      inline fns. The keyword form keeps the view's `(name ...)` call
      working AND surfaces the fn's declared name when available, which
      is what a consumer wants to see in the Guards / Actions list."
  [ref]
  (cond
    (nil? ref)                          nil
    (or (keyword? ref) (symbol? ref))   ref
    (string? ref)                       ref
    (fn? ref)
    (if-let [nm (some-> ref meta :name)]
      (keyword "rf.machine" (str nm))
      :rf.machine/anonymous-fn)
    :else                               ref))

(defn- guard-record
  "Project a guard-evaluated trace event into the record the view
  renders inside the per-transition Guards section."
  [ev]
  (let [tags (get ev :tags {})]
    {:guard-id (ref-display-id (or (:guard-id tags) (:guard tags)))
     :input    (or (:input tags) (:args tags))
     :outcome  (cond
                 (contains? tags :outcome) (:outcome tags)
                 (contains? tags :pass?)   (if (:pass? tags) :pass :fail)
                 (contains? tags :passed?) (if (:passed? tags) :pass :fail)
                 :else                     nil)
     :time     (:time ev)}))

(defn- action-record
  "Project an action-ran trace event into the record the view renders
  inside the per-transition Actions section."
  [ev]
  (let [tags (get ev :tags {})]
    {:action-id (ref-display-id (or (:action-id tags) (:action tags)))
     :input     (or (:input tags) (:args tags))
     :outcome   (cond
                  (contains? tags :outcome)   (:outcome tags)
                  (contains? tags :exception) :fail
                  :else                       :ok)
     :time      (:time ev)}))

(defn- attach-guards-and-actions
  "Attach the guard/action records that fired against `transition-record`
  to the record. Walks the cascade-window trace events and matches by
  `:machine-id` + time-window (event :time between transition.start and
  transition.end). Since today's substrate doesn't emit guard/action
  traces this is forward-compatible — when the runtime gains them, the
  per-transition lists populate without code changes.

  The match-window v1 is loose — any guard/action trace for the same
  machine inside the cascade attributes to the only-or-first transition
  for that machine. When the substrate ships explicit
  `:transition-id` / `:decl-path` tags on guard/action traces a
  follow-on bead tightens the attribution."
  [transition-record events]
  (let [mid       (:machine-id transition-record)
        guards    (->> events
                       (filter (fn [ev]
                                 (and (guard-event? ev)
                                      (= mid (machine-id-of ev)))))
                       (mapv guard-record))
        actions   (->> events
                       (filter (fn [ev]
                                 (and (action-event? ev)
                                      (= mid (machine-id-of ev)))))
                       (mapv action-record))]
    (cond-> transition-record
      (seq guards)  (assoc :guards guards)
      (seq actions) (assoc :actions actions))))

;; ---- history restore / record (rf2-mle6e.5) -----------------------------
;;
;; Per Spec 009 §History trace events, a transition that resolves a history
;; pseudo-state emits `:rf.machine.history/restored`; the exit that wrote a
;; history-bearing compound's config emits `:rf.machine.history/recorded`.
;; Both share the cascade's `:rf.trace/dispatch-id` with the macrostep's
;; `:rf.machine/transition` and carry `:machine-id`. The focused-event lens
;; surfaces them on the per-machine transition record so the Machine
;; Inspector renders WHY a re-entry landed where it did — distinguishing a
;; history restore from an ordinary descent (spec/003 §Machine-Inspector).

(def history-restored-operation :rf.machine.history/restored)
(def history-recorded-operation :rf.machine.history/recorded)

(defn- history-restored-record [ev]
  (let [tags (get ev :tags {})]
    {:compound-path   (:compound-path tags)
     :kind            (:kind tags)
     :source          (:source tags)
     :fallback        (:fallback tags)
     :restored-config (:restored-config tags)
     :resolved-leaf   (:resolved-leaf tags)}))

(defn- history-recorded-record [ev]
  (let [tags (get ev :tags {})]
    {:compound-path   (:compound-path tags)
     :kind            (:kind tags)
     :recorded-config (:recorded-config tags)
     :prev-config     (:prev-config tags)}))

(defn- attach-history
  "Attach the history restore / record records for `transition-record`'s
  machine (keyed by `:machine-id`) off the cascade-window `events`
  (rf2-mle6e.5). A transition that resolved a history pseudo-state carries
  `:history-restored [<record> …]`; one whose macrostep exited a
  history-bearing compound carries `:history-recorded [<record> …]`. Neither
  key is present on an ordinary (non-history) transition, so the inspector's
  history surface is absent for those."
  [transition-record events]
  (let [mid      (:machine-id transition-record)
        restored (->> events
                      (filter (fn [ev]
                                (and (= history-restored-operation (:operation ev))
                                     (= mid (machine-id-of ev)))))
                      (mapv history-restored-record))
        recorded (->> events
                      (filter (fn [ev]
                                (and (= history-recorded-operation (:operation ev))
                                     (= mid (machine-id-of ev)))))
                      (mapv history-recorded-record))]
    (cond-> transition-record
      (seq restored) (assoc :history-restored restored)
      (seq recorded) (assoc :history-recorded recorded))))

(defn project-focused-event-transitions
  "Project the focused epoch's cascade window into a vector of
  per-machine-transition records the Machines panel's focused-event
  lens consumes.

  Inputs:

    `trace-events` — the focused epoch record's `:trace-events` vector
                     (per spec/Spec-Schemas §`:rf/epoch-record`). nil-safe.
    `definitions`  — `{machine-id <machine-definition>}` map (from
                     `:rf.xray/machine-definitions`). nil-safe; used
                     to surface the registered definition on each
                     record so the view can render the topology
                     without re-resolving.

  Returns a vector of records, oldest-first (cascade-document-order),
  one per `:rf.machine/transition` / `:rf.machine.microstep/transition`
  OR `:rf.machine/started` (machine BIRTH — rf2-eldze) event in the
  cascade:

      {:machine-id   <kw>
       :from-state   <kw|vec|nil>          ;; nil on a START record (birth)
       :to-state     <kw|vec|nil>          ;; the resulting INITIAL state on START
       :before       <snapshot-map|nil>   ;; full {:state :data} pre-transition (nil on START)
       :after        <snapshot-map|nil>   ;; full {:state :data} post-transition / initial on START
       :start?       <bool>                ;; true iff this is the machine-birth record
       :cause        <kw|nil>              ;; :explicit / :lazy / :spawned (START only)
       :on-event     <kw|nil>
       :event        <event-vec|nil>
       :time         <int|nil>
       :id           <int|nil>
       :dispatch-id  <id|nil>
       :microstep?   <bool>
       :definition   <machine-def-or-nil>
       :no-op?       <bool>                ;; true iff this is a guard-blocked /
                                            ;; unhandled / no-op record (rf2-skmc7)
       :guards       [<guard-record>...]
       :actions      [<action-record>...]
       :history-restored [<restore-record>...]  ;; rf2-mle6e.5, only when a
                                                 ;; history pseudo-state resolved
       :history-recorded [<record-record>...]}  ;; rf2-mle6e.5, only when a
                                                 ;; history-bearing compound exited

  Per rf2-eldze a machine BIRTH (`:rf.machine/started`) is surfaced as a
  first-class record alongside transitions: a pure start emits no
  `:rf.machine/transition` (it is an entry into the initial state, not a
  from→to), so without this fold a focused start epoch produced zero
  records and the Machine tab rendered the 'does not target a state
  machine' empty state — even though the machine just came up into its
  initial state. The start record carries `:from-state nil` + `:to-state`
  = the resulting initial state, so the view highlights the initial state
  on the topology.

  Per rf2-skmc7 a guard-blocked / unhandled / NO-OP machine event
  (`:rf.machine.event/unhandled-no-op`) is ALSO surfaced as a first-class
  record — the SAME gap as the eldze birth case for a DIFFERENT no-transition
  cause. A no-op emits no `:rf.machine/transition` (the machine stayed put),
  so without this fold a focused guard-blocked-close epoch produced zero
  records and the Machine tab wrongly rendered 'does not target a state
  machine' — even though the event DID dispatch to a registered machine and
  ran its guard. The no-op record carries `:from-state` == `:to-state` == the
  current state + `:no-op? true`, so the view highlights the current state on
  the topology with a `[NO-OP]` marker. A no-op record is folded ONLY for a
  machine that produced NO transition / start record in the same cascade
  (Spec 005 — a no-op is single-signalled): a machine that both transitioned
  and later no-op'd in one cascade surfaces its transition, not a redundant
  ghost no-op section.

  Returns `[]` when no machine-transition / start / no-op trace fired in the
  cascade — the silent-by-default branch the view honours per rf2-g3ghh.

  Pure fn — JVM-runnable."
  ([trace-events]
   (project-focused-event-transitions trace-events nil))
  ([trace-events definitions]
   (let [defs   (or definitions {})
         events (or trace-events [])
         attach-def
         (fn [base]
           (let [defn-> (get defs (:machine-id base))]
             (cond-> base
               defn-> (assoc :definition defn->))))
         order  (fn [ev] (or (:id ev) (:time ev) 0))
         ;; (1) Transition macrosteps / microsteps AND machine births
         ;; (rf2-eldze). A birth carries no from-state; it is projected
         ;; via `started-record-from-trace` rather than the transition
         ;; projector.
         move-records
         (->> events
              (filter (fn [ev] (or (transition-event? ev) (started-event? ev))))
              (sort-by order)
              (map (fn [ev]
                     (attach-def
                       (if (started-event? ev)
                         ;; Birth: no guard/action/history attach — the
                         ;; initial-entry cascade's actions are not traced as
                         ;; `:rf.machine/action-ran` (rf2-n9f4z).
                         (started-record-from-trace ev)
                         (-> (transition-record-from-trace ev)
                             (attach-guards-and-actions events)
                             (attach-history events))))))
              (filter :machine-id)
              vec)
         ;; (2) Guard-blocked / unhandled / NO-OP machine events (rf2-skmc7).
         ;; Folded ONLY for a machine that did NOT also produce a transition /
         ;; start record in this cascade — Spec 005 "a no-op is
         ;; single-signalled": a machine that transitioned AND no-op'd in the
         ;; same cascade surfaces the transition, never a duplicate ghost
         ;; no-op section.
         moved-machine-ids (set (map :machine-id move-records))
         no-op-records
         (->> events
              (filter no-op-event?)
              (sort-by order)
              (map no-op-record-from-trace)
              (filter :machine-id)
              ;; Drop a no-op for a machine that already has a move record.
              (remove (fn [r] (contains? moved-machine-ids (:machine-id r))))
              ;; Dedup multiple no-op traces for the same machine in one
              ;; cascade (parallel-region aggregate can only emit one, but
              ;; belt-and-braces): keep the FIRST in trace order.
              (reduce (fn [{:keys [seen acc]} r]
                        (let [mid (:machine-id r)]
                          (if (contains? seen mid)
                            {:seen seen :acc acc}
                            {:seen (conj seen mid) :acc (conj acc r)})))
                      {:seen #{} :acc []})
              :acc
              (map attach-def)
              vec)]
     ;; Merge + re-sort by trace order so a no-op interleaves with any
     ;; transitions in document order (the Dynamic-mode single-instance rule
     ;; picks `first`, so order is load-bearing).
     (->> (concat move-records no-op-records)
          (sort-by order)
          vec))))

(defn focused-epoch-record
  "Resolve the epoch record whose `:epoch-id` matches `focus`'s
  `:epoch-id`. The spine sub `:rf.xray/focus` carries the focused
  cascade's settling `:epoch-id` per spec/018 §6 (Spine events); the
  record's `:trace-events` is the cascade window the lens reads.

  Routes through the shared `panels.shared.focus-resolver/find-epoch-record`
  (rf2-uo0rc.1) so the Machine Inspector resolves focus identically to
  every other L4 panel:

    - NIL focus epoch-id + non-empty history → HEAD record (rf2-h0120
      head-fallback — the natural LIVE/cold-start debugging UX).
    - focus epoch-id MATCHES a record → that record.
    - focus epoch-id pinned but EVICTED from the ring → nil. The panel
      then renders the §10.7 evicted/blank placeholder rather than
      silently falling back to HEAD and showing the LATEST machine
      state, which lied about which epoch the operator was inspecting.
    - empty history → nil.

  Pure fn — JVM-runnable."
  [epoch-history focus]
  (focus/find-epoch-record (:epoch-id focus) epoch-history))

;; ---- Dynamic-mode single-instance rule (rf2-8og3k, impl rf2-2n34o) ------
;;
;; Per spec/003-Machine-Inspector.md §Dynamic mode — single-instance,
;; event-driven (rf2-8og3k): the Dynamic Machines panel binds to EXACTLY
;; ONE machine instance per focused event, or to NONE. When the focused
;; event's cascade transitioned multiple instances, the rule picks the
;; FIRST transition trace by trace order (earliest `:rf.trace/at`;
;; ties broken by trace-emission sequence within the same epoch).
;;
;; `project-focused-event-transitions` already sorts the records
;; cascade-document-order (oldest-first by `:id` or `:time`), so the
;; tiebreaker is satisfied by `first`. This helper exists to name the
;; rule at the call site — the spec text "first by trace order" lands
;; here, not buried in a `(first records)` call.

(defn pick-focused-transition
  "Pick the focused transition record per the Dynamic-mode single-
  instance rule (spec/003 §Dynamic mode — single-instance, event-driven,
  rf2-8og3k). Returns the FIRST record in trace order (which is what the
  upstream projection already produces — this helper names the rule),
  or nil when no machine transitioned in the focused event's cascade.

  Pure fn — JVM-runnable."
  [transition-records]
  (first transition-records))

(defn focused-event-section-key
  "rf2-un3gfo — the STRUCTURAL React `:key` for the per-machine
  focused-event section. Keyed ONLY on the chart's topology identity —
  the inspected `target-frame` id + the record's `:machine-id` — so
  ordinary Prev/Next epoch navigation WITHIN the same machine preserves
  the section (and the nested MachineChart) React instance.

  ### Why structural, not per-epoch

  The pre-fix key embedded `(:id record)` (epoch/record id) +
  `(:from-state record)` + `(:to-state record)`, all of which change on
  every Prev/Next. React therefore treated each navigation as a
  different element → unmount + remount the section + the chart → the
  chart's per-instance `parse-cache` / `layout-state` / `layout-key`
  caches were discarded → a full topology re-parse + a fresh ELK relayout
  ran on every Prev/Next → the topology flickered.

  The per-epoch visuals do NOT need the key to change: the from/to/
  current/fired highlights flow into the chart as REACTIVE PROPS
  (`:from-highlight` / `:to-highlight` / `:current-state` /
  `:fired-edge-ids`), and the section's own `:data-*` attrs are recomputed
  from `record` on each ordinary re-render. With the instance preserved,
  ELK runs once per topology (`[definition direction layout-options
  density]`) and Prev/Next only re-paints highlights at stable positions.

  ### What IS in the key

  - `target-frame` — the inspected frame id. Switching the L1 frame
    picker re-seeds the panel against a DIFFERENT runtime, where the same
    `machine-id` may name a different machine instance; a fresh instance
    there is correct (and the chart's own layout-key would relayout
    anyway, but a clean remount avoids carrying stale per-instance state
    across frames).
  - `machine-id` — the topology identity. A genuinely different machine
    (different topology) STILL gets a distinct key → a clean instance +
    its own ELK layout, exactly as before.

  Highlight values, epoch/record id, from/to-state, and fired-edge ids
  are deliberately EXCLUDED. Re-fitting the viewport on navigation rides
  the orthogonal `:fit-signal` nonce (rf2-6tw7t), never a remount.

  Pure fn — JVM-runnable. `target-frame` may be nil (single-frame /
  pre-seed); the key tolerates it."
  [target-frame {:keys [machine-id]}]
  (str target-frame "::" machine-id))

(def empty-state-text
  "The Dynamic Machines panel's empty-state placeholder text, rendered
  verbatim per spec/003 §Empty state — focused event does not target a
  state machine (rf2-8og3k). The string is the entire empty-state
  content — no chart, no lens, no history ribbon."
  "This event does not target a state machine")
