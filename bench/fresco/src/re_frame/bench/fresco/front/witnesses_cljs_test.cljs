(ns re-frame.bench.fresco.front.witnesses-cljs-test
  "THE WITNESS ROSTER (rf2-2rtt6.8).

  A roster whose only reader is the arm that wrote it proves nothing, so
  these tests assert the two properties that make it worth being data:
  that it covers what validation.md §Witness set enumerates, and that
  [[re-frame.bench.fresco.front.witnesses/missing]] — the function an
  arm's partial-run banner is built on — actually reports an incomplete
  run rather than flattering it."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.bench.fresco.front.witnesses :as rf.bench.fresco.front.witnesses]))

(deftest the-roster-covers-what-validation-enumerates
  (testing "every family the red-zone ratios are set per"
    (is (= #{:bulk :mount :heap :controlled :correctness} rf.bench.fresco.front.witnesses/families)))
  (testing "the bulk pair, the mount row, and the two separated curves"
    (is (contains? rf.bench.fresco.front.witnesses/by-id :bulk/one-commit))
    (is (contains? rf.bench.fresco.front.witnesses/by-id :bulk/narrow-write))
    (is (contains? rf.bench.fresco.front.witnesses/by-id :mount/list-and-form))
    (is (contains? rf.bench.fresco.front.witnesses/by-id :scaling/fixed-reads))
    (is (contains? rf.bench.fresco.front.witnesses/by-id :scaling/fixed-boundaries)))
  (testing "the controlled door's six proofs are named, not summarised"
    (is (= [:same-turn-echo
            :mid-string-caret
            :selection-preserved
            :ime-composition-commits-nothing
            :unchanged-model-rejection
            :async-normalisation]
           (:asserts (rf.bench.fresco.front.witnesses/by-id :controlled/grid-100)))))
  (testing "the lifecycle row carries the standing teardown assertions"
    (is (contains? (set (:asserts (rf.bench.fresco.front.witnesses/by-id :lifecycle/strict-abandoned-teardown-hmr)))
                   :zero-leaked-subscription-refcounts-after-teardown))
    (is (contains? (set (:asserts (rf.bench.fresco.front.witnesses/by-id :lifecycle/strict-abandoned-teardown-hmr)))
                   :unchanged-hot-read-performs-no-new-attach-or-release)))
  (testing "every kill criterion the witness set is supposed to feed has a witness"
    (is (every? (rf.bench.fresco.front.witnesses/gates) [:K1 :K2 :K3 :K4]))))

(deftest every-row-is-completely-declared
  (doseq [row rf.bench.fresco.front.witnesses/witnesses]
    (testing (str (:id row))
      (is (keyword? (:id row)))
      (is (contains? rf.bench.fresco.front.witnesses/families (:family row)))
      (is (string? (:what row)))
      (is (map? (:shape row)))
      (is (seq (:asserts row)) "a row with no assertion is a clock with no correctness")
      (is (seq (:gates row)))))
  (testing "the ids are unique"
    (is (= (count rf.bench.fresco.front.witnesses/witnesses) (count rf.bench.fresco.front.witnesses/by-id)))))

(deftest the-two-scaling-curves-are-separated
  (testing "fixed reads, growing boundaries"
    (is (= [1 1 1] (mapv :reads rf.bench.fresco.front.witnesses/fixed-reads-curve)))
    (is (= [10 100 300] (mapv :boundaries rf.bench.fresco.front.witnesses/fixed-reads-curve))))
  (testing "fixed boundaries, growing reads across the 1/3/7/20 ladder"
    (is (= [100 100 100 100] (mapv :boundaries rf.bench.fresco.front.witnesses/fixed-boundaries-curve)))
    (is (= [1 3 7 20] (mapv :reads rf.bench.fresco.front.witnesses/fixed-boundaries-curve))))
  (testing "the per-read curve is measured with distinct queries — rf2-2rtt6.16's worst case"
    (is (every? :distinct-queries? rf.bench.fresco.front.witnesses/fixed-boundaries-curve))))

(deftest missing-reports-an-incomplete-run-rather-than-flattering-it
  (testing "a run that reported nothing is missing everything"
    (is (= (count rf.bench.fresco.front.witnesses/witnesses) (count (rf.bench.fresco.front.witnesses/missing [])))))
  (testing "a run missing one row names that row"
    (let [reported (remove #{:controlled/grid-100} (map :id rf.bench.fresco.front.witnesses/witnesses))]
      (is (= #{:controlled/grid-100} (rf.bench.fresco.front.witnesses/missing reported)))))
  (testing "only a complete run reports nothing missing"
    (is (= #{} (rf.bench.fresco.front.witnesses/missing (map :id rf.bench.fresco.front.witnesses/witnesses)))))
  (testing "an id nobody declared does not count toward coverage"
    (is (= #{:controlled/grid-100}
           (rf.bench.fresco.front.witnesses/missing (conj (vec (remove #{:controlled/grid-100} (map :id rf.bench.fresco.front.witnesses/witnesses)))
                            :invented/row))))))

(deftest of-family-partitions-the-roster
  (is (= (count rf.bench.fresco.front.witnesses/witnesses)
         (reduce + (map #(count (rf.bench.fresco.front.witnesses/of-family %)) rf.bench.fresco.front.witnesses/families))))
  (is (= 2 (count (rf.bench.fresco.front.witnesses/of-family :bulk))))
  (is (= 2 (count (rf.bench.fresco.front.witnesses/of-family :heap)))))
