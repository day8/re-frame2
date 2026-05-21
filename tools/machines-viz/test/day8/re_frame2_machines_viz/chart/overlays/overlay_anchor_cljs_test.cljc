(ns day8.re-frame2-machines-viz.chart.overlays.overlay-anchor-cljs-test
  "Pure-data tests for the overlay anchoring + join/cascade helpers
  shared by the `:spawn-all` join inspector + the cancellation-cascade
  visualiser overlays (rf2-3ow55 · xyflow Phase 2).

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

;; ---- node->card-testid --------------------------------------------------

(deftest node-card-testid-matches-state-node-contract
  (is (= "rf-mv-chart-node-idle" (anchor/node->card-testid "idle")))
  (is (= "rf-mv-chart-node-auth_login__hydrating"
         (anchor/node->card-testid "auth_login__hydrating"))))

(deftest node-card-testid-nil-on-blank
  (is (nil? (anchor/node->card-testid nil)))
  (is (nil? (anchor/node->card-testid "")))
  (is (nil? (anchor/node->card-testid "   "))))

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

(deftest join-resolved-all
  (testing ":all resolves only when every child is done"
    (is (true?  (anchor/join-resolved?
                  {:join :all :children [{:done? true} {:done? true}]})))
    (is (false? (anchor/join-resolved?
                  {:join :all :children [{:done? true} {:done? false}]})))))

(deftest join-resolved-any
  (testing ":any resolves once one child is done"
    (is (true?  (anchor/join-resolved?
                  {:join :any :children [{:done? true} {:done? false}]})))
    (is (false? (anchor/join-resolved?
                  {:join :any :children [{:done? false} {:done? false}]})))))

(deftest join-resolved-n
  (testing "{:n N} resolves when N children are done"
    (is (true?  (anchor/join-resolved?
                  {:join {:n 2} :children [{:done? true} {:done? true} {:done? false}]})))
    (is (false? (anchor/join-resolved?
                  {:join {:n 3} :children [{:done? true} {:done? true} {:done? false}]})))))

(deftest join-resolved-host-override-wins
  (testing "an explicit :resolved? from the host wins over the computed value"
    (is (true? (anchor/join-resolved?
                 {:join :all :resolved? true :children [{:done? false}]})))
    (is (false? (anchor/join-resolved?
                  {:join :any :resolved? false :children [{:done? true}]})))))

;; ---- join-summary -------------------------------------------------------

(deftest join-summary-counts
  (is (= "1/3 done · 1 failed · 1 cancelled"
         (anchor/join-summary
           {:children [{:done? true} {:failed? true} {:cancelled? true}]})))
  (is (= "2/2 done"
         (anchor/join-summary
           {:children [{:done? true} {:done? true}]}))))

;; ---- cascade-counts + summary -------------------------------------------

(deftest cascade-counts-by-kind
  (let [steps [{:kind :exit} {:kind :destroy} {:kind :abort}
               {:kind :abort} {:kind :cleanup}]]
    (is (= {:destroyed 1 :aborted 2 :cleaned 1} (anchor/cascade-counts steps)))))

(deftest cascade-summary-line-pluralises
  (is (= "destroyed 1 actor · aborted 3 requests"
         (anchor/cascade-summary-line
           {:steps [{:kind :destroy}
                    {:kind :abort} {:kind :abort} {:kind :abort}]})))
  (is (= "no cascade steps"
         (anchor/cascade-summary-line {:steps []}))))
