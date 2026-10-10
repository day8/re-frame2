(ns re-frame.bench.fresco.lane-quantile-cljs-test
  "[[re-frame.bench.fresco.lane/quantile]] is linear interpolation at
  `h = (n-1)q`, and [[re-frame.bench.fresco.lane/summarise]]'s `:p50` is the
  same estimator. Each fixture is one where the conventions disagree:
  `p95` of `1..20` is `19.05` here and `19` by nearest rank."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.bench.fresco.lane :as rf.bench.fresco.lane]))

(defn- close?
  "Within a float epsilon: `(a+b)/2` and `a+(b-a)/2` may part company in
  the last place and nowhere else."
  [a b]
  (< (js/Math.abs (- (double a) (double b))) 1e-9))

(def ^:private shuffled-1-to-20
  [13 2 20 7 1 19 4 11 16 6 3 18 9 14 5 12 17 8 15 10])

(deftest quantile-is-linear-interpolation-at-h-of-n-minus-one-q
  (is (close? 19.05 (rf.bench.fresco.lane/quantile shuffled-1-to-20 0.95))
      "h = 18.05, interpolated, over a sample that did not arrive sorted")
  (is (= [2.25 9.0] (mapv #(rf.bench.fresco.lane/quantile [7.5 2.25 9.0 4.0] %) [0 1]))
      "q = 0 is the minimum and q = 1 the maximum, exactly")
  (is (= 4.0 (rf.bench.fresco.lane/quantile [4.0] 0.99)))
  (is (nil? (rf.bench.fresco.lane/quantile [] 0.95))))

(deftest quantile-at-one-half-is-summarise-s-p50
  (let [odd [5.0 1.0 4.0 2.0 3.0]
        even [2.4 2.5 2.6 2.7 2.8 2.9]]
    (is (= (:p50 (rf.bench.fresco.lane/summarise odd)) (rf.bench.fresco.lane/quantile odd 0.5))
        "exactly, on an odd count — both are the middle member")
    (is (close? (:p50 (rf.bench.fresco.lane/summarise even)) (rf.bench.fresco.lane/quantile even 0.5))
        "within a float epsilon on an even count")))

(deftest summarise-carries-the-tail-quantiles-and-keeps-everything-else
  (is (= {:n   20 :min 1 :max 20 :p50 10.5
          :p95 (rf.bench.fresco.lane/quantile shuffled-1-to-20 0.95)
          :p99 (rf.bench.fresco.lane/quantile shuffled-1-to-20 0.99)}
         (rf.bench.fresco.lane/summarise shuffled-1-to-20))
      "exactly these six keys, and :p95 / :p99 are `quantile`, not a second spelling")
  (is (nil? (rf.bench.fresco.lane/summarise []))))
