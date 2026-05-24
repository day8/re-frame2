(ns day8.re-frame2-xray.panels.machines.topology
  "Pure-data projector: re-frame2 machine definition → xyflow
  nodes/edges (rf2-uwvyj · spec/021 §6 + §17.4).

  ## Scope

  One-way walker: takes a machine definition (the `{:initial :states
  ...}` map registered via `rf/reg-machine`), an optional
  current-state path (the `:to` of the most recent transition trace
  in the focused epoch), and an optional set of `:fired-this-epoch`
  edge-keys; returns `{:nodes [...] :edges [...]}` suitable for
  handing to the xyflow wrapper.

  ## Pure

  No DOM, no React, no re-frame side effects. Every input is data;
  every output is data. Tests live at
  `tools/xray/test/day8/re_frame2_xray/panels/machines/
  topology_cljs_test.cljs`.

  ## Node kinds (per spec §17.4.2)

    - `:current` — the state most recently entered (per the focused
      epoch's `:rf.machine/transition` trace; `current-state-path`
      arg).
    - `:final`   — state with `:final? true` in the definition.
    - `:standard` — every other state.
    - `:region`  — reserved for parallel-region containers (not
      surfaced in v1; layout walks the first region only — matches
      the existing chart-layout posture).

  ## Edge kinds (per spec §17.4.3)

    - `:fired-this-epoch`     — edge keys in the `fired-edges` set
                                are rendered with the animated mode-
                                accent stroke (orange Dynamic / cyan
                                Static, rf2-ad7zx).
    - `:registered-traversed` — edge keys in the `traversed-edges`
                                set (most-recent traversal in the
                                buffer, but NOT this epoch).
    - `:registered`           — every other edge (registered, never
                                traversed).

  ## Layout posture

  Initial v1 uses a deterministic top-to-bottom grid lift from the
  existing `chart-layout/layout` (the SVG chart's pure-CLJS layout
  fn) so the xyflow render's node positions visually align with the
  legacy chart. xyflow's `dagre`-based `getLayoutedElements` helper
  could ship as a follow-on bead (spec §17.4.4 calls for `rankdir:
  LR` ultimately); for v1 the simple grid keeps the projection
  self-contained + JVM-portable.

  ## current-state-path resolution

  Per spec §17.4.1, the `current ●` overlay marks the state node the
  machine is currently in. Resolution priority (caller's
  responsibility):

    1. The `:to` of the most recent `:rf.machine/transition` trace
       in the focused epoch.
    2. The `:state` field of the machine's live snapshot map.
    3. nil — no current-state overlay rendered."
  (:require [day8.re-frame2-machines-viz.chart.layout :as chart-layout]))

;; ---- state walking ------------------------------------------------------

(defn- normalise-path
  "Coerce a path-spec into a path vector. Path-specs may be:

    - a vector of keywords `[:populated]` or `[:level-1 :level-2]`
    - a single keyword `:populated` (becomes `[:populated]`)
    - nil (returns nil)
    - any other shape (returns nil)"
  [path]
  (cond
    (nil? path)       nil
    (keyword? path)   [path]
    (vector? path)    (when (every? keyword? path) path)
    (seq? path)       (when (every? keyword? path) (vec path))
    :else             nil))

(defn- node-id-for-path
  "Stable string id for a state path. DELEGATES to the canonical
  machines-viz `chart.layout/node-id` — the single source of truth for
  the machine node-id scheme (rf2-ee38b.21 · rf2-m8kod).

  Why delegate rather than re-implement: the old local body used the
  non-injective `[^a-zA-Z0-9_] → _` collapse, which MERGED `:a/b`,
  `:a-b`, and `:a_b` onto the same id. The Xray topology overlay and
  the live MachineChart must address nodes identically, so any future
  'highlight fired edges on the live chart' wiring (rf2-qeemm/B8) would
  silently mis-target with two divergent schemes. `chart.layout/node-id`
  is the injective hex-escape scheme (`_<hex>` per non-alnum char, `_2f`
  namespace separator, `__` path join); using it directly keeps the two
  renderers in lockstep by construction."
  [path]
  (chart-layout/node-id path))

(defn- walk-states
  "Walk a `{state-id state-node}` map under `parent-path`; emit a flat
  `[{:path :label :final? :initial? :compound?} ...]` seq. Compound
  substates ARE recursively flattened (the parent emits as one node;
  every nested child emits too). A compound parent's `:initial` child is
  flagged `:initial? true` so the marker surfaces at every level."
  [parent-path state-map]
  (when (map? state-map)
    (vec
      (mapcat
        (fn [[state-id state-node]]
          (let [path (conj (vec parent-path) state-id)
                self {:path     path
                      :label    (name state-id)
                      :final?   (boolean (:final? state-node))
                      :initial? (boolean (:initial? state-node))
                      :compound? (boolean (:states state-node))}
                init-key     (:initial state-node)
                raw-children (when (:states state-node)
                               (walk-states path (:states state-node)))
                children     (if init-key
                               (mapv (fn [c]
                                       (if (= (:path c) (conj path init-key))
                                         (assoc c :initial? true)
                                         c))
                                     raw-children)
                               raw-children)]
            (cons self children)))
        state-map))))

(defn- seg-name
  "Render a guard / action / event label segment to a string WITHOUT
  throwing on fn values. `cljs.core/name` blows up on fns
  (`Doesn't support name: function …` — rf2-ujra6), and re-frame2
  machines may inline a fn guard / action (`:guard (fn …)`), so coerce
  defensively: namespaced keywords keep their namespace, fns surface
  their `:name` meta (or `fn` when anonymous)."
  [v]
  (cond
    (nil? v)     nil
    (keyword? v) (if-let [n (namespace v)] (str n "/" (name v)) (name v))
    (symbol? v)  (str v)
    (string? v)  v
    (fn? v)      (or (some-> v meta :name str) "fn")
    :else        (str v)))

(defn- edge-label-str
  "Compose an xstate-stately edge label: `event [guard] / action`.
  Brackets / slash appear ONLY when the segment is present, matching
  `machines-viz`'s `chart.layout/edge-label` convention. Fn-safe via
  `seg-name`."
  [event-id guard action]
  (str (seg-name event-id)
       (when guard  (str " [" (seg-name guard) "]"))
       (when action (str " / " (seg-name action)))))

(defn- collect-edges
  "Walk a `{state-id state-node}` map; emit edges. Each edge is
  `{:from :to :label :id :event :guard :action}`. Reads the `:on` map
  (event-id → target-spec) on every state INCLUDING compound substates
  (recurses). Map target-specs surface their `:guard` / `:action` into
  the xstate-style label. Targets resolve relative to the source's
  parent path; self-transitions (target == source) are emitted."
  [parent-path state-map]
  (when (map? state-map)
    (vec
      (mapcat
        (fn [[state-id state-node]]
          (let [from-path (conj (vec parent-path) state-id)
                own (when-let [on (:on state-node)]
                      (for [[event-id target-spec] on
                            :let [target-id (cond
                                              (keyword? target-spec) target-spec
                                              (map? target-spec)     (:target target-spec)
                                              :else                  nil)
                                  guard     (when (map? target-spec) (:guard target-spec))
                                  action    (when (map? target-spec) (:action target-spec))
                                  to-path   (when target-id
                                              (conj (vec parent-path) target-id))]
                            :when to-path]
                        {:id    (str (node-id-for-path from-path) "__"
                                     (node-id-for-path to-path) "__"
                                     (name event-id))
                         :from  from-path
                         :to    to-path
                         :label (edge-label-str event-id guard action)
                         :event event-id
                         :guard guard
                         :action action}))
                nested (when (:states state-node)
                         (collect-edges from-path (:states state-node)))]
            (concat own nested)))
        state-map))))

;; ---- definition → graph -------------------------------------------------

(defn parse-definition
  "Project a `definition` machine-spec map into a flat graph
  `{:nodes [...] :edges [...] :initial-path ...}`. Mirrors the
  existing `chart-layout/parse-definition`'s posture but is
  self-contained (no machines-viz dep) so the xyflow path is fully
  isolated from the SVG chart's evolution."
  [definition]
  (cond
    (nil? definition)
    {:nodes [] :edges [] :initial-path nil}

    (= :parallel (:type definition))
    ;; Project EVERY region's states + edges (concatenated, flat). Each
    ;; region's own `:initial` flags survive; the parallel root has no
    ;; single initial path.
    (let [per (map (fn [[_region-id region]] (parse-definition region))
                   (:regions definition))]
      {:nodes        (vec (mapcat :nodes per))
       :edges        (vec (mapcat :edges per))
       :initial-path nil})

    :else
    (let [{:keys [initial states]} definition
          base-nodes   (walk-states [] states)
          initial-path (when initial [initial])
          nodes        (mapv (fn [n]
                               (if (= (:path n) initial-path)
                                 (assoc n :initial? true)
                                 n))
                             base-nodes)
          edges        (collect-edges [] states)]
      {:nodes        nodes
       :edges        edges
       :initial-path initial-path})))

;; ---- grid layout (pure) -------------------------------------------------

(def ^:private grid-x-step 180)
(def ^:private grid-y-step 90)
(def ^:private grid-margin 40)

(defn- grid-positions
  "Deterministic top-to-bottom grid layout. Nodes from the same
  level cluster horizontally; the initial state goes in row 0, all
  reachable states in subsequent rows by BFS rank. Returns
  `{path {:x :y}}`."
  [nodes edges initial-path]
  (let [adj (reduce (fn [m {:keys [from to]}]
                      (update m from (fnil conj #{}) to))
                    {}
                    edges)
        ;; BFS rank assignment
        bfs (fn []
              (loop [queue (if initial-path [[initial-path 0]] [])
                     seen  (if initial-path {initial-path 0} {})]
                (if-let [[path rank] (first queue)]
                  (let [next-queue (subvec (vec queue) 1)
                        children   (get adj path #{})
                        new-items  (for [c children
                                         :when (not (contains? seen c))]
                                     [c (inc rank)])]
                    (recur (into next-queue new-items)
                           (merge seen (into {} new-items))))
                  ;; Backfill unreached
                  (reduce (fn [acc {:keys [path]}]
                            (if (contains? acc path) acc (assoc acc path 0)))
                          seen
                          nodes))))
        ranks (bfs)
        ;; Group by rank, sort by stable id within rank
        by-rank (->> nodes
                     (sort-by #(node-id-for-path (:path %)))
                     (group-by #(get ranks (:path %) 0))
                     (into (sorted-map)))]
    (reduce
      (fn [acc [rank ranked-nodes]]
        (let [y (+ grid-margin (* rank grid-y-step))]
          (reduce
            (fn [acc2 [idx n]]
              (let [x (+ grid-margin (* idx grid-x-step))]
                (assoc acc2 (:path n) {:x x :y y})))
            acc
            (map-indexed vector ranked-nodes))))
      {}
      by-rank)))

;; ---- node-kind resolution (per spec §17.4.2) ----------------------------

(defn node-kind
  "Resolve the xyflow node kind for a node given the current-state path
  + (optionally) the focused transition's FROM-state path. Precedence:
  `:current` > `:from` > `:final` > `:standard`.

  Per spec/021 §6.2 Case C (Figma reconcile · rf2-ad7zx.10) the source
  state of the focused fired transition renders as the dashed/dim
  `:from` circle; the TO / live state renders as the `:current`
  double-circle (which therefore wins when a state is BOTH the FROM and
  the current — a self-transition reads as active, not dimmed).

  The 2-arity (no `from-state-path`) keeps the pre-Case-C precedence
  (`:current` > `:final` > `:standard`) so existing callers are
  unchanged."
  ([node current-state-path]
   (node-kind node current-state-path nil))
  ([{:keys [path final?]} current-state-path from-state-path]
   (let [cur  (normalise-path current-state-path)
         from (normalise-path from-state-path)]
     (cond
       (and cur  (= cur path))  :current
       (and from (= from path)) :from
       final?                   :final
       :else                    :standard))))

;; ---- edge-kind resolution (per spec §17.4.3) ----------------------------

(defn edge-kind
  "Resolve the xyflow edge kind for an edge given the per-epoch
  `fired-edge-ids` (edge ids fired in the focused epoch) +
  `traversed-edge-ids` (edges traversed at some point in the
  buffer's history, but NOT this epoch). Precedence:
  `:fired-this-epoch` > `:registered-traversed` > `:registered`."
  [{:keys [id]} fired-edge-ids traversed-edge-ids]
  (cond
    (contains? (or fired-edge-ids #{}) id)     :fired-this-epoch
    (contains? (or traversed-edge-ids #{}) id) :registered-traversed
    :else                                      :registered))

;; ---- public projection --------------------------------------------------

(defn project
  "Project a machine definition (+ optional overlay context) into
  xyflow `{:nodes [...] :edges [...]}`. Returns CLJS maps; the
  wrapper's `coerce-nodes` / `coerce-edges` turn them into JS at the
  React boundary.

  Args (map):

    :definition          — the machine definition map (required;
                           `nil` returns an empty graph).
    :current-state-path  — the path of the state the machine is
                           currently in (optional; precedence rules
                           in the ns docstring).
    :from-state-path     — the path of the focused fired transition's
                           SOURCE state (optional). Marks that node as
                           the dashed/dim `:from` circle per spec/021
                           §6.2 Case C (Figma reconcile · rf2-ad7zx.10).
                           `:current` wins when a state is both FROM and
                           current (self-transition reads as active).
    :fired-edge-ids      — set of edge-ids fired in the focused
                           epoch (optional).
    :traversed-edge-ids  — set of edge-ids traversed at some point
                           in the buffer (optional).
    :node-style-fn       — `(fn [kind] {style-map})`. Defaults to a
                           no-op; the view layer typically passes
                           `xyflow-style/node-style`.
    :edge-style-fn       — `(fn [kind] {style-map})`. Defaults to
                           no-op; view passes `xyflow-style/edge-
                           style`.
    :edge-animated-fn    — `(fn [kind] boolean)`. Defaults to no-op;
                           view passes `xyflow-style/animated?`.

  Style fns are injected (not called inline) so this ns stays pure
  data — view-only deps live in the view ns."
  [{:keys [definition current-state-path from-state-path fired-edge-ids
           traversed-edge-ids node-style-fn edge-style-fn edge-animated-fn]
    :or   {node-style-fn    (fn [_kind] nil)
           edge-style-fn    (fn [_kind] nil)
           edge-animated-fn (fn [_kind] false)}}]
  (let [{:keys [nodes edges]} (parse-definition definition)
        positions             (grid-positions nodes edges
                                              (some-> definition :initial vector))]
    {:nodes
     (mapv (fn [n]
             (let [kind (node-kind n current-state-path from-state-path)
                   id   (node-id-for-path (:path n))
                   pos  (get positions (:path n) {:x grid-margin :y grid-margin})]
               {:id       id
                :type     "default"
                :position pos
                :data     {:label   (:label n)
                           :kind    kind
                           :initial (boolean (:initial? n))
                           :path    (:path n)}
                :style    (node-style-fn kind)
                :draggable false
                :selectable false}))
           nodes)

     :edges
     (mapv (fn [e]
             (let [kind  (edge-kind e fired-edge-ids traversed-edge-ids)
                   style (edge-style-fn kind)]
               {:id       (:id e)
                :source   (node-id-for-path (:from e))
                :target   (node-id-for-path (:to e))
                :label    (:label e)
                :style    style
                :animated (edge-animated-fn kind)
                ;; rf2-5qsxo — arrowhead. xyflow's default edge type
                ;; renders a `<marker>` def when `:markerEnd` is present;
                ;; the colour tracks the edge stroke (per-kind via the
                ;; injected `edge-style-fn`) so the arrow reads as part of
                ;; the same line. nil-safe: falls back to the kind's
                ;; nominal stroke colour when the style fn omits one.
                :markerEnd {:type   "arrowclosed"
                            :color  (or (:stroke style) "currentColor")
                            :width  18
                            :height 18}
                :data     {:kind  kind
                           :event (:event e)}}))
           edges)}))

;; ---- focused-epoch overlay helpers --------------------------------------

(defn extract-fired-edge-ids
  "Given a vector of `:rf.machine/transition` trace events for the
  focused epoch + the machine-id of interest, return the set of
  edge-ids whose `(from → to via event)` matches a transition. Pure
  fn. The match is loose — transitions that don't carry an explicit
  event-id are excluded (they can't be matched to a `:on` edge)."
  [trace-events machine-id]
  (->> (or trace-events [])
       (filter (fn [ev]
                 (and (= :rf.machine/transition (:operation ev))
                      (or (nil? machine-id)
                          (= machine-id (get-in ev [:tags :machine-id]))))))
       (keep (fn [ev]
               (let [from  (or (:from ev) (get-in ev [:payload :from]))
                     to    (or (:to ev)   (get-in ev [:payload :to]))
                     event (or (:event ev) (get-in ev [:payload :event]))
                     from* (normalise-path from)
                     to*   (normalise-path to)
                     ev*   (cond
                             (keyword? event) event
                             (vector? event)  (first event)
                             :else            nil)]
                 (when (and from* to* ev*)
                   (str (node-id-for-path from*)
                        "__"
                        (node-id-for-path to*)
                        "__"
                        (name ev*))))))
       set))

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
  (let [matching (filter (fn [ev]
                           (and (= :rf.machine/transition (:operation ev))
                                (or (nil? machine-id)
                                    (= machine-id (get-in ev [:tags :machine-id])))))
                         (or trace-events []))]
    (when-let [last-ev (last matching)]
      (from-path-from-trace last-ev))))

(defn- to-path-from-trace
  "Pull the `:to` state path off a `:rf.machine/transition` trace
  event. Tolerant of three shapes:

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

(defn current-state-from-traces
  "Resolve the current-state path for `machine-id` from the
  `:rf.machine/transition` trace events vector. Returns the `:to`
  path of the most recent matching trace, or nil. Pure.

  Reads modern (`:tags :after :state`) + legacy (`:to`,
  `:payload :to`) shapes — see `to-path-from-trace`."
  [trace-events machine-id]
  (let [matching (filter (fn [ev]
                           (and (= :rf.machine/transition (:operation ev))
                                (or (nil? machine-id)
                                    (= machine-id (get-in ev [:tags :machine-id])))))
                         (or trace-events []))]
    (when-let [last-ev (last matching)]
      (to-path-from-trace last-ev))))

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
