(ns re-frame.ui.test-selectors-cljs-test
  "The CLOSED `ui.test/find` / `find-all` selector grammar over hand-built
  node-schema-v1 trees (drafts/ui-test-selector-grammar.md) — pure data,
  so the table runs on both hosts. Var selectors + the compiled-view-fn
  guard need real defviews and are pinned in the JVM render suite
  (re-frame.ui.test-render-jvm-test)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ui.test :as uit]))

(defn- err-id
  "nil when the thunk returns; the :rf.error/id when it throws."
  [thunk]
  (try
    (thunk)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) ex
      (:rf.error/id (ex-data ex)))))

;; A hand-built tree exercising all five node variants. (Hand-built trees
;; need not be canonical — the matcher consumes the node schema, not the
;; canonical form.)
(def price-tag
  {:view-id :shop/price-tag
   :props   {:amount 9.99}
   :children [{:tag :span :attrs {:class "price"} :children ["$9.99"]}]})

(def add-button
  {:tag :button
   :attrs {:data-testid "add"}
   :events {:on-click [:cart/add 42]
            :on-hover {:event [:cart/peek 42] :prevent-default true}}
   :children ["Add " "to cart"]})

(def tree
  {:view-id :shop/product-card
   :props   {:product {:id 42}}
   :rf.ui/tree-version 1
   :children
   [{:tag :div
     :attrs {:class "card" :data-testid "card-42"}
     :children
     [price-tag
      add-button
      {:children [{:tag :i :children ["a"]}
                  {:tag :i :children ["b"]}]}      ; fragment
      {:html "<b>raw & trusted</b>"}]}]})          ; trusted-HTML leaf

;; ---------------------------------------------------------------------------
;; tag-kw — unqualified keyword, exact element-tag match
;; ---------------------------------------------------------------------------

(deftest tag-selector
  (is (= add-button (uit/find tree :button)))
  (is (= "a" (-> (uit/find tree :i) :children first))
      "find returns the FIRST match in document order")
  (is (= 2 (count (uit/find-all tree :i)))
      "find-all returns every match in document order")
  (is (= add-button (uit/find add-button :button))
      "a single selector tests the given node ITSELF first")
  (is (nil? (uit/find tree :form)) "find misses to nil")
  (is (= [] (uit/find-all tree :form)) "find-all misses to []"))

;; ---------------------------------------------------------------------------
;; view-sel — qualified keyword matches the view-boundary node
;; ---------------------------------------------------------------------------

(deftest view-id-selector
  (is (= price-tag (uit/find tree :shop/price-tag))
      "a qualified keyword matches the boundary node, not a root element")
  (is (= tree (uit/find tree :shop/product-card))
      "the root boundary matches itself (node tested before descendants)")
  (is (nil? (uit/find tree :shop/missing)))
  (testing "the unqualified/qualified split disambiguates tags from view ids"
    (is (nil? (uit/find {:view-id :shop/price-tag} :price-tag))
        "an unqualified keyword never matches a view boundary")
    (is (nil? (uit/find {:tag :button} :shop/button))
        "a qualified keyword never matches an element tag"))
  (testing "fragment- and nil-rooted views are matchable via the boundary"
    (let [nil-rooted  {:view-id :shop/empty}
          frag-rooted {:view-id :shop/pair
                       :children [{:tag :i :children ["a"]}
                                  {:tag :i :children ["b"]}]}]
      (is (= nil-rooted (uit/find nil-rooted :shop/empty)))
      (is (= frag-rooted (uit/find frag-rooted :shop/pair))))))

;; ---------------------------------------------------------------------------
;; attr-map — rf= over the attrs projection (events + props ride the rule)
;; ---------------------------------------------------------------------------

(deftest attr-map-selector
  (is (= add-button (uit/find tree {:data-testid "add"}))
      "stable test ids")
  (is (= add-button (uit/find tree {:on-click [:cart/add 42]}))
      "intent as a selector — event vectors match by value via :events")
  (is (= add-button (uit/find tree {:data-testid "add"
                                    :on-click [:cart/add 42]}))
      "every entry must match (attrs + events in ONE projection)")
  (is (nil? (uit/find tree {:data-testid "add" :on-click [:cart/add 43]}))
      "one mismatched entry misses")
  (is (= price-tag (uit/find tree {:amount 9.99}))
      "prop matching on view-boundary nodes rides the same rule")
  (is (nil? (uit/find tree {:missing nil}))
      "a key absent from the projection never matches — even against nil")
  (is (= add-button (uit/find tree {:on-hover {:event [:cart/peek 42]
                                               :prevent-default true}}))
      "options maps compare structurally (rf=)"))

;; The {} universe: root boundary, :div, price-tag boundary, :span,
;; :button, fragment, 2× :i, trusted-HTML = 9. Pin it exactly.
(deftest attr-map-empty-matches-every-map-node
  (is (= 9 (count (uit/find-all tree {})))))

;; ---------------------------------------------------------------------------
;; pred-fn — the escape; receives MAP nodes only, text is never visited
;; ---------------------------------------------------------------------------

(deftest pred-fn-selector
  (is (= add-button (uit/find tree #(= :button (:tag %)))))
  (is (= [] (uit/find-all tree (fn [n] (not (map? n)))))
      "pred-fn selectors never receive text — every visited node is a map")
  (is (= 9 (count (uit/find-all tree map?)))
      "pred-fn traversal covers the same map-node universe as {}")
  (is (= [add-button]
         (uit/find-all tree #(contains? (uit/attrs %) :on-click)))
      "the node argument supports the read surface (attrs projection)"))

;; ---------------------------------------------------------------------------
;; Traversal + composition
;; ---------------------------------------------------------------------------

(deftest document-order-and-composition
  (is (= [:div :span :button :i :i]
         (into [] (keep :tag) (uit/find-all tree map?)))
      "depth-first pre-order: elements appear in document order")
  (is (= {:tag :span :attrs {:class "price"} :children ["$9.99"]}
         (uit/find (uit/find tree :shop/price-tag) :span))
      "a found node is itself a valid tree argument — finds compose")
  (is (nil? (uit/find nil :button)) "nil tree nil-puns through find")
  (is (= [] (uit/find-all nil :button)) "nil tree yields [] from find-all")
  (is (nil? (-> tree (uit/find :form) (uit/find :button)))
      "a missed find composes to nil, not a throw"))

;; ---------------------------------------------------------------------------
;; Failure + tier behaviour
;; ---------------------------------------------------------------------------

(deftest css-string-selector-is-tier-3
  (is (= :rf.error/ui-test-tier-mismatch
         (err-id #(uit/find tree "button.add")))
      "a CSS string is the Tier-3 contract — the error points at query")
  (is (= :rf.error/ui-test-tier-mismatch
         (err-id #(uit/find-all tree "[data-testid=add]")))))

(deftest vector-selector-not-shipped-open-2
  (is (= :rf.error/ui-test-bad-selector
         (err-id #(uit/find tree [:form :button])))
      "OPEN-2 (path form) is demand-bar: rejected, naming composed find")
  (is (= :rf.error/ui-test-bad-selector
         (err-id #(uit/find-all tree [])))))

(deftest non-selector-values-rejected
  (is (= :rf.error/ui-test-bad-selector (err-id #(uit/find tree 42))))
  (is (= :rf.error/ui-test-bad-selector (err-id #(uit/find tree 'button)))))

(deftest non-node-trees-rejected
  (is (= :rf.error/ui-tree-malformed
         (err-id #(uit/find "just text" :button)))
      "text content is not a queryable node")
  (is (= :rf.error/ui-test-tier-mismatch
         (err-id #(uit/find 42 :button)))
      "a non-tree value points at the tier split"))

(deftest malformed-nodes-fail-loud
  (is (= :rf.error/ui-tree-malformed
         (err-id #(uit/find {:tag :div :html "x"} :div)))
      "more than one primary discriminating field")
  (is (= :rf.error/ui-tree-malformed
         (err-id #(uit/find {:props {:a 1}} :div)))
      "no discriminating field at all")
  (is (= :rf.error/ui-tree-malformed
         (err-id #(uit/find {:tag :div :children [42]} :span)))
      "a :children entry that is neither node nor text"))

(deftest query-tier-split
  (is (= :rf.error/ui-test-tier-mismatch
         (err-id #(uit/query tree "button")))
      "a structural tree handed to query points back at find")
  (is (= :rf.error/ui-test-tier-mismatch
         (err-id #(uit/query nil "button")))
      "no mounted roots exist yet — query has no Tier-1 behaviour"))
