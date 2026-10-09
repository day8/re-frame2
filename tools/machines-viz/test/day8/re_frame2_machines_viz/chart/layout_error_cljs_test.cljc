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
  (testing "nil, a string and a native error each become trace data; a native
            error carries its class name and never its stack"
    (is (= {:message "unknown error"} (layout-error/error->data nil)))
    (is (= {:message "elk: not a graph"} (layout-error/error->data "elk: not a graph")))
    (is (= {:message "boom" :name #?(:clj "java.lang.RuntimeException" :cljs "Error")}
           (layout-error/error->data #?(:clj  (RuntimeException. "boom")
                                        :cljs (js/Error. "boom")))))))

(deftest layout-error-result-shape
  (testing "empty positions/routes/labels plus the :layout-error slot the banner
            reads: a truthy map, so the chart's `(when result …)` commit still runs"
    (is (= {:positions    {}
            :edge-points  {}
            :edge-labels  {}
            :layout-error {:error         {:message "bad input"}
                           :input-summary {:node-count 1 :edge-count 0 :region-count 0
                                           :parallel? false :direction :lr
                                           :layout-option-ks ["elk.algorithm"]}}}
           (layout-error/layout-error-result "bad input" {:nodes [{:id :a}] :edges []}
                                             :lr {"elk.algorithm" "layered"})))))
