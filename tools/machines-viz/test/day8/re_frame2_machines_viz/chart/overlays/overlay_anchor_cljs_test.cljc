(ns day8.re-frame2-machines-viz.chart.overlays.overlay-anchor-cljs-test
  "Pure-data tests for the overlay anchoring + join/cascade helpers
  shared by the `:spawn-all` join inspector + the cancellation-cascade
  visualiser overlays.

  The overlays walk the rendered DOM to find a bearing node's bounding
  rect; these helpers turn that rect + the overlay container's rect
  into the card's overlay-local `{:x :y}` anchor, and resolve the join
  state. Pure → JVM-runnable, so the math is pinned without a DOM.

  Dual-target via `_cljs_test.cljc` — same pattern every machines-viz
  helper test uses."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-machines-viz.chart.overlays.overlay-anchor
             :as anchor]))

;; ---- node->testid (the single canonical helper) --------------------------

(deftest node-testid-matches-state-node-contract
  (is (= "rf-mv-chart-node-idle" (anchor/node->testid "idle")))
  (is (= "rf-mv-chart-node-auth_login__hydrating"
         (anchor/node->testid "auth_login__hydrating"))))

(deftest node-testid-nil-on-blank
  (is (nil? (anchor/node->testid nil)))
  (is (nil? (anchor/node->testid "")))
  (is (nil? (anchor/node->testid "   "))))

;; ---- anchor-right-of (join inspector) -----------------------------------

(def ^:private container {:left 20 :top 10 :width 900 :height 400})

(deftest anchor-right-of-positions-card-beside-node
  ;; Node at viewport (100,100) 140×48; container origin (20,10) →
  ;; overlay-local node left = 80, top = 90; card sits at x = 80 + 140 +
  ;; gap(12) = 232, y = 90.
  (let [a (anchor/anchor-right-of {:left 100 :top 100 :width 140 :height 48}
                                  container)]
    (is (= 232.0 (double (:x a))))
    (is (= 90.0  (double (:y a))))
    ;; node centre for a connector line: cx = 80 + 70 = 150, cy = 90+24.
    (is (= 150.0 (:node-cx a)))
    (is (= 114.0 (:node-cy a)))))

(deftest anchor-right-of-nil-on-missing-or-degenerate
  (is (nil? (anchor/anchor-right-of nil container)))
  (is (nil? (anchor/anchor-right-of {:left 0 :top 0 :width 10 :height 10} nil)))
  (is (nil? (anchor/anchor-right-of {:left 0 :top 0 :width 0 :height 48} container))))

;; ---- anchor-below (cascade waterfall) -----------------------------------

(deftest anchor-below-positions-card-beneath-node
  ;; Same node; card sits at x = 80 (node left), y = 90 + 48 + gap(12) = 150.
  (let [a (anchor/anchor-below {:left 100 :top 100 :width 140 :height 48}
                               container)]
    (is (= 80.0  (double (:x a))))
    (is (= 150.0 (double (:y a))))
    (is (= 150.0 (:node-cx a)))
    (is (= 114.0 (:node-cy a)))))

(deftest anchor-below-nil-on-degenerate
  (is (nil? (anchor/anchor-below {:left 0 :top 0 :width 10 :height 0} container))))

;; ---- join-resolved? -----------------------------------------------------

(deftest join-resolved-follows-the-join-rule
  (testing ":all resolves only when every child is done, :any once one child
            is done; an explicit :resolved? from the host wins over the
            computed value; an out-of-enum join (e.g. {:n N} / {:fn}) is NOT
            resolved, because the grammar is a closed :all / :any enum"
    (doseq [[label spec expected]
            [[":all, every child done"   {:join :all :children [{:done? true} {:done? true}]}   true]
             [":all, one child pending"  {:join :all :children [{:done? true} {:done? false}]}  false]
             [":any, one child done"     {:join :any :children [{:done? true} {:done? false}]}  true]
             [":any, no child done"      {:join :any :children [{:done? false} {:done? false}]} false]
             ["host :resolved? true wins"  {:join :all :resolved? true :children [{:done? false}]} true]
             ["host :resolved? false wins" {:join :any :resolved? false :children [{:done? true}]} false]
             ["{:n N} join"              {:join {:n 2} :children [{:done? true} {:done? true}]} false]
             ["{:fn} join"               {:join {:fn :host-decides} :children [{:done? true} {:done? true}]} false]]]
      (is (identical? expected (anchor/join-resolved? spec)) label))))

;; ---- join-summary -------------------------------------------------------

(deftest join-summary-renders-done-and-positive-segments
  (testing "the leading `n/m done` segment always renders; the `· failed` /
            `· cancelled` segments appear ONLY when their count is positive"
    (doseq [[label children expected]
            [["all three kinds"        [{:done? true} {:failed? true} {:cancelled? true}] "1/3 done · 1 failed · 1 cancelled"]
             ["all done"               [{:done? true} {:done? true}]                      "2/2 done"]
             ["failed, none cancelled" [{:done? true} {:failed? true}]                    "1/2 done · 1 failed"]
             ["no children"            []                                                 "0/0 done"]]]
      (is (= expected (anchor/join-summary {:children children})) label))))

;; ---- cascade-counts + summary -------------------------------------------

(deftest cascade-summary-line-counts-and-pluralises
  (testing "each segment pluralises on its own axis; a cleanup-only cascade
            still renders its segment; an `:exit` step contributes to no
            count bucket (`cascade-counts` tracks only destroy / abort /
            cleanup), so an exit-only cascade reads like an empty one"
    (doseq [[label steps expected]
            [["one destroy, three aborts" [{:kind :destroy} {:kind :abort} {:kind :abort} {:kind :abort}]
              "destroyed 1 actor · aborted 3 requests"]
             ["no steps"                  []                                  "no cascade steps"]
             ["one cleanup"               [{:kind :cleanup}]                  "1 cleanup"]
             ["two cleanups"              [{:kind :cleanup} {:kind :cleanup}] "2 cleanups"]
             ["exit steps only"           [{:kind :exit} {:kind :exit}]       "no cascade steps"]]]
      (is (= expected (anchor/cascade-summary-line {:steps steps})) label))))
