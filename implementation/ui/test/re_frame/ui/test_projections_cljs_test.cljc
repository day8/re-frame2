(ns re-frame.ui.test-projections-cljs-test
  "`ui.test/attrs` (the MERGED projection) + `ui.test/text` over
  node-schema-v1 trees (jvm-tree-and-conversion-contract §Projections).
  Pure data — runs on both hosts."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ui.test :as uit]))

(defn- err-id
  [thunk]
  (try
    (thunk)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) ex
      (:rf.error/id (ex-data ex)))))

(def button
  {:tag :button
   :attrs {:data-testid "add" :class "cta"}
   :events {:on-click [:cart/add 42]}
   :children ["Add to cart"]})

;; ---------------------------------------------------------------------------
;; attrs — the merged projection
;; ---------------------------------------------------------------------------

(deftest attrs-on-elements
  (is (= {:data-testid "add" :class "cta" :on-click [:cart/add 42]}
         (uit/attrs button))
      "element → :attrs merged with :events (collision-free by construction)")
  (is (= {} (uit/attrs {:tag :br}))
      "an attributeless element projects {} (total)")
  (is (= [:cart/add 42] (:on-click (uit/attrs button)))
      "handler slots carry event vectors AS DATA — intent is an equality check"))

(deftest keyword-lookup-reads-fields-never-attributes
  (is (nil? (:on-click button))
      "(:on-click node) is a FIELD miss — attrs/events live under their own keys")
  (is (nil? (:data-testid button))
      "(:data-testid node) likewise — the attribute read is the projection"))

(deftest attrs-on-view-boundaries
  (is (= {:amount 9.99}
         (uit/attrs {:view-id :shop/price-tag :props {:amount 9.99}}))
      "view-boundary → the :props map")
  (is (= {} (uit/attrs {:view-id :shop/empty}))
      "a props-less boundary projects {} (absent-when-empty)"))

(deftest attrs-on-fragments-and-trusted-html
  (is (= {} (uit/attrs {:children [{:tag :i}]}))
      "fragment → {} — no attributes exist; total, not an error")
  (is (= {} (uit/attrs {:html "<b>x</b>"}))
      "trusted-HTML → {}"))

(deftest attrs-nil-punning-and-rejections
  (is (nil? (uit/attrs nil)) "nil → nil — threads through a missed find")
  (is (= :rf.error/ui-tree-malformed (err-id #(uit/attrs "Add to cart")))
      "a string (text content) is not a node")
  (is (= :rf.error/ui-test-tier-mismatch (err-id #(uit/attrs 42)))
      "a non-node value points at the tier split")
  (is (= :rf.error/ui-tree-malformed
         (err-id #(uit/attrs {:tag :div :view-id :x/y})))
      "malformed nodes fail loud in every consumer"))

;; ---------------------------------------------------------------------------
;; text — text descendants in document order
;; ---------------------------------------------------------------------------

(deftest text-concatenates-in-document-order
  (is (= "Add to cart" (uit/text button)))
  (is (= "n=2 of 10"
         (uit/text {:tag :p :children ["n=" "2" " of " "10"]}))
      "adjacent runs concatenate (no separators injected)")
  (is (= "ab"
         (uit/text {:view-id :shop/pair
                    :children [{:children [{:tag :i :children ["a"]}
                                           {:tag :i :children ["b"]}]}]}))
      "descends through view boundaries, fragments and elements alike")
  (is (= "" (uit/text {:tag :img}))
      "no text descendants → the empty string, not nil"))

(deftest text-skips-trusted-html
  (is (= "beforeafter"
         (uit/text {:tag :div
                    :children ["before"
                               {:html "<b>raw markup</b>"}
                               "after"]}))
      "trusted-HTML nodes contribute NOTHING — unparsed markup, not text"))

(deftest text-nil-punning-and-rejections
  (is (nil? (uit/text nil)) "nil → nil")
  (is (= :rf.error/ui-tree-malformed (err-id #(uit/text "Add to cart")))
      "text content is not a node — call text on the containing node")
  (is (= :rf.error/ui-test-tier-mismatch (err-id #(uit/text 42))))
  (is (= :rf.error/ui-tree-malformed
         (err-id #(uit/text {:tag :p :children [:oops]})))
      "malformed children fail loud"))

;; ---------------------------------------------------------------------------
;; The idiomatic read threads (guide-09 shapes, data-only)
;; ---------------------------------------------------------------------------

(deftest idiomatic-threads
  (let [tree   {:view-id :shop/card
                :children [{:tag :div :children [button]}]}
        by-tag (fn [t tag] (some #(when (= tag (:tag %)) %)
                                 (tree-seq map? :children t)))]
    (is (= [:cart/add 42]
           (-> (by-tag tree :button) uit/attrs :on-click)))
    (is (= "Add to cart"
           (-> (by-tag tree :button) uit/text)))
    (testing "miss nil-puns the whole thread"
      (is (nil? (-> (by-tag tree :form) uit/attrs :on-click))))))
