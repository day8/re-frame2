(ns day8.re-frame2-causa.panels.subscriptions-rows-cljs-test
  "Per-leaf smoke test for `subscriptions-rows` (rf2-nb8if).

  Renders `sub-list` for both the empty and populated cases and
  asserts the data-testid hooks the parent panel relies on."
  (:require [cljs.test :refer-macros [deftest is]]
            [day8.re-frame2-causa.panels.subscriptions-rows :as rows]))

(defn- has-testid? [tree testid]
  (some (fn [node]
          (and (vector? node)
               (map? (second node))
               (= testid (:data-testid (second node)))))
        (tree-seq (some-fn vector? seq?) seq tree)))

(deftest sub-list-empty-state-renders
  (is (has-testid? (rows/sub-list [] nil)
                   "rf-causa-subscriptions-empty-rows")))

(deftest sub-list-renders-rows
  (let [r [{:query-v [:cart/total] :sub-id :cart/total
            :status :fresh :layer 1 :ref-count 1 :input-subs []
            :recomputed? false :error nil}]]
    (is (has-testid? (rows/sub-list r nil)
                     "rf-causa-subscriptions-list"))))
