(ns day8.re-frame2-causa.panels.causality-graph-helpers
  "Pure-data helpers for Causa's Causality Graph panel
  (Phase 4, rf2-4rqs1).

  ## Why a separate `.cljc` ns

  The panel view in `causality_graph.cljs` builds an SVG hiccup tree;
  the *logic* — projecting a cascade vector into nodes + arrows, and
  computing layout positions — is pure data → data. Splitting the
  algebra into `.cljc` so it runs under the JVM test target
  (`clojure -M:test`) is required by `feedback_jvm_interop_must_work.md`.

  ## Data model (per spec/001-Causality-Graph.md §Data model)

  The graph reads cascade records produced by
  `re-frame.trace.projection/group-cascades` (rf2-wvzgd) plus the raw
  trace events the cascade was assembled from. Each cascade produces:

    - **One dispatch node** keyed by `:dispatch-id`. The node carries
      the event vector, the `:origin` axis (`:app` / `:pair` /
      `:story` / `:test` / `:causa`), an `:error?` / `:warning?` flag
      derived from the cascade's `:other` bucket, and the `:root?`
      bit (true when the cascade's parent-dispatch-id is nil).

    - **Zero or one arrow** from `:parent-dispatch-id` → `:dispatch-id`
      when the cascade is a child. The parent edge is only emitted
      when the parent cascade is also present in the input — orphan
      child cascades render as roots (per spec §What this doesn't do:
      'No retroactive correlation. If a dispatch was emitted before
      the trace surface knew about it … it appears as a root in the
      graph').

  Non-dispatch trace events (`:op-type :fx`, `:sub/run`, `:view/render`,
  `:rf.machine/transition`, errors) are surfaced as flags on the
  parent dispatch's node rather than spawning their own nodes — per
  spec §Edges 'Dispatch contains significant trace … those traces
  attach inside their dispatch's node rather than spawning their own
  outbound edge'.

  ## Layout (per spec/001-Causality-Graph.md §Layout)

  Top-down DAG layout. Roots live at the top; children stack
  vertically beneath their parent. Horizontal axis groups siblings
  side-by-side; vertical axis is time (older at the top).

  The layout is intentionally simple — a level-based BFS from each
  root, assigning column slots in encounter order. Cross-cascade edges
  (parent in one branch, child in another) take the column they land
  on; the v1 layout does not run a force-directed pass. Per the spec
  §Performance — re-layout runs incrementally — but the v1 helper is
  a stable O(n) pass over the cascade vector. The full incremental-
  layout pass lands once the panel's perf budget is measured against
  a real cascade stream."
  (:require [clojure.set :as set]))

;; ---- layout constants ----------------------------------------------------

(def default-node-width
  "SVG width of a dispatch node, in pixels. Per spec/007-UX-IA.md
  §The causality strip the strip uses 24×24px pills on cosy density;
  the full graph uses ~120×40px tiles so the event vector fits inside
  the node on one line."
  140)

(def default-node-height
  "SVG height of a dispatch node, in pixels. Matches the strip's
  cosy-density vertical rhythm."
  44)

(def default-column-gap
  "Horizontal gap between sibling node columns, in pixels. Sized so a
  parent's children fit beneath it without overlap at the default
  node width."
  24)

(def default-row-gap
  "Vertical gap between cascade levels, in pixels. Tuned so the arrow
  between a parent and child is long enough to be read at a glance
  but short enough that 6+ levels fit in a 600px-tall panel."
  28)

(def default-margin
  "Outer SVG margin around the layout's bounding box, in pixels.
  Prevents edge-clipping at the panel edges."
  16)

;; ---- node / arrow projection --------------------------------------------

(defn- dispatched-event-of
  "Walk a cascade's `:other` + `:handler` + `:fx` buckets for the
  `:event/dispatched` emit and return it (the trace event itself, not
  the event vector). nil when no such emit landed in the cascade —
  which can happen for the `:ungrouped` bucket (registry-time events
  without a `:dispatch-id`) and for cascades whose root event predates
  the trace surface."
  [cascade]
  ;; group-cascades does NOT keep the `:event/dispatched` trace event
  ;; verbatim — it only retains the event *vector* under :event. To
  ;; surface :origin / :parent-dispatch-id we need access to the raw
  ;; trace event. We accept the cascade may not carry it; callers fall
  ;; back to nil when the slot is missing.
  (or (:event-trace cascade)            ; supplied by enrich step below
      (some (fn [ev]
              (when (and (= :event (:op-type ev))
                         (= :event/dispatched (:operation ev)))
                ev))
            (concat [(:handler cascade) (:fx cascade)]
                    (:effects cascade)
                    (:other cascade)))))

(defn enrich-cascades
  "Augment each cascade record with the `:event/dispatched` trace
  event lifted off the raw trace stream. `group-cascades` retains
  the event *vector* under `:event` but drops the original trace
  event; the graph needs `:tags :origin` + `:tags :parent-dispatch-id`
  off that event, so we re-walk the raw stream once and stitch the
  dispatched-trace back onto each cascade record under
  `:event-trace`.

  Pure data → cascades-with-event-trace. JVM-runnable."
  [cascades trace-events]
  (let [by-id (->> trace-events
                   (filter (fn [ev]
                             (and (= :event (:op-type ev))
                                  (= :event/dispatched (:operation ev)))))
                   (reduce (fn [m ev]
                             (let [did (get-in ev [:tags :dispatch-id])]
                               (cond-> m
                                 did (assoc did ev))))
                           {}))]
    (mapv (fn [c]
            (if-let [t (get by-id (:dispatch-id c))]
              (assoc c :event-trace t)
              c))
          cascades)))

(defn- origin-of
  "Read `:origin` off the cascade's dispatched-event trace. Returns
  one of `:app` / `:pair` / `:story` / `:test` / `:causa`; defaults
  to `:app` when the cascade carries no :event/dispatched trace
  event (e.g. the `:ungrouped` bucket). Spec/002 §Dispatch origin
  tagging."
  [cascade]
  (or (some-> cascade dispatched-event-of :tags :origin)
      :app))

(defn- parent-of
  "Read `:parent-dispatch-id` off the cascade's dispatched-event
  trace. nil for root cascades; nil for the `:ungrouped` bucket."
  [cascade]
  (some-> cascade dispatched-event-of :tags :parent-dispatch-id))

(defn- has-error?
  "True when the cascade contains an `:op-type :error` event. Drives
  the red border treatment per spec §Visual encoding §Border colour."
  [cascade]
  (boolean
    (some (fn [ev] (= :error (:op-type ev)))
          (:other cascade))))

(defn- has-warning?
  "True when the cascade contains a `:op-type :warning` event. Drives
  the amber border treatment per spec §Visual encoding §Border
  colour."
  [cascade]
  (boolean
    (some (fn [ev] (= :warning (:op-type ev)))
          (:other cascade))))

(defn- node-of
  "Project one cascade record into the graph's node shape:

      {:dispatch-id  <id>
       :event        <event vector>
       :origin       :app | :pair | :story | :test | :causa
       :root?        bool
       :error?       bool
       :warning?     bool
       :parent       <dispatch-id or nil>
       :effect-count <int>     ; for badge / hover summary
       :sub-count    <int>
       :render-count <int>
       :other-count  <int>}"
  [cascade root?]
  {:dispatch-id  (:dispatch-id cascade)
   :event        (:event cascade)
   :origin       (origin-of cascade)
   :root?        root?
   :error?       (has-error? cascade)
   :warning?     (has-warning? cascade)
   :parent       (parent-of cascade)
   :effect-count (count (:effects cascade))
   :sub-count    (count (:subs cascade))
   :render-count (count (:renders cascade))
   :other-count  (count (:other cascade))})

(defn project-cascades-to-graph
  "Project a vector of enriched cascades (from `enrich-cascades`) into
  a `{:nodes [...] :arrows [...]}` map.

  Each cascade becomes one node; each cascade whose parent is *also*
  in the cascade set produces an arrow `[parent-id child-id]`. Orphan
  children (parent missing from the buffer) render as roots per spec
  §What this doesn't do — 'no retroactive correlation'.

  The `:ungrouped` cascade is filtered out — it has no `:dispatch-id`
  and no causal lineage; rendering it would clutter the graph with a
  bucket that consumers should inspect via the event-detail panel's
  `:other` row.

  Returns:

      {:nodes  [<node> ...]    ; one per dispatch cascade
       :arrows [[parent-id child-id] ...]
       :roots  #{<id> ...}     ; ids with no in-graph parent
       :index  {<id> <node>}}  ; convenience lookup

  Pure data → data. JVM-runnable."
  [cascades]
  (let [dispatch-cascades (filter (fn [c]
                                    (and (some? (:dispatch-id c))
                                         (not= :ungrouped (:dispatch-id c))))
                                  cascades)
        ids               (set (map :dispatch-id dispatch-cascades))
        ;; A cascade is a root if its parent isn't in the cascade
        ;; set (either truly root, or orphan whose parent aged out).
        nodes             (mapv (fn [c]
                                  (let [parent (parent-of c)
                                        root?  (or (nil? parent)
                                                   (not (contains? ids parent)))]
                                    (node-of c root?)))
                                dispatch-cascades)
        arrows            (->> nodes
                               (keep (fn [{:keys [dispatch-id parent]}]
                                       (when (and parent (contains? ids parent))
                                         [parent dispatch-id])))
                               vec)
        roots             (->> nodes
                               (filter :root?)
                               (map :dispatch-id)
                               set)
        index             (into {} (map (juxt :dispatch-id identity)) nodes)]
    {:nodes  nodes
     :arrows arrows
     :roots  roots
     :index  index}))

;; ---- layout --------------------------------------------------------------

(defn- children-by-parent
  "Index `arrows` by parent-id → vector of child-ids. Used by the
  layout walker to descend the DAG breadth-first."
  [arrows]
  (reduce (fn [m [p c]]
            (update m p (fnil conj []) c))
          {}
          arrows))

(defn- assign-levels
  "Walk the graph from `roots` outward, recording the level (distance
  from a root) for each node. Roots are level 0; their children are
  level 1; etc. Returns a map `{dispatch-id level}`.

  Cycles cannot occur in practice (parent-dispatch-id is allocated
  monotonically before the child is enqueued), but the walker is
  defensive: a node never re-enters the queue once it has a level."
  [roots children]
  (loop [acc      {}
         frontier (vec (for [r roots] [r 0]))]
    (if (empty? frontier)
      acc
      (let [[[id lvl] & more] frontier
            seen?  (contains? acc id)
            acc'   (if seen? acc (assoc acc id lvl))
            kids   (when-not seen? (get children id))
            next   (into (vec more)
                         (for [k kids] [k (inc lvl)]))]
        (recur acc' next)))))

(defn- assign-columns
  "Assign each node a 0-based column index. The encounter order is a
  stable BFS from roots, so siblings cluster horizontally and the
  layout is deterministic over a fixed input.

  Per spec §Layout the v1 horizontal axis is *frame swimlanes*. The
  v1 helper collapses every cascade onto the same swimlane; the
  cross-frame swimlane assignment lands when the frame picker /
  Spec 002 §Cross-frame fx surface is wired into the buffer (out
  of v1 scope)."
  [roots children level-of all-ids]
  (let [;; Walk in BFS order from the roots; pure-data so the encounter
        ;; index is stable.
        walk-order (loop [order    []
                          seen     #{}
                          frontier (vec roots)]
                     (if (empty? frontier)
                       order
                       (let [[id & more] frontier
                             new?  (not (contains? seen id))]
                         (if new?
                           (recur (conj order id)
                                  (conj seen id)
                                  (into (vec more) (get children id)))
                           (recur order seen (vec more))))))
        ;; Append any nodes the BFS missed (defensive — shouldn't
        ;; happen given enrich-cascades' invariants, but keeps the
        ;; helper total over arbitrary input).
        missed     (remove (set walk-order) all-ids)
        full-order (into walk-order missed)
        ;; Per-level column counter. Each new id at level `lvl` gets
        ;; the next unused column within its level.
        per-level  (atom {})]
    (into {}
          (for [id full-order
                :let [lvl (get level-of id 0)
                      col (get @per-level lvl 0)]]
            (do
              (swap! per-level update lvl (fnil inc 0))
              [id col])))))

(defn compute-layout
  "Compute pixel positions for every node in the graph.

  Returns `{:positions {dispatch-id {:x px :y px}} :width W :height H}`.

  - Roots live at the top (y = `default-margin`).
  - Levels stack downward by `default-node-height + default-row-gap`.
  - Within a level, siblings stack left-to-right by
    `default-node-width + default-column-gap`.
  - Width / height are the SVG canvas size that bounds every node
    plus the outer margin.

  Pure data → data. Deterministic given the same input — callers can
  diff two layouts for animation hooks (out of v1 scope)."
  [{:keys [nodes arrows roots] :as _graph}]
  (let [children  (children-by-parent arrows)
        all-ids   (map :dispatch-id nodes)
        level-of  (assign-levels roots children)
        ;; Nodes the level walker missed (orphans without an in-graph
        ;; parent that weren't in :roots) default to level 0.
        level-of  (reduce (fn [m id]
                            (if (contains? m id) m (assoc m id 0)))
                          level-of
                          all-ids)
        column-of (assign-columns roots children level-of all-ids)
        positions (into {}
                        (for [id all-ids
                              :let [lvl (get level-of id 0)
                                    col (get column-of id 0)]]
                          [id {:x (+ default-margin
                                     (* col (+ default-node-width
                                               default-column-gap)))
                               :y (+ default-margin
                                     (* lvl (+ default-node-height
                                               default-row-gap)))}]))
        max-col   (apply max 0 (vals column-of))
        max-lvl   (apply max 0 (vals level-of))
        width     (+ default-margin
                     (* (inc max-col) (+ default-node-width
                                         default-column-gap)))
        height    (+ default-margin
                     (* (inc max-lvl) (+ default-node-height
                                         default-row-gap)))]
    {:positions positions
     :width     width
     :height    height
     :level-of  level-of
     :column-of column-of}))

;; ---- cascade filtering ---------------------------------------------------

(defn- ancestors-of
  "Walk `parent-of`'s chain from `id` upward until a root is hit.
  Returns a set including `id` itself."
  [id parent-by-id]
  (loop [acc #{id}
         cur (get parent-by-id id)]
    (if (or (nil? cur) (contains? acc cur))
      acc
      (recur (conj acc cur) (get parent-by-id cur)))))

(defn- descendants-of
  "Walk `children-by-parent` from `id` downward. Returns a set
  including `id` itself."
  [id children-by-parent]
  (loop [acc      #{id}
         frontier [id]]
    (if (empty? frontier)
      acc
      (let [[cur & more] frontier
            kids         (remove acc (get children-by-parent cur))]
        (recur (into acc kids) (into (vec more) kids))))))

(defn cascade-of
  "Return the set of dispatch-ids that belong to the same cascade
  family as `dispatch-id` — every ancestor walked up through
  `:parent`, plus every descendant walked down through `:children`.

  Pure data → id-set. Used by `filter-to-cascade` and by the
  spec §Find root cause affordance."
  [{:keys [nodes arrows] :as _graph} dispatch-id]
  (when dispatch-id
    (let [parent-by-id (into {} (map (juxt :dispatch-id :parent)) nodes)
          children     (children-by-parent arrows)]
      (set/union (ancestors-of dispatch-id parent-by-id)
                 (descendants-of dispatch-id children)))))

(defn filter-to-cascade
  "Return a new graph containing only the cascade family of
  `dispatch-id`. Used when Time Travel's selected-epoch carries a
  cascade-id — the graph filters to that one cascade family per spec
  §Filter graph to this cascade.

  Pass nil for `dispatch-id` to return the graph unchanged. Pure
  data → data."
  [graph dispatch-id]
  (if (nil? dispatch-id)
    graph
    (let [keep-ids (cascade-of graph dispatch-id)
          nodes'   (filterv #(contains? keep-ids (:dispatch-id %))
                            (:nodes graph))
          arrows'  (filterv (fn [[p c]]
                              (and (contains? keep-ids p)
                                   (contains? keep-ids c)))
                            (:arrows graph))
          roots'   (into #{} (filter keep-ids) (:roots graph))
          ;; Recompute roots: any node whose parent is now missing
          ;; from the filtered set becomes a root in the filtered
          ;; sub-graph.
          roots''  (into roots'
                         (for [{:keys [dispatch-id parent]} nodes'
                               :when (or (nil? parent)
                                         (not (contains? keep-ids parent)))]
                           dispatch-id))
          index'   (into {} (map (juxt :dispatch-id identity)) nodes')]
      {:nodes  (mapv (fn [n]
                       (if (contains? roots'' (:dispatch-id n))
                         (assoc n :root? true)
                         n))
                     nodes')
       :arrows arrows'
       :roots  roots''
       :index  index'})))

;; ---- node selection from an epoch-id ------------------------------------

(defn dispatch-id-of-epoch
  "Resolve an `:rf/epoch-record`'s settling cascade-id by walking its
  `:trace-events` for the first `:dispatch-id`-bearing event. Mirrors
  `time-travel-helpers/dispatch-id-from-epoch` so the panel can route
  a selected-epoch → 'filter to this cascade' without depending on
  the time-travel namespace.

  Pure data → id-or-nil."
  [epoch-record]
  (some (fn [ev]
          (or (get-in ev [:tags :dispatch-id])
              (get-in ev [:tags :parent-dispatch-id])))
        (:trace-events epoch-record)))

;; ---- node visual tokens (pure-data — view consumes via index) -----------

(def origin->fill
  "Per spec §Visual encoding §Fill colour. Mirrors the dark-theme
  tokens in `spec/007-UX-IA.md` §Dark theme tokens — keeping this
  table here keeps the test surface JVM-runnable (the panel imports
  the same map and consumes it during render)."
  {:app    "#7C5CFF"   ; violet (brand / app origin)
   :pair   "#5570FF"   ; indigo
   :story  "#43C3D0"   ; cyan
   :test   "#43C3D0"   ; cyan (test rides the story accent)
   :causa  "#6B7080"}) ; grey-tertiary (Causa's own re-dispatches)

(def status->stroke
  "Per spec §Visual encoding §Border colour. Errors > warnings >
  default — surfaced on the node by `node-border-token`."
  {:error   "#F87171"   ; red
   :warning "#FBBF24"   ; amber
   :default "#2F3441"}) ; border-default token

(defn node-border-token
  "Resolve a node's border token. Errors win over warnings; default
  otherwise."
  [{:keys [error? warning?] :as _node}]
  (cond
    error?   :error
    warning? :warning
    :else    :default))

(defn node-glyph
  "Per spec §Visual encoding §Glyph. ◆ root · ○ child · ◉ selected.
  Pure-data so tests can assert the glyph choice without rendering
  SVG."
  [{:keys [root?] :as _node} selected?]
  (cond
    selected? "◉"
    root?     "◆"
    :else     "○"))
