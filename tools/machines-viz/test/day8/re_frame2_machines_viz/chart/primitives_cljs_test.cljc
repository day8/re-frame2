(ns day8.re-frame2-machines-viz.chart.primitives-cljs-test
  "Shape + theming pins for the chart `countdown-ring` glyph
  (rf2-uv1on · xyflow Phase 2).

  The `:after`-timer countdown ring is the load-bearing primitive the
  xyflow after-rings overlay paints (per
  `chart.overlays.after-rings`). These tests pin:

    - the hiccup structure (track circle + arc circle, optional
      cancelled cross-line + tooltip),
    - the `stroke-dasharray` arc maths (fraction → filled arc length),
    - the colour-tier → token mapping, and
    - the rf2-uv1on `var(--rf-causa-<key>, <hex>)` theming so light +
      dark both flow through the host's CSS custom-property surface.

  Dual-target via the `_cljs_test.cljc` extension. Pure hiccup —
  JVM-runnable, no DOM."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.chart.primitives :as prim]))

(defn- circles
  "All `:circle` elements in a hiccup tree."
  [tree]
  (filter (fn [n] (and (vector? n) (= :circle (first n))))
          (tree-seq (some-fn vector? seq?) seq tree)))

(defn- find-tag
  [tree tag]
  (some (fn [n] (when (and (vector? n) (= tag (first n))) n))
        (tree-seq (some-fn vector? seq?) seq tree)))

;; ---- structure ----------------------------------------------------------

(deftest countdown-ring-emits-track-and-arc-circles
  (let [g (prim/countdown-ring {:cx 100 :cy 100 :r 40 :fraction 0.5
                                :color :green})]
    (is (= :g (first g)))
    (is (= "rf-mv-chart-countdown-ring" (:data-testid (second g))))
    (is (= "green" (:data-color (second g))))
    (is (= 2 (count (circles g))) "a faded track circle + the arc circle")))

(deftest countdown-ring-testid-override
  (let [g (prim/countdown-ring {:cx 0 :cy 0 :r 10 :fraction 1.0
                                :testid "rf-mv-chart-after-ring-idle"})]
    (is (= "rf-mv-chart-after-ring-idle" (:data-testid (second g))))))

;; ---- arc maths ----------------------------------------------------------

(deftest countdown-ring-dasharray-tracks-fraction
  (let [r       40
        circ    (* 2 Math/PI r)
        arc-of  (fn [frac]
                  (let [g    (prim/countdown-ring
                               {:cx 0 :cy 0 :r r :fraction frac})
                        ;; the arc circle is the SECOND circle (track is first)
                        arc  (second (circles g))
                        dash (:stroke-dasharray (second arc))
                        [a _] (str/split dash #" ")]
                    (#?(:clj Double/parseDouble :cljs js/parseFloat) a)))]
    (is (< (Math/abs (- (arc-of 0.5) (* 0.5 circ))) 0.001)
        "half fraction → half the circumference filled")
    (is (< (Math/abs (- (arc-of 1.0) circ)) 0.001)
        "full fraction → whole circumference filled")
    (is (< (arc-of 0.0) 0.001) "zero fraction → no arc")))

(deftest countdown-ring-nil-fraction-renders-full
  ;; nil fraction (unresolvable duration) → a faded full ring.
  (let [g   (prim/countdown-ring {:cx 0 :cy 0 :r 40 :fraction nil})
        arc (second (circles g))
        dash (:stroke-dasharray (second arc))
        [a _] (str/split dash #" ")]
    (is (< (Math/abs (- (#?(:clj Double/parseDouble :cljs js/parseFloat) a)
                        (* 2 Math/PI 40)))
           0.001))))

;; ---- cancelled ----------------------------------------------------------

(deftest countdown-ring-cancelled-draws-cross-line
  (let [g    (prim/countdown-ring {:cx 100 :cy 100 :r 40 :fraction 0.3
                                   :cancelled? true})
        line (find-tag g :line)]
    (is (= "true" (:data-cancelled (second g))))
    (is (some? line) "cancelled rings draw a diagonal cross-line")))

(deftest countdown-ring-not-cancelled-has-no-line
  (let [g (prim/countdown-ring {:cx 0 :cy 0 :r 40 :fraction 0.3})]
    (is (nil? (find-tag g :line)))))

;; ---- tooltip ------------------------------------------------------------

(deftest countdown-ring-tooltip-emits-title
  (let [g (prim/countdown-ring {:cx 0 :cy 0 :r 10 :fraction 0.5
                                :tooltip "idle · 2500ms remaining"})]
    (is (= [:title "idle · 2500ms remaining"] (find-tag g :title)))))

;; ---- var(--*) theming (rf2-uv1on) --------------------------------------

(deftest countdown-ring-strokes-resolve-through-css-vars
  (testing "the arc stroke is var(--rf-causa-<tier-token>, <hex>) so
            light + dark flow through the host's CSS custom-property
            surface (bead requirement)"
    (let [g       (prim/countdown-ring {:cx 0 :cy 0 :r 40 :fraction 0.8
                                        :color :green})
          [track arc] (circles g)]
      ;; :green tier → :green token.
      (is (str/starts-with? (:stroke (second arc)) "var(--rf-causa-green"))
      (is (str/includes? (:stroke (second arc)) "#4ADE80")
          "carries the dark-palette hex fallback for standalone embeds")
      ;; track circle uses the subtle border token.
      (is (str/starts-with? (:stroke (second track))
                            "var(--rf-causa-border-subtle")))))

(deftest countdown-ring-cancelled-cross-uses-red-var
  (let [g    (prim/countdown-ring {:cx 0 :cy 0 :r 40 :fraction 0.2
                                   :cancelled? true})
        line (find-tag g :line)]
    (is (str/starts-with? (:stroke (second line)) "var(--rf-causa-red"))))

(deftest countdown-ring-color-tiers-map-to-tokens
  (is (str/includes? (-> (prim/countdown-ring {:cx 0 :cy 0 :r 9 :fraction 1
                                               :color :amber})
                         circles second second :stroke)
                     "--rf-causa-yellow")
      ":amber tier maps to the :yellow token")
  (is (str/includes? (-> (prim/countdown-ring {:cx 0 :cy 0 :r 9 :fraction 0
                                               :color :red})
                         circles second second :stroke)
                     "--rf-causa-red"))
  (is (str/includes? (-> (prim/countdown-ring {:cx 0 :cy 0 :r 9 :fraction 1
                                               :color :gray})
                         circles second second :stroke)
                     "--rf-causa-text-tertiary")
      ":gray tier maps to the :text-tertiary token"))
