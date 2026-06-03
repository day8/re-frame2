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
     and the Xray topology overlay. Centralising it here means
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
  the chart projector can hand xyflow a `parentId`/sub-flow grouping
  (rf2-xh1lm — v12 reads `parentId`); edges stay region-local (a
  transition declared inside a region never crosses into a sibling
  region — orthogonality).

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

(def machine-root-id
  "rf2-vcnvj — the stable string id for the synthetic MACHINE-ROOT node.

  A machine-level (top-level) `:on` transition (Spec 005 — `:on` is
  valid per-state AND top-level) is a machine-wide fallback every state
  inherits. The PRE-vcnvj projection expanded it into one visible event
  chip PER leaf state (a back-edge from every leaf to the target), which
  (a) repeated the same `door/audit` chip around every state and (b)
  injected N spurious back-edges into ELK's layered ranking, scrambling
  the natural top-to-bottom state order.

  rf2-vcnvj projects each machine-level transition ONCE, sourced from
  this synthetic root node into the transition's target — so the chip
  appears exactly once and ELK's main-column ranking is no longer
  distorted by the fallback back-edges. The id carries a leading +
  trailing `__` that no real `node-id` can mint: `node-id` hex-escapes
  every non-alphanumeric segment char (a literal `_` → `_5f`), so a
  state path can never produce a bare double-underscore boundary at the
  ends. Distinct from `region__` (region containers) by construction."
  "__rf2_machine_root__")

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
  xstate / Stately graph view convention.

    - `:*` wildcard         → `\"* (any)\"` (Spec 005 §Wildcard — the
                              `:on` key that matches any otherwise-
                              unhandled event; NOT a real fireable
                              event, so it reads as an 'otherwise' arm)
    - regular event keyword → `\"event-id\"` (with namespace when present)
    - `:after` transition   → `\"⌚ <delay>ms\"` (rf2-a2b55 — Stately
                              graph view convention: clock glyph
                              prefix + `ms` suffix replaces the prior
                              `after(<delay>)` xstate-textual form so
                              an `:after` edge's role reads at a glance
                              against ordinary event arrows)
    - `:always` transition  → `\"∞\"` (rf2-a2b55 — Stately graph view
                              convention: infinity glyph replaces the
                              prior `\"always\"` word so an eventless
                              transition reads as a continuation glyph
                              against ordinary event-labelled arrows)"
  [{:keys [event after always?]}]
  (cond
    after            (str "⌚ " after "ms")
    always?          "∞"
    (= :* event)     "* (any)"
    (keyword? event) (if-let [n (namespace event)]
                       (str n "/" (name event))
                       (name event))
    :else            (str event)))

(defn event-line
  "Render an edge's visible event line — the event segment plus a
  guard bracket when present. Stately graph view convention: the event
  + guard sit on ONE line; the action renders as a `+ <action>` pill
  on a separate row BELOW it (composed by the renderer, not by this
  pure-data helper).

  Shape: `\"event [guard]\"`. The bracket appears only when a guard is
  declared. `:after` and `:always` substitute for the event segment
  using the same bracket rule (`event-segment` paints the ⌚ / ∞
  glyphs per rf2-a2b55).

  rf2-a2b55 — the action no longer joins the visible event line. Pure
  data → string."
  [{:keys [guard] :as edge}]
  (let [evt   (event-segment edge)
        g-str (name-of guard)]
    (cond-> evt
      g-str (str " [" g-str "]"))))

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

  rf2-a2b55 — the visible edge label PAINTS `event-line` (event +
  guard only) on the canvas with the action as a `+ <action>` pill
  below it (Stately graph view convention). This text-composed form
  remains the canonical `data-event` value + edge-id-collision tie-
  break + host-side label-collision-overlay testing surface; consumers
  that want the visible-line variant call `event-line` directly.

  Pure data → string."
  [{:keys [action] :as edge}]
  (let [evt   (event-line edge)
        a-str (name-of action)]
    (cond-> evt
      a-str (str " / " a-str))))

(defn- edge-source-id
  "rf2-vcnvj — the canonical xyflow source-node id for an edge. A
  machine-level (top-level `:on`) fallback is sourced from the synthetic
  MACHINE-ROOT node (`machine-root-id`); every other edge from its
  `:from` state path via `node-id`. Centralised so the `edge-id`
  composite + the projected `:source` agree (both must address the same
  node)."
  [edge]
  (if (:machine-level? edge)
    machine-root-id
    (node-id (:from edge))))

(defn- edge-id
  "Stable string id for an edge — composite of source-id, target-id,
  event segment, AND the guard/action segment so multiple candidate
  edges between the same pair of states do not collide. The vector-of-
  candidates grammar (`spec/005-StateMachines.md:253-256`) routinely
  produces same-event/same-target edges differing only by guard, so
  folding the guard/action in keeps them distinct (xyflow drops a
  duplicate-id edge). The `parse-flat` caller appends a per-key ordinal
  as a final tiebreak for the rare byte-identical-candidate case.

  rf2-vcnvj — the source segment is `edge-source-id` (the MACHINE-ROOT
  id for a machine-level fallback, the source-state node-id otherwise)
  so a top-level fallback's id reads `<machine-root>__<target>__<event>`
  rather than re-deriving an empty source from `[]`."
  [to-path edge]
  (str (edge-source-id edge)
       "__"
       (node-id to-path)
       "__"
       (event-segment edge)
       (when (:guard edge)  (str "__g_" (name-of (:guard edge))))
       (when (:action edge) (str "__a_" (name-of (:action edge))))))

(defn- collect-machine-edges
  "Emit edges for the machine-level (top-level) `:on` fallback
  transitions (Spec 005 `spec/005-StateMachines.md:181,199` — `:on`
  is valid `per-state AND top-level`; a top-level entry is a machine-
  wide fallback every state inherits).

  rf2-vcnvj — projects each machine-level transition ONCE, sourced from
  the synthetic MACHINE-ROOT node (`machine-root-id`) into the
  transition's target, rather than expanding it into one back-edge per
  LEAF state. The pre-vcnvj per-leaf expansion repeated the same chip
  around every state AND injected N spurious back-edges that scrambled
  ELK's top-to-bottom layered ranking (the door `:door/audit → :locked`
  fallback forced every state to rank as a predecessor of `:locked`).
  One route from root → target reads as the machine-wide fallback it is
  and leaves the main-column ordering to the real state transitions.

  Each edge is flagged `:machine-level? true` (so the projection / SCXML
  emitter / fired-edge matcher can distinguish an inherited fallback)
  and carries `:from []` (the root context, NOT a concrete state) so its
  canonical `node-id` source is `machine-root-id`. The target resolves
  at the TOP level (`resolve-target-path` with a root-level `[]` source).
  An internal (omit-`:target`) or `:same-state` machine-level fallback
  has no concrete state to self-anchor against at the root, so it
  resolves to the target verbatim when one is present and is otherwise
  dropped (a root-level internal fallback has no single source leaf to
  hang an action chip off — the per-state action attribution that needs
  is a state-local `:on` concern, not a machine-wide fallback). The `:*`
  wildcard fallback is supported (rendered as a non-fireable affordance
  downstream)."
  [machine-on]
  (when (seq machine-on)
    (mapcat
      (fn [[event-id spec]]
        (keep (fn [candidate]
                (let [target (:target candidate)
                      tp     (resolve-target-path [] target)]
                  (when tp
                    {:from           []
                     :to             tp
                     :event          event-id
                     :guard          (:guard candidate)
                     :action         (:action candidate)
                     :machine-level? true})))
              (transition-candidates spec)))
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
                                   ;; `parentId` sub-flow (rf2-xh1lm — v12
                                   ;; reads `parentId`, not `parentNode`) —
                                   ;; the SAME mechanic that nests parallel-
                                   ;; region states. Top-level states (path
                                   ;; length 1) carry none.
                                   (> (count path) 1)
                                   (assoc :parent-id (node-id (pop path))))]
                        (assoc n' :id (node-id path))))
                    base-nodes)
        ;; rf2-vcnvj — machine-level (top-level) :on fallbacks now
        ;; project ONCE from the synthetic MACHINE-ROOT node (no longer
        ;; one back-edge per leaf), so `leaf-paths` is no longer threaded
        ;; into `collect-machine-edges`.
        machine-edges (collect-machine-edges on)
        ;; rf2-vcnvj — surface the synthetic root node ONLY when a
        ;; machine-level fallback exists, so a machine with no top-level
        ;; `:on` keeps the pre-vcnvj node set unchanged. The root node is
        ;; a top-level (no `:parent-id`) pseudo-state the projector paints
        ;; as the root-context chip; its `:label` is filled in by
        ;; `parse-definition` (which knows the machine-id), defaulting to
        ;; a neutral caption here.
        root-node (when (seq machine-edges)
                    {:id           machine-root-id
                     :path         []
                     :label        "root"
                     :depth        0
                     :machine-root? true
                     :compound?    false})
        raw-edges (vec (concat
                         (mapcat (fn [[state-id state-node]]
                                   (collect-state-edges [state-id] state-node))
                                 states)
                         machine-edges))
        ;; rf2-ee38b.21 — disambiguate edges that share source/target/
        ;; event but differ by guard/action/machine-level (the vector-of-
        ;; candidates grammar routinely produces same-event/same-target
        ;; forks that differ only by guard). `edge-id` folds guard/action
        ;; in; a trailing per-key ordinal guarantees uniqueness even when
        ;; two candidates are byte-identical.
        edges (->> raw-edges
                   (reduce
                     (fn [{:keys [seen out]} e]
                       (let [base (edge-id (:to e) e)
                             n    (get seen base 0)
                             id   (if (zero? n) base (str base "__" n))]
                         {:seen (assoc seen base (inc n))
                          :out  (conj out
                                      (assoc e
                                        :id          id
                                        :source      (edge-source-id e)
                                        :target      (node-id (:to e))
                                        :from-path   (:from e)
                                        :to-path     (:to e)
                                        :event-label (edge-label e)))}))
                     {:seen {} :out []})
                   :out
                   vec)]
    {;; rf2-vcnvj — the synthetic MACHINE-ROOT node (when present) leads
     ;; the node vector so it precedes the event-node + `__out` edge that
     ;; reference it (xyflow's parent-before-child / source-before-edge
     ;; ordering invariant — the same reason region/compound parents lead).
     :nodes        (vec (concat (when root-node [root-node]) nodes))
     :edges        edges
     :initial-path initial-path}))

(defn- parse-parallel
  "Project a `{:type :parallel :regions {...}}` definition into the
  flat graph, projecting EVERY region (rf2-lkwev — Phase 1 deferred
  all but the first). Each region becomes a synthetic `:region?`
  compound node whose `node-id` is `region-node-id`; each region's
  states carry `:region <region-id>` + `:parent-id <region-node-id>`
  so the chart projector can hand xyflow a `parentId`/sub-flow
  grouping (rf2-xh1lm — v12 reads `parentId`). Region order is
  preserved (regions are an ordered map in practice; we keep
  insertion order via `:regions`)."
  [definition]
  (let [regions (:regions definition)
        per-region
        (map-indexed
          (fn [idx [region-id region-def]]
            (let [rid       (region-node-id region-id)
                  parsed    (parse-flat region-def)
                  ;; Tag every state node with its region + parent so
                  ;; the projector emits xyflow `parentId` grouping
                  ;; (rf2-xh1lm — v12 reads `parentId`).
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
  `parentId` grouping (rf2-xh1lm — v12 reads `parentId`); the result
  also carries `:parallel? true`."
  [definition]
  (cond
    (nil? definition)
    {:nodes [] :edges [] :initial-path nil}

    (= :parallel (:type definition))
    (parse-parallel definition)

    :else
    (parse-flat definition)))

(defn highlight-id
  "Resolve a single-active snapshot `:state` value to the node-id used
  in the positioned graph. Snapshot `:state` is either a flat keyword
  (`:authing`) or a hierarchical path (`[:auth :authing]`).

  This is the SINGLE-active resolver — flat + compound machines, whose
  snapshot has exactly one active leaf. A PARALLEL machine's snapshot
  `:state` is a region-map (N simultaneously-active leaves); use
  `highlight-ids` for that (it subsumes this fn — the scalar value
  resolves to a singleton set). Returns nil for a region-map (a map is
  not a single-active state)."
  [state]
  (cond
    (nil? state)     nil
    (keyword? state) (node-id [state])
    (vector? state)  (node-id state)
    :else            nil))

(defn highlight-ids
  "Resolve a WHOLE snapshot `:state` value to the SET of active-leaf
  node-ids — the multi-active highlight contract (rf2-yoe6e / rf2-g2svr;
  closes parity gap G1 in `001-Topology-Parity.md`).

  Spec 005 §Snapshot shape gives `:state` three arms; this fn handles
  all three, always returning a set so a parallel machine's N
  simultaneously-active leaves all light up at once:

  - **flat keyword** (`:authing`) — single-active; `#{(node-id [:authing])}`.
  - **hierarchical path** (`[:auth :authing]`) — single-active compound
    leaf; `#{(node-id [:auth :authing])}` (the deepest leaf, since
    `node-id` of the full path IS that leaf's id).
  - **region-map** (`{:data :loading :form :neutral}`) — PARALLEL: N
    simultaneously-active leaves. Each region's value is itself a
    keyword-or-path **relative to that region's own state-tree**, so it
    resolves the SAME way the parse mints region-state ids — via
    `node-id` of the in-region path (parse-parallel does NOT region-
    prefix a state's node-id; it tags `:region` + `:parent-id` but keeps
    the in-region `node-id`). A nested region value (a region whose
    value is itself a vector path) resolves to its DEEPEST leaf, exactly
    as the single-compound case does. Returns the set of N leaf ids.

  - **nil / anything else** — the empty set (no highlight).

  Pure fn — JVM-runnable; the projection threads the result through
  `xyflow-graph`'s `:highlight-ids` so EVERY active leaf is marked
  `:active`, not just one."
  [state]
  (cond
    (nil? state)     #{}
    (keyword? state) #{(node-id [state])}
    (vector? state)  #{(node-id state)}
    (map? state)     (into #{}
                           (keep (fn [[_region region-state]]
                                   (cond
                                     (keyword? region-state) (node-id [region-state])
                                     (vector? region-state)  (node-id region-state)
                                     :else                   nil)))
                           state)
    :else            #{}))
