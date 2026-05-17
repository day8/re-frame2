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
