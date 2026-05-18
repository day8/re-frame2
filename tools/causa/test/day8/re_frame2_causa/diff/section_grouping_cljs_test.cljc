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

;; ---- (7) count-rendered-nodes — renderer-faithful chip accounting ------
;;
;; rf2-szzjh regression. The renderer collapses all `:same` direct
;; children of a `:children` container into a single chip. The earlier
;; counter charged each `:same` sibling as 1 (verbatim), inflating the
;; cost of legitimately-coalesced clusters and shattering them under
;; the `max-unchanged-context` split rule. See
;; `ai/findings/2026-05-19-causa-section-grouping-heuristics.md` §5.1.

(deftest count-rendered-nodes-charges-same-cluster-as-one-chip
  (testing "rf2-szzjh: a :children container with many :same siblings
            charges the WHOLE :same cluster as 1 (the renderer's
            `(N entries unchanged)` chip), not 1 per sibling"
    (let [;; 10 keys: 2 modified, 8 unchanged.
          before   (into {} (for [i (range 10)] [(keyword (str "k" i)) i]))
          after    (assoc before :k0 :changed :k1 :changed)
          tree     (at/diff-tree before after)]
      ;; Total nodes via the new counter:
      ;;   container (1) + 2 :modified (2) + 1 :same-chip (1) = 4
      ;; Old behaviour would have been: 1 + 2 + 8 = 11.
      (is (= 4 (sg/count-rendered-nodes tree))
          "container + 2 changed + 1 chip"))))

(deftest count-rendered-nodes-no-same-children-no-chip
  (testing "rf2-szzjh: when every child is changed, no chip is charged"
    (let [before {:a 1 :b 2 :c 3}
          after  {:a 9 :b 8 :c 7}
          tree   (at/diff-tree before after)]
      ;; All children changed → no :same children → no chip.
      ;;   container (1) + 3 :modified (3) = 4
      (is (= 4 (sg/count-rendered-nodes tree))))))

(deftest count-rendered-nodes-leaf-ops-count-as-one
  (testing "rf2-szzjh: each leaf op (:added :removed :modified :same)
            counts as 1; only :children recurses"
    (let [tree (at/diff-tree {:a 1} {:a 2})
          leaf (sg/subtree-at-path tree [:a])]
      (is (= 1 (sg/count-rendered-nodes leaf))))
    (let [tree  (at/diff-tree {:a 1 :b 2} {:a 1 :b 2})]
      ;; All same → root degrades to :same; counts as 1.
      (is (= 1 (sg/count-rendered-nodes tree))))))

(deftest count-rendered-nodes-recursion-skips-only-same-children
  (testing "rf2-szzjh: non-:same children still recurse normally — a
            nested `:children` subtree's cost still contributes verbatim"
    (let [;; outer container has one :modified and one :children child
          ;; (which itself has nested changes); :same siblings stay flat.
          before {:outer {:inner-changed {:x 1 :y 2}
                          :inner-same    "stable"}
                  :touched 0
                  :pad-a 1 :pad-b 2 :pad-c 3}
          after  {:outer {:inner-changed {:x 9 :y 8}
                          :inner-same    "stable"}
                  :touched 1
                  :pad-a 1 :pad-b 2 :pad-c 3}
          tree   (at/diff-tree before after)]
      ;; root :children — children: [:outer :children] [:touched :modified]
      ;;                             + 3 :same (:pad-a/b/c)
      ;; root count = 1
      ;;            + count(:outer) + count(:touched)   ; non-same recurse
      ;;            + 1                                  ; the :same chip
      ;; :outer count = 1 (container)
      ;;              + count(:inner-changed)            ; non-same
      ;;              + 1                                ; :same chip for :inner-same
      ;; :inner-changed count = 1 (container)
      ;;                      + 2 :modified
      ;;                      + 0 (no :same children)
      ;;                      = 3
      ;; :outer = 1 + 3 + 1 = 5
      ;; :touched = 1
      ;; root = 1 + 5 + 1 + 1 = 8
      (is (= 8 (sg/count-rendered-nodes tree))))))

;; ---- (8) pg/large-multi-tier regression --------------------------------
;;
;; rf2-szzjh + rf2-ogkh0. The corpus pathology: 50 new catalog SKUs
;; merged into a 200-key catalog map. Pre-fix counter reported 251 for
;; the [:catalog] subtree, defeated every (depth, context) ≤ 250, and
;; shattered the cluster into 50 singleton sections. Post-fix the
;; counter reports ≈52 (1 + 50 changed + 1 chip), well under the
;; default budget; the cluster survives as one section.

(defn- catalog-pad
  "Build a 200-key catalog map of stable `:sku-NNN` entries."
  []
  (into {} (for [i (range 200)]
             [(keyword (str "sku-" (format "%03d" i)))
              {:price (* i 7) :stock (mod i 13)}])))

(defn- catalog-with-new
  "Catalog after merge: pad + 50 new `:sku-new-NNN` entries."
  []
  (merge (catalog-pad)
         (into {} (for [i (range 50)]
                    [(keyword (str "sku-new-" (format "%03d" i)))
                     {:price (* i 11) :stock (mod i 9)}]))))

(deftest large-multi-tier-counter-no-longer-walks-same-cluster
  (testing "rf2-szzjh + rf2-ogkh0 pg/large-multi-tier: the counter cost
            of the [:catalog] subtree (50 changed + 200 unchanged keys)
            tracks the renderer's chip-collapse — small upper bound
            (~52: container + 50 changes + 1 chip), NOT the 251-node
            pre-fix verbatim walk that defeated every (depth, context)
            ≤ 250 in the rf2-ogkh0 sweep."
    (let [before    {:catalog (catalog-pad)}
          after     {:catalog (catalog-with-new)}
          tree      (at/diff-tree before after)
          catalog   (sg/subtree-at-path tree [:catalog])
          cost      (sg/count-rendered-nodes catalog)]
      (is (= :children (at/op-of catalog))
          ":catalog subtree is a :children container post-diff")
      ;; Tight upper bound: 1 (container) + 50 (:added leaves) + 1 (chip) = 52.
      ;; Pre-fix this would have been 1 + 50 + 200 = 251.
      (is (= 52 cost)
          (str "expected ~52 (chip-aware), got " cost
               " — pre-fix the counter walked the :same cluster verbatim "
               "and reported 251.")))))

(deftest large-multi-tier-coalesces-at-fixed-budget
  (testing "rf2-szzjh + rf2-ogkh0 pg/large-multi-tier: at a budget that
            accommodates the 50 changes (context=60), the [:catalog]
            cluster survives as ONE section. Pre-fix even context=250
            shattered it into 50 singleton sections."
    (let [before   {:catalog (catalog-pad)
                    :auth    {:user {:name "alice"}}
                    :cart    {:items [{:id 7 :qty 1}]}}
          after    {:catalog (catalog-with-new)
                    :auth    {:user {:name "alice" :last-seen 42}}
                    :cart    {:items [{:id 7 :qty 1} {:id 22 :qty 1}]}}
          tree     (at/diff-tree before after)
          ;; context=60 chosen to clear the 52-node :catalog cost. The
          ;; depth=2 budget lets [:auth :user :last-seen] coalesce up to
          ;; [:auth :user], [:cart :items 1] up to [:cart :items].
          sections (sg/group-into-sections tree
                                           {:max-coalesce-depth    2
                                            :max-unchanged-context 60})
          paths    (set (map :path sections))]
      ;; Expected: 3 sections — [:catalog], [:auth :user], [:cart :items].
      ;; Pre-fix this shattered into 52 under ANY context ≤ 250.
      (is (= 3 (count sections))
          (str "expected 3 sections, got " (count sections)
               ": " (mapv :path sections)))
      (is (contains? paths [:catalog])
          ":catalog stays coalesced (post-fix; pre-fix shattered into 50 shards)")
      (is (or (contains? paths [:auth :user])
              (contains? paths [:auth :user :last-seen]))
          "auth section present")
      (is (or (contains? paths [:cart :items])
              (contains? paths [:cart]))
          "cart section present"))))

(deftest medium-multi-tier-coalesces-at-default-budget
  (testing "rf2-szzjh: a smaller variant of the same shape — 10 new
            catalog keys merged into a 40-key catalog — coalesces into
            ONE [:catalog] section at the tuned default (depth=2,
            context=40) post-fix. The chip-aware counter prevents the
            200-sibling shatter; the smaller change-count fits the
            default budget."
    (let [pad      (into {} (for [i (range 40)]
                              [(keyword (str "sku-" i)) {:price i}]))
          extras   (into {} (for [i (range 10)]
                              [(keyword (str "sku-new-" i)) {:price (+ 100 i)}]))
          before   {:catalog pad}
          after    {:catalog (merge pad extras)}
          tree     (at/diff-tree before after)
          ;; Tuned default — depth=2, context=40 (rf2-ogkh0).
          sections (sg/group-into-sections tree)]
      (is (= 1 (count sections))
          (str "expected 1 section, got " (count sections)
               ": " (mapv :path sections)))
      (is (= [:catalog] (:path (first sections)))))))
