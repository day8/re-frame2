(ns day8.re-frame2-causa.diff.annotated-tree-cljs-test
  "Pure-data tests for the structural-diff annotated-tree walker
  (rf2-gfxmk Phase 1 of rf2-abts7).

  ## Why the `.cljc` + `_test` naming

  Cognitect's test-runner (CLJ) picks up by the default `.*-test$`
  regex on the ns name; Shadow's `:node-test` build picks up by the
  `-test$` regex too (the artefact's `:test` alias is configured to
  match both shapes).

  ## What's under test

    1. Each `::op` classifier — scalars, maps, vectors, sets, records,
       sentinels — emits the right node shape.
    2. Pointer-equality short-circuits at every level.
    3. Mixed-shape pairs (map vs vec) yield `:modified` at the parent,
       not nonsense recursion.
    4. Boundary cases: empty before, empty after, both nil, sentinel
       both-sides."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.diff.annotated-tree :as at]))

;; ---- (1) op classifiers -------------------------------------------------

(deftest scalar-same
  (testing "two equal scalars yield :same"
    (is (= :same (at/op-of (at/diff-tree 1 1))))
    (is (= :same (at/op-of (at/diff-tree "abc" "abc"))))
    (is (= :same (at/op-of (at/diff-tree :foo :foo))))
    (is (= :same (at/op-of (at/diff-tree nil nil))))))

(deftest scalar-modified
  (testing "two non-equal scalars yield :modified with both sides"
    (let [n (at/diff-tree 1 2)]
      (is (= :modified (at/op-of n)))
      (is (= 1 (:before n)))
      (is (= 2 (:after n))))))

(deftest map-added-key
  (testing "key in after but not in before → :added child"
    (let [tree (at/diff-tree {:a 1} {:a 1 :b 2})]
      (is (= :children (at/op-of tree)))
      (is (= :map (:tag tree)))
      (let [b-child (first (filter #(= :b (:key %)) (:children tree)))]
        (is (= :added (at/op-of b-child)))
        (is (= 2 (:value b-child)))))))

(deftest map-removed-key
  (testing "key in before but not in after → :removed child"
    (let [tree (at/diff-tree {:a 1 :b 2} {:a 1})]
      (is (= :children (at/op-of tree)))
      (let [b-child (first (filter #(= :b (:key %)) (:children tree)))]
        (is (= :removed (at/op-of b-child)))
        (is (= 2 (:value b-child)))))))

(deftest map-modified-leaf
  (testing "key whose value is a different scalar → :modified leaf at key"
    (let [tree (at/diff-tree {:a 1} {:a 2})]
      (is (= :children (at/op-of tree)))
      (let [a-child (first (filter #(= :a (:key %)) (:children tree)))]
        (is (= :modified (at/op-of a-child)))
        (is (= 1 (:before a-child)))
        (is (= 2 (:after a-child)))))))

(deftest map-nested-recursion
  (testing "nested map → recursive :children with inner :modified leaf"
    (let [tree (at/diff-tree {:cart {:items [{:id 7 :qty 1}]
                                      :total 10}}
                             {:cart {:items [{:id 7 :qty 1}]
                                      :total 12}})]
      (is (= :children (at/op-of tree)))
      (let [cart-child (first (filter #(= :cart (:key %)) (:children tree)))]
        (is (= :children (at/op-of cart-child)))
        (let [total-child (first (filter #(= :total (:key %))
                                         (:children cart-child)))]
          (is (= :modified (at/op-of total-child)))
          (is (= 10 (:before total-child)))
          (is (= 12 (:after  total-child))))
        (let [items-child (first (filter #(= :items (:key %))
                                         (:children cart-child)))]
          ;; The inner :items vectors are equal → no entry under cart;
          ;; the :same short-circuit drops them. Verify the child either
          ;; isn't present, or if present is :same.
          (when items-child
            (is (= :same (at/op-of items-child)))))))))

(deftest vector-positional-changes
  (testing "vectors → positional pairwise diff; per-slot ops"
    (let [tree (at/diff-tree [1 2 3] [1 9 3 4])]
      (is (= :children (at/op-of tree)))
      (is (= :vec (:tag tree)))
      (let [by-i (into {} (map (juxt :index identity)) (:children tree))]
        (is (= :same     (at/op-of (get by-i 0))))
        (is (= :modified (at/op-of (get by-i 1))))
        (is (= 2 (:before (get by-i 1))))
        (is (= 9 (:after  (get by-i 1))))
        (is (= :same     (at/op-of (get by-i 2))))
        (is (= :added    (at/op-of (get by-i 3))))
        (is (= 4 (:value (get by-i 3))))))))

(deftest vector-shorter-after
  (testing "after-vector shorter than before → trailing :removed"
    (let [tree (at/diff-tree [1 2 3] [1])]
      (is (= :children (at/op-of tree)))
      (let [by-i (into {} (map (juxt :index identity)) (:children tree))]
        (is (= :same (at/op-of (get by-i 0))))
        (is (= :removed (at/op-of (get by-i 1))))
        (is (= 2 (:value (get by-i 1))))
        (is (= :removed (at/op-of (get by-i 2))))
        (is (= 3 (:value (get by-i 2))))))))

(deftest set-membership-changes
  (testing "set diff → :added/:removed/:same children with no index"
    (let [tree (at/diff-tree #{1 2 3} #{2 3 4})]
      (is (= :children (at/op-of tree)))
      (is (= :set (:tag tree)))
      (let [ops (frequencies (map at/op-of (:children tree)))]
        ;; 1 removed, 4 added, 2/3 same
        (is (= 1 (:added ops)))
        (is (= 1 (:removed ops)))
        (is (= 2 (:same ops)))))))

;; Records — IRecord instances are opaque leaves (per ns docstring).
(defrecord Point [x y])

(deftest record-as-leaf-modified
  (testing "two records of different value → :modified at this node
            (not children-recursion); records compared by IEquiv"
    (let [tree (at/diff-tree (->Point 1 1) (->Point 1 2))]
      (is (= :modified (at/op-of tree)))
      (is (= (->Point 1 1) (:before tree)))
      (is (= (->Point 1 2) (:after  tree))))))

(deftest record-equal-yields-same
  (testing "two records that are = → :same (record IEquiv matches)"
    (let [tree (at/diff-tree (->Point 1 1) (->Point 1 1))]
      (is (= :same (at/op-of tree))))))

;; ---- (2) pointer-equality short-circuit --------------------------------

(deftest identical-subtree-short-circuits
  (testing "identical? subtree → :same; never recurses"
    (let [shared (zipmap (range 1000) (range 1000))
          before {:big shared :counter 0}
          after  (assoc before :counter 1)
          tree   (at/diff-tree before after)]
      (is (= :children (at/op-of tree)))
      (let [big-child     (first (filter #(= :big (:key %)) (:children tree)))
            counter-child (first (filter #(= :counter (:key %))
                                         (:children tree)))]
        (is (= :same (at/op-of big-child))
            "the :big subtree must be :same (pointer-equal, no recursion)")
        (is (= :modified (at/op-of counter-child)))))))

(deftest both-nil-is-same
  (is (= :same (at/op-of (at/diff-tree nil nil)))))

(deftest equal-empty-collections-are-same
  (is (= :same (at/op-of (at/diff-tree {} {}))))
  (is (= :same (at/op-of (at/diff-tree [] []))))
  (is (= :same (at/op-of (at/diff-tree #{} #{})))))

;; ---- (3) shape mismatch -------------------------------------------------

(deftest map-to-vec-is-modified-at-parent
  (testing "one side a map, other a vector → :modified at this node"
    (let [tree (at/diff-tree {:a 1} [:a 1])]
      (is (= :modified (at/op-of tree)))
      (is (= {:a 1}  (:before tree)))
      (is (= [:a 1] (:after  tree))))))

(deftest collection-to-leaf-is-modified
  (testing "container vs leaf → :modified at this node"
    (let [tree (at/diff-tree {:a 1} 42)]
      (is (= :modified (at/op-of tree)))
      (is (= {:a 1} (:before tree)))
      (is (= 42     (:after tree))))))

;; ---- (4) boundary + sentinel cases -------------------------------------

(deftest empty-before-into-non-empty-after-via-added
  (testing "empty-map before, non-empty after → root :children with
            all-added children"
    (let [tree (at/diff-tree {} {:a 1 :b 2})]
      (is (= :children (at/op-of tree)))
      (is (every? #(= :added (at/op-of %)) (:children tree)))
      (is (= 2 (count (:children tree)))))))

(deftest non-empty-before-into-empty-after-via-removed
  (testing "non-empty before, empty after → root :children with
            all-removed children"
    (let [tree (at/diff-tree {:a 1 :b 2} {})]
      (is (= :children (at/op-of tree)))
      (is (every? #(= :removed (at/op-of %)) (:children tree)))
      (is (= 2 (count (:children tree)))))))

(deftest sentinel-redacted-same-on-both-sides
  (testing "both sides :rf/redacted bare sentinel → :same (per
            design §3.1 sentinel rule 3 + ns docstring)"
    (is (= :same (at/op-of (at/diff-tree :rf/redacted :rf/redacted))))
    (is (= :same (at/op-of (at/diff-tree {:auth :rf/redacted}
                                          {:auth :rf/redacted}))))))

(deftest sentinel-redacted-added
  (testing "redacted sentinel appearing in after → :added node carrying
            the sentinel; the sentinel chip is the renderer's job"
    (let [tree (at/diff-tree {} {:auth :rf/redacted})]
      (is (= :children (at/op-of tree)))
      (let [auth-child (first (:children tree))]
        (is (= :added (at/op-of auth-child)))
        (is (= :rf/redacted (:value auth-child)))))))

(deftest sentinel-redacted-modified
  (testing "redacted sentinel on one side, plain value on the other →
            :modified leaf with both sides preserved (the renderer
            handles the chip rendering)"
    (let [tree (at/diff-tree {:auth :rf/redacted}
                             {:auth "alice@example.com"})
          child (first (:children tree))]
      (is (= :modified (at/op-of child)))
      (is (= :rf/redacted (:before child)))
      (is (= "alice@example.com" (:after child))))))

(deftest sentinel-large-same-both-sides
  (testing "{:rf/large {:bytes N :head ...}} same both sides → :same"
    (let [sentinel {:rf/large {:bytes 1024 :head "abc"}}
          tree (at/diff-tree sentinel sentinel)]
      (is (= :same (at/op-of tree))))))

;; ---- (5) child-summary slot --------------------------------------------

(deftest child-summary-counts-correctly
  (testing "container's :child-summary counts each child op"
    (let [tree (at/diff-tree {:a 1 :b 2 :c 3}
                             {:a 1 :b 99 :d 4})]
      (is (= :children (at/op-of tree)))
      (let [summary (:child-summary tree)]
        ;; :a same, :b modified, :c removed, :d added
        (is (= 1 (:same summary)))
        (is (= 1 (:modified summary)))
        (is (= 1 (:removed summary)))
        (is (= 1 (:added summary)))))))

(deftest changed-helper
  (testing "changed? on a :same node is false; on any non-:same is true"
    (is (false? (at/changed? (at/diff-tree {} {}))))
    (is (true?  (at/changed? (at/diff-tree {:a 1} {:a 2}))))
    (is (true?  (at/changed? (at/diff-tree {:a 1} {:a 1 :b 2}))))))
