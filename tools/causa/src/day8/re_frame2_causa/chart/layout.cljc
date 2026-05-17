(ns day8.re-frame2-causa.chart.layout
  "Pure-data layout primitive for the Causa Machine Inspector chart
  (rf2-2tkza, Phase 1).

  Per `tools/causa/spec/003-Machine-Inspector.md` §Architectural
  posture the chart-layout primitive lives Causa-internal at
  `tools/causa/src/day8/re_frame2_causa/chart/{layout,svg,interaction}.cljc`.
  This ns is the `layout` half: data → data, JVM-runnable, fully
  testable from `clojure -M:test`. The `svg` half consumes the
  positioned graph and emits hiccup.

  ## What this does

  Walks a machine definition (the map returned by `(rf/machine-meta
  machine-id)` — see `spec/005-StateMachines.md` §Transition table
  grammar) and produces a positioned graph:

      {:nodes      [{:id <kw>          ;; sanitised id, unique per path
                     :label <str>      ;; human-readable label
                     :path <vec-of-kw> ;; full hierarchical path
                     :x <int> :y <int>
                     :width <int> :height <int>
                     :initial? <bool>
                     :final? <bool>
                     :depth <int>} ...]
       :edges      [{:from <id> :to <id>
                     :label <str>     ;; event id
                     :guard <kw-or-nil>
                     :points [[x y] [x y]]} ...]
       :width      <int>            ;; viewport width
       :height     <int>            ;; viewport height
       :initial-id <id-or-nil>}     ;; the machine's :initial state id

  ## Layout choice — layered, not ELK

  Spec 003 names ELK.js as the preferred layout engine; rf2-2tkza
  Phase 1 ships a **simple layered layout** instead. ELK is a heavy
  JS bundle (~250KB) and bringing it in for the foundation PR delays
  the user-visible payoff. The layered approach:

    1. **Rank** each node by its depth from the initial state via
       BFS — initial is rank 0, its targets are rank 1, etc. Cycles
       and back-edges don't increase rank (the longest path wins).
    2. **Stratify** nodes into rank-buckets; within each bucket
       sort by id for determinism.
    3. **Place** each rank as a horizontal row, centred; row spacing
       is 100px, node spacing 40px, node size 140x48.
    4. **Route** edges as straight lines from source-bottom to
       target-top (simple — no orthogonal routing, no obstacle
       avoidance). Self-loops render as a small arc above the node
       (handled in the `svg` ns).

  Result: a chart that's readable for the typical 4-12 state machines
  re-frame2 apps register, fast to render, and trivially debuggable.
  Follow-on bead can swap in ELK behind the same data interface.

  ## Compound state handling (v1: flat projection)

  Compound states (`:states {...}` inside a state) project their leaves
  into the same rank-graph at v1. The `:path` slot carries the full
  hierarchical path; the SVG renderer can use `:path` to group leaves
  visually under their parent (a follow-on bead). For Phase 1 the
  flat-with-paths view is enough to surface the user-visible topology.

  ## What this does NOT do (deferred to follow-on beads)

    - Orthogonal edge routing (rf2-2tkza Phase 4 follow-on).
    - Parallel-region layout (`{:type :parallel :regions ...}`) —
      v1 falls back to rendering the first region.
    - `:invoke-all` inline child machines.
    - `:after` countdown ring geometry.
    - Compound-state visual nesting (boxes-within-boxes).
    - Edge-bundling for high-degree nodes."
  (:require [clojure.string :as str]))

;; ---- layout constants ---------------------------------------------------

(def node-width 140)
(def node-height 48)
(def rank-gap 100)             ;; vertical gap between ranks
(def node-gap 40)              ;; horizontal gap between same-rank nodes
(def chart-margin 32)          ;; outer margin around the layout

;; ---- definition walker --------------------------------------------------

(defn- target-path?
  "True when `v` is a grammar vector-path target (Spec 005)."
  [v]
  (and (vector? v)
       (seq v)
       (every? keyword? v)))

(defn- transition-candidates
  "Normalise a transition spec to candidate maps. Mirrors the
  equivalent fn in the mermaid emitter; kept local so this ns has no
  cross-dependency on machines-viz."
  [spec]
  (cond
    (keyword? spec)     [{:target spec}]
    (target-path? spec) [{:target spec}]
    (map? spec)         [spec]
    (vector? spec)      (mapcat transition-candidates spec)
    :else               []))

(defn- parent-path [path]
  (if (seq path)
    (pop path)
    []))

(defn- resolve-target-path
  "Resolve a transition target relative to source-path."
  [source-path target]
  (cond
    (keyword? target)     (conj (parent-path source-path) target)
    (target-path? target) (vec target)
    :else                 nil))

(defn- collect-state-edges
  "Walk a state node and return edge-maps for every statically-
  resolvable transition declared on it. Compound substates recurse."
  [state-path state-node]
  (let [edges-from
        (concat
         (mapcat (fn [[event-id spec]]
                   (keep (fn [candidate]
                           (when-let [tp (resolve-target-path state-path
                                                              (:target candidate))]
                             {:from   state-path
                              :to     tp
                              :event  event-id
                              :guard  (:guard candidate)
                              :action (:action candidate)}))
                         (transition-candidates spec)))
                 (:on state-node))
         (mapcat (fn [[delay spec]]
                   (keep (fn [candidate]
                           (when-let [tp (resolve-target-path state-path
                                                              (:target candidate))]
                             {:from   state-path
                              :to     tp
                              :event  (keyword (str "after-" delay))
                              :after  delay
                              :guard  (:guard candidate)
                              :action (:action candidate)}))
                         (transition-candidates spec)))
                 (:after state-node))
         (mapcat (fn [candidate]
                   (when-let [tp (resolve-target-path state-path
                                                      (:target candidate))]
                     [{:from   state-path
                       :to     tp
                       :event  :always
                       :always? true
                       :guard  (:guard candidate)
                       :action (:action candidate)}]))
                 (transition-candidates (:always state-node))))
        nested
        (mapcat (fn [[child-id child-node]]
                  (collect-state-edges (conj state-path child-id) child-node))
                (:states state-node))]
    (concat edges-from nested)))

(defn- collect-nodes
  "Walk a state-map and return a flat seq of node-maps. Each map
  carries the full path so compound nesting is preserved as data."
  [parent-path state-map]
  (mapcat (fn [[state-id state-node]]
            (let [path (conj parent-path state-id)
                  self {:id       path
                        :path     path
                        :label    (name state-id)
                        :depth    (count parent-path)
                        :initial? (:initial? state-node)
                        :final?   (:final? state-node)
                        :compound? (boolean (:states state-node))
                        :tags     (set (:tags state-node))}
                  children (when (:states state-node)
                             (collect-nodes path (:states state-node)))]
              (cons self children)))
          state-map))

(defn parse-definition
  "Project a machine definition map into a flat `{:nodes :edges
  :initial-path}` graph. Pure fn.

  For parallel definitions (`{:type :parallel :regions {...}}`) v1
  projects the first region only; a follow-on bead handles full
  parallel layout."
  [definition]
  (cond
    (nil? definition)
    {:nodes [] :edges [] :initial-path nil}

    (= :parallel (:type definition))
    (let [[_region-id region] (first (:regions definition))]
      (parse-definition region))

    :else
    (let [{:keys [initial states]} definition
          nodes     (vec (collect-nodes [] states))
          ;; Mark the initial node
          initial-path (when initial [initial])
          nodes     (mapv (fn [n]
                            (if (= (:path n) initial-path)
                              (assoc n :initial? true)
                              n))
                          nodes)
          edges     (vec (mapcat (fn [[state-id state-node]]
                                   (collect-state-edges [state-id] state-node))
                                 states))]
      {:nodes        nodes
       :edges        edges
       :initial-path initial-path})))

;; ---- ranking ------------------------------------------------------------

(defn- adjacency
  "Build a {from-path #{to-path ...}} map from the edge list."
  [edges]
  (reduce (fn [m {:keys [from to]}]
            (update m from (fnil conj #{}) to))
          {}
          edges))

(defn- bfs-ranks
  "BFS from `start-path` over the adjacency map; return a `{path
  rank}` map. Unreached nodes default to rank 0 so they still render.
  Cycles are broken by keeping the first-seen rank."
  [adj start-path all-paths]
  (loop [queue (if start-path [[start-path 0]] [])
         seen  (if start-path {start-path 0} {})]
    (if-let [[path rank] (first queue)]
      (let [next-queue (subvec (vec queue) 1)
            children   (get adj path #{})
            new-items  (for [c children
                             :when (not (contains? seen c))]
                         [c (inc rank)])
            seen'      (merge seen (into {} (map (fn [[p r]] [p r]) new-items)))]
        (recur (into next-queue new-items) seen'))
      ;; Backfill unreached nodes at rank 0 (so they still render
      ;; somewhere — e.g. dead states with no inbound edges).
      (reduce (fn [acc p]
                (if (contains? acc p) acc (assoc acc p 0)))
              seen
              all-paths))))

;; ---- placement ----------------------------------------------------------

(defn node-id
  "Stable string id for a node, suitable for SVG ids + React keys.
  Exported so the ELK adapter can mint matching ids on the way through
  ELK's JSON graph + back."
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

(defn- place-nodes
  "Given nodes + their ranks, position them into rows. Returns the
  nodes with `:x` and `:y` filled. Deterministic — within a rank
  nodes sort by their stable id."
  [nodes ranks]
  (let [by-rank (->> nodes
                     (sort-by (fn [n] (node-id (:path n))))
                     (group-by #(get ranks (:path %) 0))
                     (into (sorted-map)))
        rows    (for [[rank ns] by-rank]
                  (let [n-count   (count ns)
                        row-width (+ (* n-count node-width)
                                     (* (max 0 (dec n-count)) node-gap))]
                    {:rank rank :nodes ns :row-width row-width}))
        max-row-width (apply max 0 (map :row-width rows))
        chart-width   (+ max-row-width (* 2 chart-margin))
        positioned
        (mapcat (fn [{:keys [rank nodes row-width]}]
                  (let [start-x (+ chart-margin
                                   (quot (- max-row-width row-width) 2))
                        y       (+ chart-margin
                                   (* rank (+ node-height rank-gap)))]
                    (map-indexed
                      (fn [idx n]
                        (assoc n
                          :x     (+ start-x (* idx (+ node-width node-gap)))
                          :y     y
                          :width node-width
                          :height node-height
                          :rank  rank
                          :node-id (node-id (:path n))))
                      nodes)))
                rows)
        chart-height (+ chart-margin
                        (* (inc (apply max 0 (keys by-rank)))
                           (+ node-height rank-gap)))]
    {:nodes positioned
     :chart-width chart-width
     :chart-height chart-height}))

(defn- index-nodes
  "Return a `{path node-with-coords}` map for cheap edge-routing
  lookups."
  [positioned-nodes]
  (into {} (map (fn [n] [(:path n) n])) positioned-nodes))

(defn- route-edge
  "Straight-line routing: source-bottom-centre → target-top-centre.
  Self-loops emit a small arc-control flag for the renderer."
  [node-index {:keys [from to] :as edge}]
  (let [src (get node-index from)
        tgt (get node-index to)]
    (when (and src tgt)
      (let [self? (= from to)
            src-x (+ (:x src) (quot (:width src) 2))
            src-y (+ (:y src) (:height src))
            tgt-x (+ (:x tgt) (quot (:width tgt) 2))
            tgt-y (:y tgt)]
        (assoc edge
          :from-id (:node-id src)
          :to-id   (:node-id tgt)
          :self?   self?
          :points  (if self?
                     [[src-x src-y]
                      [(+ src-x 70) (+ src-y 30)]
                      [(+ src-x 70) (- tgt-y 30)]
                      [tgt-x tgt-y]]
                     [[src-x src-y] [tgt-x tgt-y]])
          :event-label (let [e (:event edge)]
                         (cond
                           (:after edge)    (str "after(" (:after edge) ")")
                           (:always? edge)  "always"
                           (keyword? e)     (if-let [n (namespace e)]
                                              (str n "/" (name e))
                                              (name e))
                           :else            (str e))))))))

;; ---- public entry -------------------------------------------------------

(defn layout
  "Top-level: definition → positioned graph. Pure fn, JVM-runnable.

  Returns the shape documented in this ns's docstring. Empty
  definition (no `:initial` / no `:states`) returns a stable empty
  graph so the SVG renderer can show the empty-chart state."
  [definition]
  (let [{:keys [nodes edges initial-path]} (parse-definition definition)]
    (if (empty? nodes)
      {:nodes [] :edges [] :width 200 :height 80 :initial-id nil}
      (let [all-paths (mapv :path nodes)
            adj       (adjacency edges)
            ranks     (bfs-ranks adj initial-path all-paths)
            {:keys [nodes chart-width chart-height]} (place-nodes nodes ranks)
            n-index   (index-nodes nodes)
            edges     (vec (keep #(route-edge n-index %) edges))]
        {:nodes      nodes
         :edges      edges
         :width      chart-width
         :height     chart-height
         :initial-id (when initial-path (node-id initial-path))}))))

(defn layered-fallback
  "Explicit alias for the simple layered BFS-rank `layout` fn — exported
  so callers (e.g. the ELK adapter in
  `day8.re-frame2-causa.chart.elk-layout`) can request the fallback path
  by name when ELK.js is unavailable or the topology is small enough that
  the layered placement reads cleanly.

  Rationale: Phase 4 (rf2-m7co9) introduces an ELK-driven layout that
  produces the same `{:nodes :edges :width :height :initial-id}` shape
  this fn returns. Both routes flow through `chart/svg`'s renderer
  unchanged. The fallback is what JVM tests + node-runtime tests assert
  against (ELK is a browser-only async loader and is unavailable in
  those rigs). Renaming the public surface to `layered-fallback`
  documents the role so consumer code reads as `(layered-fallback def)`
  rather than `(layout def)` — calling intent matters when there are
  two layout engines."
  [definition]
  (layout definition))

(defn highlight-id
  "Resolve a snapshot `:state` value to the node-id used in the
  positioned graph. Snapshot `:state` is either a flat keyword
  (`:authing`) or a hierarchical path (`[:auth :authing]`)."
  [state]
  (cond
    (nil? state)     nil
    (keyword? state) (node-id [state])
    (vector? state)  (node-id state)
    :else            nil))
