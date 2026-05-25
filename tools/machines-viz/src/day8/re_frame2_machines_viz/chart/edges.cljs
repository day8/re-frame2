(ns day8.re-frame2-machines-viz.chart.edges
  "Custom xyflow edge components for the MachineChart.

  rf2-gpzb4 (2026-05-21 xyflow override) — edge components render the
  xstate-style label (`event [guard] / action`) with a small backplate
  for legibility against overlapping edges + the dot-grid background.
  Active-state and focused-event lens highlighting are reflected in
  stroke colour + width via the edge's `:data` payload.

  ## Edge kinds (per the bead's scope §Custom edges)

    - `transition-edge` — the canonical edge. Renders a curved
      path between source/target with the event label sitting at
      the midpoint behind a small backplate rect.
    - `after-edge` — `:after`-timer edge. Same path as transition-
      edge but renders the label as `after(<ms>)` and exposes a
      `:data-after-ms` attr so a host-side overlay can find the
      ring's bearing node.

  rf2-0gmwp — there is no `spawn` edge type. Per Spec 005 `:spawn` /
  `:spawn-all` are state-entry actions that bring CHILD actor machines
  into existence; they are NOT same-machine transitions, so the parse
  (`chart.layout`) emits no spawn edge and `chart.projection/`
  `choose-edge-type` has no `spawn` arm to classify into. Spawned
  children surface through the `chart.overlays.spawn-all-join`
  inspector (anchored beside the spawn-bearing state), not as an edge
  in this chart. The previously-registered `spawn-edge` component was
  dead registration — never reachable from any parsed edge — and was
  removed.

  ## Substrate posture

  Reagent-only Phase 1; UIx + Helix follow-on. xyflow consumes
  these components via the `edgeTypes` prop.

  ## Implementation notes

  xyflow's `getBezierPath` / `getSmoothStepPath` helpers return a
  pre-computed SVG path string + a label-x / label-y position pair.
  We use `getBezierPath` for the polished curve aesthetic that
  competes with Stately Studio."
  (:require ["@xyflow/react" :as xyflow]
            [reagent.core :as r]
            [day8.re-frame2-machines-viz.theme.tokens
             :as tokens
             :refer [mono-stack]]
            [day8.re-frame2-machines-viz.visual-constants :as vc]))

(def ^:private get-bezier-path (.-getBezierPath xyflow))
(def ^:private BaseEdge        (.-BaseEdge xyflow))
(def ^:private EdgeLabelRenderer (.-EdgeLabelRenderer xyflow))

;; ---- elk bend-point routing (rf2-cz8v6, G2) ----------------------------
;;
;; When elk hands us a multi-point route (start → bend… → end, in
;; absolute flow coords), we render the edge ALONG it instead of a
;; single bezier between handles. This is what stops a deeply-nested
;; transition cutting ACROSS a region/compound container — the route
;; goes AROUND it (§1.7 of `001-Topology-Parity.md`).

(def ^:private corner-radius
  "Max rounding applied at each bend (px). Kept small so the route
  stays visibly orthogonal (it reads as elk's Manhattan routing) while
  softening the hard corners to match the chart's bezier aesthetic."
  8)

(defn- poly-path
  "Build an SVG path string that runs THROUGH `pts` (a JS array of
  `#js {:x :y}` points) with small rounded corners at each interior
  bend. `pts` has ≥ 2 entries (the projector / layout filter degenerate
  routes out). Mirrors the rounded-orthogonal look of a hand-routed
  statechart edge without depending on a layout-engine path string."
  [pts]
  (let [n (alength pts)]
    (if (<= n 2)
      ;; A straight start→end run — no interior bend to round.
      (let [a (aget pts 0) b (aget pts (dec n))]
        (str "M " (.-x a) "," (.-y a) " L " (.-x b) "," (.-y b)))
      (let [sb   (js/Array.)
            p0   (aget pts 0)]
        (.push sb (str "M " (.-x p0) "," (.-y p0)))
        ;; For each interior point, draw a line up TO a point shy of the
        ;; corner, then a quadratic curve THROUGH the corner to a point
        ;; just past it — rounding the bend.
        (dotimes [i (- n 2)]
          (let [prev (aget pts i)
                cur  (aget pts (inc i))
                nxt  (aget pts (+ i 2))
                dx1  (- (.-x cur) (.-x prev))
                dy1  (- (.-y cur) (.-y prev))
                dx2  (- (.-x nxt) (.-x cur))
                dy2  (- (.-y nxt) (.-y cur))
                len1 (js/Math.hypot dx1 dy1)
                len2 (js/Math.hypot dx2 dy2)
                ;; clamp the rounding to half of the shorter incident
                ;; segment so adjacent corners never overlap.
                r    (js/Math.min corner-radius (/ len1 2) (/ len2 2))
                ;; entry point: r before the corner along the incoming seg
                ex   (if (pos? len1) (- (.-x cur) (* (/ dx1 len1) r)) (.-x cur))
                ey   (if (pos? len1) (- (.-y cur) (* (/ dy1 len1) r)) (.-y cur))
                ;; exit point: r after the corner along the outgoing seg
                xx   (if (pos? len2) (+ (.-x cur) (* (/ dx2 len2) r)) (.-x cur))
                xy   (if (pos? len2) (+ (.-y cur) (* (/ dy2 len2) r)) (.-y cur))]
            (.push sb (str "L " ex "," ey))
            (.push sb (str "Q " (.-x cur) "," (.-y cur) " " xx "," xy))))
        (let [last-pt (aget pts (dec n))]
          (.push sb (str "L " (.-x last-pt) "," (.-y last-pt))))
        (.join sb " ")))))

(defn- path-midpoint
  "The label anchor for a routed edge: the midpoint of the path's
  middle segment (so the label sits ON the route, not floating at the
  bezier midpoint). `pts` is the JS points array (≥ 2)."
  [pts]
  (let [n (alength pts)
        i (quot n 2)
        a (aget pts (max 0 (dec i)))
        b (aget pts i)]
    [(/ (+ (.-x a) (.-x b)) 2)
     (/ (+ (.-y a) (.-y b)) 2)]))

;; ---- self-loop fan geometry (rf2-shv82, Issue 2) -----------------------
;;
;; Multiple self-loops on the same node (e.g. `:disconnected` has 3:
;; `:ws/arm-fail`, `:ws/disarm-fail`, `:ws/clear`) all anchored at the
;; same `(src-x, src-y)` would stack the loop AND its label at one point,
;; producing the garbled overlap rf2-shv82 reports. The fix: each
;; self-loop on a source gets a stable per-source ordinal (`loop-index`,
;; computed in the projector) which rotates the loop's anchor + label
;; around the source's perimeter, the xstate/Stately fan convention.
;;
;; Eight slots is enough headroom for any realistic per-node self-loop
;; count (>8 self-loops on one state is a code-smell the user owns); the
;; angles spread the loops on alternating sides of the cardinal
;; (top-right / top-left / right-low / left-low / bottom-right /
;; bottom-left / right-high / left-high) so the next slot is always
;; visually distinct from its predecessor.

(def ^:private self-loop-radius
  "Outer radius of one self-loop arc (px). Kept at the historical 30px
  so a SINGLE self-loop on a node renders pixel-identical to pre-
  rf2-shv82 (when loop-index 0 + the canonical top-right slot)."
  30)

(def ^:private self-loop-fan-angles
  "Per-slot (angle-radians, label-side) pairs for the self-loop fan.
  The angle picks the loop's centre direction off the source node; the
  label-side picks which side of the loop the label anchors on (so two
  adjacent slots' labels don't crowd one another).

  Slot 0 is the historical top-right (angle = -π/4, label right): a
  single self-loop renders identically to pre-rf2-shv82."
  [{:angle (* -1 (/ js/Math.PI 4))   :label-side :right}   ;; 0: top-right
   {:angle (* -3 (/ js/Math.PI 4))   :label-side :left}    ;; 1: top-left
   {:angle (/ js/Math.PI 4)          :label-side :right}   ;; 2: bottom-right
   {:angle (* 3 (/ js/Math.PI 4))    :label-side :left}    ;; 3: bottom-left
   {:angle 0                         :label-side :right}   ;; 4: right
   {:angle js/Math.PI                :label-side :left}    ;; 5: left
   {:angle (* -1 (/ js/Math.PI 2))   :label-side :right}   ;; 6: top
   {:angle (/ js/Math.PI 2)          :label-side :right}]) ;; 7: bottom

(defn- self-loop-geometry
  "For a self-loop with the given `loop-index` (0 ≡ historical slot)
  anchored at `(sx, sy)`, return `{:d :label-x :label-y}`. Each loop is
  a small cubic-bezier oval offset from the source in the slot's
  direction; the label anchors just past the loop on the slot's
  designated side."
  [sx sy loop-index]
  (let [slot   (nth self-loop-fan-angles
                    (mod (or loop-index 0) (count self-loop-fan-angles)))
        angle  (:angle slot)
        side   (:label-side slot)
        cos-a  (js/Math.cos angle)
        sin-a  (js/Math.sin angle)
        r      self-loop-radius
        ;; Two control points offset along the slot's angle and one
        ;; perpendicular spread so the loop arcs around (not back onto
        ;; itself). The perpendicular is (-sin-a, cos-a).
        c1x (+ sx (* r cos-a) (* r (- sin-a)))
        c1y (+ sy (* r sin-a) (* r cos-a))
        c2x (+ sx (* r cos-a) (* r sin-a))
        c2y (+ sy (* r sin-a) (* r (- cos-a)))
        ;; Label sits just past the loop along the slot's angle, with a
        ;; small bias on the label-side so adjacent slots don't crowd.
        label-r (+ r 8)
        bias-x  (if (= side :right) 4 -4)
        lx      (+ sx (* label-r cos-a) bias-x)
        ly      (+ sy (* label-r sin-a))]
    {:d (str "M " sx "," sy
             " C " c1x "," c1y
             " "  c2x "," c2y
             " "  sx  "," sy)
     :label-x lx
     :label-y ly}))

;; ---- cross-hierarchy label placement (rf2-shv82, Issue 3) --------------
;;
;; A cross-hierarchy edge (source and target in different parent
;; containers) routed via elk's bend-points has a midpoint that can land
;; in unexpected absolute coords — e.g. the bottom-left corner of the
;; canvas — far from where the user perceives the edge to originate.
;; Stately Studio's convention: place the label near the SOURCE-SIDE
;; first bend point (just outside the container the edge exits), so the
;; label visually tracks its origin. We use the second point on the
;; route (the first bend after the source handle) as the anchor; for a
;; pure two-point route we fall back to the segment midpoint (no bend
;; to anchor on).

(defn- cross-hierarchy-label-anchor
  "Return `[lx ly]` for a cross-hierarchy edge's label, anchored near
  the source-side bend point of the routed path. `pts` is the JS points
  array (≥ 2). Falls back to the midpoint of the first segment for
  degenerate two-point routes (no bend to anchor on)."
  [pts]
  (let [n (alength pts)]
    (if (< n 3)
      ;; No interior bend — anchor on the midpoint of the only segment.
      (let [a (aget pts 0) b (aget pts (dec n))]
        [(/ (+ (.-x a) (.-x b)) 2)
         (/ (+ (.-y a) (.-y b)) 2)])
      ;; Anchor at the first bend after the source handle. With a slight
      ;; bias along the incoming segment so the label sits just past the
      ;; corner, not on top of it.
      (let [a    (aget pts 0)
            b    (aget pts 1)
            dx   (- (.-x b) (.-x a))
            dy   (- (.-y b) (.-y a))
            len  (js/Math.hypot dx dy)
            bias 6
            ;; Step `bias` px back from the bend along the incoming
            ;; segment so the label sits in the routed channel.
            bx   (if (pos? len) (- (.-x b) (* (/ dx len) bias)) (.-x b))
            by   (if (pos? len) (- (.-y b) (* (/ dy len) bias)) (.-y b))]
        [bx by]))))

(defn edge-path
  "Pure path-selection for a transition edge — the single source of
  truth `transition-edge` renders + the test suite pins (rf2-cz8v6).

  Returns `{:d <svg-path-string> :label-x <px> :label-y <px> :routed?
  <bool>}`. Path selection, in priority order:

    1. `self-loop?`  → a small loop off the node's edge (NOT routed —
       self-loops keep their dedicated path even if elk emitted bends).
       rf2-shv82 (Issue 2): when `loop-index` > 0 the loop rotates to
       a distinct perimeter slot so multiple self-loops on one node fan
       out (xstate/Stately convention). `loop-index` nil / 0 keeps the
       historical top-right slot pixel-identical.
    2. elk route     → when `points` is a JS array of ≥ 2 `#js {:x :y}`,
       a smooth poly-path THROUGH the bends so the edge goes AROUND
       nested containers (`:routed? true`).
       rf2-shv82 (Issue 3): when `cross-hierarchy?` is true the label
       anchors near the SOURCE-SIDE first bend point (just outside the
       container the edge exits) instead of the routed midpoint — the
       Stately Studio convention for cross-hierarchy edges (whose
       midpoint can land far from the visual origin).
    3. bezier        → `getBezierPath` between the handles (`:routed?
       false`) — the no-bend-point fallback."
  [{:keys [src-x src-y tgt-x tgt-y src-pos tgt-pos
           self-loop? points loop-index cross-hierarchy?]}]
  (let [routed? (and (not self-loop?)
                     (some? points)
                     (> (alength points) 1))]
    (cond
      self-loop?
      (let [{:keys [d label-x label-y]}
            (self-loop-geometry src-x src-y loop-index)]
        {:d d :label-x label-x :label-y label-y :routed? false})

      routed?
      (let [[lx ly] (if cross-hierarchy?
                      (cross-hierarchy-label-anchor points)
                      (path-midpoint points))]
        {:d (poly-path points) :label-x lx :label-y ly :routed? true})

      :else
      (let [bz (get-bezier-path
                 #js {:sourceX        src-x
                      :sourceY        src-y
                      :sourcePosition src-pos
                      :targetX        tgt-x
                      :targetY        tgt-y
                      :targetPosition tgt-pos})]
        {:d (aget bz 0) :label-x (aget bz 1) :label-y (aget bz 2) :routed? false}))))

;; ---- helpers ------------------------------------------------------------
;;
;; rf2-k647w — edge typography (label font-size + backplate opacity)
;; reads off the resolved density's `visual-constants` map, threaded
;; onto the edge `:data` by the projector. xyflow `clj->js`-es the edge
;; array, so `chart-constants` recovers the kebab-keyword CLJS map and
;; falls back to `vc/chart-regular` when absent.

(defn- chart-constants
  "Recover the resolved visual-constants map off an edge's `:data`
  (`(.-chart d)`); `vc/chart-regular` when absent so the regular
  density stays pixel-identical to the pre-rf2-k647w hardcoded values."
  [^js d]
  (let [c (.-chart d)]
    (if (some? c)
      (js->clj c :keywordize-keys true)
      vc/chart-regular)))

(defn- edge-stroke
  "rf2-qeemm (G3) — `fired?` (the edge traversed THIS epoch) wins over
  `focused?` / `active?` so a fired arm reads as 'what just happened' in
  the FIRED hue (`:accent`, distinct from the focused/active `:info`).
  Palette delegated to Figma — `:accent` is the existing token, not a new
  palette entry."
  [{:keys [active? focused? fired?]}]
  (cond
    fired?               (:accent tokens/tokens)
    (or focused? active?) (:info tokens/tokens)
    :else                (:border-default tokens/tokens)))

(defn- edge-stroke-width
  "rf2-k647w — edge stroke widths read off the resolved density. The
  active stroke sits midway between default + emphasis; the focused
  stroke is the emphasis width (preserving the shipped regular
  relationship 1.5 / 2.0 / 2.5).

  rf2-qeemm (G3) — `fired?` paints at the emphasis width (the heaviest
  treatment), at least as prominent as `focused?`, so a traversed edge
  stands out even when it isn't the focused FROM→TO lens (both the fired
  arm and the focused lens share the emphasis width)."
  [{:keys [active? focused? fired? chart]
    :or   {chart vc/chart-regular}}]
  (let [{:keys [stroke-width stroke-width-emphasis]} chart]
    (cond
      (or fired? focused?) stroke-width-emphasis
      active?              (/ (+ stroke-width stroke-width-emphasis) 2.0)
      :else                stroke-width)))

;; ---- transition-edge ----------------------------------------------------

(defn transition-edge
  "Reagent component for a transition edge. xyflow invokes this with
  source/target coords + a `:data` payload carrying the event label
  + state flags. Returns a `<BaseEdge>` + a label renderer in a
  React fragment."
  [^js props]
  (let [src-x      (.-sourceX props)
        src-y      (.-sourceY props)
        tgt-x      (.-targetX props)
        tgt-y      (.-targetY props)
        src-pos    (.-sourcePosition props)
        tgt-pos    (.-targetPosition props)
        marker-end (.-markerEnd props)
        d          (.-data props)
        vc         (chart-constants d)
        label      (or (.-eventLabel d) "")
        active?    (boolean (.-active d))
        focused?   (boolean (.-focused d))
        ;; rf2-qeemm (G3) — this edge traversed THIS epoch. Drives the
        ;; FIRED treatment (emphasised + animated stroke, `data-fired`),
        ;; winning over the focused/active styling per `edge-stroke`.
        fired?     (boolean (.-fired d))
        after-ms   (.-afterMs d)
        ;; rf2-u422r — on-chart click wiring. `:onClick` is the host
        ;; callback (e.g. Xray's on-chart sim → sim-step); `:eventId`
        ;; is the raw fireable event keyword (nil for `:after` / `:always`
        ;; auto edges). A label is clickable only when BOTH a callback +
        ;; a fireable event-id are present, so auto edges stay inert.
        on-click   (.-onClick d)
        event-id   (.-eventId d)
        from-path  (.-fromPath d)
        to-path    (.-toPath d)
        clickable? (and (fn? on-click) (some? event-id))
        self-loop? (boolean (.-selfLoop d))
        ;; rf2-shv82 (Issue 2) — per-source self-loop ordinal so multiple
        ;; self-loops on one node fan around the perimeter (instead of
        ;; stacking at the same coords). nil for non-self-loops.
        loop-index (.-loopIndex d)
        ;; rf2-shv82 (Issue 3) — cross-hierarchy edge flag. When true +
        ;; routed, `edge-path` anchors the label near the source-side
        ;; first bend point (Stately convention) instead of the routed
        ;; midpoint.
        cross-hierarchy? (boolean (.-crossHierarchy d))
        ;; rf2-ee38b.21 — an INTERNAL self-transition (Spec 005:
        ;; omit :target) runs only :action; :exit / :entry do NOT fire.
        ;; Render it WITHOUT the re-entry arrowhead + with a dashed loop
        ;; so it reads as "no exit/entry re-trigger" (Stately parity).
        internal?  (boolean (.-internal d))
        ;; rf2-cz8v6 (G2) — elk's routed bend-points (absolute coords),
        ;; a JS array of `#js {:x :y}`. Present only when elk computed a
        ;; multi-point route; nil for a simple edge (bezier fallback)
        ;; and for self-loops (which keep their dedicated loop path).
        points     (.-points d)
        ;; rf2-j10sm (Phase 2, B) — multi-event same-`[source target]`
        ;; collapse. The projector groups edges by their `[source target]`
        ;; pair and assigns each a `:siblingIndex` 0..N-1 + a
        ;; `:siblingCount` N (in emission order). ONLY the leader
        ;; (`:siblingIndex 0`) renders the SVG path + arrowhead; siblings
        ;; suppress the path but render their OWN label, vertically
        ;; stacked above/below the leader so adjacent event labels read as
        ;; one xstate/Stately-style event-list on a single arrow rather
        ;; than N overlapping arrows + N overlapping labels. nil / 1 keeps
        ;; the single-event behaviour pixel-identical.
        sibling-index   (or (.-siblingIndex d) 0)
        sibling-count   (or (.-siblingCount d) 1)
        sibling-leader? (zero? sibling-index)
        {:keys [edge-label-px edge-label-backplate-opacity]} vc
        ;; rf2-j10sm (Phase 2, B) — per-sibling label-y offset. Vertical
        ;; row pitch ≈ (label font-size + 6px padding) so adjacent labels
        ;; touch without overlap. Centred on the geometric anchor
        ;; (`label-y`) so a 3-event group reads as [−Δ, 0, +Δ]; a 2-event
        ;; group as [−Δ/2, +Δ/2]; single-event group as [0].
        row-pitch       (+ edge-label-px 6)
        label-y-offset  (* row-pitch
                           (- sibling-index (/ (dec sibling-count) 2.0)))
        ;; rf2-cz8v6 (G2) — path selection lives in the pure `edge-path`
        ;; helper (self-loop → elk route → bezier), so the test suite
        ;; pins the SAME geometry the renderer paints.
        {path :d label-x :label-x label-y :label-y routed? :routed?}
        (edge-path {:src-x src-x :src-y src-y
                    :tgt-x tgt-x :tgt-y tgt-y
                    :src-pos src-pos :tgt-pos tgt-pos
                    :self-loop? self-loop?
                    :points points
                    :loop-index loop-index
                    :cross-hierarchy? cross-hierarchy?})
        stroke  (edge-stroke {:active? active? :focused? focused? :fired? fired?})
        stroke-w (edge-stroke-width {:active? active? :focused? focused?
                                     :fired? fired? :chart vc})]
    (r/as-element
      [:<>
       ;; rf2-j10sm (Phase 2, B) — non-leader siblings (same `[source
       ;; target]` pair, siblingIndex > 0) suppress the SVG path so N
       ;; events on one transition render as ONE arrow with N stacked
       ;; labels (the xstate/Stately convention), not N overlapping
       ;; arrows. Single-event edges (siblingCount 1) always render the
       ;; path — leader == only sibling.
       (when sibling-leader?
         [:> BaseEdge {:id (.-id props)
                       :path path
                       ;; Internal self-transitions suppress the arrowhead
                       ;; (no exit/entry re-trigger to point back at).
                       :markerEnd (when-not internal? marker-end)
                       :style #js {:stroke stroke
                                   :strokeWidth stroke-w
                                   :strokeDasharray (when internal? "4 3")
                                   ;; rf2-qeemm (G3) — the fired-this-epoch edge
                                   ;; animates the same glow as the focused lens
                                   ;; (it traversed), so a fired arm that is NOT
                                   ;; the focused FROM→TO still pulses.
                                   :animation (when (or focused? fired?)
                                                "mv-chart-transition-glow 720ms ease-out infinite")}}])
       (when (seq label)
       [:> EdgeLabelRenderer
        [:div {:data-testid (str "rf-mv-chart-edge-" (.-id props))
               :data-event (when label label)
               :data-active (str active?)
               :data-focused-edge (str focused?)
               ;; rf2-qeemm (G3) — DOM pin for the fired-this-epoch edge
               ;; treatment; tests + hosts read it to find the traversed arm.
               :data-fired (str fired?)
               :data-after-ms (when after-ms (str after-ms))
               ;; rf2-ee38b.21 — surface internal / machine-level so the
               ;; DOM suite + a host can distinguish self-transition kind
               ;; + inherited fallbacks.
               :data-internal (str internal?)
               ;; rf2-cz8v6 (G2) — surface whether this edge rendered
               ;; along elk's bend-point route (true) or fell back to
               ;; the bezier (false), so the DOM suite can pin routing.
               :data-routed (str routed?)
               ;; rf2-shv82 (Issue 2) — fan ordinal for the self-loop
               ;; perimeter slot; surfaced so the DOM suite can pin
               ;; multi-self-loop slotting per source node.
               :data-loop-index (when (some? loop-index) (str loop-index))
               ;; rf2-shv82 (Issue 3) — cross-hierarchy flag the label-
               ;; placement adjustment reads; surfaced so the DOM suite
               ;; can pin label-anchor-near-source-bend behaviour.
               :data-cross-hierarchy (str cross-hierarchy?)
               ;; rf2-j10sm (Phase 2, B) — sibling slot in the merged
               ;; multi-event group + per-group total. Surfaced so DOM
               ;; tests + hosts can pin which event in a stacked label
               ;; list this label is, and whether the edge collapsed
               ;; (sibling-count > 1).
               :data-sibling-index (str sibling-index)
               :data-sibling-count (str sibling-count)
               :data-machine-level (str (boolean (.-machineLevel d)))
               ;; rf2-u422r — clickable edges surface their fireable
               ;; event-id so a host (Xray on-chart sim) + tests can
               ;; address them; inert (auto / no-callback) edges omit it.
               :data-clickable (str clickable?)
               :data-event-id (when (and clickable? event-id)
                                (str event-id))
               :role (when clickable? "button")
               :title (when clickable?
                        (str "Send " event-id))
               :on-click (when clickable?
                           (fn [ev]
                             ;; Stop the click bubbling to the xyflow
                             ;; pane (which would pan / clear selection).
                             (.stopPropagation ev)
                             (on-click
                               #js {:eventId  event-id
                                    :fromPath from-path
                                    :toPath   to-path})))
               :style {:position       "absolute"
                       :transform      (str "translate(-50%, -50%) translate("
                                            label-x "px," (+ label-y label-y-offset) "px)")
                       :pointer-events "auto"
                       :cursor         (if clickable? "pointer" "default")
                       :font-family    mono-stack
                       :font-size      (str edge-label-px "px")
                       :font-weight    (if (and clickable? focused?) 600 400)
                       ;; rf2-j10sm (Phase 1, D) — padded label backgrounds.
                       ;; Opaque chart-bg colour matching the canvas
                       ;; (`:bg-1`, the colour the chart root paints), so
                       ;; each label "punches through" overlapping nodes /
                       ;; edges with the surrounding background. Light text
                       ;; on the dark canvas instead of the previous
                       ;; white-pill + dark-text chip. Rounded ~4px corners
                       ;; + ~4px padding give the label breathing room. The
                       ;; `:edge-label-backplate-opacity` knob is preserved
                       ;; for hosts that want a semi-transparent backplate
                       ;; (still applied as the bg alpha; the new default of
                       ;; 0.94 is high enough to mask underlying ink while
                       ;; admitting a hint of the canvas so adjacent labels
                       ;; do not look like solid stacked tiles).
                       :color          (:text-primary tokens/tokens)
                       :background     (tokens/with-alpha :bg-1 edge-label-backplate-opacity)
                       :padding        "2px 6px"
                       :border-radius  "4px"
                       :border         (str "1px solid "
                                            (if clickable?
                                              (tokens/with-alpha :yellow 0.7)
                                              (tokens/with-alpha :border-default 0.6)))
                       ;; rf2-j10sm (Phase 1, D) — z-index bump. The edge
                       ;; label sits above edges + node borders but below
                       ;; interactive controls (xyflow's Controls render at
                       ;; z 6+). A modest 5 reads as "label is the focus
                       ;; you must be able to scan" without blocking the
                       ;; built-in chrome.
                       :z-index        5
                       :white-space    "nowrap"
                       :user-select    "none"}}
         label]])])))

;; ---- after-edge ---------------------------------------------------------

;; rf2-gpzb4 — :after-timer edges share the transition-edge body but
;; carry the after-ms duration as a data-attribute so a host-side
;; overlay (Xray's machine_after_rings ns) can find the bearing
;; node and paint a countdown ring on top. The default styling is
;; identical to transition-edge; future polish (a small countdown-
;; ring glyph rendered inline on the label) can land in a follow-on
;; bead.

(def after-edge
  "Alias for `transition-edge` — `:after`-edge specifics ride on the
  `:data {:after-ms}` attr; the rendering shape is the same."
  transition-edge)

;; ---- edge-types map -----------------------------------------------------

(defn edge-types
  "The `edgeTypes` prop value for `<ReactFlow>`. Returns a fresh
  plain-JS object on every call; callers SHOULD memoise this map at
  component-construction time to avoid re-render churn.

  Only `transition` + `after` are registered — every parsed edge maps
  to one of those two via `chart.projection/choose-edge-type`. There
  is no `spawn` type (rf2-0gmwp): spawns are state-entry actions, not
  edges (see the ns docstring)."
  []
  #js {"transition" transition-edge
       "after"      after-edge})
