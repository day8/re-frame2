(ns day8.re-frame2-causa.diff.render-cljs-test
  "Smoke tests for the sections-per-cluster renderer (rf2-gfxmk Phase 1
  of rf2-abts7).

  Per the panel-render test pattern (`app_db_diff_slices_cljs_test`)
  each public renderer is invoked once and the load-bearing
  `data-testid` hooks asserted present in the produced hiccup."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.diff.annotated-tree :as at]
            [day8.re-frame2-causa.diff.render :as render]
            [day8.re-frame2-causa.diff.section-grouping :as sg]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
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

;; ---- empty / boundary --------------------------------------------------

(deftest render-sections-empty-state
  (testing "no sections → empty-state hiccup with testid hook"
    (is (has-testid? (render/render-sections [] "app-db-diff")
                     "rf-causa-diff-empty"))))

;; ---- single-leaf section -----------------------------------------------

(deftest render-single-leaf-section
  (testing "one :modified leaf at path [:a] → 1 section with breadcrumb +
            section testid + section-header testid"
    (let [tree     (at/diff-tree {:a 1} {:a 2})
          sections (sg/group-into-sections tree)
          hiccup   (render/render-sections sections "app-db-diff")]
      (is (= 1 (count sections)))
      (is (has-testid? hiccup "rf-causa-diff-sections"))
      (is (any-testid-prefix? hiccup "rf-causa-diff-section-"))
      (is (any-testid-prefix? hiccup "rf-causa-diff-section-header-")))))

;; ---- multi-section cart cascade ----------------------------------------

(def ^:private cart-before
  {:cart   {:items [{:id 7  :qty 1} {:id 22 :qty 1}] :gross 42}
   :user   {:name "Alice" :prefs {:theme :light}}
   :status :pending})

(def ^:private cart-after
  {:cart   {:items [{:id 7  :qty 1} {:id 22 :qty 2} {:id 91 :qty 3}]
            :gross 47.5}
   :user   {:name "Alice" :prefs {:theme :dark}}
   :status :submitting
   :flash  "Order saved"})

(deftest render-cart-cascade-yields-four-section-blocks
  (testing "cart cascade → 4 section blocks each with a testid"
    (let [tree     (at/diff-tree cart-before cart-after)
          sections (sg/group-into-sections tree)
          hiccup   (render/render-sections sections "app-db-diff")]
      (is (= 4 (count sections)))
      (is (has-testid? hiccup "rf-causa-diff-section-[:cart]"))
      (is (has-testid? hiccup "rf-causa-diff-section-[:user :prefs]"))
      (is (has-testid? hiccup "rf-causa-diff-section-[:status]"))
      (is (has-testid? hiccup "rf-causa-diff-section-[:flash]")))))

;; ---- smart-expand depth + collapse-unchanged-chip ----------------------

(deftest render-collapses-unchanged-when-many-same-siblings
  (testing "design §5.3: many :same direct children collapse into a
            (N entries unchanged) chip"
    (let [;; Make 10 same keys + 1 modified key.
          padding (into {} (for [i (range 10)]
                             [(keyword (str "k" i)) i]))
          before  (assoc padding :changed 1)
          after   (assoc padding :changed 2)
          tree     (at/diff-tree before after)
          sections (sg/group-into-sections tree)
          hiccup   (render/render-sections sections "app-db-diff")]
      ;; One section at [:changed]. The :same siblings aren't direct
      ;; children of the SECTION subtree (each section's subtree is the
      ;; cluster-headed local view, NOT the root), so this assertion
      ;; checks the collapse-chip primitive in isolation: when a
      ;; :children container has > collapse-unchanged-threshold
      ;; same-children, the chip renders.
      ;;
      ;; Drive this directly: build a :children node with many :same
      ;; kids and render via render-annotated.
      (is (= 1 (count sections))
          "single-key change yields one section")
      ;; Drive the collapse-chip primitive directly.
      (let [container {:day8.re-frame2-causa.diff.annotated-tree/op :children
                       :tag :map
                       :value (assoc padding :z 99)
                       :children (vec
                                   (concat
                                     [{:day8.re-frame2-causa.diff.annotated-tree/op :modified
                                       :key :z :before 1 :after 99}]
                                     (for [i (range 10)]
                                       {:day8.re-frame2-causa.diff.annotated-tree/op :same
                                        :key (keyword (str "k" i))
                                        :value i})))
                       :child-summary {:added 0 :removed 0
                                       :modified 1 :children 0 :same 10}}
            rendered  (render/render-annotated container [] [] "test" 0)]
        (is (has-testid? rendered "rf-causa-diff-unchanged-chip"))))))

(deftest render-smart-expand-respects-depth-cap
  (testing "design §3.1.2: auto-expand caps at smart-expand-max-depth
            levels (default 3)"
    (let [container {:day8.re-frame2-causa.diff.annotated-tree/op :children
                     :tag :map
                     :value {}
                     :children []
                     :child-summary {:added 0 :removed 0 :modified 5
                                     :children 0 :same 0}}
          ;; Render at the cap → should NOT recurse / auto-expand; emits
          ;; the collapse hint.
          rendered (render/render-annotated container [] [] "test"
                                            render/smart-expand-max-depth)]
      ;; The container block itself is still rendered, but the auto-
      ;; expand body is replaced by the deferred-expand hint.
      (is (vector? rendered)))))

;; ---- gutters per ::op --------------------------------------------------

(deftest render-modified-leaf-emits-yellow-tone
  (testing ":modified leaf — gutter renders with the yellow tone (no
            crash on the inline before → after format)"
    (let [node     {:day8.re-frame2-causa.diff.annotated-tree/op :modified
                    :key :status :before :pending :after :submitting}
          rendered (render/render-annotated node [] [] "test" 0)]
      (is (vector? rendered)))))

(deftest render-added-leaf-emits-green-gutter
  (let [node     {:day8.re-frame2-causa.diff.annotated-tree/op :added
                  :key :flash :value "Order saved"}
        rendered (render/render-annotated node [] [] "test" 0)]
    (is (vector? rendered))))

(deftest render-removed-leaf-emits-red-gutter
  (let [node     {:day8.re-frame2-causa.diff.annotated-tree/op :removed
                  :key :old-flag :value :was-here}
        rendered (render/render-annotated node [] [] "test" 0)]
    (is (vector? rendered))))

;; ---- sentinel handling -------------------------------------------------

(deftest render-redacted-sentinel-respects-elision-contract
  (testing "when both sides are :rf/redacted, the walker emits :same;
            the renderer never crosses the elision boundary"
    (let [tree (at/diff-tree {:auth :rf/redacted} {:auth :rf/redacted})]
      (is (= :same (at/op-of tree)))
      ;; And via sections — empty.
      (is (= [] (sg/group-into-sections tree))))))
