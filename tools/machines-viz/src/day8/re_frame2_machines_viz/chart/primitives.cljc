(ns day8.re-frame2-machines-viz.chart.primitives
  "Reusable SVG glyph primitives — `countdown-ring` and `sparkline`.

  rf2-gpzb4 (2026-05-21) — relocated from the now-deleted
  `chart/svg.cljc` in the xyflow migration. The xyflow chart owns
  node + edge rendering; these primitives remain because they are
  consumed OUTSIDE the chart canvas:

    - `countdown-ring` — Causa's `panels/machine_after_rings.cljs`
      overlay paints rings ON TOP of the chart for the focused
      `:after`-timer. The overlay's positioning logic is host-side
      (it walks the chart's DOM to find node bboxes), but the ring
      glyph itself is pure-data hiccup so the JVM test corpus can
      still pin its shape.
    - `sparkline` — used by Causa's cluster-row state-change rate
      indicator and various Story / Causa stats surfaces. Pure
      hiccup, JVM-runnable.

  Both fns produce hiccup forms; substrate-agnostic; JVM-testable.

  Per `tools/machines-viz/spec/000-Vision.md` §Decision trace
  §Interactive renderer (2026-05-21 xyflow override)."
  (:require [clojure.string :as str]
            [day8.re-frame2-machines-viz.theme.tokens :as tokens]))

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
        ;; rf2-uv1on — resolve through `var(--rf-causa-<key>, <hex>)`
        ;; so light + dark themes both flow through the host's CSS
        ;; custom-property surface (the xyflow overlay paints from the
        ;; same palette the chart + host do). Falls back to the dark-
        ;; palette hex for standalone embeds + the JVM hiccup tests.
        stroke    (tokens/css-var token-key)
        opacity   (if cancelled? 0.4 0.85)]
    [:g {:data-testid    (or testid "rf-mv-chart-countdown-ring")
         :data-color     (name color)
         :data-cancelled (str (boolean cancelled?))
         :data-fraction  (when fraction (str fraction))
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

;; ---- sparkline ----------------------------------------------------------

(defn sparkline
  "Inline SVG glyph for `samples` — a vector of non-negative integers,
  oldest-first.

  Options:

    :width   (default 60)
    :height  (default 16)
    :stroke              — line colour (default `:cyan` token)
    :testid              — overrides the root data-testid
    :max-sample          — pin the y-axis ceiling
    :label               — a11y. Overrides the default `aria-label`.
                           Hosts that render the sparkline next to a
                           textual count MAY pass empty string to
                           suppress the announcement; otherwise the
                           default label reads as
                           `\"Sparkline of N samples, peak P.\"`"
  ([samples] (sparkline samples {}))
  ([samples {:keys [width height stroke testid max-sample label]
             :or   {width  60
                    height 16
                    stroke (:cyan tokens/tokens)
                    testid "rf-mv-chart-sparkline"}}]
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
                          samples))
         aria-label (cond
                      (= "" label)  nil
                      (some? label) label
                      (zero? n)     "Sparkline (no samples)."
                      :else         (str "Sparkline of " n " "
                                         (if (= 1 n) "sample" "samples")
                                         ", peak " max-v "."))]
     [:svg (cond-> {:data-testid testid
                    :data-samples (pr-str samples)
                    :width  width
                    :height height
                    :viewBox (str "0 0 " width " " height)
                    :style {:display "inline-block"
                            :vertical-align "middle"}}
             aria-label       (assoc :role "img"
                                     :aria-label aria-label)
             (nil? aria-label) (assoc :aria-hidden "true"))
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
