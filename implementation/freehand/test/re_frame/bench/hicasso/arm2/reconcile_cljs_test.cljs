(ns re-frame.bench.hicasso.arm2.reconcile-cljs-test
  "The keyed reconciler's decisions, asserted as values (rf2-2rtt6.10).

  `:keyed/insert-delete-reorder` in the witness set asserts
  `identity-follows-the-key` and `no-node-recreated-on-reorder`. Both are
  properties of the plan, so both are checked here — on a runtime with no
  DOM at all, in milliseconds, on every PR — rather than only in the
  browser suite where they would be visible as a pointer comparison and
  nowhere else."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.bench.hicasso.arm2.reconcile :as rc]))

(defn- ops
  "The plan's placements in ascending new-index order, which reads the
  way a person thinks about a list even though the applier wants them
  backwards."
  [plan]
  (vec (reverse (:places plan))))

;; ---------------------------------------------------------------------------
;; The longest increasing subsequence
;; ---------------------------------------------------------------------------

(deftest lis-finds-a-longest-run
  (is (= #{0 1 2} (rc/lis-positions [0 1 2])))
  (is (= 1 (count (rc/lis-positions [2 1 0]))) "a full reversal has no run longer than one")
  (is (= 4 (count (rc/lis-positions [0 8 1 2 3 9]))))
  (is (= #{} (rc/lis-positions [])))
  (is (= #{} (rc/lis-positions [nil nil])) "a mount is never part of a stable run")
  (is (= #{1 3} (rc/lis-positions [nil 0 nil 5]))))

;; ---------------------------------------------------------------------------
;; Unkeyed children
;; ---------------------------------------------------------------------------

(deftest unkeyed-children-pair-by-position
  (testing "same length — every slot is patched in place"
    (let [p (rc/plan [nil nil nil] [nil nil nil])]
      (is (= [] (:removes p)))
      (is (= 3 (rc/reuse-count p)))
      (is (= 0 (rc/mount-count p)))))
  (testing "growing — the surplus mounts at the end"
    (let [p (rc/plan [nil nil] [nil nil nil])]
      (is (= [] (:removes p)))
      (is (= [{:op :keep :new 0 :old 0}
              {:op :keep :new 1 :old 1}
              {:op :mount :new 2 :old nil}]
             (ops p)))))
  (testing "shrinking — the deficit is removed"
    (let [p (rc/plan [nil nil nil] [nil])]
      (is (= [1 2] (:removes p)))
      (is (= [{:op :keep :new 0 :old 0}] (ops p))))))

;; ---------------------------------------------------------------------------
;; Keyed children
;; ---------------------------------------------------------------------------

(deftest an-unchanged-keyed-list-moves-nothing
  (let [p (rc/plan [:a :b :c] [:a :b :c])]
    (is (= [] (:removes p)))
    (is (= 0 (rc/move-count p)))
    (is (= 0 (rc/mount-count p)))
    (is (= 3 (rc/reuse-count p)))))

(deftest insertion-at-the-head-mounts-one-and-moves-none
  (let [p (rc/plan [:a :b :c] [:z :a :b :c])]
    (is (= [] (:removes p)))
    (is (= 1 (rc/mount-count p)))
    (is (= 0 (rc/move-count p)) "the three survivors keep their relative order")
    (is (= {:op :mount :new 0 :old nil} (first (ops p))))))

(deftest deletion-removes-exactly-the-missing-key
  (let [p (rc/plan [:a :b :c] [:a :c])]
    (is (= [1] (:removes p)))
    (is (= 0 (rc/mount-count p)))
    (is (= [{:op :keep :new 0 :old 0} {:op :keep :new 1 :old 2}] (ops p)))))

(deftest a-reorder-recreates-nothing
  (testing "identity follows the key, and the plan says so"
    (let [p (rc/plan [:a :b :c] [:c :a :b])]
      (is (= [] (:removes p)))
      (is (= 0 (rc/mount-count p)) "no node is recreated on reorder")
      (is (= 3 (rc/reuse-count p)))
      (is (= 1 (rc/move-count p)) "one insertBefore, not two")))
  (testing "a swap of the two ends"
    (let [p (rc/plan [:a :b :c :d] [:d :b :c :a])]
      (is (= 0 (rc/mount-count p)))
      (is (= 2 (rc/move-count p))))))

(deftest a-full-reversal-is-linear-in-moves-not-quadratic
  (let [ks (vec (range 100))
        p  (rc/plan ks (vec (reverse ks)))]
    (is (= 0 (rc/mount-count p)))
    (is (= 100 (rc/reuse-count p)))
    (is (= 99 (rc/move-count p)) "a reversal is the worst case and it is n-1")))

(deftest a-rotation-of-a-hundred-rows-costs-one-move
  (let [ks (vec (range 100))
        p  (rc/plan ks (into [99] (subvec ks 0 99)))]
    (is (= 0 (rc/mount-count p)))
    (is (= 1 (rc/move-count p))
        "the whole point of the longest-increasing-subsequence stable set")))

(deftest mixed-insert-delete-and-reorder
  (let [p (rc/plan [:a :b :c :d :e] [:e :b :x :a])]
    (is (= [2 3] (:removes p)) "c and d")
    (is (= 1 (rc/mount-count p)) "x is new")
    (is (= 3 (rc/reuse-count p)) "e, b and a survive")))

(deftest a-nil-key-in-a-keyed-list-matches-nothing
  (testing "it mounts rather than pairing by position — the same rule React applies"
    (let [p (rc/plan [:a nil :c] [:a nil :c])]
      (is (= 1 (rc/mount-count p)))
      (is (= [1] (:removes p))))))

(deftest a-duplicated-key-degrades-to-one-reuse
  (testing "first wins, so two children never fight over one node"
    (let [p (rc/plan [:a :a] [:a :a])]
      (is (= 1 (rc/mount-count p)))
      (is (= [1] (:removes p))))))

(deftest places-run-backwards-so-the-applier-carries-one-anchor
  (let [p (rc/plan [:a :b :c] [:c :b :a])]
    (is (= [0 1 2] (mapv :new (reverse (:places p))))
        "ascending when reversed — i.e. descending as emitted")))
