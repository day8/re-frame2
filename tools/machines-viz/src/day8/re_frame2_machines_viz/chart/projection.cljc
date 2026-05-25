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

(defn elk-child
  "Build a single elk.js child descriptor for a parsed node (a plain
  CLJS map; `chart`'s `->elk-input` `clj->js`-es the whole tree)."
  [n]
  {:id     (:id n)
   :width  (if (:compound? n) compound-node-min-width state-node-min-width)
   :height (if (:compound? n) compound-node-min-height state-node-min-height)
   :labels [{:text (:label n)}]})

(defn ->elk-children
  "Project parsed nodes into elk.js's `children` shape.

  Nesting is keyed on `:parent-id`: parallel-region states (rf2-lkwev)
  AND compound substates carry it, so BOTH nest UNDER their container
  and elkjs lays them out inside the container's bounding box (xyflow's
  `parentId` sub-flow then renders them inside the dashed boundary —
  rf2-xh1lm: xyflow v12 reads `parentId`, NOT the pre-v12 `parentNode`).
  Nesting recurses — a compound inside a region, or a compound inside a
  compound, lays out correctly. Each container (region OR compound) gets
  its own `elk.algorithm`/`elk.padding` so the header strip has room and
  the zone gets a clean internal layout; top-level nodes are laid out at
  the root."
  [{:keys [nodes]}]
  (let [by-parent (group-by :parent-id nodes)
        build (fn build [n]
                (let [kids (get by-parent (:id n))]
                  (cond-> (elk-child n)
                    (seq kids)
                    (assoc :children (mapv build kids)
                           ;; Container lays out its own children;
                           ;; padding leaves top room for the header strip
                           ;; (region label / compound title).
                           :layoutOptions {"elk.algorithm" "layered"
                                           "elk.padding"   "[top=34,left=14,bottom=14,right=14]"}))))]
    (mapv build (get by-parent nil))))

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

        ;; rf2-shv82 (Issue 2) — fan multiple self-loops on the same
        ;; source so each gets a unique perimeter slot + label position
        ;; instead of stacking at the same coords (the bug: 3 self-loops
        ;; on `:disconnected` rendered overlapping garbled text). We
        ;; assign each self-loop a STABLE per-source ordinal (0..N-1) in
        ;; emission order; `edge-path` uses the ordinal to rotate the
        ;; loop's angular slot around the node's perimeter, the
        ;; xstate/Stately "fan multiple events on one transition" read.
        self-loop-index-of
        (let [seen (volatile! {})]
          (fn [e]
            (when (= (:source e) (:target e))
              (let [src (:source e)
                    n   (get @seen src 0)]
                (vswap! seen assoc src (inc n))
                n))))
        ;; rf2-shv82 (Issue 3) — flag cross-hierarchy edges (source and
        ;; target sit in different parent containers). The label-
        ;; positioning logic uses this to anchor the label near the
        ;; SOURCE-SIDE first bend point (just outside the container the
        ;; edge exits) instead of at the routed midpoint, which can land
        ;; far from the visual origin for a deeply-nested cross-hierarchy
        ;; edge (Stately Studio's convention).
        cross-hierarchy?-of
        (fn [e]
          (let [src-parent (get parent-of (:source e))
                tgt-parent (get parent-of (:target e))]
            (and (not= (:source e) (:target e))
                 (not= src-parent tgt-parent))))
        proj-edges
        (mapv (fn [e]
                (let [from-active? (or (contains? active-ids (:source e))
                                       (contains? active-ids (:target e)))
                      focused?     (and (some? from-highlight-id)
                                        (some? to-highlight-id)
                                        (= (:source e) from-highlight-id)
                                        (= (:target e) to-highlight-id))
                      ;; rf2-qeemm (G3) — the edge fired THIS epoch when its
                      ;; canonical id ∈ `:fired-edge-ids` (matched directly,
                      ;; not by endpoint node-ids like `focused?`). The set
                      ;; is the host's `extract-fired-edge-ids` result, whose
                      ;; ids agree with `(:id e)` by construction.
                      fired?       (contains? fired-edge-ids (:id e))
                      ;; A self-transition (source == target) renders as a
                      ;; loop, not a degenerate near-zero bezier; the edge
                      ;; component reads the `:selfLoop` flag.
                      self-loop?   (= (:source e) (:target e))
                      ;; rf2-shv82 (Issue 2) — ordinal among self-loops on
                      ;; this source (0 for the first, 1 for the next, …);
                      ;; nil when not a self-loop. Drives the perimeter
                      ;; rotation in `edge-path`.
                      loop-index   (self-loop-index-of e)
                      ;; rf2-shv82 (Issue 3) — cross-hierarchy flag for the
                      ;; label-placement adjustment in `edge-path`.
                      cross-hier?  (cross-hierarchy?-of e)
                      ;; rf2-qeemm (G3) — the fired-this-epoch arrowhead reads
                      ;; in the FIRED hue (`:accent`, distinct from the
                      ;; focused/active `:info`); fired wins over focused/active
                      ;; so a traversed edge stands out as "what just happened".
                      ;; Palette delegated to Figma (no new token).
                      marker-color (cond
                                     fired?                  (:accent tokens/tokens)
                                     (or focused? from-active?) (:info tokens/tokens)
                                     :else                   (:border-default tokens/tokens))
                      ;; A `:*` wildcard `:on` arm is a real transition
                      ;; but NOT a fireable event (Spec 005 §Wildcard —
                      ;; it matches "any otherwise-unhandled event").
                      ;; Excluding it keeps `:eventId` nil so the on-chart
                      ;; sim can't dispatch a literal `[:* ...]` (same
                      ;; inert posture as `:after` / `:always`).
                      fireable?    (and (nil? (:after e))
                                        (not (:always? e))
                                        (keyword? (:event e))
                                        (not= :* (:event e)))
                      event-id     (when fireable? (:event e))
                      ;; rf2-cz8v6 (G2) — elk's routed bend-points for
                      ;; this edge (absolute coords). Self-loops keep
                      ;; their dedicated loop path, so they never carry
                      ;; points even if elk emitted a degenerate route.
                      points       (when-not self-loop?
                                     (get edge-points (:id e)))]
                  {:id     (:id e)
                   :source (:source e)
                   :target (:target e)
                   :type   (choose-edge-type e)
                   :markerEnd {:type "arrowclosed"
                               :color marker-color
                               :width 18
                               :height 18}
                   :data   {:eventLabel (:event-label e)
                            :active     from-active?
                            :focused    focused?
                            ;; rf2-qeemm (G3) — fired-this-epoch flag the
                            ;; edge component reads to paint the FIRED
                            ;; treatment + surface `data-fired`.
                            :fired      fired?
                            :afterMs    (:after e)
                            :guard      (layout/name-of (:guard e))
                            :action     (layout/name-of (:action e))
                            :selfLoop   self-loop?
                            ;; rf2-shv82 (Issue 2) — fan ordinal for the
                            ;; self-loop perimeter rotation in `edge-path`.
                            ;; Nil for non-self-loops.
                            :loopIndex  loop-index
                            ;; rf2-shv82 (Issue 3) — cross-hierarchy flag
                            ;; for the label-position shift in `edge-path`
                            ;; (source-side bend point instead of midpoint).
                            :crossHierarchy cross-hier?
                            ;; rf2-cz8v6 (G2) — elk's routed bend-points
                            ;; (a `[{:x :y} …]` vector in absolute /
                            ;; flow coords) when elk computed a route;
                            ;; the edge component draws a smooth poly-
                            ;; path THROUGH them (around nested
                            ;; containers). nil → the bezier fallback.
                            :points       points
                            ;; rf2-ee38b.21 — an internal self-transition
                            ;; (omit :target) runs only :action; the
                            ;; renderer draws it as a self-loop with no
                            ;; exit/entry re-trigger affordance.
                            :internal     (boolean (:internal? e))
                            ;; A machine-level (top-level :on) fallback
                            ;; transition every state inherits.
                            :machineLevel (boolean (:machine-level? e))
                            :eventId    event-id
                            :fromPath   (:from-path e)
                            :toPath     (:to-path e)
                            :onClick    on-edge-click
                            :chart      chart}}))
              edges)

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
                 :data        {:eventLabel "" :entry true
                               :active false :focused false :fired false
                               :afterMs nil
                               :guard nil :action nil :selfLoop false
                               ;; rf2-shv82 — entry edges are never self-
                               ;; loops nor cross-hierarchy (a marker→leaf
                               ;; hop inside one container); nil/false so
                               ;; the every-edge :data shape stays whole.
                               :loopIndex nil
                               :crossHierarchy false
                               ;; rf2-cz8v6 (G2) — entry edges keep the
                               ;; bezier (a short marker→state hop never
                               ;; crosses a container); :points nil so
                               ;; the every-edge :data shape stays whole.
                               :points nil
                               :internal false :machineLevel false
                               :eventId nil :fromPath nil :toPath nil
                               :onClick on-edge-click :chart chart}})
              initial-nodes)]
    {:nodes (into proj-nodes marker-nodes)
     :edges (into proj-edges entry-edges)}))
