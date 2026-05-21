(ns day8.re-frame2-machines-viz.chart.layout
  "Pure-data graph projector for the MachineChart.

  rf2-gpzb4 (2026-05-21) — Mike's xyflow override of the 2026-05-19
  ELK+SVG lock. This ns is no longer a layout engine: xyflow + elkjs
  own positioning. What remains is the substrate-agnostic **graph
  parse** — definition → flat list of nodes + edges + per-node /
  per-edge metadata. The xyflow chart ns (`chart/xyflow.cljs`)
  consumes this projection and hands the nodes/edges to xyflow's
  React renderer; elkjs runs as xyflow's layout backend off the same
  graph.

  ## Why this ns survives the migration

  Three reasons:

  1. **Substrate-independence for the parse.** The walker (states,
     compound nesting paths, transition normalisation, after / always
     edge emission) is pure CLJS data → data and JVM-runnable. Tests
     pin the parse contract without DOM / React / xyflow.
  2. **One source for `node-id` + `edge-label`.** xyflow needs string
     ids; the same id contract is used by SCXML / Mermaid emitters
     and the Causa topology overlay. Centralising it here means
     every consumer addresses nodes the same way.
  3. **`highlight-id` resolution.** Snapshot `:state` → xyflow
     node-id mapping for the live-highlight surface. Pure fn, no
     dependency on the renderer.

  ## Shape

  `(parse-definition definition)` returns:

      {:nodes        [{:id <string>          ;; xyflow node id
                       :path <vec-of-kw>     ;; full hierarchical path
                       :label <str>          ;; human-readable label
                       :depth <int>          ;; nesting depth
                       :initial? <bool>
                       :final? <bool>
                       :compound? <bool>
                       :tags #{<keyword>}}
                      ...]
       :edges        [{:id <string>           ;; xyflow edge id
                       :source <string>       ;; from node id
                       :target <string>       ;; to node id
                       :from-path <vec-of-kw> ;; source state path
                       :to-path   <vec-of-kw> ;; target state path
                       :event <kw>            ;; raw event id
                       :event-label <str>     ;; xstate label
                                              ;; `event [guard] / action`
                       :guard <kw-or-nil>
                       :action <kw-or-nil>
                       :after <ms-or-nil>     ;; non-nil iff :after edge
                       :always? <bool>}       ;; true iff :always edge
                      ...]
       :initial-path <vec-of-kw>}            ;; the machine's :initial path

  ## Parallel regions (rf2-lkwev — xyflow Phase 2)

  A `{:type :parallel :regions {...}}` definition projects EVERY
  region (Phase 1 deferred all but the first). Each region becomes a
  synthetic `:region?` compound node — an orthogonal-zone container
  the `chart.nodes/parallel-region-node` paints with a distinct
  dashed boundary (Stately parity). Every state inside a region
  carries `:region <region-id>` + `:parent-id <region-node-id>` so
  the chart projector can hand xyflow a `parentNode`/sub-flow
  grouping; edges stay region-local (a transition declared inside a
  region never crosses into a sibling region — orthogonality).

  Region node-ids are prefixed `region__<region-id>` so they never
  collide with a real state's `node-id`.

  ## What this does NOT do (pre-migration, this ns DID do)

  - **Positioning** (`bfs-ranks`, `place-nodes`, `route-edge`) —
    REMOVED. xyflow owns positions; elkjs runs as xyflow's layout
    backend.
  - **Width / height / chart geometry** — REMOVED. xyflow's
    `<ReactFlow>` container measures itself; `fitView` handles
    initial framing.
  - **Edge points + bend-points** — REMOVED. xyflow renders edges
    via its own path-builder over its layouted graph.

  Per `tools/machines-viz/spec/000-Vision.md` §Decision trace
  §Interactive renderer (revised 2026-05-21)."
  (:require [clojure.string :as str]))

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
                  self {:path     path
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

;; ---- public ids ---------------------------------------------------------

(defn node-id
  "Stable string id for a node, suitable for xyflow ids and SCXML
  / Mermaid emitter ids. Exported so every consumer addresses nodes
  the same way."
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

(defn region-node-id
  "Stable string id for a parallel region's synthetic compound node.
  Prefixed `region__` so it never collides with a state `node-id`
  (rf2-lkwev). `region-id` is the region's key in the `:regions` map."
  [region-id]
  (str "region__"
       (if (keyword? region-id)
         (if-let [ns (namespace region-id)]
           (str ns "_" (name region-id))
           (name region-id))
         (str region-id))))

(defn- name-of
  "Render a guard/action symbol-like value as a short string. Keywords
  use `ns/name` when namespaced, plain `name` otherwise. Non-keywords
  fall through to `str`."
  [v]
  (cond
    (nil? v)     nil
    (keyword? v) (if-let [n (namespace v)]
                   (str n "/" (name v))
                   (name v))
    :else        (str v)))

(defn event-segment
  "Render the leading event segment of an edge label per the
  xstate-stately convention.

    - regular event keyword → `\"event-id\"` (with namespace when present)
    - `:after` transition   → `\"after(<delay>)\"`
    - `:always` transition  → `\"always\"`"
  [{:keys [event after always?]}]
  (cond
    after            (str "after(" after ")")
    always?          "always"
    (keyword? event) (if-let [n (namespace event)]
                       (str n "/" (name event))
                       (name event))
    :else            (str event)))

(defn edge-label
  "Compose an xstate-stately-convention edge label from an edge map.

  Shape: `\"event [guard] / action\"`. Brackets and slash are
  introduced ONLY when their segment is present, so the four legal
  forms render cleanly:

  | Segments              | Rendered                     |
  |-----------------------|------------------------------|
  | event                 | `event`                      |
  | event + guard         | `event [guard]`              |
  | event + action        | `event / action`             |
  | event + guard + action| `event [guard] / action`     |

  `:after` and `:always` substitute for the event segment using the
  same bracket/slash rules.

  Pure data → string."
  [{:keys [guard action] :as edge}]
  (let [evt   (event-segment edge)
        g-str (name-of guard)
        a-str (name-of action)]
    (cond-> evt
      g-str (str " [" g-str "]")
      a-str (str " / " a-str))))

(defn- edge-id
  "Stable string id for an edge — composite of source-id, target-id,
  and event segment so multiple parallel edges between the same pair
  of states do not collide."
  [from-path to-path edge]
  (str (node-id from-path)
       "__"
       (node-id to-path)
       "__"
       (event-segment edge)))

;; ---- public projection --------------------------------------------------

(defn- parse-flat
  "Project a non-parallel machine definition into `{:nodes :edges
  :initial-path}`. The shared single-machine walker; `parse-definition`
  calls this for plain machines AND once per region of a `:parallel`
  machine (the per-region nodes/edges then get tagged with their
  region + parent-id)."
  [definition]
  (let [{:keys [initial states]} definition
        base-nodes (vec (collect-nodes [] states))
        initial-path (when initial [initial])
        nodes (mapv (fn [n]
                      (let [n' (if (= (:path n) initial-path)
                                 (assoc n :initial? true)
                                 n)]
                        (assoc n' :id (node-id (:path n')))))
                    base-nodes)
        raw-edges (vec (mapcat (fn [[state-id state-node]]
                                 (collect-state-edges [state-id] state-node))
                               states))
        edges (vec (map (fn [e]
                          (assoc e
                            :id          (edge-id (:from e) (:to e) e)
                            :source      (node-id (:from e))
                            :target      (node-id (:to e))
                            :from-path   (:from e)
                            :to-path     (:to e)
                            :event-label (edge-label e)))
                        raw-edges))]
    {:nodes        nodes
     :edges        edges
     :initial-path initial-path}))

(defn- parse-parallel
  "Project a `{:type :parallel :regions {...}}` definition into the
  flat graph, projecting EVERY region (rf2-lkwev — Phase 1 deferred
  all but the first). Each region becomes a synthetic `:region?`
  compound node whose `node-id` is `region-node-id`; each region's
  states carry `:region <region-id>` + `:parent-id <region-node-id>`
  so the chart projector can hand xyflow a `parentNode`/sub-flow
  grouping. Region order is preserved (regions are an ordered map in
  practice; we keep insertion order via `:regions`)."
  [definition]
  (let [regions (:regions definition)
        per-region
        (map-indexed
          (fn [idx [region-id region-def]]
            (let [rid       (region-node-id region-id)
                  parsed    (parse-flat region-def)
                  ;; Tag every state node with its region + parent so
                  ;; the projector emits xyflow parentNode grouping.
                  tagged-nodes
                  (mapv (fn [n]
                          (assoc n
                            :region    region-id
                            :parent-id rid))
                        (:nodes parsed))
                  ;; The synthetic region container node.
                  region-node
                  {:id        rid
                   :path      [region-id]
                   :label     (name region-id)
                   :depth     0
                   :region?   true
                   :region    region-id
                   :region-index idx
                   :compound? true}]
              {:nodes (into [region-node] tagged-nodes)
               :edges (:edges parsed)}))
          regions)]
    {:nodes        (vec (mapcat :nodes per-region))
     :edges        (vec (mapcat :edges per-region))
     :initial-path nil
     :parallel?    true}))

(defn parse-definition
  "Project a machine definition into a flat `{:nodes :edges
  :initial-path}` graph. Pure fn — JVM-runnable.

  Parallel definitions (`{:type :parallel :regions {...}}`) project
  EVERY region as an orthogonal zone (rf2-lkwev — xyflow Phase 2 full
  parallel-region rendering; supersedes the Phase 1 first-region-only
  projection). Each region surfaces a synthetic `:region?` compound
  node and its states carry `:region` + `:parent-id` for xyflow
  parentNode grouping; the result also carries `:parallel? true`."
  [definition]
  (cond
    (nil? definition)
    {:nodes [] :edges [] :initial-path nil}

    (= :parallel (:type definition))
    (parse-parallel definition)

    :else
    (parse-flat definition)))

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
