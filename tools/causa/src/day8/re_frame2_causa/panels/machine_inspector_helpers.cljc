(ns day8.re-frame2-causa.panels.machine-inspector-helpers
  "Pure-data helpers for Causa's Machine Inspector panel
  (Phase 5+, rf2-r9f9u, parent rf2-5aw5v).

  ## Why a separate `.cljc` ns

  Same dual-target pattern every other panel uses (subscriptions,
  routes, ...). The panel view in
  `machine_inspector.cljs` builds the hiccup; the *logic* — projecting
  the registered-machine set + the live snapshots + the trace-buffer's
  `:rf.machine/transition` slice into per-machine row + chart-prop
  data — is pure data → data and runs under the JVM unit-test target
  (`clojure -M:test`).

  ## What this panel surfaces (per tools/causa/spec/003-Machine-Inspector.md)

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
       §Subscribing to machines via sub-machine), surfaced on the
       picker row + in the placeholder's prop summary.

    4. **Transition history ribbon** — filter the Causa trace buffer
       to `:rf.machine/transition` events for the selected machine and
       render them as a scrubbable horizontal list. Click a row →
       `:rf.causa/select-dispatch-id` (pivots to event-detail, parity
       with every other Causa cross-panel jump).

  ## What v1 does NOT include

    - No source-coord jumps (the cross-panel jump API hasn't
      stabilised yet — same as the routes panel's v1 deferral).
    - No `:spawn-all` viz / `:after` countdown rings / share
      affordance — those live in `tools/machines-viz/` per Spec
      003 §Embedding posture. This panel embeds the component; the
      rendering is the component's job. The placeholder simply
      surfaces the props the real component would consume.
    - No share-URL encoding — that lives in
      `tools/machines-viz/spec/API.md` §Share-URL encoding under the
      `share/encode-share-url` surface. The panel will wire the
      affordance through to those exports when machines-viz ships.

  ## Inputs to the projection

  The composite sub `:rf.causa/machine-inspector-data` feeds three
  sources into `project-data`:

    1. **`machines`** — vector of registered machine-ids
       (`(rf/machines)`). Empty when the machines artefact is not on
       the classpath (Spec 005 §Querying machines: returns `[]`).

    2. **`snapshots`** — `{machine-id snapshot-or-nil}` map; each
       value is the result of `(rf/machine-meta-via-sub machine-id)`
       (i.e. `(get-in app-db [:rf/machines <id>])` against the target
       frame). nil when the machine is registered but not yet
       initialised.

    3. **`trace-buffer`** — Causa's trace ring buffer. The helper
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
  on a populated registrar, `rf/get-frame-db` on a frame) are read
  by the composite sub in `registry.cljs`; the result is handed to
  this ns as a plain map."
  (:require [clojure.string :as str]))

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

;; ---- machine-id resolution ----------------------------------------------

(defn machine-id-of
  "Resolve the machine-id off a trace event. Per Spec 009 the runtime
  stamps `:tags :machine-id` (or `:tags :handler-id` for the machine's
  registered handler-id, which is the same value); the helper falls
  back to either. Returns nil when the slot is missing — the caller
  filters those out before sorting."
  [ev]
  (or (get-in ev [:tags :machine-id])
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
      :on-transition-click
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
     :dispatch-id (:dispatch-id tags)
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
    `trace-buffer` — Causa's trace ring buffer. nil-safe.
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
  to `str` if `pr-str` throws (mirrors the format-edn helper in
  event_detail.cljs but lives here so the test suite can assert
  against the formatted output without booting the view)."
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
  to the legacy `:from`/`:to` tag slots when present (test fixtures)."
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
     :on-event     on-event
     :event        event-v
     :time         (:time ev)
     :id           (:id ev)
     :dispatch-id  (get-in ev [:tags :dispatch-id])
     :microstep?   (= :rf.machine.microstep/transition (:operation ev))
     ;; Guards/actions filled in by `attach-guards-and-actions` —
     ;; default empty so the record shape is stable.
     :guards       []
     :actions      []}))

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

(defn project-focused-event-transitions
  "Project the focused epoch's cascade window into a vector of
  per-machine-transition records the Machines panel's focused-event
  lens consumes.

  Inputs:

    `trace-events` — the focused epoch record's `:trace-events` vector
                     (per spec/Spec-Schemas §`:rf/epoch-record`). nil-safe.
    `definitions`  — `{machine-id <machine-definition>}` map (from
                     `:rf.causa/machine-definitions`). nil-safe; used
                     to surface the registered definition on each
                     record so the view can render the topology
                     without re-resolving.

  Returns a vector of records, oldest-first (cascade-document-order),
  one per `:rf.machine/transition` / `:rf.machine.microstep/transition`
  event in the cascade:

      {:machine-id   <kw>
       :from-state   <kw|vec|nil>
       :to-state     <kw|vec|nil>
       :on-event     <kw|nil>
       :event        <event-vec|nil>
       :time         <int|nil>
       :id           <int|nil>
       :dispatch-id  <id|nil>
       :microstep?   <bool>
       :definition   <machine-def-or-nil>
       :guards       [<guard-record>...]
       :actions      [<action-record>...]}

  Returns `[]` when no machine-transition trace fired in the cascade —
  the silent-by-default branch the view honours per rf2-g3ghh.

  Pure fn — JVM-runnable."
  ([trace-events]
   (project-focused-event-transitions trace-events nil))
  ([trace-events definitions]
   (let [defs (or definitions {})
         events (or trace-events [])]
     (->> events
          (filter transition-event?)
          ;; Stable cascade order — :id is monotonic per Spec 009;
          ;; fall back to :time, then 0.
          (sort-by (fn [ev] (or (:id ev) (:time ev) 0)))
          (map (fn [ev]
                 (let [base (transition-record-from-trace ev)
                       mid  (:machine-id base)
                       defn-> (get defs mid)]
                   (cond-> (attach-guards-and-actions base events)
                     defn-> (assoc :definition defn->)))))
          ;; Drop records whose machine-id couldn't be resolved — a
          ;; malformed trace shouldn't surface a section with no
          ;; identity. nil :machine-id matches no chart definition so
          ;; the row would be unrenderable anyway.
          (filter :machine-id)
          vec))))

(defn focused-epoch-record
  "Resolve the epoch record whose `:epoch-id` matches `focus`'s
  `:epoch-id`. The spine sub `:rf.causa/focus` carries the focused
  cascade's settling `:epoch-id` per spec/018 §6 (Spine events); the
  record's `:trace-events` is the cascade window the lens reads.

  Falls back to the head record when the focused epoch-id is nil
  (LIVE mode default; matches the views-focused-cascade-pair fallback
  pattern). Returns nil when the buffer is empty.

  Pure fn — JVM-runnable."
  [epoch-history focus]
  (let [history (vec (or epoch-history []))]
    (cond
      (empty? history) nil
      (some? (:epoch-id focus))
      (or (some (fn [r] (when (= (:epoch-id focus) (:epoch-id r)) r))
                history)
          ;; Focused epoch-id not in buffer (evicted) → fall back to
          ;; head so the panel renders something rather than going
          ;; silent on what is really a buffer-eviction event.
          (peek history))
      :else (peek history))))
