(ns day8.re-frame2-machines-viz.chart.post-elk
  "Post-ELK layout subsystem for the MachineChart — the adaptive-aspect
  rebalance + back-edge return-route detour that close the two structural
  Stately-parity residuals ELK alone cannot reach (`001-Topology-Parity.md`
  §4.3.1 + §4.3.2).

  ## OPT-IN — the default render is UNTOUCHED

  This whole subsystem is **opt-in**. `chart.cljs` invokes it ONLY when a
  host (or a machine's `:layout {:aspect :adaptive}` hint) explicitly asks
  for the adaptive pass via `:direction :auto`. With the DEFAULT
  `:direction :tb` (or an explicit `:tb` / `:lr`), `chart.cljs` does NOT
  resolve a heuristic direction and does NOT run `apply-post-elk` — the ELK
  result flows to the projector byte-for-byte. The #1 contract here is that
  a machine's render is unchanged unless it explicitly opts in.
  `chart.post-elk/adaptive?` is the single gate predicate.

  ## Why a SEPARATE stage (the §4.3 verdict)

  Both gaps were proven NOT closeable via an ELK option:

    - **§4.3.2 G-ASPECT** — ELK Layered has no width/height BALANCE lever
      (`elk.aspectRatio` is a no-op for Layered; the only width lever,
      `wrapping.strategy`, shatters the linear chains). And a per-region
      `elk.direction` is DISCARDED by the `INCLUDE_CHILDREN` cross-
      hierarchy mode (mandatory for parallel's broadcast edges, G5). So
      the per-machine aspect + the parallel-region stacking axis are
      reachable only via NEW machinery: a direction HEURISTIC fed to ELK
      (column vs landscape per machine) PLUS a post-ELK coordinate
      transpose for parallel regions.

    - **§4.3.1 G-ROUTE** — under events-as-nodes a back-edge
      `alarming → reset → locked` is two ELK edges through a synthetic
      `reset` event-node; ELK ranks `reset` one layer BELOW the deepest
      state (it is a forward target of the already-deepest source), so the
      return reads as a long detour to the canvas bottom. GREEDY cycle-
      breaking lifts it but inverts the initial-on-top spine. The only
      non-regressing path is a CUSTOM post-ELK reroute of the back-edge's
      segments.

  ## What this ns owns — the opt-in gate + three PURE composable steps

    0. `adaptive?` / `resolve-direction` — the OPT-IN gate. `adaptive?`
       answers whether a host's `:direction` value requests the adaptive
       pass (`:auto` only); `resolve-direction` maps `:auto` to the
       per-machine heuristic and passes `:tb` / `:lr` straight through.

    1. `aspect-direction` — the orientation HEURISTIC: parsed graph →
       `:tb` (column) / `:lr` (landscape). Fed to ELK's direction once a
       machine has opted in. Linear/chain machines stay a column (door /
       brew); genuinely-branchy machines flow landscape (quiz / modal /
       gate). NOT a blanket flip — a chain has nowhere to branch, so it
       stays vertical.

    2. `transpose-parallel-regions` — the parallel-region STACKING-AXIS
       transform (post-ELK coordinate transpose). ELK lays parallel regions
       side-by-side with vertical intra-region flow; Stately stacks the
       regions VERTICALLY with horizontal intra-region flow. This step
       transposes each region's interior (swap the within-region x/y) and
       re-stacks the region containers in a vertical column.

    3. `reroute-back-edges` — the back-edge RETURN-ROUTE detour. A back-edge
       event-node that sank below BOTH its endpoints is lifted to mid-height
       beside the spine and its two segments rerouted as a clean side-detour
       (out → up → in) instead of the deep-layer plunge.

  `apply-post-elk` composes (2) + (3) on the `elk-result->positions` map
  (`{:positions :edge-points :edge-labels}`); `aspect-direction` (1) is
  consumed earlier, at direction-resolution time. BOTH are invoked by
  `chart.cljs` only on the opt-in path.

  ## Coordinate frames

  `chart.cljs/elk-result->positions` records each node's position AS ELK
  gives it — RELATIVE to its parent container (xyflow's `parentId` sub-flow
  wants that), and the region/compound containers at their ROOT-relative
  position. `:edge-points` are ABSOLUTE (root) coords (`elk.json.edgeCoords
  ROOT`). The two transposes here respect those frames: the region-interior
  transpose works in the region's LOCAL frame (children are parent-relative);
  the region RE-STACK moves the container's root-relative origin; the
  back-edge reroute works in the ABSOLUTE frame the edge-points already use.

  ## What this does NOT own

    - The ELK invocation + the JS-interop result walk — `chart.cljs`.
    - The parse — `chart.layout`.
    - The xyflow node/edge projection — `chart.projection`.

  Pure data → data, JVM-runnable, so the test corpus pins every transform
  at the cheap JVM layer (mirroring `chart.layout` / `chart.projection`)."
  (:require [day8.re-frame2-machines-viz.chart.projection :as projection]))

;; ============================================================================
;; Step 0 — the OPT-IN gate
;; ============================================================================
;;
;; The #1 contract: the DEFAULT render is byte-identical to a plain ELK
;; layout. So the whole post-ELK subsystem is gated behind an explicit
;; opt-in `:direction :auto`. `chart.cljs` calls `adaptive?` once; on a
;; falsey result it takes the non-adaptive path (no heuristic, no
;; apply-post-elk). Only `:auto` opts in. An explicit `:tb` / `:lr` is a
;; FORCED direction (the host's layout intent) — it skips the heuristic AND
;; the transpose/reroute, exactly as a default `:tb` does.

(def adaptive-direction
  "The single sentinel `:direction` value that OPTS A MACHINE IN to the
  adaptive post-ELK pass. A host passes `:direction :auto` (e.g. resolved
  from a machine's `:layout {:aspect :adaptive}` hint) to request
  per-machine aspect + the parallel-region transpose + the back-edge
  reroute. Every other value (`:tb`, `:lr`, nil, the default) takes the
  non-adaptive path."
  :auto)

(defn adaptive?
  "The OPT-IN predicate: does this `:direction` prop value request the
  adaptive post-ELK pass? True ONLY for the explicit `:auto` sentinel.
  Pure.

  This is the single gate `chart.cljs` consults: a falsey result means the
  ENTIRE post-ELK subsystem (heuristic direction + parallel transpose +
  back-edge reroute) is skipped and the render is a plain ELK layout. The
  default `:direction :tb`, an explicit `:tb` / `:lr`, and nil all return
  false (non-adaptive); only `:auto` returns true."
  [host-direction]
  (= host-direction adaptive-direction))

;; ============================================================================
;; Step 1 — adaptive layout aspect: the orientation heuristic
;; ============================================================================
;;
;; §4.3.2 wall 1: there is no ELK *balance* lever for Layered, and wrapping a
;; chain IS the regression. But the machines that read too-narrow are the ones
;; that genuinely BRANCH (quiz / modal / gate) — and Stately renders those
;; landscape for the same reason: a branchy flow spreads sideways. A LINEAR
;; chain (door / brew) has nowhere to branch, so Stately renders it a column —
;; exactly what ELK DOWN already produces.
;;
;; So the realistic, non-regressing aspect lever is a per-machine DIRECTION
;; choice (the bead's "vertical vs landscape per machine"): pick `:lr`
;; (landscape) for a genuinely-branchy machine, `:tb` (column) for a linear
;; one. ELK then lays the chosen direction out cleanly — no wrapping, no
;; aspectRatio no-op, no initial-on-top regression (DEPTH_FIRST + the model-
;; order preference work identically in either direction).
;;
;; "Branchy" is measured STRUCTURALLY from the parsed graph: the max
;; out-degree (fan-out) of any single state. A pure chain has out-degree 1
;; everywhere; a 3-way guarded fork (gate) or a hub state with several
;; distinct events (quiz / modal) has a state with out-degree ≥ the
;; threshold. This is deterministic, parse-only (no measured DOM), and
;; agrees with the Stately reference per the §4.3.2 measurements.

(def landscape-branch-threshold
  "The max single-state out-degree (distinct transition TARGETS leaving
  one state) at or above which a flat machine is judged BRANCHY enough to
  read better landscape (`:lr`) than as a column.

  3 captures the genuinely-branchy corpus machines (the gate `:gate/check`
  3-way guarded fork; the quiz / modal hubs that fan to several distinct
  next-states) while leaving the linear chains (door / brew / session —
  out-degree 1–2 along a spine) as columns. A door `:open` state with two
  exits (`close` / `trip`) stays under the threshold, so the chain reads
  vertical the way Stately renders it."
  3)

(defn- target-of
  "The settle TARGET node-id of a parsed edge — its `:target` for an
  external transition, or its `:source` for an internal/self transition
  (which has no distinct target). Used by the out-degree count so an
  internal action transition does not inflate a state's branch factor with
  a phantom distinct target."
  [edge]
  (or (:target edge) (:source edge)))

(defn max-out-degree
  "The maximum, over all REAL statechart states, of the number of DISTINCT
  transition targets leaving that state. Pure: parsed graph → int.

  Counts only edges leaving a genuine state node (a synthetic machine-root /
  parallel-root anchor's machine-level fallback is not a state's own fan-out,
  so it is excluded). DISTINCT targets — three guarded candidates of ONE
  `:gate/check` fork that all branch to different states count as 3 (a real
  3-way branch), but two events to the SAME target count once (no visual
  spread). A self/internal transition targets the source, so it does not add
  a distinct outward branch."
  [{:keys [nodes edges]}]
  (let [synthetic? (into #{}
                         (comp (filter #(or (:machine-root? %)
                                            (:parallel-root? %)
                                            (:region? %)
                                            (:root-container? %)))
                               (map :id))
                         nodes)
        by-source  (->> edges
                        (remove #(contains? synthetic? (:source %)))
                        (group-by :source))]
    (->> by-source
         (map (fn [[src es]]
                (count (disj (into #{} (map target-of) es) src))))
         (reduce max 0))))

(defn aspect-direction
  "The adaptive-aspect ORIENTATION heuristic: choose the ELK layout
  direction for `parsed` so a large machine reads in a screen-friendly
  proportion (the §4.3.2 G-ASPECT close). Pure: parsed graph → `:tb`
  (column / DOWN) or `:lr` (landscape / RIGHT).

  Only consulted on the OPT-IN path (`adaptive?` true). Rules (in order):

    - **PARALLEL machines → `:tb`.** Their aspect is handled by the
      region STACKING-AXIS transpose (`transpose-parallel-regions`), not by
      the root direction — the regions stack vertically with horizontal
      intra-region flow regardless of the root direction the root layout
      ran in. Returning `:tb` keeps the region containers laid out the way
      the transpose expects (side-by-side, then re-stacked).

    - **Branchy flat machines → `:lr`.** A state whose out-degree
      (distinct outward targets) reaches `landscape-branch-threshold` makes
      the machine read squarer-than-Stately as a column; flowing it
      LEFT-TO-RIGHT spreads the branch sideways into the landscape
      proportion Stately renders (quiz / modal / gate).

    - **Linear flat machines → `:tb`.** A chain (max out-degree below the
      threshold) has nowhere to branch; a column is the Stately shape
      (door / brew / session / media). This is NOT a blanket flip — the
      genuinely-vertical machines are preserved.

  This is the heuristic the bead asks for; `chart.cljs` calls it (via
  `resolve-direction`) to resolve the ELK direction once a machine has
  opted in with `:direction :auto`."
  [parsed]
  (cond
    (:parallel? parsed)
    :tb

    (>= (max-out-degree parsed) landscape-branch-threshold)
    :lr

    :else
    :tb))

(defn resolve-direction
  "rf2-lamdfl — the direction `chart.cljs` feeds ELK, given the host's
  `:direction` prop and the parsed graph. ONLY `:auto` (the opt-in sentinel)
  defers to the adaptive `aspect-direction` heuristic; an explicit `:tb` /
  `:lr` (and any non-`:auto` value, including the default `:tb`) is passed
  STRAIGHT THROUGH as the forced direction. Pure.

  Because `chart.cljs` only reaches this on the opt-in branch (`adaptive?`),
  `resolve-direction` is effectively the `:auto` → heuristic mapping; the
  pass-through arms keep it total/defensive if a forced direction is ever
  routed here."
  [host-direction parsed]
  (if (adaptive? host-direction)
    (aspect-direction parsed)
    host-direction))

;; ============================================================================
;; Step 2 — parallel-region stacking-axis transpose (rf2-lamdfl)
;; ============================================================================
;;
;; §4.3.2 wall 2: a per-region `elk.direction RIGHT` is the natural fix
;; (states flow left→right ⇒ each region is a wide short BAND ⇒ the root DOWN
;; stacks the regions vertically = the Stately shape), but `INCLUDE_CHILDREN`
;; (mandatory for the cross-region broadcast edge, G5) discards it. So we run
;; ELK normally (one global direction) and TRANSPOSE the result:
;;
;;   - inside each region, swap the within-region x/y of every descendant so
;;     the intra-region flow that ran vertically (root DOWN) now runs
;;     horizontally (the Stately intra-region axis);
;;   - re-stack the region CONTAINERS in a vertical column (Stately stacks
;;     the regions top-to-bottom), each sized to its transposed extent.
;;
;; Region children are parent-RELATIVE (xyflow parentId sub-flow), so the
;; interior transpose is a clean local-frame swap. The region CONTAINERS are
;; root-relative; re-stacking moves their origins. Cross-region broadcast
;; edges (rare — only traffic/shared) carry stale ELK bend-points after the
;; transpose, so their routes are CLEARED to fall back to the bezier between
;; the (now-transposed) handles — the renderer's documented no-route
;; fallback.

(defn region-descendant-ids
  "rf2-lamdfl — for each region CONTAINER id, the set of its DIRECT child
  node-ids (region states + their synthetic event-nodes). Pure: parsed graph
  → `{region-container-id #{child-node-id …}}`.

  A region state carries `:parent-id` = the region container id
  (`region-node-id`). Its synthetic event-node lays out under the SAME
  parent (the source state's parent), so the event-node ids for edges whose
  source is a region state belong to that region too. We collect both so the
  interior transpose moves the whole region body, not just the state boxes."
  [{:keys [nodes edges]}]
  (let [region-ids   (into #{} (comp (filter :region?) (map :id)) nodes)
        node-parent  (into {} (keep (fn [n]
                                      (when-let [p (:parent-id n)] [(:id n) p]))
                                    nodes))
        ;; state-id → its region container parent (only states whose parent
        ;; is a region container)
        state->region (into {} (filter (fn [[_ p]] (contains? region-ids p)))
                            node-parent)
        base (reduce (fn [acc [sid rid]]
                       (update acc rid (fnil conj #{}) sid))
                     {}
                     state->region)]
    ;; fold each region state's synthetic event-node in under the same region
    (reduce (fn [acc e]
              (if-let [rid (get state->region (:source e))]
                (update acc rid conj (projection/event-node-id e))
                acc))
            base
            edges)))

(defn- bbox
  "The bounding box `{:min-x :min-y :max-x :max-y :width :height}` of a seq
  of `{:x :y :width :height}` position maps, treating each as a box at its
  `:x`/`:y` with its `:width`/`:height`. nil for an empty seq. Pure."
  [boxes]
  (when (seq boxes)
    (let [min-x (reduce min (map :x boxes))
          min-y (reduce min (map :y boxes))
          max-x (reduce max (map (fn [b] (+ (:x b) (or (:width b) 0))) boxes))
          max-y (reduce max (map (fn [b] (+ (:y b) (or (:height b) 0))) boxes))]
      {:min-x min-x :min-y min-y :max-x max-x :max-y max-y
       :width (- max-x min-x) :height (- max-y min-y)})))

(def region-stack-gap
  "rf2-lamdfl — vertical gap (px) between stacked parallel-region containers
  after the transpose, so the bands read as distinct orthogonal zones with a
  clear divider (the UML/SCXML dashed-divider convention `parallel-region-
  node` paints — this is the spacing between them)."
  40)

(def intra-region-flow-gap
  "rf2-vb359s — horizontal gap (px) between consecutive flow RANKS inside a
  transposed region (a state box and the next state box, or a state box and
  the event-node chip between them). After the transpose the within-region
  flow runs along the NEW x-axis, but a bare coordinate swap inherits the
  flow spacing ELK budgeted for node HEIGHTS (state 58 / chip 34 + the 50px
  between-layer gap ≈ 108px rank pitch), which is far too tight once the
  boxes occupy their WIDTHS along x (state 152 / chip 96) — adjacent ranks
  overlap, and the intra-region event-node chips bury into the state boxes
  (the bug). So the transpose re-packs the ranks left-to-right by each rank's
  ACTUAL width plus this gap. Mirrors ELK's `nodeNodeBetweenLayers` (50) flow
  pitch so a transposed region reads with the same rank rhythm as a
  non-transposed column."
  50)

(defn- box-width
  "The layout width (px) of a transposed child box, floored to the state-node
  minimum so a child that lost its measured `:width` still reserves a sane
  rank pitch. Pure."
  [pos]
  (or (:width pos) projection/state-node-min-width))

(defn- respace-flow-ranks
  "rf2-vb359s — after the coordinate swap, re-pack the transposed children
  along the NEW x-axis (the within-region flow axis) so adjacent flow RANKS
  clear each other by their actual widths + `intra-region-flow-gap`. Pure:
  transposed `{child-id pos}` submap → the same submap with x re-spaced.

  A bare swap inherits the flow spacing ELK sized for node HEIGHTS, which is
  too tight once boxes occupy their WIDTHS along x — so intra-region event-
  node chips overlap the state boxes (the bug). We group children into ranks
  by their swapped x (the original flow layer, incl. the +0.5 event-node
  inter-ranks), order the ranks left-to-right, and re-base each rank's x to
  the running cursor = previous rank's right edge + gap. The y-axis (the
  cross-flow spread of within-rank siblings) is left UNTOUCHED — only the
  flow pitch is corrected."
  [transposed]
  (if (empty? transposed)
    transposed
    (let [start-x   (reduce min (map :x (vals transposed)))
          ;; group child-ids by their swapped x (the flow rank), ordered
          ;; ascending so the re-pack preserves the transposed flow order.
          ranks     (->> transposed
                         (group-by (fn [[_ pos]] (:x pos)))
                         (sort-by key))
          ;; fold the ranks left-to-right, advancing the x-cursor by each
          ;; rank's widest box + the flow gap.
          [_ x->new]
          (reduce
            (fn [[cursor acc] [_ entries]]
              (let [rank-w (reduce max 0 (map (fn [[_ pos]] (box-width pos)) entries))
                    acc'   (reduce (fn [m [id _]] (assoc m id cursor)) acc entries)]
                [(+ cursor rank-w intra-region-flow-gap) acc']))
            [start-x {}]
            ranks)]
      (reduce-kv (fn [m id pos]
                   (assoc m id (assoc pos :x (get x->new id (:x pos)))))
                 {}
                 transposed))))

(defn transpose-region-interior
  "rf2-lamdfl — transpose the WITHIN-region layout of one region so its
  intra-region flow runs HORIZONTALLY (Stately) instead of vertically (root
  DOWN). Pure: takes the region's `{child-id position}` submap (positions are
  parent-relative) and returns the transposed submap.

  The transpose swaps each child's local x↔y: a child at local
  `{:x a :y b :width w :height h}` moves to `{:x b :y a :width w :height h}` —
  the POSITION axes swap (so a vertical stack becomes a horizontal row) while
  each node keeps its own box (a state box is not rotated, only repositioned).

  rf2-vb359s — a BARE swap leaves the flow ranks spaced by the heights ELK
  budgeted (state 58 / chip 34 + the 50px between-layer gap), which is far too
  tight once the boxes occupy their WIDTHS along x (state 152 / chip 96), so
  intra-region event-node chips overlapped the state boxes. After the swap we
  therefore RE-PACK the ranks along the new x-axis (`respace-flow-ranks`) by
  each rank's actual width + `intra-region-flow-gap`. We then re-base to the
  title-strip inset so children still clear the region header, preserving the
  `container-elk-padding` reservation."
  [region-children]
  (let [transposed (->> (reduce-kv
                          (fn [m id {:keys [x y] :as pos}]
                            (assoc m id (assoc pos :x y :y x)))
                          {}
                          region-children)
                        respace-flow-ranks)
        ;; re-base so the transposed cluster's top-left returns to the
        ;; original interior inset (the smallest original local x/y), keeping
        ;; the header-strip clearance the padding reserved.
        orig-min-x (when (seq region-children)
                     (reduce min (map :x (vals region-children))))
        orig-min-y (when (seq region-children)
                     (reduce min (map :y (vals region-children))))
        new-min-x  (when (seq transposed)
                     (reduce min (map :x (vals transposed))))
        new-min-y  (when (seq transposed)
                     (reduce min (map :y (vals transposed))))]
    (if (seq transposed)
      (let [dx (- orig-min-x new-min-x)
            dy (- orig-min-y new-min-y)]
        (reduce-kv (fn [m id pos]
                     (assoc m id (-> pos
                                     (update :x + dx)
                                     (update :y + dy))))
                   {}
                   transposed))
      transposed)))

(defn transpose-parallel-regions
  "rf2-lamdfl — the parallel-region STACKING-AXIS transform (§4.3.2 close).
  Pure: takes the `{:positions :edge-points :edge-labels}` layout result +
  the parsed graph; returns the same shape with each region's interior
  transposed (horizontal intra-region flow) and the region containers
  re-stacked into a VERTICAL column — the Stately parallel shape.

  No-op for a non-parallel machine (returns the layout unchanged).

  Steps:

    1. Transpose each region's interior children (`transpose-region-
       interior`): the within-region flow flips from vertical to horizontal.
    2. Recompute each region container's extent from its transposed
       children's bbox (the band is now wide + short) and re-stack the
       containers top-to-bottom in a vertical column with `region-stack-gap`
       between them, preserving region order (`:region-index`).
    3. CLEAR the routed `:edge-points` for any cross-region broadcast edge
       (source + target in different region containers) so it re-routes as a
       bezier between the transposed handles instead of carrying stale ELK
       bend-points (the renderer's documented no-route fallback). Intra-
       region edges keep their ELK routes — the interior transpose moved
       their endpoints consistently, but ELK's absolute bend-points would
       now mismatch, so intra-region routes are cleared too and fall back to
       the clean bezier through the transposed handles."
  [{:keys [positions edge-points edge-labels] :as layout} parsed]
  (if-not (:parallel? parsed)
    layout
    (let [{:keys [nodes edges]} parsed
          region-nodes  (->> nodes
                             (filter :region?)
                             (sort-by (fn [n] (or (:region-index n) 0))))
          region-ids    (into #{} (map :id) region-nodes)
          desc-by-region (region-descendant-ids parsed)
          ;; LEFT-align the stacked column to the leftmost region's root x
          ;; (Stately stacks the regions in one vertical column, not at their
          ;; original side-by-side x-offsets).
          ;;
          ;; rf2-qp613a — compute the column origin from the ACTUAL region
          ;; positions only, NOT seeded with 0. A root-container-wrapped chart
          ;; positions its region containers relative to the root frame's
          ;; padding/header, so every region origin is POSITIVE; seeding the
          ;; reduce with 0 (`(reduce min 0 …)`) clamped a positive origin to
          ;; zero, sliding the stacked column up/left into the reserved root
          ;; chrome. `(reduce min …)` over the region xs/ys alone preserves the
          ;; leftmost/topmost region's true origin (and stays total — the
          ;; outer `if-not (:parallel? …)` guarantees ≥1 region here).
          region-xs     (map #(:x (get positions (:id %) {:x 0})) region-nodes)
          region-ys     (map #(:y (get positions (:id %) {:y 0})) region-nodes)
          column-x      (reduce min region-xs)
          ;; the column's top (the y the first region's band starts at) —
          ;; preserve the topmost region's original root y so the stack
          ;; doesn't jump to the canvas origin.
          column-y      (reduce min region-ys)
          ;; 1 + 2 — transpose each region interior, then re-stack containers.
          ;; We fold over the ordered regions, accumulating the running y
          ;; cursor for the vertical column.
          [new-positions _]
          (reduce
            (fn [[pos-acc y-cursor] rnode]
              (let [rid       (:id rnode)
                    child-ids (get desc-by-region rid #{})
                    child-pos (select-keys positions child-ids)
                    t-child   (transpose-region-interior child-pos)
                    ;; the transposed interior bbox (children are parent-
                    ;; relative, so this is the inner extent)
                    inner     (bbox (vals t-child))
                    ;; container keeps its header-inset origin for children;
                    ;; size the band to enclose the transposed children plus
                    ;; the right/bottom inset (mirror the left/top the
                    ;; children already sit at).
                    cont-pos  (get positions rid {:x 0 :y 0})
                    band-w    (if inner (+ (:max-x inner) (:min-x inner)) (:width cont-pos))
                    band-h    (if inner (+ (:max-y inner) (:min-y inner)) (:height cont-pos))
                    ;; re-stack: this region container LEFT-aligns to the
                    ;; shared column x and drops to the running y-cursor — the
                    ;; Stately vertical-column stack.
                    stacked-cont (assoc cont-pos
                                        :x column-x
                                        :y y-cursor
                                        :width band-w
                                        :height band-h)
                    pos-acc' (-> pos-acc
                                 (assoc rid stacked-cont)
                                 (merge t-child))]
                [pos-acc' (+ y-cursor band-h region-stack-gap)]))
            [positions column-y]
            region-nodes)
          ;; 3 — drop ELK routes for every edge touching a region (intra- or
          ;; cross-region): the transpose invalidates ELK's absolute bend-
          ;; points; the renderer falls back to the clean bezier through the
          ;; transposed handles.
          node-parent   (into {} (keep (fn [n]
                                         (when-let [p (:parent-id n)]
                                           [(:id n) p]))
                                       nodes))
          region-touch?
          (fn [e]
            (let [sp (get node-parent (:source e))
                  tp (get node-parent (:target e))]
              (or (contains? region-ids sp)
                  (contains? region-ids tp)
                  (contains? region-ids (:source e))
                  (contains? region-ids (:target e)))))
          stale-edge-ids
          (into #{}
                (comp (filter region-touch?)
                      (mapcat (fn [e] [(str (:id e) "__in") (str (:id e) "__out")])))
                edges)
          pruned-points (apply dissoc edge-points stale-edge-ids)
          pruned-labels (apply dissoc edge-labels stale-edge-ids)]
      (assoc layout
             :positions   new-positions
             :edge-points pruned-points
             :edge-labels pruned-labels))))

;; ============================================================================
;; Step 3 — back-edge return-route detour (rf2-gnrkke)
;; ============================================================================
;;
;; §4.3.1 G-ROUTE: under events-as-nodes a back-edge `alarming → reset →
;; locked` is `alarming → <reset event-node> → locked`. ELK ranks the
;; event-node one layer BELOW the deepest state (it is a forward target of
;; the already-deepest source), so the `reset` chip sinks to the canvas
;; bottom and the return reads as a long detour to the deepest layer.
;;
;; A back-edge is detected GEOMETRICALLY from the laid-out result (no GREEDY
;; cycle-breaking, no initial-on-top regression): an event-node whose centre
;; sits BELOW (greater y, for a DOWN layout) both its source AND its target
;; state centres — i.e. it sank past the spine. (Mirror for an `:lr` layout:
;; sank to the RIGHT of both endpoints.)
;;
;; The reroute lifts the back-edge event-node to MID-HEIGHT between its
;; endpoints and routes its two segments as a clean SIDE-DETOUR: source →
;; out the side → up to the lifted chip → in to the target. The chip floats
;; beside the spine (Stately's compact mid-height return) instead of at the
;; bottom. Pure coordinate math on the ABSOLUTE-frame positions + edge-points.

(def back-edge-detour-offset
  "rf2-gnrkke — how far (px) to the SIDE of the spine a rerouted back-edge's
  detour bows, and where the lifted event-node sits. Wide enough that the
  return route reads as a distinct path AROUND the node cluster (not through
  it), matching Stately's return-up-one-side convention."
  64)

(defn- node-center
  "Absolute centre `{:x :y}` of a positioned node. `positions` holds parent-
  relative coords for nested nodes; the back-edge geometry compares nodes on
  the SAME spine (siblings under one container), so a consistent frame
  cancels — we use the recorded position directly (callers pass the absolute
  map for top-level spines, the common back-edge case: door/gate/brew back-
  edges are all top-level)."
  [positions id]
  (when-let [{:keys [x y width height]} (get positions id)]
    {:x (+ x (/ (or width projection/state-node-min-width) 2))
     :y (+ y (/ (or height projection/state-node-min-height) 2))}))

(defn back-edge?
  "rf2-gnrkke — is this parsed edge a back-edge whose event-node SANK below
  both endpoints in the laid-out result? Pure: needs the `positions` map +
  the resolved `direction` (`:tb` / `:lr`).

  True when the synthetic event-node's centre is PAST both the source's and
  the target's centre along the flow axis (y for `:tb`, x for `:lr`) — i.e.
  it ranked one layer beyond the deepest endpoint, the §4.3.1 signature.
  Self-loops and internal transitions (no distinct target) are excluded — a
  self-event-node sits beside its one state, not below a spine."
  [edge positions direction]
  (let [src (:source edge)
        tgt (:target edge)]
    (when (and tgt (not= src tgt) (not (:internal? edge)))
      (let [ev-id  (projection/event-node-id edge)
            ev     (node-center positions ev-id)
            sc     (node-center positions src)
            tc     (node-center positions tgt)]
        (when (and ev sc tc)
          (let [axis (if (= direction :lr) :x :y)]
            (and (> (axis ev) (axis sc))
                 (> (axis ev) (axis tc)))))))))

(defn back-edge-detour
  "rf2-gnrkke — compute the lifted event-node position + the two rerouted
  segment point-lists for one back-edge. Pure: positions + direction + the
  parsed edge → `{:event-pos {:x :y} :in-points [{:x :y}…] :out-points
  [{:x :y}…]}` (or nil when the geometry is degenerate).

  The chip is lifted to mid-height between the source and target centres
  (the Stately compact mid-height return) and bowed `back-edge-detour-offset`
  px to the LEAVING side (the side the source exits toward — left of the
  spine for a `:tb` layout, above the flow row for an `:lr` layout, biasing
  the loop away from the forward column / row). The `__in` segment runs
  source → out the side → the lifted chip; the `__out` runs chip → in to the
  target — a clean detour AROUND the cluster rather than the deep plunge.

  rf2-hpe9ws — the elbow construction MIRRORS the flow axis. For `:tb`
  (vertical flow) the route leaves the source SIDEWAYS to the side lane
  (`detour-x`, keeping the source's y) then runs vertically to the lifted
  chip; for `:lr` (horizontal flow) it must leave the source VERTICALLY to
  the side lane (`detour-y`, keeping the source's x) then run horizontally
  to the chip. A `:tb`-shaped elbow on an `:lr` layout would run along the
  flow row first, crossing the forward edges (the bug)."
  [edge positions direction]
  (let [src (:source edge)
        tgt (:target edge)
        ev-id (projection/event-node-id edge)
        sc (node-center positions src)
        tc (node-center positions tgt)
        ev (get positions ev-id)]
    (when (and sc tc ev)
      (let [tb?      (not= direction :lr)
            ;; mid-point between the endpoints (the Stately mid-height return)
            mid-flow {:x (/ (+ (:x sc) (:x tc)) 2)
                      :y (/ (+ (:y sc) (:y tc)) 2)}
            ;; bow to the LEFT of the spine for :tb (above the row for :lr) —
            ;; the quiet side a return route hugs.
            detour-x (if tb? (- (:x mid-flow) back-edge-detour-offset) (:x mid-flow))
            detour-y (if tb? (:y mid-flow) (- (:y mid-flow) back-edge-detour-offset))
            ;; the lifted event-node position (top-left): centre the chip at
            ;; the detour point, accounting for the chip's box.
            ev-w   (or (:width ev) projection/event-node-elk-width)
            ev-h   (or (:height ev) projection/event-node-elk-height)
            ev-pos {:x (- detour-x (/ ev-w 2))
                    :y (- detour-y (/ ev-h 2))}
            chip-c {:x detour-x :y detour-y}]
        {:event-pos  ev-pos
         ;; source centre → out the side lane → lifted chip. The elbow leaves
         ;; the source ORTHOGONAL to the flow axis: sideways (to detour-x) for
         ;; :tb so it then runs vertically up the lane; upward (to detour-y)
         ;; for :lr so it then runs horizontally along the lane (rf2-hpe9ws —
         ;; mirror the elbow, don't reuse the :tb shape).
         :in-points  [sc
                      (if tb?
                        {:x detour-x :y (:y sc)}
                        {:x (:x sc) :y detour-y})
                      chip-c]
         ;; lifted chip → in to the target. Mirror: the elbow drops back onto
         ;; the target ORTHOGONAL to the flow axis — to the target's x then
         ;; into it for :tb, to the target's y then into it for :lr.
         :out-points [chip-c
                      (if tb?
                        {:x detour-x :y (:y tc)}
                        {:x (:x tc) :y detour-y})
                      tc]}))))

(defn reroute-back-edges
  "rf2-gnrkke — the back-edge return-route detour pass (§4.3.1 close). Pure:
  `{:positions :edge-points :edge-labels}` + parsed graph + resolved
  direction → the same shape with every sunk back-edge's event-node lifted to
  mid-height and its `__in`/`__out` segments rerouted as a side-detour.

  No back-edge ⇒ the layout is returned unchanged (a non-cyclic / already-
  compact machine is untouched). Only back-edges whose event-node SANK
  (`back-edge?`) are rerouted, so a back-edge ELK already placed compactly
  keeps its route."
  [{:keys [positions edge-points] :as layout} parsed direction]
  (let [back-edges (filter #(back-edge? % positions direction) (:edges parsed))]
    (if (empty? back-edges)
      layout
      (reduce
        (fn [{:keys [positions edge-points] :as acc} edge]
          (if-let [{:keys [event-pos in-points out-points]}
                   (back-edge-detour edge positions direction)]
            (let [ev-id (projection/event-node-id edge)
                  ev    (get positions ev-id)]
              (assoc acc
                     :positions   (assoc positions ev-id
                                         (merge ev event-pos))
                     :edge-points (assoc edge-points
                                         (str (:id edge) "__in")  in-points
                                         (str (:id edge) "__out") out-points)))
            acc))
        (assoc layout :positions positions :edge-points edge-points)
        back-edges))))

;; ============================================================================
;; Composing pass
;; ============================================================================

(defn apply-post-elk
  "rf2-lamdfl + rf2-gnrkke — the cohesive post-ELK stage: run the parallel-
  region stacking-axis transpose THEN the back-edge return-route detour on
  the `elk-result->positions` map. Pure: `{:positions :edge-points
  :edge-labels}` + parsed graph + resolved direction → the same shape.

  INVOKED ONLY ON THE OPT-IN PATH. `chart.cljs` calls this exclusively when
  `adaptive?` is true (the host passed `:direction :auto`); on the default /
  forced path it is never reached and the ELK result flows untouched to the
  projector — the byte-identical-to-main guarantee.

  Order matters: the parallel transpose runs FIRST (it re-stacks regions +
  transposes interiors), then the back-edge reroute runs on the resulting
  positions (so a back-edge inside a transposed region — rare — is detected
  against the transposed coords). Both steps are no-ops on a machine that
  does not exhibit their pattern (non-parallel / no sunk back-edge), so the
  pass is identity for the simple linear case even after a machine opts in.

  `chart.cljs` invokes this in its layout-settle callback, after
  `elk-result->positions` and before `done-fn` hands the result to the
  projector — the §4.3 'post-ELK stage, before projection' the beads
  specify."
  [layout parsed direction]
  (-> layout
      (transpose-parallel-regions parsed)
      (reroute-back-edges parsed direction)))
