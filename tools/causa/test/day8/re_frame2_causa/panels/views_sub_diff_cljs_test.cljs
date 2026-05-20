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
  (test-support/reset-runtime-fixture-factory {:adapter plain-atom/adapter}))

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

;; ---------------------------------------------------------------------------
;; rf2-87lkf — Delta 3: recomputed-but-equal sub drilldown UX
;;
;; Pre-bead the `:unchanged?` chip read "Value identical between
;; db-before and db-after." (substrate-framed). The polish replaces it
;; with a developer-framed two-beat treatment:
;;
;;   ✓ No change. Sub recomputed; value = previous. (React skipped re-render.)
;;   [Why?] → reaction-lifecycle expander
;;
;; The drilldown ships a `·` hoverable marker in the header so the
;; block-level treatment mirrors the Re-rendered row's marker column.
;; ---------------------------------------------------------------------------

(defn- collect-strings [tree]
  (->> (tree-seq (some-fn vector? seq?) seq (expand-fn tree))
       (filter string?)))

(deftest unchanged-drilldown-developer-framed-text
  (testing "Delta 3 — the `:unchanged?` chip leads with `✓ No change.`
            and ships the developer-framed reason. The substrate
            phrasing 'identical between db-before and db-after' is
            replaced."
    (let [record {:sub-id        :user/profile
                  :query-v       [:user/profile]
                  :before-value  {:a 1}
                  :after-value   {:a 1}
                  :diff-sections []
                  :unchanged?    true}
          tree   (view/sub-block record)
          texts  (collect-strings tree)]
      (is (some #(= "✓" %) texts)
          "the ✓ glyph renders inside the unchanged drilldown")
      (is (some #(re-find #"No change" %) texts)
          "the chip leads with 'No change'")
      (is (some #(re-find #"value = previous" %) texts)
          "developer-framed sub-line explains the value relation")
      (is (some #(re-find #"React skipped re-render" %) texts)
          "the React-skipped-re-render note ships")
      (is (not-any? #(re-find #"db-before and db-after" %) texts)
          (str "the old substrate phrasing is replaced. offending: "
               (pr-str (filter #(re-find #"db-" %) texts)))))))

(deftest unchanged-drilldown-has-why-expander
  (testing "Delta 3 — the chip exposes a `[Why?]` <details><summary>
            expander wired to a reaction-lifecycle explainer paragraph.
            The expander is inline (no event dispatch) so the drilldown
            stays self-contained."
    (let [record {:sub-id        :user/profile
                  :query-v       [:user/profile]
                  :before-value  1
                  :after-value   1
                  :diff-sections []
                  :unchanged?    true}
          tree   (view/sub-block record)
          texts  (collect-strings tree)]
      (is (has-testid? tree
                       "rf-causa-views-sub-diff-unchanged-why-:user/profile")
          "Why? expander has a stable testid hook")
      (is (some #(re-find #"(?i)reaction lifecycle" %) texts)
          "the explainer paragraph references reaction-lifecycle")
      (is (some #(re-find #"input ratoms" %) texts)
          "the explainer mentions input ratoms (substrate term, in
           expander context only — the chip itself stays developer-
           framed)"))))

(deftest unchanged-drilldown-header-carries-non-trigger-marker
  (testing "Delta 3 — the sub-header on an unchanged record leads with
            a hoverable `·` marker so the drilldown block mirrors the
            row-column marker chrome. Marker has :title + aria-label."
    (let [record {:sub-id        :user/profile
                  :query-v       [:user/profile]
                  :before-value  1
                  :after-value   1
                  :diff-sections []
                  :unchanged?    true}
          tree   (view/sub-block record)]
      (is (has-testid? tree
                       "rf-causa-views-sub-diff-marker-:user/profile")
          "the per-sub `·` marker has a stable testid hook")
      ;; Walk the tree and confirm the marker carries both attrs.
      (let [marker-node
            (some (fn [node]
                    (when (and (vector? node)
                               (map? (second node))
                               (= (str "rf-causa-views-sub-diff-marker-"
                                       (pr-str :user/profile))
                                  (:data-testid (second node))))
                      node))
                  (->> (tree-seq (some-fn vector? seq?) seq (expand-fn tree))
                       (map expand-fn)))]
        (is (some? marker-node) "marker node located")
        (is (string? (:title (second marker-node)))
            "marker has a :title hover tooltip")
        (is (= "value unchanged" (:aria-label (second marker-node)))
            "marker has an :aria-label for screen readers")))))

(deftest changed-record-does-not-render-no-change-chip
  (testing "Delta 3 — only `:unchanged?` records render the No change
            chip. A changed record falls through to the section
            renderer; the No change chip and Why? expander are absent."
    (let [record {:sub-id        :user/profile
                  :query-v       [:user/profile]
                  :before-value  {:a 1}
                  :after-value   {:a 2}
                  :diff-sections []   ;; empty falls through to empty chip
                  :unchanged?    false}
          tree   (view/sub-block record)
          texts  (collect-strings tree)]
      (is (not-any? #(re-find #"No change" %) texts)
          "no 'No change' phrasing on a changed record")
      (is (nil? (some (fn [node]
                        (and (vector? node)
                             (map? (second node))
                             (= (str "rf-causa-views-sub-diff-unchanged-"
                                     (pr-str :user/profile))
                                (:data-testid (second node)))))
                      (->> (tree-seq (some-fn vector? seq?) seq
                                     (expand-fn tree))
                           (map expand-fn))))
          "the unchanged chip is absent on a changed record"))))

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
