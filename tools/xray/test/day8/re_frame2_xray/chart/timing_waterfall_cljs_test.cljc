(ns day8.re-frame2-xray.chart.timing-waterfall-cljs-test
  "Pure-data tests for the wire-timing waterfall primitive
  (rf2-uyp86, parent rf2-5aw5v).

  Covers `normalise-phases` (the projection), `slowest-phase` (the
  annotation feed), and the smoke-render hiccup shape."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-xray.chart.timing-waterfall :as wf]))

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

(deftest normalise-phases-drops-negative-and-non-numeric
  (testing "Non-numeric and NEGATIVE durations are filtered out; a ZERO-length
            phase is KEPT and renders as a zero-width bar.

            A zero-length phase is a real measurement, not a bogus row: the
            managed-fx producers emit `[[:issued 0] [:elapsed N]]` deliberately,
            the `:issued` instant being the start of the window rather than a
            span. Dropping it collapsed that two-bar waterfall to one bar and
            made `timing-waterfall/bar-fill`'s `:issued` accent arm unreachable.
            The renderer already clamps bar width with `(max 1 …)`, so a
            zero-width row was always safe to draw."
    (let [out (wf/normalise-phases {:phases [[:a 100] [:b 0] [:c -10] [:d "x"]]
                                    :total-ms 100})]
      (is (= 2 (count out)))
      (is (= [:a :b] (mapv :phase out)))
      (is (= 1.0 (:width-pct (first out))))
      (is (= 0.0 (:width-pct (second out)))
          "the zero-length phase draws a zero-width bar rather than vanishing"))))

(deftest normalise-phases-keeps-the-producers-two-row-issued-waterfall
  (testing "The exact shape `managed-fx-helpers`' wire-timing readers synthesise
            — `[[:issued 0] [:elapsed N]]` — reaches the renderer as TWO rows,
            which is what makes the `:issued` accent fill reachable."
    (let [out (wf/normalise-phases {:phases [[:issued 0] [:elapsed 12]]
                                    :total-ms 12})]
      (is (= 2 (count out)))
      (is (= [:issued :elapsed] (mapv :phase out)))
      (is (= 0.0 (:width-pct (first out))))
      (is (= 1.0 (:width-pct (second out))))))
  (testing "and the renderer draws both rows"
    (let [svg (wf/render {:phases [[:issued 0] [:elapsed 12]] :total-ms 12})]
      (is (= "2" (-> svg second :data-row-count))))))

(deftest slowest-phase-still-ignores-zero-length-phases
  (testing "`slowest-phase` stays on `pos?` — a zero-length phase can never be
            the slowest, so widening `normalise-phases` must not widen this."
    (is (= :elapsed (wf/slowest-phase {:phases [[:issued 0] [:elapsed 12]]})))
    (is (nil? (wf/slowest-phase {:phases [[:issued 0]]})))))

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
