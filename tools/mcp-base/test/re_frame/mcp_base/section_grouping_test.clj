(ns re-frame.mcp-base.section-grouping-test
  "Tests for the path-headed cluster projection used at the MCP wire
  boundary. The pass takes the flat patch list produced
  by `re-frame.mcp-base.diff-encode/collect-patches` and projects it
  into N path-headed clusters — the same `sections-per-cluster`
  decomposition Xray ships in its panel renderer, recast over patches
  so mcp-base stays free of the xray dep."
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
  (testing "single :assoc at root path is the replace-app-db! signature"
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
      (is (= :modified (:section-kind s))
          "all-:assoc direct children WITHOUT :db-before context → :modified (rf2-ykv9a0): patch shape can't prove the container is new")
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
;; Worked examples — the three Xray cluster cases recast over patches.
;; ---------------------------------------------------------------------------

(deftest cart-cascade-projects-to-3-cart-and-non-cart-sections
  ;; The classic cart-event drain: line item + totals + user
  ;; last-edit + flash. With max-coalesce-depth=3 over patches, the
  ;; three [:cart ...] patches all sit within depth-3 of the shared
  ;; [:cart] ancestor and coalesce into one [:cart]-headed section.
  ;; [:user :last-edited-at] is a singleton → promotes to [:user];
  ;; [:flash] is a top-level singleton → stays at [:flash].
  ;;
  ;; Three sections — fewer than Xray's panel-side annotated-tree
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

(deftest all-assoc-direct-children-classify-as-added-only-with-db-before-proof
  ;; The patch grammar uses :assoc for BOTH inserted and
  ;; changed leaves, so all-:assoc direct-child shape alone CANNOT prove
  ;; a container is newly added. `:added` is claimed only when :db-before
  ;; proves the container was absent.
  (let [patches  [[[:user :name]  :assoc "ada"]
                  [[:user :email] :assoc "ada@example.com"]]]
    (testing "without :db-before context → conservative :modified (no false :added)"
      (is (= :modified (:section-kind (first (sg/group-patches-into-sections patches))))))
    (testing ":db-before proves [:user] is genuinely new → :added"
      (is (= :added (:section-kind
                      (first (sg/group-patches-into-sections
                               patches {:db-before {}}))))
          "container [:user] absent in db-before ⇒ newly-introduced subtree"))
    (testing ":db-before shows [:user] already existed (a sibling changed) → :modified, NOT a false :added"
      (is (= :modified (:section-kind
                         (first (sg/group-patches-into-sections
                                  patches {:db-before {:user {:name "bob"}}}))))
          "existing [:user] whose direct children changed is a modification, not an addition"))))

(deftest synthetic-direct-child-cluster-under-absent-container-classifies-as-added
  ;; The genuine :added shape. An advanced consumer
  ;; supplies a synthetic patch list (NOT from collect-patches, which
  ;; emits a whole-subtree singleton for a brand-new key): direct-child
  ;; :assoc patches under a container that :db-before proves was absent.
  ;; THAT is a real newly-introduced subtree → :added.
  (let [patches [[[:user :name]  :assoc "ada"]
                 [[:user :email] :assoc "ada@example.com"]]]
    (is (= :added (:section-kind
                    (first (sg/group-patches-into-sections patches {:db-before {}}))))
        "[:user] absent in db-before + all-:assoc direct children ⇒ :added")
    (is (= :added (:section-kind
                    (first (sg/group-patches-into-sections patches {:db-before {:session :idle}}))))
        "[:user] still absent (only :session present) ⇒ :added")))

(deftest db-before-with-stored-nil-counts-container-as-present
  ;; A stored `nil` under the container key is PRESENT, not absent — the
  ;; sentinel-based path-present? check distinguishes a stored nil from a
  ;; missing key. So an all-:assoc direct-child cluster whose container
  ;; key is present-but-nil classifies :modified, not a false :added.
  (let [patches [[[:user :name]  :assoc "ada"]
                 [[:user :email] :assoc "ada@example.com"]]]
    (is (= :modified (:section-kind
                       (first (sg/group-patches-into-sections patches {:db-before {:user nil}}))))
        "[:user] present (stored nil) ⇒ :modified, not a false :added")))

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

(deftest sections-are-sorted-ascending-by-final-section-path
  ;; rf2-8y5pen: the initial sort keys patches by their FULL path, but
  ;; coalescing (`group-by-ancestor`) and singleton-promotion both
  ;; NARROW a cluster's prefix to a shorter common-ancestor path —
  ;; changing its sort key. `pr-str` of a vector closes with `]` (char
  ;; 93) but separates elements with a space (char 32), so an
  ;; ancestor's `pr-str` is ALWAYS greater than its own descendants':
  ;; `(pr-str [:cart])` > `(pr-str [:cart-items])` even though
  ;; `:cart` < `:cart-items` as keywords. A walk-order-only sort (never
  ;; re-deriving order post-coalescing) can therefore emit sections
  ;; out of the documented ascending order. This test asserts the
  ;; FINAL output is always ascending by `(pr-str :section-path)` —
  ;; the §Ordering contract in `spec/section-grouping.md` — not merely
  ;; stable across input-order permutations (the older test above).
  (testing "bead repro: [:cart] (narrowed ancestor) vs [:cart-summary] (untouched sibling)"
    ;; {:cart {:qty 1} :cart-summary old} -> {:cart {:qty 5} :cart-summary new}.
    ;; [:cart :qty] promotes (singleton, depth > 1) to the [:cart]
    ;; ancestor; [:cart-summary] is an untouched top-level singleton.
    ;; Sorted by full path pre-coalescing, [:cart ...] < [:cart-summary]
    ;; (matching keyword order) — the walk-order-only bug preserved
    ;; that ordering into the output. But post-promotion the KEYS
    ;; being compared are [:cart] vs [:cart-summary], and
    ;; `(pr-str [:cart])` = "[:cart]" > `(pr-str [:cart-summary])` =
    ;; "[:cart-summary]" because `]` (93) > `-` (45) at the
    ;; first point of difference right after the shared "[:cart"
    ;; prefix. The contract requires [:cart-summary] first.
    (let [patches  [[[:cart :qty] :assoc 5]
                    [[:cart-summary] :assoc :new]]
          sections (sg/group-patches-into-sections patches)
          paths    (mapv :section-path sections)]
      (is (= [:cart] (:section-path (first (filter #(= [:cart] (:section-path %)) sections))))
          "sanity: [:cart :qty] promotes to the [:cart] ancestor")
      (is (= [[:cart-summary] [:cart]] paths)
          "ancestor [:cart] sorts AFTER [:cart-summary] by pr-str, even though it was
           walked first — the contract order, not the walk order")))
  (testing "general property: output section-paths are ascending by pr-str, regardless of input order"
    (let [patches [[[:cart :qty] :assoc 5]
                   [[:cart-summary] :assoc :new]
                   [[:a :b :c] :assoc 1]
                   [[:ab] :assoc 2]
                   [[:flash] :assoc "Saved"]]]
      (doseq [ordering (list patches
                             (vec (reverse patches))
                             (shuffle patches))]
        (let [sections (sg/group-patches-into-sections ordering)
              paths    (mapv (comp pr-str :section-path) sections)]
          (is (= (sort paths) paths)
              (str "sections must be ascending by (pr-str :section-path); got " paths)))))))
