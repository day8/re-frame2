(ns re-frame.bench.hicasso.arm2.template-cljs-test
  "TIER 1's ENTRY CONDITION — the shape signature and what it refuses
  (rf2-2rtt6.10).

  The signature is the only thing standing between a hole plan and a
  wrong patch: a shape that says it matches when it does not will write
  text into the wrong node, and it will do so *silently*, because tier 1
  never looks at the tree it is patching. So the refusals are asserted
  here, on a runtime with no DOM, and the plan's behaviour is asserted in
  the browser suite.

  Nothing in this file builds a plan — [[template/plan-for]] creates DOM
  nodes. That split is deliberate: the decision half is testable
  everywhere, the emission half is testable where DOM is."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.bench.hicasso.arm2.template :as t]))

;; ---------------------------------------------------------------------------
;; What the signature reads
;; ---------------------------------------------------------------------------

(deftest a-signature-reads-shape-and-never-data
  (testing "two instances of one shape share a signature"
    (is (= (t/signature [:li.row {:data-i 1} [:span "a"]])
           (t/signature [:li.row {:data-i 2} [:span "b"]]))))
  (testing "a different tag is a different shape"
    (is (not= (t/signature [:li.row {:data-i 1}])
              (t/signature [:ul.row {:data-i 1}]))))
  (testing "a different prop KEY is a different shape"
    (is (not= (t/signature [:li {:data-i 1}])
              (t/signature [:li {:data-j 1}]))))
  (testing "a different child count is a different shape"
    (is (not= (t/signature [:div "a"])
              (t/signature [:div "a" "b"]))))
  (testing "the tag's own shorthand is part of the shape"
    (is (not= (t/signature [:div.a "x"]) (t/signature [:div.b "x"])))))

(deftest key-is-identity-not-shape
  (testing "two rows differing only in :key share one plan"
    (is (= (t/signature [:li {:key 1 :data-i 1}])
           (t/signature [:li {:key 2 :data-i 1}])))))

;; ---------------------------------------------------------------------------
;; What the signature refuses
;; ---------------------------------------------------------------------------

(deftest a-seq-child-refuses-the-shape
  (testing "a for-expansion's length is data, which is exactly what a plan may not assume"
    (is (nil? (t/signature [:ul (list [:li "a"] [:li "b"])])))
    (is (false? (t/templatable? [:ul (for [i (range 3)] [:li i])])))))

(deftest a-conditional-child-refuses-the-shape
  (testing "nil is a stable SLOT under the 1:1 law but not a stable KIND"
    (is (nil? (t/signature [:div nil])))
    (is (nil? (t/signature [:div [:b "x"] false])))))

(deftest a-fragment-child-refuses-the-shape
  (is (nil? (t/signature [:div [:<> [:b "x"]]]))))

(deftest a-boundary-head-refuses-the-shape
  (let [boundary (fn [_props] [:i "x"])]
    (is (nil? (t/signature [:div [boundary {}]]))
        "a boundary owns its own subtree and its own commit")
    (is (nil? (t/signature [boundary {}])))))

(deftest refusal-is-per-shape-not-per-tree
  (testing "a list refuses; its rows do not"
    (is (nil? (t/signature [:ul (list [:li.row {:data-i 0} "a"])])))
    (is (some? (t/signature [:li.row {:data-i 0} "a"])))))

;; ---------------------------------------------------------------------------
;; Shapes that DO get a plan
;; ---------------------------------------------------------------------------

(deftest the-witness-shapes-are-templatable
  (testing "the P0 list row"
    (is (t/templatable? [:li.row [:span.lbl "cell "] [:span.cell {:data-i 3} "7"]])))
  (testing "the controlled grid cell"
    (is (t/templatable? [:div.cell
                         [:label.lbl {:for "f3"} "Field 3"]
                         [:input.inp {:id "f3" :type "text" :value "abc"
                                      :on-input [:grid/edit 3 :re-frame.hicasso/value]}]])))
  (testing "a deeply nested static shape"
    (is (t/templatable? [:div [:div [:div [:span {:class "x"} "leaf"]]]]))))

(deftest signature-parts-are-legible
  (let [sig (t/signature [:li.row {:data-i 1} [:span "a"]])]
    (is (string? sig))
    (is (= 2 (count (t/sig-parts sig))) "one part per element")))
