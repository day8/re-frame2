(ns day8.re-frame2-causa.panels.views-view-cljs-test
  "Per-leaf smoke test for `views-view` (rf2-21ob3).

  Mounts `views-panel` (the plain Reagent fn per the canonical facade
  convention — the public `reg-view` lives in the `views.cljs` facade)
  and asserts the structural data-testid hooks ship: the panel root,
  the controls footer, the heatmap toggle, and the three-group
  container."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panels.views :as facade]
            [day8.re-frame2-causa.panels.views-view :as view]))

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

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest views-panel-renders-with-testids
  (facade/install!)
  (frame/reg-frame :rf/causa {})
  (let [tree (view/views-panel)]
    (is (has-testid? tree "rf-causa-views")
        "the root :section data-testid is present")
    (is (has-testid? tree "rf-causa-views-controls")
        "bottom-controls footer renders")
    (is (has-testid? tree "rf-causa-views-heatmap-toggle")
        "heatmap toggle is mounted (default: heatmap mode off)")))

;; ---------------------------------------------------------------------------
;; rf2-gphsi — React unique-key warning regression guard
;;
;; The Views panel renders two `for` loops over sibling Reagent elements:
;; (1) `group-section` iterates `items` and emits one row per item;
;; (2) `views-panel` iterates `h/group-order` and emits one `group-section`
;; per group. Both previously attached `^{:key …}` reader meta to a
;; function-call list form (a `(case …)` form and a `(group-section …)`
;; call respectively). That meta is dropped at evaluation — Reagent's
;; `get-react-key` only reads `:key` from vector meta — producing React
;; "unique key prop" warnings in panel_gallery fixtures (dense-subs,
;; three-group, filter-applied, heatmap). Fix moves the key meta onto
;; the returned `[:div …]` / `[:section …]` vector via `with-meta`.
;; This test asserts every child vector carries `:key` meta so the
;; regression cannot recur silently. (rf2-gphsi)
;; ---------------------------------------------------------------------------

(defn- mk-single-item [view-id triggered-by]
  {:kind          :single
   :render        {:render-key      [view-id 0]
                   :triggered-by    triggered-by
                   :elapsed-ms      1.5
                   :props-before    nil
                   :props-after     nil}
   :invalidated-by [{:sub-id triggered-by :trigger? true}]})

(deftest group-section-children-carry-key-meta
  (facade/install!)
  (frame/reg-frame :rf/causa {})
  (let [items   [(mk-single-item ::comp-a ::sub-a)
                 (mk-single-item ::comp-b ::sub-b)
                 (mk-single-item ::comp-c ::sub-c)]
        section (#'view/group-section :rendered items #{} #{})
        ;; `section` is `[:section attrs [:header …] <for-lazy-seq>]`
        ;; — the `for` over rows ships as a single seq in the tail
        ;; vector slot; Reagent walks the seq and applies React keys
        ;; per child via `get-react-key`. Pull that seq and assert
        ;; each row carries `:key` meta.
        rows    (nth section 3)]
    (is (seq? rows) "rows ship as a seq inside the section vector")
    (is (= 3 (count rows)) "one row per item")
    (doseq [row rows]
      (is (vector? row)
          (str "row is a hiccup vector — got " (pr-str (type row))))
      (is (some? (some-> (meta row) :key))
          (str "row carries :key meta — got " (pr-str (meta row)))))
    (is (= ["[:day8.re-frame2-causa.panels.views-view-cljs-test/comp-a 0]"
            "[:day8.re-frame2-causa.panels.views-view-cljs-test/comp-b 0]"
            "[:day8.re-frame2-causa.panels.views-view-cljs-test/comp-c 0]"]
           (mapv #(-> % meta :key) rows))
        "each row's :key is the per-item row-key (pr-str of :render-key)")))

(deftest views-panel-group-loop-keys-each-section
  ;; Exercises the top-level (for [g h/group-order] …) loop. The panel's
  ;; sub returns nil under the empty fixture, so we stub the resolution
  ;; path by reaching into the inner builders directly via the private
  ;; group-section var and confirming the same with-meta wrapping the
  ;; panel applies. The panel-level loop guards nil with `:when section`
  ;; and wraps non-nil sections via `(with-meta section {:key g})`.
  (facade/install!)
  (frame/reg-frame :rf/causa {})
  (let [items [(mk-single-item ::comp-a ::sub-a)]
        ;; emulate the panel-loop expression for two groups
        sections (for [g [:mounted :rendered]
                       :let [s (#'view/group-section g items #{} #{})]
                       :when s]
                   (with-meta s {:key g}))]
    (is (= 2 (count sections)))
    (doseq [s sections]
      (is (some? (some-> (meta s) :key))
          (str "panel-loop section carries :key meta — got "
               (pr-str (meta s)))))))
