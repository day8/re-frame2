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

  ## Transition-source slots (rf2-ezqpm)

  `collect-edges` walks THREE transition slots on every state node
  (Spec 005 first-class — pre-rf2-ezqpm only `:on` was inspected,
  dropping `:always` + `:after` entirely):

    - `(:on    state-node)` — event-triggered transitions.
    - `(:after state-node)` — delay-fired transitions. One edge per
                              `[delay spec]` entry; edge carries
                              `:after <delay>` + label `⌚ <delay>ms`.
    - `(:always state-node)` — eventless / transient transitions.
                              One edge per guarded candidate (the
                              vector-of-maps fork grammar); edge
                              carries `:always? true` + label `∞`.

  Glyph conventions match `machines-viz/chart.layout/event-segment`
  (rf2-a2b55 — Stately graph view). Downstream consumers can split
  by transition-kind via the `:after` / `:always?` fields on each
  edge (also surfaced into the projected `:data`).

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
    3. nil — no current-state overlay rendered.

  ## Trace → state derivation

  Deriving the current-state path / fired-edge ids FROM trace events
  (the overlay's other half) is a distinct concern and lives in
  `day8.re-frame2-xray.panels.machines.trace-state` (rf2-8jzm1) — the
  single source of truth for that derivation. This ns only borrows
  `trace-state/normalise-path` (the shared path coercion); callers that
  want the trace→state helpers require `trace-state` directly."
  (:require [day8.re-frame2-machines-viz.chart.layout :as chart-layout]
            [day8.re-frame2-xray.panels.machines.trace-state :as trace-state]))

;; ---- state walking ------------------------------------------------------

(def ^:private normalise-path
  "Coerce a path-spec into a path vector. Delegates to the canonical
  `trace-state/normalise-path` (single source of truth)."
  trace-state/normalise-path)

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

(defn- event-segment-str
  "Render the leading event segment of an edge label per the Stately
  graph view convention. rf2-2678t — delegate to the canonical
  `machines-viz/chart.layout/event-segment` so xray + machines-viz
  agree on every shape (no per-tool reimplementation drift):

    - `:* (any)` wildcard           — Spec 005 §Wildcard arm
    - `:after <delay>` transition   — `⌚ <delay>ms` (clock glyph)
    - `:always`        transition   — `∞`           (infinity glyph)
    - regular event keyword          — `event-id` (ns preserved)

  Pre-rf2-2678t (PR #2205) xray's local copy stripped the `:*` arm
  and fell through to `seg-name`, which produced bare `\"*\"` — a
  glyph drift away from machines-viz's `\"* (any)\"`. Importing the
  canonical fn removes the entire drift class (rf2-2678t)."
  [edge]
  (chart-layout/event-segment edge))

(defn- edge-label-str
  "Compose an xstate-stately edge label: `event [guard] / action`.
  Brackets / slash appear ONLY when the segment is present, matching
  `machines-viz`'s `chart.layout/edge-label` convention. Fn-safe via
  `seg-name`. The event segment uses the Stately glyph for `:after`
  and `:always` (rf2-a2b55 · rf2-ezqpm) — `event-segment-str`."
  [edge]
  (let [{:keys [guard action]} edge]
    (str (event-segment-str edge)
         (when guard  (str " [" (seg-name guard) "]"))
         (when action (str " / " (seg-name action))))))

(defn- target-of
  "Pull the target keyword off a transition spec. The spec may be a
  bare keyword (`:populated`) or a map (`{:target :populated …}`).
  Returns nil for any other shape."
  [spec]
  (cond
    (keyword? spec) spec
    (map? spec)     (:target spec)
    :else           nil))

(defn- spec-attrs
  "Pull `:guard` / `:action` off a transition spec map (or nil for a
  bare-keyword spec)."
  [spec]
  (when (map? spec)
    {:guard  (:guard spec)
     :action (:action spec)}))

(defn- on-edges
  "Emit one edge per `(:on state-node)` entry. Event-triggered
  transitions — the legacy projection's only edge kind."
  [parent-path from-path on]
  (for [[event-id target-spec] on
        :let [target-id (target-of target-spec)
              {:keys [guard action]} (spec-attrs target-spec)
              to-path   (when target-id
                          (conj (vec parent-path) target-id))]
        :when to-path]
    (let [edge {:from   from-path
                :to     to-path
                :event  event-id
                :guard  guard
                :action action}]
      (assoc edge
        :id    (str (node-id-for-path from-path) "__"
                    (node-id-for-path to-path) "__"
                    (name event-id))
        :label (edge-label-str edge)))))

(defn- after-edges
  "Emit one edge per `(:after state-node)` entry — Spec 005 §Delayed
  `:after` transitions (rf2-ezqpm). `after-map` is `{delay-ms spec
  ...}`; each delay schedules an independent timer. Edge label uses the
  Stately clock glyph + `<delay>ms` (rf2-a2b55)."
  [parent-path from-path after-map]
  (for [[delay spec] after-map
        :let [target-id (target-of spec)
              {:keys [guard action]} (spec-attrs spec)
              to-path   (when target-id
                          (conj (vec parent-path) target-id))]
        :when to-path]
    (let [edge {:from   from-path
                :to     to-path
                :event  (keyword (str "after-" delay))
                :after  delay
                :guard  guard
                :action action}]
      (assoc edge
        :id    (str (node-id-for-path from-path) "__"
                    (node-id-for-path to-path) "__"
                    "after-" delay)
        :label (edge-label-str edge)))))

(defn- always-edges
  "Emit one edge per `(:always state-node)` entry — Spec 005 §Eventless
  `:always` transitions (rf2-ezqpm). `always-spec` may be a bare keyword
  / map / vector-of-maps (the guarded-fork grammar). Edge label uses the
  Stately infinity glyph (rf2-a2b55).

  Multiple guarded candidates fork into multiple edges sharing the same
  from→to event segment — the per-candidate ordinal disambiguates the
  edge id so xyflow keeps every branch (mirrors `parse-flat`'s ordinal
  scheme in `machines-viz/chart.layout`)."
  [parent-path from-path always-spec]
  (let [candidates (cond
                     (nil? always-spec)     []
                     (keyword? always-spec) [always-spec]
                     (map? always-spec)     [always-spec]
                     (vector? always-spec)  always-spec
                     :else                  [])
        base-id    (str (node-id-for-path from-path) "__always__")]
    (->> candidates
         (keep-indexed
           (fn [idx spec]
             (let [target-id (target-of spec)
                   {:keys [guard action]} (spec-attrs spec)
                   to-path   (when target-id
                               (conj (vec parent-path) target-id))]
               (when to-path
                 (let [edge {:from    from-path
                             :to      to-path
                             :event   :always
                             :always? true
                             :guard   guard
                             :action  action}]
                   (assoc edge
                     :id    (str base-id
                                 (node-id-for-path to-path)
                                 (when (pos? idx) (str "__" idx)))
                     :label (edge-label-str edge)))))))
         vec)))

(defn- collect-edges
  "Walk a `{state-id state-node}` map; emit edges. Each edge is
  `{:from :to :label :id :event :guard :action [:after _] [:always? _]}`.
  Reads three transition-source slots on every state (rf2-ezqpm —
  previously only `:on` was inspected, dropping `:always` + `:after`
  entirely):

    - `(:on state-node)`     — event-triggered transitions (legacy).
    - `(:after state-node)`  — delay-fired transitions (Spec 005
                               §Delayed `:after`). One edge per entry
                               with `:after <delay>` set; label is
                               `⌚ <delay>ms` (Stately convention,
                               rf2-a2b55).
    - `(:always state-node)` — eventless transitions (Spec 005
                               §Eventless `:always`). One edge per
                               guarded candidate with `:always? true`;
                               label is `∞` (Stately convention,
                               rf2-a2b55).

  Compound substates recurse. Map target-specs surface their `:guard` /
  `:action` into the xstate-style label. Targets resolve relative to
  the source's parent path; self-transitions (target == source) are
  emitted."
  [parent-path state-map]
  (when (map? state-map)
    (vec
      (mapcat
        (fn [[state-id state-node]]
          (let [from-path (conj (vec parent-path) state-id)
                own       (concat
                            (on-edges     parent-path from-path (:on    state-node))
                            (after-edges  parent-path from-path (:after state-node))
                            (always-edges parent-path from-path (:always state-node)))
                nested    (when (:states state-node)
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
                :data     (cond-> {:kind  kind
                                   :event (:event e)}
                            ;; rf2-ezqpm — `:always?` / `:after` flags
                            ;; surface on the projected edge so the
                            ;; downstream renderer (or a test asserting
                            ;; the transition-category split) can read
                            ;; the kind without re-parsing the label.
                            (:always? e) (assoc :always? true)
                            (:after   e) (assoc :after (:after e)))}))
           edges)}))
