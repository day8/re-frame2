(ns day8.re-frame2-machines-viz.chart.projection
  "Pure-data projection layer for the MachineChart — the parsed graph
  → xyflow `:nodes` / `:edges` arrays + the elk.js `children` shape.

  rf2-0gmwp (2026-05-21) — extracted from `chart.cljs` so the pure
  projector is JVM-runnable. `chart.cljs` `:require`s `@xyflow/react`
  + `elkjs`, which makes the WHOLE ns JVM-unloadable; the projection
  itself is pure CLJS data → data with no DOM, React, or JS-interop,
  so it lives here where the JVM test corpus can pin it directly
  (mirroring the `chart.layout` parse split).

  ## What this owns

    - `xyflow-graph` — the central projector that turns
      `chart.layout/parse-definition` output + a `{node-id position}`
      map into the xyflow `:nodes` + `:edges` shape, carrying the
      per-node / per-edge `:data` payloads (active / from-highlight /
      to-highlight / sim flags, event labels, tags).
    - `choose-edge-type` — maps a parsed edge to its registered
      xyflow edge-type id.
    - `->elk-children` — the elk.js `children` projection (flat for
      plain machines, nested region containers for parallel ones).
    - The node-size floor constants the elk projection + the node
      renderers share.

  ## What this does NOT own

    - **JS-interop glue** — `->elk-input` (`clj->js` / `#js`),
      `elk-result->positions` (`aget` walk over the elk.js result
      tree), and the async `compute-layout!` promise pass stay in
      `chart.cljs`; they touch JS objects and are not portable to the
      JVM.
    - **Topology parsing** — lives in `chart.layout`."
  (:require [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.theme.tokens :as tokens]
            [day8.re-frame2-machines-viz.visual-constants :as vc]))

;; ---- node-size floor constants -----------------------------------------
;;
;; The elk projection (`elk-child`) and the node renderers
;; (`chart.nodes`) share these floors so a state's measured box and
;; its laid-out slot agree. This is their single canonical home (pure,
;; JVM-readable, so the projection tests can reference them); rf2-kra7h
;; — `chart.nodes` `:require`s this ns and reads them via
;; `projection/<name>` for the renderer's CSS `min-width` /
;; `min-height` rather than re-declaring its own copies.

(def state-node-min-width
  "Minimum width in px for a state node body. xyflow lays out nodes
  based on their measured size; this floor gives every node a
  consistent rhythm without overflowing labels."
  140)

(def state-node-min-height
  "Minimum height in px for a state node body."
  44)

(def compound-node-min-width
  "Minimum width for a compound container."
  220)

(def compound-node-min-height
  "Minimum height for a compound container."
  120)

;; ---- elk.js children projection ----------------------------------------

(def event-node-elk-width
  "elk.js layout width for an event-node — narrower than a state node
  so two adjacent events do not crowd the state row, but wide enough
  for the `event-segment` text + optional `[guard]` chip + `+ action`
  pill (rf2-qo5xy)."
  120)

(def event-node-elk-height
  "elk.js layout height for an event-node — taller than a single text
  line so the action-pill row has space underneath the header
  (rf2-qo5xy)."
  46)

(defn elk-child
  "Build a single elk.js child descriptor for a parsed node (a plain
  CLJS map; `chart`'s `->elk-input` `clj->js`-es the whole tree)."
  [n]
  {:id     (:id n)
   :width  (if (:compound? n) compound-node-min-width state-node-min-width)
   :height (if (:compound? n) compound-node-min-height state-node-min-height)
   :labels [{:text (:label n)}]})

(defn elk-event-child
  "rf2-qo5xy — build an elk.js child descriptor for a SYNTHETIC
  event-node. The events-as-nodes paradigm inserts one of these per
  spec transition (between source state and target state); elkjs lays
  it out alongside the source state's siblings inside the source's
  parent container."
  [parsed-edge]
  {:id     (event-node-id parsed-edge)
   :width  event-node-elk-width
   :height event-node-elk-height
   :labels [{:text (or (:event-label parsed-edge) "")}]})

(defn ->elk-children
  "Project parsed nodes + parsed edges into elk.js's `children` shape.

  Nesting is keyed on `:parent-id`: parallel-region states (rf2-lkwev)
  AND compound substates carry it, so BOTH nest UNDER their container
  and elkjs lays them out inside the container's bounding box (xyflow's
  `parentId` sub-flow then renders them inside the dashed boundary —
  rf2-xh1lm: xyflow v12 reads `parentId`, NOT the pre-v12 `parentNode`).
  Nesting recurses — a compound inside a region, or a compound inside a
  compound, lays out correctly. Each container (region OR compound) gets
  its own `elk.algorithm`/`elk.padding` so the header strip has room and
  the zone gets a clean internal layout; top-level nodes are laid out at
  the root.

  rf2-qo5xy — synthetic event-nodes (one per parsed edge) sit alongside
  state-nodes as elk children of the SOURCE state's parent container
  (top-level when the source has no parent). elk then lays them out
  with the layered algorithm — events flow naturally between states
  per the events-as-nodes paradigm. They carry no children of their
  own."
  [{:keys [nodes edges]}]
  (let [node-by-id (into {} (map (juxt :id identity)) nodes)
        ;; The event-node's parent container == the source state's
        ;; parent. Top-level when the source has no `:parent-id`.
        event-children
        (mapv (fn [e]
                (let [src (get node-by-id (:source e))
                      pid (:parent-id src)]
                  (cond-> (elk-event-child e)
                    pid (assoc ::event-parent pid))))
              edges)
        by-parent (group-by :parent-id nodes)
        events-by-parent (group-by ::event-parent event-children)
        build (fn build [n]
                (let [state-kids (get by-parent (:id n) [])
                      event-kids (get events-by-parent (:id n) [])
                      kids       (concat state-kids event-kids)]
                  (cond-> (elk-child n)
                    (seq kids)
                    (assoc :children (->> kids
                                          (mapv (fn [k]
                                                  (if (contains? k ::event-parent)
                                                    ;; Strip the helper-only key
                                                    ;; before handing to elk.
                                                    (dissoc k ::event-parent)
                                                    (build k)))))
                           :layoutOptions {"elk.algorithm" "layered"
                                           "elk.padding"   "[top=34,left=14,bottom=14,right=14]"}))))
        top-state-children (mapv build (get by-parent nil))
        top-event-children (->> (get events-by-parent nil [])
                                (mapv #(dissoc % ::event-parent)))]
    (vec (concat top-state-children top-event-children))))

;; ---- graph projection (parsed + positions → xyflow nodes/edges) ---------

(defn choose-edge-type
  "Map a parsed edge to its registered xyflow edge-type id (one of the
  keys in `chart.edges/edge-types`).

  `:after`-timer edges render via the dedicated `after` type (it adds
  the `after(<ms>)` label + `data-after-ms` attr the ring overlay
  reads). Every other edge — plain `:on` transitions AND `:always`
  eventless transitions — renders via the canonical `transition` type;
  `:always` carries no distinct edge type (its `always` label segment
  is composed upstream in `chart.layout/edge-label`).

  Note there is deliberately NO `spawn` arm: per Spec 005 `:spawn` /
  `:spawn-all` are state-entry actions that bring CHILD actor machines
  into existence — they are not same-machine transitions, so the parse
  emits no spawn edge for `choose-edge-type` to classify. Spawned
  children surface through the `chart.overlays.spawn-all-join`
  inspector, not as an edge in this chart."
  [edge]
  (if (:after edge) "after" "transition"))

(defn event-variant
  "rf2-qo5xy — bucket a parsed edge by its event variant for the
  events-as-nodes paradigm: `:after` (clock glyph), `:always`
  (infinity glyph), or `:on` (regular event keyword). Pure data → keyword."
  [edge]
  (cond
    (:after edge)   :after
    (:always? edge) :always
    :else           :on))

(defn event-node-id
  "rf2-qo5xy — stable string id for an event-node. The xyflow node
  inserted between the source state and the (optional) target state
  in the events-as-nodes paradigm. Derived from the canonical edge-id
  (`chart.layout/edge-id`) so two transitions sharing source/target/
  event/guard/action keep distinct event-node ids — the same
  collision-tiebreak `chart.layout/parse-flat` applies."
  [edge]
  (str "event__" (:id edge)))

(defn xyflow-graph
  "Project the parsed graph + a `{node-id position}` map into the
  xyflow `:nodes` + `:edges` arrays. Pure fn (no DOM, no React).

  Options:

    :highlight-ids       — rf2-g2svr (closes parity gap G1). A SET of
                           active-leaf node-ids. A node is `:active`
                           when its id ∈ this set. A PARALLEL machine's
                           snapshot has N simultaneously-active leaves
                           (one per region), so passing the set lights
                           up EVERY active region at once — the multi-
                           active highlight Stately renders (§1.2 of
                           `001-Topology-Parity.md`). Resolve it from a
                           snapshot `:state` via
                           `chart.layout/highlight-ids` (handles all
                           three `:state` arms: flat / compound /
                           region-map).
    :highlight-id        — node-id of the active state (the SINGLE-
                           active convenience). Folded into
                           `:highlight-ids` as a singleton so flat /
                           compound callers need not build a set; when
                           both are supplied the union is used. Resolve
                           it via `chart.layout/highlight-id`.
    :from-highlight-id   — node-id of the focused-event lens's
                           origin state.
    :to-highlight-id     — node-id of the focused-event lens's
                           landing state.
    :sim?                — flips the highlight palette to amber.
    :on-state-click      — `(fn [path] ...)` invoked when a state
                           node is clicked.
    :on-edge-click       — rf2-u422r. `(fn [{:keys [event-id from-path
                           to-path]}] ...)` invoked when a transition
                           edge's label is clicked. Threaded onto every
                           edge `:data` as `:onClick` (+ the raw
                           `:eventId` / `:fromPath` / `:toPath` so the
                           edge component can hand the host the
                           originating transition). The on-chart machine
                           simulator (Xray) wires this to send the
                           clicked event into the hermetic sim engine.
                           Edges with no fireable event (`:after` /
                           `:always`) carry the callback too but a nil
                           `:eventId`; the host filters those out. nil
                           omits the wiring entirely (no-op edge labels).
    :edge-points         — rf2-cz8v6 (closes parity gap G2). A map
                           `{edge-id [{:x :y} …]}` of elk's routed
                           bend-points (absolute / flow coordinates;
                           `chart`'s `compute-layout!` sets
                           `elk.json.edgeCoords ROOT` so they share
                           xyflow's frame). When an edge has an entry,
                           its route is attached to the edge `:data` as
                           `:points`, and `chart.edges/transition-edge`
                           draws a smooth poly-path THROUGH those points
                           — routing AROUND nested/parallel containers
                           rather than cutting across them (§1.7 of
                           `001-Topology-Parity.md`). An edge with no
                           entry (or a self-loop, which keeps its
                           dedicated loop path) falls back to the bezier
                           between handles. Defaults to `{}`.
    :fired-edge-ids      — rf2-qeemm (closes parity gap G3). A SET of
                           canonical edge-ids (the EXACT `:id` scheme
                           `chart.layout` mints) that fired THIS epoch.
                           An edge is marked `:fired` when its id ∈ this
                           set; `chart.edges/transition-edge` then paints
                           the fired-this-epoch treatment (emphasised +
                           animated stroke + `data-fired`) ALONG the
                           existing routed path — coexisting with G2's
                           bend-points and G1's active styling. The host
                           (Xray) resolves the set via
                           `panels.machines.trace-state/extract-fired-edge-ids`
                           for the focused epoch; the ids agree with the
                           chart by construction (G3 'single edge-id
                           source of truth'). Distinct from the
                           focused-lens `:from/:to-highlight-id` (which
                           matches by ENDPOINT node-ids): `:fired-edge-ids`
                           matches the EDGE directly, so it lights every
                           traversed arm (microsteps, guard-fork
                           candidates) the lens cannot. Defaults to `#{}`.
    :chart               — rf2-k647w. The resolved visual-constants
                           map for the active `:density`
                           (`visual-constants/chart-for-density`).
                           Threaded into every node/edge `:data` as
                           `:chart` so the xyflow node/edge components
                           — which React invokes OUTSIDE the render's
                           dynamic-binding scope — read their geometry
                           + typography off the payload instead of a
                           hardcoded literal. Defaults to
                           `visual-constants/chart-regular` so callers
                           that omit it (the JVM projection tests, a
                           density-less host) get the regular density
                           pixel-identical to pre-rf2-k647w."
  [{:keys [nodes edges]}
   positions
   {:keys [highlight-id highlight-ids from-highlight-id to-highlight-id sim?
           on-state-click on-edge-click edge-points fired-edge-ids chart]
    :or   {chart vc/chart-regular edge-points {} fired-edge-ids #{}}}]
  (let [;; rf2-g2svr (G1) — the active set unifies the single-active
        ;; (`:highlight-id`) and multi-active (`:highlight-ids`) cases.
        ;; A PARALLEL machine's snapshot has N simultaneously-active
        ;; leaves; `:highlight-ids` carries them all so EVERY active
        ;; region lights up at once. The scalar `:highlight-id` folds in
        ;; as a singleton so flat/compound callers (and the focused-edge
        ;; logic below) need no set. A node is active when its id ∈ the
        ;; union.
        active-ids (cond-> (set highlight-ids)
                     (some? highlight-id) (conj highlight-id))
        ;; rf2-80rm2 (G4) — container ACTIVE chrome. The active set above
        ;; holds active LEAF ids; a parallel-region (or compound) CONTAINER
        ;; reads as active when ANY descendant leaf is active, so an active
        ;; region's chrome (the dashed box + header) emphasises — not just
        ;; the leaf inside it (Stately parity §1.4 of `001-Topology-Parity.md`).
        ;;
        ;; Reuses the G1 `active-ids` directly: walk each active leaf UP its
        ;; `:parent-id` chain (the same id every node already carries — region
        ;; states point at their `region-node-id`, compound substates at their
        ;; parent's `node-id`) and collect every ancestor container id. No
        ;; path-prefix reimplementation; no duplication of the highlight logic.
        ;; A self-consistent compound container lights the same way a region
        ;; does — both are containers with a `:parent-id` chain reaching them.
        parent-of (into {} (keep (fn [n]
                                   (when-let [p (:parent-id n)]
                                     [(:id n) p]))
                                 nodes))
        active-container-ids
        (loop [seeds  (filter active-ids (keys parent-of))
               acc    #{}]
          (if-let [s (first seeds)]
            (let [p (get parent-of s)]
              (recur (if (and p (not (contains? acc p)))
                       (conj (rest seeds) p)
                       (rest seeds))
                     (cond-> acc p (conj p))))
            acc))
        ;; rf2-lkwev — container nodes (parallel regions AND compound
        ;; parents) MUST precede their children in the xyflow nodes
        ;; array (xyflow requires a parentId target to appear before any
        ;; node that references it; the v12 `adoptUserNodes` walk warns
        ;; otherwise — `Parent node ${parentId} not found. Please make
        ;; sure that parent nodes are in front of their child nodes in
        ;; the nodes array.`). The parse already emits parents first;
        ;; sort defensively so the parent-before-child invariant holds.
        proj-nodes
        (mapv (fn [n]
                (let [pos      (get positions (:id n) {:x 0 :y 0})
                      region?  (boolean (:region? n))
                      ;; rf2-80rm2 (G4) — a node is active when it is an
                      ;; active leaf (G1) OR a container reaching an active
                      ;; leaf via the `:parent-id` chain (the active-region
                      ;; chrome). The two sets are disjoint by construction
                      ;; (`active-ids` is leaves, `active-container-ids` is
                      ;; their ancestor containers), so the union is the
                      ;; whole active surface.
                      active?  (or (contains? active-ids (:id n))
                                   (contains? active-container-ids (:id n)))
                      from-hi? (= (:id n) from-highlight-id)
                      to-hi?   (= (:id n) to-highlight-id)
                      base
                      {:id       (:id n)
                       :type     (cond
                                   region?        "parallel-region"
                                   (:compound? n) "compound"
                                   :else          "state")
                       :position {:x (:x pos) :y (:y pos)}
                       :data     (cond-> {:label          (:label n)
                                          :path           (:path n)
                                          :active         active?
                                          :fromHighlight  from-hi?
                                          :toHighlight    to-hi?
                                          :sim            (boolean (and active? sim?))
                                          :initial        (boolean (:initial? n))
                                          :final          (boolean (:final? n))
                                          :compound       (boolean (:compound? n))
                                          :tags           (vec (:tags n))
                                          ;; rf2-ee38b.21 — :entry / :exit
                                          ;; state actions (Spec 005
                                          ;; §State nodes) ride the payload
                                          ;; so state-node paints
                                          ;; `entry / <name>` rows. nil when
                                          ;; the state declares none.
                                          :entry          (:entry n)
                                          :exit           (:exit n)
                                          :chart          chart
                                          :onClick        on-state-click}
                                   region? (assoc :regionId    (:region n)
                                                  :regionIndex (:region-index n)))
                       :draggable false
                       :selectable false}]
                  ;; rf2-a64bi — BOTH region AND compound containers receive
                  ;; `:style {:width :height}` from elk's measured position.
                  ;; The parallel-region renderer (`parallel_region_node`)
                  ;; AND the compound renderer (`compound_node`) both fill
                  ;; their box with `width:100% height:100%`, so xyflow must
                  ;; allocate the box elk measured — otherwise it falls back
                  ;; to `compound-node-min-{width,height}` (220×120), and
                  ;; substates whose parent-relative coords elk computed
                  ;; against the FULL measured extent overflow the smaller
                  ;; fallback box and visually escape the container. The
                  ;; asymmetry (region styled, compound not) was the bug;
                  ;; mirroring the region path here keeps containment.
                  (cond-> base
                    (or region? (:compound? n))
                    (assoc :style {:width  (:width pos)
                                   :height (:height pos)})

                    ;; rf2-xh1lm — xyflow v12 reads `parentId` (NOT
                    ;; `parentNode`, the pre-v12 name retained only in
                    ;; xyflow's CHANGELOG). Without `parentId` xyflow
                    ;; does NOT recognise the child as nested: it
                    ;; interprets `:position` as ABSOLUTE flow coords
                    ;; and ignores `:extent "parent"` clamping, so an
                    ;; ELK parent-relative child (e.g. `{:x 14 :y 34}`
                    ;; under an `:active` container at `{:x 22 :y 240}`)
                    ;; renders at root `(14, 34)` — visually OUTSIDE
                    ;; the parent. Emit `:parentId` so xyflow's
                    ;; `adoptUserNodes` path adopts the child + uses
                    ;; the parent's absolute origin as the offset.
                    (and (not region?) (:parent-id n))
                    (assoc :parentId (:parent-id n)
                           :extent   "parent"))))
              (sort-by #(if (or (:region? %) (:compound? %)) 0 1) nodes))

        ;; rf2-qo5xy — events-as-nodes paradigm. Each parsed transition
        ;; emits ONE event-node (xyflow `type "rf2-event"`) plus one or
        ;; two edges:
        ;;
        ;;   source-state ─→ event-node ─→ target-state  (regular external)
        ;;   source-state ─→ event-node                 (internal: no :target)
        ;;
        ;; Pre-rf2-qo5xy the chart painted transitions as edge LABELS
        ;; between state boxes (event + guard + action floated on the
        ;; line). The events-as-nodes paradigm hoists the event into a
        ;; first-class box so the action attribution (+ guard chip)
        ;; reads cleanly even with several stacked candidates — the
        ;; Stately graph view convention (rf2-qo5xy bead §Shift 1).
        ;;
        ;; The legacy single-edge `:edges` shape (state → state with
        ;; event/guard/action on the label) is dropped: tests that used
        ;; to assert on the edge `:data {:eventLabel ...}` now address
        ;; the event-node's `:data {:eventLabel ...}` instead. The
        ;; cross-hierarchy / sibling / self-loop / fired / focused
        ;; treatments still apply to the incoming + outgoing edges, but
        ;; the visible label rides on the event-node.
        cross-hierarchy?-of
        (fn [src tgt]
          (let [src-parent (get parent-of src)
                tgt-parent (get parent-of tgt)]
            (and (not= src tgt)
                 (not= src-parent tgt-parent))))
        ;; Per-edge derived values + the event-node descriptor for the
        ;; current parsed transition.
        edge-descriptors
        (mapv (fn [e]
                (let [src          (:source e)
                      tgt          (:target e)
                      from-active? (or (contains? active-ids src)
                                       (contains? active-ids tgt))
                      focused?     (and (some? from-highlight-id)
                                        (some? to-highlight-id)
                                        (= src from-highlight-id)
                                        (= tgt to-highlight-id))
                      fired?       (contains? fired-edge-ids (:id e))
                      self-loop?   (= src tgt)
                      internal?    (boolean (:internal? e))
                      ;; A `:*` wildcard `:on` arm is a real transition
                      ;; but NOT a fireable event (Spec 005 §Wildcard).
                      fireable?    (and (nil? (:after e))
                                        (not (:always? e))
                                        (keyword? (:event e))
                                        (not= :* (:event e)))
                      event-id     (when fireable? (:event e))
                      variant      (event-variant e)
                      ev-node-id   (event-node-id e)
                      ;; The event-node nests inside the SAME parent
                      ;; container as the source state (parent-id chain
                      ;; preserved). elkjs lays it out alongside other
                      ;; siblings; xyflow's `parentId` sub-flow does the
                      ;; rest. Top-level states leave `:parent-id` nil.
                      parent-id    (get parent-of src)
                      cross-hier?  (cross-hierarchy?-of src tgt)
                      points       (get edge-points (:id e))]
                  {:edge      e
                   :event-id  event-id
                   :variant   variant
                   :ev-node-id ev-node-id
                   :parent-id parent-id
                   :focused?  focused?
                   :fired?    fired?
                   :from-active? from-active?
                   :self-loop? self-loop?
                   :internal? internal?
                   :cross-hier? cross-hier?
                   :points    points}))
              edges)
        ;; Event-nodes — one per parsed transition. The xyflow node
        ;; renderer (`chart.nodes.event-node`) paints the event header
        ;; (`event-segment` glyph), the `[guard]` chip, and the
        ;; `+ <action>` pill row.
        event-nodes
        (mapv (fn [{:keys [edge variant ev-node-id parent-id
                           focused? fired? internal? event-id]}]
                (let [src (:source edge)
                      src-pos (get positions src {:x 0 :y 0})
                      ev-pos  (get positions ev-node-id
                                   ;; Pre-layout fallback: a small offset
                                   ;; from the source so a no-layout
                                   ;; render does not stack everything at
                                   ;; the origin.
                                   {:x (+ (:x src-pos) 0)
                                    :y (+ (:y src-pos) 80)})]
                  (cond-> {:id        ev-node-id
                           :type      "rf2-event"
                           :position  {:x (:x ev-pos) :y (:y ev-pos)}
                           :data      {:eventLabel  (layout/event-segment edge)
                                       :variant     (name variant)
                                       :afterMs     (:after edge)
                                       :guard       (layout/name-of (:guard edge))
                                       :action      (layout/name-of (:action edge))
                                       :focused     focused?
                                       :fired       fired?
                                       :internal    internal?
                                       :machineLevel (boolean (:machine-level? edge))
                                       :eventId     event-id
                                       :fromPath    (:from-path edge)
                                       :toPath      (:to-path edge)
                                       :onClick     on-edge-click
                                       :chart       chart}
                           :draggable false
                           :selectable false}
                    ;; rf2-xh1lm — the event-node nests inside the same
                    ;; parent the source state nests in, so a transition
                    ;; declared on a compound substate's child sits inside
                    ;; the compound container rather than leaking to the
                    ;; root.
                    parent-id
                    (assoc :parentId parent-id
                           :extent   "parent"))))
              edge-descriptors)
        ;; Inbound edges: source-state → event-node. One per parsed
        ;; transition. No label rides on this edge (the event-node holds
        ;; the event/guard/action text); the edge is structural —
        ;; "this state handles this event".
        inbound-edges
        (mapv (fn [{:keys [edge ev-node-id from-active? focused? fired?
                           cross-hier?]}]
                {:id        (str (:id edge) "__in")
                 :source    (:source edge)
                 :target    ev-node-id
                 :type      "transition"
                 :markerEnd {:type "arrowclosed"
                             :color (cond
                                      fired?    (:accent tokens/tokens)
                                      focused?  (:info tokens/tokens)
                                      from-active? (:info tokens/tokens)
                                      :else     (:border-default tokens/tokens))
                             :width 14
                             :height 14}
                 :data      {:eventLabel ""
                             :eventLineLabel ""
                             :active     from-active?
                             :focused    focused?
                             :fired      fired?
                             :afterMs    nil
                             :guard      nil
                             :action     nil
                             :selfLoop   false
                             :loopIndex  nil
                             :siblingIndex 0
                             :siblingCount 1
                             :crossHierarchy cross-hier?
                             :points     nil
                             :internal   false
                             :machineLevel (boolean (:machine-level? edge))
                             :eventId    nil
                             :fromPath   (:from-path edge)
                             :toPath     (:to-path edge)
                             :onClick    nil
                             :chart      chart
                             :inbound    true
                             :eventNodeId ev-node-id
                             :spec-edge-id (:id edge)}})
              edge-descriptors)
        ;; Outbound edges: event-node → target-state. Omitted for
        ;; internal transitions (`:internal? true`) — the event-node
        ;; hangs with no outgoing arrow per the Stately graph view
        ;; convention for internal handlers ("runs an action, no state
        ;; change").
        outbound-edges
        (->> edge-descriptors
             (remove (fn [{:keys [internal?]}] internal?))
             (mapv (fn [{:keys [edge ev-node-id focused? fired? from-active?
                                cross-hier? points]}]
                     {:id        (str (:id edge) "__out")
                      :source    ev-node-id
                      :target    (:target edge)
                      :type      "transition"
                      :markerEnd {:type "arrowclosed"
                                  :color (cond
                                           fired?    (:accent tokens/tokens)
                                           focused?  (:info tokens/tokens)
                                           from-active? (:info tokens/tokens)
                                           :else     (:border-default tokens/tokens))
                                  :width 18
                                  :height 18}
                      :data      {:eventLabel ""
                                  :eventLineLabel ""
                                  :active     from-active?
                                  :focused    focused?
                                  :fired      fired?
                                  :afterMs    nil
                                  :guard      nil
                                  :action     nil
                                  :selfLoop   false
                                  :loopIndex  nil
                                  :siblingIndex 0
                                  :siblingCount 1
                                  :crossHierarchy cross-hier?
                                  :points     points
                                  :internal   false
                                  :machineLevel (boolean (:machine-level? edge))
                                  :eventId    nil
                                  :fromPath   (:from-path edge)
                                  :toPath     (:to-path edge)
                                  :onClick    nil
                                  :chart      chart
                                  :outbound   true
                                  :eventNodeId ev-node-id
                                  :spec-edge-id (:id edge)}})))
        proj-edges (vec (concat inbound-edges outbound-edges))

        ;; Initial-state markers — a small filled dot wired into each
        ;; `:initial?` state via an unlabelled entry edge. xstate/SCXML
        ;; semantics: every compound level shows its own initial marker.
        ;; The marker shares the state's xyflow coordinate frame (same
        ;; `:parentId` for region/compound children) so it sits just left
        ;; of the state inside its container.
        initial-nodes (filter :initial? nodes)
        marker-nodes
        (mapv (fn [n]
                (let [sid (:id n)
                      pos (get positions sid {:x 0 :y 0})]
                  (cond-> {:id        (str "initial__" sid)
                           :type      "initial-marker"
                           :position  {:x (- (:x pos) 48)
                                       :y (+ (:y pos) 14)}
                           :data      {:targetPath (:path n) :chart chart}
                           :draggable false
                           :selectable false}
                    ;; rf2-xh1lm — see compound substate :parentId
                    ;; comment above. xyflow v12 reads `parentId`.
                    (:parent-id n)
                    (assoc :parentId (:parent-id n)
                           :extent   "parent"))))
              initial-nodes)
        entry-edges
        (mapv (fn [n]
                {:id          (str "initial__" (:id n) "__entry")
                 :source      (str "initial__" (:id n))
                 :target      (:id n)
                 :targetHandle "left"
                 :type        "transition"
                 :markerEnd   {:type "arrowclosed"
                               :color (:border-default tokens/tokens)
                               :width 14
                               :height 14}
                 ;; Entry edges are non-interactive, but carry the full
                 ;; edge `:data` shape (flags + threaded callback/chart)
                 ;; so the "every edge has X" projection invariants hold.
                 :data        {:eventLabel "" :eventLineLabel "" :entry true
                               :active false :focused false :fired false
                               :afterMs nil
                               :guard nil :action nil :selfLoop false
                               ;; rf2-shv82 — entry edges are never self-
                               ;; loops nor cross-hierarchy (a marker→leaf
                               ;; hop inside one container); nil/false so
                               ;; the every-edge :data shape stays whole.
                               :loopIndex nil
                               :crossHierarchy false
                               ;; rf2-j10sm (Phase 2, B) — entry edges are
                               ;; always singleton (one marker → one state),
                               ;; so the leader sibling slot keeps the
                               ;; every-edge :data shape whole.
                               :siblingIndex 0
                               :siblingCount 1
                               ;; rf2-cz8v6 (G2) — entry edges keep the
                               ;; bezier (a short marker→state hop never
                               ;; crosses a container); :points nil so
                               ;; the every-edge :data shape stays whole.
                               :points nil
                               :internal false :machineLevel false
                               :eventId nil :fromPath nil :toPath nil
                               :onClick on-edge-click :chart chart}})
              initial-nodes)]
    ;; rf2-qo5xy — order matters for xyflow: parent containers must
    ;; precede any node that references them via `:parentId`. State /
    ;; region / compound nodes are already sorted parent-first; the
    ;; event-nodes nest into the same parents (we set `:parent-id` to
    ;; the source state's parent), so appending them AFTER `proj-nodes`
    ;; keeps the parent-before-child invariant.
    {:nodes (vec (concat proj-nodes marker-nodes event-nodes))
     :edges (into proj-edges entry-edges)}))
