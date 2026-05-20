(ns day8.re-frame2-causa.panels.machines.topology
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
  `tools/causa/test/day8/re_frame2_causa/panels/machines/
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
                                are rendered with the animated violet
                                stroke.
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
  (:require [clojure.string :as str]))

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
  "Stable string id for a state path. Matches `chart-layout/node-id`'s
  shape so node-ids resolve consistently across renderers."
  [path]
  (->> path
       (map (fn [p]
              (if (keyword? p)
                (if-let [ns (namespace p)]
                  (str ns "_" (name p))
                  (name p))
                (str p))))
       (str/join "__")
       (#(str/replace % #"[^a-zA-Z0-9_]" "_"))))

(defn- walk-states
  "Walk a `{state-id state-node}` map under `parent-path`; emit
  `[{:path :final? :label} ...]`. Compound + parallel states are NOT
  recursively flattened in v1 (parent is emitted as a single node;
  children stay invisible). Same posture as the existing
  chart-layout's `:depth 0` slice."
  [parent-path state-map]
  (when (map? state-map)
    (mapv (fn [[state-id state-node]]
            (let [path (conj (vec parent-path) state-id)]
              {:path     path
               :label    (name state-id)
               :final?   (boolean (:final? state-node))
               :initial? (boolean (:initial? state-node))}))
          state-map)))

(defn- collect-edges
  "Walk a `{state-id state-node}` map; emit edges. Each edge is
  `{:from :to :label :id}`. v1 reads `:on` map (event-id → target-
  spec) on the immediate children of `:states`. Targets are coerced
  to `[target-state-id]` paths (single-level). Self-transitions
  (target = source) are emitted; the renderer can decide to render
  or suppress."
  [parent-path state-map]
  (when (map? state-map)
    (vec
      (mapcat
        (fn [[state-id state-node]]
          (let [from-path (conj (vec parent-path) state-id)]
            (when-let [on (:on state-node)]
              (for [[event-id target-spec] on
                    :let [target-id (cond
                                      (keyword? target-spec) target-spec
                                      (map? target-spec)     (:target target-spec)
                                      :else                  nil)
                          to-path   (when target-id [target-id])]
                    :when to-path]
                {:id    (str (node-id-for-path from-path)
                             "__"
                             (node-id-for-path to-path)
                             "__"
                             (name event-id))
                 :from  from-path
                 :to    to-path
                 :label (name event-id)
                 :event event-id}))))
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
    ;; v1: project the first region only — matches chart-layout's
    ;; existing posture. A follow-on bead surfaces full parallel
    ;; rendering using xyflow's group/parent-node mechanic.
    (let [[_region-id region] (first (:regions definition))]
      (parse-definition region))

    :else
    (let [{:keys [initial states]} definition
          nodes        (walk-states [] states)
          initial-path (when initial [initial])
          edges        (collect-edges [] states)]
      {:nodes        (vec nodes)
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
  "Resolve the xyflow node kind for a node given the current-state
  path. Precedence: `:current` > `:final` > `:standard`."
  [{:keys [path final?]} current-state-path]
  (let [cur (normalise-path current-state-path)]
    (cond
      (and cur (= cur path)) :current
      final?                 :final
      :else                  :standard)))

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
  [{:keys [definition current-state-path fired-edge-ids traversed-edge-ids
           node-style-fn edge-style-fn edge-animated-fn]
    :or   {node-style-fn    (fn [_kind] nil)
           edge-style-fn    (fn [_kind] nil)
           edge-animated-fn (fn [_kind] false)}}]
  (let [{:keys [nodes edges]} (parse-definition definition)
        positions             (grid-positions nodes edges
                                              (some-> definition :initial vector))]
    {:nodes
     (mapv (fn [n]
             (let [kind (node-kind n current-state-path)
                   id   (node-id-for-path (:path n))
                   pos  (get positions (:path n) {:x grid-margin :y grid-margin})]
               {:id       id
                :type     "default"
                :position pos
                :data     {:label (:label n)
                           :kind  kind
                           :path  (:path n)}
                :style    (node-style-fn kind)
                :draggable false
                :selectable false}))
           nodes)

     :edges
     (mapv (fn [e]
             (let [kind (edge-kind e fired-edge-ids traversed-edge-ids)]
               {:id       (:id e)
                :source   (node-id-for-path (:from e))
                :target   (node-id-for-path (:to e))
                :label    (:label e)
                :style    (edge-style-fn kind)
                :animated (edge-animated-fn kind)
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
