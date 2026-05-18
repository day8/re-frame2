(ns day8.re-frame2-causa.diff.hiccup-test
  "Tests for the hiccup-tree-diff micro-engine (rf2-i39w2 Phase 3 of
  rf2-abts7). Pure data → data so the JVM target picks them up via
  `.cljc`. Per design `ai/findings/2026-05-18-difftastic-in-causa.md`
  §3.3 + §4."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-causa.diff.hiccup :as h]))

;; ---- shape-level scalar cases ------------------------------------------

(deftest identical-pointers-short-circuit
  (testing "pointer-equal short-circuit → :same with the same value"
    (let [v [:div {:class "x"} "hello"]
          n (h/diff-hiccup-node v v)]
      (is (= :same   (h/op-of n)))
      (is (= v       (:value n))))))

(deftest equal-values-collapse-to-same
  (testing "structurally equal but pointer-different → :same"
    (let [a [:div {:class "x"} "hello"]
          b [:div {:class "x"} "hello"]
          n (h/diff-hiccup-node a b)]
      (is (= :same (h/op-of n))))))

(deftest both-scalars-not-equal-modified
  (testing "both sides scalars, different → :modified"
    (let [n (h/diff-hiccup-node "hi" "ho")]
      (is (= :modified (h/op-of n)))
      (is (= "hi" (:before n)))
      (is (= "ho" (:after n))))))

(deftest scalar-vs-hiccup-modified
  (testing "shape mismatch → :modified (no recursion)"
    (let [n (h/diff-hiccup-node "hi" [:span "hi"])]
      (is (= :modified (h/op-of n))))))

(deftest different-tags-modified
  (testing "different tags → whole element :modified"
    (let [n (h/diff-hiccup-node [:div "x"] [:span "x"])]
      (is (= :modified (h/op-of n))))))

;; ---- attrs diff --------------------------------------------------------

(deftest attrs-add-remove-change
  (testing "attrs map diffs key-by-key: added / removed / modified"
    (let [n (h/diff-hiccup-node [:div {:class "a" :id "x"} "k"]
                                [:div {:class "b" :data-test "y"} "k"])]
      (is (= :element-changed (h/op-of n)))
      (is (= :div (:tag n)))
      (let [attrs (:attrs-diff n)
            kids  (:children attrs)
            by-key (into {} (map (juxt :key identity) kids))]
        (is (= :modified (h/op-of (by-key :class))))
        (is (= "a" (:before (by-key :class))))
        (is (= "b" (:after  (by-key :class))))
        (is (= :removed (h/op-of (by-key :id))))
        (is (= :added   (h/op-of (by-key :data-test))))))))

(deftest attrs-unchanged-children-changed
  (testing "attrs unchanged, children change → only :children-diff
            carries non-:same entries"
    (let [n (h/diff-hiccup-node [:div {:class "row"} [:span "1"]]
                                [:div {:class "row"} [:span "2"]])]
      (is (= :element-changed (h/op-of n)))
      ;; attrs all :same
      (let [attrs (:attrs-diff n)
            kids  (:children attrs)]
        (is (every? (fn [c] (= :same (h/op-of c))) kids))))))

;; ---- children diff: positional ----------------------------------------

(deftest positional-add-at-end
  (testing "positional child added at end → :added with :index"
    (let [n (h/diff-hiccup-node [:ul [:li "a"]]
                                [:ul [:li "a"] [:li "b"]])
          cdiff (:children-diff n)]
      (is (= 2 (count cdiff)))
      (is (= :same  (h/op-of (nth cdiff 0))))
      (is (= :added (h/op-of (nth cdiff 1))))
      (is (= 1      (:index (nth cdiff 1)))))))

(deftest positional-remove-at-end
  (testing "positional child removed at end → :removed with :index"
    (let [n (h/diff-hiccup-node [:ul [:li "a"] [:li "b"]]
                                [:ul [:li "a"]])
          cdiff (:children-diff n)]
      (is (= 2 (count cdiff)))
      (is (= :same    (h/op-of (nth cdiff 0))))
      (is (= :removed (h/op-of (nth cdiff 1)))))))

(deftest positional-text-child-modified
  (testing "text child differs → :modified leaf at :index"
    (let [n (h/diff-hiccup-node [:span "1"] [:span "2"])
          cdiff (:children-diff n)]
      (is (= 1 (count cdiff)))
      (is (= :modified (h/op-of (nth cdiff 0))))
      (is (= "1" (:before (nth cdiff 0))))
      (is (= "2" (:after  (nth cdiff 0)))))))

;; ---- children diff: keyed identity-tracked reorder ---------------------

(deftest keyed-reorder-no-false-positives
  (testing "keyed children reordered → :element-moved per item; no
            false-positive :modified on identity-preserved content"
    (let [before [:ul
                  [:li {:key 7}  "seven"]
                  [:li {:key 22} "twenty-two"]
                  [:li {:key 91} "ninety-one"]]
          after  [:ul
                  [:li {:key 22} "twenty-two"]
                  [:li {:key 7}  "seven"]
                  [:li {:key 91} "ninety-one"]]
          n     (h/diff-hiccup-node before after)
          cdiff (:children-diff n)
          by-key (into {} (map (juxt :key identity) cdiff))]
      (is (= :element-changed (h/op-of n)))
      (is (= :element-moved (h/op-of (by-key 22))))
      (is (= 1 (:from-index (by-key 22))))
      (is (= 0 (:to-index   (by-key 22))))
      (is (= :element-moved (h/op-of (by-key 7))))
      (is (= 0 (:from-index (by-key 7))))
      (is (= 1 (:to-index   (by-key 7))))
      ;; 91 stayed at index 2 → :same
      (is (= :same (h/op-of (by-key 91)))))))

(deftest keyed-insert-at-front
  (testing "keyed insert at index 0 → :added for new key, others stay
            :same (their key→content didn't change even though their
            positional index shifted; this is the React reconciliation
            contract)"
    (let [before [:ul
                  [:li {:key 7} "seven"]
                  [:li {:key 9} "nine"]]
          after  [:ul
                  [:li {:key 1} "one"]
                  [:li {:key 7} "seven"]
                  [:li {:key 9} "nine"]]
          n     (h/diff-hiccup-node before after)
          cdiff (:children-diff n)
          by-key (into {} (map (juxt :key identity) cdiff))]
      (is (= :added (h/op-of (by-key 1))))
      ;; 7 moved from index 0 to 1, 9 moved from index 1 to 2.
      (is (= :element-moved (h/op-of (by-key 7))))
      (is (= :element-moved (h/op-of (by-key 9)))))))

(deftest keyed-remove-from-middle
  (testing "keyed remove from middle → :removed for missing key,
            others detect their new position"
    (let [before [:ul
                  [:li {:key 1} "one"]
                  [:li {:key 2} "two"]
                  [:li {:key 3} "three"]]
          after  [:ul
                  [:li {:key 1} "one"]
                  [:li {:key 3} "three"]]
          n     (h/diff-hiccup-node before after)
          cdiff (:children-diff n)
          by-key (into {} (map (juxt :key identity) cdiff))]
      (is (= :removed       (h/op-of (by-key 2))))
      (is (= :same          (h/op-of (by-key 1))))
      ;; 3 moved from index 2 to 1.
      (is (= :element-moved (h/op-of (by-key 3)))))))

(deftest keyed-content-changes-without-move
  (testing "keyed child stays at same index but content changes →
            :element-changed (or :modified) at that key"
    (let [before [:ul [:li {:key 7} "before"]]
          after  [:ul [:li {:key 7} "after"]]
          n     (h/diff-hiccup-node before after)
          cdiff (:children-diff n)
          c     (first cdiff)]
      ;; The child is at the same index, content differs → element-changed
      ;; for the inner :li (the :key stays as the slot).
      (is (= :element-changed (h/op-of c)))
      (is (= 7 (:key c))))))

;; ---- fragment flattening ----------------------------------------------

(deftest fragments-flatten-into-parent
  (testing "[:<> ...] fragment children flatten so the diff sees the
            inner children as direct children of the parent"
    (let [before [:ul
                  [:<>
                   [:li "a"]
                   [:li "b"]]]
          after  [:ul
                  [:li "a"]
                  [:li "b"]]
          n (h/diff-hiccup-node before after)]
      ;; After flattening, both sides have the same two children → :same.
      (is (= :element-changed (h/op-of n)))
      (let [cdiff (:children-diff n)]
        (is (= 2 (count cdiff)))
        (is (every? (fn [c] (= :same (h/op-of c))) cdiff))))))

;; ---- opaque fn props: same-by-default (§4.5) ---------------------------

(deftest opaque-fn-prop-same-by-default
  (testing "function-valued prop both sides → :same (not :modified)
            even when the fn IDENTITY changed (fresh per-render closure)"
    (let [f1     (fn [_e] :one)
          f2     (fn [_e] :two)  ; different identity, same purpose
          before [:button {:on-click f1} "Click"]
          after  [:button {:on-click f2} "Click"]
          n      (h/diff-hiccup-node before after)
          attrs  (:attrs-diff n)
          per-key (into {} (map (juxt :key identity) (:children attrs)))]
      (is (= :element-changed (h/op-of n)))
      (is (= :same (h/op-of (per-key :on-click))))
      ;; And children/attrs together carry NO non-:same entries.
      (is (every? (fn [c] (= :same (h/op-of c))) (:children attrs))))))

(deftest opaque-fn-prop-with-toggle-on-surfaces-fn-ref-changed
  (testing "with :highlight-fn-ref-changes? true, identity-different
            opaque fns surface as :fn-ref-changed"
    (let [f1 (fn [_e] :one)
          f2 (fn [_e] :two)
          before [:button {:on-click f1} "Click"]
          after  [:button {:on-click f2} "Click"]
          n      (h/diff-hiccup-node before after
                                     {:highlight-fn-ref-changes? true})
          attrs  (:attrs-diff n)
          per-key (into {} (map (juxt :key identity) (:children attrs)))]
      (is (= :fn-ref-changed (h/op-of (per-key :on-click)))))))

(deftest opaque-fn-prop-identical-stays-same-under-toggle
  (testing "with toggle on, identical fn references still surface :same
            (force a real attrs diff via :class so the element-changed
            path runs and we can inspect the :on-click per-attr classify)"
    (let [f1     (fn [_e] :one)
          before [:button {:on-click f1 :class "a"} "Click"]
          after  [:button {:on-click f1 :class "b"} "Click"]
          n      (h/diff-hiccup-node before after
                                     {:highlight-fn-ref-changes? true})
          attrs  (:attrs-diff n)
          per-key (into {} (map (juxt :key identity) (:children attrs)))]
      (is (= :element-changed (h/op-of n)))
      (is (= :same (h/op-of (per-key :on-click))))
      (is (= :modified (h/op-of (per-key :class)))))))

(deftest opaque-fn-prop-added-removed
  (testing "fn prop added / removed surface correctly"
    (let [f1     (fn [_e] :one)
          ;; Removed: was a fn, now nil/absent.
          n-rm   (h/diff-hiccup-node [:button {:on-click f1} "x"]
                                     [:button {} "x"])
          ;; Added: was absent, now a fn.
          n-add  (h/diff-hiccup-node [:button {} "x"]
                                     [:button {:on-click f1} "x"])
          by-key (fn [n]
                   (into {} (map (juxt :key identity)
                                 (:children (:attrs-diff n)))))]
      (is (= :removed (h/op-of (get (by-key n-rm) :on-click))))
      (is (= :added   (h/op-of (get (by-key n-add) :on-click)))))))

(deftest opaque-prop-type-mismatch-modified
  (testing "opaque → non-opaque (fn replaced by a string handler URL)
            surfaces :modified (real type change)"
    (let [f1     (fn [_e] :one)
          before [:a {:on-click f1} "x"]
          after  [:a {:on-click "javascript:void(0)"} "x"]
          n      (h/diff-hiccup-node before after)
          attrs  (:attrs-diff n)
          per-key (into {} (map (juxt :key identity) (:children attrs)))]
      (is (= :modified (h/op-of (per-key :on-click)))))))

;; ---- mixed opaque shapes (§4.5 other opaque values) -------------------

(deftest opaque-other-shapes-are-same-by-default
  (testing "non-fn opaque shapes (atom, IDeref) also default to :same"
    (let [a1 (atom :one)
          a2 (atom :two)  ; different identity, both atoms
          before [:input {:ref a1}]
          after  [:input {:ref a2}]
          n      (h/diff-hiccup-node before after)
          attrs  (:attrs-diff n)
          per-key (into {} (map (juxt :key identity) (:children attrs)))]
      (is (= :same (h/op-of (per-key :ref)))))))

(deftest opaque-classifier-handles-atoms-and-fns-uniformly
  (testing "classify-prop direct: (atom, atom) → :same; (fn, fn) →
            :same; (fn, fn) with toggle → :fn-ref-changed"
    (let [a1 (atom :one) a2 (atom :two)
          f1 (fn [] 1)   f2 (fn [] 2)]
      (is (= :same (h/op-of (h/classify-prop a1 a2 {}))))
      (is (= :same (h/op-of (h/classify-prop f1 f2 {}))))
      (is (= :fn-ref-changed
             (h/op-of (h/classify-prop f1 f2 {:highlight-fn-ref-changes? true})))))))

;; ---- nil children / scalar shapes -------------------------------------

(deftest nil-children-no-recursion-bug
  (testing "nil children handled as scalar leaves (not as a seqable)"
    (let [n (h/diff-hiccup-node [:div nil] [:div "x"])
          c (first (:children-diff n))]
      (is (= :modified (h/op-of c))))))

;; ---- changed? helper ---------------------------------------------------

(deftest changed-detects-non-same
  (testing "changed? returns true for :modified / :added / :removed /
            :element-changed (with at least one non-same child)"
    (is (false? (h/changed? {::h/op :same :value 1})))
    (is (true?  (h/changed? {::h/op :modified :before 1 :after 2})))
    (is (true?  (h/changed? {::h/op :added :value 1})))
    (is (true?  (h/changed? (h/diff-hiccup-node [:div {:class "a"}]
                                                [:div {:class "b"}])))))
  (testing "changed? returns false for an element-changed with all-same
            attrs + all-same children (degenerate; shouldn't normally
            arise because identical/equal short-circuits earlier)"
    (let [synthetic {::h/op :element-changed
                     :tag :div
                     :attrs-diff {::h/op :attrs :children []
                                  :child-summary {}}
                     :children-diff []}]
      (is (false? (h/changed? synthetic))))))
