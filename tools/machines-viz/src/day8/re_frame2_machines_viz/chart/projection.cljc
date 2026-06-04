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
  consistent rhythm without overflowing labels.

  rf2-az6e2 — lifted 140 → 152 to match the title/body box grammar (the
  full-width title strip wants a touch more horizontal rhythm than the
  prior centred-pill body)."
  152)

(def state-node-min-height
  "Minimum height in px for a state node body.

  rf2-az6e2 — lifted 44 → 58 to match the title-strip + body geometry
  (title strip ~24px + a body band for tags / action rows)."
  58)

(def machine-root-node-min-width
  "rf2-vcnvj — minimum width for the synthetic MACHINE-ROOT chip (the
  source of a machine-level top-level `:on` fallback). A compact root-
  context chip, narrower than a state box so it reads as the root anchor
  rather than a peer state."
  120)

(def machine-root-node-min-height
  "rf2-vcnvj — minimum height for the synthetic MACHINE-ROOT chip. A
  single header line — no body band."
  30)

(def compound-node-min-width
  "Minimum width for a compound container.

  rf2-az6e2 — lifted 220 → 260 so the solid title-strip container has
  room for the strip label + the body band above its children."
  260)

(def compound-node-min-height
  "Minimum height for a compound container.

  rf2-az6e2 — lifted 120 → 150 so the title strip + optional metadata
  band do not crowd the nested children."
  150)

;; ---- synthetic event-node helpers --------------------------------------
;;
;; rf2-qo5xy events-as-nodes paradigm: each parsed transition emits ONE
;; synthetic xyflow node sitting between source and target states. These
;; two tiny pure helpers bucket the variant + mint the stable id, and are
;; consumed by BOTH the elk children projection (`elk-event-child` below)
;; AND the main `xyflow-graph` projector further down — so they live
;; above both rather than buried inside the graph-projection section.

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

;; ---- elk.js children projection ----------------------------------------

(def event-node-elk-width
  "elk.js layout width for an event-node — narrower than a state node
  so two adjacent events do not crowd the state row, but wide enough
  for the `event-segment` text + optional `[guard]` chip + `+ action`
  pill (rf2-qo5xy).

  rf2-az6e2 — pulled 120 → 96 so the route chip lays out as a
  SUBORDINATE node, not a peer state box. The event chip's CSS min-width
  (`:event-chip-min-w` 92 regular) sits just under this floor; the
  measure-then-relayout pass still sizes the chip to its real content
  via `(max measured floor)`, but the LAYOUT WEIGHT (the seed ELK uses
  before measurement, and the floor a content-light chip lands on) is
  lower so event chips don't push state rows apart like peers."
  96)

(def event-node-elk-height
  "elk.js layout height for an event-node — taller than a single text
  line so the action-pill row has space underneath the header
  (rf2-qo5xy).

  rf2-az6e2 — pulled 46 → 34 (the route chip is a compact card, not a
  title/body box). Subordinate layout weight relative to a state node's
  58px floor."
  34)

(defn leaf-elk-size
  "rf2-d9ro2 — the elk.js layout size for a LEAF state node, given the
  optional `measured` `{:width :height}` map xyflow reported for that
  node on a prior render (`node.measured`).

  The first ELK pass has no measurement yet (`measured` is nil), so the
  node falls back to the `state-node-min-{width,height}` FLOOR — the same
  constant the old single-pass used. On the measure-then-relayout second
  pass (`chart.cljs`) the host hands back the rendered box; we take the
  MAX of the measured dimension and the floor so a node never lays out
  SMALLER than its CSS `min-{width,height}`, but a node whose CONTENT
  (long label + tag pills + entry/exit action pills, rf2-a2b55) exceeds
  the floor gets the real box ELK must budget for — closing the
  missized/overlapping-topology bug where ELK assumed every node was
  exactly the floor.

  Returns a `{:width :height}` map (both positive)."
  [measured]
  {:width  (max state-node-min-width  (or (:width  measured) 0))
   :height (max state-node-min-height (or (:height measured) 0))})

(defn elk-child
  "Build a single elk.js child descriptor for a parsed node (a plain
  CLJS map; `chart`'s `->elk-input` `clj->js`-es the whole tree).

  rf2-d9ro2 — `measured-dims` is the optional `{node-id {:width :height}}`
  map of xyflow-reported rendered boxes (`node.measured`) from a prior
  render, threaded through `->elk-children`. A LEAF state takes
  `(max measured floor)` per dimension (`leaf-elk-size`) so ELK sizes
  each slot to the node's REAL rendered box rather than the fixed floor.
  COMPOUND containers keep the compound floor as their SEED size — their
  true extent comes from ELK laying out their measured children inside
  (`elk.hierarchyHandling INCLUDE_CHILDREN`), not from a self-measurement
  (the rendered compound box is `width:100% height:100%` of whatever ELK
  allocates, so feeding its measured DOM size back would be circular).
  When `measured-dims` is nil / empty (the first pass) every leaf falls
  back to the floor — identical to the pre-rf2-d9ro2 single-pass."
  ([n] (elk-child n nil))
  ([n measured-dims]
   (merge
     {:id     (:id n)
      :labels [{:text (:label n)}]}
     (cond
       ;; rf2-vcnvj — the synthetic machine-root node is a compact root-
       ;; context chip, not a full state box; it lays out at content size
       ;; (`max measured floor`) against a small floor so it does not push
       ;; the main state column apart like a peer state.
       (:machine-root? n)
       (let [m (get measured-dims (:id n))]
         {:width  (max machine-root-node-min-width  (or (:width  m) 0))
          :height (max machine-root-node-min-height (or (:height m) 0))})

       (:compound? n)
       {:width  compound-node-min-width
        :height compound-node-min-height}

       :else
       (leaf-elk-size (get measured-dims (:id n)))))))

(defn elk-event-child
  "rf2-qo5xy — build an elk.js child descriptor for a SYNTHETIC
  event-node. The events-as-nodes paradigm inserts one of these per
  spec transition (between source state and target state); elkjs lays
  it out alongside the source state's siblings inside the source's
  parent container.

  rf2-d9ro2 — like a leaf state, an event-node renders at CONTENT size
  (event header + optional `[guard]` chip + `+ action` pill row), so on
  the measure-then-relayout second pass we take `(max measured floor)`
  per dimension against the `event-node-elk-{width,height}` floor.
  `measured-dims` is the optional `{node-id {:width :height}}` map; nil
  on the first pass falls back to the floor (pre-rf2-d9ro2 behaviour)."
  ([parsed-edge] (elk-event-child parsed-edge nil))
  ([parsed-edge measured-dims]
   (let [id (event-node-id parsed-edge)
         m  (get measured-dims id)]
     {:id     id
      :width  (max event-node-elk-width  (or (:width  m) 0))
      :height (max event-node-elk-height (or (:height m) 0))
      :labels [{:text (or (:event-label parsed-edge) "")}]})))

(defn order-state-children
  "rf2-ly51l — order a container's STATE children so the initial state
  LEADS its local model order, with the synthetic machine-root annotation
  demoted LAST; every other state keeps its parse order. A stable sort on
  a per-node rank:

    0  initial state            (`:initial?`)  — the preferred anchor
    1  ordinary state           (parse order kept by stable sort)
    2  machine-root annotation  (`:machine-root?`) — a quiet routing
                                 anchor, never a flow start, so it sinks
                                 to the END of the model order

  This is the model-order half of the rf2-ly51l initial-state placement
  SOFT preference (the layout half is `chart.cljs`'s `cycleBreaking.
  strategy DEPTH_FIRST`). It biases TWO ELK decisions toward the initial
  state without forcing any position:

    - DEPTH_FIRST cycle-breaking walks from the SOURCES; an earlier
      model-order source is preferred as the walk root, so leading the
      initial state makes ELK's acyclic-isation start there;
    - LAYER_SWEEP within-layer ordering uses model order as a tiebreaker
      when crossings are equal.

  ELK still runs full crossing-minimisation + node-placement on top, so a
  state can still land off the initial-on-top ideal when the graph
  demands it — preference, not invariant. Applied per container (top
  level + every compound/region via `->elk-children`'s recursion), so a
  nested compound's own `:initial?` child leads ITS local order too."
  [state-nodes]
  (sort-by (fn [n] (cond
                     (:machine-root? n) 2
                     (:initial? n)      0
                     :else              1))
           state-nodes))

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
  own.

  rf2-d9ro2 — the optional `measured-dims` `{node-id {:width :height}}`
  map (xyflow's `node.measured` from a prior render, threaded by
  `chart.cljs`'s measure-then-relayout pass) is forwarded to every
  `elk-child` / `elk-event-child` so leaf states + event-nodes lay out
  at their REAL rendered box rather than the fixed floor. nil / empty on
  the first pass — identical to the pre-rf2-d9ro2 single-pass."
  ([parsed] (->elk-children parsed nil))
  ([{:keys [nodes edges]} measured-dims]
  (let [node-by-id (into {} (map (juxt :id identity)) nodes)
        ;; The event-node's parent container == the source state's
        ;; parent. Top-level when the source has no `:parent-id`.
        event-children
        (mapv (fn [e]
                (let [src (get node-by-id (:source e))
                      pid (:parent-id src)]
                  (cond-> (elk-event-child e measured-dims)
                    pid (assoc ::event-parent pid))))
              edges)
        by-parent (group-by :parent-id nodes)
        events-by-parent (group-by ::event-parent event-children)
        build (fn build [n]
                (let [;; rf2-ly51l — the local initial state leads its
                      ;; container's model order (machine-root sinks last);
                      ;; biases ELK's DEPTH_FIRST source selection + the
                      ;; within-layer tiebreak toward the initial state.
                      state-kids (order-state-children
                                   (get by-parent (:id n) []))
                      event-kids (get events-by-parent (:id n) [])
                      kids       (concat state-kids event-kids)]
                  (cond-> (elk-child n measured-dims)
                    (seq kids)
                    (assoc :children (->> kids
                                          (mapv (fn [k]
                                                  (if (contains? k ::event-parent)
                                                    ;; Strip the helper-only key
                                                    ;; before handing to elk.
                                                    (dissoc k ::event-parent)
                                                    (build k)))))
                           ;; rf2-az6e2 — the container top padding clears
                           ;; the solid title strip (26px regular) PLUS a
                           ;; metadata band for compound tags / entry-exit
                           ;; rows, so children never collide with the
                           ;; header. Side/bottom padding tracks the
                           ;; `:container-body-pad` inset.
                           :layoutOptions {"elk.algorithm" "layered"
                                           "elk.padding"   "[top=44,left=16,bottom=16,right=16]"}))))
        ;; rf2-ly51l — top-level model order: initial state first,
        ;; machine-root annotation last (see `order-state-children`).
        top-state-children (mapv build (order-state-children
                                         (get by-parent nil)))
        top-event-children (->> (get events-by-parent nil [])
                                (mapv #(dissoc % ::event-parent)))]
    (vec (concat top-state-children top-event-children)))))

;; ---- elk.js EDGE projection (rf2-rlq97) ---------------------------------
;;
;; rf2-rlq97 — the elk EDGE descriptors are projected here (pure data),
;; mirroring `->elk-children` for nodes. Before rf2-rlq97 the elk edge
;; objects were built inline in `chart.cljs/->elk-input` (a `mapcat` over
;; the parsed edges) — JS-side and JVM-unloadable, so the edges that ELK
;; routes the topology AROUND were never pinned at the cheap JVM layer.
;; Lifting them into `->elk-edge` here lets the projection test corpus
;; assert the edge-feed (ids + endpoints + label dims) the same way it
;; pins the node-feed.
;;
;; Each parsed transition is decomposed into the events-as-nodes
;; (rf2-qo5xy) edge pair:
;;
;;   source-state ─→ event-node   (`<spec-edge-id>__in`)
;;   event-node   ─→ target-state (`<spec-edge-id>__out`; omitted for
;;                                  internal transitions, which have no
;;                                  `:target`)
;;
;; ## Edge labels (rf2-rlq97)
;;
;; The xstate-style transition text (`event [guard] / action`) rides on
;; the event-NODE (`xyflow-graph` event-nodes; the event-node is the
;; events-as-nodes analogue of a Stately edge label and is ALREADY
;; measured + ELK-laid-out via `elk-event-child` + the d9ro2 measure
;; pass). So the `__in` / `__out` edges themselves carry NO visible
;; label, and ELK must reserve NO label channel for them — feeding label
;; dims onto BOTH the event-node AND its incident edges would
;; double-budget the same text.
;;
;; `->elk-edge` therefore takes an optional `label-dims` map but uses it
;; ONLY for an edge that genuinely renders its OWN label in the renderer
;; (none today under events-as-nodes; the parameter keeps the helper
;; honest + future-proof — a labelled edge type added later threads its
;; MEASURED `{:width :height}` here so ELK's `edgeLabels.placement`
;; reserves space). A nil entry emits an empty zero-size label, which
;; ELK treats as no label to place. This is the edge-label analogue of
;; d9ro2's node measure: dims flow from the rendered DOM into the ELK
;; input rather than being a renderer-side heuristic.

(defn elk-edge-label
  "rf2-rlq97 — build the elk edge `labels` entry for an edge. `text` is
  the visible label string (\"\" when the label rides on the event-node,
  which is the events-as-nodes default). `dims` is the optional MEASURED
  `{:width :height}` of the rendered label; nil emits a zero-size label
  ELK treats as nothing to place. Returns a single-label vector (elk's
  `labels` is always an array)."
  [text dims]
  [(cond-> {:text (or text "")}
     (and dims (pos? (:width dims 0)) (pos? (:height dims 0)))
     (assoc :width (:width dims) :height (:height dims)))])

(defn ->elk-edge
  "rf2-rlq97 — project ONE parsed transition into its elk edge
  descriptor(s) under the events-as-nodes paradigm (rf2-qo5xy):

    [{:id \"<spec-id>__in\"  :sources [src]   :targets [ev]    :labels …}
     {:id \"<spec-id>__out\" :sources [ev]    :targets [tgt]   :labels …}]

  The `__out` edge is OMITTED for an internal transition (no `:target`)
  — its event-node hangs with no outgoing arrow per the Stately
  convention.

  `label-dims` is the optional `{elk-edge-id {:width :height}}` map of
  MEASURED rendered-label boxes, threaded through from the measure pass
  the same way `->elk-children` threads node `measured-dims`. Keyed by
  the elk edge id (`<spec-id>__in` / `__out`). Under events-as-nodes the
  visible text is on the event-NODE so these entries are normally absent
  and the edges carry empty labels — see the section comment above."
  ([e] (->elk-edge e nil))
  ([e label-dims]
   (let [ev-id  (event-node-id e)
         in-id  (str (:id e) "__in")
         out-id (str (:id e) "__out")]
     (cond-> [{:id      in-id
               :sources [(:source e)]
               :targets [ev-id]
               :labels  (elk-edge-label "" (get label-dims in-id))}]
       (not (:internal? e))
       (conj {:id      out-id
              :sources [ev-id]
              :targets [(:target e)]
              :labels  (elk-edge-label "" (get label-dims out-id))})))))

(defn ->elk-edges
  "rf2-rlq97 — project ALL parsed edges into the flat elk `edges` vector
  `chart.cljs/->elk-input` `clj->js`-es onto the elk root graph. The
  events-as-nodes split (`->elk-edge`) means N parsed transitions emit
  up to 2N elk edges. `label-dims` (optional measured-label map) is
  forwarded to every `->elk-edge`."
  ([parsed] (->elk-edges parsed nil))
  ([{:keys [edges]} label-dims]
   (vec (mapcat #(->elk-edge % label-dims) edges))))

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
    :edge-points         — rf2-cz8v6 (closes parity gap G2); rf2-r636q
                           (key-scheme fix). A map `{elk-edge-id
                           [{:x :y} …]}` of elk's routed bend-points
                           (absolute / flow coordinates; `chart`'s
                           `compute-layout!` sets `elk.json.edgeCoords
                           ROOT` so they share xyflow's frame). The KEYS
                           are the elk edge ids `chart.cljs/->elk-input`
                           emits — `<spec-edge-id>__in` (source-state →
                           event-node) and `<spec-edge-id>__out`
                           (event-node → target-state) — under the
                           events-as-nodes paradigm (rf2-qo5xy). The
                           inbound xyflow edge (`<spec-edge-id>__in`)
                           looks up the `__in` route and the outbound
                           (`<spec-edge-id>__out`) looks up the `__out`
                           route, so each edge draws exactly the segment
                           it represents; the route attaches to that
                           edge's `:data {:points}` and
                           `chart.edges/transition-edge` draws a smooth
                           poly-path THROUGH those points — routing
                           AROUND nested/parallel containers rather than
                           cutting across them (§1.7 of
                           `001-Topology-Parity.md`). An edge with no
                           entry (or a self-loop, which keeps its
                           dedicated loop path) falls back to the bezier
                           between handles. Defaults to `{}`.

                           (Before rf2-r636q the lookup used the BARE
                           `<spec-edge-id>`, which the producer never
                           emits post-rf2-qo5xy, so every lookup missed
                           and the whole feature silently fell back to
                           beziers — a dead G2.)
    :edge-labels         — rf2-rlq97. A map `{elk-edge-id {:x :y}}` of
                           elk's COMPUTED label positions (absolute /
                           flow coords; the LABEL analogue of
                           `:edge-points`). Keyed by the same
                           `<spec-edge-id>__in` / `__out` elk edge ids.
                           When present for an edge, the projector
                           attaches it to that edge's `:data {:labelPos}`
                           and `chart.edges/transition-edge` paints the
                           label at elk's placement instead of the
                           geometric middle-segment midpoint — so a
                           labelled edge's text sits where elk reserved a
                           collision-free channel. Empty under
                           events-as-nodes (the transition text rides on
                           the event-NODE, ELK-placed already), so this
                           is normally `{}` and the edge keeps its
                           geometric anchor. Defaults to `{}`.
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
                           pixel-identical to pre-rf2-k647w.
    :palette             — rf2-az6e2. The resolved chart-semantic token
                           map for the active `:theme`
                           (`theme/tokens/chart-tokens` of the
                           theme-palette). Threaded into every node/edge
                           `:data` as `:palette` so the xyflow node/edge
                           components — invoked OUTSIDE the render's
                           dynamic scope — read their structured-grammar
                           colours off the payload, painting the ACTIVE
                           theme (not the hardwired dark alias). Defaults
                           to `(chart-tokens)` (dark) so a theme-less
                           caller still resolves the dark surface."
  [{:keys [nodes edges]}
   positions
   {:keys [highlight-id highlight-ids from-highlight-id to-highlight-id sim?
           on-state-click on-edge-click edge-points edge-labels
           fired-edge-ids chart palette]
    :or   {chart vc/chart-regular edge-points {} edge-labels {}
           fired-edge-ids #{}}}]
  (let [;; rf2-az6e2 — resolve the chart-semantic token map for the
        ;; active theme ONCE. nil → dark chart-tokens (a theme-less
        ;; caller keeps the dark surface). Threaded onto every node/edge
        ;; `:data {:palette}` so the renderers paint the active theme.
        ct (or palette (tokens/chart-tokens))
        ;; rf2-g2svr (G1) — the active set unifies the single-active
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
                                   ;; rf2-vcnvj — the synthetic root node a
                                   ;; machine-level (top-level `:on`) fallback
                                   ;; routes FROM. Painted as a quiet root-
                                   ;; context chip (`chart.nodes/machine-root-
                                   ;; node`), not a state box.
                                   (:machine-root? n) "machine-root"
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
                                          :palette        ct
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
              ;; rf2-vcnvj — the machine-root node leads (it is the source
              ;; of a machine-level fallback's `__in` edge + event-node, and
              ;; xyflow wants a source-node before any edge that references
              ;; it), alongside region/compound parents.
              (sort-by #(if (or (:machine-root? %) (:region? %) (:compound? %)) 0 1) nodes))

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
                      ;; rf2-r636q — reconcile the producer/consumer
                      ;; key scheme. `chart.cljs/->elk-input` splits each
                      ;; parsed transition into TWO elk edges with ids
                      ;; `<spec-edge-id>__in` (source-state → event-node)
                      ;; and `<spec-edge-id>__out` (event-node → target),
                      ;; and `elk-result->positions` keys `:edge-points`
                      ;; by those elk edge ids. The xyflow edges this
                      ;; projector emits carry the SAME `__in` / `__out`
                      ;; ids, so each edge looks up ITS OWN elk route and
                      ;; draws exactly the segment it represents — the
                      ;; inbound edge gets the source→event-node route,
                      ;; the outbound edge gets the event-node→target
                      ;; route. (Previously the lookup used the bare
                      ;; canonical id, which the producer never emits, so
                      ;; every lookup missed and the feature fell back to
                      ;; beziers — silently dead post-rf2-qo5xy.)
                      in-points    (get edge-points (str (:id e) "__in"))
                      out-points   (get edge-points (str (:id e) "__out"))
                      ;; rf2-rlq97 — elk's computed label positions for
                      ;; the two segments (LABEL analogue of the routes).
                      ;; Empty under events-as-nodes (label is on the
                      ;; event-node); present for any labelled edge.
                      in-label-pos  (get edge-labels (str (:id e) "__in"))
                      out-label-pos (get edge-labels (str (:id e) "__out"))]
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
                   :in-points  in-points
                   :out-points out-points
                   :in-label-pos  in-label-pos
                   :out-label-pos out-label-pos}))
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
                                       :chart       chart
                                       :palette     ct}
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
        ;; rf2-idx41 — the two halves of the events-as-nodes route
        ;; (source-state → event-node, event-node → target-state) emit
        ;; structurally-identical xyflow edges: same `:type`, the same
        ;; resting/active/fired marker COLOUR cond, and a `:data` payload
        ;; that differs only in its routed `:points` / `:labelPos`, the
        ;; quiet-vs-primary flag, and the `:inbound`/`:outbound` marker.
        ;; `events-as-nodes-edge` builds one half from those few
        ;; differing values so the shared shape lives in ONE place (the
        ;; two were copy-pasted before, drifting risk on every `:data`
        ;; key add). `half` is `:in` (quiet source→event, 10px arrowhead)
        ;; or `:out` (primary event→target, 18px arrowhead).
        marker-color
        (fn [{:keys [fired? focused? from-active?]}]
          (cond
            fired?       (:edge-fired ct)
            focused?     (:edge-active ct)
            from-active? (:edge-active ct)
            :else        (:edge-quiet ct)))
        events-as-nodes-edge
        (fn [half {:keys [edge ev-node-id from-active? focused? fired?
                          cross-hier?] :as desc} points label-pos]
          (let [in? (= half :in)
                w   (if in? 10 18)]
            {:id        (str (:id edge) (if in? "__in" "__out"))
             :source    (if in? (:source edge) ev-node-id)
             :target    (if in? ev-node-id (:target edge))
             :type      "transition"
             ;; rf2-az6e2 — the `__in` (source→event) half is the QUIET
             ;; segment: a SMALL 10px arrowhead in the quiet colour so the
             ;; PRIMARY 18px arrowhead reads on the `__out` (event→target)
             ;; half and the pair reads as ONE transition route.
             :markerEnd {:type   "arrowclosed"
                         :color  (marker-color desc)
                         :width  w
                         :height w}
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
                         ;; rf2-r636q — the elk route for THIS half
                         ;; (`__in`: source-state → event-node; `__out`:
                         ;; event-node → target-state). nil when elk
                         ;; emitted no route (bezier fallback).
                         :points     points
                         ;; rf2-rlq97 — elk's computed label position (nil
                         ;; under events-as-nodes; renderer falls back to
                         ;; its geometric anchor).
                         :labelPos   label-pos
                         :internal   false
                         :machineLevel (boolean (:machine-level? edge))
                         :eventId    nil
                         :fromPath   (:from-path edge)
                         :toPath     (:to-path edge)
                         :onClick    nil
                         :chart      chart
                         :palette    ct
                         :inbound    in?
                         :outbound   (not in?)
                         ;; rf2-az6e2 — the `__in` half paints thinner + in
                         ;; the quiet colour so the pair reads as one
                         ;; transition with the primary arrowhead on `__out`.
                         :quietSegment in?
                         :eventNodeId ev-node-id
                         :spec-edge-id (:id edge)}}))
        ;; Inbound edges: source-state → event-node. One per parsed
        ;; transition. No label rides on this edge (the event-node holds
        ;; the event/guard/action text); the edge is structural —
        ;; "this state handles this event".
        inbound-edges
        (mapv (fn [{:keys [in-points in-label-pos] :as desc}]
                (events-as-nodes-edge :in desc in-points in-label-pos))
              edge-descriptors)
        ;; Outbound edges: event-node → target-state. Omitted for
        ;; internal transitions (`:internal? true`) — the event-node
        ;; hangs with no outgoing arrow per the Stately graph view
        ;; convention for internal handlers ("runs an action, no state
        ;; change").
        outbound-edges
        (->> edge-descriptors
             (remove :internal?)
             (mapv (fn [{:keys [out-points out-label-pos] :as desc}]
                     (events-as-nodes-edge :out desc out-points out-label-pos))))
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
                           :data      {:targetPath (:path n) :chart chart
                                       :palette ct}
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
                               :color (:edge-quiet ct)
                               :width 12
                               :height 12}
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
                               ;; rf2-rlq97 — entry edges carry no label
                               ;; (unlabelled marker→state hop), so no elk
                               ;; label position; nil keeps the shape whole.
                               :labelPos nil
                               :internal false :machineLevel false
                               :eventId nil :fromPath nil :toPath nil
                               :quietSegment false
                               :onClick on-edge-click :chart chart :palette ct}})
              initial-nodes)]
    ;; rf2-qo5xy — order matters for xyflow: parent containers must
    ;; precede any node that references them via `:parentId`. State /
    ;; region / compound nodes are already sorted parent-first; the
    ;; event-nodes nest into the same parents (we set `:parent-id` to
    ;; the source state's parent), so appending them AFTER `proj-nodes`
    ;; keeps the parent-before-child invariant.
    {:nodes (vec (concat proj-nodes marker-nodes event-nodes))
     :edges (into proj-edges entry-edges)}))
