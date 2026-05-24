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

(defn edge-path
  "Pure path-selection for a transition edge — the single source of
  truth `transition-edge` renders + the test suite pins (rf2-cz8v6).

  Returns `{:d <svg-path-string> :label-x <px> :label-y <px> :routed?
  <bool>}`. Path selection, in priority order:

    1. `self-loop?`  → a small loop off the node's edge (NOT routed —
       self-loops keep their dedicated path even if elk emitted bends).
    2. elk route     → when `points` is a JS array of ≥ 2 `#js {:x :y}`,
       a smooth poly-path THROUGH the bends so the edge goes AROUND
       nested containers (`:routed? true`).
    3. bezier        → `getBezierPath` between the handles (`:routed?
       false`) — the no-bend-point fallback."
  [{:keys [src-x src-y tgt-x tgt-y src-pos tgt-pos self-loop? points]}]
  (let [routed? (and (not self-loop?)
                     (some? points)
                     (> (alength points) 1))]
    (cond
      self-loop?
      (let [r 30]
        {:d (str "M " src-x "," src-y
                 " C " (+ src-x r) "," (- src-y r)
                 " "  (+ src-x r) "," (+ src-y r)
                 " "  src-x       "," src-y)
         :label-x (+ src-x r 4)
         :label-y src-y
         :routed? false})

      routed?
      (let [[lx ly] (path-midpoint points)]
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
  [{:keys [active? focused?]}]
  (cond
    focused? (:info tokens/tokens)
    active?  (:info tokens/tokens)
    :else    (:border-default tokens/tokens)))

(defn- edge-stroke-width
  "rf2-k647w — edge stroke widths read off the resolved density. The
  active stroke sits midway between default + emphasis; the focused
  stroke is the emphasis width (preserving the shipped regular
  relationship 1.5 / 2.0 / 2.5)."
  [{:keys [active? focused? chart]
    :or   {chart vc/chart-regular}}]
  (let [{:keys [stroke-width stroke-width-emphasis]} chart]
    (cond
      focused? stroke-width-emphasis
      active?  (/ (+ stroke-width stroke-width-emphasis) 2.0)
      :else    stroke-width)))

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
        {:keys [edge-label-px edge-label-backplate-opacity]} vc
        ;; rf2-cz8v6 (G2) — path selection lives in the pure `edge-path`
        ;; helper (self-loop → elk route → bezier), so the test suite
        ;; pins the SAME geometry the renderer paints.
        {path :d label-x :label-x label-y :label-y routed? :routed?}
        (edge-path {:src-x src-x :src-y src-y
                    :tgt-x tgt-x :tgt-y tgt-y
                    :src-pos src-pos :tgt-pos tgt-pos
                    :self-loop? self-loop? :points points})
        stroke  (edge-stroke {:active? active? :focused? focused?})
        stroke-w (edge-stroke-width {:active? active? :focused? focused? :chart vc})]
    (r/as-element
      [:<>
       [:> BaseEdge {:id (.-id props)
                     :path path
                     ;; Internal self-transitions suppress the arrowhead
                     ;; (no exit/entry re-trigger to point back at).
                     :markerEnd (when-not internal? marker-end)
                     :style #js {:stroke stroke
                                 :strokeWidth stroke-w
                                 :strokeDasharray (when internal? "4 3")
                                 :animation (when focused?
                                              "mv-chart-transition-glow 720ms ease-out infinite")}}]
       (when (seq label)
       [:> EdgeLabelRenderer
        [:div {:data-testid (str "rf-mv-chart-edge-" (.-id props))
               :data-event (when label label)
               :data-active (str active?)
               :data-focused-edge (str focused?)
               :data-after-ms (when after-ms (str after-ms))
               ;; rf2-ee38b.21 — surface internal / machine-level so the
               ;; DOM suite + a host can distinguish self-transition kind
               ;; + inherited fallbacks.
               :data-internal (str internal?)
               ;; rf2-cz8v6 (G2) — surface whether this edge rendered
               ;; along elk's bend-point route (true) or fell back to
               ;; the bezier (false), so the DOM suite can pin routing.
               :data-routed (str routed?)
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
                                            label-x "px," label-y "px)")
                       :pointer-events "auto"
                       :cursor         (if clickable? "pointer" "default")
                       :font-family    mono-stack
                       :font-size      (str edge-label-px "px")
                       :font-weight    (if (and clickable? focused?) 600 400)
                       :color          (:bg-0 tokens/tokens)
                       :background     (tokens/with-alpha :white edge-label-backplate-opacity)
                       :padding        "1px 5px"
                       :border-radius  "3px"
                       :border         (str "1px solid "
                                            (if clickable?
                                              (tokens/with-alpha :yellow 0.55)
                                              (tokens/with-alpha :border-subtle 0.4)))
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
