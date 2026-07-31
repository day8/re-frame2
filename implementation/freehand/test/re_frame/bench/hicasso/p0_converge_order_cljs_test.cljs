(ns re-frame.bench.hicasso.p0-converge-order-cljs-test
  "THE SEGMENT-ORDER VERDICT, replayed against the numbers it was built to
  judge (rf2-a4x1o).

  `p0-converge-app/segment-order-verdict` partitions a cross-segment
  figure by which segment ran FIRST in each round — the question
  `lane/guard!` cannot ask, because the guard adjudicates arms INSIDE a
  segment and the red-zone is a ratio ACROSS the seam.

  A gate whose only evidence is the run it shipped with has not been
  tested. These are the PUBLISHED per-round vectors from
  `docs/design/hicasso/studio/p0-converged-witness-set.md`, replayed:
  the run the red-zone table was taken from, and the independent
  four-row reproduction sweep (rf2-rjfz1) taken at main `32cb224d6e`.
  Both are on the page, both are five rounds, and both ran the only
  schedule that existed before rf2-6i0i2 — round 0 led by the Reagent
  segment, alternating — so rounds 0, 2, 4 are Reagent-first and rounds
  1, 3 are UIx-first, in both, and every replay below says so with an
  explicit `:reagent-subs` start.

  What the replay establishes, and it is the whole of rf2-a4x1o's second
  item:

  1. The M1 partition the PR #7268 audit reported is REPRODUCED exactly
     from the published vector — the strata are disjoint, so the
     operative `1.2301` is a mean over a split whose two halves do not
     meet.
  2. The same partition applied to the other three published rows agrees
     with the audit on M2 and broad, and DISAGREES on narrow: the
     CURRENT (batched) narrow row's strata are disjoint too. Only the
     SUPERSEDED unbatched narrow row overlaps.
  3. Across the two runs, WHICH rows split disjointly MOVES — M1 and
     narrow in the published run, broad in the sweep, and no row in
     both. At a 3:2 split a disjoint partition arises in 2 of the
     `C(5,2) = 10` exchangeable assignments, so 3 disjoint rows out of 8
     row-runs is what no order effect at all looks like.
  4. DIRECTION survives everywhere. Every stratum of every row-run
     agrees with its sibling about which side of 1.0 it is on, which is
     what the fail-closed half of the verdict tests — and it never
     fires on any published row.

  ## The balanced ensemble's table, and the hole in it (rf2-6i0i2)

  The PR #7303 audit found that the ten-run counterbalanced ensemble
  published only GROUP MEANS, intervals, ranges and p-values: the ten
  per-run threshold means and the ten per-run `d` values reached no
  committed file, so the central statistics could not be recomputed
  from the repository. This namespace now carries what the repository
  actually has, and it says which is which:

  * RECORDED — run 1, the pre-registered run, whose four six-round
    per-round vectors the studio page prints in full. Its four run
    means and its four `d` values are DERIVED here from those vectors
    rather than copied, so the page's numbers are checked rather than
    restated.
  * NOT RECORDED — runs 2 through 10. Their per-round vectors were
    never committed and the worktree that produced them is gone, so
    they cannot be recovered. [[ensemble-run-means]] holds them as
    explicit `nil`s rather than pretending the table is whole.

  What that leaves checkable is the arithmetic BETWEEN the published
  summaries, and it is not nothing: the 5/5 counterbalance forces the
  threshold to be the mean of the two start-group means, the View 1
  difference to be their difference, and every View 2 ratio and
  interval to be the exponential of a log-scale quantity centred on the
  mean `d`. Those identities are derived below and they hold. What
  cannot be checked without the missing rows is the step from ten runs
  to the group means, and no arrangement of the published summaries
  recovers it — see [[recorded-runs]].

  Pure arithmetic over recorded vectors: no DOM, no clock, no browser,
  so this runs on every runtime."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.set :as set]
            [re-frame.bench.hicasso.p0-converge-app :as app]))

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
  "The unbatched narrow row, struck through on the page and kept here
  because it is the row the audit's `narrow overlaps` reading came from."
  [1.1111 1.2500 1.2500 1.0417 1.1250])

(def ^:private sweep
  "rf2-rjfz1's independent four-row reproduction sweep at `32cb224d6e`."
  {:M1     [1.4242 1.1462 1.3611 1.3214 1.1905]
   :M2     [1.2727 0.8000 1.0000 1.0909 1.0000]
   :broad  [0.5750 0.5263 0.6176 0.5556 0.7353]
   :narrow [1.2528 1.1591 1.1705 1.1507 1.1136]})

(defn- v
  "Replay a PUBLISHED five-round vector. Every published five-round run
  was Reagent-start, and the start is stated rather than defaulted."
  [vs]
  (app/segment-order-verdict vs 5 :reagent-subs))

(defn- close-to?
  "Within `tol`. Every figure below is quoted from the studio page at
  four decimals, and several of the page's own numbers were rounded
  from unrounded readings rather than from the four-decimal vectors it
  prints — so an identity that holds exactly on the raw data can miss
  by a unit in the last place here. `tol` is stated at each call site
  for that reason, never widened silently."
  [a b tol]
  (< (js/Math.abs (- a b)) tol))

(defn- close? [a b] (close-to? a b 0.0002))

;; ---------------------------------------------------------------------------
;; 1. The audit's M1 partition, reproduced from the published vector
;; ---------------------------------------------------------------------------

(deftest the-published-m1-partition-is-the-one-the-audit-reported
  (testing "PR #7268's audit read the M1 red-zone rounds as Reagent-first
           [1.3065 1.2388 1.3538] against UIx-first [1.1417 1.1099],
           disjoint. The verdict must derive exactly that from the
           published vector and nothing else"
    (let [r (v (:M1 published))]
      (is (= [1.3065 1.2388 1.3538] (:per-round (:reagent-first r))))
      (is (= [1.1417 1.1099] (:per-round (:uix-first r))))
      (is (false? (:strata-overlap? r)) "disjoint, as the audit reported")
      (is (close? 1.2997 (:mean (:reagent-first r))))
      (is (close? 1.1258 (:mean (:uix-first r)))
          "the audit's p50 1.1258 for the UIx-first stratum")
      (is (false? (:magnitude-resolved? r))
          "so the row may NOT publish 1.2301 as a threshold")
      (is (close? 1.2128 (:order-balanced-mean r))
          "and the design-unbiased estimator is 1.2128, not 1.2301"))))

(deftest the-m1-direction-survives-the-partition
  (testing "both M1 strata sit wholly above 1.0, so UIx-slower is a
           verdict the partition does not touch — the fail-closed half
           does not fire"
    (let [r (v (:M1 published))]
      (is (= :numerator-slower (:direction (:reagent-first r))))
      (is (= :numerator-slower (:direction (:uix-first r))))
      (is (true? (:direction-agrees? r)))
      (is (false? (:refuse? r))))))

;; ---------------------------------------------------------------------------
;; 2. The same partition on the other three published rows
;; ---------------------------------------------------------------------------

(deftest m2-and-broad-overlap-and-the-current-narrow-row-does-not
  (testing "the audit reported M2, broad AND narrow as overlapping. Two of
           the three reproduce; the CURRENT narrow row is disjoint, and
           only the SUPERSEDED unbatched one overlaps"
    (is (true?  (:strata-overlap? (v (:M2 published)))))
    (is (true?  (:strata-overlap? (v (:broad published)))))
    (is (false? (:strata-overlap? (v (:narrow published))))
        "the batched narrow row's strata are [1.1700-1.2053] against
         [1.0570-1.1515] — DISJOINT, so its 1.1540 is not a threshold
         either")
    (is (true?  (:strata-overlap? (v superseded-narrow)))
        "the superseded unbatched row is the one that overlaps")))

;; ---------------------------------------------------------------------------
;; 3. Which row splits disjointly is not stable across runs
;; ---------------------------------------------------------------------------

(deftest no-row-is-disjoint-in-both-runs
  (testing "if the segment order moved a row's figure, the SAME row would
           split in both runs. None does: M1 and narrow split in the
           published run, broad splits in the sweep, and the intersection
           is empty — which is what a 20%-per-row chance rate looks like"
    (let [disjoint (fn [m] (into #{} (remove #(:strata-overlap? (v (get m %))))
                                 [:M1 :M2 :broad :narrow]))]
      (is (= #{:M1 :narrow} (disjoint published)))
      (is (= #{:broad}      (disjoint sweep)))
      (is (empty? (set/intersection (disjoint published)
                                            (disjoint sweep)))))))

(deftest the-sweep-does-not-reproduce-the-m1-split
  (testing "the reproduction sweep's M1 strata OVERLAP — 1.3253
           [1.1905-1.4242] against 1.2338 [1.1462-1.3214] — so the
           disjointness the published run showed is not a property of the
           M1 row"
    (let [r (v (:M1 sweep))]
      (is (true? (:strata-overlap? r)))
      (is (true? (:magnitude-resolved? r))))))

;; ---------------------------------------------------------------------------
;; 4. Direction is the fail-closed half, and it never fires on real data
;; ---------------------------------------------------------------------------

(deftest direction-agrees-on-every-published-row-of-both-runs
  (testing "eight row-runs, sixteen strata, and no row whose two halves
           point opposite ways across 1.0. The gate is fail-closed and it
           is closed"
    (doseq [[label m] [["published" published] ["sweep" sweep]]
            row       [:M1 :M2 :broad :narrow]]
      (let [r (v (get m row))]
        (is (true? (:direction-agrees? r)) (str label " " row))
        (is (false? (:refuse? r)) (str label " " row))))))

(deftest a-row-whose-halves-point-opposite-ways-is-refused
  (testing "the gate can go red. Reagent-first rounds reading 1.4 and
           UIx-first rounds reading 0.7 is a figure that says `slower`
           when one segment leads and `faster` when the other does — no
           direction to publish, and the run must not"
    (let [r (app/segment-order-verdict [1.40 0.70 1.45 0.72 1.38] 5 :reagent-subs)]
      (is (= :numerator-slower (:direction (:reagent-first r))))
      (is (= :numerator-faster (:direction (:uix-first r))))
      (is (false? (:direction-agrees? r)))
      (is (true? (:refuse? r))))))

;; ---------------------------------------------------------------------------
;; The design, and the estimator that survives it
;; ---------------------------------------------------------------------------

(deftest five-rounds-cannot-balance-two-orders
  (testing "an odd round count splits 3:2, so the raw mean over-weights
           whichever order got the extra round. The verdict says so, and
           publishes the mean of the two stratum means beside it"
    (let [r (v (:M1 published))]
      (is (false? (:balanced-design? r)))
      (is (= 3 (:n (:reagent-first r))))
      (is (= 2 (:n (:uix-first r))))))
  (testing "at an EVEN round count the two estimators coincide by
           construction — the repair rf2-6i0i2 took, and the design the
           entry now runs"
    (let [vs [1.10 1.20 1.30 1.40 1.50 1.60]
          r  (app/segment-order-verdict vs 6 :reagent-subs)]
      (is (true? (:balanced-design? r)))
      (is (= 3 (:n (:reagent-first r))))
      (is (= 3 (:n (:uix-first r))))
      (is (close? 1.35 (:order-balanced-mean r))
          "= the raw mean of the six rounds, because 3:3 is balanced"))))

;; ---------------------------------------------------------------------------
;; The start is a parameter, and the strata follow the schedule that ran
;; ---------------------------------------------------------------------------

(deftest the-strata-are-keyed-by-the-segment-that-actually-led
  (testing "flipping the start swaps which index-parity lands in which
           stratum: a UIx-start run's even rounds ARE its UIx-first
           rounds. Before rf2-6i0i2 the start was constant, so `Reagent
           first` and `rounds 0, 2, 4` were the same set in every run —
           which is exactly the confound the counterbalanced runs exist
           to break"
    (let [vs [1.30 1.10 1.25 1.12 1.35 1.11]
          r  (app/segment-order-verdict vs 6 :reagent-subs)
          u  (app/segment-order-verdict vs 6 :uix-subs)]
      (is (= :reagent-subs (:start r)))
      (is (= :uix-subs (:start u)))
      (is (= [1.30 1.25 1.35] (:per-round (:reagent-first r))))
      (is (= [1.10 1.12 1.11] (:per-round (:uix-first r))))
      (is (= (:per-round (:reagent-first r)) (:per-round (:uix-first u)))
          "the same readings land in the OPPOSITE stratum under the
           flipped schedule")
      (is (= (:per-round (:uix-first r)) (:per-round (:reagent-first u))))))
  (testing "the refusal logic follows the strata, not the index parity: a
           vector that refuses under one start refuses under the other
           with the directions exchanged"
    (let [vs [1.40 0.70 1.45 0.72 1.38 0.69]
          r  (app/segment-order-verdict vs 6 :reagent-subs)
          u  (app/segment-order-verdict vs 6 :uix-subs)]
      (is (= :numerator-slower (:direction (:reagent-first r))))
      (is (= :numerator-faster (:direction (:reagent-first u))))
      (is (true? (:refuse? r)))
      (is (true? (:refuse? u))))))

;; ---------------------------------------------------------------------------
;; The reactive leg, replayed (rf2-2rtt6.21)
;; ---------------------------------------------------------------------------

(def ^:private leg
  "The PUBLISHED per-round `reagent-subs / reagent-ratom` vectors from the
  studio page's `The reactive leg, from a second author` section — six
  runs a row, starting segment counterbalanced 3/3.

  These are the second author's, and they are replayed for the reason the
  red-zone vectors above are: a verdict that has only ever seen the run it
  shipped with is a verdict nobody can check. The leg does NOT cross the
  segment seam — both its terms are Reagent arms measured in the same
  segment of the same round — and it is adjudicated by the same partition
  anyway, because the Reagent segment leads half the rounds and follows
  the other half, and a lower bound that reads differently for which is
  not a lower bound."
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
                         (app/segment-order-verdict vs 6 start "the reactive leg's"))
                       (get leg row)))

(deftest every-published-leg-run-resolves-a-magnitude
  (testing "twelve row-runs, twenty-four strata: the strata OVERLAP on all
           twelve, so every one is entitled to publish a magnitude, and no
           row-run is reduced to a direction. That is what the studio
           page's `magnitude-resolved? true on 12 of 12` asserts, derived
           from the vectors rather than transcribed beside them"
    (doseq [row [:M1 :broad] r (legs row)]
      (is (true? (:strata-overlap? r)) (str row " " (:start r)))
      (is (true? (:magnitude-resolved? r)) (str row " " (:start r)))
      (is (true? (:balanced-design? r)) "six rounds, so 3:3")
      (is (= 3 (:n (:reagent-first r))))
      (is (= 3 (:n (:uix-first r)))))))

(deftest the-leg-is-above-1-in-every-stratum-of-every-run
  (testing "the DIRECTION is what a second author corroborates, and it is
           unanimous: both strata of all twelve row-runs sit wholly above
           1.0, so `subs costs more than a bare cursor` is a verdict no
           partition of this ensemble touches"
    (doseq [row [:M1 :broad] r (legs row)]
      (is (= :numerator-slower (:direction (:reagent-first r))) (str row))
      (is (= :numerator-slower (:direction (:uix-first r))) (str row))
      (is (false? (:refuse? r)) (str row)))))

(deftest the-leg-does-not-reproduce-the-first-authors-magnitude
  (testing "and the MAGNITUDE is not corroborated, which is the finding
           rf2-2rtt6.21 exists to surface. rf2-2rtt6.2 publishes 1.218 /
           1.216 / 1.213 on M1 and 2.008 / 1.965 / 2.073 on broad; every
           one of these run means sits above every one of those, and the
           lowest M1 round of all six runs still clears the highest of the
           first author's three means"
    (let [means (fn [row] (map :order-balanced-mean (legs row)))]
      (is (every? #(> % 1.30) (means :M1))
          "every M1 run mean is above 1.30, where the first author's three
           are 1.213-1.218")
      (is (every? #(> % 2.30) (means :broad))
          "every broad run mean is above 2.30, where the first author's
           three are 1.965-2.073")
      (is (> (apply min (mapcat :vs (:M1 leg))) 1.24)
          "and the LOWEST single round across all six M1 runs, 1.2500,
           still sits above the first author's re-run maxima of 1.241 and
           1.273 — a disagreement at the round level and not only at the
           mean")))
  (testing "the same statement from the other side: replay the first
           author's own published M1 leg as if it were a six-round vector
           and it does not reach this ensemble's floor"
    (is (< 1.218 (apply min (mapcat :vs (:M1 leg))))
        "1.218 is below every round the second author measured")))

;; ---------------------------------------------------------------------------
;; THE BALANCED ENSEMBLE'S OBSERVATION TABLE (rf2-6i0i2, the PR #7303 audit)
;;
;; Ten independently launched six-round runs, the starting segment
;; counterbalanced five and five, one statistic per run per row. The studio
;; page publishes the SUMMARIES of that table; the table itself was never
;; committed. What follows is the table as far as the repository has it,
;; the derivation of every summary that can be recomputed from what IS
;; there, and an explicit `nil` everywhere it cannot.
;; ---------------------------------------------------------------------------

(def ^:private rows [:M1 :M2 :broad :narrow])

(def ^:private run-1
  "RUN 1's per-round `uix-subs ÷ reagent-subs` vectors, six rounds,
  Reagent-start — the one run of the ten whose readings reached a
  committed file. The commit that balanced the design nominated it as
  the re-publication run BEFORE the ensemble ran, which is why its
  vectors are on the page at all; the other nine were summarised and
  discarded."
  {:M1     [1.4286 0.9529 1.2681 1.2186 1.1378 1.2892]
   :M2     [0.8571 1.3846 1.0714 1.0000 1.0794 0.8889]
   :broad  [0.6190 0.6328 0.6144 0.5532 0.8485 0.7292]
   :narrow [1.2575 1.1161 1.0999 1.3602 1.0568 1.0346]})

(def ^:private recorded-runs
  "How many of the ten runs have their per-run figures in the
  repository. ONE. This is the audit's finding stated as a number the
  suite reads: nine tenths of the ensemble's observations exist only in
  logs that were never committed, on a worktree that no longer exists.

  Raise this — and fill [[ensemble-run-means]] — when an ensemble
  records its table. It is deliberately asserted below so that the gap
  is a thing the suite states rather than a thing a reader has to
  notice."
  1)

(def ^:private ensemble-run-means
  "THE 10x4 OBSERVATION TABLE. One row per launched run, one column per
  witness, each cell that run's six per-round readings — the vector its
  threshold mean and its `d` are both derived from. `:start` is the
  segment that led round 0; the ten alternate, so the odd-numbered
  launches are the Reagent-start group.

  `nil` means NOT RECORDED — not zero, not missing at random, and not
  reconstructible: nine sets of six-round vectors were summarised into
  the group means the page prints and then lost. The published group
  means, threshold, interval and range are the only trace they left,
  and those are six constraints on nine unknowns per column."
  [{:run 1  :start :reagent-subs :M1 (:M1 run-1) :M2 (:M2 run-1)
    :broad (:broad run-1) :narrow (:narrow run-1)}
   {:run 2  :start :uix-subs     :M1 nil :M2 nil :broad nil :narrow nil}
   {:run 3  :start :reagent-subs :M1 nil :M2 nil :broad nil :narrow nil}
   {:run 4  :start :uix-subs     :M1 nil :M2 nil :broad nil :narrow nil}
   {:run 5  :start :reagent-subs :M1 nil :M2 nil :broad nil :narrow nil}
   {:run 6  :start :uix-subs     :M1 nil :M2 nil :broad nil :narrow nil}
   {:run 7  :start :reagent-subs :M1 nil :M2 nil :broad nil :narrow nil}
   {:run 8  :start :uix-subs     :M1 nil :M2 nil :broad nil :narrow nil}
   {:run 9  :start :reagent-subs :M1 nil :M2 nil :broad nil :narrow nil}
   {:run 10 :start :uix-subs     :M1 nil :M2 nil :broad nil :narrow nil}])

(def ^:private view-1
  "The page's View 1 table: the two start-group means over five runs
  each, their difference, and the threshold the row publishes."
  {:M1     {:reagent-start 1.2276 :uix-start 1.2344 :difference -0.0068 :threshold 1.2310}
   :M2     {:reagent-start 1.0461 :uix-start 1.0740 :difference -0.0280 :threshold 1.0601}
   :broad  {:reagent-start 0.6295 :uix-start 0.6288 :difference +0.0007 :threshold 0.6291}
   :narrow {:reagent-start 1.1754 :uix-start 1.1755 :difference -0.0001 :threshold 1.1754}})

(def ^:private view-2
  "The page's View 2 table: the mean of the ten per-run `d` values, the
  same figure as a ratio, and the 95% interval printed as ratios."
  {:M1        {:mean-d +0.0357 :ratio 1.036 :lo 0.979 :hi 1.097}
   :M2        {:mean-d -0.0837 :ratio 0.920 :lo 0.795 :hi 1.065}
   :broad     {:mean-d -0.0388 :ratio 0.962 :lo 0.887 :hi 1.044}
   :narrow    {:mean-d +0.0070 :ratio 1.007 :lo 0.977 :hi 1.038}
   :composite {:mean-d -0.0200 :ratio 0.980 :lo 0.934 :hi 1.029}})

(def ^:private components
  "The page's order/temporal decomposition. Under a counterbalanced
  start the average of the two start groups isolates the ORDER term and
  half their difference isolates the TEMPORAL one."
  {:M1        {:order +0.0357 :temporal -0.0212}
   :M2        {:order -0.0837 :temporal +0.0632}
   :broad     {:order -0.0388 :temporal -0.0011}
   :narrow    {:order +0.0070 :temporal -0.0063}
   :composite {:order -0.0200 :temporal +0.0086}})

(defn- mean [xs] (/ (reduce + 0.0 xs) (count xs)))

(defn- d-of
  "THE PER-RUN STATISTIC, defined once and derived nowhere else:

      d = ln( mean of the Reagent-first stratum / mean of the UIx-first stratum )

  positive when the figure reads higher with the Reagent segment
  leading, which is the direction the withdrawn claim asserted. The
  strata come from the verdict itself, so this and the published
  partition cannot drift apart."
  [vs start]
  (let [r (app/segment-order-verdict vs 6 start)]
    (js/Math.log (/ (:mean (:reagent-first r)) (:mean (:uix-first r))))))

;; ---------------------------------------------------------------------------
;; What the repository holds of the table, stated rather than implied
;; ---------------------------------------------------------------------------

(deftest nine-of-the-ten-runs-were-never-recorded
  (testing "the audit's completeness finding, as arithmetic the suite
           reads. One run of ten has its readings in a committed file;
           the other nine exist only as the group means they were
           folded into. Filling them needs a fresh ensemble that commits
           its table, never a reconstruction from the summaries"
    (let [recorded (filter #(some? (:M1 %)) ensemble-run-means)]
      (is (= 10 (count ensemble-run-means)) "ten launched, ten in the table")
      (is (= recorded-runs (count recorded)))
      (is (= [1] (mapv :run recorded)) "and it is the pre-registered run")
      (is (= 5 (count (filter #(= :reagent-subs (:start %)) ensemble-run-means)))
          "the start is counterbalanced 5/5 across the ten launches")
      (is (= 5 (count (filter #(= :uix-subs (:start %)) ensemble-run-means)))))))

(deftest the-start-label-is-the-launch-parity-and-that-is-a-confound
  (testing "the ten labels were ALTERNATED, not drawn at random, so
           `Reagent-start` and `odd-numbered launch` name the same five
           runs. A drift across the session would therefore reproduce a
           start effect exactly — the same confound the five-round
           design had between segment order and round parity, moved up
           to the run level. It is why the page's permutation p-values
           are exact only under an assumption, and the assumption is
           stated there rather than left to the reader"
    (is (= (mapv :run (filter #(= :reagent-subs (:start %)) ensemble-run-means))
           (filterv odd? (mapv :run ensemble-run-means))))))

;; ---------------------------------------------------------------------------
;; Run 1 is derived from its vectors, not copied from the page
;; ---------------------------------------------------------------------------

(deftest run-1s-published-means-are-derivable-from-its-vectors
  (testing "the four run means the page prints for the pre-registered
           run fall out of the six-round vectors it prints beside them"
    (doseq [[row published] [[:M1 1.2159] [:M2 1.0469] [:broad 0.6662] [:narrow 1.1542]]]
      (is (close? published (mean (get run-1 row))) (name row)))))

(deftest run-1s-partition-is-the-one-the-page-publishes
  (testing "the stratum table the page prints for the pre-registered run
           — Reagent-first mean [min–max] against UIx-first — is what
           the verdict derives from the same vectors, and all four rows
           overlap, so all four were entitled to publish a magnitude"
    (doseq [[row rf-mean rf-min rf-max uf-mean uf-min uf-max]
            [[:M1     1.2782 1.1378 1.4286 1.1536 0.9529 1.2892]
             [:M2     1.0027 0.8571 1.0794 1.0912 0.8889 1.3846]
             [:broad  0.6940 0.6144 0.8485 0.6384 0.5532 0.7292]
             [:narrow 1.1381 1.0568 1.2575 1.1703 1.0346 1.3602]]]
      (let [r  (app/segment-order-verdict (get run-1 row) 6 :reagent-subs)
            rf (:reagent-first r)
            uf (:uix-first r)]
        (is (true? (:balanced-design? r)) (name row))
        (is (= 3 (:n rf)) (name row))
        (is (= 3 (:n uf)) (name row))
        ;; M2's Reagent-first mean is the one page figure that misses at
        ;; the fourth decimal: 1.0027 there against 1.00263 from the
        ;; four-decimal vector, because the page rounded from the raw
        ;; readings. Recorded here rather than papered over.
        (is (close-to? rf-mean (:mean rf) 0.0002) (name row))
        (is (close? rf-min (:min rf)) (name row))
        (is (close? rf-max (:max rf)) (name row))
        (is (close-to? uf-mean (:mean uf) 0.0002) (name row))
        (is (close? uf-min (:min uf)) (name row))
        (is (close? uf-max (:max uf)) (name row))
        (is (true? (:strata-overlap? r)) (name row))
        (is (true? (:magnitude-resolved? r)) (name row))))))

(deftest run-1s-d-values-are-one-tenth-of-view-2s-input
  (testing "the four `d` values of run 1, derived from its vectors. They
           are ONE of the ten rows View 2 averages, and they are printed
           here because a mean whose summands are nowhere in the
           repository is not a checkable mean — one summand is better
           than none, and it is also the demonstration that a single run
           says very little: run 1's broad `d` is +0.0835 where the
           ensemble mean is −0.0388, opposite in sign"
    (doseq [[row expected] [[:M1 +0.1026] [:M2 -0.0846] [:broad +0.0835] [:narrow -0.0279]]]
      (is (close-to? expected (d-of (get run-1 row) :reagent-subs) 0.0002) (name row)))))

;; ---------------------------------------------------------------------------
;; The identities the 5/5 counterbalance forces, which ARE checkable
;; ---------------------------------------------------------------------------

(deftest the-threshold-is-the-mean-of-the-two-start-group-means
  (testing "five runs each side means the grand mean over ten IS the
           average of the two group means — so the threshold column and
           the View 1 columns are one table, and a typo in either shows
           up here"
    (doseq [row rows]
      (let [{:keys [reagent-start uix-start threshold]} (get view-1 row)]
        (is (close? threshold (/ (+ reagent-start uix-start) 2.0)) (name row))))))

(deftest view-1s-difference-column-is-the-difference
  (testing "the start-group contrast, recomputed. M2 misses by 0.0001
           because the page differenced the unrounded group means"
    (doseq [row rows]
      (let [{:keys [reagent-start uix-start difference]} (get view-1 row)]
        (is (close-to? difference (- reagent-start uix-start) 0.0002) (name row))))))

(deftest view-2s-ratio-column-is-the-exponential-of-mean-d
  (testing "`d` is a log ratio, so the `as a ratio` column is exp(mean
           d) and nothing else. Three decimals on the page, so 0.001"
    (doseq [[row {:keys [mean-d ratio]}] view-2]
      (is (close-to? ratio (js/Math.exp mean-d) 0.001) (name row)))))

(deftest view-2s-interval-is-centred-on-mean-d-in-log-space
  (testing "the 95% interval is printed as ratios but computed on `d`,
           so the midpoint of its two logarithms must be the mean `d`
           itself. This is the check that catches an interval pasted
           against the wrong row"
    (doseq [[row {:keys [mean-d lo hi]}] view-2]
      (is (close-to? mean-d
                     (/ (+ (js/Math.log lo) (js/Math.log hi)) 2.0)
                     0.0006)
          (name row)))))

(deftest the-composite-is-the-mean-over-the-four-rows
  (testing "the pre-registered statistic was fixed in advance as the
           mean over the four rows — never as four trials — and both
           components obey it"
    (is (close-to? (:order (:composite components))
                   (mean (map #(:order (get components %)) rows))
                   0.0002))
    (is (close-to? (:temporal (:composite components))
                   (mean (map #(:temporal (get components %)) rows))
                   0.0002))
    (is (close-to? (:mean-d (:composite view-2))
                   (mean (map #(:mean-d (get view-2 %)) rows))
                   0.0002)
        "and View 2's composite row is the same mean of the same four
         numbers, so the two tables cannot disagree in silence")))

(deftest the-order-component-is-view-2s-mean-d
  (testing "the decomposition's ORDER column is not a second estimate:
           averaging the two start groups is what the paired `d` already
           does once the start is counterbalanced, so the two columns
           are the same number and the page prints both only because
           they answer differently-worded questions"
    (doseq [row rows]
      (is (close? (:mean-d (get view-2 row)) (:order (get components row))) (name row)))))
