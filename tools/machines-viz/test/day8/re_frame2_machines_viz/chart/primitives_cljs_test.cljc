(ns day8.re-frame2-machines-viz.chart.primitives-cljs-test
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.chart.primitives :as prim]
            [day8.re-frame2-machines-viz.theme.tokens :as tokens]))

(defn- circles
  "All `:circle` elements in a hiccup tree."
  [tree]
  (filter (fn [n] (and (vector? n) (= :circle (first n))))
          (tree-seq (some-fn vector? seq?) seq tree)))

(defn- find-tag
  [tree tag]
  (some (fn [n] (when (and (vector? n) (= tag (first n))) n))
        (tree-seq (some-fn vector? seq?) seq tree)))

(deftest countdown-ring-dasharray-tracks-fraction
  (testing "the arc (the second circle; the track is first) fills the remaining
            fraction of the circumference; a nil fraction renders a full ring"
    (let [r    40
          circ (* 2 Math/PI r)]
      (doseq [[fraction filled] [[0.0 0] [0.5 0.5] [1.0 1] [nil 1]]]
        (let [dash (-> (prim/countdown-ring {:cx 0 :cy 0 :r r :fraction fraction})
                       circles second second :stroke-dasharray)
              arc  (#?(:clj Double/parseDouble :cljs js/parseFloat)
                     (first (str/split dash #" ")))]
          (is (< (Math/abs (- arc (* filled circ))) 0.001) (str "fraction " fraction)))))))

(deftest countdown-ring-strokes-resolve-through-css-vars
  (testing "each colour tier strokes the arc through its theme token's CSS var;
            the track uses the subtle border token"
    (doseq [[color token] [[:green :green] [:amber :yellow] [:red :red] [:gray :text-tertiary]]]
      (let [[track arc] (circles (prim/countdown-ring {:cx 0 :cy 0 :r 9 :fraction 1
                                                       :color color}))]
        (is (= [(tokens/css-var :border-subtle) (tokens/css-var token)]
               (map (comp :stroke second) [track arc]))
            (str color))))))

(deftest countdown-ring-cancelled-draws-cross-line
  (is (str/starts-with?
        (-> (prim/countdown-ring {:cx 100 :cy 100 :r 40 :fraction 0.3 :cancelled? true})
            (find-tag :line) second :stroke)
        "var(--rf-xray-red")
      "a cancelled ring is struck through in the red token")
  (is (nil? (find-tag (prim/countdown-ring {:cx 0 :cy 0 :r 40 :fraction 0.3 :cancelled? false})
                      :line))))

(deftest countdown-ring-carries-testid-and-tooltip
  (let [g (prim/countdown-ring {:cx 0 :cy 0 :r 10 :fraction 0.5
                                :testid  "rf-mv-chart-after-ring-idle"
                                :tooltip "idle · 2500ms remaining"})]
    (is (= "rf-mv-chart-after-ring-idle" (:data-testid (second g))))
    (is (= [:title "idle · 2500ms remaining"] (find-tag g :title)))))
