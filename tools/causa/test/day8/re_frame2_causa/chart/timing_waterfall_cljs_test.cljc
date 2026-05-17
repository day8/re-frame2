(ns day8.re-frame2-causa.chart.timing-waterfall-cljs-test
  "Pure-data tests for the wire-timing waterfall primitive
  (rf2-uyp86, parent rf2-5aw5v).

  Covers `normalise-phases` (the projection), `slowest-phase` (the
  annotation feed), and the smoke-render hiccup shape."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.chart.timing-waterfall :as wf]))

;; ---- normalise-phases ---------------------------------------------------

(deftest normalise-phases-empty-returns-empty
  (is (= [] (wf/normalise-phases {})))
  (is (= [] (wf/normalise-phases {:phases [] :total-ms 0})))
  (is (= [] (wf/normalise-phases nil))))

(deftest normalise-phases-derives-total-from-sum-when-absent
  (testing "When :total-ms is missing the sum of phase durations is used"
    (let [out (wf/normalise-phases {:phases [[:dns 50] [:connect 50]]})]
      (is (= 2 (count out)))
      (is (= 0.5 (:width-pct (first out))))
      (is (= 0.5 (:width-pct (second out)))))))

(deftest normalise-phases-uses-explicit-total
  (testing "Explicit :total-ms is honoured"
    (let [out (wf/normalise-phases {:phases [[:a 50] [:b 50]] :total-ms 200})]
      (is (= 0.25 (:width-pct (first out))))
      (is (= 0.25 (:width-pct (second out)))))))

(deftest normalise-phases-clamps-width-to-one
  (testing "A phase whose duration exceeds total gets clamped to 100%"
    (let [out (wf/normalise-phases {:phases [[:x 9999]] :total-ms 100})]
      (is (= 1.0 (:width-pct (first out)))))))

(deftest normalise-phases-tracks-offset
  (testing "Each row's offset-pct is the running sum of preceding widths"
    (let [out (wf/normalise-phases {:phases [[:a 25] [:b 25] [:c 50]] :total-ms 100})]
      (is (= 0.0 (:offset-pct (nth out 0))))
      (is (= 0.25 (:offset-pct (nth out 1))))
      (is (= 0.5 (:offset-pct (nth out 2)))))))

(deftest normalise-phases-drops-non-positive
  (testing "Non-numeric / non-positive durations are filtered out"
    (let [out (wf/normalise-phases {:phases [[:a 100] [:b 0] [:c -10] [:d "x"]]
                                    :total-ms 100})]
      (is (= 1 (count out)))
      (is (= :a (:phase (first out)))))))

;; ---- slowest-phase ------------------------------------------------------

(deftest slowest-phase-picks-largest-duration
  (is (= :ttfb (wf/slowest-phase {:phases [[:dns 2] [:connect 15] [:ttfb 180] [:download 30]]}))))

(deftest slowest-phase-nil-on-empty
  (is (nil? (wf/slowest-phase {:phases []})))
  (is (nil? (wf/slowest-phase {}))))

;; ---- render smoke -------------------------------------------------------

(deftest render-returns-nil-for-empty
  (is (nil? (wf/render {})))
  (is (nil? (wf/render {:phases []}))))

(deftest render-returns-svg-hiccup
  (let [out (wf/render {:phases [[:dns 2] [:connect 15] [:ttfb 180]]
                        :total-ms 200})]
    (is (vector? out))
    (is (= :svg (first out)))
    (is (map? (second out)))
    (is (= "3" (-> out second :data-row-count)))))

(deftest render-overrides-testid
  (let [out (wf/render {:phases [[:a 10]]} {:testid "custom-id"})]
    (is (= "custom-id" (-> out second :data-testid)))))
