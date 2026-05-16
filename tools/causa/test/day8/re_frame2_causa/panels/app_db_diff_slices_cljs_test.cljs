(ns day8.re-frame2-causa.panels.app-db-diff-slices-cljs-test
  "Per-leaf smoke test for `app-db-diff-slices` (rf2-nb8if).

  Renders each public slice fn (`empty-state`, `slice-row`,
  `changed-slices-stack`) once and asserts the load-bearing
  `data-testid` hook is present in the produced hiccup."
  (:require [cljs.test :refer-macros [deftest is]]
            [day8.re-frame2-causa.panels.app-db-diff-slices :as slices]))

(defn- has-testid? [tree testid]
  (some (fn [node]
          (and (vector? node)
               (map? (second node))
               (= testid (:data-testid (second node)))))
        (tree-seq (some-fn vector? seq?) seq tree)))

(deftest empty-state-renders
  (let [tree (slices/empty-state :rf/default)]
    (is (vector? tree))
    (is (has-testid? tree "rf-causa-app-db-diff-empty"))))

(deftest slice-row-renders-with-action-buttons
  (let [tree (slices/slice-row {:op :modified :path [:cart :items]
                                :before [] :after [{:id 7}]})]
    (is (has-testid? tree "rf-causa-app-db-diff-slice-[:cart :items]"))
    (is (has-testid? tree "rf-causa-app-db-diff-pin-[:cart :items]"))
    (is (has-testid? tree "rf-causa-app-db-diff-show-when-[:cart :items]"))))

(deftest changed-slices-stack-renders-no-changes-state-on-empty
  (is (has-testid? (slices/changed-slices-stack [])
                   "rf-causa-app-db-diff-no-changes")))

(deftest changed-slices-stack-renders-slice-container-when-non-empty
  (let [tree (slices/changed-slices-stack
               [{:op :added :path [:user] :before nil :after "ada"}])]
    (is (has-testid? tree "rf-causa-app-db-diff-slices"))))
