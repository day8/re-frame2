(ns day8.re-frame2-causa.panels.subscriptions-badges-cljs-test
  "Per-leaf smoke test for `subscriptions-badges` (rf2-nb8if).

  Renders the four public badge fns and asserts at least one of
  them carries the data-testid hook the parent panel relies on."
  (:require [cljs.test :refer-macros [deftest is]]
            [day8.re-frame2-causa.panels.subscriptions-badges :as badges]))

(defn- has-testid? [tree testid]
  (some (fn [node]
          (and (vector? node)
               (map? (second node))
               (= testid (:data-testid (second node)))))
        (tree-seq (some-fn vector? seq?) seq tree)))

(deftest status-colour-returns-a-non-nil-token
  (is (some? (badges/status-colour :fresh)))
  (is (some? (badges/status-colour :error))))

(deftest status-badge-renders
  (is (vector? (badges/status-badge :fresh))))

(deftest layer-pill-renders-with-the-layer-number
  (is (vector? (badges/layer-pill 1))))

(deftest filter-header-renders-with-testid
  (is (has-testid? (badges/filter-header #{} {:fresh 2} 2)
                   "rf-causa-subscriptions-filters")))
