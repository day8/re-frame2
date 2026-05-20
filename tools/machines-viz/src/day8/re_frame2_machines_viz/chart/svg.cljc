(ns day8.re-frame2-machines-viz.chart.svg
  "Hiccup SVG renderer for the MachineChart (rf2-2tkza Phase 1;
  relocated under rf2-o9arp from `day8.re-frame2-causa.chart.svg` and
  uplifted per the rf2-0yil0 audit cluster).

  Consumes the positioned graph from
  `day8.re-frame2-machines-viz.chart.layout` (or its ELK twin) and
  emits a hiccup `[:svg ...]` form. Pure data → data — JVM-runnable
  so the renderer is unit-testable from `clojure -M:test`.

  ## Visual character (per the rf2-0yil0 audit cluster)

  - **State-space dot-grid background** — rf2-m4nj4. Subtle violet
    dots on a 16px lattice behind every element. Reads as 'state-
    space', distinguishes from Stately/XState's flat canvas.
  - **Heartbeat pulse + transition glow** — rf2-xfx6l. The active /
    landing state breathes (2s sine on stroke-width + opacity); the
    transition edge glows for one beat on event-fire. Both honour
    `prefers-reduced-motion` via the `--rf-causa-motion-scale` seam.
  - **Refused-floor typography** — rf2-cd053. State labels 11px,
    edge labels 9px (spec/007-UX-IA's refused-floor). Node geometry
    widens to fit.
  - **Caption strip** — rf2-3zdzw. Opt-in `:show-caption?` paints a
    machine-id + current-state header above the chart so PNG/SVG
    exports self-identify.
  - **State-tag pills** — rf2-m1b88. Coloured pills above each node
    label when the state has `:tags`. Deterministic per-tag colour.
  - **Tokenised tints** — rf2-pyvmr. Every colour resolves through
    `theme/tokens` (palette key + alpha helper); zero rgba literals.
  - **Visual constants in one map** — rf2-g6cig. Corner radius,
    paddings, font sizes, durations all live in
    `visual_constants/chart`. Compound title in sans-stack so
    container chrome reads as chrome, not data.
  - **Sans-stack empty-state** — rf2-trorn. The 'machine has no
    states' fallback resolves through `tokens/sans-stack`, not a
    hardcoded `'Inter, ...'` literal.

  ## Visual grammar (per spec/005 + spec/machines-viz/API.md)

    - **Rounded rectangle nodes** with state label centred.
    - **Live highlight** — cyan border + tint on the active state.
    - **Initial-state marker** — a small filled circle to the left
      of the initial node, with a connecting arrow into the node.
    - **Final-state marker** — doubled border + a small check glyph
      in the corner.
    - **Edges** — straight grey lines with arrow heads; the event
      label sits at the midpoint with a subtle background pill.
    - **Self-loops** — bezier arc to the right of the node.
    - **Tags** — coloured pills above the node label (one per tag)."
  (:require [clojure.string :as str]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.theme.tokens :as tokens]
            [day8.re-frame2-machines-viz.visual-constants :as vc]))

;; ---- visual constants (delegated to vc/chart) ---------------------------

(def ^:private corner-radius           (:corner-radius vc/chart))
(def ^:private stroke-width            (:stroke-width vc/chart))
(def ^:private highlight-stroke-width  (:stroke-width-emphasis vc/chart))
(def ^:private compound-pad-x          (:compound-pad-x vc/chart))
(def ^:private compound-pad-y          (:compound-pad-y vc/chart))
(def ^:private state-label-px          (:state-label-px vc/chart))
(def ^:private edge-label-px           (:edge-label-px vc/chart))
(def ^:private final-glyph-px          (:final-glyph-px vc/chart))
(def ^:private compound-title-px       (:compound-title-px vc/chart))
(def ^:private caption-strip-px        (:caption-strip-px vc/chart))
(def ^:private caption-text-px         (:caption-text-px vc/chart))
(def ^:private tag-pill-height         (:tag-pill-height vc/chart))
(def ^:private tag-pill-pad-x          (:tag-pill-pad-x vc/chart))
(def ^:private tag-pill-px             (:tag-pill-px vc/chart))
(def ^:private tag-pill-gap            (:tag-pill-gap vc/chart))
(def ^:private tag-pill-row-gap        (:tag-pill-row-gap vc/chart))
(def ^:private dot-grid-spacing-px     (:dot-grid-spacing-px vc/chart))
(def ^:private dot-grid-radius-px      (:dot-grid-radius-px vc/chart))
(def ^:private dot-grid-alpha          (:dot-grid-alpha vc/chart))

(def ^:private mono-font-stack
  "Mono stack used for chart node + edge labels — payload surface.
  Mirrors `tokens/mono-stack` but inlined here so the SVG `<text>`
  elements never reach for it via a Var lookup that the JVM-side
  hiccup-walker tests would have to evaluate."
  "ui-monospace, SFMono-Regular, 'JetBrains Mono', Menlo, Consolas, monospace")

;; ---- inline stylesheet (rf2-xfx6l, rf2-g6cig L3) -----------------------

(def ^:private heartbeat-pulse-css
  "@keyframes mv-chart-heartbeat-pulse for the active / landing state.

  rf2-xfx6l — the active state breathes: stroke-width + opacity drift
  from a resting baseline to an emphasised peak on a 2s sine cycle.
  The seam variable `--rf-causa-motion-scale` (mirrored from Causa's
  motion seam) drives the duration through `calc(...)`; the
  `prefers-reduced-motion: reduce` media query below collapses it to
  ~0 so the keyframes resolve to their `to` state in one frame
  (matches Causa's 0.001 trick — see Causa theme/global-styles motion
  docstring)."
  (str
    ":root {\n"
    "  --rf-causa-motion-scale: 1;\n"
    "}\n"
    "@media (prefers-reduced-motion: reduce) {\n"
    "  :root { --rf-causa-motion-scale: 0.001; }\n"
    "}\n"
    "@keyframes mv-chart-heartbeat-pulse {\n"
    "  0%   { opacity: 0.85; stroke-width: var(--mv-pulse-base, 2.5); }\n"
    "  50%  { opacity: 1.00; stroke-width: var(--mv-pulse-peak, 3.5); }\n"
    "  100% { opacity: 0.85; stroke-width: var(--mv-pulse-base, 2.5); }\n"
    "}\n"
    "@keyframes mv-chart-transition-glow {\n"
    "  0%   { opacity: 0.55; stroke-width: var(--mv-glow-base, 2.5); }\n"
    "  20%  { opacity: 1.00; stroke-width: var(--mv-glow-peak, 4.0); }\n"
    "  100% { opacity: 0.85; stroke-width: var(--mv-glow-base, 2.5); }\n"
    "}\n"))

(defn- inline-stylesheet
  "An SVG `<style>` element carrying the chart's `@keyframes` blocks +
  the `prefers-reduced-motion` override. Lives inside the SVG so the
  chart's motion is self-contained: a host that mounts the chart
  without injecting any global CSS still gets the pulse / glow and
  honours the user's reduced-motion preference.

  Pure data → hiccup; JVM-runnable."
  []
  [:style {:data-testid "rf-mv-chart-stylesheet"} heartbeat-pulse-css])

;; ---- helpers ------------------------------------------------------------

(defn- node-fill
  [{:keys [highlight? sim? from-highlight? to-highlight?]}]
  ;; rf2-pyvmr — every tint resolves through `tokens/with-alpha` so a
  ;; palette shift on the underlying token propagates without a
  ;; per-call-site edit.
  (cond
    sim?            (tokens/with-alpha :yellow         0.18)
    to-highlight?   (tokens/with-alpha :cyan           0.22)
    from-highlight? (tokens/with-alpha :accent-violet  0.14)
    highlight?      (tokens/with-alpha :cyan           0.18)
    :else           (:bg-2 tokens/tokens)))

(defn- node-stroke
  [{:keys [highlight? sim? final? from-highlight? to-highlight?]}]
  (cond
    sim?            (:yellow tokens/tokens)
    to-highlight?   (:cyan tokens/tokens)
    from-highlight? (:accent-violet tokens/tokens)
    highlight?      (:cyan tokens/tokens)
    final?          (:green tokens/tokens)
    :else           (:border-default tokens/tokens)))

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
        pad-x compound-pad-x
        pad-y compound-pad-y                ;; extra top padding for the title strip
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
  the visual grouping is self-explanatory.

  rf2-g6cig L4 — title font is `tokens/sans-stack`. The container is
  grouping CHROME, not data; sans reads as 'section heading' where
  mono would read as 'identifier value'."
  [{:keys [x y width height label node-id]}]
  [:g {:data-testid (str "rf-causa-chart-compound-" node-id)
       :data-node-id node-id}
   [:rect {:x x :y y :width width :height height
           :rx 10
           :fill (tokens/with-alpha :accent-violet 0.06)
           :stroke (:accent-violet tokens/tokens)
           :stroke-width 1
           :stroke-dasharray (:compound-stroke-dash vc/chart)
           :pointer-events "none"}]
   ;; Title strip — small label flushed top-left so the parent state's
   ;; name reads as a "section heading" above its children.
   (when label
     [:text {:x (+ x 10)
             :y (+ y 14)
             :font-family tokens/sans-stack
             :font-size compound-title-px
             :font-weight 600
             :fill (:accent-violet tokens/tokens)
             :pointer-events "none"}
      label])])

(defn- dot-grid-pattern
  "rf2-m4nj4 — define an SVG `<pattern>` painting a single tinted dot
  per `dot-grid-spacing` cell. Referenced by a backdrop `<rect>` over
  the entire viewport so the chart canvas reads as 'state-space'
  rather than a flat slide.

  The dot is tinted from `:accent-violet` at `dot-grid-alpha` opacity
  — subtle (≈6%); legibility is preserved on every overlap with
  chart nodes / edges / labels. Pattern lives in `<defs>` so the
  rest of the SVG is unchanged structurally; the backdrop rect is
  painted FIRST in the render order so every chart element sits
  above it."
  []
  [:pattern {:id "rf-mv-chart-dot-grid"
             :data-testid "rf-mv-chart-dot-grid"
             :x 0 :y 0
             :width dot-grid-spacing-px
             :height dot-grid-spacing-px
             :patternUnits "userSpaceOnUse"}
   [:circle {:cx (/ dot-grid-spacing-px 2)
             :cy (/ dot-grid-spacing-px 2)
             :r dot-grid-radius-px
             :fill (tokens/with-alpha :accent-violet dot-grid-alpha)}]])

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

(defn- render-tag-pills
  "rf2-m1b88 — render a horizontal row of state-tag pills ABOVE the
  node label. One pill per tag in `tags`. Deterministic per-tag
  colour via `tokens/tag-pill-color`. Pills carry a native SVG
  `<title>` for hover-tooltip; the parent `<g>` carries a collated
  `<title>` listing every tag.

  Pure data → hiccup. The pill row sits inside the node's `<g>` so
  it transforms with the node (no separate positioning logic).
  Empty / nil `tags` produces nil so the renderer can `(when ...)` it."
  [{:keys [x y width tags]}]
  (let [pills (sort (vec tags))]
    (when (seq pills)
      (let [pill-w (fn [t]
                     (let [s (if (keyword? t) (name t) (str t))]
                       (+ (* 2 tag-pill-pad-x)
                          (max 8 (long (* 5.5 (count s)))))))
            ;; Centre the row above the node — sum widths + gaps,
            ;; then offset by half the row width to the left of
            ;; node-centre.
            widths (mapv pill-w pills)
            total-w (+ (reduce + widths)
                       (* tag-pill-gap (max 0 (dec (count pills)))))
            row-x  (+ x (quot (- width total-w) 2))
            row-y  (- y tag-pill-height tag-pill-row-gap)]
        (into [:g {:data-testid "rf-mv-chart-state-tags"
                   :data-tag-count (count pills)
                   :pointer-events "none"}]
              (map-indexed
                (fn [i tag]
                  (let [w        (nth widths i)
                        prior-w  (reduce + (take i widths))
                        prior-g  (* tag-pill-gap i)
                        px       (+ row-x prior-w prior-g)
                        py       row-y
                        token-k  (tokens/tag-pill-color tag)
                        fill     (tokens/with-alpha token-k 0.18)
                        stroke   (get tokens/tokens token-k)
                        label    (if (keyword? tag) (name tag) (str tag))]
                    [:g {:data-testid (str "rf-mv-chart-state-tag-" label)
                         :data-tag    label}
                     [:rect {:x px :y py
                             :width w :height tag-pill-height
                             :rx (quot tag-pill-height 2)
                             :fill fill
                             :stroke stroke
                             :stroke-width 1}]
                     [:text {:x (+ px (quot w 2))
                             :y (+ py (- tag-pill-height 3))
                             :text-anchor "middle"
                             :font-family tokens/sans-stack
                             :font-size tag-pill-px
                             :font-weight 600
                             :fill stroke}
                      label]
                     [:title (str tag)]]))
                pills))))))

(defn- render-node
  [{:keys [x y width height label final? compound? node-id path]
    :as n}
   {:keys [highlight-id from-highlight-id to-highlight-id sim? on-state-click]}]
  (let [active?         (= node-id highlight-id)
        from-highlight? (= node-id from-highlight-id)
        to-highlight?   (= node-id to-highlight-id)
        ;; rf2-xfx6l — the active / landing state pulses. The FROM
        ;; node does NOT pulse (it's the just-left state); only the
        ;; current state breathes.
        pulse?          (or active? to-highlight?)
        emphasised?     (or active? from-highlight? to-highlight?)
        styled          (assoc n
                               :highlight?      active?
                               :from-highlight? from-highlight?
                               :to-highlight?   to-highlight?
                               :sim?            (and active? sim?))
        fill            (node-fill styled)
        stroke          (node-stroke styled)
        stroke-w        (if emphasised? highlight-stroke-width stroke-width)
        pulse-base      stroke-w
        pulse-peak      (+ stroke-w (:pulse-stroke-width-add vc/chart))
        ;; The rect carries the animation via inline-style so the SVG
        ;; remains self-contained (no global CSS dependency).
        rect-style      (when pulse?
                          {:animation
                           (str "mv-chart-heartbeat-pulse "
                                (tokens/duration-css (:pulse-duration-ms tokens/motion))
                                " ease-in-out infinite")
                           :transform-box "fill-box"
                           :transform-origin "center"})
        rect-extra      (when pulse?
                          {;; expose pulse stroke-width endpoints via
                           ;; CSS vars so the keyframes interpolate
                           ;; them — each pulsing node carries its own
                           ;; base/peak.
                           :style (assoc rect-style
                                    "--mv-pulse-base" (str pulse-base "px")
                                    "--mv-pulse-peak" (str pulse-peak "px"))})]
    [:g {:data-testid (str "rf-causa-chart-node-" node-id)
         :data-active (str active?)
         :data-from-highlight (str from-highlight?)
         :data-to-highlight (str to-highlight?)
         :data-pulse (str pulse?)
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
               :stroke-dasharray (:compound-stroke-dash vc/chart)}])
     ;; Final states get a double-ring
     (when final?
       [:rect {:x (- x 3) :y (- y 3)
               :width (+ width 6) :height (+ height 6)
               :rx (+ corner-radius 1)
               :fill "none"
               :stroke (:green tokens/tokens)
               :stroke-width 1}])
     ;; Main node body — pulse animation applied here when emphasised.
     [:rect (cond-> {:x x :y y :width width :height height
                     :rx corner-radius
                     :fill fill
                     :stroke stroke
                     :stroke-width stroke-w
                     :stroke-dasharray (when (and from-highlight? (not to-highlight?))
                                         "4 2")}
              pulse? (merge rect-extra))]
     ;; State-tag pills (rf2-m1b88)
     (render-tag-pills n)
     ;; Label — rf2-cd053 restores typography to the refused-floor
     ;; (state 11px). Vertical baseline tweak (+4) keeps glyphs
     ;; visually centred at the larger size.
     [:text {:x (+ x (quot width 2))
             :y (+ y (quot height 2) 4)
             :text-anchor "middle"
             :font-family mono-font-stack
             :font-size state-label-px
             :font-weight (if emphasised? 600 400)
             :fill (if emphasised?
                     (:text-primary tokens/tokens)
                     (:text-secondary tokens/tokens))}
      label]
     ;; Final-state check glyph — rf2-cd053 final-glyph-px tracks
     ;; state-label-px so the corner check stays proportional.
     (when final?
       [:text {:x (+ x width -10)
               :y (+ y 15)
               :font-family mono-font-stack
               :font-size final-glyph-px
               :fill (:green tokens/tokens)}
        "✓"])]))

(defn- path-from-points
  "Render an SVG path `d` attribute from a point list.

    - 2 points → straight line.
    - 4 points → cubic bezier (self-loop / hand-routed S-curve).
    - 3 or 5+ points → polyline (ELK orthogonal routing returns
      multi-segment edges via bend points)."
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
  [{:keys [event-label points guard action from-id to-id]
    :as _edge}
   {:keys [highlight-id from-highlight-id to-highlight-id]}]
  (let [active?         (or (= from-id highlight-id)
                            (= to-id   highlight-id))
        focused-edge?   (and (some? from-highlight-id)
                             (some? to-highlight-id)
                             (= from-id from-highlight-id)
                             (= to-id   to-highlight-id))
        emphasised?     (or active? focused-edge?)
        stroke  (if emphasised?
                  (:cyan tokens/tokens)
                  (:border-default tokens/tokens))
        marker  (if emphasised?
                  "url(#rf-causa-chart-arrow-highlight)"
                  "url(#rf-causa-chart-arrow)")
        [lx ly] (edge-label-position points)
        ;; rf2-jeim7 — `:event-label` is now the full xstate label
        ;; `event [guard] / action` built upstream by `layout/edge-label`
        ;; (both layered + ELK paths). Render it as-is; no per-segment
        ;; re-stitching here.
        full-label event-label
        ;; rf2-xfx6l — focused edge glows for one beat on event-fire.
        ;; The animation is `infinite` here so a re-render driven by
        ;; a re-fire restarts the cycle (focused-edge? is per-render
        ;; transient — when the lens moves off the edge, the
        ;; animation vanishes with the focused style).
        path-style (when focused-edge?
                     {:animation
                      (str "mv-chart-transition-glow "
                           (tokens/duration-css (:glow-duration-ms tokens/motion))
                           " ease-out infinite")})
        base-w (if emphasised? highlight-stroke-width stroke-width)]
    [:g {:data-testid (str "rf-causa-chart-edge-" from-id "-to-" to-id)
         :data-event event-label
         :data-guard (when guard
                       (if (keyword? guard) (name guard) (str guard)))
         :data-action (when action
                        (if (keyword? action) (name action) (str action)))
         :data-active (str active?)
         :data-focused-edge (str focused-edge?)}
     [:path (cond-> {:d (path-from-points points)
                     :fill "none"
                     :stroke stroke
                     :stroke-width base-w
                     :marker-end marker}
              focused-edge? (assoc :style (assoc path-style
                                            "--mv-glow-base" (str base-w "px")
                                            "--mv-glow-peak" (str (+ base-w 1.5) "px"))))]
     (when (seq full-label)
       [:g
        ;; rf2-cd053 — edge-label restored to refused-floor 9px. Pill
        ;; geometry scales with the larger glyph.
        [:rect {:x (- lx (long (* 3.5 (count full-label))))
                :y (- ly 8)
                :width (long (* 7 (count full-label)))
                :height 12
                :rx 3
                :fill (:bg-2 tokens/tokens)
                :opacity 0.85}]
        [:text {:x lx :y ly
                :text-anchor "middle"
                :font-family mono-font-stack
                :font-size edge-label-px
                :fill (:text-secondary tokens/tokens)}
         full-label]])]))

(defn- format-state-label
  "rf2-3zdzw / rf2-g6cig — render a path-like state-id with namespace
  qualifiers + dot-joined hierarchy, e.g. `[:auth :login :idle]` →
  `auth.login.idle`. Used in the caption strip. Pure data → string."
  [state]
  (cond
    (nil? state)
    nil

    (keyword? state)
    (if-let [ns (namespace state)]
      (str ns "/" (name state))
      (name state))

    (vector? state)
    (str/join "." (map (fn [s] (if (keyword? s) (name s) (str s))) state))

    :else (str state)))

(defn- render-caption-strip
  "rf2-3zdzw — caption strip at the top of the viewBox. Shows the
  machine-id (mono) + current state (mono-italic) + reached-state
  ratio (sans micro). Sits ABOVE the chart in the viewBox so PNG /
  SVG exports self-identify.

  Pure hiccup. Caller is responsible for shifting the chart body
  down by `caption-strip-px` when this is rendered."
  [{:keys [machine-id current-state reached-count total-count chart-width]}]
  (when (or machine-id current-state)
    [:g {:data-testid "rf-mv-chart-caption-strip"
         :data-machine-id (str machine-id)
         :data-current-state (str current-state)}
     [:rect {:x 0 :y 0
             :width chart-width
             :height caption-strip-px
             :fill (tokens/with-alpha :bg-2 0.6)
             :stroke (:border-subtle tokens/tokens)
             :stroke-width 0.5}]
     ;; machine-id (mono, primary)
     (when machine-id
       [:text {:x 12 :y (- caption-strip-px 10)
               :font-family mono-font-stack
               :font-size caption-text-px
               :font-weight 600
               :fill (:accent-violet tokens/tokens)}
        (format-state-label machine-id)])
     ;; separator + current-state (mono, italic, cyan)
     (when (and machine-id current-state)
       [:text {:x (+ 12
                     ;; rough mono char-width estimate so the cursor
                     ;; lands past the machine-id without measuring
                     ;; the DOM. The strip uses sans-stack for chrome
                     ;; punctuation between the two payload mono
                     ;; slugs.
                     (long (* 7 (count (format-state-label machine-id)))))
               :y (- caption-strip-px 10)
               :font-family tokens/sans-stack
               :font-size caption-text-px
               :fill (:text-tertiary tokens/tokens)}
        " · current "])
     (when current-state
       [:text {:x (+ 12
                     (long (* 7 (count (format-state-label machine-id))))
                     ;; sans " · current " ~64px at 11px
                     64)
               :y (- caption-strip-px 10)
               :font-family mono-font-stack
               :font-size caption-text-px
               :font-style "italic"
               :fill (:cyan tokens/tokens)}
        (format-state-label current-state)])
     ;; reached/total — right-aligned (sans micro)
     (when (and (integer? reached-count) (integer? total-count))
       [:text {:x (- chart-width 12) :y (- caption-strip-px 10)
               :font-family tokens/sans-stack
               :font-size caption-text-px
               :text-anchor "end"
               :fill (:text-tertiary tokens/tokens)}
        (str reached-count "/" total-count " states reached")])]))

;; ---- public entry -------------------------------------------------------

(defn render
  "Render a positioned graph (from `layout/layout`) as a hiccup SVG.

  Options:

    :highlight-id        — node-id to render as the active state
                           (cyan when `:sim?` is false, amber when
                           true)
    :from-highlight-id   — node-id for the focused-event lens's
                           origin state (rf2-a9cke). Dashed accent-
                           violet border.
    :to-highlight-id     — node-id for the focused-event lens's
                           landing state (rf2-a9cke). Bold cyan
                           border; wins over `:highlight-id`. Pulse
                           target.
    :sim?                — flips the highlight palette to amber.
    :on-state-click      — `(fn [path] ...)` called when a node is
                           clicked.
    :testid              — overrides the root SVG data-testid.
    :show-caption?       — rf2-3zdzw. When true (default false) a
                           caption strip paints at the top of the
                           viewBox with machine-id + current state.
    :caption             — `{:machine-id ... :current-state ...
                           :reached-count N :total-count M}` map
                           driving the caption strip when
                           `:show-caption?` is true.
    :viewport-transform  — rf2-y3l8z. Optional `{:scale s :tx tx :ty ty}`
                           map (or `nil`) carrying the user's zoom +
                           pan state. Applied to the chart body via
                           an outer `<g transform=\"translate(tx ty)
                           scale(s)\">` wrap. Edges, nodes, compounds
                           and the dot-grid pattern all sit inside
                           the wrap so the entire canvas scales as
                           one. When nil / absent the chart renders
                           at 1:1 (back-compat — identical output to
                           pre-y3l8z callers, asserted by tests).
    :svg-attrs           — rf2-y3l8z. Optional map of extra hiccup
                           attrs merged onto the root `<svg>` (e.g.
                           `:on-wheel`, `:on-mouse-down`, `tabIndex`,
                           inline `:style` overrides). Lets the
                           interactive controls wrap the chart with
                           wheel-zoom / click-drag-pan / keyboard
                           handlers without forking `render`.

  Returns a stable hiccup form. Empty graphs (no nodes) render an
  inline note so the panel never sees an empty SVG container."
  ([positioned] (render positioned {}))
  ([{:keys [nodes edges width height] :as positioned}
    {:keys [highlight-id from-highlight-id to-highlight-id
            sim? on-state-click testid show-caption? caption
            viewport-transform svg-attrs]}]
   (if (empty? nodes)
     [:div {:data-testid (or testid "rf-causa-chart-empty")
            :style {:padding "16px"
                    ;; rf2-trorn — sans-stack token rather than the
                    ;; previous `'Inter, system-ui, ...'` literal.
                    :font-family tokens/sans-stack
                    :font-size "12px"
                    :color (:text-tertiary tokens/tokens)}}
      "Machine has no states to render."]
     (let [initial-node (some (fn [n]
                                (when (and (:initial? n)
                                           (= 0 (:depth n))) n))
                              nodes)
           containers (compound-containers nodes)
           ;; rf2-3zdzw — when the caption strip is rendered the body
           ;; shifts down by `caption-strip-px`. Chart geometry
           ;; (nodes, edges) is unchanged; we apply a group translate.
           caption-h    (if show-caption? caption-strip-px 0)
           total-h      (+ height caption-h)
           ;; rf2-y3l8z — viewport-transform carries the user's zoom +
           ;; pan state. Applied to a chart-content wrapper that holds
           ;; the dot-grid backdrop + the chart body (caption strip
           ;; stays unscaled — it's chrome).
           {:keys [scale tx ty]
            :or   {scale 1 tx 0 ty 0}}
           (or viewport-transform {})
           ;; `transform="translate(tx,ty) scale(s)"` — order matters:
           ;; we want pan to read as 'shift the world by (tx,ty)' and
           ;; zoom to read as 'scale the world about the pan-shifted
           ;; origin'. The standard reading on SVG is the
           ;; right-to-left function composition — scale applies first,
           ;; then translate — so `translate(...) scale(...)` is
           ;; canonical for fit-to-viewport math (`tx`,`ty` are the
           ;; post-scale viewport offset).
           transform-applied? (or (not= 1 scale) (not= 0 tx) (not= 0 ty))
           transform-attr (when transform-applied?
                            (str "translate(" tx "," ty ")"
                                 " scale(" scale ")"))
           svg-base-attrs {:data-testid (or testid "rf-causa-chart-svg")
                           :data-highlight-id (or highlight-id "")
                           :data-from-highlight-id (or from-highlight-id "")
                           :data-to-highlight-id (or to-highlight-id "")
                           :data-has-caption (str (boolean show-caption?))
                           :data-viewport-scale (str scale)
                           :data-viewport-tx (str tx)
                           :data-viewport-ty (str ty)
                           :viewBox (str "0 0 " width " " total-h)
                           :width "100%"
                           :preserveAspectRatio "xMidYMin meet"
                           :style {:background (:bg-1 tokens/tokens)
                                   :border-radius "4px"
                                   :max-height "100%"
                                   :display "block"}}
           svg-attrs-merged (if (map? svg-attrs)
                              (let [{extra-style :style :as rest-attrs}
                                    svg-attrs]
                                (-> (merge svg-base-attrs
                                           (dissoc rest-attrs :style))
                                    (update :style merge (or extra-style {}))))
                              svg-base-attrs)]
       [:svg svg-attrs-merged
        ;; ---- inline keyframes (rf2-xfx6l) + reduced-motion seam ----
        (inline-stylesheet)
        ;; ---- arrow markers + dot-grid pattern ----
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
          [:path {:d "M 0 0 L 10 5 L 0 10 z"}]]
         (dot-grid-pattern)]
        ;; ---- caption strip (rf2-3zdzw, opt-in) — chrome, stays
        ;; OUTSIDE the viewport-transform wrap so the machine-id slug
        ;; reads at a fixed size regardless of canvas zoom level.
        (when show-caption?
          (render-caption-strip
            (assoc caption :chart-width width)))
        ;; ---- viewport-transform wrap (rf2-y3l8z) ----
        ;; Holds the dot-grid backdrop + the chart body. When
        ;; `viewport-transform` is nil/absent the `transform` attr is
        ;; omitted entirely so the rendered DOM is byte-identical to
        ;; the pre-y3l8z output — back-compat.
        [:g (cond-> {:data-testid "rf-mv-chart-viewport"}
              transform-attr (assoc :transform transform-attr))
         ;; ---- dot-grid background backdrop (rf2-m4nj4) ----
         [:rect {:data-testid "rf-mv-chart-dot-grid-backdrop"
                 :x 0 :y caption-h
                 :width width :height height
                 :fill "url(#rf-mv-chart-dot-grid)"
                 :pointer-events "none"}]
         ;; ---- chart body, shifted down by caption height ----
         [:g {:data-testid "rf-mv-chart-body"
              :transform (str "translate(0," caption-h ")")}
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
                  (render-edge edge {:highlight-id      highlight-id
                                     :from-highlight-id from-highlight-id
                                     :to-highlight-id   to-highlight-id})))
          (render-initial-marker initial-node)
          (into [:g {:data-testid "rf-causa-chart-nodes"}]
                (for [node nodes]
                  ^{:key (:node-id node)}
                  (render-node node
                               {:highlight-id      highlight-id
                                :from-highlight-id from-highlight-id
                                :to-highlight-id   to-highlight-id
                                :sim?              sim?
                                :on-state-click    on-state-click})))]]]))))

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
;; indicator. Pure data → hiccup; JVM-runnable.

;; ---- countdown ring primitive (rf2-7hwwe) ------------------------------
;;
;; A partial-circle SVG glyph drawn AROUND a chart state-node when an
;; `:after` timer is armed for that state.

(def ^:private ring-color->token
  "Semantic colour-tier keywords → tokens-table keywords."
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
                    cross-line through it.
    :stroke-width — defaults to 2.5.
    :testid       — overrides the root data-testid.
    :tooltip      — wraps a native SVG `<title>`."
  [{:keys [cx cy r fraction color cancelled? stroke-width testid tooltip]
    :or   {color        :gray
           stroke-width 2.5}}]
  (let [sw        stroke-width
        circ      (* 2 Math/PI r)
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
     [:circle {:cx cx :cy cy :r r
               :fill "none"
               :stroke (:border-subtle tokens/tokens)
               :stroke-width (* 0.6 sw)
               :opacity 0.4
               :pointer-events "none"}]
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
     (when cancelled?
       (let [diag (long (* 0.707 r))]
         [:line {:x1 (- cx diag) :y1 (- cy diag)
                 :x2 (+ cx diag) :y2 (+ cy diag)
                 :stroke (:red tokens/tokens)
                 :stroke-width (* 0.8 sw)
                 :stroke-linecap "round"
                 :opacity 0.7
                 :pointer-events "none"}]))
     (when tooltip
       [:title tooltip])]))

(defn sparkline
  "Inline SVG glyph for `samples` — a vector of non-negative integers,
  oldest-first. Used by the Mode C cluster view.

  Options:

    :width   (default 60)
    :height  (default 16)
    :stroke              — line colour (default `:cyan` token)
    :testid              — overrides the root data-testid
    :max-sample          — pin the y-axis ceiling"
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
