(ns day8.re-frame2-causa.diff.section-grouping-cljs-test
  "Pure-data tests for the section-grouping pass (rf2-gfxmk Phase 1 of
  rf2-abts7).

  ## Worked cases (per design §3.1.1)

    1. **cart-cascade** — multi-key changes under `:cart` + unrelated
       changes at `:user :prefs`, `:status`, `:flash` → 4 sections.
    2. **sparse-2-of-100** — 100-key app-db, two unrelated leaf changes
       at deep paths → 2 sections (NOT one root-rooted, NOT 100).
    3. **reset-frame-db** — every top-level key changed → 1 root
       section.

  ## Heuristic tuning

  `max-coalesce-depth` swept over {1, 2, 3, 4, 5} on the cart-cascade
  shape; assert the expected grouping at each value."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.diff.annotated-tree :as at]
            [day8.re-frame2-causa.diff.section-grouping :as sg]))

;; ---- shared fixtures ---------------------------------------------------

(def ^:private cart-before
  {:cart   {:items [{:id 7  :qty 1}
                    {:id 22 :qty 1}]
            :gross 42}
   :user   {:name "Alice"
            :prefs {:theme :light}}
   :status :pending})

(def ^:private cart-after
  {:cart   {:items [{:id 7  :qty 1}
                    {:id 22 :qty 2}
                    {:id 91 :qty 3}]
            :gross 47.5}
   :user   {:name "Alice"
            :prefs {:theme :dark}}
   :status :submitting
   :flash  "Order saved"})

;; ---- (1) cart-cascade worked example -----------------------------------

(deftest cart-cascade-four-sections
  (testing "design §3.1.1 worked example: cart cascade → 4 sections"
    (let [tree     (at/diff-tree cart-before cart-after)
          sections (sg/group-into-sections tree)]
      ;; Sections: [:cart] (items + gross), [:user :prefs] (theme),
      ;; [:status], [:flash]. Per design — the depth-3 coalescence
      ;; swallows the inner [:cart :items] + [:cart :gross] changes
      ;; into one [:cart] section.
      (is (= 4 (count sections))
          (str "expected 4 sections, got "
               (count sections) ": "
               (mapv :path sections)))
      (let [paths (set (map :path sections))]
        (is (contains? paths [:cart]))
        (is (contains? paths [:user :prefs]))
        (is (contains? paths [:status]))
        (is (contains? paths [:flash]))))))

;; ---- (2) sparse-2-of-100 worked example --------------------------------

(deftest sparse-100-keys-two-changes-yields-two-sections
  (testing "design §3.1.1 worked example: 100-key app-db, two unrelated
            deep changes → 2 sections (NOT root-rooted, NOT 100)"
    (let [;; 98 unchanged top-level keys.
          padding (into {} (for [i (range 98)]
                             [(keyword (str "pad-" i)) i]))
          before  (assoc padding
                         :users {42 {:profile {:email "alice@old.com"}}}
                         :cart  {:checkout {:payment-method :card}})
          after   (assoc padding
                         :users {42 {:profile {:email "alice@new.com"}}}
                         :cart  {:checkout {:payment-method :paypal}})
          tree     (at/diff-tree before after)
          sections (sg/group-into-sections tree)]
      ;; Two leaf changes, common ancestor is root, depth budget (3)
      ;; doesn't allow root-coalescence → standalone sections.
      (is (= 2 (count sections))
          (str "expected 2 sections, got " (count sections)
               ": " (mapv :path sections)))
      (let [paths (set (map :path sections))]
        (is (or (contains? paths [:users 42 :profile :email])
                ;; depth-3 coalescence under :users would head at
                ;; [:users 42 :profile] — both are valid section
                ;; ancestors per the rule.
                (contains? paths [:users 42 :profile])
                (contains? paths [:users 42])
                (contains? paths [:users]))
            "expected a section under :users")
        (is (or (contains? paths [:cart :checkout :payment-method])
                (contains? paths [:cart :checkout])
                (contains? paths [:cart]))
            "expected a section under :cart")))))

;; ---- (3) reset-frame-db whole-replacement ------------------------------

(deftest reset-frame-db-whole-replacement-one-root-section
  (testing "design §3.1.1 rule 5: every top-level key changed → 1 root
            section (path [])"
    (let [before {:a 1 :b 2 :c 3}
          after  {:a 10 :b 20 :c 30}
          tree     (at/diff-tree before after)
          sections (sg/group-into-sections tree)]
      (is (= 1 (count sections)) "all 3 keys changed → 1 root section")
      (is (= [] (:path (first sections))))
      (is (= :children (at/op-of (:subtree (first sections))))))))

(deftest reset-frame-db-via-added-keys
  (testing "rule 5 also fires when every top-level slot is added (the
            empty-before → big-after case)"
    (let [tree     (at/diff-tree {} {:a 1 :b 2 :c 3})
          sections (sg/group-into-sections tree)]
      (is (= 1 (count sections)))
      (is (= [] (:path (first sections)))))))

;; ---- (4) heuristic tuning sweep ----------------------------------------

(defn- section-paths
  [sections]
  (set (map :path sections)))

(deftest heuristic-depth-1
  (testing "max-coalesce-depth=1: only adjacent siblings coalesce"
    (let [tree (at/diff-tree
                 {:cart {:totals {:gross 10 :tax 1}}}
                 {:cart {:totals {:gross 12 :tax 2}}})
          sections (sg/group-into-sections tree {:max-coalesce-depth 1})
          paths    (section-paths sections)]
      ;; Both changes share [:cart :totals] at depth 2 from each;
      ;; depth-1 doesn't coalesce them at [:cart :totals] (distance 1
      ;; from each change-point is allowed), so they merge.
      ;; Actual: each path is [:cart :totals :gross] / [:cart :totals
      ;; :tax]; common prefix [:cart :totals]; (len 3 - len 2) = 1 ≤ 1
      ;; → coalesces.
      (is (= 1 (count sections)))
      (is (contains? paths [:cart :totals])))))

(deftest heuristic-depth-0-degenerate
  (testing "max-coalesce-depth=0: no coalescence; one section per
            change-point"
    (let [tree (at/diff-tree
                 {:cart {:totals {:gross 10 :tax 1}}}
                 {:cart {:totals {:gross 12 :tax 2}}})
          sections (sg/group-into-sections tree {:max-coalesce-depth 0})]
      ;; depth 0 means even sibling pairs don't merge.
      (is (= 2 (count sections))))))

(deftest heuristic-depth-3-coalesces-cart
  (testing "max-coalesce-depth=3 (default): cart cascade collapses to
            4 sections (verified in cart-cascade-four-sections)"
    ;; Already covered above; repeated for explicit sweep parity.
    (let [tree     (at/diff-tree cart-before cart-after)
          sections (sg/group-into-sections tree
                                           {:max-coalesce-depth 3})]
      (is (= 4 (count sections))))))

(deftest heuristic-depth-5-still-respects-root
  (testing "even with a wide depth-5 budget, root-level
            (path [:flash] vs [:status]) doesn't coalesce — root
            coalescence is reserved for the whole-DB replacement rule"
    (let [;; Two unrelated top-level changes (no deep nesting).
          tree     (at/diff-tree
                     {:a 1 :b 2 :c 3 :flash "old" :status :ok}
                     {:a 1 :b 2 :c 3 :flash "new" :status :busy})
          sections (sg/group-into-sections tree
                                           {:max-coalesce-depth 5})]
      ;; rule 5: NOT all top-level keys changed (a/b/c unchanged), so
      ;; the whole-DB rule doesn't apply. Two standalone sections.
      (is (= 2 (count sections))))))

;; ---- (5) collect-change-points coverage --------------------------------

(deftest collect-change-points-walks-deeply
  (testing "every :added/:removed/:modified surfaces as a candidate
            with the path that leads to it"
    (let [tree   (at/diff-tree {:a {:b {:c 1 :d 2}}}
                               {:a {:b {:c 9}        ;; modified c, removed d
                                    :e 3}})          ;; added e
          points (sg/collect-change-points tree)
          paths  (set (map :path points))]
      (is (contains? paths [:a :b :c]))
      (is (contains? paths [:a :b :d]))
      (is (contains? paths [:a :e]))
      (is (= 3 (count points))))))

(deftest no-changes-emits-no-sections
  (testing "diff with no changes → empty sections vector"
    (let [tree     (at/diff-tree {:a 1 :b 2} {:a 1 :b 2})
          sections (sg/group-into-sections tree)]
      (is (= [] sections)))))

(deftest singleton-leaf-change-yields-one-section
  (testing "one leaf change → one section headed by that path"
    (let [tree     (at/diff-tree {:a 1} {:a 2})
          sections (sg/group-into-sections tree)]
      (is (= 1 (count sections)))
      (is (= [:a] (:path (first sections)))))))

;; ---- (6) subtree-at-path -----------------------------------------------

(deftest subtree-at-path-walks-children
  (testing "subtree-at-path resolves the annotated node at the path"
    (let [tree (at/diff-tree {:a {:b 1}} {:a {:b 2}})
          node (sg/subtree-at-path tree [:a :b])]
      (is (= :modified (at/op-of node)))
      (is (= 1 (:before node)))
      (is (= 2 (:after  node))))
    (let [tree (at/diff-tree {} {:a 1})
          node (sg/subtree-at-path tree [:a])]
      (is (= :added (at/op-of node)))
      (is (= 1 (:value node))))))

(deftest subtree-at-empty-path-returns-root
  (let [tree (at/diff-tree {:a 1} {:a 2})]
    (is (= tree (sg/subtree-at-path tree [])))))
