(ns day8.re-frame2-machines-viz.chart.projection
  "Pure-data projection layer for the MachineChart — the parsed graph
  → xyflow `:nodes` / `:edges` arrays + the elk.js `children` shape.

  The projector is pure CLJS data → data with no DOM, React, or
  JS-interop, so it lives here where the JVM test corpus can pin it
  directly. (`chart.cljs` `:require`s `@xyflow/react` + `elkjs`, which
  would make the whole ns JVM-unloadable.) This mirrors the
  `chart.layout` parse split.

  ## What this owns

    - `xyflow-graph` — the central projector that turns
      `chart.layout/project-definition` output + a `{node-id position}`
      map into the xyflow `:nodes` + `:edges` shape, carrying the
      per-node / per-edge `:data` payloads (active / from-highlight /
      to-highlight / sim flags, event labels, tags). Every projected
      edge is the canonical transition type — under events-as-nodes
      the `:after`-timer specifics ride the event-NODE (`:variant`
      after + `:afterMs`), not a distinct edge type.
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
            [day8.re-frame2-machines-viz.chart.context-redaction :as ctx-redact]
            [day8.re-frame2-machines-viz.theme.tokens :as tokens]
            [day8.re-frame2-machines-viz.visual-constants :as vc]))

;; ---- node-size floor constants -----------------------------------------
;;
;; The elk projection (`elk-child`) and the node renderers
;; (`chart.nodes`) share these floors so a state's measured box and
;; its laid-out slot agree. This is their single canonical home (pure,
;; JVM-readable, so the projection tests can reference them):
;; `chart.nodes` `:require`s this ns and reads them via
;; `projection/<name>` for the renderer's CSS `min-width` /
;; `min-height` rather than re-declaring its own copies.

(def state-node-min-width
  "Minimum width in px for a state node body. xyflow lays out nodes
  based on their measured size; this floor gives every node a
  consistent rhythm without overflowing labels.

  152px matches the title/body box grammar — the full-width title strip
  wants a touch more horizontal rhythm than a centred-pill body would."
  152)

(def state-node-min-height
  "Minimum height in px for a state node body.

  58px matches the title-strip + body geometry (title strip ~24px + a
  body band for tags / action rows)."
  58)

(def machine-root-node-min-width
  "Minimum width for the synthetic MACHINE-ROOT chip (the source of a
  machine-level top-level `:on` fallback). A compact root-context chip,
  narrower than a state box so it reads as the root anchor rather than a
  peer state."
  120)

(def machine-root-node-min-height
  "Minimum height for the synthetic MACHINE-ROOT chip. A single header
  line — no body band."
  30)

(def compound-node-min-width
  "Minimum width for a compound container. 260px gives the solid
  title-strip container room for the strip label + the body band above
  its children."
  260)

(def compound-node-min-height
  "Minimum height for a compound container. 150px keeps the title strip
  + optional metadata band from crowding the nested children."
  150)

;; ---- initial-marker glyph offsets --------------------------------------
;;
;; The initial-state marker node is positioned at a FIXED, SMALL offset from
;; its state so the fixed glyph (`chart.nodes/initial-marker` — dot + Q-hook
;; + triangle arrow) reads as a SHORT hook that ends JUST OUTSIDE the state's
;; near (left) edge — the Stately/xstate-viz `InitialEdgeViz` signature (a
;; visible gap pointing AT the edge, NOT penetrating it).
;;
;; The dot offset is DECOUPLED from the arrow-tip position. The node sits
;; `initial-marker-x-offset` (26) px left of the state (so the dot CENTRE
;; lands `offset - dot-x` px outside the edge — `state.x - 19` at regular
;; density), but the glyph's arrowhead tip is drawn at node-local x =
;; `(offset - initial-marker-tip-gap)`, so the absolute tip lands at
;; `state.x - gap` — a clean ~`gap`px gap OUTSIDE the edge. The decoupling
;; keeps the tip from landing on `state.x` (the edge) and overshooting INTO
;; nested states once xyflow's `:extent "parent"` clamps a deeply-negative
;; marker position rightward.

(def initial-marker-x-offset
  "Horizontal offset (px) of the initial-marker node LEFT of its state.
  SMALL (the Stately short-hook span): the filled dot's CENTRE lands
  `(offset - dot-x)` px outside the state's left edge — `state.x - 19` at
  regular density (offset 26, `dot-x = pseudo-radius + 1 = 7`). The arrow
  tip is drawn further right in the glyph (`offset - initial-marker-tip-gap`)
  so the tip ends just OUTSIDE the edge, not on/through it.

  26 gives the glyph room for a SMALL Stately-sized arrowhead AND a short
  FORWARD-flowing hook (dot → down-right curve → arrowhead). Paired with
  the small arrowhead in `chart.nodes/initial-marker`, it yields a clean
  dot → forward-hook → small-arrow-into-edge unit (a tighter offset with
  an oversized arrowhead would run the hook BACKWARDS).

  The offset also LENGTHENS the visible hook arm (Stately-aligned glyph
  tuning). The arm length is `end-x - dot-x`; with the arrowhead-ceiling at
  6 (below) a longer `tip-x` (= offset − tip-gap) is REQUIRED to keep
  `end-x = tip-x − ah > dot-x` in every density — so 26 lands a clearly-
  visible arm AND a larger arrowhead clear of the dot."
  26)

(def initial-marker-tip-gap
  "The visible GAP (px) between the initial-glyph arrow tip and the state's
  left edge. The tip is drawn at node-local x =
  `(initial-marker-x-offset - initial-marker-tip-gap)`, so its ABSOLUTE x is
  `state.x - initial-marker-tip-gap` — a small clean gap OUTSIDE the edge,
  the Stately/xstate-viz `endPoint.x = node.x - 10` signature. The glyph
  points AT the edge; it never penetrates the state."
  7)

(def initial-marker-y-offset
  "Vertical offset (px) of the initial-marker node BELOW its state's top
  edge — roughly the title-strip centre, so the glyph's arrow points into
  the state's near edge near its title row."
  14)

(def initial-marker-left-extent
  "The px the initial-state glyph extends LEFT of its target state's near
  (left) edge — i.e. how far a container must reserve on its LEFT so a
  NESTED initial substate's marker sits fully INSIDE the container border
  instead of spilling past it.

  The marker node is positioned at `state.x - initial-marker-x-offset`
  (it sits that far left of the state). Inside that node's local frame the
  filled dot is centred at node-local `dot-x = pseudo-radius + 1`. The
  reservation anchors to the dot's GEOMETRY-radius left edge — node-local
  x = `dot-x - pseudo-radius = (pseudo-radius + 1) - pseudo-radius = 1`
  (`initial-marker-glyph`) — NOT the slightly-narrower PAINTED left edge at
  x = `dot-x - dot-paint-r = 1.5` (the dot is painted at `pseudo-radius − 0.5`).
  Anchoring to the geometry-radius edge is the conservative
  choice: it reserves the wider extent, so the painted ink (which only
  reaches x=1.5) always lands INSIDE the reservation. So the dot's
  geometry-radius left edge lands at absolute
  `(state.x - initial-marker-x-offset) + 1 = state.x - (initial-marker-x-offset - 1)`
  — i.e. `initial-marker-x-offset - 1` px LEFT of the state's near edge,
  independent of density (the +1 dot inset is density-independent).

  Equal to `initial-marker-x-offset` (one px of slack over the bare
  `offset - 1` extent), so a container reserving this much LEFT padding
  lands the dot's left edge ~1px INSIDE its border — the clean Stately
  inset, never on/through it. `container-elk-padding`'s
  `reserve-initial-marker?` arm takes `(max container-body-pad
  initial-marker-left-extent)` so the reservation only ever GROWS the
  inset, never shrinks a body-pad already wider than the glyph."
  initial-marker-x-offset)

(defn initial-marker-glyph
  "PURE geometry of the initial-state glyph (filled dot + single Q-hook +
  small triangle arrowhead), in the marker node's LOCAL coordinate frame,
  given the active density's `pseudo-radius` (the dot radius). Single-
  sources the math the `chart.nodes/initial-marker` renderer paints AND the
  projection test asserts, so the FORWARD-FLOW invariant (`end-x > dot-x`)
  cannot silently regress.

  Layout (node-local x; the state's left edge is at x=`initial-marker-x-
  offset`, since the node sits that far LEFT of the state):

    - `dot-x` — the filled dot's centre, inset so its left edge clears x=0;
    - `tip-x` — the arrowhead tip, at `offset - tip-gap`, so its ABSOLUTE x
      is `state.x - tip-gap` (a small gap OUTSIDE the edge, pointing AT it);
    - `ah`    — the SMALL Stately-sized arrowhead side (≈ dot diameter / 2,
      clamped to a 4–6px band) — NOT the oversized `arrow-width-entry`,
      which would drive a backwards hook;
    - `end-x` — the arrowhead BASE (`tip-x - ah`), where the stroked hook
      meets the triangle. The glyph reads as ONE clean unit IFF
      `end-x > dot-x` (the hook flows dot → down-and-RIGHT into the head).

  Returns a map of the resolved local-frame coordinates. Pure."
  [pseudo-radius]
  (let [;; SMALL Stately-sized head: ≈ dot diameter / 2, but CLAMPED to a
        ;; 4–6px band so it stays Stately-small in EVERY density (Stately's
        ;; marker is a fixed ~5×6 regardless of node size). An uncapped
        ;; `pseudo-radius`-scaled head would squeeze the forward hook run in
        ;; cosy. The 6px ceiling gives a slightly larger arrowhead; it tunes
        ;; with `initial-marker-x-offset` (the bigger head lowers
        ;; `end-x = tip-x − ah`, so the offset must keep `end-x > dot-x`).
        ah    (-> (dec pseudo-radius) (max 4) (min 6))
        tip-x (- initial-marker-x-offset initial-marker-tip-gap)
        dot-x (+ pseudo-radius 1)
        end-x (- tip-x ah)]
    {:ah ah :tip-x tip-x :dot-x dot-x :end-x end-x}))

;; ---- synthetic event-node helpers --------------------------------------
;;
;; Under the events-as-nodes paradigm each parsed transition emits ONE
;; synthetic xyflow node sitting between source and target states. These
;; two tiny pure helpers bucket the variant + mint the stable id, and are
;; consumed by BOTH the elk children projection (`elk-event-child` below)
;; AND the main `xyflow-graph` projector further down — so they live
;; above both rather than buried inside the graph-projection section.

(defn event-variant
  "Bucket a parsed edge by its event variant for the events-as-nodes
  paradigm: `:after` (clock glyph), `:always` (infinity glyph),
  `:on-done` (completion ✓ done chip — the XState `onDone`
  compound/parallel completion transition), or `:on` (regular event
  keyword). Pure data → keyword."
  [edge]
  (cond
    (:on-done? edge) :on-done
    (:after edge)    :after
    (:always? edge)  :always
    :else            :on))

(defn event-node-id
  "Stable string id for an event-node. The xyflow node inserted between
  the source state and the (optional) target state in the events-as-nodes
  paradigm. Derived from the canonical edge-id (`chart.layout/edge-id`) so
  two transitions sharing source/target/event/guard/action keep distinct
  event-node ids — the same collision-tiebreak `chart.layout/project-flat`
  applies."
  [edge]
  (str "event__" (:id edge)))

;; ---- guarded-fork branch-order -----------------------------------------
;;
;; Stately renders a guarded multi-branch fork — the gate machine's
;; `:gate/check` 3-way (`[{:guard :gate-high? …} {:guard :gate-low? …}
;; {:target :rejected}]`) — with NUMBERED PRIORITY BADGES (①②③) on each
;; branch chip, communicating the DETERMINISTIC ORDER the engine evaluates
;; the guards in (first-pass-wins). re-frame2's candidate vector IS
;; deterministically ordered (the vector index is the priority), and the
;; parse (`chart.layout/collect-state-edges` → `project-flat`) PRESERVES
;; that order in the `:edges` vector — so the branch-order index is simply
;; the candidate's position among its same-source / same-trigger siblings.
;;
;; This helper derives, from the ORDERED `:edges` vector, a
;; `{edge-id → 1-based-priority}` map for every edge that is one branch of
;; a GENUINE guarded fork. The numbered badge is the additive Stately
;; affordance; the per-branch `IF <guard>` wording is the IF-guard
;; divergence (parity spec §3.2) and is untouched.

(defn fork-trigger-key
  "The grouping key that decides whether two edges leaving the SAME source
  belong to the SAME guarded fork: they must share the same TRIGGER. A
  trigger is one of:

    - an `:on` event keyword (`:gate/check`)         → `[:on <event>]`
    - an `:after` timer delay (`5000`)               → `[:after <ms>]`
    - an eventless `:always` continuation            → `[:always]`

  Two same-source candidates under the SAME trigger form an ordered
  candidate vector (first-guard-pass-wins); under DIFFERENT triggers they
  are independent transitions, never a fork. `:gate/set` and `:gate/check`
  both leave `:idle` but carry different `:event`s, so they never group —
  only the three `:gate/check` candidates do.

  Pure data → key vector."
  [edge]
  (cond
    (:after edge)   [:after (:after edge)]
    (:always? edge) [:always]
    :else           [:on (:event edge)]))

(defn fork-groups
  "The SHARED definition of a genuine guarded multi-branch fork, returned
  as a seq of ORDERED candidate-edge groups (each group is the same-source
  / same-trigger candidate vector, in first-pass-wins order). The single
  source of truth `fork-order-by-edge-id` (the numbered badges) AND
  `fork-connector-edges` (the dotted evaluation-order connector) both build
  on.

  A guarded fork is a group of edges sharing the SAME source AND the SAME
  `fork-trigger-key` with:

    - 2+ members (a multi-branch fork; a single transition is NOT a fork),
      AND
    - at least one member carrying a `:guard` (it is a GUARDED fork — the
      branch order is meaningful precisely because the guards are tried in
      priority order; a guardless same-trigger group has no ordered
      evaluation semantics to communicate).

  The per-group order is the candidates' position in the ORDERED `:edges`
  vector — which `chart.layout` preserves from the source candidate vector
  (the engine's first-pass-wins order). `group-by` preserves the per-group
  order of first appearance, and the parse lays a source's candidates down
  contiguously in vector order, so each returned group is already in
  priority order (no re-sort needed).

  `:internal?` self-transitions (action-only, no `:target`) are still
  candidates of their trigger group and counted in the order — an internal
  guarded branch is a real evaluated candidate. (`:on-done` / `:machine-
  level?` edges never share a source with a normal trigger group, so they
  fall out naturally as singletons and are filtered.)

  Returns a seq of edge-vectors (one per qualifying fork). Pure data."
  [edges]
  (->> edges
       (group-by (juxt :source fork-trigger-key))
       vals
       (filter (fn [group]
                 (and (>= (count group) 2)
                      (some :guard group))))))

(defn fork-order-by-edge-id
  "Map each edge-id to its 1-based branch-priority index WHEN that edge is
  one branch of a genuine guarded multi-branch fork (`fork-groups`).

  Within a qualifying group the priority is the candidate's position in
  the ORDERED `:edges` vector — which `chart.layout` preserves from the
  source candidate vector (the engine's first-pass-wins order). So the
  gate fork yields `gate-high? → 1`, `gate-low? → 2`, `rejected → 3`.

  Returns `{edge-id 1-based-index}` for fork branches only; non-fork edges
  are absent. Pure data → map."
  [edges]
  (->> edges
       (fork-groups)
       (into {}
             (mapcat (fn [group]
                       (map-indexed (fn [i e] [(:id e) (inc i)]) group))))))

;; ---- guarded-fork branch LAYOUT ORDER ----------------------------------
;;
;; Stately stacks a guarded fork's branches in PRIORITY ORDER (1, 2, 3) so
;; the dotted evaluation-order connector reads as a clean monotonic line.
;; ELK's LAYER_SWEEP crossing-minimisation, by contrast, freely REORDERS
;; the same-rank branch event-nodes (the gate fork lands 3,1,2 left-to-
;; right), so the connector — which links the branches in priority order
;; 1→2→3 — would otherwise have to cross over itself (a bow-tie weave).
;;
;; Each fork-branch EVENT-NODE's within-layer order is pinned to its
;; priority index via the `elk.position` hint, paired with the container's
;; `elk.layered.crossingMinimization.semiInteractive` (`fork-branch-
;; container-ids` flags the holding containers). semiInteractive constrains
;; ONLY the nodes that carry an `elk.position` — every other node is still
;; freely crossing-minimised — so the pin is SURGICAL: it orders the three
;; branches and touches nothing else.

(defn fork-branch-event-positions
  "Map each GUARDED-FORK branch EVENT-NODE id to its 1-based priority
  index, for the `elk.position` within-layer ordering hint. Reuses
  `fork-order-by-edge-id` (the single source of fork-branch priority — same
  index as the numbered badge + the dotted connector) and re-keys it from
  the spec edge-id to the synthetic event-node id ELK actually lays out.

  Returns `{event-node-id 1-based-index}` for fork branches only. Pure."
  [edges]
  (let [by-id (into {} (map (juxt :id identity)) edges)]
    (reduce-kv (fn [m edge-id idx]
                 (assoc m (event-node-id (get by-id edge-id)) idx))
               {}
               (fork-order-by-edge-id edges))))

(defn fork-branch-container-ids
  "The SET of container ids (the `:parent-id` each fork's branch
  event-nodes lay out under; `nil` == the root container) that hold at
  least one guarded multi-branch fork. A container in this set gets
  `elk.layered.crossingMinimization.semiInteractive = true` so the
  `elk.position` hints on its fork-branch event-nodes are honoured; every
  other container keeps the default (full crossing-minimisation), so the
  semi-interactive constraint never touches a non-fork layout.

  A fork's branches share the SAME source state, and the event-nodes lay
  out under the SOURCE state's parent (`->elk-children`'s `events-by-parent`
  keys on the source's `:parent-id`) — so the container is that source's
  `:parent-id`. Pure."
  [{:keys [nodes edges]}]
  (let [node-by-id (into {} (map (juxt :id identity)) nodes)]
    (->> (fork-groups edges)
         (map (fn [group]
                (let [src (:source (first group))]
                  (:parent-id (get node-by-id src)))))
         (into #{}))))

(defn fork-connector-edges
  "DECORATIVE dotted evaluation-order connector edges across the branches
  of a guarded multi-branch fork (`fork-groups`).

  Stately joins the branches of a guarded fork (the gate machine's
  `:gate/check` 3-way) with a DOTTED connector linking the numbered
  branches IN ORDER (1→2→3), visually reinforcing the first-pass-wins
  evaluation order the numbered badges (`fork-order-by-edge-id`) already
  annotate. This is the connector half of that affordance.

  For each fork group of N branches it emits N-1 connector edges, linking
  each branch's EVENT-NODE (`event-node-id`) to the next in priority order
  (branch 1's event-node → branch 2's, branch 2's → branch 3's, …). The
  edges are RENDER-ONLY: they are appended to the xyflow `:edges` array by
  `xyflow-graph` AFTER the ELK layout pass, and are NEVER fed to ELK
  (ELK's input edges come from `->elk-edges`, driven by the parsed graph
  alone), so the connector cannot perturb a single node position. They
  carry no route `:points` (so the renderer draws a straight dotted line
  between the two event-node handles), no arrowhead, and no label —
  `edges.cljs` paints them as a quiet dotted line off the `:forkConnector`
  flag.

  `ct` is the resolved chart-token map so the connector picks up the
  active theme's neutral hue (an order annotation, matching the badge's
  posture — not a runtime edge); `chart` is the resolved density map,
  threaded onto `:data {:chart}` like every other edge for shape parity
  (the connector paints no label, so the typography it carries is inert).

  Returns a vector of xyflow edge maps (empty when no guarded fork
  exists). Pure data."
  [edges ct chart]
  (->> (fork-groups edges)
       (mapcat
         (fn [group]
           (map (fn [[from to]]
                  {:id     (str "fork-connector__"
                                (:id from) "__" (:id to))
                   :source (event-node-id from)
                   :target (event-node-id to)
                   :type   "transition"
                   ;; ANCHOR the connector to EXPLICIT handles rather than
                   ;; relying on xyflow's default handle pick. The branch
                   ;; event-nodes lay out LEFT-TO-RIGHT in priority order
                   ;; (1→2→3, the within-layer cross-axis under the chart's
                   ;; DOWN direction), so the order chain must read side-to-
                   ;; side: it leaves each
                   ;; branch from its RIGHT source handle (id `\"right\"`,
                   ;; `four-cardinal-handles`) and enters the next from its
                   ;; LEFT target handle (id `\"left\"`). Without these,
                   ;; xyflow may attach BOTTOM→TOP (the unnamed cardinal
                   ;; source/target handles), making the priority chain
                   ;; visually awkward + dependent on xyflow internals.
                   :sourceHandle "right"
                   :targetHandle "left"
                   ;; The connector is a quiet order chain, NOT a
                   ;; transition: the renderer suppresses the arrowhead off
                   ;; the `:forkConnector` flag (same as an internal self-
                   ;; transition). The `:markerEnd` map is kept present —
                   ;; matching the every-edge `arrowclosed` markerEnd
                   ;; invariant — but never drawn (in the neutral
                   ;; `:pseudo-marker` hue so a stray render is unobtrusive).
                   :markerEnd {:type   "arrowclosed"
                               :color  (:pseudo-marker ct)
                               :width  (:arrow-width-quiet chart)
                               :height (:arrow-width-quiet chart)}
                   :data   {:eventLabel ""
                            :eventLineLabel ""
                            ;; the decorative-connector flag `edges.cljs`
                            ;; keys the dotted, arrowhead-less, label-less
                            ;; render off.
                            :forkConnector true
                            :active false :focused false :fired false
                            ;; the decorative connector is never a guard-
                            ;; blocked transition; false keeps the every-edge
                            ;; :data shape whole.
                            :guardBlocked false
                            :afterMs nil :guard nil :action nil
                            :crossHierarchy false
                            ;; Straight handle-to-handle dotted line — no
                            ;; ELK route (the connector was never in the ELK
                            ;; graph), so :points nil keeps the every-edge
                            ;; :data shape whole and the renderer draws the
                            ;; bezier/straight fallback dotted.
                            :points nil :labelPos nil
                            :internal false :machineLevel false
                            :eventId nil :fromPath nil :toPath nil
                            :quietSegment false :onClick nil
                            :chart chart :palette ct}})
                (partition 2 1 group))))
       vec))

;; ---- elk.js children projection ----------------------------------------

(def event-node-elk-width
  "elk.js layout width for an event-node — narrower than a state node
  so two adjacent events do not crowd the state row, but wide enough
  for the `event-segment` text + optional `[guard]` chip + `+ action`
  pill.

  96px lays the route chip out as a SUBORDINATE node, not a peer state
  box. The event chip's CSS min-width (`:event-chip-min-w` 92 regular)
  sits just under this floor; the measure-then-relayout pass still sizes
  the chip to its real content via `(max measured floor)`, but the LAYOUT
  WEIGHT (the seed ELK uses before measurement, and the floor a content-
  light chip lands on) is low enough that event chips don't push state
  rows apart like peers."
  96)

(def event-node-elk-height
  "elk.js layout height for an event-node — taller than a single text
  line so the action-pill row has space underneath the header.

  34px keeps the route chip a compact card, not a title/body box —
  subordinate layout weight relative to a state node's 58px floor."
  34)

(defn leaf-elk-size
  "The elk.js layout size for a LEAF state node, given the optional
  `measured` `{:width :height}` map xyflow reported for that node on a
  prior render (`node.measured`).

  The first ELK pass has no measurement yet (`measured` is nil), so the
  node falls back to the `state-node-min-{width,height}` FLOOR. On the
  measure-then-relayout second pass (`chart.cljs`) the host hands back the
  rendered box; we take the MAX of the measured dimension and the floor so
  a node never lays out SMALLER than its CSS `min-{width,height}`, but a
  node whose CONTENT (long label + tag pills + entry/exit action pills)
  exceeds the floor gets the real box ELK must budget for — so ELK never
  assumes every node is exactly the floor and mis-sizes the topology.

  Returns a `{:width :height}` map (both positive)."
  [measured]
  {:width  (max state-node-min-width  (or (:width  measured) 0))
   :height (max state-node-min-height (or (:height measured) 0))})

(defn elk-child
  "Build a single elk.js child descriptor for a parsed node (a plain
  CLJS map; `chart`'s `->elk-input` `clj->js`-es the whole tree).

  `measured-dims` is the optional `{node-id {:width :height}}` map of
  xyflow-reported rendered boxes (`node.measured`) from a prior render,
  threaded through `->elk-children`. A LEAF state takes
  `(max measured floor)` per dimension (`leaf-elk-size`) so ELK sizes
  each slot to the node's REAL rendered box rather than the fixed floor.
  COMPOUND containers keep the compound floor as their SEED size — their
  true extent comes from ELK laying out their measured children inside
  (`elk.hierarchyHandling INCLUDE_CHILDREN`), not from a self-measurement
  (the rendered compound box is `width:100% height:100%` of whatever ELK
  allocates, so feeding its measured DOM size back would be circular).
  When `measured-dims` is nil / empty (the first pass) every leaf falls
  back to the floor."
  ([n] (elk-child n nil))
  ([n measured-dims]
   (merge
     {:id     (:id n)
      :labels [{:text (:label n)}]}
     (cond
       ;; the synthetic machine-root node is a compact root-context chip,
       ;; not a full state box; it lays out at content size
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
  "Build an elk.js child descriptor for a SYNTHETIC event-node. The
  events-as-nodes paradigm inserts one of these per spec transition
  (between source state and target state); elkjs lays it out alongside
  the source state's siblings inside the source's parent container.

  Like a leaf state, an event-node renders at CONTENT size (event header
  + optional `[guard]` chip + `+ action` pill row), so on the measure-
  then-relayout second pass we take `(max measured floor)` per dimension
  against the `event-node-elk-{width,height}` floor. `measured-dims` is
  the optional `{node-id {:width :height}}` map; nil on the first pass
  falls back to the floor."
  ([parsed-edge] (elk-event-child parsed-edge nil))
  ([parsed-edge measured-dims]
   (let [id (event-node-id parsed-edge)
         m  (get measured-dims id)]
     {:id     id
      :width  (max event-node-elk-width  (or (:width  m) 0))
      :height (max event-node-elk-height (or (:height m) 0))
      :labels [{:text (or (:event-label parsed-edge) "")}]})))

(defn order-state-children
  "Order a container's STATE children so the initial state LEADS its local
  model order, with the synthetic machine-root annotation demoted LAST;
  every other state keeps its parse order. A stable sort on a per-node
  rank:

    0  initial state            (`:initial?`)  — the preferred anchor
    1  ordinary state           (parse order kept by stable sort)
    2  machine-root annotation  (`:machine-root?`) — a quiet routing
                                 anchor, never a flow start, so it sinks
                                 to the END of the model order

  This is the model-order half of the initial-state placement SOFT
  preference (the layout half is `chart.cljs`'s `cycleBreaking.strategy
  DEPTH_FIRST`). It biases TWO ELK decisions toward the initial state
  without forcing any position:

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

;; ---- root-container Context-band height ---------------------------------
;;
;; The synthetic ROOT-CONTAINER frame paints a TITLE strip PLUS an OPTIONAL
;; Context band under it (`chart.nodes/root-container-node` — the
;; key→type-caption shape the host feeds via `:context-band`). The band is
;; a VARIABLE-height block: its height grows with the number of context
;; rows. `container-elk-padding`'s plain TOP reservation only clears the
;; title strip + a body-pad band, so with non-trivial context the children
;; laid out at the reserved content edge would sit UNDER the painted
;; Context band. These constants model the band's rendered geometry so the
;; root-container's ELK top padding reserves it too.
;;
;; The band's CSS (in `chart.nodes/root-container-node`) is fixed-pixel,
;; NOT density-scaled, so these constants mirror those literals. They are
;; the SINGLE SOURCE the renderer reads (the renderer references these via
;; `root-container-node`) so the reservation and the paint can never drift.

(def context-band-pad-top
  "The Context band's TOP padding (px). Mirrors the `padding 5px 10px 6px`
  top component in `root-container-node`."
  5)

(def context-band-pad-bottom
  "The Context band's BOTTOM padding (px). Mirrors the
  `padding 5px 10px 6px` bottom component in `root-container-node`."
  6)

(def context-band-pad-x
  "The Context band's HORIZONTAL padding (px). Mirrors the
  `padding 5px 10px 6px` left/right component in `root-container-node`."
  10)

(def context-band-row-font-px
  "The Context band's row `font-size` (px). Mirrors the band's
  `font-size 10px` (the row text; the caption + badge are smaller 8px but
  their row height is modelled by `context-band-header-height`)."
  10)

(def context-band-row-line-height
  "The Context band's `line-height` (unitless). Mirrors the band's
  `line-height 1.3`. One row's rendered height is `ceil(font-px ·
  line-height)`."
  1.3)

(def context-band-header-margin-bottom
  "The `margin-bottom` (px) on the band's caption/badge header row.
  Mirrors the header row's `margin-bottom 1px`."
  1)

(defn- ceil-int
  "Round a number UP to the nearest int. Pure; portable (CLJ + CLJS)."
  [x]
  #?(:clj  (long (Math/ceil (double x)))
     :cljs (long (js/Math.ceil x))))

(def context-band-row-height
  "The rendered height (px) of ONE context key→type row: the row font at
  the band's line-height, rounded up (`ceil(10 · 1.3) = 13`). Single-
  sourced from the font + line-height constants the renderer reads."
  (ceil-int (* context-band-row-font-px context-band-row-line-height)))

(def context-band-header-height
  "The rendered height (px) of the band's `Context` caption +
  provenance-badge header row: an 8px-caption line at the band line-height
  PLUS its `margin-bottom`. A fixed row independent of the context row
  count (`ceil(8 · 1.3) + 1 = 12`)."
  (+ (ceil-int (* 8 context-band-row-line-height))
     context-band-header-margin-bottom))

(def context-band-row-gap
  "The vertical `gap` (px) the band's column flex puts BETWEEN adjacent
  children (the header row + each context row). Mirrors the band's
  `gap 2px`."
  2)

(defn context-band-height
  "The rendered height (px) the root-container Context band occupies for
  `n-rows` context rows, modelling the fixed-pixel CSS in
  `chart.nodes/root-container-node`:

    pad-top + header-row + n-rows · row-height
            + (1 header + n-rows children − 1) · row-gap   [= n-rows gaps]
            + pad-bottom + divider

  `divider-width` is the band's bottom-border hairline (the active
  density's `:container-divider-width`). Returns 0 for `n-rows` ≤ 0 — no
  band is painted, so no extra top padding is reserved. Pure — JVM-runnable
  so the projection regression can pin it without a renderer."
  [n-rows divider-width]
  (if (pos? n-rows)
    (+ context-band-pad-top
       context-band-header-height
       (* n-rows context-band-row-height)
       (* n-rows context-band-row-gap)   ;; (1 header + n rows) children ⇒ n gaps
       context-band-pad-bottom
       (or divider-width 0))
    0))

(defn container-elk-padding
  "The `elk.padding` string for a compound / region container, derived
  from the active density's `visual-constants` rather than a hardcoded
  literal.

  The container reserves:

    - TOP   = `:container-title-height` (the solid title strip the
              `compound-node` paints) PLUS a `:container-body-pad` band
              so the metadata row (compound tags / entry-exit rows) and
              the first child clear the header;
    - LEFT   = `:container-body-pad` — the inset the container chrome
              leaves around its children below the strip — UNLESS
              `reserve-initial-marker?` is set, in which case it grows to
              `(max container-body-pad initial-marker-left-extent)` so a
              NESTED initial substate's marker glyph (the dot + short
              hook, drawn `initial-marker-x-offset` px LEFT of the state)
              sits fully INSIDE the container border instead of spilling
              past it;
    - RIGHT / BOTTOM = `:container-body-pad` — the same inset.

  Both density keys are density-dependent (`chart-{compact,regular,cosy}`),
  so a fixed literal would reserve the wrong header gap in the non-regular
  densities (children crowding the strip in `:cosy`, over-spaced in
  `:compact`). Pass the resolved density map (`vc/chart-for-density`);
  defaults to `vc/chart` (regular) so the nil-arity stays stable.

  `reserve-initial-marker?` (default false) widens ONLY the LEFT side.
  `->elk-children` sets it per-container for every container whose own
  initial substate carries a marker (the common case — a compound / region
  always has an `:initial?` child), so the left inset reserves room for the
  marker's leftward extent. The marker itself is NOT in the ELK graph (it
  is a decorative xyflow-only glyph node), so its extent cannot be measured
  into the container box by ELK's `INCLUDE_CHILDREN` pass — the padding
  reservation is what grows the box to enclose it.

  `extra-top` (default 0) is ADDED to the TOP reservation, ABOVE the
  title-strip + body-pad band. The synthetic ROOT-CONTAINER frame uses it
  to reserve the variable-height Context band the frame header paints UNDER
  its title strip (`context-band-height`): without it, the band — which is
  NOT an ELK child and so never grows the box via `INCLUDE_CHILDREN` —
  would overlap the first child ELK laid out at the plain reserved content
  edge. Every other container passes 0 (no band)."
  ([] (container-elk-padding vc/chart false 0))
  ([chart-vc] (container-elk-padding chart-vc false 0))
  ([chart-vc reserve-initial-marker?]
   (container-elk-padding chart-vc reserve-initial-marker? 0))
  ([{:keys [container-title-height container-body-pad]} reserve-initial-marker?
    extra-top]
   (let [left (if reserve-initial-marker?
                (max container-body-pad initial-marker-left-extent)
                container-body-pad)]
     (str "[top="   (+ container-title-height container-body-pad (or extra-top 0))
          ",left="  left
          ",bottom=" container-body-pad
          ",right=" container-body-pad "]"))))

(defn ->elk-children
  "Project parsed nodes + parsed edges into elk.js's `children` shape.

  Nesting is keyed on `:parent-id`: parallel-region states AND compound
  substates carry it, so BOTH nest UNDER their container and elkjs lays
  them out inside the container's bounding box (xyflow's `parentId`
  sub-flow then renders them inside the dashed boundary — xyflow v12 reads
  `parentId`, NOT the pre-v12 `parentNode`). Nesting recurses — a compound
  inside a region, or a compound inside a compound, lays out correctly.
  Each container (region OR compound) gets its own
  `elk.algorithm`/`elk.padding` so the header strip has room and the zone
  gets a clean internal layout; top-level nodes are laid out at the root.

  Synthetic event-nodes (one per parsed edge) sit alongside state-nodes as
  elk children of the SOURCE state's parent container (top-level when the
  source has no parent). elk then lays them out with the layered
  algorithm — events flow naturally between states per the events-as-nodes
  paradigm. They carry no children of their own.

  The optional `measured-dims` `{node-id {:width :height}}` map (xyflow's
  `node.measured` from a prior render, threaded by `chart.cljs`'s measure-
  then-relayout pass) is forwarded to every `elk-child` / `elk-event-child`
  so leaf states + event-nodes lay out at their REAL rendered box rather
  than the fixed floor. nil / empty on the first pass.

  The optional `chart-vc` (the resolved density map from
  `vc/chart-for-density`, threaded by `chart.cljs` alongside
  `measured-dims`) sizes each container's `elk.padding` from the active
  density's title-strip + body-pad constants via `container-elk-padding`,
  rather than a regular-only literal. nil falls back to `vc/chart`
  (regular).

  The optional `context-rows` (the integer count of context rows the
  root-container Context band paints, threaded by `chart.cljs` from
  `:context-band`) ADDS the band's rendered height to the ROOT-CONTAINER
  frame's TOP padding only (`context-band-height`), so the first child ELK
  lays out below the title strip clears the painted Context band too. 0 /
  nil ⇒ no band, no extra top reservation — every non-root container is
  unaffected regardless."
  ([parsed] (->elk-children parsed nil nil 0))
  ([parsed measured-dims] (->elk-children parsed measured-dims nil 0))
  ([parsed measured-dims chart-vc] (->elk-children parsed measured-dims chart-vc 0))
  ([{:keys [nodes edges] :as parsed} measured-dims chart-vc context-rows]
  (let [resolved-vc   (or chart-vc vc/chart)
        ;; the default container padding (no initial-marker reservation). A
        ;; container whose own initial substate carries a marker takes the
        ;; wider-LEFT variant instead (see `marker-container-pad` +
        ;; `marker-container-ids` below).
        container-pad (container-elk-padding resolved-vc)
        ;; the LEFT-widened padding for a container that holds a NESTED
        ;; initial substate, so the marker's leftward glyph extent sits
        ;; inside the border instead of spilling past it.
        marker-container-pad (container-elk-padding resolved-vc true)
        ;; the ROOT-CONTAINER frame ALSO reserves the variable-height
        ;; Context band ON TOP of the title strip. The frame holds the
        ;; machine's top-level initial (so it is in `marker-container-ids`),
        ;; so the band reservation rides the LEFT-widened variant. The band
        ;; height is derived from the context-row count + the density's
        ;; divider hairline; 0 rows ⇒ the extra-top is 0 and this collapses
        ;; to the plain marker-widened padding.
        context-band-px (context-band-height (or context-rows 0)
                                             (:container-divider-width resolved-vc))
        root-container-pad (container-elk-padding resolved-vc true context-band-px)
        ;; the set of container-ids (each `:parent-id` an `:initial?` state
        ;; nests under) that therefore need the LEFT-widened padding. A
        ;; compound / region always has an `:initial?` child, so this is the
        ;; common case; a container with no initial child (none in a valid
        ;; statechart, but cheap to be exact) keeps the plain body-pad
        ;; inset. The synthetic ROOT-CONTAINER frame is the parent of the
        ;; machine's TOP-LEVEL initial state (`wrap-in-root-container`
        ;; re-parents every no-parent node to `root-container-id`), so it
        ;; lands in the set too — the top-level initial marker is reserved
        ;; against the frame border the same way a nested one is reserved
        ;; against its compound.
        marker-container-ids (into #{}
                                   (comp (filter :initial?)
                                         (keep :parent-id))
                                   nodes)
        node-by-id (into {} (map (juxt :id identity)) nodes)
        ;; the guarded-fork branch within-layer ORDER pins. `fork-positions`
        ;; maps each fork-branch EVENT-NODE id to its 1-based priority index
        ;; (the `elk.position` hint); `fork-containers` is the set of holding
        ;; containers (`:parent-id`; nil == root) that therefore enable
        ;; `crossingMinimization.semiInteractive`. Empty for any machine
        ;; without a guarded fork — the pins are then a no-op.
        fork-positions (fork-branch-event-positions edges)
        fork-containers (fork-branch-container-ids parsed)
        ;; The event-node's parent container == the source state's
        ;; parent. Top-level when the source has no `:parent-id`.
        event-children
        (mapv (fn [e]
                (let [src (get node-by-id (:source e))
                      pid (:parent-id src)
                      ev-id (event-node-id e)
                      ;; a fork-branch event-node carries an `elk.position`
                      ;; ordering hint == its priority index, so (under the
                      ;; container's semiInteractive) ELK stacks the branches
                      ;; 1,2,3 and the dotted connector reads monotonic
                      ;; instead of weaving.
                      pos   (get fork-positions ev-id)]
                  (cond-> (elk-event-child e measured-dims)
                    pid (assoc ::event-parent pid)
                    ;; `elk.position` is a KVector `(x,y)`. With the chart's
                    ;; DOWN direction the layers stack vertically, so the
                    ;; WITHIN-LAYER (cross) axis is X — the priority index
                    ;; goes in x; y is left 0. semiInteractive reads this to
                    ;; order the branches 1,2,3 left-to-right.
                    pos (assoc :layoutOptions
                               {"elk.position" (str "(" pos ",0)")}))))
              edges)
        by-parent (group-by :parent-id nodes)
        events-by-parent (group-by ::event-parent event-children)
        ;; container `layoutOptions`, with semiInteractive added ONLY when
        ;; this container holds a guarded fork (so the branch `elk.position`
        ;; hints are honoured; non-fork containers are untouched and keep
        ;; full crossing-minimisation).
        ;; A container holding a NESTED initial substate uses the LEFT-
        ;; widened padding so the substate's initial-marker glyph sits inside
        ;; the border instead of spilling past it; every other container
        ;; keeps the plain body-pad inset.
        ;; The ROOT-CONTAINER frame additionally reserves the Context band on
        ;; TOP (it holds the top-level initial, so it ALSO takes the marker-
        ;; widened LEFT). `root-container-pad` folds both; with 0 context
        ;; rows it equals `marker-container-pad`, so a context-less root
        ;; carries the plain marker-widened padding.
        container-opts (fn [parent-id]
                         (cond-> {"elk.algorithm" "layered"
                                  "elk.padding"   (cond
                                                    (= parent-id layout/root-container-id)
                                                    root-container-pad
                                                    (contains? marker-container-ids
                                                               parent-id)
                                                    marker-container-pad
                                                    :else
                                                    container-pad)}
                           (contains? fork-containers parent-id)
                           (assoc "elk.layered.crossingMinimization.semiInteractive"
                                  "true")))
        build (fn build [n]
                (let [;; the local initial state leads its container's model
                      ;; order (machine-root sinks last); biases ELK's
                      ;; DEPTH_FIRST source selection + the within-layer
                      ;; tiebreak toward the initial state.
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
                           ;; the container top padding clears the solid
                           ;; title strip PLUS a body-pad band (metadata row
                           ;; for compound tags / entry-exit rows), so
                           ;; children never collide with the header;
                           ;; side/bottom track the `:container-body-pad`
                           ;; inset. Density-derived via `container-elk-
                           ;; padding`. `container-opts` adds semiInteractive
                           ;; when this container holds a guarded fork.
                           :layoutOptions (container-opts (:id n))))))
        ;; top-level model order: initial state first, machine-root
        ;; annotation last (see `order-state-children`).
        top-state-children (mapv build (order-state-children
                                         (get by-parent nil)))
        top-event-children (->> (get events-by-parent nil [])
                                (mapv #(dissoc % ::event-parent)))]
    (vec (concat top-state-children top-event-children)))))

;; ---- elk.js EDGE projection --------------------------------------------
;;
;; The elk EDGE descriptors are projected here (pure data), mirroring
;; `->elk-children` for nodes. Keeping them in `->elk-edge` (rather than
;; inline in `chart.cljs/->elk-input`, which is JS-side and JVM-unloadable)
;; lets the projection test corpus assert the edge-feed — the edges ELK
;; routes the topology AROUND — at the cheap JVM layer, the same way it
;; pins the node-feed (ids + endpoints + label dims).
;;
;; Each parsed transition is decomposed into the events-as-nodes edge pair:
;;
;;   source-state ─→ event-node   (`<spec-edge-id>__in`)
;;   event-node   ─→ target-state (`<spec-edge-id>__out`; omitted for
;;                                  internal transitions, which have no
;;                                  `:target`)
;;
;; ## Edge labels
;;
;; The xstate-style transition text (`event [guard] / action`) rides on
;; the event-NODE (`xyflow-graph` event-nodes; the event-node is the
;; events-as-nodes analogue of a Stately edge label and is ALREADY
;; measured + ELK-laid-out via `elk-event-child` + the measure pass). So
;; the `__in` / `__out` edges themselves carry NO visible label, and ELK
;; must reserve NO label channel for them — feeding label dims onto BOTH
;; the event-node AND its incident edges would double-budget the same
;; text.
;;
;; `->elk-edge` therefore takes an optional `label-dims` map but uses it
;; ONLY for an edge that genuinely renders its OWN label in the renderer
;; (none today under events-as-nodes; the parameter keeps the helper
;; honest + future-proof — a labelled edge type added later threads its
;; MEASURED `{:width :height}` here so ELK's `edgeLabels.placement`
;; reserves space). A nil entry emits an empty zero-size label, which
;; ELK treats as no label to place. This is the edge-label analogue of
;; the node measure pass: dims flow from the rendered DOM into the ELK
;; input rather than being a renderer-side heuristic.

(defn elk-edge-label
  "Build the elk edge `labels` entry for an edge. `text` is the visible
  label string (\"\" when the label rides on the event-node, which is the
  events-as-nodes default). `dims` is the optional MEASURED
  `{:width :height}` of the rendered label; nil emits a zero-size label
  ELK treats as nothing to place. Returns a single-label vector (elk's
  `labels` is always an array)."
  [text dims]
  [(cond-> {:text (or text "")}
     (and dims (pos? (:width dims 0)) (pos? (:height dims 0)))
     (assoc :width (:width dims) :height (:height dims)))])

(def initial-edge-priority-direction
  "The `elk.layered.priority.direction` value set on an INITIAL state's
  outgoing `__in` edge (every other edge is unset / the ELK default 0).
  xstate-viz uses this lever (with ELK's default GREEDY cycle-breaking +
  root `considerModelOrder NODES_AND_EDGES`) to anchor the initial state at
  the START of its region's flow. A higher direction-priority edge is
  preferred when ELK orders layers, pulling the edge's SOURCE (the initial
  state) toward the first layer — which fixes the parallel/pure-cyclic
  regions (traffic's `red` / `walk`) where the soft DEPTH_FIRST + model-
  order preference SLIPS and the initial would otherwise sink to the bottom
  layer.

  General, not traffic-specific: it biases EVERY machine's initial
  state(s) toward flow-start. For an already-spine-ordered machine
  (door / brew / session) the initial is ALREADY first, so the extra
  pull is a no-op on its rank; it only rescues the cases where the weak
  preference loses."
  "1")

(defn ->elk-edge
  "Project ONE parsed transition into its elk edge descriptor(s) under the
  events-as-nodes paradigm:

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
  and the edges carry empty labels — see the section comment above.

  `initial-ids` is the optional SET of state node-ids that are
  `:initial?`. When the `__in` edge LEAVES an initial state, it carries
  `elk.layered.priority.direction = 1` (the
  `initial-edge-priority-direction` lever) so ELK pulls the initial state
  toward flow-start — fixing the parallel/pure-cyclic regions where the
  soft initial-on-top preference slips. Empty / nil leaves every edge
  unset."
  ([e] (->elk-edge e nil nil))
  ([e label-dims] (->elk-edge e label-dims nil))
  ([e label-dims initial-ids]
   (let [ev-id     (event-node-id e)
         in-id     (str (:id e) "__in")
         out-id    (str (:id e) "__out")
         ;; the `__in` edge LEAVES the source state; when that state is
         ;; initial, pull it to flow-start via ELK's direction priority.
         ;; Only the OUTGOING edge from the initial gets it (the `__out`
         ;; event→target edge is unaffected).
         from-initial? (contains? (or initial-ids #{}) (:source e))]
     (cond-> [(cond-> {:id      in-id
                       :sources [(:source e)]
                       :targets [ev-id]
                       :labels  (elk-edge-label "" (get label-dims in-id))}
                from-initial?
                (assoc :layoutOptions
                       {"elk.layered.priority.direction"
                        initial-edge-priority-direction}))]
       (not (:internal? e))
       (conj {:id      out-id
              :sources [ev-id]
              :targets [(:target e)]
              :labels  (elk-edge-label "" (get label-dims out-id))})))))

(defn ->elk-edges
  "Project ALL parsed edges into the flat elk `edges` vector
  `chart.cljs/->elk-input` `clj->js`-es onto the elk root graph. The
  events-as-nodes split (`->elk-edge`) means N parsed transitions emit
  up to 2N elk edges. `label-dims` (optional measured-label map) is
  forwarded to every `->elk-edge`.

  The set of `:initial?` node-ids is derived once from `:nodes` and
  forwarded to every `->elk-edge` so an initial state's outgoing `__in`
  edge carries ELK's flow-start direction priority."
  ([parsed] (->elk-edges parsed nil))
  ([{:keys [edges nodes]} label-dims]
   (let [initial-ids (into #{} (comp (filter :initial?) (map :id)) nodes)]
     (vec (mapcat #(->elk-edge % label-dims initial-ids) edges)))))

;; ---- graph projection (parsed + positions → xyflow nodes/edges) ---------

(defn xyflow-graph
  "Project the parsed graph + a `{node-id position}` map into the
  xyflow `:nodes` + `:edges` arrays. Pure fn (no DOM, no React).

  Options:

    :highlight-ids       — a SET of active-leaf node-ids (parity gap G1).
                           A node is `:active` when its id ∈ this set. A
                           PARALLEL machine's snapshot has N simultaneously-
                           active leaves (one per region), so passing the
                           set lights up EVERY active region at once — the
                           multi-active highlight Stately renders (§1.2 of
                           `001-Topology-Parity.md`). Resolve it from a
                           snapshot `:state` via
                           `chart.layout/highlight-ids` (handles all
                           three `:state` arms: flat / compound /
                           region-map). A flat / compound snapshot
                           resolves to a singleton set, so this is the
                           single option for active-state highlighting.
    :from-highlight-id   — node-id of the focused-event lens's
                           origin state.
    :to-highlight-id     — node-id of the focused-event lens's
                           landing state.
    :sim?                — flips the highlight palette to amber.
    :on-state-click      — `(fn [path] ...)` invoked when a REAL
                           statechart-state node is clicked: a LEAF state
                           (its body) or a COMPOUND state (its TITLE STRIP
                           only — the compound body stays
                           `pointer-events:none` so a click in the body
                           falls through to the nested leaf). Threaded onto
                           leaf + compound `:data` as `:onClick`. The
                           synthetic machine-root chip and parallel-region
                           containers are NOT click targets (no region-
                           selection concept yet), so the projector does
                           NOT thread `:onClick` onto their `:data` — no
                           node carries an `:onClick` its renderer would
                           never consume.
    :on-edge-click       — `(fn [{:keys [event-id from-path
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
    :edge-points         — a map `{elk-edge-id [{:x :y} …]}` of elk's
                           routed bend-points (parity gap G2; absolute /
                           flow coordinates; `chart`'s `compute-layout!`
                           sets `elk.json.edgeCoords ROOT` so they share
                           xyflow's frame). The KEYS are the elk edge ids
                           `chart.cljs/->elk-input` emits —
                           `<spec-edge-id>__in` (source-state →
                           event-node) and `<spec-edge-id>__out`
                           (event-node → target-state) — under the
                           events-as-nodes paradigm. The inbound xyflow
                           edge (`<spec-edge-id>__in`) looks up the `__in`
                           route and the outbound (`<spec-edge-id>__out`)
                           looks up the `__out` route, so each edge draws
                           exactly the segment it represents; the route
                           attaches to that edge's `:data {:points}` and
                           `chart.edges/transition-edge` draws a smooth
                           poly-path THROUGH those points — routing
                           AROUND nested/parallel containers rather than
                           cutting across them (§1.7 of
                           `001-Topology-Parity.md`). An edge with no
                           entry (or a self-loop, which keeps its
                           dedicated loop path) falls back to the bezier
                           between handles. Defaults to `{}`.
    :edge-labels         — a map `{elk-edge-id {:x :y}}` of
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
    :fired-edge-ids      — a SET of canonical edge-ids (parity gap G3;
                           the EXACT `:id` scheme `chart.layout` mints)
                           that fired THIS epoch.
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
    :guard-blocked-edge-ids — a SET of canonical edge-ids whose guard
                           REJECTED the event this epoch (a
                           guard-blocked no-op: the runtime emitted
                           `:rf.machine/guard-evaluated` fail/threw but NO
                           `:rf.machine/transition`). The event-node AND its
                           `__in` (source-state → event-node) half are
                           marked `:guardBlocked` when the canonical id ∈
                           this set; the renderer paints the PINK guard-
                           blocked treatment (emphasised pink stroke +
                           emphasised pink `IF <guard>` chip, `data-guard-
                           blocked`) which WINS over fired/focused/active so
                           the attempted-and-rejected edge stands out. The
                           highlight STOPS at the guard event-node: the
                           `__out` (event-node → target-state) half is NOT
                           marked, because a blocked transition is a no-op
                           (the machine stayed in the source state; the
                           target was never reached) and an onward pink arrow
                           would falsely imply the transition progressed. The
                           static `__out` topology edge still renders; only
                           the live blocked-overlay stops at the event-node.
                           Without any of this the chart would paint ALL of
                           the active state's exits affordance-blue and give
                           ZERO signal which edge the event hit or that a
                           guard blocked it. The host (Xray) resolves the set
                           via
                           `panels.machines.trace-state/extract-guard-blocked-edge-ids`.
                           SUPERSET of XState/Stately (which highlights
                           nothing on a block). Defaults to `#{}`.
    :chart               — the resolved visual-constants map for the
                           active `:density`
                           (`visual-constants/chart-for-density`).
                           Threaded into every node/edge `:data` as
                           `:chart` so the xyflow node/edge components
                           — which React invokes OUTSIDE the render's
                           dynamic-binding scope — read their geometry
                           + typography off the payload instead of a
                           hardcoded literal. Defaults to
                           `visual-constants/chart-regular` so callers
                           that omit it (the JVM projection tests, a
                           density-less host) get the regular density.
    :palette             — the resolved chart-semantic token map for the
                           active `:theme`
                           (`theme/tokens/chart-tokens` of the
                           theme-palette). Threaded into every node/edge
                           `:data` as `:palette` so the xyflow node/edge
                           components — invoked OUTSIDE the render's
                           dynamic scope — read their structured-grammar
                           colours off the payload, painting the ACTIVE
                           theme (not the hardwired dark alias). Defaults
                           to `(chart-tokens)` (dark) so a theme-less
                           caller still resolves the dark surface."
  [{:keys [nodes edges parallel?]}
   positions
   {:keys [highlight-ids from-highlight-id to-highlight-id sim?
           on-state-click on-edge-click edge-points edge-labels
           fired-edge-ids guard-blocked-edge-ids chart palette
           machine-id context-band context-band-inferred?
           context-band-sensitive context-band-large context-band-raw?]
    :or   {chart vc/chart-regular edge-points {} edge-labels {}
           fired-edge-ids #{} guard-blocked-edge-ids #{}
           context-band-inferred? true
           context-band-sensitive #{} context-band-large #{}
           context-band-raw? false}}]
  (let [;; resolve the chart-semantic token map for the active theme
        ;; ONCE. nil → dark chart-tokens (a theme-less caller keeps the
        ;; dark surface). Threaded onto every node/edge `:data {:palette}`
        ;; so the renderers paint the active theme.
        ct (or palette (tokens/chart-tokens))
        ;; the Context band is serialised into the
        ;; SVG / PNG / clipboard export (export/chart-as-svg clones the
        ;; live viewport DOM). It defaults to a LOCAL-REDACTED projection:
        ;; a `:context-band-sensitive` key renders `:rf/redacted` and a
        ;; large value is elided WITHOUT a content head, so a host feeding
        ;; live `:data` cannot leak a schema-marked secret/large slot into
        ;; an egress artefact. `:context-band-raw? true` is the explicit
        ;; trusted-local (`:rf.egress/local-raw`) opt-in that skips it.
        ;; The production feeder's value-free type-caption shape is a
        ;; no-op under redaction, so the default surface is unchanged.
        ctx-display (when (seq context-band)
                      (let [redacted (if context-band-raw?
                                       context-band
                                       (ctx-redact/redact-context
                                         context-band
                                         {:sensitive context-band-sensitive
                                          :large     context-band-large}))]
                        (mapv (fn [[k v]]
                                [(if (keyword? k) (str (symbol k)) (str k))
                                 (ctx-redact/display-string v)])
                              redacted)))
        ;; guarded-fork branch-order. Derived ONCE from the ordered
        ;; `:edges` vector (the parse preserves the source candidate vector
        ;; order, which IS the engine's first-pass-wins priority);
        ;; `{edge-id 1-based-index}` for every edge that is one branch of a
        ;; genuine guarded multi-branch fork (2+ same-source / same-trigger
        ;; candidates with at least one guard). Threaded onto each event-
        ;; node's `:data {:forkOrder}` so `event-node` paints the numbered
        ;; priority badge Stately shows on the gate `:check` 3-way; absent
        ;; (→ nil) for every non-fork event, so a single transition carries
        ;; no badge.
        fork-order (fork-order-by-edge-id edges)
        ;; the active set (G1). A PARALLEL machine's snapshot has N
        ;; simultaneously-active leaves; `:highlight-ids` carries them all
        ;; so EVERY active region lights up at once. A flat / compound
        ;; snapshot resolves (via `chart.layout/highlight-ids`) to a
        ;; singleton set. A node is active when its id ∈ this set.
        active-ids (set highlight-ids)
        ;; container ACTIVE chrome (G4). The active set above holds active
        ;; LEAF ids; a parallel-region (or compound) CONTAINER reads as
        ;; active when ANY descendant leaf is active, so an active region's
        ;; chrome (the dashed box + header) emphasises — not just the leaf
        ;; inside it (Stately parity §1.4 of `001-Topology-Parity.md`).
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
        ;; container nodes (parallel regions AND compound parents) MUST
        ;; precede their children in the xyflow nodes array (xyflow requires
        ;; a parentId target to appear before any node that references it;
        ;; the v12 `adoptUserNodes` walk warns otherwise — `Parent node
        ;; ${parentId} not found. Please make sure that parent nodes are in
        ;; front of their child nodes in the nodes array.`). The parse
        ;; already emits parents first; sort defensively so the parent-
        ;; before-child invariant holds.
        proj-nodes
        (mapv (fn [n]
                (let [pos      (get positions (:id n) {:x 0 :y 0})
                      region?  (boolean (:region? n))
                      ;; a node is active when it is an active leaf (G1) OR
                      ;; a container reaching an active leaf via the
                      ;; `:parent-id` chain (the active-region chrome). The
                      ;; two sets are disjoint by construction (`active-ids`
                      ;; is leaves, `active-container-ids` is their ancestor
                      ;; containers), so the union is the whole active
                      ;; surface.
                      ;; The synthetic ROOT-CONTAINER frame is the ancestor
                      ;; of EVERY node, so the `active-container-ids` parent-
                      ;; chain walk would always mark it active and the whole
                      ;; frame would light up on every transition. It is
                      ;; structural chrome, not a state, so it never picks up
                      ;; active affordance — gate it out explicitly.
                      active?  (and (not (:root-container? n))
                                    (or (contains? active-ids (:id n))
                                        (contains? active-container-ids (:id n))))
                      from-hi? (= (:id n) from-highlight-id)
                      to-hi?   (= (:id n) to-highlight-id)
                      base
                      {:id       (:id n)
                       :type     (cond
                                   ;; the synthetic ROOT-CONTAINER frame: the
                                   ;; Stately-Studio-style NAMED box wrapping
                                   ;; the WHOLE machine. A container (ELK-sized
                                   ;; to hug its children) rendered by
                                   ;; `chart.nodes/root-container-node`, whose
                                   ;; header carries the machine name + Context
                                   ;; shape. Checked BEFORE `:compound?` (it
                                   ;; carries `:compound? true` for the
                                   ;; ELK/xyflow container mechanics, but is
                                   ;; NOT a compound state box).
                                   (:root-container? n) "root-container"
                                   ;; the synthetic root node a machine-level
                                   ;; (top-level `:on`) fallback routes FROM.
                                   ;; Painted as a quiet root-context chip
                                   ;; (`chart.nodes/machine-root-node`), not a
                                   ;; state box.
                                   (:machine-root? n) "machine-root"
                                   ;; the synthetic PARALLEL-ROOT node is the
                                   ;; anchor the whole-parallel `:on-done`
                                   ;; completion affordance hangs off — NOT a
                                   ;; statechart state. Route it through the
                                   ;; quiet root-context chip
                                   ;; (`machine-root-node`) so it reads as the
                                   ;; completion anchor, not a clickable state
                                   ;; box (its path is a rendering sentinel, so
                                   ;; it must not be an on-state-click target).
                                   ;; The SAME inert-synthetic-chip class as
                                   ;; the machine-root + region containers.
                                   (:parallel-root? n) "machine-root"
                                   ;; a `:type :history` PSEUDO-STATE (Spec 005
                                   ;; §History states) is NEVER occupiable: it
                                   ;; is a transition target that resolves to
                                   ;; the compound's recorded / default leaf,
                                   ;; so the machine never sits in `[… :hist]`.
                                   ;; Paint it as the small `history-marker`
                                   ;; glyph (`H` / `H*`) inside its owning
                                   ;; compound — NOT a clickable state box. The
                                   ;; renderer is `chart.nodes/history-marker`.
                                   (:history? n)  "history-marker"
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
                                          ;; error-terminal KIND. `:error?`
                                          ;; finals (a re-frame2 extension
                                          ;; routing the parent's `:on-error`)
                                          ;; get the error-hue outer ring;
                                          ;; success finals keep the quiet
                                          ;; runtime-coupled ring.
                                          :errorFinal     (boolean (:error? n))
                                          :compound       (boolean (:compound? n))
                                          ;; serialise each tag to its FULLY-
                                          ;; QUALIFIED string (`door/open`, not
                                          ;; `open`) HERE, before xyflow
                                          ;; `clj->js`-es the `:data` map.
                                          ;; `clj->js`'s default keyword-fn is
                                          ;; `name`, which drops the namespace —
                                          ;; so a `:door/open` tag would arrive
                                          ;; at the renderer as the truncated
                                          ;; `"open"` and the `nodes/tag-label`
                                          ;; identity fix (which runs AFTER the
                                          ;; round-trip) could never recover it.
                                          ;; Stringify via `symbol` so the
                                          ;; namespace survives the boundary
                                          ;; intact.
                                          :tags           (mapv (fn [t]
                                                                  (if (keyword? t)
                                                                    (str (symbol t))
                                                                    (str t)))
                                                                (:tags n))
                                          ;; :entry / :exit state actions
                                          ;; (Spec 005 §State nodes) ride the
                                          ;; payload so state-node paints
                                          ;; `entry / <name>` rows. nil when
                                          ;; the state declares none.
                                          :entry          (:entry n)
                                          :exit           (:exit n)
                                          ;; the entry / exit lifecycle
                                          ;; action's declared `:rf.cofx/requires`
                                          ;; (EP-0017 consumer attachment), as
                                          ;; compact display strings the action
                                          ;; row paints as a `needs …` chip. nil
                                          ;; when the action declares no facts.
                                          ;; IDS only. camelCase so the JS-interop
                                          ;; `(.-entryRequires d)` resolves after
                                          ;; xyflow `clj->js`-es the `:data` map
                                          ;; (a kebab key would not).
                                          :entryRequires  (:entry-requires n)
                                          :exitRequires   (:exit-requires n)
                                          ;; a `:type :history` pseudo-state's
                                          ;; variant. The `history-marker`
                                          ;; renderer reads `(.-deep props)`:
                                          ;; deep history paints `H*`, shallow
                                          ;; `H` (Spec 005 §History states).
                                          :deep           (boolean (:deep? n))
                                          :chart          chart
                                          :palette        ct}
                                   ;; `:onClick` (on-state-click) rides ONLY
                                   ;; real statechart-state nodes: LEAF states
                                   ;; (clickable body) and COMPOUND states
                                   ;; (clickable TITLE STRIP; body stays
                                   ;; pointer-transparent so child-leaf clicks
                                   ;; pass through). The synthetic machine-root
                                   ;; chip, parallel-region containers, AND the
                                   ;; synthetic parallel-root completion anchor
                                   ;; are NOT `:on-state-click` targets (no
                                   ;; region-selection concept yet; the
                                   ;; parallel-root is a completion affordance
                                   ;; whose path is a rendering sentinel, not a
                                   ;; real statechart state), so they carry no
                                   ;; `:onClick` the renderer would never
                                   ;; consume.
                                   ;; A `:type :history` pseudo-state is also
                                   ;; NOT an on-state-click target: it is never
                                   ;; occupied, so there is no state to select
                                   ;; (same inert-synthetic-chip posture as the
                                   ;; machine-root / parallel-root anchors).
                                   ;; The synthetic ROOT-CONTAINER frame is
                                   ;; structural chrome, not a statechart state,
                                   ;; so (like the machine-root / region /
                                   ;; parallel-root anchors) it carries no
                                   ;; `:onClick`.
                                   (not (or (:machine-root? n)
                                            (:parallel-root? n)
                                            (:history? n)
                                            (:root-container? n)
                                            region?))
                                   (assoc :onClick on-state-click)

                                   region? (assoc :regionId    (:region n)
                                                  :regionIndex (:region-index n))

                                   ;; the ROOT-CONTAINER frame's header
                                   ;; content. The pure layer minted a neutral
                                   ;; `:label` ("machine"); override it with the
                                   ;; MACHINE NAME (the host's `:machine-id`)
                                   ;; and thread the inferred Context shape +
                                   ;; parallel flag so
                                   ;; `chart.nodes/root-container-node` paints
                                   ;; the named frame header + Context band.
                                   ;; Serialise the context map's keyword keys
                                   ;; to fully-qualified strings BEFORE xyflow
                                   ;; `clj->js` (whose default keyword-fn drops
                                   ;; the namespace), mirroring the `:tags`
                                   ;; handling.
                                   (:root-container? n)
                                   (assoc :label (if machine-id
                                                   (name machine-id)
                                                   (:label n))
                                          :machineName (when machine-id
                                                         (name machine-id))
                                          :parallel    (boolean parallel?)
                                          ;; the local-redacted band display
                                          ;; rows (computed once above; never the
                                          ;; raw values unless
                                          ;; `:context-band-raw?` opted in).
                                          :context     ctx-display
                                          :contextInferred (boolean
                                                             context-band-inferred?)))
                       :draggable false
                       :selectable false}]
                  ;; BOTH region AND compound containers receive
                  ;; `:style {:width :height}` from elk's measured position.
                  ;; The parallel-region renderer (`parallel_region_node`)
                  ;; AND the compound renderer (`compound_node`) both fill
                  ;; their box with `width:100% height:100%`, so xyflow must
                  ;; allocate the box elk measured — otherwise it falls back
                  ;; to `compound-node-min-{width,height}` (220×120), and
                  ;; substates whose parent-relative coords elk computed
                  ;; against the FULL measured extent would overflow the
                  ;; smaller fallback box and visually escape the container.
                  ;; Styling both (not just the region) keeps containment.
                  (cond-> base
                    (or region? (:compound? n))
                    (assoc :style {:width  (:width pos)
                                   :height (:height pos)})

                    ;; xyflow v12 reads `parentId` (NOT `parentNode`,
                    ;; the pre-v12 name retained only in xyflow's
                    ;; CHANGELOG). Without `parentId` xyflow does NOT
                    ;; recognise the child as nested: it interprets
                    ;; `:position` as ABSOLUTE flow coords and ignores
                    ;; `:extent "parent"` clamping, so an ELK parent-
                    ;; relative child (e.g. `{:x 14 :y 34}` under an
                    ;; `:active` container at `{:x 22 :y 240}`) would
                    ;; render at root `(14, 34)` — visually OUTSIDE the
                    ;; parent. Emit `:parentId` so xyflow's
                    ;; `adoptUserNodes` path adopts the child + uses
                    ;; the parent's absolute origin as the offset.
                    (and (not region?) (:parent-id n))
                    (assoc :parentId (:parent-id n)
                           :extent   "parent"))))
              ;; the machine-root node leads (it is the source of a
              ;; machine-level fallback's `__in` edge + event-node, and
              ;; xyflow wants a source-node before any edge that references
              ;; it), alongside region/compound parents.
              (sort-by #(if (or (:machine-root? %) (:region? %) (:compound? %)) 0 1) nodes))

        ;; events-as-nodes paradigm. Each parsed transition emits ONE
        ;; event-node (xyflow `type "rf2-event"`) plus one or two edges:
        ;;
        ;;   source-state ─→ event-node ─→ target-state  (regular external)
        ;;   source-state ─→ event-node                 (internal: no :target)
        ;;
        ;; The event is hoisted into a first-class box (rather than floated
        ;; as an edge LABEL between state boxes) so the action attribution
        ;; (+ guard chip) reads cleanly even with several stacked
        ;; candidates — the Stately graph view convention.
        ;;
        ;; The event-node — not the edge — carries the `:data {:eventLabel
        ;; ...}`; the cross-hierarchy / sibling / self-loop / fired /
        ;; focused treatments apply to the incoming + outgoing edges, but
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
                      ;; SOURCE-active only: an edge is `from-active?` iff
                      ;; its SOURCE is an active state (the outgoing
                      ;; "available transitions from here" fan). Target-
                      ;; active is deliberately NOT lit — it would light
                      ;; INCOMING edges, noise that misrepresents what a
                      ;; focused event did (e.g. a rejected no-op guard
                      ;; lighting every way you COULD have arrived at the
                      ;; resting state). The actually-traversed edge of a
                      ;; real transition still lights via `fired?` (matched
                      ;; by edge-id, direction-agnostic), so no fired arm
                      ;; relies on this clause.
                      from-active? (contains? active-ids src)
                      focused?     (and (some? from-highlight-id)
                                        (some? to-highlight-id)
                                        (= src from-highlight-id)
                                        (= tgt to-highlight-id))
                      fired?       (contains? fired-edge-ids (:id e))
                      ;; this edge's guard rejected the event this epoch
                      ;; (guard-blocked no-op). Drives the PINK guard-blocked
                      ;; treatment on the event-node and the `__in`
                      ;; (source→event-node) half, winning over
                      ;; fired/focused/active. The highlight STOPS at the
                      ;; event-node: the `__out` (event-node→target) half
                      ;; stays static/resting (`half-blocked?` below gates the
                      ;; per-half overlay to `:in` only), so the onward arrow
                      ;; does not imply the no-op progressed.
                      blocked?     (contains? guard-blocked-edge-ids (:id e))
                      internal?    (boolean (:internal? e))
                      ;; the EXTERNAL restart axis (`:reenter? true`). A
                      ;; targeted transition is internal by default (XState v5
                      ;; / Spec 005 §Self-transitions); `:reenter?` re-runs
                      ;; the target's exit/entry + restarts its
                      ;; `:after`/`:spawn`. Surfaced so a reentering
                      ;; transition reads distinctly from its internal
                      ;; default.
                      reenter?     (boolean (:reenter? e))
                      ;; A `:*` wildcard `:on` arm is a real transition
                      ;; but NOT a fireable event (Spec 005 §Wildcard).
                      ;; An `:on-done` completion edge carries the reserved
                      ;; `:rf.machine/done` (an engine-RAISED event, not
                      ;; user-fireable), so it is not click-to-send.
                      fireable?    (and (nil? (:after e))
                                        (not (:always? e))
                                        (not (:on-done? e))
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
                      ;; the producer/consumer key scheme.
                      ;; `chart.cljs/->elk-input` splits each parsed
                      ;; transition into TWO elk edges with ids
                      ;; `<spec-edge-id>__in` (source-state → event-node)
                      ;; and `<spec-edge-id>__out` (event-node → target),
                      ;; and `elk-result->positions` keys `:edge-points`
                      ;; by those elk edge ids. The xyflow edges this
                      ;; projector emits carry the SAME `__in` / `__out`
                      ;; ids, so each edge looks up ITS OWN elk route and
                      ;; draws exactly the segment it represents — the
                      ;; inbound edge gets the source→event-node route,
                      ;; the outbound edge gets the event-node→target
                      ;; route.
                      in-points    (get edge-points (str (:id e) "__in"))
                      out-points   (get edge-points (str (:id e) "__out"))
                      ;; elk's computed label positions for the two
                      ;; segments (LABEL analogue of the routes). Empty
                      ;; under events-as-nodes (label is on the event-node);
                      ;; present for any labelled edge.
                      in-label-pos  (get edge-labels (str (:id e) "__in"))
                      out-label-pos (get edge-labels (str (:id e) "__out"))]
                  {:edge      e
                   :event-id  event-id
                   :variant   variant
                   :ev-node-id ev-node-id
                   :parent-id parent-id
                   :focused?  focused?
                   :fired?    fired?
                   :blocked?  blocked?
                   :from-active? from-active?
                   :internal? internal?
                   :reenter?  reenter?
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
                           focused? fired? blocked? internal? reenter? event-id]}]
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
                                       ;; the guard / action consumer's
                                       ;; declared `:rf.cofx/requires` (EP-0017
                                       ;; consumer attachment), as compact
                                       ;; display strings the event-node paints
                                       ;; as a `needs …` chip. nil when the
                                       ;; named guard / action declares no facts
                                       ;; (so an undeclared callback is visually
                                       ;; unchanged). IDS only — never the
                                       ;; `:fn`. camelCase so the JS-interop
                                       ;; `(.-guardRequires d)` resolves after
                                       ;; xyflow `clj->js`-es the `:data` map.
                                       :guardRequires  (:guard-requires edge)
                                       :actionRequires (:action-requires edge)
                                       ;; 1-based priority index WHEN this
                                       ;; event-node is one branch of a genuine
                                       ;; guarded multi-branch fork (gate's
                                       ;; `:gate/check` 3-way → 1/2/3); nil for
                                       ;; every non-fork event so a single
                                       ;; transition carries no badge.
                                       ;; `event-node` paints the numbered
                                       ;; priority badge Stately shows on the
                                       ;; fork branches.
                                       :forkOrder   (get fork-order (:id edge))
                                       :focused     focused?
                                       :fired       fired?
                                       ;; guard-blocked no-op: the event-node
                                       ;; paints the PINK guard-blocked
                                       ;; treatment (pink border + emphasised
                                       ;; pink IF-guard chip).
                                       :guardBlocked blocked?
                                       :internal    internal?
                                       ;; the external-restart axis surfaced to
                                       ;; the event-node renderer (the `↻`
                                       ;; reenter chip).
                                       :reenter     reenter?
                                       :machineLevel (boolean (:machine-level? edge))
                                       ;; the XState `onDone` completion edge
                                       ;; (compound/parallel done.state).
                                       ;; `:onDone` is the renderer hook for the
                                       ;; ✓ done chip; `:doneState` is the
                                       ;; SCXML-style `done.state.<id>` label
                                       ;; (the node-id of the done node's path)
                                       ;; so a reader sees WHICH sub-flow
                                       ;; completed.
                                       ;;
                                       ;; The PARALLEL-ROOT done-path is the
                                       ;; engine's root sentinel `[]`, whose
                                       ;; `node-id` is the EMPTY string — the
                                       ;; naive form would yield a degenerate
                                       ;; `"done.state."` (trailing dot, no id)
                                       ;; that diverges from the SCXML emitter's
                                       ;; `"done.state.rf2_parallel_root"`. Use
                                       ;; the shared canonical sentinel id for
                                       ;; the parallel-root so the chart label
                                       ;; matches SCXML (single source of truth
                                       ;; in `layout/parallel-root-done-state-id`).
                                       :onDone      (boolean (:on-done? edge))
                                       :doneState   (when (:on-done? edge)
                                                      (str "done.state."
                                                           (if (:parallel-root? edge)
                                                             layout/parallel-root-done-state-id
                                                             (layout/node-id (:done-path edge)))))
                                       :eventId     event-id
                                       :fromPath    (:from-path edge)
                                       :toPath      (:to-path edge)
                                       :onClick     on-edge-click
                                       :chart       chart
                                       :palette     ct}
                           :draggable false
                           :selectable false}
                    ;; the event-node nests inside the same parent the
                    ;; source state nests in, so a transition declared on a
                    ;; compound substate's child sits inside the compound
                    ;; container rather than leaking to the
                    ;; root.
                    parent-id
                    (assoc :parentId parent-id
                           :extent   "parent"))))
              edge-descriptors)
        ;; the two halves of the events-as-nodes route (source-state →
        ;; event-node, event-node → target-state) emit structurally-
        ;; identical xyflow edges: same `:type`, the same
        ;; resting/active/fired marker COLOUR cond, and a `:data` payload
        ;; that differs only in its routed `:points` / `:labelPos`, the
        ;; quiet-vs-primary flag, and the `:inbound`/`:outbound` marker.
        ;; `events-as-nodes-edge` builds one half from those few
        ;; differing values so the shared shape lives in ONE place (rather
        ;; than copy-pasted, with drift risk on every `:data` key add).
        ;; `half` is `:in` (quiet source→event, quiet arrowhead) or `:out`
        ;; (primary event→target, primary arrowhead).
        ;; The arrowhead COLOUR routes through the shared
        ;; `tokens/edge-color` (the SAME helper `chart.edges/edge-stroke`
        ;; uses for the SVG path) so the head + stroke cannot disagree;
        ;; the arrowhead SIZE reads off the resolved density `chart` map
        ;; (`:arrow-width-quiet` for `__in`, `:arrow-width` for `__out`)
        ;; so the head scales with the stroke instead of a baked literal.
        marker-color
        (fn [{:keys [fired? focused? blocked? from-active?]}]
          (tokens/edge-color ct {:fired?   fired?
                                 :focused? focused?
                                 :blocked? blocked?
                                 :active?  from-active?}))
        events-as-nodes-edge
        (fn [half {:keys [edge ev-node-id from-active? focused? fired? blocked?
                          cross-hier?] :as desc} points label-pos]
          (let [in? (= half :in)
                ;; a guard-BLOCKED transition is a no-op: the guard
                ;; declined, the machine STAYED in the source state, the
                ;; target was NEVER reached. So the guard-blocked HIGHLIGHT
                ;; must stop at the guard event-node — it covers the `__in`
                ;; (source→event-node) half ONLY, never the `__out`
                ;; (event-node→target) half. Lighting the onward arrow would
                ;; falsely imply the transition progressed to the target. The
                ;; STATIC `__out` topology edge still renders (the transition
                ;; exists in the definition); only the live blocked-overlay
                ;; is withheld from it. General to ALL guard-blocked
                ;; transitions (each guarded-fork branch's own `__out` half
                ;; independently drops the overlay). The event-node itself +
                ;; the `__in` half stay PINK.
                half-blocked? (and blocked? in?)
                w   (if in?
                      (:arrow-width-quiet chart)
                      (:arrow-width chart))]
            {:id        (str (:id edge) (if in? "__in" "__out"))
             :source    (if in? (:source edge) ev-node-id)
             :target    (if in? ev-node-id (:target edge))
             :type      "transition"
             ;; the `__in` (source→event) half is the QUIET segment: a
             ;; SMALLER `:arrow-width-quiet` head in the quiet colour so the
             ;; PRIMARY `:arrow-width` head reads on the `__out`
             ;; (event→target) half and the pair reads as ONE transition
             ;; route. Both sizes ride the resolved density map (trimmed
             ;; toward Stately's small/thin heads).
             ;; `half-blocked?` (not the raw `blocked?`) drives the arrowhead
             ;; colour so the onward `__out` head is NOT pink for a guard-
             ;; blocked no-op (the head + stroke agree per half).
             :markerEnd {:type   "arrowclosed"
                         :color  (marker-color (assoc desc :blocked? half-blocked?))
                         :width  w
                         :height w}
             :data      {:eventLabel ""
                         :eventLineLabel ""
                         :active     from-active?
                         :focused    focused?
                         :fired      fired?
                         ;; guard-blocked no-op: the PINK guard-blocked
                         ;; stroke (winning over fired/focused/active) covers
                         ;; the `__in` (source→event-node) half ONLY. The
                         ;; `__out` (event-node→target) half stays resting —
                         ;; the target was never reached, so the onward arrow
                         ;; must NOT imply the transition progressed.
                         ;; `half-blocked?` is `blocked?` AND `in?`.
                         :guardBlocked half-blocked?
                         :afterMs    nil
                         :guard      nil
                         :action     nil
                         :crossHierarchy cross-hier?
                         ;; the elk route for THIS half (`__in`: source-state
                         ;; → event-node; `__out`: event-node → target-state).
                         ;; nil when elk emitted no route (bezier fallback).
                         :points     points
                         ;; elk's computed label position (nil under events-
                         ;; as-nodes; renderer falls back to its geometric
                         ;; anchor).
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
                         ;; the `__in` half paints thinner + in the quiet
                         ;; colour so the pair reads as one transition with
                         ;; the primary arrowhead on `__out`.
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
                           :position  {:x (- (:x pos) initial-marker-x-offset)
                                       :y (+ (:y pos) initial-marker-y-offset)}
                           :data      {:targetPath (:path n) :chart chart
                                       :palette ct}
                           :draggable false
                           :selectable false}
                    ;; see compound substate :parentId comment above. xyflow
                    ;; v12 reads `parentId`, which gives the marker the
                    ;; container's coordinate frame so it sits just left of a
                    ;; NESTED initial inside its box.
                    ;;
                    ;; But NO `:extent "parent"` on the marker. The marker is
                    ;; a decorative glyph that sits a few px to the LEFT of
                    ;; (and slightly outside) the state; for a nested initial
                    ;; near its container's left padding the marker's
                    ;; `state.x - offset` position is legitimately just
                    ;; outside the container content area. `:extent "parent"`
                    ;; would CLAMP that position rightward (so the node box
                    ;; stays inside the parent), shoving the whole glyph —
                    ;; dot, hook AND arrow tip — INTO the state (only nested
                    ;; markers carry a parent to clamp against, so a top-level
                    ;; initial like `door` would be unaffected while nested
                    ;; ones like `red`/`walk` overshoot). Dropping `:extent`
                    ;; lets the short hook end the same clean gap outside the
                    ;; edge at every nesting level.
                    (:parent-id n)
                    (assoc :parentId (:parent-id n)))))
              initial-nodes)
        entry-edges
        (mapv (fn [n]
                {:id          (str "initial__" (:id n) "__entry")
                 :source      (str "initial__" (:id n))
                 :target      (:id n)
                 :targetHandle "left"
                 :type        "transition"
                 ;; G-START — the initial-marker entry edge is the
                 ;; SCXML/Stately initial-state icon: a filled dot PLUS a
                 ;; short arrow whose head points AT the initial state's near
                 ;; edge and stops just OUTSIDE it with a small visible gap
                 ;; (the `initial-marker-glyph` tip-gap) — it does NOT land
                 ;; flush on or penetrate the state. It paints the SAME neutral
                 ;; `:pseudo-marker` hue as the dot (NOT the near-invisible
                 ;; `:edge-quiet` resting hue the route halves use) so the
                 ;; dot + arrow read as one unit, clearly visible against the
                 ;; dark canvas, matching Stately's initial marker. Sized off
                 ;; the resolved density (`:arrow-width-entry`) like the route
                 ;; arrowheads. The renderer (`chart.edges/edge-stroke`)
                 ;; routes the PATH stroke to the same hue off the `:entry`
                 ;; flag so stroke + arrowhead never disagree.
                 :markerEnd   {:type "arrowclosed"
                               :color (:pseudo-marker ct)
                               :width  (:arrow-width-entry chart)
                               :height (:arrow-width-entry chart)}
                 ;; Entry edges are non-interactive, but carry the full
                 ;; edge `:data` shape (flags + threaded callback/chart)
                 ;; so the "every edge has X" projection invariants hold.
                 :data        {:eventLabel "" :eventLineLabel "" :entry true
                               :active false :focused false :fired false
                               ;; the initial-marker entry edge is never a
                               ;; guard-blocked transition; false keeps the
                               ;; every-edge :data shape whole.
                               :guardBlocked false
                               :afterMs nil
                               :guard nil :action nil
                               ;; entry edges are never cross-hierarchy (a
                               ;; marker→leaf hop inside one container); false
                               ;; keeps the every-edge :data shape whole.
                               :crossHierarchy false
                               ;; entry edges keep the bezier (G2; a short
                               ;; marker→state hop never crosses a container);
                               ;; :points nil so the every-edge :data shape
                               ;; stays whole.
                               :points nil
                               ;; entry edges carry no label (unlabelled
                               ;; marker→state hop), so no elk label position;
                               ;; nil keeps the shape whole.
                               :labelPos nil
                               :internal false :machineLevel false
                               :eventId nil :fromPath nil :toPath nil
                               :quietSegment false
                               :onClick on-edge-click :chart chart :palette ct}})
              initial-nodes)
        ;; DECORATIVE dotted evaluation-order connector across the branches
        ;; of a guarded multi-branch fork (gate's `:gate/check` 3-way),
        ;; linking each branch's event-node to the next IN PRIORITY ORDER
        ;; (1→2→3). RENDER-ONLY: emitted HERE, after the ELK layout pass, and
        ;; NEVER fed to ELK (ELK's input edges come from `->elk-edges`, the
        ;; parsed graph alone), so the connector cannot move a single node —
        ;; it reinforces the first-pass-wins order the numbered badges
        ;; already annotate (the Stately dotted order chain). Empty when no
        ;; guarded fork exists.
        connector-edges (fork-connector-edges edges ct chart)]
    ;; order matters for xyflow: parent containers must precede any node
    ;; that references them via `:parentId`. State / region / compound nodes
    ;; are already sorted parent-first; the
    ;; event-nodes nest into the same parents (we set `:parent-id` to
    ;; the source state's parent), so appending them AFTER `proj-nodes`
    ;; keeps the parent-before-child invariant.
    {:nodes (vec (concat proj-nodes marker-nodes event-nodes))
     :edges (-> proj-edges (into entry-edges) (into connector-edges))}))
