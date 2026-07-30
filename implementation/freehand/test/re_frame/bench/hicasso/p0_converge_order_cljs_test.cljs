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
  Both are on the page, both are five rounds, and
  `p0-converge-app/segment-order` alternates on the round index — so
  rounds 0, 2, 4 are Reagent-first and rounds 1, 3 are UIx-first, in
  both.

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

(defn- v [vs] (app/segment-order-verdict vs 5))

(defn- close? [a b] (< (js/Math.abs (- a b)) 0.0002))

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
    (let [r (app/segment-order-verdict [1.40 0.70 1.45 0.72 1.38] 5)]
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
           construction, which is the arm-level repair available to
           whoever re-takes the table"
    (let [vs [1.10 1.20 1.30 1.40 1.50 1.60]
          r  (app/segment-order-verdict vs 6)]
      (is (true? (:balanced-design? r)))
      (is (close? 1.35 (:order-balanced-mean r))
          "= the raw mean of the six rounds, because 3:3 is balanced"))))
