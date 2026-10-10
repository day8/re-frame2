(ns day8.re-frame2-machines-viz.chart.primitives
  "Reusable SVG glyph primitives — `countdown-ring`.

  The xyflow chart owns node + edge rendering; this primitive lives
  here because it is consumed OUTSIDE the chart canvas: the
  `chart.overlays.after-rings` overlay (which Xray's
  `panels/machine_after_rings.cljs` mounts) paints rings ON TOP of the
  chart for armed `:after` timers. The overlay walks the chart's DOM to
  find node bboxes, but the ring glyph itself is pure-data hiccup so the
  JVM test corpus can pin its shape. Substrate-agnostic; JVM-testable.

  Per `tools/machines-viz/spec/000-Vision.md` §Decision trace
  §Interactive renderer."
  (:require [day8.re-frame2-machines-viz.theme.tokens :as tokens]))

;; ---- countdown ring -----------------------------------------------------

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
    :color        — semantic tier `:green / :amber / :red / :gray`; an
                    absent or unknown tier strokes as `:gray`.
    :cancelled?   — when true the ring keeps its tier colour, fades to
                    0.4 opacity and gains a red diagonal cross-line.
    :stroke-width — defaults to 2.5.
    :testid       — the root data-testid.
    :tooltip      — wraps a native SVG `<title>`."
  [{:keys [cx cy r fraction color cancelled? stroke-width testid tooltip]
    :or   {stroke-width 2.5}}]
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
        ;; Resolve through `var(--rf-xray-<key>, <hex>)` so light + dark
        ;; themes both flow through the host's CSS custom-property surface
        ;; (the xyflow overlay paints from the same palette the chart +
        ;; host do). Falls back to the dark-palette hex for standalone
        ;; embeds + the JVM hiccup tests.
        stroke    (tokens/css-var token-key)
        opacity   (if cancelled? 0.4 0.85)]
    [:g {:data-testid    testid
         :pointer-events "all"}
     [:circle {:cx cx :cy cy :r r
               :fill "none"
               :stroke (tokens/css-var :border-subtle)
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
                 :stroke (tokens/css-var :red)
                 :stroke-width (* 0.8 sw)
                 :stroke-linecap "round"
                 :opacity 0.7
                 :pointer-events "none"}]))
     (when tooltip
       [:title tooltip])]))
