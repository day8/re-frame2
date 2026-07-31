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

  ## The balanced ensemble's observation table (rf2-6i0i2)

  The PR #7303 audit found that the ten-run counterbalanced ensemble
  published only GROUP MEANS, intervals, ranges and p-values: the ten
  per-run threshold means and the ten per-run `d` values reached no
  committed file, so the central statistics could not be recomputed
  from the repository.

  [[ensemble]] is that table, whole — all forty cells, recovered from
  the ten runs' own console logs (see its docstring for the provenance,
  which matters: recovered, not re-run). Everything the studio page
  publishes about the ensemble is now DERIVED here from those forty
  cells and checked against the page's claims, which are transcribed
  into [[view-1]], [[view-1-p]], [[view-2]], [[components]] and
  [[published-threshold]] purely so that the derivation has something
  to be checked against.

  WHAT REPRODUCES: every point estimate — run means and their spread,
  both start-group means, the difference, the threshold, mean `d`, the
  ratio, the order and temporal components, the composite — and BOTH
  p columns, the 252-relabelling permutation test and the 1024-assignment
  sign-flip test, which the audit correctly said could not be checked
  and now can. So do the prose counts: 37 of 40 strata overlapping, 23
  of 40 Reagent-first-higher, 59 of 60 M1 rounds above 1.0.

  WHAT DOES NOT: the INTERVALS. Every one of them is about 2% wider
  than the data supports, because a t multiplier for EIGHT degrees of
  freedom was used where a mean of ten needs NINE. The error is
  conservative and changes no verdict, but it is real and it is
  reported rather than absorbed — see
  [[the-published-intervals-used-eight-degrees-of-freedom-where-nine-is-right]]
  and [[the-corrected-nine-degree-intervals-change-no-verdict]].

  Pure arithmetic over recorded vectors: no DOM, no clock, no browser,
  so this runs on every runtime."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.set :as set]
            [clojure.walk :as walk]
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

(def ^:private first-author
  "rf2-2rtt6.2's publication run and rf2-2rtt6.17's two re-runs of its OWN
  `:reagent-ratom` arm — the run mean and the per-round range of each,
  exactly as the studio page tabulates them beside this ensemble.

  Kept as DATA rather than as constants inside an assertion, so that what
  the tests below compare is two published sets against each other and
  not a number somebody typed into a threshold."
  {:M1    [{:mean 1.218 :min 1.122 :max 1.310}
           {:mean 1.216 :min 1.185 :max 1.241}
           {:mean 1.213 :min 1.093 :max 1.273}]
   :broad [{:mean 2.008 :min 1.938 :max 2.100}
           {:mean 1.965 :min 1.875 :max 2.000}
           {:mean 2.073 :min 2.000 :max 2.167}]})

(deftest the-leg-does-not-reproduce-the-first-authors-magnitude
  (testing "and the MAGNITUDE is not corroborated, which is the finding
           rf2-2rtt6.21 exists to surface. rf2-2rtt6.2 publishes 1.218 /
           1.216 / 1.213 on M1 and 2.008 / 1.965 / 2.073 on broad, and
           every one of these RUN MEANS sits above every one of those.
           That is the separation the studio page claims, and it is
           claimed at the run level because that is the level it holds at"
    (let [means (fn [row] (map :order-balanced-mean (legs row)))]
      (is (every? #(> % 1.30) (means :M1))
          "every M1 run mean is above 1.30, where the first author's three
           are 1.213-1.218")
      (is (every? #(> % 2.30) (means :broad))
          "every broad run mean is above 2.30, where the first author's
           three are 1.965-2.073")
      (doseq [row [:M1 :broad]]
        (is (> (apply min (means row))
               (apply max (map :mean (get first-author row))))
            (str row ": the LOWEST of the second author's six run means "
                 "must clear the HIGHEST of the first author's three"))))))

(deftest the-round-ranges-overlap-so-the-separation-is-not-a-round-level-claim
  (testing "THE CORRECTION the PR #7310 audit required (rf2-2rtt6.21). The
           replay above used to carry a third assertion —
           `min(second-author round) > 1.24` — under a message reading
           `1.2500 still sits above the first author's re-run maxima of
           1.241 AND 1.273`. It does not: 1.2500 is below 1.273, so the
           message made a FALSE round-level claim look pinned by a test
           that was only ever pinning 1.24.

           The round ranges OVERLAP, which is what the studio page has
           always said — `overlap at the edges, 1.1667 - 1.3175 against
           1.2500 - 1.4822, while the run means are disjoint`. So the
           round-level reading is pinned FALSE here rather than merely
           deleted: a later hand that reinstates it reds this test"
    (doseq [row [:M1 :broad]]
      (let [lowest-second (apply min (mapcat :vs (get leg row)))
            highest-first (apply max (map :max (get first-author row)))]
        (is (< lowest-second highest-first)
            (str row ": the lowest single round of the second author's six "
                 "runs sits BELOW the highest round the first author "
                 "published, so the two ensembles are NOT separated round "
                 "by round — only run mean by run mean"))))))

;; ---------------------------------------------------------------------------
;; THE BALANCED ENSEMBLE'S OBSERVATION TABLE (rf2-6i0i2, the PR #7303 audit)
;;
;; Ten independently launched six-round runs, the starting segment
;; counterbalanced five and five. The studio page published the SUMMARIES of
;; this table and committed nothing behind them, which is what the audit
;; found. The table itself is below — all forty cells — so every figure in
;; both views is now derived here rather than asserted there.
;;
;; PROVENANCE, because recovered data is not the same as measured data and
;; must not be passed off as it. These are the CONSOLE LOGS OF THE TEN RUNS
;; THEMSELVES, taken at the ensemble's landed anchor and recovered from an
;; out-of-tree backup after the producing worktree was reaped. They are NOT a
;; re-run: nothing here was measured again, no browser was opened, and the
;; machine has since moved. Each cell is the `:threshold :per-round` vector
;; of that run's `red-zone` record, transcribed by script rather than by
;; hand. The pilot runs that share the backup directory (`run1..run6`,
;; `runA1..runA3`) are NOT this ensemble and are deliberately absent.
;; ---------------------------------------------------------------------------

(def ^:private rows [:M1 :M2 :broad :narrow])

(def ^:private ensemble
  "THE 10x4 OBSERVATION TABLE, complete. One entry per launched run, one
  vector per witness: that run's six per-round `uix-subs ÷ reagent-subs`
  readings, each floor-normalised in its own round and segment. `:start`
  is the segment that led round 0.

  Everything the studio page publishes about the ensemble is derived
  from exactly these numbers — run means, group means, thresholds, `d`,
  both *p* columns, the components and the composite. Nothing is
  transcribed from the page except the page's own claims, which live in
  [[view-1]], [[view-2]] and [[components]] so that the derivation can
  be checked AGAINST them.

  The vectors are the instrument's four-decimal output. The instrument
  rounds a mean from UNROUNDED readings while rounding each round
  separately for display, so a mean re-derived from these vectors can
  differ from the instrument's own in the fourth decimal. It never
  exceeded 0.0006 on any figure below, and the tolerances say so."
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
  "The page's order/temporal decomposition and the permutation *p* on the
  start-group difference of `d`. Under a counterbalanced start the
  average of the two start groups isolates the ORDER term and half their
  difference isolates the TEMPORAL one."
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
  interval on the mean of TEN run means (`n − 1`)."
  2.262157)

(def ^:private t-8
  "Student's t, 0.975, EIGHT degrees of freedom. Correct for the
  two-sample five-against-five contrast the `resolution limit` column
  reports, and — see
  [[the-published-intervals-used-eight-degrees-of-freedom-where-nine-is-right]]
  — the multiplier the page also used for its one-sample intervals,
  which is an error."
  2.306004)

(defn- run-mean [run row] (mean (get run row)))

(defn- d-of
  "THE PER-RUN STATISTIC, defined once and derived nowhere else:

      d = ln( mean of the Reagent-first stratum / mean of the UIx-first stratum )

  positive when the figure reads higher with the Reagent segment
  leading, which is the direction the withdrawn claim asserted.

  The PARTITION comes from `segment-order-verdict`, so this and the
  published strata cannot drift apart — but the two stratum means are
  averaged HERE, from the readings, rather than taken from the verdict's
  own `:mean`. The verdict rounds its means to four decimals for
  display, and on the broad row that rounding is load-bearing: a
  sign-flip *p* moves in steps of 1/1024 = 0.00098, and the fourth
  decimal is enough to carry exactly one of the 1024 assignments across
  the observed mean. Rounded strata give broad 878/1024 = 0.8574; the
  readings give 877/1024 = 0.8564, which is the 0.856 the page
  publishes. A displayed number is not an input."
  [vs start]
  (let [r (app/segment-order-verdict vs 6 start)]
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
  "Every way to choose `k` indices from `[start, n)`, ascending. Called
  once, as C(10,5) = 252, and deliberately nothing more general."
  [start n k]
  (if (zero? k)
    [[]]
    (mapcat (fn [i] (map #(cons i %) (combinations-from (inc i) n (dec k))))
            (range start (inc (- n k))))))

(defn- permutation-p
  "EXACT two-sided permutation *p* on the difference between the two
  start groups: enumerate all C(10,5) = 252 relabellings and count those
  whose |difference| is at least the observed one.

  Exact GIVEN the relabelling set — which is not the same as exact by
  randomisation, because the ten starts were alternated rather than
  drawn. The studio page states that assumption; this only computes the
  number."
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
  "EXACT one-sided sign-flip *p* over all 2^10 = 1024 sign assignments,
  in the positive direction the withdrawn claim named. Exact only under
  sign symmetry of `d` under the null — again an assumption, stated on
  the page, not established by the design."
  [ds]
  (let [obs (mean ds)
        hits (count (filter (fn [m]
                              (>= (mean (map-indexed
                                          (fn [i d] (if (bit-test m i) (- d) d))
                                          ds))
                                  (- obs 1e-12)))
                            (range 1024)))]
    (/ hits 1024.0)))


;; ---------------------------------------------------------------------------
;; The table is whole, and it is the ensemble the page describes
;; ---------------------------------------------------------------------------

(deftest the-observation-table-is-complete-and-counterbalanced
  (testing "forty cells, ten runs, six rounds each, and the start
           counterbalanced five and five — the design the page claims,
           checked against the data rather than asserted beside it"
    (is (= 10 (count ensemble)) "ten launched, ten in the table")
    (is (= (range 1 11) (map :run ensemble)))
    (is (= 5 (count (filter reagent-start? ensemble))))
    (is (= 5 (count (remove reagent-start? ensemble))))
    (doseq [run ensemble row rows]
      (is (= 6 (count (get run row)))
          (str "run " (:run run) " " (name row) " has six rounds"))
      (is (every? pos? (get run row))
          (str "run " (:run run) " " (name row) " is all positive ratios")))))

(deftest the-start-label-is-the-launch-parity-and-that-is-a-confound
  (testing "the ten labels were ALTERNATED, not drawn at random, so
           `Reagent-start` and `odd-numbered launch` name the same five
           runs. A drift across the session would therefore reproduce a
           start effect exactly — the same confound the five-round
           design had between segment order and round parity, moved up
           to the run level. It is why the permutation and sign-flip
           p-values below are exact only under an assumption, and the
           page states the assumption rather than leaving it to the
           reader"
    (is (= (mapv :run (filter reagent-start? ensemble))
           (filterv odd? (mapv :run ensemble))))))

(deftest run-1-is-the-pre-registered-run-the-page-prints
  (testing "the page prints one run's vectors in full — the run the
           design nominated in advance — and they are this table's first
           entry. That is the join between the recovered logs and what
           was already published, and it is what identifies the backup
           as THIS ensemble rather than one of the pilots beside it"
    (let [r1 (first ensemble)]
      (is (= 1 (:run r1)))
      (is (= :reagent-subs (:start r1)))
      (is (= [1.4286 0.9529 1.2681 1.2186 1.1378 1.2892] (:M1 r1)))
      (is (= [0.8571 1.3846 1.0714 1.0000 1.0794 0.8889] (:M2 r1)))
      (is (= [0.6190 0.6328 0.6144 0.5532 0.8485 0.7292] (:broad r1)))
      (is (= [1.2575 1.1161 1.0999 1.3602 1.0568 1.0346] (:narrow r1))))
    (doseq [[row published] [[:M1 1.2159] [:M2 1.0469] [:broad 0.6662] [:narrow 1.1542]]]
      (is (close? published (run-mean (first ensemble) row)) (name row)))))

;; ---------------------------------------------------------------------------
;; Every published figure, re-derived from the forty cells
;; ---------------------------------------------------------------------------

(deftest the-run-mean-spread-reproduces
  (testing "the RED-ZONE table's `run means (10)` column is the min and
           max of this table's ten run means, per row. Four ranges, eight
           endpoints, all from the data"
    (doseq [row rows]
      (let [t (thresholds row)
            {:keys [run-min run-max]} (get published-threshold row)]
        (is (close? run-min (apply min t)) (name row))
        (is (close? run-max (apply max t)) (name row))))))

(deftest the-threshold-is-the-mean-of-the-ten-run-means
  (testing "and, because the counterbalance is 5/5, equally the average
           of the two start-group means — so the RED-ZONE table and View
           1 are one table and cannot drift apart"
    (doseq [row rows]
      (let [t (thresholds row)
            [rs us] (by-start t)
            {:keys [threshold]} (get published-threshold row)]
        (is (close? threshold (mean t)) (name row))
        (is (close? threshold (/ (+ (mean rs) (mean us)) 2.0)) (name row))))))

(deftest view-1s-group-means-and-difference-reproduce
  (testing "the five Reagent-start runs against the five UIx-start runs,
           and the difference column that contrasts them"
    (doseq [row rows]
      (let [[rs us] (by-start (thresholds row))
            {:keys [reagent-start uix-start difference]} (get view-1 row)]
        (is (close? reagent-start (mean rs)) (name row))
        (is (close? uix-start (mean us)) (name row))
        (is (close? difference (- (mean rs) (mean us))) (name row))))))

(deftest view-1s-permutation-p-reproduces
  (testing "THE FIRST OF THE TWO p COLUMNS THE AUDIT COULD NOT CHECK.
           All 252 relabellings of the ten runs into two fives,
           enumerated, counting those whose group difference is at least
           the observed one in absolute value. The page publishes 0.770 /
           0.611 / 0.984 / 0.992 and the data yields them"
    (doseq [row rows]
      (is (close-to? (:p (get view-1-p row)) (permutation-p (thresholds row)) 0.0006)
          (name row)))))

(deftest view-1s-resolution-limit-reproduces
  (testing "the half-width of the 95% interval on the start-group
           difference — a genuine two-sample five-against-five contrast,
           so EIGHT degrees of freedom is correct here. It reproduces
           exactly, which is what makes the one-sample intervals'
           multiplier diagnosable below"
    (doseq [row rows]
      (let [[rs us] (by-start (thresholds row))
            pooled  (js/Math.sqrt (/ (+ (* 4 (js/Math.pow (sample-sd rs) 2))
                                        (* 4 (js/Math.pow (sample-sd us) 2)))
                                     8))
            limit   (* t-8 pooled (js/Math.sqrt (/ 2.0 5)))]
        (is (close-to? (:limit (get view-1-p row)) limit 0.0006) (name row))))))

(deftest view-2s-mean-d-ratio-and-counts-reproduce
  (testing "one `d` per run per row, averaged; the `as a ratio` column is
           exp of it; and the `positive` column counts the runs whose `d`
           is above zero"
    (doseq [row rows]
      (let [d (d-values row)
            {:keys [mean-d ratio positive]} (get view-2 row)]
        (is (close? mean-d (mean d)) (name row))
        (is (close-to? ratio (js/Math.exp (mean d)) 0.0006) (name row))
        (is (= positive (count (filter pos? d))) (name row))))))

(deftest view-2s-sign-flip-p-reproduces
  (testing "THE SECOND p COLUMN THE AUDIT COULD NOT CHECK. All 1024 sign
           assignments, one-sided in the direction the withdrawn claim
           named. The page publishes 0.084 / 0.889 / 0.856 / 0.302 and
           the data yields them — including M1's 0.084, the largest lean
           on the page and still not significant"
    (doseq [row rows]
      (is (close-to? (:p (get view-2 row)) (sign-flip-p (d-values row)) 0.0006)
          (name row)))))

(deftest the-components-and-their-permutation-p-reproduce
  (testing "averaging the two start groups of `d` isolates the ORDER
           term; half their difference isolates the TEMPORAL one; and
           the last column is the same 252-relabelling test applied to
           `d` rather than to the threshold means"
    (doseq [row rows]
      (let [d (d-values row)
            [rs us] (by-start d)
            {:keys [order temporal p]} (get components row)]
        (is (close? order (/ (+ (mean rs) (mean us)) 2.0)) (name row))
        (is (close? temporal (/ (- (mean rs) (mean us)) 2.0)) (name row))
        (is (close-to? p (permutation-p d) 0.0006) (name row))))))

(deftest the-composite-is-the-per-run-mean-over-the-four-rows
  (testing "the pre-registered statistic: per RUN, the mean of that run's
           four `d` values — never four trials pooled. Its mean, its
           ratio, its sign-flip p, its positive count and both components
           all reproduce"
    (let [ds   (into {} (map (fn [r] [r (d-values r)])) rows)
          comp (mapv (fn [i] (mean (map #(nth (get ds %) i) rows))) (range 10))
          [rs us] (by-start comp)
          {:keys [mean-d ratio p positive]} (:composite view-2)
          {:keys [order temporal] cp :p} (:composite components)]
      (is (close? mean-d (mean comp)))
      (is (close-to? ratio (js/Math.exp (mean comp)) 0.0006))
      (is (= positive (count (filter pos? comp))))
      (is (close-to? p (sign-flip-p comp) 0.0006))
      (is (close? order (/ (+ (mean rs) (mean us)) 2.0)))
      (is (close? temporal (/ (- (mean rs) (mean us)) 2.0)))
      (is (close-to? cp (permutation-p comp) 0.0006))))
  (testing "and the mean of the four ROW means equals the mean of the ten
           per-run composites, which is why the page may print either and
           get the same number"
    (is (close? (:mean-d (:composite view-2))
                (mean (map #(mean (d-values %)) rows))))))

(deftest the-order-component-is-view-2s-mean-d
  (testing "the decomposition's ORDER column is not a second estimate:
           averaging the two start groups is what the paired `d` already
           does once the start is counterbalanced, so the two columns are
           the same number and the page prints both only because they
           answer differently-worded questions"
    (doseq [row rows]
      (is (close? (:mean-d (get view-2 row)) (:order (get components row))) (name row)))))

;; ---------------------------------------------------------------------------
;; The counts the page quotes in prose
;; ---------------------------------------------------------------------------

(defn- strata-of [row]
  (mapcat (fn [run]
            (let [v (app/segment-order-verdict (get run row) 6 (:start run))]
              [(:reagent-first v) (:uix-first v)]))
          ensemble))

(defn- rounds-of [row] (mapcat #(get % row) ensemble))

(deftest the-per-row-round-and-stratum-counts-reproduce
  (testing "`59 of 60 rounds above 1.0; 19 of 20 order strata wholly
           above it` on M1, and `all 60 / all 20` below on broad and
           above on narrow — the sentences the RED-ZONE table's verdict
           column carries, counted from the forty cells"
    (is (= 59 (count (filter #(> % 1.0) (rounds-of :M1)))))
    (is (= 19 (count (filter #(> (:min %) 1.0) (strata-of :M1)))))
    (is (= 60 (count (filter #(< % 1.0) (rounds-of :broad)))))
    (is (= 20 (count (filter #(< (:max %) 1.0) (strata-of :broad)))))
    (is (= 60 (count (filter #(> % 1.0) (rounds-of :narrow)))))
    (is (= 20 (count (filter #(> (:min %) 1.0) (strata-of :narrow)))))))

(deftest the-magnitude-resolution-and-the-discredited-statistic-reproduce
  (testing "`the strata overlap in 37 of 40 row-runs`, the three
           unresolved ones falling one each on M1, M2 and narrow and
           never on broad, and `no row is disjoint twice`. Those three
           are the individually-unpublishable points the aggregate rule
           deliberately keeps in the ensemble"
    (let [verdicts (for [run ensemble row rows]
                     [(:run run) row (app/segment-order-verdict (get run row) 6 (:start run))])
          unresolved (remove #(:magnitude-resolved? (nth % 2)) verdicts)]
      (is (= 37 (count (filter #(:magnitude-resolved? (nth % 2)) verdicts))))
      (is (= 3 (count unresolved)))
      (is (= #{:M1 :M2 :narrow} (set (map second unresolved))) "broad never splits")
      (is (= 2 (count (set (map first unresolved))))
          "the three fall in two runs, so no row is disjoint twice")
      (is (every? #(false? (:refuse? (nth % 2))) verdicts)
          "and the fail-closed DIRECTION half never fires on any of the forty")))
  (testing "`counted the way the page counted it — 40 row-runs treated as
           if independent — the Reagent-first stratum is higher in 23 of
           40`, which is the discredited 11-of-12 restated on the
           balanced design and no longer an effect"
    (is (= 23 (count (for [run ensemble row rows
                           :let [v (app/segment-order-verdict (get run row) 6 (:start run))]
                           :when (> (:mean (:reagent-first v)) (:mean (:uix-first v)))]
                       [(:run run) row]))))))

;; ---------------------------------------------------------------------------
;; THE ONE DISAGREEMENT, pinned rather than conformed away
;; ---------------------------------------------------------------------------

(deftest the-published-intervals-used-eight-degrees-of-freedom-where-nine-is-right
  (testing "EVERY point estimate and both p columns reproduce. The
           INTERVALS do not, and they miss the same way on every row:
           the page's are about 2% wider than the data supports.

           The cause is identifiable rather than guessed. An interval on
           the mean of TEN run means is a one-sample Student-t interval
           with n − 1 = NINE degrees of freedom, t = 2.2622. The
           multiplier the page actually used is ~2.306 — t at EIGHT
           degrees of freedom, which is the CORRECT multiplier for the
           two-sample five-against-five `resolution limit` column
           standing beside it, and which reproduces exactly there. The
           same t was reused for the one-sample case.

           This test asserts the diagnosis both ways: the published
           half-width is NOT t-9 times the standard error, and IS t-8
           times it. The studio page states the disagreement and prints
           both intervals; nothing is silently conformed"
    (doseq [row rows]
      (let [t    (thresholds row)
            se   (/ (sample-sd t) (js/Math.sqrt 10))
            {:keys [lo hi]} (get published-threshold row)
            half (/ (- hi lo) 2.0)]
        (is (not (close-to? half (* t-9 se) 0.0002))
            (str (name row) " — the published interval is NOT the 9-df one"))
        (is (close-to? half (* t-8 se) 0.0006)
            (str (name row) " — it IS the 8-df one")))))
  (testing "the same reuse in View 2's intervals on `d`, where the page
           prints ratios to three decimals, so the multiplier can only be
           recovered to about ±0.02 — enough to exclude 2.2622 and to
           include 2.3060"
    (doseq [row rows]
      (let [d    (d-values row)
            se   (/ (sample-sd d) (js/Math.sqrt 10))
            {:keys [lo hi]} (get view-2 row)
            half (/ (- (js/Math.log hi) (js/Math.log lo)) 2.0)
            mult (/ half se)]
        (is (> mult 2.28) (str (name row) " — above the 9-df multiplier 2.2622"))
        (is (< mult 2.34) (str (name row) " — consistent with the 8-df 2.3060"))))))

(deftest the-corrected-nine-degree-intervals-change-no-verdict
  (testing "the error is CONSERVATIVE — the published intervals are too
           WIDE — so every verdict on the page survives it. Recomputed
           here so that the claim is arithmetic rather than reassurance"
    (doseq [[row lo hi] [[:M1 1.2109 1.2510] [:M2 1.0028 1.1173]
                         [:broad 0.6001 0.6581] [:narrow 1.1582 1.1926]]]
      (let [t  (thresholds row)
            se (/ (sample-sd t) (js/Math.sqrt 10))
            m  (mean t)]
        (is (close? lo (- m (* t-9 se))) (name row))
        (is (close? hi (+ m (* t-9 se))) (name row)))))
  (testing "M1, broad and narrow stay clear of 1.0 on the corrected
           interval and M2 still only just clears parity, exactly as
           published"
    (let [ci (fn [row]
               (let [t (thresholds row) se (/ (sample-sd t) (js/Math.sqrt 10))]
                 [(- (mean t) (* t-9 se)) (+ (mean t) (* t-9 se))]))]
      (is (> (first (ci :M1)) 1.0) "M1 wholly above 1.0")
      (is (< (second (ci :broad)) 1.0) "broad wholly below 1.0")
      (is (> (first (ci :narrow)) 1.0) "narrow wholly above 1.0")
      (is (> (first (ci :M2)) 1.0)
          "M2's interval on the MEAN clears parity — barely, and it stays
           a diagnostic row precisely because its individual runs do not:
           three of the ten read below 1.0 outright"))))

;; ---------------------------------------------------------------------------
;; THE POST-.25 RE-TAKE'S OBSERVATION TABLE (rf2-b0tz5)
;; ---------------------------------------------------------------------------
;;
;; PR #7315 restated the M1 mount line on the post-`.25` tree and published,
;; for the four runs it accepted, a point estimate, a 95% interval and a
;; min–max range per row — plus M1's four run means and nothing else. The
;; merged-PR audit found that this repeats the gap #7312 had just closed one
;; section above: the intervals that decided *not restated* on M2, broad and
;; narrow could not be recomputed from the repository, because the numbers
;; they are computed FROM had never reached it.
;;
;; [[retake]] is that table. Every figure the studio page prints about the
;; re-take is derived below from these twenty cells and checked against the
;; page's own claims, which live in [[retake-published]] so that the
;; derivation has something to be checked against.
;;
;; PROVENANCE, and it is NOT the same as the ensemble's. Nothing here was
;; measured for this fixture and no browser was opened: the operator has
;; deferred measurement while the adapter is built, and five sibling workers
;; were on the box. The producing worktree (`worker/clockline-b0tz5`) was
;; reaped with its `ai/bench-logs/` inside it, so the run logs themselves are
;; gone. What survives is the producing agent's own transcript, which
;; captured the driver's console output and the analysis run over those logs
;; verbatim; the cells below are transcribed from it. Two consequences,
;; stated rather than glossed:
;;
;;   * The unit that survives is the RUN MEAN — each row's `red-zone :mean`
;;     for that run. The six per-round vectors behind each accepted run's
;;     mean did NOT survive, except for run 5, whose four vectors are in
;;     [[run-5-per-round]] because the diagnosis of its refusal was
;;     captured in full. So every ENSEMBLE-level figure is derivable here;
;;     the page's per-round counts (`14 of 24 rounds above 1.0`, `1 of 8
;;     strata wholly above`) are NOT, and the page now says so.
;;   * A run mean transcribed at four decimals is the instrument's own
;;     rounded output, so a mean re-derived from these cells can differ from
;;     the instrument's in the fourth decimal. It never did on any figure
;;     below, and the tolerances say so.
;;
;; THE SECOND FINDING, which changes a published number. The four-run set was
;; not the launch set: a fifth run completed all four rows at measured 0%
;; load and was dropped because the driver exits 1 when a row's two
;; segment-order strata point OPPOSITE WAYS across 1.0. That condition is a
;; function of the result — and the aggregate rule this file pinned for the
;; ensemble one section above says exactly what to do with it. See
;; [[the-fifth-run-was-dropped-for-the-way-its-strata-split]].

(def ^:private retake
  "THE 5 × 4 OBSERVATION TABLE of the post-`.25` re-take, complete.

  One entry per run that completed all four rows on the converged
  instrument at whole-tree anchor `7f54c67d5a`. Each cell is that run's
  `red-zone :mean` for the row — its six per-round `uix-subs ÷
  reagent-subs` readings averaged, each reading floor-normalised in its
  own round and segment. `:start` is the segment that led round 0.

  `:exit` is the driver's exit code and `:in-ensemble?` is whether the
  run is one observation of the ensemble. They are not the same
  question, which is the whole of
  [[the-fifth-run-was-dropped-for-the-way-its-strata-split]].

  `:M1-legs` is the same run's `M1` mount leg pair — `reagent-subs ÷
  floor` and `uix-subs ÷ floor`, the denominator and numerator the page
  decomposes the threshold into."
  [{:run 1 :start :reagent-subs :exit 0 :in-ensemble? true
    :M1 0.9980 :M2 1.0584 :broad 0.6316 :narrow 1.2306
    :M1-legs {:reagent 4.2853 :uix 4.2723}}
   {:run 2 :start :uix-subs :exit 0 :in-ensemble? true
    :M1 1.0391 :M2 0.9685 :broad 0.5437 :narrow 1.1767
    :M1-legs {:reagent 4.3457 :uix 4.5089}}
   {:run 3 :start :reagent-subs :exit 0 :in-ensemble? true
    :M1 1.0219 :M2 1.0714 :broad 0.5538 :narrow 1.3090
    :M1-legs {:reagent 4.5731 :uix 4.6673}}
   {:run 6 :start :uix-subs :exit 0 :in-ensemble? true
    :M1 1.0382 :M2 0.9302 :broad 0.5102 :narrow 1.1769
    :M1-legs {:reagent 4.3144 :uix 4.4687}}
   {:run 5 :start :reagent-subs :exit 1 :in-ensemble? true
    :M1 0.9780 :M2 0.9242 :broad 0.6135 :narrow 1.1291
    :M1-legs {:reagent 4.6045 :uix 4.4573}}])

(def ^:private retake-refused
  "The launches that produced no ensemble observation, kept here because a
  launch set with the failures left out is not a launch set.

  Both are ARM-ORDER GUARD refusals — `p0_converge_run.cjs` exit **2**,
  the guard reporting that an arm reads differently for WHERE IN THE PLAN
  it was measured. That verdict is computed from arm timings by plan
  position and never looks at the `uix ÷ reagent` ratio, so excluding
  these two selects on the INSTRUMENT's validity and not on the result —
  which is the distinction run 5 fails and they pass.

  Their row figures did not survive the reaping and are NOT reconstructed;
  what survives is the exit code, the refusing row and the load either
  side, which is what the exclusion rests on anyway."
  [{:run 2 :attempt 1 :start :uix-subs :exit 2 :refused-row :narrow
    :pre-cpu 101 :post-cpu 310 :why "phase strata 1.37×–1.55× last-third over
                                     first-third; the box degraded mid-run. Re-taken under quiet"}
   {:run 4 :start :uix-subs :exit 2 :refused-row :M2
    :pre-cpu 0 :post-cpu 0 :why "the mount-budget fragility this instrument already
                                 documents — 720 mounts a page refused 4 of 6 for
                                 rf2-6i0i2 too. Not contention, and not new"}])

(def ^:private run-5-per-round
  "Run 5's four per-round vectors, the only per-round record of the
  re-take that survived. Six rounds, round 0 led by the Reagent segment.

  They are here because run 5's refusal is the one thing on the page that
  has to be re-derived rather than quoted: whether the driver was right to
  refuse, and whether the ensemble was right to drop the run, are
  different questions with different answers."
  {:M1     [1.0345 0.9749 1.0792 0.9279 1.0459 0.8055]
   :M2     [0.8    1.1053 0.9584 0.7727 1.0    0.9091]
   :broad  [0.5938 0.6667 0.6667 0.4675 0.54   0.7467]
   :narrow [1.178  1.1033 1.1584 1.1559 1.151  1.0282]})

(def ^:private retake-published
  "What PR #7315 printed for the FOUR accepted runs — the figures the audit
  said could not be recomputed. Transcribed so the derivation can be
  checked against them, and superseded on the page by the five-run
  figures below."
  {:M1     {:threshold 1.0243 :lo 0.9937 :hi 1.0549 :run-min 0.9980 :run-max 1.0391}
   :M2     {:threshold 1.0071 :lo 0.8978 :hi 1.1165 :run-min 0.9302 :run-max 1.0714}
   :broad  {:threshold 0.5598 :lo 0.4781 :hi 0.6415 :run-min 0.5102 :run-max 0.6316}
   :narrow {:threshold 1.2233 :lo 1.1238 :hi 1.3228 :run-min 1.1767 :run-max 1.3090}})

(def ^:private retake-corrected
  "What the page publishes now: the same four rows over the FIVE runs the
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
  "The four runs PR #7315 published: exit 0."
  []
  (filterv #(zero? (:exit %)) retake))

(defn- retake-ci
  "Point estimate, sample sd and one-sample Student-t 95% interval on the
  mean of `runs`' means for `row`. `n − 1` degrees of freedom, which is
  the rule the ensemble misapplied and this section applies correctly for
  both n = 4 and n = 5."
  [runs row]
  (let [xs (mapv #(get % row) runs)
        m  (mean xs)
        sd (sample-sd xs)
        t  (case (count xs) 4 t-3 5 t-4)
        h  (* t (/ sd (js/Math.sqrt (count xs))))]
    {:mean m :sd sd :lo (- m h) :hi (+ m h) :half h
     :run-min (apply min xs) :run-max (apply max xs)}))

(deftest the-retake-table-is-the-launch-set-not-a-selection
  (testing "five completed runs and two guard-refused launches — seven
           launches, and the table names all seven rather than only the
           ones that published"
    (is (= 5 (count retake)))
    (is (= 2 (count retake-refused)))
    (is (= [1 2 3 6 5] (mapv :run retake)))
    (is (every? #(contains? % :in-ensemble?) retake)))
  (testing "the four PR #7315 published are the four that exited 0, and
           the fifth is the one that exited 1"
    (is (= [1 2 3 6] (mapv :run (accepted-only))))
    (is (= [5] (mapv :run (remove #(zero? (:exit %)) retake)))))
  (testing "the accepted four are counterbalanced two and two, and the
           five-run set is 3:2 — an imbalance that is a CONSEQUENCE of
           not choosing the launch set by outcome, not a defect of it"
    (is (= 2 (count (filter #(= :reagent-subs (:start %)) (accepted-only)))))
    (is (= 2 (count (filter #(= :uix-subs (:start %)) (accepted-only)))))
    (is (= 3 (count (filter #(= :reagent-subs (:start %)) retake))))
    (is (= 2 (count (filter #(= :uix-subs (:start %)) retake))))))

(deftest every-figure-pr-7315-published-reproduces-from-the-four-cells
  (testing "the point estimates, the intervals the audit said could not be
           recomputed, and the run-mean ranges — all four rows, from the
           table and nothing else"
    (doseq [row rows]
      (let [{:keys [mean lo hi run-min run-max]} (retake-ci (accepted-only) row)
            p (get retake-published row)]
        (is (close? mean (:threshold p)) (str (name row) " — threshold"))
        (is (close? lo (:lo p))          (str (name row) " — interval low"))
        (is (close? hi (:hi p))          (str (name row) " — interval high"))
        (is (close? run-min (:run-min p)) (str (name row) " — run-mean min"))
        (is (close? run-max (:run-max p)) (str (name row) " — run-mean max")))))
  (testing "M1's published sd, which is the only one the page prints"
    (is (close-to? (:sd (retake-ci (accepted-only) :M1)) 0.0192 0.0001)))
  (testing "the multiplier is t at THREE degrees of freedom — the page
           states `1.0243 ± 3.1824 × 0.009616` and that is what these four
           run means give"
    (let [{:keys [sd half]} (retake-ci (accepted-only) :M1)]
      (is (close-to? (/ sd 2.0) 0.009616 0.000002) "the standard error")
      (is (close-to? half (* t-3 0.009616) 0.00001) "half-width = t(3) × se"))))

(deftest the-not-restated-dispositions-are-arithmetic-not-assertion
  (testing "M2, broad and narrow were held at their published values
           because the re-take's interval CONTAINS the published
           magnitude. That is now checked rather than stated — on the
           four-run set the audit questioned, and on the five-run set that
           replaces it"
    (doseq [runs [(accepted-only) retake]]
      (doseq [row [:M2 :broad :narrow]]
        (let [{:keys [lo hi]} (retake-ci runs row)
              pub (:threshold (get published-threshold row))]
          (is (and (<= lo pub) (<= pub hi))
              (str (name row) " — the interval contains the published " pub
                   " over " (count runs) " runs, so the row is NOT restated"))))))
  (testing "M1 is the one row restated, and for the opposite reason: the
           published 1.2310 lies outside the interval on either set, and
           the run-mean spreads do not even meet"
    (doseq [runs [(accepted-only) retake]]
      (let [{:keys [lo hi run-max]} (retake-ci runs :M1)
            {:keys [threshold run-min]} (get published-threshold :M1)]
        (is (not (and (<= lo threshold) (<= threshold hi)))
            (str "M1 over " (count runs) " runs — 1.2310 is outside the interval"))
        (is (< run-max run-min)
            (str "M1 over " (count runs)
                 " runs — the run-mean spread is wholly disjoint from the published one"))))))

(deftest the-leg-decomposition-is-the-mean-of-the-per-run-legs
  (testing "the page decomposes the threshold into two floor-normalised
           legs and reports 4.380 ÷ 4.479 over the four accepted runs. Both
           are means of the per-run legs in the table"
    (let [runs (accepted-only)
          den  (mean (mapv #(get-in % [:M1-legs :reagent]) runs))
          num  (mean (mapv #(get-in % [:M1-legs :uix]) runs))]
      (is (close-to? den 4.380 0.0005) "denominator leg")
      (is (close-to? num 4.479 0.0005) "numerator leg")
      (is (close-to? (/ num den) 1.023 0.0005)
          "and their quotient, 1.023 against the 1.0243 the runs report —
           the residue being that each run forms its ratio per round and per
           segment before averaging, never from the two means")))
  (testing "the leg comparison is what carries the restatement, so it is
           also stated over the five-run set. The denominator moves further
           from the published 4.358 — +1.5% rather than +0.5% — and the
           conclusion is unchanged, because the numerator's fall is an
           order of magnitude larger either way"
    (let [den (mean (mapv #(get-in % [:M1-legs :reagent]) retake))
          num (mean (mapv #(get-in % [:M1-legs :uix]) retake))]
      (is (close-to? den 4.425 0.0005) "denominator leg, five runs")
      (is (close-to? num 4.475 0.0005) "numerator leg, five runs")
      (is (< (js/Math.abs (- (/ den 4.358) 1.0)) 0.02) "denominator within 2% of published")
      (is (< (/ num 5.343) 0.85) "numerator down more than 15% from published")
      (is (close-to? (/ num den) 1.011 0.0005) "quotient, five runs"))))

(deftest the-fifth-run-was-dropped-for-the-way-its-strata-split
  (testing "run 5's M1 refusal is REPRODUCED from its per-round vector
           rather than quoted: the two order strata point opposite ways
           across 1.0, so `segment-order-verdict` refuses and the driver
           exits 1. The driver was right"
    (let [vd (app/segment-order-verdict (:M1 run-5-per-round) 6 :reagent-subs)]
      (is (true? (:refuse? vd)))
      (is (false? (:direction-agrees? vd)))
      (is (false? (:magnitude-resolved? vd)))
      (is (close? 1.0532 (:mean (:reagent-first vd))) "Reagent-first stratum, above 1.0")
      (is (close? 0.9028 (:mean (:uix-first vd)))     "UIx-first stratum, below 1.0")
      (is (close? 0.9780 (mean (:M1 run-5-per-round)))
          "and its row mean is the cell in the table")))
  (testing "the OTHER three rows of run 5 refuse nothing — their strata
           overlap and every one resolves a magnitude. Run 5 is not a bad
           run; it is a run whose M1 sat on parity"
    (doseq [row [:M2 :broad :narrow]]
      (let [vd (app/segment-order-verdict (get run-5-per-round row) 6 :reagent-subs)]
        (is (false? (:refuse? vd)) (str (name row) " — no refusal"))
        (is (true? (:magnitude-resolved? vd)) (str (name row) " — magnitude resolved"))
        (is (close? (get (nth retake 4) row) (mean (get run-5-per-round row)))
            (str (name row) " — the vector's mean is the cell in the table")))))
  (testing "the exit code proves the rest of the run was clean. The driver
           checks page errors, then the ARM-ORDER guard (exit 2), then the
           positive control (exit 1, different message), and only then the
           segment-order control. An exit 1 carrying the segment-order
           message means every earlier gate passed on every row — so the
           ONLY thing wrong with run 5 was the direction its M1 strata took"
    (is (= 1 (:exit (nth retake 4))))
    (is (every? #(= 2 (:exit %)) retake-refused)
        "and the two launches that ARE excluded were excluded by the
         arm-order guard, which never looks at the ratio"))
  (testing "dropping it is the outcome-based selection this file already
           forbade for the ensemble one section above: `:in-an-ensemble`
           says a row-run whose strata split is still ONE OBSERVATION,
           because a split IS the extreme partition and excluding it moves
           the estimate. It moved it here — away from parity, which is the
           direction that flatters the restatement"
    (let [four (retake-ci (accepted-only) :M1)
          five (retake-ci retake :M1)]
      (is (< (:mean five) (:mean four))
          "the dropped run pulled the estimate toward 1.0, so dropping it pushed it away")
      (is (close-to? (- (:mean four) (:mean five)) 0.0093 0.0002)
          "by 0.9 points")
      (is (< (:half four) (:half five))
          "and narrowed the interval, which is the second half of the same error")))
  (testing "and it changes no verdict, which is why the row is corrected
           rather than re-run: the five-run interval still contains 1.0,
           still excludes the published 1.2310, and the row is still
           indistinguishable from parity"
    (let [{:keys [lo hi]} (retake-ci retake :M1)]
      (is (and (< lo 1.0) (> hi 1.0)) "contains parity")
      (is (< hi 1.1989) "and does not reach the published run-mean spread"))))

(deftest the-corrected-five-run-figures-are-what-the-page-publishes
  (testing "every figure in the page's corrected table, derived from the
           five cells"
    (doseq [row rows]
      (let [{:keys [mean lo hi run-min run-max]} (retake-ci retake row)
            c (get retake-corrected row)]
        (is (close? mean (:threshold c)) (str (name row) " — threshold"))
        (is (close? lo (:lo c))          (str (name row) " — interval low"))
        (is (close? hi (:hi c))          (str (name row) " — interval high"))
        (is (close? run-min (:run-min c)) (str (name row) " — run-mean min"))
        (is (close? run-max (:run-max c)) (str (name row) " — run-mean max")))))
  (testing "the 3:2 start imbalance does not carry the M1 result: the
           start-balanced estimate — the mean of the two start-group means,
           which is what an alternating design's unbiased estimator is —
           lands inside the interval"
    (let [by  (group-by :start retake)
          rg  (mean (mapv :M1 (:reagent-subs by)))
          ux  (mean (mapv :M1 (:uix-subs by)))
          bal (/ (+ rg ux) 2.0)
          {:keys [lo hi]} (retake-ci retake :M1)]
      (is (close-to? rg 0.9993 0.0002) "reagent-start group, three runs")
      (is (close-to? ux 1.0387 0.0002) "uix-start group, two runs")
      (is (close-to? bal 1.0190 0.0002) "start-balanced")
      (is (and (< lo bal) (< bal hi)) "inside the published interval"))))

(deftest what-the-retake-table-cannot-check-is-said-rather-than-implied
  (testing "the per-round vectors of the four accepted runs did not
           survive, so the page's per-round and per-stratum counts are
           reported by the instrument and NOT re-derivable here. Only run
           5 carries vectors, and this test pins which cells the fixture
           does and does not hold, so a later reader cannot mistake the
           table for something it is not"
    (is (= 20 (count (for [r retake row rows] (get r row))))
        "twenty run means — every accepted and every completed run, all four rows")
    (is (= #{:M1 :M2 :broad :narrow} (set (keys run-5-per-round)))
        "and per-round vectors for exactly one run")
    (is (every? #(= 6 (count %)) (vals run-5-per-round))
        "six rounds each, the balanced design")
    (is (empty? (filter :per-round retake))
        "no run in the table claims a per-round vector it does not have")))

;; ---------------------------------------------------------------------------
;; The flag is global and the arm is not (rf2-2rtt6.21)
;; ---------------------------------------------------------------------------
;;
;; `HICASSO_RATOM=on` is a page-global request; arm presence is decided per
;; row. With no `HICASSO_ONLY` the driver selects ALL FOUR rows, and `M2`
;; and `narrow` deliberately carry no `:reagent-ratom` arm — `M2`'s witness
;; ignores the flag and `bulk-arms` admits the arm only on `:broad`. Until
;; this section the record still read the FLAG: it divided by a ratio those
;; rows never produced, formed a ratom floor of nothing, ran the leg verdict
;; over the result and labelled the row as carrying the arm. The published
;; runs escaped it only by always pairing the flag with
;; `HICASSO_ONLY=M1,broad`, so neither CI nor the quality gates ever ran the
;; natural flagged invocation.
;;
;; Three facts, in the order the run establishes them: which arms the row's
;; PLAN plants, what [[app/ratom-leg]] DECIDES from a round, and what the
;; RECORD then publishes. Pure arithmetic over constants — no DOM, no clock,
;; no browser, like everything else in this namespace.

(def ^:private flagged-rows
  "What `HICASSO_RATOM=on` with no `HICASSO_ONLY` selects: the driver's
  default row list, all four of them."
  [:M1 :M2 :broad :narrow])

(def ^:private rows-carrying-the-arm
  "And the two of those four whose plan admits the arm."
  #{:M1 :broad})

(deftest the-flagged-default-selection-plants-the-arm-on-two-rows-of-four
  (testing "with the flag ON, M1 and broad run a FOUR-arm Reagent segment
           and M2 and narrow run their published three. This is the fact
           every assertion below rests on, and it is asked of the entry's
           own plan rather than restated beside it"
    (doseq [row flagged-rows]
      (let [ids (set (app/reagent-segment-arm-ids row true))]
        (is (contains? ids :reagent-subs) (str row " must always run the denominator"))
        (is (= (contains? rows-carrying-the-arm row)
               (contains? ids :reagent-ratom))
            (str row " under HICASSO_RATOM=on")))))
  (testing "and with the flag OFF no row runs it, which is what keeps an
           unflagged invocation the instrument the four published rows
           were measured on"
    (doseq [row flagged-rows]
      (is (not (contains? (set (app/reagent-segment-arm-ids row false)) :reagent-ratom))
          (str row " under HICASSO_RATOM=off")))))

(deftest the-leg-is-formed-only-when-every-round-measured-the-arm
  (let [round (fn [ratom] {:reagent-subs
                           {:ratio (cond-> {:reagent-subs 4.0}
                                     ratom (assoc :reagent-ratom ratom))}})]
    (testing "six rounds that all measured the arm: the leg is 4.0 / 3.0,
             once per round"
      (let [l (app/ratom-leg (mapv round (repeat 6 3.0)))]
        (is (= 6 (count l)))
        (is (every? #(close? 1.3333 %) l))))
    (testing "no round measured it — nil, and NOT a quotient over a
             denominator that was never taken. This is the M2 and narrow
             case, and `subs / nil` is `Infinity` in JavaScript rather
             than an error, so nothing downstream would have complained"
      (is (nil? (app/ratom-leg (mapv round (repeat 6 nil))))))
    (testing "a PARTIAL arm is no arm: one round short and the leg is nil,
             because a figure that quietly changes what it averages over
             is worse than one that is absent"
      (is (nil? (app/ratom-leg (mapv round [3.0 3.0 3.0 3.0 3.0 nil])))))
    (testing "and a zero denominator divides to Infinity, so it is refused
             at the same door"
      (is (nil? (app/ratom-leg (mapv round (repeat 6 0.0))))))))

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
  "The published record for `row` under `HICASSO_RATOM=on`, over six rounds
  of that row's ACTUAL flagged arm set. The Reagent segment's arms come
  from the entry's own plan, so no premise is transcribed here."
  [row]
  (let [reagent-arms (select-keys synthetic-ms (app/reagent-segment-arm-ids row true))
        uix-arms     (select-keys synthetic-ms [:floor :uix-subs :ctl-2x])]
    (:record (app/row-record
               {:row row
                :grade :bar
                :doc "a contract fixture, not a measurement"
                :control {:predicted 2.0 :basis "the 2x control reads twice the floor"}
                :writes-per-sample 1
                :start :reagent-subs}
               (vec (repeat 6 {:reagent-subs reagent-arms :uix-subs uix-arms}))))))

(defn- non-finite
  "Every number anywhere in `x` that is not finite — NaN, Infinity,
  -Infinity. A record is a tree of maps and vectors, so the whole of it is
  checked rather than the two or three keys somebody remembered."
  [x]
  (let [found (atom [])]
    (walk/postwalk (fn [v]
                     (when (and (number? v) (not (js/isFinite v)))
                       (swap! found conj v))
                     v)
                   x)
    @found))

(deftest the-flagged-default-selection-publishes-a-leg-only-where-the-arm-ran
  (testing "HICASSO_RATOM=on with the default four rows. M1 and broad
           publish a leg of 4.0 / 3.0 and say they carry the arm; M2 and
           narrow publish no leg, no ratom floor, no leg verdict and no
           comparability note, and say they carry no arm — and no row
           emits a non-finite number anywhere in its record"
    (doseq [row flagged-rows]
      (let [rec (flagged-record row)]
        (if (contains? rows-carrying-the-arm row)
          (do (is (true? (:ratom-arm? rec)) (str row))
              (is (true? (:ratom-arm? (:red-zone rec))) (str row))
              (is (string? (:comparability (:red-zone rec))) (str row))
              (is (close? 1.3333 (:mean (:reactive-leg rec))) (str row))
              (is (= :magnitude (:claim (:reactive-leg rec))) (str row))
              (is (some? (:ratom-over-floor rec)) (str row))
              (is (some? (:reactive-leg-segment-order rec)) (str row)))
          (do (is (false? (:ratom-arm? rec)) (str row))
              (is (false? (:ratom-arm? (:red-zone rec))) (str row))
              (is (nil? (:comparability (:red-zone rec))) (str row))
              (is (nil? (:reactive-leg rec)) (str row))
              (is (nil? (:ratom-over-floor rec)) (str row))
              (is (nil? (:reactive-leg-segment-order rec)) (str row))))
        (is (empty? (non-finite rec))
            (str row " emitted non-finite numbers: " (pr-str (non-finite rec))))))))

(deftest an-m2-or-narrow-only-flagged-selection-emits-no-leg-and-no-infinity
  (testing "`HICASSO_RATOM=on HICASSO_ONLY=M2,narrow` — the selection
           nobody published from, CI never ran and the quality gates never
           exercised, which is precisely why it was the broken one. Both
           rows must answer with no leg at all rather than with a division
           by a denominator they never measured"
    (doseq [row [:M2 :narrow]]
      (let [rec (flagged-record row)]
        (is (nil? (:reactive-leg rec)) (str row " must publish no leg"))
        (is (false? (:ratom-arm? rec)) (str row " must not claim the arm"))
        (is (empty? (non-finite rec))
            (str row " emitted non-finite numbers: " (pr-str (non-finite rec))))
        (is (some? (:red-zone rec))
            (str row " still publishes its ordinary red-zone"))))))
