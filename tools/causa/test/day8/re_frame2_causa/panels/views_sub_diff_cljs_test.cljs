(ns day8.re-frame2-causa.panels.views-sub-diff-cljs-test
  "Smoke test for the sub-output structural-diff drilldown view
  (rf2-xjhhp Phase 2 of rf2-abts7).

  Hiccup-level walk over `views-sub-diff/drilldown` — asserts the
  drilldown's data-testid hooks ship + the empty-state chip renders
  when records is empty."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.diff.annotated-tree :as at]
            [day8.re-frame2-causa.diff.section-grouping :as sg]
            [day8.re-frame2-causa.panels.views-sub-diff :as view]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- expand-fn [node]
  (if (and (vector? node) (fn? (first node)))
    (try (apply (first node) (rest node))
         (catch :default _ node))
    node))

(defn- has-testid? [tree testid]
  (some (fn [node]
          (and (vector? node)
               (map? (second node))
               (= testid (:data-testid (second node)))))
        (->> (tree-seq (some-fn vector? seq?) seq (expand-fn tree))
             (map expand-fn))))

(deftest drilldown-empty-records-shows-empty-chip
  (testing "no records → the drilldown renders the empty-state chip
            so the caller can tell 'no subs recomputed' from a
            mounting bug"
    (let [tree (view/drilldown [])]
      (is (has-testid? tree "rf-causa-views-sub-diff-drilldown")
          "drilldown container renders")
      (is (has-testid? tree "rf-causa-views-sub-diff-empty")
          "empty-state chip renders"))))

(deftest drilldown-with-records-renders-blocks
  (testing "records → one block per record with the sub-id testid hook"
    (let [records [{:sub-id        :test/counter
                    :query-v       [:test/counter]
                    :before-value  0
                    :after-value   1
                    :diff-sections []
                    :unchanged?    false}
                   {:sub-id        :test/items
                    :query-v       [:test/items]
                    :before-value  []
                    :after-value   [{:x 1}]
                    :diff-sections []
                    :unchanged?    false}]
          tree    (view/drilldown records)]
      (is (has-testid? tree "rf-causa-views-sub-diff-drilldown"))
      (is (has-testid? tree "rf-causa-views-sub-diff-block-:test/counter"))
      (is (has-testid? tree "rf-causa-views-sub-diff-block-:test/items")))))

(deftest drilldown-unchanged-record-renders-unchanged-chip
  (testing "an :unchanged? record renders its 'value identical' chip
            instead of the sections renderer"
    (let [records [{:sub-id        :test/counter
                    :query-v       [:test/counter]
                    :before-value  1
                    :after-value   1
                    :diff-sections []
                    :unchanged?    true}]
          tree    (view/drilldown records)]
      (is (has-testid? tree "rf-causa-views-sub-diff-unchanged-:test/counter")
          "the per-sub unchanged chip renders for an unchanged record"))))

(deftest sub-block-renders-with-sections
  (testing "a record with diff-sections falls through to the Phase 1
            renderer; we assert the section block ships its testid
            (the renderer's own hook from `diff/render.cljs`)"
    (let [;; Build a minimal annotated subtree from the Phase 1 engine
          ;; via diff-tree so the renderer's per-section markup is
          ;; well-formed.
          before    {:cart {:total 10}}
          after     {:cart {:total 12}}
          annotated (at/diff-tree before after)
          sections  (sg/group-into-sections annotated)
          record    {:sub-id        :test/cart
                     :query-v       [:test/cart]
                     :before-value  before
                     :after-value   after
                     :diff-sections sections
                     :unchanged?    false}
          tree      (view/sub-block record)]
      (is (has-testid? tree "rf-causa-views-sub-diff-block-:test/cart")
          "the per-sub block renders + the per-sub header testid")
      (is (has-testid? tree "rf-causa-views-sub-diff-header-:test/cart")))))
