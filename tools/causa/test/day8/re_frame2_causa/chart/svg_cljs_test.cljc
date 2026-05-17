(ns day8.re-frame2-causa.chart.svg-cljs-test
  "Pure-data tests for the chart-SVG renderer (rf2-2tkza Phase 1).

  The renderer returns hiccup. Tests walk the tree by `data-testid`
  rather than mounting to a DOM — same approach the panel view tests
  use (see machine_inspector_view_cljs_test.cljs)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.chart.layout :as layout]
            [day8.re-frame2-causa.chart.svg :as chart-svg]))

;; ---- fixtures ----------------------------------------------------------

(def small-machine
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :success :err :failed}}
             :success {:final? true}
             :failed  {:final? true}}})

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq tree))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid-prefix [tree prefix]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (when-let [tid (:data-testid (second node))]
                   #?(:clj  (.startsWith ^String tid ^String prefix)
                      :cljs (.startsWith tid prefix)))))
          (hiccup-seq tree)))

;; ---- empty ------------------------------------------------------------

(deftest render-empty-shows-fallback
  (let [tree (chart-svg/render-from-definition nil)]
    (is (some? (find-by-testid tree "rf-causa-chart-empty"))
        "nil definition renders a friendly fallback")))

;; ---- happy path -------------------------------------------------------

(deftest render-emits-svg-root
  (let [tree (chart-svg/render-from-definition small-machine)]
    (is (some? (find-by-testid tree "rf-causa-chart-svg"))
        "root SVG present")))

(deftest render-emits-one-node-per-state
  (let [tree    (chart-svg/render-from-definition small-machine)
        nodes   (find-all-by-testid-prefix tree "rf-causa-chart-node-")]
    (is (= 4 (count nodes))
        "four state nodes — :idle :loading :success :failed")))

(deftest render-emits-edges
  (let [tree (chart-svg/render-from-definition small-machine)
        edges (find-all-by-testid-prefix tree "rf-causa-chart-edge-")]
    (is (= 3 (count edges))
        "three edges — :start, :ok, :err")))

(deftest render-marks-active-node
  (let [hid  (layout/highlight-id :loading)
        tree (chart-svg/render-from-definition
               small-machine
               {:highlight-id hid})
        ;; node-id derived from path
        node (find-by-testid tree (str "rf-causa-chart-node-" hid))]
    (is (some? node))
    (is (= "true" (:data-active (second node)))
        ":loading node is data-active=\"true\"")))

(deftest render-no-active-when-no-highlight
  (let [tree (chart-svg/render-from-definition small-machine)
        nodes (find-all-by-testid-prefix tree "rf-causa-chart-node-")]
    (is (every? (fn [n] (= "false" (:data-active (second n)))) nodes)
        "no node is active when highlight-id is omitted")))

(deftest render-initial-marker-present
  (let [tree (chart-svg/render-from-definition small-machine)]
    (is (some? (find-by-testid tree "rf-causa-chart-initial-marker"))
        "the initial-state marker is rendered")))

(deftest render-on-state-click-fires-with-path
  (let [seen   (atom [])
        tree   (chart-svg/render-from-definition
                 small-machine
                 {:on-state-click (fn [p] (swap! seen conj p))})
        node   (find-by-testid
                 tree
                 (str "rf-causa-chart-node-" (layout/highlight-id :loading)))
        click  (:on-click (second node))]
    (is (some? click))
    (when click (click nil))
    (is (= [[:loading]] @seen)
        "click handler receives the state's :path")))

;; ---- sparkline primitive (rf2-juon8, Mode C) ---------------------------

(deftest sparkline-emits-stable-svg-root
  (let [svg (chart-svg/sparkline [1 2 3])]
    (is (vector? svg))
    (is (= :svg (first svg)))
    (is (= "rf-causa-chart-sparkline"
           (:data-testid (second svg))))))

(deftest sparkline-empty-samples-still-renders-baseline-only
  (let [svg      (chart-svg/sparkline [])
        polylines (filter #(and (vector? %) (= :polyline (first %)))
                          (hiccup-seq svg))]
    (is (vector? svg))
    (is (zero? (count polylines))
        "empty samples → baseline-only SVG (no polyline)")))

(deftest sparkline-includes-polyline-when-enough-samples
  (let [svg (chart-svg/sparkline [0 1 2 3])
        ;; polyline is inline; walk and find one
        nodes (filter #(and (vector? %) (= :polyline (first %)))
                      (hiccup-seq svg))]
    (is (= 1 (count nodes)))
    (let [pl (first nodes)]
      (is (string? (-> pl second :points)))
      (is (= "none" (-> pl second :fill))))))

(deftest sparkline-data-samples-attribute-roundtrips
  (let [svg (chart-svg/sparkline [0 1 5 2 3])]
    (is (= "[0 1 5 2 3]" (:data-samples (second svg))))))

(deftest sparkline-honours-custom-testid
  (let [svg (chart-svg/sparkline [1 2 3] {:testid "rf-cluster-rate-1"})]
    (is (= "rf-cluster-rate-1" (:data-testid (second svg))))))
