(ns day8.re-frame2-machines-viz.chart.overlays.after-rings-geometry-cljs-test
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test    :refer-macros [deftest is]])
            [day8.re-frame2-machines-viz.chart.overlays.after-rings-geometry
             :as geo]))

(deftest ring-radius-half-longer-side-plus-gap
  (is (= 76.0 (geo/ring-radius {:width 140 :height 48})) "half of 140, plus the 6px gap")
  (is (= 106.0 (geo/ring-radius {:width 48 :height 200})) "portrait: height is the longer side")
  (is (= (double geo/min-ring-radius-px) (geo/ring-radius {:width 4 :height 4}))
      "a tiny node still draws a visible ring"))

(deftest overlay-rings-positions-measured-specs-in-container-coords
  (let [specs [{:node-id "idle"  :color :green :fraction 0.8}
               {:node-id "flat"  :color :red}    ;; zero width: not laid out yet
               {:node-id "ghost" :color :red}]   ;; no measured rect
        rects {"idle" {:left 100 :top 100 :width 140 :height 48}
               "flat" {:left 0   :top 0   :width 0   :height 48}}]
    (is (= [{:node-id "idle" :color :green :fraction 0.8 :cx 150.0 :cy 114.0 :r 76.0}]
           (geo/overlay-rings specs rects {:left 20 :top 10 :width 900 :height 400}))
        "the node centre minus the container origin, with the presentation payload kept")))
