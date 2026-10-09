(ns day8.re-frame2-machines-viz.chart.overlays.overlay-anchor-cljs-test
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-machines-viz.chart.overlays.overlay-anchor
             :as anchor]))

(deftest node-testid-matches-state-node-contract
  (is (= "rf-mv-chart-node-idle" (anchor/node->testid "idle"))
      "mirrors chart.nodes/state-node's data-testid"))

(deftest anchors-place-the-card-beside-or-below-the-node
  (testing "overlay-local coordinates (node minus container origin), the card
            clear of the node by the 12px gap, plus the node centre for the
            connector; a node not laid out yet gets no anchor"
    (let [node      {:left 100 :top 100 :width 140 :height 48}
          container {:left 20.0 :top 10.0 :width 900 :height 400}]
      (is (= {:x 232.0 :y 90.0 :node-cx 150.0 :node-cy 114.0}
             (anchor/anchor-right-of node container)))
      (is (= {:x 80.0 :y 150.0 :node-cx 150.0 :node-cy 114.0}
             (anchor/anchor-below node container)))
      (is (nil? (anchor/anchor-right-of (assoc node :width 0) container)))
      (is (nil? (anchor/anchor-below (assoc node :height 0) container))))))

(deftest join-resolved-follows-the-join-rule
  (testing ":all resolves only when every child is done, :any once one child
            is done; an explicit :resolved? from the host wins over the
            computed value; a join outside the closed :all / :any enum is not
            resolved"
    (doseq [[label spec expected]
            [[":all, every child done"     {:join :all :children [{:done? true} {:done? true}]}   true]
             [":all, one child pending"    {:join :all :children [{:done? true} {:done? false}]}  false]
             [":any, one child done"       {:join :any :children [{:done? true} {:done? false}]}  true]
             [":any, no child done"        {:join :any :children [{:done? false} {:done? false}]} false]
             ["host :resolved? true wins"  {:join :all :resolved? true :children [{:done? false}]} true]
             ["host :resolved? false wins" {:join :any :resolved? false :children [{:done? true}]} false]
             ["{:n N} join"                {:join {:n 2} :children [{:done? true} {:done? true}]} false]]]
      (is (identical? expected (anchor/join-resolved? spec)) label))))

(deftest join-summary-renders-done-and-positive-segments
  (testing "the `n/m done` segment always renders; `· failed` / `· cancelled`
            only when their count is positive"
    (doseq [[children expected]
            [[[{:done? true} {:failed? true} {:cancelled? true}] "1/3 done · 1 failed · 1 cancelled"]
             [[{:done? true} {:done? true}]                      "2/2 done"]]]
      (is (= expected (anchor/join-summary {:children children}))))))

(deftest cascade-summary-line-counts-and-pluralises
  (testing "each segment pluralises on its own axis"
    (doseq [[label steps expected]
            [["one destroy, three aborts" [{:kind :destroy} {:kind :abort} {:kind :abort} {:kind :abort}]
              "destroyed 1 actor · aborted 3 requests"]
             ["no steps"                  []                                  "no cascade steps"]
             ["one cleanup"               [{:kind :cleanup}]                  "1 cleanup"]
             ["two cleanups"              [{:kind :cleanup} {:kind :cleanup}] "2 cleanups"]]]
      (is (= expected (anchor/cascade-summary-line {:steps steps})) label))))
