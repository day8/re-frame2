(ns day8.re-frame2-xray.panels.machines.trace-state
  "Single source of truth for deriving machine STATE + fired-edge ids
  from `:rf.machine/transition` trace events (rf2-8jzm1 · spec/021 §6 +
  machines-viz `001-Topology-Parity.md` §4.4 / G3).

  ## Scope

  Pure data, no DOM / React / re-frame side effects. Every input is
  data; every output is data. The public fns answer three questions
  the topology overlay asks of a buffer of trace events:

    1. **Which state is the machine in?** — `current-state-from-traces`
       (focused epoch), `current-state-from-epoch-history` (walk-back
       fallback), and `from-state-from-traces` (the focused
       transition's SOURCE, for the dashed/dim `:from` circle per
       spec/021 §6.2 Case C).

    2. **Which edges fired this epoch?** — `extract-fired-edge-ids`,
       returning machines-viz-CANONICAL edge ids (see below).

    3. **Which edges were GUARD-BLOCKED this epoch?** —
       `extract-guard-blocked-edge-ids` (rf2-fzrzlw), returning the
       canonical edge ids whose guard evaluated `:fail` / `:threw` so
       the transition was a no-op. A guard-blocked no-op emits NO
       `:rf.machine/transition` (the chart's fired set is empty), so
       without this the operator gets ZERO signal that the event hit an
       edge and was rejected. The runtime DOES emit the exact data —
       `:rf.machine/guard-evaluated {:guard-id … :outcome :fail/:threw
       :input {:event …}}` (machines · transition.cljc) — carrying the
       NAMED guard the bare transition trace lacks, so the blocked-edge
       match is EXACT. This is a SUPERSET enhancement beyond XState
       (Stately takes no transition + highlights nothing on a block);
       only possible because re-frame2 emits `guard-evaluated`.

  Before rf2-8jzm1 these helpers were scattered through the pure-data
  projector `topology.cljs`, interleaved with node/edge geometry. They
  are a distinct concern — *trace events → state paths / edge ids* — so
  they live here as one cohesive, documented module.

  ## Canonical fired-edge ids (G3 — the live-chart prerequisite)

  `extract-fired-edge-ids` mints the SAME edge ids the live MachineChart
  mints, so a future 'highlight the edge that fired this epoch' wiring
  (rf2-qeemm / B8) lands on real chart edges. Rather than RE-IMPLEMENT
  the canonical id scheme (source-id `__` target-id `__` event-segment
  `__g_<guard>` `__a_<action>` + per-key ordinal tiebreak — private to
  `chart.layout`), this module PROJECTS the definition through the public
  `chart.layout/project-definition` and looks the ids up off the projected
  edges. So the ids agree with the live chart BY CONSTRUCTION — there is
  exactly one minting fn, mirroring the node-id unification (rf2-m8kod).

  The match is loose by necessity: a `:rf.machine/transition` trace
  carries `(from-state, to-state, event)` (Spec 005 §lifecycle_fx) but
  NOT the guard/action that discriminated a candidate fork. When a
  machine declares multiple candidate edges sharing
  `(from, to, event)` (the vector-of-candidates grammar — Spec 005),
  every matching canonical id is returned: the transition demonstrably
  fired one of them and the chart paints the matching arm(s) as
  fired-this-epoch.

  Tests live at `tools/xray/test/day8/re_frame2_xray/panels/machines/
  trace_state_cljs_test.cljs`."
  (:require [day8.re-frame2-machines-viz.chart.layout :as chart-layout]))

;; ---- path coercion ------------------------------------------------------

(defn normalise-path
  "Coerce a path-spec into a path vector. Path-specs may be:

    - a vector of keywords `[:populated]` or `[:level-1 :level-2]`
    - a single keyword `:populated` (becomes `[:populated]`)
    - nil (returns nil)
    - any other shape (returns nil)

  Public so the projector (`topology`) and any other consumer coerce
  trace paths identically."
  [path]
  (cond
    (nil? path)     nil
    (keyword? path) [path]
    (vector? path)  (when (every? keyword? path) path)
    (seq? path)     (when (every? keyword? path) (vec path))
    :else           nil))

;; ---- trace-event reading ------------------------------------------------

(defn- machine-transition?
  "True when `ev` is a `:rf.machine/transition` trace event for
  `machine-id` (or for ANY machine when `machine-id` is nil). Per
  rf2-ws5thu the addressed live actor lives under `:tags :actor-id`
  (with a `:tags :machine-id` fallback for legacy fixtures)."
  [ev machine-id]
  (and (= :rf.machine/transition (:operation ev))
       (or (nil? machine-id)
           (= machine-id (or (get-in ev [:tags :actor-id])
                             (get-in ev [:tags :machine-id]))))))

(defn- machine-transitions
  "Filter `trace-events` to the `:rf.machine/transition` events for
  `machine-id`, preserving order. nil-safe."
  [trace-events machine-id]
  (filter #(machine-transition? % machine-id) (or trace-events [])))

(defn- to-path-from-trace
  "Pull the `:to` (target) state path off a `:rf.machine/transition`
  trace event. Tolerant of three shapes:

    1. Modern runtime: `:tags {:after {:state ...}}` (per
       `lifecycle_fx/registration` `trace/emit!` of
       `:rf.machine/transition`).
    2. Legacy/test fixtures: top-level `:to` or `:tags :to`.
    3. Pre-rf2-hwuki: `:payload :to`.

  Returns the normalised path vector or nil."
  [ev]
  (let [after-state (get-in ev [:tags :after :state])
        to          (or (get-in ev [:tags :to])
                        (:to ev)
                        (get-in ev [:payload :to]))]
    (normalise-path (or after-state to))))

(defn- from-path-from-trace
  "Pull the `:from` (source) state path off a `:rf.machine/transition`
  trace event. Tolerant of the same three shapes as `to-path-from-trace`:

    1. Modern runtime: `:tags {:before {:state ...}}`.
    2. Legacy/test fixtures: top-level `:from` or `:tags :from`.
    3. Pre-rf2-hwuki: `:payload :from`.

  Returns the normalised path vector or nil."
  [ev]
  (let [before-state (get-in ev [:tags :before :state])
        from         (or (get-in ev [:tags :from])
                         (:from ev)
                         (get-in ev [:payload :from]))]
    (normalise-path (or before-state from))))

;; ---- raw (un-normalised) before/after state -----------------------------
;;
;; rf2-8ncxrf — a PARALLEL machine's snapshot `:state` is a region-MAP
;; (`{:climate :idle :fan :off}`), one active leaf per orthogonal region
;; (Spec 005 §Parallel regions). `from-path-from-trace` / `to-path-from-trace`
;; run that value through `normalise-path`, which returns nil for a map —
;; correct for the single-active path resolvers, but it means a parallel
;; transition's before/after vanish there and `extract-fired-edge-ids` lights
;; NO edge for an event that fired in N regions at once. The parallel branch
;; needs the RAW `:state` value (keyword / vector / region-map) so it can
;; iterate the per-region (from, to) pairs. These readers mirror the
;; before/after slot tolerance of the path readers but DON'T normalise.

(defn- before-state-from-trace
  "Pull the RAW (un-normalised) `:before` (source) state value off a
  `:rf.machine/transition` trace — a keyword, a path vector, OR a
  parallel region-MAP. Same slot tolerance as `from-path-from-trace`
  (modern `:tags :before :state`, legacy `:tags :from` / `:from` /
  `:payload :from`); returns the value verbatim (no path coercion)."
  [ev]
  (let [before-state (get-in ev [:tags :before :state])]
    (if (some? before-state)
      before-state
      (or (get-in ev [:tags :from])
          (:from ev)
          (get-in ev [:payload :from])))))

(defn- after-state-from-trace
  "Pull the RAW (un-normalised) `:after` (target) state value off a
  `:rf.machine/transition` trace — a keyword, a path vector, OR a
  parallel region-MAP. Same slot tolerance as `to-path-from-trace`
  (modern `:tags :after :state`, legacy `:tags :to` / `:to` /
  `:payload :to`); returns the value verbatim (no path coercion)."
  [ev]
  (let [after-state (get-in ev [:tags :after :state])]
    (if (some? after-state)
      after-state
      (or (get-in ev [:tags :to])
          (:to ev)
          (get-in ev [:payload :to])))))

(defn- event-from-trace
  "Pull the event keyword off a `:rf.machine/transition` trace event.

  Tolerant of the three shapes that carry the inner event (mirrors the
  before/after-state tolerance of `to-path-from-trace`):

    1. Modern runtime: `:tags {:event <kw-or-vec>}` — `commit-or-finalize`
       (`lifecycle_fx/registration`) emits `(trace/emit! :rf.machine
       :rf.machine/transition {:event inner-event ...})`, so the inner
       event lands under `:tags :event`. This is the shape the live chart
       wiring (rf2-qeemm / G3) sees in production + the sub-reactivity
       fixtures use.
    2. Legacy/test fixtures: top-level `:event`.
    3. Pre-rf2-hwuki: `:payload :event`.

  The event may be a keyword or a `[event-id & args]` vector; returns the
  bare event keyword (the vector's head) or nil."
  [ev]
  (let [event (or (get-in ev [:tags :event])
                  (:event ev)
                  (get-in ev [:payload :event]))]
    (cond
      (keyword? event) event
      (vector? event)  (first event)
      :else            nil)))

;; ---- current / from state resolution ------------------------------------

(defn current-state-from-traces
  "Resolve the current-state path for `machine-id` from the
  `:rf.machine/transition` trace events vector. Returns the `:to`
  path of the most recent matching trace, or nil. Pure.

  Reads modern (`:tags :after :state`) + legacy (`:to`,
  `:payload :to`) shapes — see `to-path-from-trace`."
  [trace-events machine-id]
  (when-let [last-ev (last (machine-transitions trace-events machine-id))]
    (to-path-from-trace last-ev)))

(defn from-state-from-traces
  "Resolve the FROM (source) state path for `machine-id` from the
  `:rf.machine/transition` trace events vector. Returns the `:from`
  path of the most recent matching trace, or nil. Pure.

  Per spec/021 §6.2 Case C (Figma reconcile · rf2-ad7zx.10): the
  focused fired transition's source state renders as the dashed/dim
  `:from` circle. The view layer pairs this with
  `current-state-from-traces` (the TO / `:current` double-circle).

  Reads modern (`:tags :before :state`) + legacy (`:from`,
  `:payload :from`) shapes — see `from-path-from-trace`."
  [trace-events machine-id]
  (when-let [last-ev (last (machine-transitions trace-events machine-id))]
    (from-path-from-trace last-ev)))

(defn current-state-from-epoch-history
  "Walk `epoch-history` (a vector of epoch records, oldest-first per
  `:rf/epoch-record`) backwards looking for the most recent
  `:rf.machine/transition` trace for `machine-id`. Returns the `:to`
  path of that transition, or nil if no transition for `machine-id`
  appears anywhere in history.

  Per spec/021 §6.3 (Queries / Per-frame state · current-state ●
  annotation for case B): when the focused epoch has no transition,
  the panel still renders topology with the most-recent-known state
  annotated. This helper is the historical fallback caller — the view
  layer composes it with `current-state-from-traces` (focused epoch)
  + live snapshot `:state`.

  Pure fn — JVM-runnable."
  [epoch-history machine-id]
  (let [history (vec (or epoch-history []))]
    (loop [i (dec (count history))]
      (when (>= i 0)
        (let [events (get-in history [i :trace-events])
              found  (current-state-from-traces events machine-id)]
          (or found (recur (dec i))))))))

;; ---- fired-edge ids (machines-viz canonical) ----------------------------

(defn- on-active-path?
  "True iff `path` (an edge's declaring `:from-path` / `:to-path`) is on
  the active `state-path` — i.e. `path` is a (possibly equal) PREFIX of
  `state-path`. A STATE-LOCAL edge declares on the active leaf (`path` ==
  `state-path`); an INHERITED edge declares on an ANCESTOR still on the
  active path (`path` is a strict prefix). A path declared on a DIFFERENT
  branch (a sibling state reusing the same event, or the bug's sibling
  guard case) is NOT a prefix of the active path, so it is excluded. nil
  `state-path` (region-map / absent) disables the check upstream — never
  reaches here."
  [path state-path]
  (and (<= (count path) (count state-path))
       (= (vec path) (vec (take (count path) state-path)))))

(defn- single-transition-fired-ids
  "Fired-edge ids for ONE single-active (flat / compound) transition: the
  `(from*, to*, event*)` paths read off the trace (already path-coerced;
  `from*` / `to*` are the runtime's actual LEAF before/after state).
  A STATE-LOCAL edge matches on all three; falls back to a MACHINE-LEVEL
  (top-level `:on`) edge matched on (to, event) alone — rf2-vcnvj projects
  the fallback ONCE from the synthetic MACHINE-ROOT node, so its
  `:from-path` is the root context `[]`, not the concrete leaf the runtime
  fired it from. Without that fallback the door `:door/audit` fallback
  firing from `:alarming` would highlight no edge. Returns a seq of ids.

  rf2-6e8rh8 — `:from-path` / `:to-path` match via `on-active-path?`
  (prefix), NOT `=` (exact equality). Per Spec 005 deepest-wins, a
  transition inherited from a compound ANCESTOR fires with the runtime's
  `:before :state` at the active LEAF (e.g. `[:open :wide]`) while
  `chart.layout/project-definition` projects the edge's `:from-path` at
  the DECLARING ancestor (`[:open]`) — exact `=` never matches an
  inherited edge. Symmetrically, a compound TARGET's initial-descent
  lands the runtime's `:after :state` at a deeper leaf than the edge's
  declared `:to-path`, so `to*` is matched the same way. `on-active-path?`
  subsumes the STATE-LOCAL case (`path` == the leaf, count-equal prefix)."
  [edges from* to* event*]
  (let [local (keep (fn [e]
                      (when (and (not (:machine-level? e))
                                 (on-active-path? (:from-path e) from*)
                                 (on-active-path? (:to-path e) to*)
                                 (= event* (:event e)))
                        (:id e)))
                    edges)]
    (if (seq local)
      local
      (keep (fn [e]
              (when (and (:machine-level? e)
                         (on-active-path? (:to-path e) to*)
                         (= event* (:event e)))
                (:id e)))
            edges))))

(defn- cascade-from-trace
  "rf2-l8ls6w — pull the structured `:cascade` step vector off a
  `:rf.machine/transition` trace. The runtime stamps it under `:tags :cascade`
  (modern) — `commit-or-finalize` (`lifecycle_fx/registration`) emits
  `(trace/emit! :rf.machine :rf.machine/transition {… :cascade cascade})`, so
  it lands under `:tags`. A legacy/test-fixture flat `:cascade` is tolerated.
  Returns the step vector (possibly empty) — never nil."
  [ev]
  (or (get-in ev [:tags :cascade])
      (:cascade ev)
      []))

(defn- handled-regions-from-cascade
  "rf2-l8ls6w — the SET of region names a parallel macrostep actually HANDLED
  this transition, read off the trace's structured `:cascade`. Each cascade
  step carries `:region <region-name>` (stamped by the single-machine engine
  from the synthetic region-spec's `:rf/region` — `transition.cljc` §structured
  cascade steps); a region that handled the event contributes at least one
  step (`:exit` / `:action` / `:entry`), a RESTING region (declined the event)
  contributes none. This is the discriminator that distinguishes a HANDLED-
  unchanged region (a real self/internal transition with `before == after` +
  a non-empty cascade) from a region that simply did not move. Returns the set
  of non-nil `:region`s present in the cascade."
  [cascade]
  (into #{} (keep :region) cascade))

(defn- region-local-fired-ids
  "rf2-8ncxrf — fired-edge ids for ONE region that CHANGED state (its
  `before ≠ after`). The region-scoped match: the edge's `:from-path` /
  `:to-path` are the IN-REGION paths and its `:source` is
  `(region-scoped-id region from)` — the same injective id-scheme
  `highlight-ids` resolves a region-map against (rf2-wnzha), so two regions
  sharing a state NAME never cross-match. Returns a seq of ids (empty when no
  region-LOCAL edge matched — e.g. the region moved via the parallel ROOT
  `:on`; that fallback is matched by `region-root-on-fired-ids`)."
  [edges region from* to* event*]
  (let [scoped-source (chart-layout/region-scoped-id region from*)]
    (keep (fn [e]
            (when (and (= from*         (:from-path e))
                       (= to*           (:to-path e))
                       (= event*        (:event e))
                       (= scoped-source (:source e)))
              (:id e)))
          edges)))

(defn- region-root-on-fired-ids
  "rf2-3v3gv1 — fired-edge ids for ONE region that the parallel ROOT `:on`
  moved (the ancestor fallback — Spec 005 §Root parallel `:on`). When no
  region-LOCAL transition handles the event, the root `:on` fires, moving one
  or more REGION-QUALIFIED targets. `project-parallel`
  (`collect-parallel-root-edges`) projects each such edge from the synthetic
  MACHINE-ROOT chip with a region-qualified `:to-path` `[<region> &
  <in-region-path>]` (NOT a region-scoped in-region `:from-path`/`:source`
  like a region-local edge), so the region-local match above can't reach it.

  We match a root `:on` edge (`:parallel-root-on? true`) whose region-
  qualified `:to-path` head names THIS region and whose tail is the region's
  in-region `to*`, on the event. `to*` is the moved region's in-region path
  (a keyword coerces to a 1-vector); the edge's region-qualified `:to-path`
  is `(cons region to*)`. Returns a seq of ids."
  [edges region to* event*]
  (let [qualified-to (vec (cons region to*))]
    (keep (fn [e]
            (when (and (:parallel-root-on? e)
                       (= qualified-to (:to-path e))
                       (= event*       (:event e)))
              (:id e)))
          edges)))

(defn- region-machine-on-fired-ids
  "rf2-85a9do — fired-edge ids for ONE region that CHANGED via its OWN
  region-level top-level `:on` fallback (a legal Spec 005 region-level
  fallback — XState v5: a region is an orthogonal compound state, so its
  `:on` is an ordinary region-scoped transition that applies when no
  child state handles the event).

  `project-parallel` (`layout.cljc` §rf2-7i7t3) projects such a region
  def's top-level `:on` by DROPPING the synthetic machine-root node and
  re-pointing the machine-level fallback edge's source to the REGION
  CONTAINER (`region-node-id`). So the projected edge carries
  `:machine-level? true`, `:from-path []`, `:source (region-node-id
  region)`, and an in-region `:to-path` (NOT region-qualified, and NOT
  `:parallel-root-on?`). Neither `region-local-fired-ids` (keys on the
  region-scoped in-region source) nor `region-root-on-fired-ids` (keys on
  `:parallel-root-on?` + a region-qualified `:to-path`) can reach it, so
  the region top-level fallback was the one traversed arm Xray failed to
  light — leaving a parallel region state change with no fired-edge
  highlight (the edge-id agreement the live chart relies on, machines-viz
  `001-Topology-Parity.md`).

  We match the machine-level fallback edge whose `:source` is THIS
  region's container, whose `:to-path` is the moved region's in-region
  `to*`, on the event. Returns a seq of ids."
  [edges region to* event*]
  (let [region-source (chart-layout/region-node-id region)]
    (keep (fn [e]
            (when (and (:machine-level? e)
                       (not (:parallel-root-on? e))
                       (= region-source (:source e))
                       (= to*           (:to-path e))
                       (= event*        (:event e)))
              (:id e)))
          edges)))

(defn- region-self-internal-fired-ids
  "rf2-l8ls6w — fired-edge ids for ONE region that was HANDLED but whose state
  did NOT change (`before == after`): a real targetless/internal or external
  self transition (`:target :same-state`). The runtime emits a
  `:rf.machine/transition` (the macrostep was not a no-op — the handled
  region's cascade is non-empty) and machines-viz projects the self edge, yet
  the before/after region-map shows no change for the region, so
  `region-local-fired-ids` (which keys on `from ≠ to`) skips it and Xray
  highlights nothing.

  A self/internal edge in the region is region-scoped exactly like any region
  edge: its `:from-path == :to-path == self*` (the resting/self path) and its
  `:source` is `(region-scoped-id region self*)`. We match on the region-
  scoped source + `from == to == self*` + event, lighting BOTH the external
  self-loop (`:from-path == :to-path`, no `:internal?`) and the internal
  action-only edge (`:internal? true`, also self-anchored). Returns a seq of
  ids."
  [edges region self* event*]
  (let [scoped-source (chart-layout/region-scoped-id region self*)]
    (keep (fn [e]
            (when (and (= self*         (:from-path e))
                       (= self*         (:to-path e))
                       (= event*        (:event e))
                       (= scoped-source (:source e)))
              (:id e)))
          edges)))

(defn- region-machine-internal-fired-ids
  "rf2-pdvtxt — fired-edge ids for ONE region HANDLED-but-UNCHANGED via its
  OWN region-level TARGETLESS/action-only top-level `:on` fallback (a legal
  Spec 005 region-level internal fallback — XState v5 targetless semantics:
  the region's `:on` runs an `:action` and moves no state when no child
  handles the event).

  The peer of `region-machine-on-fired-ids` for the UNCHANGED case.
  `project-parallel` (`layout.cljc` §rf2-pdvtxt) projects such a region def's
  targetless top-level `:on` by dropping the synthetic machine-root node and
  anchoring the internal fallback edge to the REGION CONTAINER on BOTH ends:
  `:machine-level? true`, `:internal? true`, `:source == :target ==
  (region-node-id region)`, NOT `:parallel-root-on?`.

  `region-self-internal-fired-ids` keys on the region-SCOPED in-region source
  (`region-scoped-id region self*`), so it lights a LEAF self/internal edge
  but CANNOT reach a region-ROOT internal fallback whose source is the
  container. Before this arm, such a handled-unchanged region-root fallback
  was the one traversed edge Xray failed to light on a focused epoch.

  We match the machine-level INTERNAL fallback edge whose `:source` is THIS
  region's container, on the event, excluding `:parallel-root-on?` edges.
  Returns a seq of ids."
  [edges region event*]
  (let [region-source (chart-layout/region-node-id region)]
    (keep (fn [e]
            (when (and (:machine-level? e)
                       (:internal? e)
                       (not (:parallel-root-on? e))
                       (= region-source (:source e))
                       (= event*        (:event e)))
              (:id e)))
          edges)))

(defn- parallel-transition-fired-ids
  "Fired-edge ids for ONE PARALLEL multi-region transition.

  A `:type :parallel` machine's snapshot `:state` is a region-MAP — one
  active leaf per orthogonal region (Spec 005 §Parallel regions). A single
  external event (e.g. `[:hvac/power-cycle]`) fires transitions in N
  regions at once, but the runtime emits ONE `:rf.machine/transition`
  whose `:before` / `:after` carry the WHOLE composite region-map (per
  `lifecycle_fx.registration/commit-or-finalize`). So the fired EDGES are
  derived from the before/after region-map PLUS the structured `:cascade`,
  NOT from a single (from, to) pair.

  Per region, the CHANGED outcome is resolved through an ordered fallback
  (the three edge shapes are mutually exclusive per region) and the
  HANDLED-but-UNCHANGED outcome lights the self/internal edge:

    - **rf2-8ncxrf — region CHANGED via a region-local transition.** `from ≠
      after`; the region-scoped (from, to, event) match (`region-local-fired-
      ids`) lights the moved region's edge. WINS over both fallbacks below.
    - **rf2-85a9do — region CHANGED via its OWN top-level `:on` fallback.**
      `from ≠ after`, no region-local edge matched, but the region def
      carried a region-level `:on` whose machine-level fallback edge
      (`:machine-level?`, sourced from the region CONTAINER, in-region
      `:to-path`, NOT `:parallel-root-on?`) moved it. `region-machine-on-
      fired-ids` lights it. Reserved between region-local and the root `:on`.
    - **rf2-3v3gv1 — region CHANGED via the parallel ROOT `:on`.** `from ≠
      after` but NEITHER a region-local NOR a region-level `:on` edge matched —
      the move came from the root `:on` ancestor fallback. `region-root-on-
      fired-ids` lights the root-sourced chip whose region-qualified
      `:to-path` names this region. (A root `:on` moving a region while a
      region-local/region-level edge ALSO matches cannot happen — the root
      `:on` is suppressed ENTIRELY when any region handles the event, Spec
      005; so the three are mutually exclusive per region.)
    - **rf2-l8ls6w / rf2-pdvtxt — region HANDLED but UNCHANGED.** `before ==
      after` yet the region appears in the `:cascade` (a real self/internal
      transition fired with a non-empty cascade). Two self/internal edge
      shapes are lit, in order: a LEAF self/internal edge (`region-self-
      internal-fired-ids`, region-scoped in-region source), else a region-ROOT
      targetless/action-only `:on` fallback (`region-machine-internal-fired-
      ids`, `:machine-level? true` `:internal? true` sourced from the region
      CONTAINER — rf2-pdvtxt). A region that simply DECLINED the event
      (RESTING — absent from the cascade) lights nothing.

  `handled-regions` is the set of region names the cascade recorded (from
  `handled-regions-from-cascade`). Per-region `from` / `to` are coerced
  through `normalise-path` (a region value is a keyword or in-region vector).
  Returns a seq of ids."
  [edges before-map after-map event* handled-regions]
  (when event*
    (mapcat
      (fn [[region region-before]]
        (let [region-after (get after-map region)
              from*        (normalise-path region-before)
              to*          (normalise-path region-after)]
          (cond
            ;; CHANGED: region-local match wins; else the region's OWN
            ;; top-level `:on` fallback (rf2-85a9do); else the parallel
            ;; ROOT `:on` ancestor fallback. The three edge shapes are
            ;; mutually exclusive per region (a region-scoped in-region
            ;; source, vs a region-container source, vs a `:parallel-root-
            ;; on?` region-qualified target), so the first that matches is
            ;; the traversed arm.
            (and from* to* (not= region-before region-after))
            (or (seq (region-local-fired-ids edges region from* to* event*))
                (seq (region-machine-on-fired-ids edges region to* event*))
                (region-root-on-fired-ids edges region to* event*))

            ;; HANDLED-but-UNCHANGED: a real self/internal transition with
            ;; before == after + a non-empty cascade for this region. A LEAF
            ;; self/internal edge wins; else a region-ROOT targetless/action-
            ;; only `:on` fallback (rf2-pdvtxt — `:machine-level?` `:internal?`
            ;; sourced from the region container, which `region-self-internal-
            ;; fired-ids` cannot reach). A RESTING region (= but absent from
            ;; the cascade) lights nothing.
            (and from*
                 (= region-before region-after)
                 (contains? handled-regions region))
            (or (seq (region-self-internal-fired-ids edges region from* event*))
                (region-machine-internal-fired-ids edges region event*))

            :else nil)))
      before-map)))

(defn extract-fired-edge-ids
  "Given a machine `definition`, a vector of `:rf.machine/transition`
  trace events for the focused epoch, and the `machine-id` of interest,
  return the SET of machines-viz-canonical edge ids that fired this
  epoch. Pure fn — JVM-runnable.

  The returned ids are the EXACT ids the live MachineChart mints: the
  definition is projected through `chart.layout/project-definition` and
  each transition trace's `(from-path, to-path, event)` is matched
  against the projected edges' `:from-path` / `:to-path` / `:event`.
  This is the prerequisite for wiring a fired-this-epoch edge highlight
  onto the live chart (rf2-qeemm / B8) — the ids agree by construction,
  not by a re-implemented id scheme (the G3 'single edge-id source of
  truth' contract — machines-viz `001-Topology-Parity.md` §4.4).

  The match is loose: a transition trace carries
  `(from, to, event)` but not the guard/action that discriminated a
  candidate fork, so when the definition declares several candidate
  edges sharing that triple, EVERY matching canonical id is returned.
  Transitions that don't carry an explicit event-id are excluded (they
  can't be matched to a declared edge).

  rf2-8ncxrf — PARALLEL multi-region transitions. A `:type :parallel`
  machine's `:before` / `:after` `:state` is a region-MAP, and ONE event
  fires transitions in N regions simultaneously yet emits ONE trace. The
  per-event branch detects the region-map shape and lights EVERY changed
  region's edge (`parallel-transition-fired-ids`), so the focused Machine
  view renders all N fired region-transitions — not a blank chart (the
  single-active path returns nil for a map, which is why the event-focused
  view showed NO transition for `[:hvac/power-cycle]`).

  rf2-3v3gv1 — PARALLEL ROOT `:on` (the ancestor fallback — Spec 005 §Root
  parallel `:on`). When no region-local transition handles the event the root
  `:on` fires, moving one or more region-qualified targets; the move shows in
  the before/after region-map but is sourced from the synthetic MACHINE-ROOT
  chip (region-qualified `:to-path`), not a region-local edge — so the per-
  region branch falls back to matching the root-sourced chip.

  rf2-l8ls6w — HANDLED-but-UNCHANGED parallel regions. A parallel region can
  fire a real targetless/internal or self transition with `before == after`
  and a non-empty cascade; the before/after region-map shows no change, so the
  pure region-map diff skipped it. The per-region branch reads the trace's
  structured `:cascade` (`handled-regions-from-cascade`) to distinguish a
  HANDLED-unchanged region (lights its self/internal edge) from a RESTING
  region that simply declined the event (lights nothing).

  Returns `#{}` for a nil/empty definition or no matching transitions."
  [definition trace-events machine-id]
  (let [edges (:edges (chart-layout/project-definition definition))]
    (->> (machine-transitions trace-events machine-id)
         (mapcat
           (fn [ev]
             (let [event*      (event-from-trace ev)
                   before-raw  (before-state-from-trace ev)
                   after-raw   (after-state-from-trace ev)]
               (if (or (map? before-raw) (map? after-raw))
                 ;; PARALLEL multi-region: derive from the region-maps + the
                 ;; structured cascade so every region that moved (region-local
                 ;; or via the root `:on`) OR was handled-unchanged lights its
                 ;; edge.
                 (parallel-transition-fired-ids
                   edges
                   (if (map? before-raw) before-raw {})
                   (if (map? after-raw) after-raw {})
                   event*
                   (handled-regions-from-cascade (cascade-from-trace ev)))
                 ;; Single-active (flat / compound): the existing
                 ;; (from, to, event) match + machine-level fallback.
                 (let [from* (from-path-from-trace ev)
                       to*   (to-path-from-trace ev)]
                   (when (and from* to* event*)
                     (single-transition-fired-ids edges from* to* event*)))))))
         set)))

;; ---- guard-blocked-edge ids (rf2-fzrzlw) --------------------------------

(defn- machine-guard-evaluated?
  "True when `ev` is a `:rf.machine/guard-evaluated` trace event for
  `machine-id` (or for ANY machine when `machine-id` is nil). The
  addressed live actor rides under `:tags :actor-id` (machines ·
  transition.cljc `evaluate-guard` `trace/emit!` stamps the running
  INSTANCE address there; `:machine-id` is reserved for the registered
  TYPE), with a `:tags :machine-id` fallback for legacy fixtures —
  matching `machine-transition?`."
  [ev machine-id]
  (and (= :rf.machine/guard-evaluated (:operation ev))
       (or (nil? machine-id)
           (= machine-id (or (get-in ev [:tags :actor-id])
                             (get-in ev [:tags :machine-id]))))))

(defn- guard-blocked?
  "True when a `:rf.machine/guard-evaluated` trace's `:outcome` signals
  the guard DECLINED the candidate — `:fail` (guard returned falsey) or
  `:threw` (guard threw; the engine treats throw as fail and walks on,
  per machines · transition.cljc `evaluate-guard`)."
  [ev]
  (contains? #{:fail :threw} (get-in ev [:tags :outcome])))

(defn- guard-ref-from-trace
  "Pull the user-declared guard REF off a `:rf.machine/guard-evaluated`
  trace. The ref is the value AS-IS (keyword OR inline fn — Spec
  Schemas §`:rf.machine/guard-evaluated`); it rides `:tags :guard-id`
  (the canonical slot) with a legacy `:tags :guard` fallback (mirrors
  `machine_inspector_helpers/guard-record`). Returns the ref or nil."
  [ev]
  (or (get-in ev [:tags :guard-id])
      (get-in ev [:tags :guard])))

(defn- guard-event-from-trace
  "Pull the inbound EVENT keyword off a `:rf.machine/guard-evaluated`
  trace. The event rides `:tags :input :event` (the unified callback
  context the engine stamps — machines · transition.cljc
  `evaluate-guard` `input {:data … :event …}`); a legacy `:tags :event`
  fallback covers test fixtures that stamp it flat. The event may be a
  bare keyword or a `[event-id & args]` vector; returns the bare event
  keyword (the vector's head) or nil."
  [ev]
  (let [event (or (get-in ev [:tags :input :event])
                  (get-in ev [:tags :event]))]
    (cond
      (keyword? event) event
      (vector? event)  (first event)
      :else            nil)))

(defn- guard-state-from-trace
  "Pull the ACTIVE state path the guard was evaluated against off a
  `:rf.machine/guard-evaluated` trace (rf2-tjm3u2). The runtime stamps the
  snapshot's `:state` under `:tags :state` (machines · transition.cljc
  `evaluate-guard`). Coerced through `normalise-path` so a keyword
  (`:open`) or vector path (`[:authenticated :cart]`) both land as a path
  vector; a parallel region-MAP `:state` (no single active leaf) or an
  absent slot returns nil — and a nil state then disables source-path
  disambiguation (the consumer falls back to the `(event, guard)` match,
  preserving the pre-rf2-tjm3u2 behaviour for traces that carry no state)."
  [ev]
  (normalise-path (get-in ev [:tags :state])))

(defn extract-guard-blocked-edge-ids
  "Given a machine `definition`, a vector of trace events for the
  focused epoch, and the `machine-id` of interest, return the SET of
  machines-viz-canonical edge ids that were GUARD-BLOCKED this epoch —
  the transition's guard evaluated `:fail` / `:threw` so the event was
  a no-op (NO `:rf.machine/transition` was emitted). Pure fn —
  JVM-runnable. rf2-fzrzlw.

  A guard-blocked no-op paints NOTHING on the chart via the fired set
  (it carries no transition), so the operator cannot see which edge the
  event hit or that a guard rejected it. The runtime emits the exact
  data on the no-op path: `:rf.machine/guard-evaluated {:guard-id …
  :outcome :fail/:threw :input {:event …} :state …}` carrying the NAMED
  guard AND the ACTIVE state the guard ran against (rf2-tjm3u2), so the
  blocked-edge match is EXACT.

  rf2-tjm3u2 — match by `(source-path, event, guard)`, NOT `(event,
  guard)` alone. A machine may declare the SAME event + SAME guard id on
  MULTIPLE states (`:open` and `:ajar` both with `:door/close` guarded by
  `:may-close?`). `(event, guard)` alone matched EVERY such edge, so ONE
  guard failure in ONE active state painted ALL of them pink. The guard
  runs during the ACTIVE-configuration resolution, so the trace's `:state`
  (the active path) is the discriminator: only an edge whose declaring
  `:from-path` is ON that active path (a prefix of it — `on-active-path?`)
  could have produced this block. A STATE-LOCAL edge declares on the
  active leaf; an INHERITED edge declares on an ancestor still on the
  active path; a SIBLING state's same-(event, guard) edge is NOT on the
  active path and is correctly excluded. The MACHINE-LEVEL fallback
  (top-level `:on`, `:from-path []`) is on EVERY active path (the empty
  vector is a prefix of all), so it still matches — matching the
  machine-level fallback `extract-fired-edge-ids` keeps.

  Backward-compatible: when the trace carries NO `:state` (a region-MAP
  parallel snapshot, or an older/hand-built trace), `on-active-path?` is
  not consulted and the match falls back to `(event, guard)` alone — the
  pre-rf2-tjm3u2 behaviour. So a guarded FORK on a single state (two
  same-source candidates differing by guard) still lights ONLY the arm
  whose named guard the trace reports failing (the named guard remains the
  fork-arm discriminator).

  The returned ids are the EXACT ids the live MachineChart mints: the
  definition is projected through `chart.layout/project-definition` and
  each fail/threw guard trace is matched against the projected edges'
  `:from-path` / `:event` / `:guard`. The guard refs compare with `=`
  because both the trace's `:guard-id` and the edge's `:guard` are the
  SAME user-declared ref off the SAME definition (keyword or fn).

  Scope is GUARD-BLOCKED only (rf2-fzrzlw design call (3)): a truly-
  unhandled event (no declared edge for it) is a separate state-node
  'ignored event' marker, filed separately.

  Returns `#{}` for a nil/empty definition or no fail/threw guard
  traces."
  [definition trace-events machine-id]
  (let [edges (:edges (chart-layout/project-definition definition))]
    (->> (or trace-events [])
         (filter #(and (machine-guard-evaluated? % machine-id)
                       (guard-blocked? %)))
         (mapcat
           (fn [ev]
             (let [guard* (guard-ref-from-trace ev)
                   event* (guard-event-from-trace ev)
                   ;; rf2-tjm3u2 — the active state path the guard ran
                   ;; against. nil (region-map / absent) disables source-
                   ;; path disambiguation (the `(event, guard)` fallback).
                   state* (guard-state-from-trace ev)]
               ;; A blocked candidate is uniquely (source-path, event,
               ;; guard): the named guard discriminates which arm of a
               ;; guarded fork declined, and the active state discriminates
               ;; WHICH state's edge when several states reuse the same
               ;; (event, guard). An edge with no guard cannot be the
               ;; blocked candidate, so the `(some? guard*)` precondition
               ;; keeps a guardless trace (which the runtime never emits —
               ;; `evaluate-guard` skips the synthesised always-true guard)
               ;; from lighting every same-event edge.
               (when (and (some? guard*) event*)
                 (keep (fn [e]
                         (when (and (= event* (:event e))
                                    (= guard* (:guard e))
                                    ;; Source-path gate: skip only when the
                                    ;; trace carried a usable `:state` AND
                                    ;; this edge's declaring `:from-path` is
                                    ;; NOT on that active path. nil `state*`
                                    ;; → no gate (the (event, guard)
                                    ;; fallback).
                                    (or (nil? state*)
                                        (on-active-path? (:from-path e) state*)))
                           (:id e)))
                       edges)))))
         set)))
