(ns day8.re-frame2-causa.panels.mcp-server-chrome-cljs-test
  "Per-leaf smoke test for `mcp-server-chrome` (rf2-nb8if).

  Renders the two public chrome fns over an empty/idle filter state."
  (:require [cljs.test :refer-macros [deftest is]]
            [day8.re-frame2-causa.panels.mcp-server-chrome :as chrome]))

(defn- has-testid? [tree testid]
  (some (fn [node]
          (and (vector? node)
               (map? (second node))
               (= testid (:data-testid (second node)))))
        (tree-seq (some-fn vector? seq?) seq tree)))

(deftest header-renders-with-no-filter
  (is (vector?
        (chrome/header {:agent-attached?   false
                        :total             0
                        :rendered          0
                        :active-op-types   #{}
                        :distinct-op-types []
                        :op-type-counts    {}
                        :since-ms          nil
                        :any-filter?       false}))))

(deftest settings-sub-pane-renders
  (is (vector?
        (chrome/settings-sub-pane {:origin-filter-enabled? false}))))
