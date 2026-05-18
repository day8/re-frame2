(ns re-frame.mcp-base.section-grouping-test
  "Tests for the path-headed cluster projection used at the MCP wire
  boundary (rf2-qeous). The pass takes the flat patch list produced
  by `re-frame.mcp-base.diff-encode/collect-patches` and projects it
  into N path-headed clusters — the same `sections-per-cluster`
  decomposition Causa ships in its panel renderer (rf2-gfxmk Phase 1
  of rf2-abts7), recast over patches so mcp-base stays free of the
  causa dep."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.mcp-base.diff-encode :as de]
            [re-frame.mcp-base.section-grouping :as sg]))

;; ---------------------------------------------------------------------------
;; Trivial cases — empty, root replacement.
;; ---------------------------------------------------------------------------

(deftest empty-patches-emit-no-sections
  (is (= [] (sg/group-patches-into-sections [])))
  (is (= [] (sg/group-patches-into-sections nil))))

(deftest root-replacement-projects-to-one-root-section
  (testing "single :assoc at root path is the reset-frame-db! signature"
    (let [patches  [[[] :assoc {:new :db}]]
          sections (sg/group-patches-into-sections patches)]
      (is (= 1 (count sections)))
      (is (= [] (:section-path (first sections))))
      (is (= :modified (:section-kind (first sections))))
      (is (= patches (:patches (first sections)))))))

;; ---------------------------------------------------------------------------
;; Singleton — promote-to-parent breadcrumb rule.
;; ---------------------------------------------------------------------------

(deftest deep-singleton-promotes-to-parent-breadcrumb
  ;; A single change at [:user :prefs :theme] heads as [:user :prefs] —
  ;; the parent gives container context.
  (let [patches  [[[:user :prefs :theme] :assoc :dark]]
        sections (sg/group-patches-into-sections patches)]
    (is (= 1 (count sections)))
    (is (= [:user :prefs] (:section-path (first sections))))
    (is (= patches (:patches (first sections))))))

(deftest top-level-singleton-keeps-full-path
  ;; [:flash] is already a useful breadcrumb at depth-1; don't promote
  ;; to [] (which would conflict with the whole-DB rule).
  (let [patches  [[[:flash] :assoc "Saved"]]
        sections (sg/group-patches-into-sections patches)]
    (is (= 1 (count sections)))
    (is (= [:flash] (:section-path (first sections))))))

;; ---------------------------------------------------------------------------
;; Coalescence — siblings within the depth budget merge under a
;; common ancestor.
;; ---------------------------------------------------------------------------

(deftest siblings-under-common-prefix-coalesce-into-one-section
  ;; Both patches are leaves of [:cart :items 0] — they merge under
  ;; the [:cart :items 0] head.
  (let [patches  [[[:cart :items 0 :qty] :assoc 2]
                  [[:cart :items 0 :discount] :assoc 0.1]]
        sections (sg/group-patches-into-sections patches)]
    (is (= 1 (count sections)))
    (let [s (first sections)]
      (is (= [:cart :items 0] (:section-path s)))
      (is (= :added (:section-kind s))
          "all-:assoc, all direct children → :added (newly-added subtree)")
      (is (= 2 (count (:patches s)))))))

(deftest unrelated-root-keys-stand-as-separate-sections
  ;; Empty common prefix → no coalesce. Two separate sections, each
  ;; with its own root-key breadcrumb.
  (let [patches  [[[:flash] :assoc "Saved"]
                  [[:status] :assoc :ok]]
        sections (sg/group-patches-into-sections patches)]
    (is (= 2 (count sections)))
    (is (= [:flash] (:section-path (first sections))))
    (is (= [:status] (:section-path (second sections))))))

(deftest cluster-coalescence-respects-max-depth
  ;; Default max-coalesce-depth is 3. Two patches whose common
  ;; ancestor sits 4 levels away from both should NOT coalesce.
  (let [patches  [[[:a :b :c :d :leaf1] :assoc 1]
                  [[:a :b :c :d :leaf2] :assoc 2]]
        sections (sg/group-patches-into-sections patches)]
    (is (= 1 (count sections))
        "common ancestor [:a :b :c :d] sits 1 level from each leaf — within budget"))
  ;; But two patches with common ancestor [:a] and depth distance 5
  ;; each → outside budget; separate clusters.
  (let [patches  [[[:a :b :c :d :e :leaf1] :assoc 1]
                  [[:a :B :C :D :E :leaf2] :assoc 2]]
        sections (sg/group-patches-into-sections patches)]
    (is (= 2 (count sections))
        "common ancestor [:a] is 5 levels away from each leaf — out of budget")))

(deftest tunable-max-depth-via-opts
  (let [patches [[[:a :b :c :d :e :leaf1] :assoc 1]
                 [[:a :B :C :D :E :leaf2] :assoc 2]]]
    (is (= 1 (count (sg/group-patches-into-sections patches {:max-coalesce-depth 10})))
        "raising the budget admits the previously-rejected coalescence")))

;; ---------------------------------------------------------------------------
;; Worked examples — the three rf2-gfxmk cases recast over patches.
;; ---------------------------------------------------------------------------

(deftest cart-cascade-projects-to-3-cart-and-non-cart-sections
  ;; The classic cart-event cascade: line item + totals + user
  ;; last-edit + flash. With max-coalesce-depth=3 over patches, the
  ;; three [:cart ...] patches all sit within depth-3 of the shared
  ;; [:cart] ancestor and coalesce into one [:cart]-headed section.
  ;; [:user :last-edited-at] is a singleton → promotes to [:user];
  ;; [:flash] is a top-level singleton → stays at [:flash].
  ;;
  ;; Three sections — fewer than Causa's panel-side annotated-tree
  ;; projection produces (which can split [:cart :items] vs
  ;; [:cart :totals] because the annotated tree carries the
  ;; container structure that lets the renderer split overfilled
  ;; sections). The patch-based projection is a cheaper signal: same
  ;; cluster-intent the agent needs, slightly coarser headers.
  (let [patches  [[[:cart :items 0 :qty]      :assoc 3]
                  [[:cart :totals :line]      :assoc 30]
                  [[:cart :totals :grand]     :assoc 33]
                  [[:user :last-edited-at]    :assoc 12345]
                  [[:flash]                   :assoc "Cart updated"]]
        sections (sg/group-patches-into-sections patches)]
    (is (= 3 (count sections)))
    (let [paths (mapv :section-path sections)]
      (is (some #(= [:cart] %) paths))
      (is (some #(= [:user] %) paths))
      (is (some #(= [:flash] %) paths)))
    ;; The [:cart] cluster carries all 3 cart patches.
    (let [cart-s (first (filter #(= [:cart] (:section-path %)) sections))]
      (is (= 3 (count (:patches cart-s))))
      (is (= :modified (:section-kind cart-s))
          "patches span multiple depths under [:cart] → :modified"))))

(deftest sparse-100-key-app-db-with-two-unrelated-changes-projects-to-2-sections
  ;; The 100-key sparse worked example: only [:account-23 :balance]
  ;; and [:account-71 :status] change. NOT 100 sections, NOT 1 root
  ;; section — exactly 2.
  (let [patches  [[[:account-23 :balance] :assoc 500]
                  [[:account-71 :status]  :assoc :frozen]]
        sections (sg/group-patches-into-sections patches)]
    (is (= 2 (count sections)))
    (is (every? #(< (count (:section-path %)) 3) sections)
        "no spurious deeper aggregation — each singleton stays scoped")))

;; ---------------------------------------------------------------------------
;; section-kind classification.
;; ---------------------------------------------------------------------------

(deftest all-dissoc-patches-classify-as-removed
  (let [patches  [[[:user :token] :dissoc]
                  [[:user :session-id] :dissoc]]
        sections (sg/group-patches-into-sections patches)]
    (is (= :removed (:section-kind (first sections))))))

(deftest all-assoc-direct-children-classify-as-added
  ;; All patches are :assoc at exactly one level below the section
  ;; path → :added.
  (let [patches  [[[:user :name]  :assoc "ada"]
                  [[:user :email] :assoc "ada@example.com"]]
        sections (sg/group-patches-into-sections patches)]
    (is (= :added (:section-kind (first sections))))))

(deftest mixed-assoc-and-dissoc-classify-as-modified
  (let [patches  [[[:user :name]  :assoc "ada"]
                  [[:user :token] :dissoc]]
        sections (sg/group-patches-into-sections patches)]
    (is (= :modified (:section-kind (first sections))))))

(deftest assoc-at-non-uniform-depth-classifies-as-modified
  ;; Patches under [:user] but at different depths — section is
  ;; :modified (conservative; the cluster isn't a wholly-new container).
  (let [patches  [[[:user :name]              :assoc "ada"]
                  [[:user :prefs :theme]      :assoc :dark]]
        sections (sg/group-patches-into-sections patches)]
    (is (= :modified (:section-kind (first sections))))))

;; ---------------------------------------------------------------------------
;; Round-trip — sections->patches inverts group-patches-into-sections.
;; ---------------------------------------------------------------------------

(deftest sections-roundtrip-via-flatten-then-apply
  ;; The load-bearing claim: concatenating sections back to a patch
  ;; list and applying against db-before reproduces db-after.
  (let [db-before {:cart {:items [{:sku "A1" :qty 1}]
                          :totals {:line 10 :grand 10}}
                   :user  {:id 7 :last-edited-at 0}}
        db-after  {:cart {:items [{:sku "A1" :qty 3}]
                          :totals {:line 30 :grand 30}}
                   :user  {:id 7 :last-edited-at 12345}
                   :flash "Updated"}
        patches  (de/collect-patches db-before db-after [])
        sections (sg/group-patches-into-sections patches)
        flat     (sg/sections->patches sections)
        rebuilt  (de/apply-patches db-before flat)]
    (is (= db-after rebuilt)
        "sections → flat patches → apply reconstructs db-after exactly")))

(deftest sections-preserve-all-patches
  ;; Every input patch lands in exactly one section.
  (let [patches  [[[:a :x] :assoc 1]
                  [[:a :y] :assoc 2]
                  [[:b :p] :assoc 3]
                  [[:b :q] :dissoc]
                  [[:c]    :assoc :singleton]]
        sections (sg/group-patches-into-sections patches)
        flat     (sg/sections->patches sections)]
    (is (= (set patches) (set flat))
        "no patches dropped or duplicated")
    (is (= (count patches) (count flat))
        "exact count preserved")))

;; ---------------------------------------------------------------------------
;; Stable ordering — same input ⇒ same section order across runs.
;; ---------------------------------------------------------------------------

(deftest section-order-is-deterministic-across-input-orders
  (let [patches-a [[[:cart :items 0 :qty] :assoc 2]
                   [[:flash] :assoc "Saved"]
                   [[:cart :totals :grand] :assoc 30]]
        patches-b [[[:flash] :assoc "Saved"]
                   [[:cart :totals :grand] :assoc 30]
                   [[:cart :items 0 :qty] :assoc 2]]
        sections-a (sg/group-patches-into-sections patches-a)
        sections-b (sg/group-patches-into-sections patches-b)]
    (is (= (mapv :section-path sections-a)
           (mapv :section-path sections-b))
        "input order doesn't alter section order — the sort makes it stable")))
