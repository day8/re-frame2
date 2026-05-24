(ns day8.re-frame2-xray.panels.machines.trace-state
  "Single source of truth for deriving machine STATE + fired-edge ids
  from `:rf.machine/transition` trace events (rf2-8jzm1 · spec/021 §6 +
  machines-viz `001-Topology-Parity.md` §4.4 / G3).

  ## Scope

  Pure data, no DOM / React / re-frame side effects. Every input is
  data; every output is data. The four public fns answer two questions
  the topology overlay asks of a buffer of trace events:

    1. **Which state is the machine in?** — `current-state-from-traces`
       (focused epoch), `current-state-from-epoch-history` (walk-back
       fallback), and `from-state-from-traces` (the focused
       transition's SOURCE, for the dashed/dim `:from` circle per
       spec/021 §6.2 Case C).

    2. **Which edges fired this epoch?** — `extract-fired-edge-ids`,
       returning machines-viz-CANONICAL edge ids (see below).

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
  `chart.layout/parse-definition` and looks the ids up off the projected
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

(defn- event-from-trace
  "Pull the event keyword off a `:rf.machine/transition` trace event.
  The runtime stamps the inner event under `:event` (a keyword, or a
  `[event-id & args]` vector). Legacy fixtures may carry it under
  `:payload :event`. Returns the bare event keyword or nil."
  [ev]
  (let [event (or (:event ev) (get-in ev [:payload :event]))]
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

(defn extract-fired-edge-ids
  "Given a machine `definition`, a vector of `:rf.machine/transition`
  trace events for the focused epoch, and the `machine-id` of interest,
  return the SET of machines-viz-canonical edge ids that fired this
  epoch. Pure fn — JVM-runnable.

  The returned ids are the EXACT ids the live MachineChart mints: the
  definition is projected through `chart.layout/parse-definition` and
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

  Returns `#{}` for a nil/empty definition or no matching transitions."
  [definition trace-events machine-id]
  (let [edges (:edges (chart-layout/parse-definition definition))]
    (->> (machine-transitions trace-events machine-id)
         (mapcat
           (fn [ev]
             (let [from*  (from-path-from-trace ev)
                   to*    (to-path-from-trace ev)
                   event* (event-from-trace ev)]
               (when (and from* to* event*)
                 (keep (fn [e]
                         (when (and (= from*  (:from-path e))
                                    (= to*    (:to-path e))
                                    (= event* (:event e)))
                           (:id e)))
                       edges)))))
         set)))
