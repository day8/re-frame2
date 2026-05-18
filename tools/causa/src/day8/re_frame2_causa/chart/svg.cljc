(ns day8.re-frame2-causa.chart.svg
  "Hiccup SVG renderer for the Causa Machine Inspector chart
  (rf2-2tkza, Phase 1).

  Consumes the positioned graph from
  `day8.re-frame2-causa.chart.layout` and emits a hiccup `[:svg
  ...]` form. Pure data → data — JVM-runnable so the renderer is
  unit-testable from `clojure -M:test`. Live-snapshot highlighting
  is driven via the `:highlight-id` arg; the CLJS panel passes the
  current snapshot's `:state`-derived id.

  ## Visual grammar (per spec/003-Machine-Inspector.md §Definition view)

    - **Rounded rectangle nodes** with state label centred.
    - **Live highlight** — cyan (`:cyan` token) border + inner glow
      on the active state.
    - **Initial-state marker** — a small filled circle to the left
      of the initial node, with a connecting arrow into the node.
    - **Final-state marker** — doubled border + a small check glyph
      in the corner.
    - **Edges** — straight grey lines with arrow heads; the event
      label sits at the midpoint with a subtle background pill.
    - **Self-loops** — bezier arc to the right of the node.
    - **Tags** — when a state carries `:tags`, a coloured pill row
      above the node label (one pill per tag, deterministic colour).

  This v1 surface deliberately avoids ELK's orthogonal routing,
  obstacle avoidance, and compound-state nesting — the layered
  layout in `layout.cljc` produces a readable chart for the
  4-12-state machines re-frame2 apps register today. Bigger
  topologies fall back to scrollable overflow."
  (:require [clojure.string :as str]
            [day8.re-frame2-causa.chart.layout :as layout]
            [day8.re-frame2-causa.theme.tokens :as tokens]))

;; ---- visual constants ---------------------------------------------------

(def ^:private corner-radius 8)
(def ^:private stroke-width 1.5)
(def ^:private highlight-stroke-width 2.5)
(def ^:private font-stack
  "ui-monospace, SFMono-Regular, 'JetBrains Mono', Menlo, Consolas, monospace")

;; ---- helpers ------------------------------------------------------------

(defn- node-fill
  [{:keys [highlight? sim?]}]
  (cond
    sim?       "rgba(251, 191, 36, 0.18)"   ;; amber tint (Sim mode)
    highlight? "rgba(67, 195, 208, 0.18)"   ;; cyan tint (live)
    :else      "#1c2030"))

(defn- node-stroke
  [{:keys [highlight? sim? final?]}]
  (cond
    sim?       (:yellow tokens/tokens)
    highlight? (:cyan tokens/tokens)
    final?     (:green tokens/tokens)
    :else      (:border-default tokens/tokens)))

(defn compound-containers
  "rf2-m7co9 Phase 4 — compute the bounding box of each compound state
  from the union of its descendant leaves' positions. Returns a vec of
  `{:node-id :x :y :width :height :path :label}` maps, one per
  compound parent that has at least one descendant in the positioned
  graph. Pure fn — JVM-runnable.

  Why an after-the-fact pass: the layered fallback engine places every
  state (including compound parents) as a flat node so the data
  interface stays uniform across engines. The compound container is a
  *visual* concept — its rectangle is the union-bbox of its children
  plus a small inset margin. Both the layered and ELK engines feed
  this fn the same way.

  When the compound parent itself has no children in the projected
  graph (e.g. an empty `:states {}`) the container is dropped."
  [nodes]
  (let [compound-parents (filter :compound? nodes)
        ;; Group every node by the prefix-paths of its ancestors so we
        ;; can do a single pass instead of scanning per-parent.
        by-parent-path
        (reduce (fn [m n]
                  (let [p (:path n)]
                    (if (> (count p) 1)
                      (let [parent-p (subvec p 0 (dec (count p)))]
                        (update m parent-p (fnil conj []) n))
                      m)))
                {}
                nodes)
        pad-x 16
        pad-y 24                            ;; extra top padding for the title strip
        ;; For deeply-nested compounds, descendant leaves can sit
        ;; multiple levels down; collect every descendant by
        ;; checking the path-prefix.
        descendants-of
        (fn [parent-path]
          (filter (fn [n]
                    (let [p (:path n)]
                      (and (> (count p) (count parent-path))
                           (= parent-path (subvec p 0 (count parent-path))))))
                  nodes))]
    (vec
      (keep
        (fn [parent]
          (let [parent-path (:path parent)
                kids        (or (get by-parent-path parent-path) [])
                ;; Use the full descendant set so a compound with a
                ;; compound child still draws a sensible outer hull.
                hull        (descendants-of parent-path)]
            (when (seq hull)
              (let [min-x (apply min (map :x hull))
                    min-y (apply min (map :y hull))
                    max-x (apply max (map (fn [n] (+ (:x n) (:width n))) hull))
                    max-y (apply max (map (fn [n] (+ (:y n) (:height n))) hull))]
                {:node-id (:node-id parent)
                 :path    parent-path
                 :label   (:label parent)
                 :x       (- min-x pad-x)
                 :y       (- min-y pad-y)
                 :width   (+ (- max-x min-x) (* 2 pad-x))
                 :height  (+ (- max-y min-y) pad-x pad-y)
                 :leaf-count (count kids)}))))
        compound-parents))))

(defn- render-compound-container
  "One translucent box around a compound state's children. Carries a
  small title strip across the top with the compound state's label so
  the visual grouping is self-explanatory."
  [{:keys [x y width height label node-id]}]
  [:g {:data-testid (str "rf-causa-chart-compound-" node-id)
       :data-node-id node-id}
   [:rect {:x x :y y :width width :height height
           :rx 10
           :fill "rgba(124, 92, 255, 0.06)"          ;; translucent violet
           :stroke (:accent-violet tokens/tokens)
           :stroke-width 1
           :stroke-dasharray "4 3"
           :pointer-events "none"}]
   ;; Title strip — small label flushed top-left so the parent state's
   ;; name reads as a "section heading" above its children.
   (when label
     [:text {:x (+ x 10)
             :y (+ y 14)
             :font-family font-stack
             :font-size 10
             :font-weight 600
             :fill (:accent-violet tokens/tokens)
             :pointer-events "none"}
      label])])

(defn- arrow-marker
  "Define an arrow marker once, reuse it on every edge."
  []
  [:defs
   [:marker {:id "rf-causa-chart-arrow"
             :viewBox "0 0 10 10"
             :refX "9" :refY "5"
             :markerWidth "8" :markerHeight "8"
             :orient "auto-start-reverse"
             :fill (:text-secondary tokens/tokens)}
    [:path {:d "M 0 0 L 10 5 L 0 10 z"}]]
   [:marker {:id "rf-causa-chart-arrow-highlight"
             :viewBox "0 0 10 10"
             :refX "9" :refY "5"
             :markerWidth "8" :markerHeight "8"
             :orient "auto-start-reverse"
             :fill (:cyan tokens/tokens)}
    [:path {:d "M 0 0 L 10 5 L 0 10 z"}]]])

(defn- render-initial-marker
  [initial-node]
  (when initial-node
    (let [cx (- (:x initial-node) 16)
          cy (+ (:y initial-node) (quot (:height initial-node) 2))
          nx (:x initial-node)
          ny cy]
      [:g {:data-testid "rf-causa-chart-initial-marker"}
       [:circle {:cx cx :cy cy :r 5
                 :fill (:accent-violet tokens/tokens)
                 :stroke (:accent-violet tokens/tokens)
                 :stroke-width 1}]
       [:line {:x1 (+ cx 5) :y1 cy
               :x2 (- nx 2) :y2 ny
               :stroke (:text-secondary tokens/tokens)
               :stroke-width stroke-width
               :marker-end "url(#rf-causa-chart-arrow)"}]])))

(defn- render-node
  [{:keys [x y width height label final? compound? node-id path] :as n}
   {:keys [highlight-id sim? on-state-click]}]
  (let [active?    (= node-id highlight-id)
        styled     (assoc n
                          :highlight? active?
                          :sim?       (and active? sim?))
        fill       (node-fill styled)
        stroke     (node-stroke styled)
        stroke-w   (if active? highlight-stroke-width stroke-width)]
    [:g {:data-testid (str "rf-causa-chart-node-" node-id)
         :data-active (str active?)
         :data-state-path (pr-str path)
         :on-click (when on-state-click
                     (fn [_ev] (on-state-click path)))
         :style {:cursor (if on-state-click "pointer" "default")}}
     ;; Compound states get a slightly larger dashed outer border
     (when compound?
       [:rect {:x (- x 4) :y (- y 4)
               :width (+ width 8) :height (+ height 8)
               :rx (+ corner-radius 2)
               :fill "none"
               :stroke (:border-subtle tokens/tokens)
               :stroke-width 1
               :stroke-dasharray "4 3"}])
     ;; Final states get a double-ring
     (when final?
       [:rect {:x (- x 3) :y (- y 3)
               :width (+ width 6) :height (+ height 6)
               :rx (+ corner-radius 1)
               :fill "none"
               :stroke (:green tokens/tokens)
               :stroke-width 1}])
     ;; Main node body
     [:rect {:x x :y y :width width :height height
             :rx corner-radius
             :fill fill
             :stroke stroke
             :stroke-width stroke-w}]
     ;; Label
     ;; rf2-gz7vi — state-label tuned 11 → 9 (follow-up to rf2-isgqu's
     ;; 12 → 11 — diagram still didn't fit at default width). Vertical
     ;; baseline tweak (+3 → +2) keeps the glyphs visually centred at
     ;; the smaller size.
     [:text {:x (+ x (quot width 2))
             :y (+ y (quot height 2) 2)
             :text-anchor "middle"
             :font-family font-stack
             :font-size 9
             :font-weight (if active? 600 400)
             :fill (if active?
                     (:text-primary tokens/tokens)
                     (:text-secondary tokens/tokens))}
      label]
     ;; Final-state check glyph
     ;; rf2-gz7vi — corner check tuned 10 → 9 to track the state-label
     ;; reduction (rf2-isgqu followed 12 → 11; this PR follows 11 → 9).
     (when final?
       [:text {:x (+ x width -9)
               :y (+ y 13)
               :font-family font-stack
               :font-size 9
               :fill (:green tokens/tokens)}
        "✓"])]))

(defn- path-from-points
  "Render an SVG path `d` attribute from a point list.

    - 2 points → straight line.
    - 4 points → cubic bezier (self-loop / hand-routed S-curve).
    - 3 or 5+ points → polyline (ELK orthogonal routing returns
      multi-segment edges via bend points; we render them as connected
      `L` segments). rf2-m7co9 Phase 4."
  [points]
  (case (count points)
    0 ""
    1 ""
    2 (let [[[x1 y1] [x2 y2]] points]
        (str "M " x1 " " y1 " L " x2 " " y2))
    4 (let [[[x1 y1] [cx1 cy1] [cx2 cy2] [x2 y2]] points]
        (str "M " x1 " " y1
             " C " cx1 " " cy1
             " "  cx2 " " cy2
             " "  x2  " " y2))
    ;; 3 or 5+ points → polyline.
    (let [[[x0 y0] & rst] points]
      (str "M " x0 " " y0
           " "
           (str/join " "
                     (map (fn [[x y]] (str "L " x " " y)) rst))))))

(defn- edge-label-position
  "Mid-point of the edge for label placement.

    - 4-point self-loop bezier → label to the right of the control
      points.
    - 2-point straight line → midpoint of the segment.
    - 3+ point polyline (orthogonal routing) → midpoint of the middle
      segment so the label sits near the visual centre of the path."
  [points]
  (cond
    (= 4 (count points))
    (let [[_ [cx _] _ _] points] [(+ cx 30) (second (second points))])

    (>= (count points) 3)
    (let [n (count points)
          mid-idx (quot n 2)
          [x1 y1] (nth points (dec mid-idx))
          [x2 y2] (nth points mid-idx)]
      [(quot (+ x1 x2) 2) (- (quot (+ y1 y2) 2) 6)])

    :else
    (let [[[x1 y1] [x2 y2]] points]
      [(quot (+ x1 x2) 2) (- (quot (+ y1 y2) 2) 6)])))

(defn- render-edge
  [{:keys [event-label points guard from-id to-id]
    :as _edge}
   {:keys [highlight-id]}]
  (let [active? (or (= from-id highlight-id)
                    (= to-id   highlight-id))
        stroke  (if active?
                  (:cyan tokens/tokens)
                  (:border-default tokens/tokens))
        marker  (if active?
                  "url(#rf-causa-chart-arrow-highlight)"
                  "url(#rf-causa-chart-arrow)")
        [lx ly] (edge-label-position points)
        full-label (if guard
                     (str event-label " [" (if (keyword? guard)
                                             (name guard) (str guard)) "]")
                     event-label)]
    [:g {:data-testid (str "rf-causa-chart-edge-" from-id "-to-" to-id)
         :data-event event-label
         :data-active (str active?)}
     [:path {:d (path-from-points points)
             :fill "none"
             :stroke stroke
             :stroke-width (if active? highlight-stroke-width stroke-width)
             :marker-end marker}]
     (when (seq full-label)
       [:g
        ;; rf2-gz7vi — edge-label tuned 9 → 7 (rf2-isgqu went 10 → 9;
        ;; chart still crowded). Keeps state > edge by ~2px so the
        ;; visual hierarchy still reads. Pill geometry shrinks
        ;; proportionally: 3.5/7 → 2.75/5.5 char-width, height
        ;; 12 → 10, vertical offset 8 → 7 to match the smaller glyph.
        ;; Background pill for legibility
        [:rect {:x (- lx (long (* 2.75 (count full-label))))
                :y (- ly 7)
                :width (long (* 5.5 (count full-label)))
                :height 10
                :rx 3
                :fill (:bg-2 tokens/tokens)
                :opacity 0.85}]
        [:text {:x lx :y ly
                :text-anchor "middle"
                :font-family font-stack
                :font-size 7
                :fill (:text-secondary tokens/tokens)}
         full-label]])]))

;; ---- public entry -------------------------------------------------------

(defn render
  "Render a positioned graph (from `layout/layout`) as a hiccup SVG.

  Options:

    :highlight-id     — node-id to render as the active state (cyan
                        when `:sim?` is false, amber when true)
    :sim?             — flips the highlight palette to amber
                        (UC1 sim mode — follow-on bead wires this)
    :on-state-click   — `(fn [path] ...)` called when a node is clicked
                        (Causa wires this to its source-coord jump)
    :testid           — overrides the root SVG data-testid

  Returns a stable hiccup form. Empty graphs (no nodes) render an
  inline note so the panel never sees an empty SVG container."
  ([positioned] (render positioned {}))
  ([{:keys [nodes edges width height] :as positioned}
    {:keys [highlight-id sim? on-state-click testid]}]
   (if (empty? nodes)
     [:div {:data-testid (or testid "rf-causa-chart-empty")
            :style {:padding "16px"
                    :font-family (str "Inter, system-ui, sans-serif")
                    :font-size "12px"
                    :color (:text-tertiary tokens/tokens)}}
      "Machine has no states to render."]
     (let [initial-node (some (fn [n]
                                (when (and (:initial? n)
                                           (= 0 (:depth n))) n))
                              nodes)
           containers (compound-containers nodes)]
       [:svg {:data-testid (or testid "rf-causa-chart-svg")
              :data-highlight-id (or highlight-id "")
              :viewBox (str "0 0 " width " " height)
              :width "100%"
              :preserveAspectRatio "xMidYMin meet"
              :style {:background (:bg-1 tokens/tokens)
                      :border-radius "4px"
                      :max-height "100%"
                      :display "block"}}
        (arrow-marker)
        ;; Compound containers sit BELOW edges + nodes so the dashed
        ;; outline reads as a backdrop. rf2-m7co9 Phase 4.
        (into [:g {:data-testid "rf-causa-chart-compounds"}]
              (for [c containers]
                ^{:key (:node-id c)}
                (render-compound-container c)))
        ;; Edges first so nodes paint over them
        (into [:g {:data-testid "rf-causa-chart-edges"}]
              (for [edge edges]
                ^{:key (str (:from-id edge) "->" (:to-id edge)
                            "/" (:event-label edge))}
                (render-edge edge {:highlight-id highlight-id})))
        (render-initial-marker initial-node)
        (into [:g {:data-testid "rf-causa-chart-nodes"}]
              (for [node nodes]
                ^{:key (:node-id node)}
                (render-node node
                             {:highlight-id   highlight-id
                              :sim?           sim?
                              :on-state-click on-state-click})))]))))

(defn render-from-definition
  "Convenience: lay out + render in one call. Useful for the panel
  and for the panel-gallery testbed.

  See `render` for the option map."
  ([definition] (render-from-definition definition {}))
  ([definition opts]
   (render (layout/layout definition) opts)))

;; ---- sparkline primitive (rf2-juon8, Mode C) ---------------------------
;;
;; A tiny inline SVG sparkline for the cluster-row state-change rate
;; indicator. Pure data → hiccup; JVM-runnable so the cluster-helpers
;; tests can exercise the shape without a CLJS runtime.
;;
;; The glyph renders the sample vector (oldest-first) as a flat polyline,
;; padded into the box. Empty / all-zero samples collapse to a centred
;; baseline so the visual lane stays consistent across cluster rows.

;; ---- countdown ring primitive (rf2-7hwwe) ------------------------------
;;
;; A partial-circle SVG glyph drawn AROUND a chart state-node when an
;; `:after` timer is armed for that state. The ring sweeps from a full
;; circle (just armed) to empty (about to fire); colour shifts from
;; green (fresh) through amber (mid-cycle) to red (firing-soon).
;;
;; Implementation: SVG `<circle stroke-dasharray>` trick. A circle's
;; circumference is `2πr`; setting `stroke-dasharray` to
;; `[arc-len, (2πr - arc-len)]` produces a partial sweep. Rotating the
;; circle -90° puts the sweep's origin at 12 o'clock (the conventional
;; clock face).
;;
;; Pure data → hiccup; JVM-runnable so the rings-helpers tests can
;; assert against the shape without a CLJS runtime.

(def ^:private ring-color->token
  "Semantic colour-tier keywords (from
  `machine_after_rings_helpers.cljc/ring-color`) → tokens-table
  keywords. Centralised here so the SVG primitive stays decoupled
  from the helpers ns."
  {:green :green
   :amber :yellow
   :red   :red
   :gray  :text-tertiary})

(defn countdown-ring
  "Render a single `:after`-timer countdown ring around `(cx, cy)`
  with radius `r`. Pure fn — produces hiccup.

  Options:

    :cx           — node centre x  (required)
    :cy           — node centre y  (required)
    :r            — ring radius    (required)
    :fraction     — 0.0..1.0; portion of the ring to FILL (= the
                    portion of countdown REMAINING). nil renders the
                    ring as a faded full circle (degenerate cases:
                    no resolvable duration, sub-vec mid-resolution).
    :color        — semantic tier `:green / :amber / :red / :gray`
                    (defaults to `:gray`).
    :cancelled?   — when true the ring renders gray + a diagonal
                    cross-line through it (the timer was cancelled
                    by state exit or sub-resolution).
    :stroke-width — defaults to 2.5 (chunky enough to read at chart-
                    scale, thin enough to not obscure the node).
    :testid       — overrides the root data-testid (used by the panel
                    to stamp `(machine-id, state)` for hit-testing).
    :tooltip      — when present, wraps a native SVG `<title>` so a
                    hover shows browser-default chrome.

  Returns a hiccup `[:g ...]` form. The outer `<g>` carries
  `:pointer-events :all` and is sized to the bounding box of the
  circle so the ring is hover-targetable across its full sweep.

  ## Why a single SVG primitive (not three sub-components)

  Both the live ring + the cancelled ring + the degenerate full-circle
  ring share the same outer geometry — only the dasharray + stroke
  colour + the cross-overlay change. Folding the three states into one
  fn keeps the data → hiccup mapping trivial for tests."
  [{:keys [cx cy r fraction color cancelled? stroke-width testid tooltip]
    :or   {color        :gray
           stroke-width 2.5}}]
  (let [sw        stroke-width
        circ      (* 2 Math/PI r)
        ;; Clamp the fraction inside `[0, 1]`. Nil → full ring (the
        ;; degenerate-data fallback already renders gray via colour).
        f         (cond
                    (nil? fraction)        1.0
                    (< fraction 0.0)       0.0
                    (> fraction 1.0)       1.0
                    :else                  (double fraction))
        arc-len   (* f circ)
        gap-len   (- circ arc-len)
        token-key (get ring-color->token color :text-tertiary)
        stroke    (get tokens/tokens token-key)
        opacity   (if cancelled? 0.4 0.85)]
    [:g {:data-testid    (or testid "rf-causa-chart-countdown-ring")
         :data-color     (name color)
         :data-cancelled (str (boolean cancelled?))
         :data-fraction  (when fraction (str fraction))
         :pointer-events "all"}
     ;; Underlay: a faint full circle so the user reads the "track"
     ;; the ring sweeps along. Without it the partial ring looks like
     ;; an arc fragment.
     [:circle {:cx cx :cy cy :r r
               :fill "none"
               :stroke (:border-subtle tokens/tokens)
               :stroke-width (* 0.6 sw)
               :opacity 0.4
               :pointer-events "none"}]
     ;; The countdown sweep itself. Rotate -90° around (cx, cy) so
     ;; the dasharray origin sits at 12 o'clock.
     [:circle {:cx cx :cy cy :r r
               :fill "none"
               :stroke stroke
               :stroke-width sw
               :stroke-linecap "round"
               :stroke-dasharray (str arc-len " " gap-len)
               :stroke-dashoffset 0
               :opacity opacity
               :transform (str "rotate(-90 " cx " " cy ")")
               :pointer-events "stroke"}]
     ;; Cancelled-ring diagonal cross — a single line at 45° through
     ;; the circle's bounding box. Renders ABOVE the ring so it reads
     ;; as a kill-mark.
     (when cancelled?
       (let [diag (long (* 0.707 r))]   ;; r / sqrt(2)
         [:line {:x1 (- cx diag) :y1 (- cy diag)
                 :x2 (+ cx diag) :y2 (+ cy diag)
                 :stroke (:red tokens/tokens)
                 :stroke-width (* 0.8 sw)
                 :stroke-linecap "round"
                 :opacity 0.7
                 :pointer-events "none"}]))
     ;; Tooltip (native SVG `<title>`) — zero-cost accessible chrome
     ;; that the browser surfaces on hover; no JS handlers required.
     (when tooltip
       [:title tooltip])]))

(defn sparkline
  "Inline SVG glyph for `samples` — a vector of non-negative integers,
  oldest-first. Used by the Mode C cluster view to show recent
  state-change rate.

  Options:

    :width   (default 60)   — viewport width in px
    :height  (default 16)   — viewport height in px
    :stroke              — line colour (default `:cyan` token)
    :testid              — overrides the root data-testid
    :max-sample          — pin the y-axis ceiling (defaults to
                            the data's `max`; useful when comparing
                            multiple sparklines on the same scale)

  Returns a hiccup `[:svg ...]` form. Pure fn."
  ([samples] (sparkline samples {}))
  ([samples {:keys [width height stroke testid max-sample]
             :or   {width  60
                    height 16
                    stroke (:cyan tokens/tokens)
                    testid "rf-causa-chart-sparkline"}}]
   (let [n        (count samples)
         max-v    (or max-sample
                      (when (seq samples)
                        (apply max samples))
                      0)
         pad-y    1
         inner-h  (max 1 (- height (* 2 pad-y)))
         baseline (- height pad-y)
         ;; When n < 2 we can't draw a polyline; render an empty SVG
         ;; with the baseline so callers still get a stable visual lane.
         points   (when (>= n 2)
                    (mapv (fn [i v]
                            (let [x (if (= n 1)
                                      (quot width 2)
                                      (long (* (/ (double i) (double (- n 1)))
                                               width)))
                                  ratio (if (pos? max-v)
                                          (/ (double v) (double max-v))
                                          0.0)
                                  y (long (- baseline
                                             (* ratio inner-h)))]
                              [x y]))
                          (range n)
                          samples))]
     [:svg {:data-testid testid
            :data-samples (pr-str samples)
            :width  width
            :height height
            :viewBox (str "0 0 " width " " height)
            :style {:display "inline-block"
                    :vertical-align "middle"}}
      ;; Baseline so the lane is visible even on empty / all-zero data.
      [:line {:x1 0 :y1 baseline :x2 width :y2 baseline
              :stroke (:border-subtle tokens/tokens)
              :stroke-width 0.5}]
      (when points
        [:polyline {:fill "none"
                    :stroke stroke
                    :stroke-width 1.25
                    :stroke-linecap "round"
                    :stroke-linejoin "round"
                    :points (str/join
                              " "
                              (map (fn [[x y]] (str x "," y)) points))}])])))
