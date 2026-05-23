(ns day8.re-frame2-machines-viz.chart.layout
  "Pure-data graph projector for the MachineChart.

  rf2-gpzb4 (2026-05-21) — Mike's xyflow override of the 2026-05-19
  ELK+SVG lock. This ns is no longer a layout engine: xyflow + elkjs
  own positioning. What remains is the substrate-agnostic **graph
  parse** — definition → flat list of nodes + edges + per-node /
  per-edge metadata. The xyflow chart ns (`chart.cljs`)
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

(defn name-of
  "Render a guard / action / entry / exit symbol-like value as a short
  string WITHOUT throwing on fn values. Keywords use `ns/name` when
  namespaced, plain `name` otherwise; non-keywords fall through to
  `str`. Inlined fn guards / actions surface their `:name` meta (named
  `(fn name [...] ...)` / `(defn ...)`) or `\"fn\"` when anonymous — so
  the xstate label reads cleanly instead of `#object[Function]`.

  Public + namespace-preserving — `chart.projection` reuses this (it
  previously carried a near-duplicate `safe-name` that dropped the
  namespace; rf2-ee38b.21 collapses the two)."
  [v]
  (cond
    (nil? v)     nil
    (keyword? v) (if-let [n (namespace v)]
                   (str n "/" (name v))
                   (name v))
    (fn? v)      (or (some-> v meta :name str) "fn")
    :else        (str v)))

(defn- resolve-target-path
  "Resolve a transition target relative to source-path.

  Per Spec 005 §Self-transitions (`spec/005-StateMachines.md:206,263,
  289-294`):

  - `:target :same-state` — the **external** self-transition sentinel.
    Resolve to `source-path` so the edge is a true self-loop
    (source == target) and renders via the existing `:selfLoop` path.
  - a plain keyword target — resolve relative to the parent path.
  - a vector-path target — take it verbatim.
  - nil (no `:target`) — returns nil. The **internal** self-transition
    case (a candidate map that omits `:target`) is NOT handled here:
    `collect-state-edges` detects the missing key and self-anchors the
    edge itself (flagging `:internal? true`), so this fn never sees it."
  [source-path target]
  (cond
    (= :same-state target) (vec source-path)
    (keyword? target)      (conj (parent-path source-path) target)
    (target-path? target)  (vec target)
    :else                  nil))

(defn- collect-state-edges
  "Walk a state node and return edge-maps for every statically-
  resolvable transition declared on it. Compound substates recurse.

  Self-transitions (Spec 005 §Self-transitions) chart as self-loops:
  `:target :same-state` (external) resolves to the source path; a
  candidate that omits `:target` entirely (internal — runs only its
  `:action`) self-anchors and is flagged `:internal? true` so the
  renderer can distinguish it (no exit/entry re-trigger)."
  [state-path state-node]
  (let [edges-from
        (concat
         (mapcat (fn [[event-id spec]]
                   (keep (fn [candidate]
                           (let [internal? (and (map? candidate)
                                                (not (contains? candidate :target)))
                                 tp        (if internal?
                                             (vec state-path)
                                             (resolve-target-path state-path
                                                                  (:target candidate)))]
                             (when tp
                               (cond-> {:from   state-path
                                        :to     tp
                                        :event  event-id
                                        :guard  (:guard candidate)
                                        :action (:action candidate)}
                                 internal? (assoc :internal? true)))))
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
  carries the full path so compound nesting is preserved as data.

  A compound parent's `:initial` child is flagged `:initial? true` so
  the initial-state marker surfaces at EVERY compound level (not just
  the machine root) — xstate/SCXML semantics. The flag also feeds the
  SCXML / Mermaid emitters' `<initial>` rendering."
  [parent-path state-map]
  (mapcat (fn [[state-id state-node]]
            (let [path (conj parent-path state-id)
                  self (cond-> {:path     path
                                :label    (name state-id)
                                :depth    (count parent-path)
                                :initial? (boolean (:initial? state-node))
                                :final?   (boolean (:final? state-node))
                                :compound? (boolean (:states state-node))
                                :tags     (set (:tags state-node))}
                         ;; rf2-ee38b.21 — :entry / :exit state actions
                         ;; (Spec 005 §State nodes L218-219) surface as
                         ;; short name strings so the renderer can paint
                         ;; `entry / <name>` / `exit / <name>` rows. nil
                         ;; when absent (cond-> skips the assoc).
                         (:entry state-node) (assoc :entry (name-of (:entry state-node)))
                         (:exit state-node)  (assoc :exit  (name-of (:exit state-node))))
                  init-key     (:initial state-node)
                  raw-children (when (:states state-node)
                                 (collect-nodes path (:states state-node)))
                  children     (if init-key
                                 (map (fn [c]
                                        (if (= (:path c) (conj path init-key))
                                          (assoc c :initial? true)
                                          c))
                                      raw-children)
                                 raw-children)]
              (cons self children)))
          state-map))

;; ---- public ids ---------------------------------------------------------

(defn- escape-id-segment
  "Sanitise one path segment into an xyflow-id-safe string
  INJECTIVELY — distinct inputs always yield distinct outputs.

  The naive `str/replace #\"[^a-zA-Z0-9_]\" \"_\"` collapses `:a/b`,
  `:a-b`, `:a_b` all to `\"a_b\"` (rf2-ee38b.21 P2): a React key
  collision drops one node + makes every edge addressing either
  ambiguous. Hyphens are pervasive in re-frame keywords
  (`:logged-in`, `:rate-limited`), so the collision is reachable.

  Scheme: every char outside `[a-zA-Z0-9]` becomes `_<hex>` (the
  underscore itself included), so the mapping is reversible and no two
  distinct chars share an encoding. The path separator `__` (double
  underscore) can never appear inside a segment because a literal `_`
  encodes to `_5f`."
  [s]
  (str/join
    (map (fn [ch]
           (if (re-matches #"[a-zA-Z0-9]" (str ch))
             (str ch)
             (str "_" #?(:clj  (format "%02x" (int ch))
                         :cljs (let [h (.toString (.charCodeAt (str ch) 0) 16)]
                                 (if (= 1 (count h)) (str "0" h) h))))))
         s)))

(defn node-id
  "Stable string id for a node, suitable for xyflow ids and SCXML
  / Mermaid emitter ids. Exported so every consumer addresses nodes
  the same way.

  rf2-ee38b.21 — the id is INJECTIVE: distinct paths always mint
  distinct ids (the old `[^a-zA-Z0-9_]`-collapse merged `:a/b` /
  `:a-b` / `:a_b`). Segments are `_<hex>`-escaped (see
  `escape-id-segment`) and joined with `__`."
  [path]
  (->> path
       (map (fn [p]
              (if (keyword? p)
                (if-let [ns (namespace p)]
                  (str (escape-id-segment ns) "_2f" (escape-id-segment (name p)))
                  (escape-id-segment (name p)))
                (escape-id-segment (str p)))))
       (str/join "__")))

(defn region-node-id
  "Stable string id for a parallel region's synthetic compound node.
  Prefixed `region__` so it never collides with a state `node-id`
  (rf2-lkwev). `region-id` is the region's key in the `:regions` map.

  rf2-ee38b.21 — segments are `escape-id-segment`-escaped (the same
  injective scheme `node-id` uses) so a region named `:auth/main` and
  a state `:auth-main` can never collapse to the same id."
  [region-id]
  (str "region__"
       (if (keyword? region-id)
         (if-let [ns (namespace region-id)]
           (str (escape-id-segment ns) "_2f" (escape-id-segment (name region-id)))
           (escape-id-segment (name region-id)))
         (escape-id-segment (str region-id)))))

(defn event-segment
  "Render the leading event segment of an edge label per the
  xstate-stately convention.

    - `:*` wildcard         → `\"* (any)\"` (Spec 005 §Wildcard — the
                              `:on` key that matches any otherwise-
                              unhandled event; NOT a real fireable
                              event, so it reads as an 'otherwise' arm)
    - regular event keyword → `\"event-id\"` (with namespace when present)
    - `:after` transition   → `\"after(<delay>)\"`
    - `:always` transition  → `\"always\"`"
  [{:keys [event after always?]}]
  (cond
    after            (str "after(" after ")")
    always?          "always"
    (= :* event)     "* (any)"
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
  event segment, AND the guard/action segment so multiple candidate
  edges between the same pair of states do not collide. The vector-of-
  candidates grammar (`spec/005-StateMachines.md:253-256`) routinely
  produces same-event/same-target edges differing only by guard, so
  folding the guard/action in keeps them distinct (xyflow drops a
  duplicate-id edge). The `parse-flat` caller appends a per-key ordinal
  as a final tiebreak for the rare byte-identical-candidate case."
  [from-path to-path {:keys [guard action] :as edge}]
  (str (node-id from-path)
       "__"
       (node-id to-path)
       "__"
       (event-segment edge)
       (when guard  (str "__g_" (name-of guard)))
       (when action (str "__a_" (name-of action)))))

(defn- collect-machine-edges
  "Emit edges for the machine-level (top-level) `:on` fallback
  transitions (Spec 005 `spec/005-StateMachines.md:181,199` — `:on`
  is valid `per-state AND top-level`; a top-level entry is a machine-
  wide fallback every state inherits).

  Renders one edge from every LEAF state to the transition's target,
  flagged `:machine-level? true` so the projection / SCXML emitter can
  distinguish an inherited fallback from a state-local transition. Leaf
  states (no `:states`) are the real transition sources — a compound
  parent's inherited fallback fires from whichever leaf is active.
  Edges back into a leaf's own state are kept (they self-loop). The
  `:*` wildcard fallback is supported too (rendered as a non-fireable
  affordance downstream)."
  [machine-on leaf-paths]
  (when (seq machine-on)
    (mapcat
      (fn [[event-id spec]]
        (mapcat
          (fn [leaf-path]
            (keep (fn [candidate]
                    (let [target    (:target candidate)
                          internal? (and (map? candidate)
                                         (not (contains? candidate :target)))
                          ;; A machine-level transition's target is
                          ;; resolved at the TOP level (a top-level
                          ;; state), NOT relative to the inheriting
                          ;; leaf's parent — so `resolve-target-path`
                          ;; is called with a root-level `[]` source.
                          ;; `:same-state` / omitted-target self-loop on
                          ;; the leaf itself.
                          tp        (cond
                                      internal?              (vec leaf-path)
                                      (= :same-state target) (vec leaf-path)
                                      :else (resolve-target-path [] target))]
                      (when tp
                        (cond-> {:from           leaf-path
                                 :to             tp
                                 :event          event-id
                                 :guard          (:guard candidate)
                                 :action         (:action candidate)
                                 :machine-level? true}
                          internal? (assoc :internal? true)))))
                  (transition-candidates spec)))
          leaf-paths))
      machine-on)))

;; ---- public projection --------------------------------------------------

(defn- parse-flat
  "Project a non-parallel machine definition into `{:nodes :edges
  :initial-path}`. The shared single-machine walker; `parse-definition`
  calls this for plain machines AND once per region of a `:parallel`
  machine (the per-region nodes/edges then get tagged with their
  region + parent-id)."
  [definition]
  (let [{:keys [initial states on]} definition
        base-nodes (vec (collect-nodes [] states))
        initial-path (when initial [initial])
        nodes (mapv (fn [n]
                      (let [path (:path n)
                            n'   (cond-> n
                                   (= path initial-path) (assoc :initial? true)
                                   ;; A node nested inside a compound parent
                                   ;; carries `:parent-id` (the parent's
                                   ;; node-id) so the projector wires xyflow's
                                   ;; parentNode sub-flow — the SAME mechanic
                                   ;; that nests parallel-region states. Top-
                                   ;; level states (path length 1) carry none.
                                   (> (count path) 1)
                                   (assoc :parent-id (node-id (pop path))))]
                        (assoc n' :id (node-id path))))
                    base-nodes)
        leaf-paths (->> base-nodes
                        (remove :compound?)
                        (mapv :path))
        raw-edges (vec (concat
                         (mapcat (fn [[state-id state-node]]
                                   (collect-state-edges [state-id] state-node))
                                 states)
                         ;; Machine-level (top-level) :on fallback
                         ;; transitions every state inherits (Spec 005).
                         (collect-machine-edges on leaf-paths)))
        ;; rf2-ee38b.21 — disambiguate edges that share source/target/
        ;; event but differ by guard/action/machine-level (the vector-of-
        ;; candidates grammar routinely produces same-event/same-target
        ;; forks that differ only by guard). `edge-id` folds guard/action
        ;; in; a trailing per-key ordinal guarantees uniqueness even when
        ;; two candidates are byte-identical.
        edges (->> raw-edges
                   (reduce
                     (fn [{:keys [seen out]} e]
                       (let [base (edge-id (:from e) (:to e) e)
                             n    (get seen base 0)
                             id   (if (zero? n) base (str base "__" n))]
                         {:seen (assoc seen base (inc n))
                          :out  (conj out
                                      (assoc e
                                        :id          id
                                        :source      (node-id (:from e))
                                        :target      (node-id (:to e))
                                        :from-path   (:from e)
                                        :to-path     (:to e)
                                        :event-label (edge-label e)))}))
                     {:seen {} :out []})
                   :out
                   vec)]
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
