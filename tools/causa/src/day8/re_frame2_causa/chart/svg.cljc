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
     [:text {:x (+ x (quot width 2))
             :y (+ y (quot height 2) 4)
             :text-anchor "middle"
             :font-family font-stack
             :font-size 12
             :font-weight (if active? 600 400)
             :fill (if active?
                     (:text-primary tokens/tokens)
                     (:text-secondary tokens/tokens))}
      label]
     ;; Final-state check glyph
     (when final?
       [:text {:x (+ x width -10)
               :y (+ y 14)
               :font-family font-stack
               :font-size 11
               :fill (:green tokens/tokens)}
        "✓"])]))

(defn- path-from-points
  "Render an SVG path `d` attribute from a point list. Straight lines
  for 2 points, cubic bezier for 4."
  [points]
  (case (count points)
    2 (let [[[x1 y1] [x2 y2]] points]
        (str "M " x1 " " y1 " L " x2 " " y2))
    4 (let [[[x1 y1] [cx1 cy1] [cx2 cy2] [x2 y2]] points]
        (str "M " x1 " " y1
             " C " cx1 " " cy1
             " "  cx2 " " cy2
             " "  x2  " " y2))
    ""))

(defn- edge-label-position
  "Mid-point of the edge for label placement. For self-loops, place
  the label to the right of the control points."
  [points]
  (if (= 4 (count points))
    (let [[_ [cx _] _ _] points] [(+ cx 30) (second (second points))])
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
        ;; Background pill for legibility
        [:rect {:x (- lx (* 4 (count full-label)))
                :y (- ly 9)
                :width (* 8 (count full-label))
                :height 14
                :rx 3
                :fill (:bg-2 tokens/tokens)
                :opacity 0.85}]
        [:text {:x lx :y (+ ly 1)
                :text-anchor "middle"
                :font-family font-stack
                :font-size 10
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
                              nodes)]
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
