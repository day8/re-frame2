(ns day8.re-frame2-causa.diff.hiccup-render-cljs-test
  "Smoke tests for the hiccup-diff renderer (rf2-i39w2 Phase 3 of
  rf2-abts7).

  Each public render path is invoked once and the load-bearing
  `data-testid` hooks asserted present in the produced hiccup."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.diff.hiccup :as hd]
            [day8.re-frame2-causa.diff.hiccup-render :as hd-render]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

;; ---- helpers -----------------------------------------------------------

(defn- has-testid? [tree testid]
  (some (fn [node]
          (and (vector? node)
               (map? (second node))
               (= testid (:data-testid (second node)))))
        (tree-seq (some-fn vector? seq?) seq tree)))

(defn- any-testid-prefix? [tree prefix]
  (some (fn [node]
          (and (vector? node)
               (map? (second node))
               (let [tid (:data-testid (second node))]
                 (and tid (.startsWith tid prefix)))))
        (tree-seq (some-fn vector? seq?) seq tree)))

;; ---- element-changed root ----------------------------------------------

(deftest renders-element-changed-root
  (testing "an :element-changed annotated node renders with the hiccup
            root testid + an element testid"
    (let [n      (hd/diff-hiccup-node [:div {:class "a"} "k"]
                                      [:div {:class "b"} "k"])
          hiccup (hd-render/render-root n "view-hiccup")]
      (is (has-testid? hiccup "rf-causa-diff-hiccup-root"))
      (is (any-testid-prefix? hiccup "rf-causa-diff-hiccup-element-"))
      (is (has-testid? hiccup "rf-causa-diff-hiccup-attrs")))))

;; ---- element-moved ----------------------------------------------------

(deftest renders-element-moved-chip
  (testing "keyed reorder produces an :element-moved subtree with the
            rf-causa-diff-hiccup-moved testid"
    (let [before [:ul
                  [:li {:key 1} "one"]
                  [:li {:key 2} "two"]]
          after  [:ul
                  [:li {:key 2} "two"]
                  [:li {:key 1} "one"]]
          n      (hd/diff-hiccup-node before after)
          hiccup (hd-render/render-root n "view-hiccup")]
      (is (has-testid? hiccup "rf-causa-diff-hiccup-moved")))))

;; ---- fn-ref-changed (toggle on) ---------------------------------------

(deftest renders-fn-ref-changed-chip-when-toggle-on
  (testing "with :highlight-fn-ref-changes? on, an opaque fn whose
            reference changed renders with the fn-ref-changed chip
            testid"
    (let [f1 (fn [_e] :one)
          f2 (fn [_e] :two)
          before [:button {:on-click f1 :class "a"} "Click"]
          after  [:button {:on-click f2 :class "b"} "Click"]
          n      (hd/diff-hiccup-node before after
                                      {:highlight-fn-ref-changes? true})
          hiccup (hd-render/render-root n "view-hiccup")]
      (is (has-testid? hiccup "rf-causa-diff-fn-ref-changed-chip")))))

(deftest no-fn-ref-chip-when-toggle-off
  (testing "with default opts, an opaque fn whose reference changed does
            NOT render the fn-ref-changed chip"
    (let [f1 (fn [_e] :one)
          f2 (fn [_e] :two)
          before [:button {:on-click f1 :class "a"} "Click"]
          after  [:button {:on-click f2 :class "b"} "Click"]
          n      (hd/diff-hiccup-node before after)  ; no toggle
          hiccup (hd-render/render-root n "view-hiccup")]
      (is (not (has-testid? hiccup "rf-causa-diff-fn-ref-changed-chip"))))))

;; ---- scalar leaves ----------------------------------------------------

(deftest renders-modified-leaf-fallback
  (testing ":modified scalar (e.g. text child) → renders inside the
            root container; the gutter+inspector call returns hiccup"
    (let [n      (hd/diff-hiccup-node "hi" "ho")
          hiccup (hd-render/render-root n "view-hiccup")]
      (is (has-testid? hiccup "rf-causa-diff-hiccup-root")))))

;; ---- deep tree ---------------------------------------------------------

(deftest renders-deep-element-changed-tree
  (testing "nested change: row [span 'qty 1'] → [span 'qty 2']"
    (let [before [:div.row
                  [:span.id "22"]
                  [:span.qty "1"]
                  [:span.added-at "..."]]
          after  [:div.row
                  [:span.id "22"]
                  [:span.qty "2"]
                  [:span.added-at "..."]]
          n      (hd/diff-hiccup-node before after)
          hiccup (hd-render/render-root n "view-hiccup")]
      (is (has-testid? hiccup "rf-causa-diff-hiccup-root"))
      (is (any-testid-prefix? hiccup "rf-causa-diff-hiccup-element-"))
      (is (has-testid? hiccup "rf-causa-diff-hiccup-children")))))
