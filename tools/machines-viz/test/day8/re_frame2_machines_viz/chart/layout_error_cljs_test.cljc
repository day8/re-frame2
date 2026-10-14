(ns day8.re-frame2-machines-viz.chart.layout-error-cljs-test
  "The data an ELK failure hands the chart's banner and the error trace."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-machines-viz.chart.layout-error :as layout-error]))

(deftest input-summary-counts-the-graph-and-keeps-option-values-off-the-bus
  (testing "counts + direction + the SORTED option key-set; option values never
            ride the summary"
    (is (= {:node-count 3 :edge-count 1 :region-count 1 :parallel? true
            :direction :lr :layout-option-ks ["elk.algorithm" "elk.direction"]}
           (layout-error/input-summary
             {:nodes [{:id :audio :region? true} {:id :on} {:id :off}]
              :edges [{:id :a}]
              :parallel? true}
             :lr
             {"elk.direction" "DOWN" "elk.algorithm" "layered"})))))

(deftest error->data-adapts-every-thrown-value
  (testing "nil and a string each become trace data"
    (is (= {:message "unknown error"} (layout-error/error->data nil)))
    (is (= {:message "elk: not a graph"} (layout-error/error->data "elk: not a graph")))))
