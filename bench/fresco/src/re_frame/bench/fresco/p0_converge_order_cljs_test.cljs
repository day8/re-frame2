(ns re-frame.bench.fresco.p0-converge-order-cljs-test
  "THE SEGMENT-ORDER VERDICT, replayed against the numbers it was built to
  judge, and the studio page's ensemble and re-take figures derived from
  their observation tables.

  `p0-converge-app/segment-order-verdict` partitions a cross-segment
  figure by which segment ran FIRST in each round — the question
  `lane/guard!` cannot ask, because the guard adjudicates arms INSIDE a
  segment and the red-zone is a ratio ACROSS the seam. The published
  per-round vectors from
  `docs/design/fresco/studio/p0-converged-witness-set.md` are replayed
  through it: the red-zone run, the four-row reproduction sweep, the
  second author's reactive leg and the re-take's run 5. Every published
  five-round run was Reagent-start, so rounds 0, 2, 4 are Reagent-first.

  [[ensemble]] and [[retake]] are the observation tables behind the page's
  summaries. Every figure the page publishes about them is derived here and
  checked against the page's own numbers, transcribed into [[view-1]],
  [[view-1-p]], [[view-2]], [[components]], [[published-threshold]],
  [[retake-published]] and [[retake-corrected]]. The page's ensemble
  intervals use t at EIGHT degrees of freedom where a mean of ten needs
  NINE: [[the-published-intervals-used-eight-degrees-of-freedom-where-nine-is-right]]
  pins the diagnosis and
  [[the-corrected-nine-degree-intervals-change-no-verdict]] the correction.

  Last, the `FRESCO_RATOM=on` contract: the flag is page-global, the arm
  is per row, and `row-record` publishes a reactive leg only where the arm
  ran.

  Pure arithmetic over recorded vectors: no DOM, no clock, no browser,
  so this runs on every runtime."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.bench.fresco.p0-converge-app :as rf.bench.fresco.p0-converge-app]))

;; ---------------------------------------------------------------------------
;; The published vectors
;; ---------------------------------------------------------------------------

(def ^:private published
  "Per-round `uix-subs ÷ reagent-subs`, both floor-normalised in the same
  round and segment — the RED-ZONE column of the studio page's table."
  {:M1     [1.3065 1.1417 1.2388 1.1099 1.3538]
   :M2     [1.4286 0.8572 0.8572 1.0550 1.0714]
   :broad  [0.7172 0.6046 0.5417 0.7857 0.4701]
   :narrow [1.2053 1.1515 1.1860 1.0570 1.1700]})

(def ^:private superseded-narrow
  "The unbatched narrow row, struck through on the page: the narrow row
  whose strata overlap."
  [1.1111 1.2500 1.2500 1.0417 1.1250])

(def ^:private sweep
  "The independent four-row reproduction sweep."
  {:M1     [1.4242 1.1462 1.3611 1.3214 1.1905]
   :M2     [1.2727 0.8000 1.0000 1.0909 1.0000]
   :broad  [0.5750 0.5263 0.6176 0.5556 0.7353]
   :narrow [1.2528 1.1591 1.1705 1.1507 1.1136]})

(def ^:private rows [:M1 :M2 :broad :narrow])

(defn- v
  "Replay a published five-round vector, every one of which was
  Reagent-start."
  [vs]
  (rf.bench.fresco.p0-converge-app/segment-order-verdict vs 5 :reagent-subs))

(defn- close-to?
  "Within `tol`. The page quotes four decimals, several of them rounded
  from unrounded readings, so an identity that holds exactly on the raw
  data can miss by a unit in the last place."
  [a b tol]
  (< (js/Math.abs (- a b)) tol))

(def ^:private three-decimal
  "The page prints ratios, p values and resolution limits to three
  decimals."
  #{:ratio :p :limit})

(defn- approx=
  "Every figure in `expected` matches the same key of `actual` to the
  page's rounding — `tol` when given, else 0.0006 for a three-decimal
  figure and 0.0002 for the rest."
  ([expected actual] (approx= nil expected actual))
  ([tol expected actual]
   (every? (fn [[k x]]
             (let [y (get actual k)]
               (and (number? y)
                    (close-to? x y (or tol (if (three-decimal k) 0.0006 0.0002))))))
           expected)))

;; ---------------------------------------------------------------------------
;; The published five-round runs
;; ---------------------------------------------------------------------------

(deftest the-published-m1-partition-is-the-one-the-audit-reported
  ;; Disjoint strata, so the row may not publish 1.2301 as a threshold; the
  ;; design-unbiased estimator over the 3:2 split is 1.2128.
  (is (= {:start               :reagent-subs
          :reagent-first       {:per-round [1.3065 1.2388 1.3538] :mean 1.2997
                                :min 1.2388 :max 1.3538 :straddles-1? false
                                :direction :numerator-slower :n 3}
          :uix-first           {:per-round [1.1417 1.1099] :mean 1.1258
                                :min 1.1099 :max 1.1417 :straddles-1? false
                                :direction :numerator-slower :n 2}
          :order-balanced-mean 1.2128
          :balanced-design?    false
          :strata-overlap?     false
          :magnitude-resolved? false
          :direction-agrees?   true
          :refuse?             false}
         (dissoc (v (:M1 published)) :why))))

(deftest no-row-is-disjoint-in-both-runs-and-none-points-both-ways
  ;; An order effect would split the SAME row in both runs; at 3:2 a
  ;; disjoint split arises by chance in 2 of C(5,2) = 10 assignments. The
  ;; fail-closed direction half fires on none of the eight row-runs.
  (let [rows-where (fn [m pred] (set (filter #(pred (v (get m %))) rows)))]
    (is (= {:published {:disjoint #{:M1 :narrow} :opposed #{}}
            :sweep     {:disjoint #{:broad}      :opposed #{}}}
           (into {} (for [[k m] [[:published published] [:sweep sweep]]]
                      [k {:disjoint (rows-where m (complement :strata-overlap?))
                          :opposed  (rows-where m (complement :direction-agrees?))}])))))
  (is (true? (:strata-overlap? (v superseded-narrow)))
      "only the superseded unbatched narrow row overlaps"))

(deftest the-strata-are-keyed-by-the-segment-that-actually-led
  (testing "flipping the start swaps the strata and changes nothing else; at
           an even round count the order-balanced mean is the raw mean"
    (let [vs [1.30 1.10 1.25 1.12 1.35 1.11]
          r  (dissoc (rf.bench.fresco.p0-converge-app/segment-order-verdict vs 6 :reagent-subs) :why)
          u  (dissoc (rf.bench.fresco.p0-converge-app/segment-order-verdict vs 6 :uix-subs) :why)]
      (is (= {:start               :reagent-subs
              :reagent-first       {:per-round [1.3 1.25 1.35] :mean 1.3
                                    :min 1.25 :max 1.35 :straddles-1? false
                                    :direction :numerator-slower :n 3}
              :uix-first           {:per-round [1.1 1.12 1.11] :mean 1.11
                                    :min 1.1 :max 1.12 :straddles-1? false
                                    :direction :numerator-slower :n 3}
              :order-balanced-mean 1.205
              :balanced-design?    true
              :strata-overlap?     false
              :magnitude-resolved? false
              :direction-agrees?   true
              :refuse?             false}
             r))
      (is (= (assoc r :start :uix-subs :reagent-first (:uix-first r) :uix-first (:reagent-first r))
             u))))
  (testing "strata pointing opposite ways across 1.0 refuse under either
           start, with the directions exchanged"
    (let [vs [1.40 0.70 1.45 0.72 1.38 0.69]]
      (is (= [[:numerator-slower true] [:numerator-faster true]]
             (map #((juxt (comp :direction :reagent-first) :refuse?)
                    (rf.bench.fresco.p0-converge-app/segment-order-verdict vs 6 %))
                  [:reagent-subs :uix-subs]))))))

;; ---------------------------------------------------------------------------
;; The reactive leg, replayed
;; ---------------------------------------------------------------------------

(def ^:private leg
  "The second author's published per-round `reagent-subs / reagent-ratom`
  vectors, six runs a row, starting segment counterbalanced 3/3. Both terms
  are Reagent arms measured in the same segment, but that segment leads
  half the rounds and follows the other half, so the same partition
  adjudicates it."
  {:M1    [{:start :reagent-subs :vs [1.3788 1.2963 1.3333 1.3396 1.3654 1.2778]}
           {:start :uix-subs     :vs [1.2963 1.3137 1.2692 1.4000 1.3469 1.3333]}
           {:start :reagent-subs :vs [1.3621 1.3200 1.3061 1.4822 1.3214 1.3065]}
           {:start :uix-subs     :vs [1.3725 1.3061 1.2500 1.4348 1.3636 1.3000]}
           {:start :reagent-subs :vs [1.3333 1.3750 1.3043 1.3478 1.3333 1.2766]}
           {:start :uix-subs     :vs [1.3667 1.3333 1.2727 1.4039 1.3396 1.3077]}]
   :broad [{:start :reagent-subs :vs [2.5909 2.4000 2.4210 2.5556 2.6471 2.6875]}
           {:start :uix-subs     :vs [2.5789 2.3000 2.1000 2.3000 2.5000 2.3889]}
           {:start :reagent-subs :vs [2.5833 2.3636 2.1363 2.5789 2.5556 2.3889]}
           {:start :uix-subs     :vs [2.5883 2.3333 2.2105 2.5000 2.5000 2.7858]}
           {:start :reagent-subs :vs [2.7778 2.3889 2.5000 2.5625 2.6667 2.7143]}
           {:start :uix-subs     :vs [2.7222 2.4210 2.4444 2.8750 2.3889 2.3889]}]})

(defn- legs [row] (map (fn [{:keys [vs start]}]
                         (rf.bench.fresco.p0-converge-app/segment-order-verdict vs 6 start "the reactive leg's"))
                       (get leg row)))

(deftest the-second-authors-leg-agrees-in-direction-and-not-in-magnitude
  ;; The page's `magnitude-resolved? true on 12 of 12`, with both strata of
  ;; every row-run wholly above 1.0; and every run mean clears the first
  ;; author's 1.213 – 1.218 on M1 and 1.965 – 2.073 on broad.
  (is (= (repeat 12 [true :numerator-slower :numerator-slower])
         (map (juxt :magnitude-resolved? (comp :direction :reagent-first) (comp :direction :uix-first))
              (mapcat legs [:M1 :broad]))))
  (is (every? #(> % 1.30) (map :order-balanced-mean (legs :M1))))
  (is (every? #(> % 2.30) (map :order-balanced-mean (legs :broad)))))

;; ---------------------------------------------------------------------------
;; THE BALANCED ENSEMBLE'S OBSERVATION TABLE
;;
;; Ten independently launched six-round runs, the starting segment
;; counterbalanced five and five. The cells are the ten runs' own console
;; logs, recovered rather than re-measured; the studio page carries the
;; provenance.
;; ---------------------------------------------------------------------------

(def ^:private ensemble
  "THE 10x4 OBSERVATION TABLE. One entry per launched run, one vector per
  witness: that run's six per-round `uix-subs ÷ reagent-subs` readings,
  each floor-normalised in its own round and segment. `:start` is the
  segment that led round 0.

  The vectors are the instrument's four-decimal output and the
  instrument's own means come from unrounded readings, so a re-derived
  mean can differ in the fourth decimal, by no more than 0.0006."
  [{:run  1 :start :reagent-subs
    :M1     [1.4286 0.9529 1.2681 1.2186 1.1378 1.2892]
    :M2     [0.8571 1.3846 1.0714 1.0000 1.0794 0.8889]
    :broad  [0.6190 0.6328 0.6144 0.5532 0.8485 0.7292]
    :narrow [1.2575 1.1161 1.0999 1.3602 1.0568 1.0346]}
   {:run  2 :start :uix-subs
    :M1     [1.3375 1.1299 1.2017 1.1522 1.1818 1.1905]
    :M2     [0.9722 1.0000 1.3333 0.9333 0.8750 0.8000]
    :broad  [0.4857 0.5769 0.8974 0.5385 0.6282 0.5652]
    :narrow [1.2584 1.0789 1.0710 1.4104 1.1913 1.0800]}
   {:run  3 :start :reagent-subs
    :M1     [1.2466 1.1178 1.2121 1.2286 1.2698 1.4026]
    :M2     [0.6667 1.6250 1.0714 1.0000 0.7500 0.9796]
    :broad  [0.5556 0.6500 0.6667 0.8571 0.6216 0.5789]
    :narrow [1.1959 1.2648 1.1483 1.2884 1.1095 1.1764]}
   {:run  4 :start :uix-subs
    :M1     [1.1190 1.3897 1.3231 1.2500 1.0606 1.2727]
    :M2     [1.3500 0.6667 0.7656 1.0208 1.0000 0.9333]
    :broad  [0.5314 0.6667 0.6364 0.5556 0.5581 0.5909]
    :narrow [1.1539 1.1550 1.0691 1.1420 1.1771 1.1368]}
   {:run  5 :start :reagent-subs
    :M1     [1.2418 1.1719 1.2308 1.2343 1.2258 1.1290]
    :M2     [1.2500 1.2000 1.1667 1.1000 1.2000 0.8571]
    :broad  [0.6222 0.4632 0.4444 0.6875 0.5790 0.7237]
    :narrow [1.2316 1.1444 1.1786 1.1111 1.2143 1.1566]}
   {:run  6 :start :uix-subs
    :M1     [1.1975 1.2418 1.0867 1.2500 1.2983 1.2542]
    :M2     [1.7143 0.9167 1.0000 1.0000 1.6000 0.9231]
    :broad  [0.5159 0.6500 0.6250 0.7857 0.8250 0.5641]
    :narrow [1.2182 1.1334 1.1258 1.1809 1.2282 1.1420]}
   {:run  7 :start :reagent-subs
    :M1     [1.1192 1.1426 1.1875 1.3393 1.4194 1.0788]
    :M2     [0.7778 1.0000 1.2000 1.1000 1.0000 0.6875]
    :broad  [0.6383 0.7895 0.6316 0.5500 0.5263 0.6944]
    :narrow [1.1667 1.0947 1.1905 1.1265 1.1470 1.2024]}
   {:run  8 :start :uix-subs
    :M1     [1.1004 1.2167 1.2091 1.3241 1.3960 1.5124]
    :M2     [1.1667 1.2000 1.2000 1.0000 1.1000 1.0000]
    :broad  [0.4211 0.4259 0.7639 0.7895 0.4865 0.5882]
    :narrow [1.2055 1.1698 1.1183 1.2355 1.0947 1.1765]}
   {:run  9 :start :reagent-subs
    :M1     [1.1549 1.3103 1.1057 1.3158 1.2895 1.3572]
    :M2     [1.1667 0.9000 1.2000 1.0000 1.1111 1.0909]
    :broad  [0.7250 0.6000 0.6111 0.5000 0.5526 0.6176]
    :narrow [1.3105 1.1465 1.1315 1.2145 1.1964 1.1893]}
   {:run 10 :start :uix-subs
    :M1     [1.1291 1.3929 1.1008 1.1118 1.2381 1.3621]
    :M2     [1.1429 1.6364 0.8000 1.2000 1.2833 0.6875]
    :broad  [0.5814 0.5750 0.7733 0.6571 0.9000 0.7051]
    :narrow [1.2226 1.1715 1.2373 1.3892 1.1422 1.1487]}])

(def ^:private view-1
  "The page's View 1 table: the two start-group means over five runs
  each, their difference, and the threshold the row publishes."
  {:M1     {:reagent-start 1.2276 :uix-start 1.2344 :difference -0.0068 :threshold 1.2310}
   :M2     {:reagent-start 1.0461 :uix-start 1.0740 :difference -0.0280 :threshold 1.0601}
   :broad  {:reagent-start 0.6295 :uix-start 0.6288 :difference +0.0007 :threshold 0.6291}
   :narrow {:reagent-start 1.1754 :uix-start 1.1755 :difference -0.0001 :threshold 1.1754}})

(def ^:private view-1-p
  "The page's View 1 permutation *p* on the start-group difference of the
  threshold means, and its `resolution limit` — the half-width of the 95%
  interval on that difference."
  {:M1 {:p 0.770 :limit 0.043} :M2 {:p 0.611 :limit 0.122}
   :broad {:p 0.984 :limit 0.063} :narrow {:p 0.992 :limit 0.037}})

(def ^:private view-2
  "The page's View 2 table: the mean of the ten per-run `d` values, the
  same figure as a ratio, the 95% interval printed as ratios, the
  one-sided sign-flip *p*, and how many of the ten `d` are positive."
  {:M1        {:mean-d +0.0357 :ratio 1.036 :lo 0.979 :hi 1.097 :p 0.084 :positive 7}
   :M2        {:mean-d -0.0837 :ratio 0.920 :lo 0.795 :hi 1.065 :p 0.889 :positive 4}
   :broad     {:mean-d -0.0388 :ratio 0.962 :lo 0.887 :hi 1.044 :p 0.856 :positive 5}
   :narrow    {:mean-d +0.0070 :ratio 1.007 :lo 0.977 :hi 1.038 :p 0.302 :positive 7}
   :composite {:mean-d -0.0200 :ratio 0.980 :lo 0.934 :hi 1.029 :p 0.823 :positive 6}})

(def ^:private components
  "The page's order/temporal decomposition of `d` and the permutation *p*
  on its start-group difference. The split is descriptive — exact only
  under an additive, antisymmetric temporal model — so what is checked is
  its arithmetic, not the attribution."
  {:M1        {:order +0.0357 :temporal -0.0212 :p 0.421}
   :M2        {:order -0.0837 :temporal +0.0632 :p 0.318}
   :broad     {:order -0.0388 :temporal -0.0011 :p 1.000}
   :narrow    {:order +0.0070 :temporal -0.0063 :p 0.698}
   :composite {:order -0.0200 :temporal +0.0086 :p 0.810}})

(def ^:private published-threshold
  "The RED-ZONE table: the threshold, the 95% interval on the mean, and
  the observed spread of the ten run means."
  {:M1     {:threshold 1.2310 :lo 1.2105 :hi 1.2514 :run-min 1.1989 :run-max 1.2931}
   :M2     {:threshold 1.0601 :lo 1.0017 :hi 1.1185 :run-min 0.9561 :run-max 1.1923}
   :broad  {:threshold 0.6291 :lo 0.5996 :hi 0.6587 :run-min 0.5792 :run-max 0.6987}
   :narrow {:threshold 1.1754 :lo 1.1579 :hi 1.1930 :run-min 1.1390 :run-max 1.2186}})

;; --- the derivation, in the smallest form that computes the published table ---

(defn- mean [xs] (/ (reduce + 0.0 xs) (count xs)))

(defn- sample-sd [xs]
  (let [m (mean xs)]
    (js/Math.sqrt (/ (reduce + 0.0 (map #(* (- % m) (- % m)) xs))
                     (dec (count xs))))))

(def ^:private t-9
  "Student's t, 0.975, NINE degrees of freedom — the multiplier for a 95%
  interval on the mean of TEN run means."
  2.262157)

(def ^:private t-8
  "Student's t, 0.975, EIGHT degrees of freedom: right for the two-sample
  five-against-five `resolution limit`, and the multiplier the page also
  uses for its one-sample intervals, which is the error."
  2.306004)

(defn- run-mean [run row] (mean (get run row)))

(defn- d-of
  "THE PER-RUN STATISTIC: `ln(mean Reagent-first / mean UIx-first)`,
  positive when the figure reads higher with the Reagent segment leading.

  The partition comes from `segment-order-verdict`, but the stratum means
  are averaged here from the readings, not taken from the verdict's
  four-decimal `:mean` — on broad that rounding carries one of the 1024
  sign-flip assignments across the observed mean (878/1024 against the
  published 877/1024)."
  [vs start]
  (let [r (rf.bench.fresco.p0-converge-app/segment-order-verdict vs 6 start)]
    (js/Math.log (/ (mean (:per-round (:reagent-first r)))
                    (mean (:per-round (:uix-first r)))))))

(defn- reagent-start? [run] (= :reagent-subs (:start run)))

(defn- by-start
  "Split ten per-run values into [reagent-start-five uix-start-five],
  keyed on the run that produced each."
  [values]
  [(keep-indexed #(when (reagent-start? (nth ensemble %1)) %2) values)
   (keep-indexed #(when-not (reagent-start? (nth ensemble %1)) %2) values)])

(defn- thresholds [row] (mapv #(run-mean % row) ensemble))
(defn- d-values  [row] (mapv #(d-of (get % row) (:start %)) ensemble))

(defn- combinations-from
  "Every way to choose `k` indices from `[start, n)`, ascending."
  [start n k]
  (if (zero? k)
    [[]]
    (mapcat (fn [i] (map #(cons i %) (combinations-from (inc i) n (dec k))))
            (range start (inc (- n k))))))

(defn- permutation-p
  "Exact two-sided permutation *p* on the difference between the two
  start groups, over all C(10,5) = 252 relabellings — exact given the
  relabelling set, since the starts were alternated rather than drawn."
  [values]
  (let [[a b] (by-start values)
        obs   (js/Math.abs (- (mean a) (mean b)))
        combs (combinations-from 0 10 5)
        hits  (count (filter (fn [c]
                               (let [s (set c)
                                     x (keep-indexed #(when (s %1) %2) values)
                                     y (keep-indexed #(when-not (s %1) %2) values)]
                                 (>= (js/Math.abs (- (mean x) (mean y))) (- obs 1e-12))))
                             combs))]
    (/ hits (double (count combs)))))

(defn- sign-flip-p
  "Exact one-sided sign-flip *p* over all 2^10 = 1024 sign assignments, in
  the positive direction the order-effect hypothesis names."
  [ds]
  (let [obs (mean ds)
        hits (count (filter (fn [m]
                              (>= (mean (map-indexed
                                          (fn [i d] (if (bit-test m i) (- d) d))
                                          ds))
                                  (- obs 1e-12)))
                            (range 1024)))]
    (/ hits 1024.0)))

(defn- view-2-of [d]
  {:mean-d   (mean d)
   :ratio    (js/Math.exp (mean d))
   :p        (sign-flip-p d)
   :positive (count (filter pos? d))})

(defn- components-of [d]
  (let [[rs us] (by-start d)]
    {:order    (/ (+ (mean rs) (mean us)) 2.0)
     :temporal (/ (- (mean rs) (mean us)) 2.0)
     :p        (permutation-p d)}))

(defn- verdict-of [run row]
  (rf.bench.fresco.p0-converge-app/segment-order-verdict (get run row) 6 (:start run)))

;; ---------------------------------------------------------------------------
;; Every published figure, re-derived from the forty cells
;; ---------------------------------------------------------------------------

(deftest the-red-zone-table-and-view-1-reproduce
  ;; The threshold is the mean of the ten run means and, because the
  ;; counterbalance is 5/5, equally View 1's average of the two start-group
  ;; means. The resolution limit is a two-sample five-against-five
  ;; contrast, so eight degrees of freedom is right there.
  (doseq [row rows]
    (let [t       (thresholds row)
          [rs us] (by-start t)
          pooled  (js/Math.sqrt (/ (+ (* 4 (js/Math.pow (sample-sd rs) 2))
                                      (* 4 (js/Math.pow (sample-sd us) 2)))
                                   8))]
      (is (approx= (dissoc (get published-threshold row) :lo :hi)
                   {:threshold (mean t) :run-min (apply min t) :run-max (apply max t)})
          (name row))
      (is (approx= (get view-1 row)
                   {:reagent-start (mean rs)
                    :uix-start     (mean us)
                    :difference    (- (mean rs) (mean us))
                    :threshold     (/ (+ (mean rs) (mean us)) 2.0)})
          (name row))
      (is (approx= (get view-1-p row)
                   {:p     (permutation-p t)
                    :limit (* t-8 pooled (js/Math.sqrt (/ 2.0 5)))})
          (name row)))))

(deftest view-2-and-the-components-reproduce
  ;; One `d` per run per row; the composite is the per-RUN mean of that
  ;; run's four `d`, never four pooled trials.
  (let [ds (into {} (map (juxt identity d-values)) rows)
        ds (assoc ds :composite (apply mapv (fn [& per-row] (mean per-row)) (map ds rows)))]
    (doseq [[row d] ds]
      (is (approx= (dissoc (get view-2 row) :lo :hi) (view-2-of d)) (name row))
      (is (approx= (get components row) (components-of d)) (name row)))))

(deftest the-prose-counts-reproduce
  ;; `59 of 60 rounds above 1.0; 19 of 20 order strata wholly above it` on
  ;; M1, all 60 / all 20 below on broad and above on narrow. The strata
  ;; overlap in 37 of 40 row-runs, the three unresolved falling on M1, M2
  ;; and narrow in two runs, so no row is disjoint twice, and the direction
  ;; half never refuses. Counted as 40 independent row-runs, the
  ;; Reagent-first stratum is higher in 23 — the old 11-of-12 statistic on
  ;; the balanced design, where it is not an effect.
  (let [rounds-of  (fn [row] (mapcat #(get % row) ensemble))
        strata-of  (fn [row] (mapcat #((juxt :reagent-first :uix-first) (verdict-of % row)) ensemble))
        count-of   (fn [row pred bound]
                     [(count (filter pred (rounds-of row)))
                      (count (filter (comp pred bound) (strata-of row)))])
        verdicts   (for [run ensemble row rows] [(:run run) row (verdict-of run row)])
        unresolved (remove #(:magnitude-resolved? (nth % 2)) verdicts)]
    (is (= {:M1 [59 19] :broad [60 20] :narrow [60 20]
            :resolved 37 :unresolved-rows #{:M1 :M2 :narrow} :unresolved-runs 2
            :not-refused 40 :reagent-first-higher 23}
           {:M1                   (count-of :M1 #(> % 1.0) :min)
            :broad                (count-of :broad #(< % 1.0) :max)
            :narrow               (count-of :narrow #(> % 1.0) :min)
            :resolved             (- (count verdicts) (count unresolved))
            :unresolved-rows      (set (map second unresolved))
            :unresolved-runs      (count (set (map first unresolved)))
            :not-refused          (count (filter #(false? (:refuse? (nth % 2))) verdicts))
            :reagent-first-higher (count (filter (fn [[_ _ v]]
                                                   (> (:mean (:reagent-first v)) (:mean (:uix-first v))))
                                                 verdicts))}))))

;; ---------------------------------------------------------------------------
;; THE ONE DISAGREEMENT, pinned rather than conformed away
;; ---------------------------------------------------------------------------

(deftest the-published-intervals-used-eight-degrees-of-freedom-where-nine-is-right
  (testing "an interval on the mean of TEN run means needs t at nine degrees
           of freedom; the published half-width is t at EIGHT times the
           standard error, and not t at nine"
    (doseq [row rows]
      (let [t    (thresholds row)
            se   (/ (sample-sd t) (js/Math.sqrt 10))
            {:keys [lo hi]} (get published-threshold row)
            half (/ (- hi lo) 2.0)]
        (is (not (close-to? half (* t-9 se) 0.0002))
            (str (name row) " — the published interval is NOT the 9-df one"))
        (is (close-to? half (* t-8 se) 0.0006)
            (str (name row) " — it IS the 8-df one")))))
  (testing "View 2's intervals on `d`, printed as three-decimal ratios,
           recover a multiplier between 2.28 and 2.34: not 2.2622, and
           consistent with 2.3060"
    (doseq [row rows]
      (let [d  (d-values row)
            se (/ (sample-sd d) (js/Math.sqrt 10))
            {:keys [lo hi]} (get view-2 row)]
        (is (< 2.28 (/ (/ (- (js/Math.log hi) (js/Math.log lo)) 2.0) se) 2.34)
            (name row))))))

(deftest the-corrected-nine-degree-intervals-change-no-verdict
  ;; The published intervals are too WIDE, so the corrected ones keep every
  ;; verdict: M1, M2 and narrow stay above 1.0 and broad below.
  (doseq [[row lo hi] [[:M1 1.2109 1.2510] [:M2 1.0028 1.1173]
                       [:broad 0.6001 0.6581] [:narrow 1.1582 1.1926]]]
    (let [t (thresholds row)
          h (* t-9 (/ (sample-sd t) (js/Math.sqrt 10)))]
      (is (approx= {:lo lo :hi hi} {:lo (- (mean t) h) :hi (+ (mean t) h)})
          (name row)))))

;; ---------------------------------------------------------------------------
;; THE RE-TAKE'S OBSERVATION TABLE, ON THE CONVERGED INSTRUMENT
;; ---------------------------------------------------------------------------
;;
;; The unit that survives is the RUN MEAN: each row's `red-zone :mean` for
;; that run, transcribed from the producing transcript; nothing was
;; re-measured. Only run 5's per-round vectors survive, in
;; [[run-5-per-round]], so the page's per-round counts are not derivable
;; here and every ensemble-level figure is.
;;
;; THE FIVE-RUN SET. A fifth run completed all four rows and the driver
;; refused it, exiting 1, because its M1 strata point opposite ways across
;; 1.0. That is a function of the result, so the run stays one observation
;; of the ensemble — see
;; [[the-fifth-run-was-dropped-for-the-way-its-strata-split]].

(def ^:private retake
  "THE 5 × 4 OBSERVATION TABLE of the re-take. Each cell is that run's
  `red-zone :mean` for the row. `:start` is the segment that led round 0,
  `:exit` the driver's exit code, and `:M1-legs` the run's M1
  `reagent-subs ÷ floor` and `uix-subs ÷ floor` legs."
  [{:run 1 :start :reagent-subs :exit 0
    :M1 0.9980 :M2 1.0584 :broad 0.6316 :narrow 1.2306
    :M1-legs {:reagent 4.2853 :uix 4.2723}}
   {:run 2 :start :uix-subs :exit 0
    :M1 1.0391 :M2 0.9685 :broad 0.5437 :narrow 1.1767
    :M1-legs {:reagent 4.3457 :uix 4.5089}}
   {:run 3 :start :reagent-subs :exit 0
    :M1 1.0219 :M2 1.0714 :broad 0.5538 :narrow 1.3090
    :M1-legs {:reagent 4.5731 :uix 4.6673}}
   {:run 6 :start :uix-subs :exit 0
    :M1 1.0382 :M2 0.9302 :broad 0.5102 :narrow 1.1769
    :M1-legs {:reagent 4.3144 :uix 4.4687}}
   {:run 5 :start :reagent-subs :exit 1
    :M1 0.9780 :M2 0.9242 :broad 0.6135 :narrow 1.1291
    :M1-legs {:reagent 4.6045 :uix 4.4573}}])

(def ^:private run-5-per-round
  "Run 5's four per-round vectors, six rounds, round 0 led by the Reagent
  segment."
  {:M1     [1.0345 0.9749 1.0792 0.9279 1.0459 0.8055]
   :M2     [0.8    1.1053 0.9584 0.7727 1.0    0.9091]
   :broad  [0.5938 0.6667 0.6667 0.4675 0.54   0.7467]
   :narrow [1.178  1.1033 1.1584 1.1559 1.151  1.0282]})

(def ^:private retake-published
  "The page's figures over the FOUR accepted runs, superseded on the page
  by the five-run figures below."
  {:M1     {:threshold 1.0243 :lo 0.9937 :hi 1.0549 :run-min 0.9980 :run-max 1.0391}
   :M2     {:threshold 1.0071 :lo 0.8978 :hi 1.1165 :run-min 0.9302 :run-max 1.0714}
   :broad  {:threshold 0.5598 :lo 0.4781 :hi 0.6415 :run-min 0.5102 :run-max 0.6316}
   :narrow {:threshold 1.2233 :lo 1.1238 :hi 1.3228 :run-min 1.1767 :run-max 1.3090}})

(def ^:private retake-corrected
  "What the page publishes: the same four rows over the FIVE runs the
  ensemble actually holds, run 5 included."
  {:M1     {:threshold 1.0150 :lo 0.9820 :hi 1.0480 :run-min 0.9780 :run-max 1.0391}
   :M2     {:threshold 0.9905 :lo 0.9035 :hi 1.0776 :run-min 0.9242 :run-max 1.0714}
   :broad  {:threshold 0.5706 :lo 0.5078 :hi 0.6333 :run-min 0.5102 :run-max 0.6316}
   :narrow {:threshold 1.2045 :lo 1.1193 :hi 1.2896 :run-min 1.1291 :run-max 1.3090}})

(def ^:private t-3
  "Student's t, 0.975, THREE degrees of freedom — a 95% interval on the
  mean of FOUR run means."
  3.182446)

(def ^:private t-4
  "Student's t, 0.975, FOUR degrees of freedom — a 95% interval on the
  mean of FIVE."
  2.776445)

(defn- accepted-only
  "The four accepted runs: exit 0."
  []
  (filterv #(zero? (:exit %)) retake))

(defn- retake-ci
  "Point estimate, sample sd, one-sample Student-t 95% interval and
  run-mean range for `row` over `runs`, at `n − 1` degrees of freedom."
  [runs row]
  (let [xs (mapv #(get % row) runs)
        m  (mean xs)
        sd (sample-sd xs)
        t  (case (count xs) 4 t-3 5 t-4)
        h  (* t (/ sd (js/Math.sqrt (count xs))))]
    {:threshold m :sd sd :lo (- m h) :hi (+ m h)
     :run-min (apply min xs) :run-max (apply max xs)}))

(deftest the-re-take-figures-reproduce-over-four-runs-and-over-five
  ;; The four accepted runs' figures, then the five-run figures that replace
  ;; them, and M1's floor-normalised legs on each set — whose quotient differs
  ;; from the threshold because each run forms its ratio per round before
  ;; averaging.
  (doseq [[runs figures legs] [[(accepted-only) retake-published {:den 4.380 :num 4.479 :quotient 1.023}]
                               [retake retake-corrected {:den 4.425 :num 4.475 :quotient 1.011}]]]
    (doseq [row rows]
      (is (approx= (get figures row) (retake-ci runs row)) (str (count runs) " runs, " (name row))))
    (let [den (mean (mapv #(get-in % [:M1-legs :reagent]) runs))
          num (mean (mapv #(get-in % [:M1-legs :uix]) runs))]
      (is (approx= 0.0005 legs {:den den :num num :quotient (/ num den)})
          (str (count runs) " runs, M1 legs"))))
  (testing "the page's `1.0243 ± 3.1824 × 0.009616`: the standard error of
           M1's four run means"
    (is (close-to? (/ (:sd (retake-ci (accepted-only) :M1)) 2.0) 0.009616 0.000002)))
  (testing "the 3:2 start imbalance does not carry M1: the start groups'
           means average to 1.0190, inside 0.9820 – 1.0480"
    (is (approx= {:reagent-subs 0.9993 :uix-subs 1.0387}
                 (into {} (map (fn [[start runs]] [start (mean (mapv :M1 runs))]))
                       (group-by :start retake))))))

(deftest the-fifth-run-was-dropped-for-the-way-its-strata-split
  (testing "run 5's M1 strata point opposite ways across 1.0, so the verdict
           refuses and the driver exits 1"
    (is (= {:start               :reagent-subs
            :reagent-first       {:per-round [1.0345 1.0792 1.0459] :mean 1.0532
                                  :min 1.0345 :max 1.0792 :straddles-1? false
                                  :direction :numerator-slower :n 3}
            :uix-first           {:per-round [0.9749 0.9279 0.8055] :mean 0.9028
                                  :min 0.8055 :max 0.9749 :straddles-1? false
                                  :direction :numerator-faster :n 3}
            :order-balanced-mean 0.978
            :balanced-design?    true
            :strata-overlap?     false
            :magnitude-resolved? false
            :direction-agrees?   false
            :refuse?             true}
           (dissoc (rf.bench.fresco.p0-converge-app/segment-order-verdict (:M1 run-5-per-round) 6 :reagent-subs)
                   :why))))
  (testing "its other three rows overlap and resolve a magnitude: run 5 is a
           run whose M1 sat on parity, not a bad run"
    (is (= {:M2 [false true] :broad [false true] :narrow [false true]}
           (into {} (for [row [:M2 :broad :narrow]]
                      [row ((juxt :refuse? :magnitude-resolved?)
                            (rf.bench.fresco.p0-converge-app/segment-order-verdict (get run-5-per-round row) 6 :reagent-subs))])))))
  (testing "dropping it is outcome-based selection: it moves M1's estimate
           0.9 points away from parity"
    (is (close-to? (- (:threshold (retake-ci (accepted-only) :M1))
                      (:threshold (retake-ci retake :M1)))
                   0.0093 0.0002))))

;; ---------------------------------------------------------------------------
;; The flag is global and the arm is not
;; ---------------------------------------------------------------------------
;;
;; `FRESCO_RATOM=on` is a page-global request; arm presence is decided per
;; row. With no `FRESCO_ONLY` the driver selects all four rows, and `M2`
;; and `narrow` carry no `:reagent-ratom` arm. A record that read the FLAG
;; would divide by a ratio those rows never produce and label them as
;; carrying the arm — and every published run pairs the flag with
;; `FRESCO_ONLY=M1,broad`, so no published run shows it.

(def ^:private flagged-rows
  "What `FRESCO_RATOM=on` with no `FRESCO_ONLY` selects: the driver's
  default row list, all four of them."
  [:M1 :M2 :broad :narrow])

(def ^:private rows-carrying-the-arm
  "And the two of those four whose plan admits the arm."
  #{:M1 :broad})

(deftest the-flagged-default-selection-plants-the-arm-on-two-rows-of-four
  ;; And with the flag OFF no row runs it, so an unflagged invocation is the
  ;; instrument the published rows were measured on.
  (is (= {true rows-carrying-the-arm false #{}}
         (into {} (for [flag [true false]]
                    [flag (set (filter #(some #{:reagent-ratom}
                                              (rf.bench.fresco.p0-converge-app/reagent-segment-arm-ids % flag))
                                       flagged-rows))])))))

(deftest the-leg-is-formed-only-when-every-round-measured-the-arm
  (let [round  (fn [ratom] {:reagent-subs
                            {:ratio (cond-> {:reagent-subs 4.0}
                                      ratom (assoc :reagent-ratom ratom))}})
        leg-of #(rf.bench.fresco.p0-converge-app/ratom-leg (mapv round %))]
    (is (= (repeat 6 (/ 4.0 3.0)) (leg-of (repeat 6 3.0)))
        "every round measured the arm: one leg per round")
    ;; `subs / nil` and `subs / 0` are Infinity in JavaScript rather than an
    ;; error, and a leg over some rounds is a different figure.
    (is (= [nil nil nil] (map leg-of [(repeat 6 nil) [3.0 3.0 3.0 3.0 3.0 nil] (repeat 6 0.0)]))
        "no arm, a partial arm and a zero denominator form no leg")))

(def ^:private synthetic-ms
  "One reading vector per arm id, constant, so every ratio the record
  computes is exact: the floor is 1.0, the denominator arm 4.0, the ratom
  arm 3.0, and the 2x control 2.0 — which is the control's own
  prediction, so it passes."
  {:floor [1.0 1.0 1.0]
   :reagent-subs [4.0 4.0 4.0]
   :reagent-ratom [3.0 3.0 3.0]
   :uix-subs [5.0 5.0 5.0]
   :ctl-2x [2.0 2.0 2.0]})

(defn- flagged-record
  "The published record for `row` under `FRESCO_RATOM=on`, over six rounds
  of that row's ACTUAL flagged arm set, taken from the entry's own plan."
  [row]
  (let [reagent-arms (select-keys synthetic-ms (rf.bench.fresco.p0-converge-app/reagent-segment-arm-ids row true))
        uix-arms     (select-keys synthetic-ms [:floor :uix-subs :ctl-2x])]
    (:record (rf.bench.fresco.p0-converge-app/row-record
               {:row row
                :grade :bar
                :doc "a contract fixture, not a measurement"
                :control {:predicted 2.0 :basis "the 2x control reads twice the floor"}
                :writes-per-sample 1
                :start :reagent-subs}
               (vec (repeat 6 {:reagent-subs reagent-arms :uix-subs uix-arms}))))))

(defn- non-finite
  "Every number anywhere in `x` that is not finite — NaN, Infinity,
  -Infinity — so the whole record is checked, not a few keys."
  [x]
  (let [found (atom [])]
    (walk/postwalk (fn [v]
                     (when (and (number? v) (not (js/isFinite v)))
                       (swap! found conj v))
                     v)
                   x)
    @found))

(defn- arm-facts
  "What a record publishes about the ratom arm."
  [rec]
  {:ratom-arm?        (:ratom-arm? rec)
   :red-zone-arm?     (:ratom-arm? (:red-zone rec))
   :comparability     (some-> (:comparability (:red-zone rec)) string?)
   :reactive-leg      (some-> (:reactive-leg rec) (select-keys [:mean :claim]))
   :ratom-over-floor? (some? (:ratom-over-floor rec))
   :leg-order?        (some? (:reactive-leg-segment-order rec))
   :non-finite        (non-finite rec)})

(deftest the-flagged-default-selection-publishes-a-leg-only-where-the-arm-ran
  ;; A record is per row, so this also answers `FRESCO_RATOM=on
  ;; FRESCO_ONLY=M2,narrow`, the selection nobody publishes from.
  (let [carries {:ratom-arm? true :red-zone-arm? true :comparability true
                 :reactive-leg {:mean 1.3333 :claim :magnitude}
                 :ratom-over-floor? true :leg-order? true :non-finite []}
        lacks   {:ratom-arm? false :red-zone-arm? false :comparability nil
                 :reactive-leg nil
                 :ratom-over-floor? false :leg-order? false :non-finite []}]
    (is (= (zipmap flagged-rows (map #(if (rows-carrying-the-arm %) carries lacks) flagged-rows))
           (zipmap flagged-rows (map (comp arm-facts flagged-record) flagged-rows))))))
