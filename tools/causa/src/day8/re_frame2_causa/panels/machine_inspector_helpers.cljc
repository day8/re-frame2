(ns day8.re-frame2-causa.panels.machine-inspector-helpers
  "Pure-data helpers for Causa's Machine Inspector panel
  (Phase 5+, rf2-r9f9u, parent rf2-5aw5v).

  ## Why a separate `.cljc` ns

  Same dual-target pattern every other panel uses (causality-graph,
  subscriptions, routes, ...). The panel view in
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
    - No `:invoke-all` viz / `:after` countdown rings / share
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
