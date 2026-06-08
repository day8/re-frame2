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
  `machine-id` (or for ANY machine when `machine-id` is nil). The
  machine-id lives under `:tags :machine-id` per the runtime
  `trace/emit!` of `:rf.machine/transition`."
  [ev machine-id]
  (and (= :rf.machine/transition (:operation ev))
       (or (nil? machine-id)
           (= machine-id (get-in ev [:tags :machine-id])))))

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

(defn- single-transition-fired-ids
  "Fired-edge ids for ONE single-active (flat / compound) transition: the
  `(from*, to*, event*)` paths read off the trace (already path-coerced).
  A STATE-LOCAL edge matches on all three; falls back to a MACHINE-LEVEL
  (top-level `:on`) edge matched on (to, event) alone — rf2-vcnvj projects
  the fallback ONCE from the synthetic MACHINE-ROOT node, so its
  `:from-path` is the root context `[]`, not the concrete leaf the runtime
  fired it from. Without that fallback the door `:door/audit` fallback
  firing from `:alarming` would highlight no edge. Returns a seq of ids."
  [edges from* to* event*]
  (let [local (keep (fn [e]
                      (when (and (not (:machine-level? e))
                                 (= from*  (:from-path e))
                                 (= to*    (:to-path e))
                                 (= event* (:event e)))
                        (:id e)))
                    edges)]
    (if (seq local)
      local
      (keep (fn [e]
              (when (and (:machine-level? e)
                         (= to*    (:to-path e))
                         (= event* (:event e)))
                (:id e)))
            edges))))

(defn- parallel-transition-fired-ids
  "Fired-edge ids for ONE PARALLEL multi-region transition (rf2-8ncxrf).

  A `:type :parallel` machine's snapshot `:state` is a region-MAP — one
  active leaf per orthogonal region (Spec 005 §Parallel regions). A single
  external event (e.g. `[:hvac/power-cycle]`) fires transitions in N
  regions at once, but the runtime emits ONE `:rf.machine/transition`
  whose `:before` / `:after` carry the WHOLE composite region-map (per
  `lifecycle_fx.registration/commit-or-finalize`). So the fired EDGES are
  the per-region edges of every region that CHANGED — derived from the
  before/after region-map, NOT from a single (from, to) pair.

  `project-parallel` (machines-viz `chart.layout`) region-SCOPES each
  region's edges: an edge's `:from-path` / `:to-path` are the IN-REGION
  paths (e.g. `[:idle]` → `[:running]`) and its `:source` is
  `(region-scoped-id region from-path)` — the SAME injective id-scheme
  `highlight-ids` resolves a region-map against (rf2-wnzha). Edges carry
  no `:region` key, so two regions sharing a state NAME would collide on
  the `(from, to, event)` triple; we disambiguate by matching the edge's
  region-scoped `:source` against `(region-scoped-id region from-path)`,
  guaranteeing each fired id belongs to the region that actually moved.

  Only regions whose `(from ≠ to)` contribute — an event that moves some
  regions and leaves others resting lights exactly the moved regions.
  Per-region `from` / `to` are coerced through `normalise-path` (a region
  value is a keyword or in-region vector). Returns a seq of ids."
  [edges before-map after-map event*]
  (when event*
    (mapcat
      (fn [[region region-before]]
        (let [region-after (get after-map region)
              from*        (normalise-path region-before)
              to*          (normalise-path region-after)]
          ;; Skip a region that did not move, or whose paths don't coerce.
          (when (and from* to* (not= region-before region-after))
            (let [scoped-source (chart-layout/region-scoped-id region from*)]
              (keep (fn [e]
                      (when (and (= from*         (:from-path e))
                                 (= to*           (:to-path e))
                                 (= event*        (:event e))
                                 (= scoped-source (:source e)))
                        (:id e)))
                    edges)))))
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
                 ;; PARALLEL multi-region: derive from the region-maps so
                 ;; every region that moved this event lights its edge.
                 (parallel-transition-fired-ids
                   edges
                   (if (map? before-raw) before-raw {})
                   (if (map? after-raw) after-raw {})
                   event*)
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
  machine-id rides under `:tags :machine-id` (machines ·
  transition.cljc `evaluate-guard` `trace/emit!`)."
  [ev machine-id]
  (and (= :rf.machine/guard-evaluated (:operation ev))
       (or (nil? machine-id)
           (= machine-id (get-in ev [:tags :machine-id])))))

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
  :outcome :fail/:threw :input {:event …}}` carrying the NAMED guard,
  so the blocked-edge match is EXACT — `(event, guard)` uniquely picks
  the declining candidate even within a guarded fork (the precision the
  bare transition trace lacks; see `extract-fired-edge-ids`' loose
  `(from, to, event)` match).

  The returned ids are the EXACT ids the live MachineChart mints: the
  definition is projected through `chart.layout/project-definition` and
  each fail/threw guard trace's `(event, guard-ref)` is matched against
  the projected edges' `:event` / `:guard`. The guard refs compare with
  `=` because both the trace's `:guard-id` and the edge's `:guard` are
  the SAME user-declared ref off the SAME definition (keyword or fn).

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
                   event* (guard-event-from-trace ev)]
               ;; A blocked candidate is uniquely (event, guard): the
               ;; named guard discriminates which arm of a guarded fork
               ;; declined. Match every projected edge carrying that
               ;; same (event, guard) pair — typically exactly one. An
               ;; edge with no guard cannot be the blocked candidate, so
               ;; the `(some? guard*)` precondition keeps a guardless
               ;; trace (which the runtime never emits — `evaluate-guard`
               ;; skips the synthesised always-true guard) from lighting
               ;; every same-event edge.
               (when (and (some? guard*) event*)
                 (keep (fn [e]
                         (when (and (= event* (:event e))
                                    (= guard* (:guard e)))
                           (:id e)))
                       edges)))))
         set)))
