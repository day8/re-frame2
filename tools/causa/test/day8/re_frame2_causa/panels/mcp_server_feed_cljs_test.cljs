(ns day8.re-frame2-causa.panels.mcp-server-feed-cljs-test
  "Per-leaf smoke test for `mcp-server-feed` (rf2-nb8if).

  Renders the public feed fns and asserts data-testid hooks."
  (:require [cljs.test :refer-macros [deftest is]]
            [day8.re-frame2-causa.panels.mcp-server-feed :as feed]))

(defn- has-testid? [tree testid]
  (some (fn [node]
          (and (vector? node)
               (map? (second node))
               (= testid (:data-testid (second node)))))
        (tree-seq (some-fn vector? seq?) seq tree)))

(deftest mcp-row-renders-with-row-testid
  (let [tree (feed/mcp-row {:id          1
                            :time        1700000000000
                            :op-type     :tool
                            :operation   :tool/call
                            :tool        :get-app-db
                            :description "tool — get-app-db"
                            :source-coord "foo.cljs:42"
                            :dispatch-id nil})]
    (is (has-testid? tree "rf-causa-mcp-row-1"))))

(deftest empty-state-no-activity-renders
  (is (vector? (feed/empty-state-no-activity))))

(deftest empty-state-no-matches-renders
  (is (vector? (feed/empty-state-no-matches))))

(deftest activity-feed-renders-no-activity-when-empty-kind
  (is (vector?
        (feed/activity-feed {:rows [] :empty-kind :no-activity}))))
