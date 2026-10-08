(ns day8.re-frame2-xray.chart.timing-waterfall-cljs-test
  "Pure-data tests for the wire-timing waterfall primitive."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test    :refer-macros [deftest is]])
            [day8.re-frame2-xray.chart.timing-waterfall :as wf]))

(deftest normalise-phases-projects-width-and-offset-per-phase
  (doseq [[wire rows]
          [;; no :total-ms — the sum of the durations is the total
           [{:phases [[:dns 50] [:connect 50]]}
            [[:dns 0.5 0.0] [:connect 0.5 0.5]]]
           [{:phases [[:a 50] [:b 50]] :total-ms 200}
            [[:a 0.25 0.0] [:b 0.25 0.25]]]
           ;; a phase longer than the total clamps to 100%
           [{:phases [[:x 9999]] :total-ms 100}
            [[:x 1.0 0.0]]]
           ;; the offset is the running sum of every preceding width
           [{:phases [[:a 25] [:b 25] [:c 50]] :total-ms 100}
            [[:a 0.25 0.0] [:b 0.25 0.25] [:c 0.5 0.5]]]
           ;; negative and non-numeric durations drop; a zero-length phase
           ;; stays, because managed-fx emits [[:issued 0] [:elapsed N]]
           [{:phases [[:a 100] [:b 0] [:c -10] [:d "x"]] :total-ms 100}
            [[:a 1.0 0.0] [:b 0.0 1.0]]]]]
    (is (= rows (mapv (juxt :phase :width-pct :offset-pct)
                      (wf/normalise-phases wire)))
        (pr-str wire))))

(deftest slowest-phase-picks-largest-positive-duration
  (doseq [[wire slowest]
          [[{:phases [[:dns 2] [:connect 15] [:ttfb 180] [:download 30]]} :ttfb]
           ;; a zero-length phase is never the slowest, though
           ;; normalise-phases keeps it as a row
           [{:phases [[:issued 0]]} nil]
           [{} nil]]]
    (is (= slowest (wf/slowest-phase wire)) (pr-str wire))))

(deftest render-returns-nil-for-empty
  (is (nil? (wf/render {}))))

(deftest render-returns-svg-hiccup
  (let [[tag attrs :as out] (wf/render {:phases   [[:dns 2] [:connect 15] [:ttfb 180]]
                                        :total-ms 200})]
    (is (vector? out))
    (is (= [:svg "3"] [tag (:data-row-count attrs)]))))

(deftest render-overrides-testid
  (is (= "custom-id"
         (-> (wf/render {:phases [[:a 10]]} {:testid "custom-id"}) second :data-testid))))
