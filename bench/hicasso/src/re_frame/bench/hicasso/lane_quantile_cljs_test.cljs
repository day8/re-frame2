(ns re-frame.bench.hicasso.lane-quantile-cljs-test
  "THE LANE'S QUANTILE ESTIMATOR, WITNESSED (rf2-xa8wo).

  `budgets.md` registers `U1`–`U4` at `p95` or `p99` and `C8`'s benefit
  disjunct at `≥ 2 ms p95`, and until this bead the lane answered
  `{:n :min :max :p50}` and computed no tail quantile anywhere. So the
  block on those rows was a line of source rather than a missing box, and
  [[re-frame.bench.hicasso.lane/quantile]] is that line. This file is what
  keeps it honest.

  ## Why a DEFINITION needs a witness at all

  Because a percentile is not one thing. Nearest-rank, linear
  interpolation, and the exclusive and inclusive rank conventions all
  answer differently, and they differ MOST at exactly the sample sizes a
  bench window has: at `n = 20` the two spellings of `p95` here are `19`
  and `19.05`. A row that quoted one under the other's name would be
  wrong by less than a rounding error and unfalsifiable by inspection.

  Every case below therefore follows `lane_bytes_cljs_test`'s discipline
  — **assert the fixture DISCRIMINATES before asserting it is correct**.
  A fixture on which the two conventions agree tests the arithmetic and
  says nothing about the definition, and a fixture that quietly stopped
  discriminating would fail here rather than go on passing.

  ## The one consistency that matters more than the choice

  `:p50` and `:p95` are printed side by side in a single row, so they had
  better be one estimator. The lane's `:p50` was already a
  linear-interpolated median — a single order statistic at odd `n`, the
  mean of the two middle ones at even — which is `h = (n-1)q` at
  `q = 0.5`, and that is why that convention was taken rather than
  nearest-rank. The agreement is asserted below rather than asserted in a
  docstring: exactly at odd `n`, and within a float epsilon at even,
  where `(a+b)/2` and `a+(b-a)/2` may part company in the last place.

  ## NO READING IS TAKEN HERE, and none may be

  This is a test over known inputs. It runs on a loaded box, touches no
  clock, mounts nothing, and pins no figure any budget row reads. The
  window that will point the estimator at an application is a separate
  quiet-box run and is not this bead's — see `budgets.md` §9.4."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private eps
  "A float epsilon. Two spellings of one arithmetic may part company in
  the last representable place and nowhere else; anything wider than this
  is a different definition, not a rounding."
  1e-9)

(defn- close?
  [a b]
  (< (js/Math.abs (- (double a) (double b))) eps))

(defn- nearest-rank
  "The OTHER convention, present only so the fixtures can be shown to
  discriminate between it and the lane's. Never called by the lane."
  [xs q]
  (let [v (vec (sort xs))
        r (js/Math.ceil (* (double q) (count v)))]
    (nth v (max 0 (min (dec (count v)) (dec (int r)))))))

;; ---------------------------------------------------------------------------
;; The definition
;; ---------------------------------------------------------------------------

(deftest quantile-is-linear-interpolation-at-h-of-n-minus-one-q
  (testing "the fixture DISCRIMINATES — the two conventions disagree on it"
    (let [xs (vec (range 1 21))]                            ; 1..20, n = 20
      (is (= 20 (count xs)))
      (is (not (close? (rf.bench.hicasso.lane/quantile xs 0.95) (nearest-rank xs 0.95)))
          "if these agree the case below has stopped testing the definition")))

  (testing "and the answer is the interpolated one, not the nearest rank"
    (let [xs (vec (range 1 21))]
      ;; h = (20-1)*0.95 = 18.05 -> v[18] + (v[19] - v[18]) * 0.05
      ;;                          = 19   + 1 * 0.05 = 19.05
      (is (close? 19.05 (rf.bench.hicasso.lane/quantile xs 0.95)))
      (is (= 19 (nearest-rank xs 0.95))
          "the convention NOT taken, stated so the difference is on the page")))

  (testing "q = 0 is the minimum and q = 1 is the maximum, exactly"
    (let [xs [7.5 2.25 9.0 4.0]]
      (is (= 2.25 (rf.bench.hicasso.lane/quantile xs 0)))
      (is (= 9.0 (rf.bench.hicasso.lane/quantile xs 1)))))

  (testing "the sample need not arrive sorted"
    (let [asc  (vec (range 1 21))
          desc (vec (reverse asc))
          shuf [13 2 20 7 1 19 4 11 16 6 3 18 9 14 5 12 17 8 15 10]]
      (is (= (set asc) (set shuf)) "the shuffled fixture is the same sample")
      (is (close? (rf.bench.hicasso.lane/quantile asc 0.95) (rf.bench.hicasso.lane/quantile desc 0.95)))
      (is (close? (rf.bench.hicasso.lane/quantile asc 0.95) (rf.bench.hicasso.lane/quantile shuf 0.95)))))

  (testing "a one-sample and an empty sample"
    (is (= 4.0 (rf.bench.hicasso.lane/quantile [4.0] 0.99)))
    (is (nil? (rf.bench.hicasso.lane/quantile [] 0.95)))
    (is (nil? (rf.bench.hicasso.lane/quantile nil 0.95)))))

;; ---------------------------------------------------------------------------
;; The consistency with `:p50`, which is why this convention and not another
;; ---------------------------------------------------------------------------

(deftest quantile-at-one-half-is-summarise-s-p50
  (testing "EXACTLY, on an odd count — both are one member of the sample"
    (doseq [xs [[3.0 1.0 2.0]
                [5.0 1.0 4.0 2.0 3.0]
                (vec (range 1 22))]]
      (let [p50 (:p50 (rf.bench.hicasso.lane/summarise xs))]
        (is (= p50 (rf.bench.hicasso.lane/quantile xs 0.5))
            (str "p50 and quantile 0.5 must be one estimator on " (count xs) " samples"))
        (is (some #(= % p50) xs)
            "an odd count answers a member of its own sample"))))

  (testing "and within a float epsilon on an even count, where the two
            spellings of the midpoint may differ in the last place"
    (doseq [xs [[1.0 2.0 3.0 4.0]
                [0.1 0.3]
                [2.4 2.5 2.6 2.7 2.8 2.9]
                (vec (range 1 21))]]
      (is (close? (:p50 (rf.bench.hicasso.lane/summarise xs)) (rf.bench.hicasso.lane/quantile xs 0.5))
          (str "p50 and quantile 0.5 must agree on " (count xs) " samples"))))

  (testing "`:p50`'s own spelling is UNCHANGED by this bead — the two-branch
            median still answers what it always did"
    ;; The values below are the pre-`rf2-xa8wo` behaviour, transcribed. A
    ;; refactor that routed `:p50` through `quantile` would move a published
    ;; figure by up to one ulp, and this is the assertion that would catch it.
    (is (= 2.0 (:p50 (rf.bench.hicasso.lane/summarise [1.0 2.0 3.0]))))
    (is (= 2.5 (:p50 (rf.bench.hicasso.lane/summarise [1.0 2.0 3.0 4.0]))))
    (is (= (/ (+ 0.1 0.3) 2.0) (:p50 (rf.bench.hicasso.lane/summarise [0.1 0.3]))))))

;; ---------------------------------------------------------------------------
;; What a SHORT sample does to a tail quantile — the docstring's own caveat,
;; pinned, because it is the property a reader of a published row needs
;; ---------------------------------------------------------------------------

(deftest a-tail-quantile-on-a-short-sample-is-mostly-interpolation
  (testing "at n = 20 a p99 sits between the top two readings and is no
            reading the sample ever took"
    (let [xs  (vec (range 1 21))
          p99 (rf.bench.hicasso.lane/quantile xs 0.99)]
      ;; h = 19*0.99 = 18.81 -> 19 + (20-19)*0.81 = 19.81
      (is (close? 19.81 p99))
      (is (< 19 p99 20) "strictly between the second-largest and the largest")
      (is (not (some #(close? % p99) xs))
          "no member of the sample took this value — this is the caveat")))

  (testing "a quantile never exceeds the maximum, however short the sample"
    (doseq [n [1 2 3 5 20 101]]
      (let [xs (vec (range 1 (inc n)))
            s  (rf.bench.hicasso.lane/summarise xs)]
        (is (<= (:p50 s) (:p95 s) (:p99 s) (:max s))
            (str "quantiles must be monotone and bounded by :max at n = " n))
        (is (>= (:p95 s) (:min s)))))))

;; ---------------------------------------------------------------------------
;; `summarise`'s contract — the shape existing callers destructure, plus the
;; two fields this bead adds
;; ---------------------------------------------------------------------------

(deftest summarise-carries-the-tail-quantiles-and-keeps-everything-else
  (testing "the four fields every existing caller destructures are unmoved"
    (let [xs (vec (range 1 21))
          s  (rf.bench.hicasso.lane/summarise xs)]
      (is (= 20 (:n s)))
      (is (= 1 (:min s)))
      (is (= 20 (:max s)))
      (is (= 10.5 (:p50 s)))))

  (testing "and `:p95` / `:p99` are [[rf.bench.hicasso.lane/quantile]] and not a second spelling"
    (let [xs [4.2 1.9 3.3 8.8 2.7 5.1 6.4 7.0 9.6 0.5
              3.9 2.1 6.9 5.5 1.2 8.1 4.7 7.7 0.9 9.1]
          s  (rf.bench.hicasso.lane/summarise xs)]
      (is (= (rf.bench.hicasso.lane/quantile xs 0.95) (:p95 s)))
      (is (= (rf.bench.hicasso.lane/quantile xs 0.99) (:p99 s)))))

  (testing "and the summary carries EXACTLY these six keys"
    ;; Frozen here, next to the function, because it used to be frozen by
    ;; accident: `lane_control_strict_cljs_test`'s aggregate row compared a
    ;; whole summary map with `=` while meaning only to state a range, so
    ;; adding a field reddened a test with no opinion about fields. A shape
    ;; worth holding is worth holding where a reader would look for it.
    (is (= #{:n :min :max :p50 :p95 :p99}
           (set (keys (rf.bench.hicasso.lane/summarise [1.0 2.0 3.0]))))))

  (testing "an empty sample is still `nil` rather than a map of nothings"
    (is (nil? (rf.bench.hicasso.lane/summarise [])))
    (is (nil? (rf.bench.hicasso.lane/summarise nil))))

  (testing "a one-sample answers that sample at every quantile"
    (let [s (rf.bench.hicasso.lane/summarise [3.75])]
      (is (= 1 (:n s)))
      (is (= 3.75 (:min s) (:max s) (:p50 s) (:p95 s) (:p99 s))))))
