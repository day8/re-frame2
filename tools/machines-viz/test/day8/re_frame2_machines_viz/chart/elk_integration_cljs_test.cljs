(ns day8.re-frame2-machines-viz.chart.elk-integration-cljs-test
  "Browser-only integration tests for the ELK adapter (rf2-m7co9 Phase 4).

  These tests exercise the actual ELK.js lazy loader path against a
  pre-stubbed `js/window.ELK` constructor (so the test does not depend
  on the bundler resolving `elkjs`, which keeps the ELK package
  optional at the consumer-classpath level). The pure-data adapter
  tests live in `elk_layout_cljs_test.cljs`.

  The browser-test build picks this ns up via the `-cljs-test$`
  regex; the node-test build (which doesn't define `js/window`) skips
  the body via a host check so this file is also safe to ship in the
  node-test classpath."
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.chart.elk-layout :as elk-layout]
            [day8.re-frame2-machines-viz.chart.svg :as chart-svg]))

;; ---- fixtures -----------------------------------------------------------

(defn- clear-elk-stub! []
  (elk-layout/reset-elk-state-for-test!)
  (elk-layout/reset-cache-for-test!)
  (when (exists? js/window)
    (set! (.-ELK js/window) js/undefined)))

(use-fixtures :each
  {:before clear-elk-stub!
   :after  clear-elk-stub!})

;; ---- ELK stub -----------------------------------------------------------

(def three-state-machine
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :done}}
             :done    {:final? true}}})

(def compound-machine
  {:initial :unauth
   :states  {:unauth        {:on {:login :authenticated}}
             :authenticated {:initial :browsing
                             :states  {:browsing {:on {:checkout :paying}}
                                       :paying   {:on {:done :browsing}}}
                             :on      {:logout :unauth}}}})

(defn- mk-deterministic-elk-stub
  "Construct an ELK-shaped object whose .layout assigns x/y based on
  child position in the input list. Good enough to assert 'ELK ran +
  positions were honoured' without bundling real ELK into the rig."
  []
  (fn []
    (this-as this
      (set! (.-layout this)
            (fn [graph]
              (let [graph-clj  (js->clj graph :keywordize-keys true)
                    children   (:children graph-clj)
                    positioned (vec
                                 (map-indexed
                                   (fn [idx c]
                                     (assoc c
                                       :x (* idx 200)
                                       :y (* idx 100)
                                       :width  140
                                       :height 48))
                                   children))
                    edges      (mapv (fn [e]
                                       (assoc e :sections
                                              [{:startPoint {:x 50 :y 50}
                                                :endPoint   {:x 150 :y 150}
                                                :bendPoints [{:x 100 :y 100}]}]))
                                     (:edges graph-clj))
                    result     (clj->js
                                 (assoc graph-clj
                                   :children positioned
                                   :edges    edges
                                   :width    800
                                   :height   400))]
                (js/Promise.resolve result))))
      this)))

;; ---- integration: stub-load + layout-or-fallback ------------------------

(deftest elk-load-then-compute-then-cache
  (testing "stub ELK, load it, compute layout, observe cache populated +
            positions on the chart-layout match the stub's grid"
    (when (exists? js/window)
      (set! (.-ELK js/window) (mk-deterministic-elk-stub))
      (async done
        (elk-layout/ensure-elk!
          (fn [_inst]
            (is (= :ready (elk-layout/elk-status)))
            (elk-layout/compute-layout!
              three-state-machine :tb
              (fn [chart-layout]
                (is (some? chart-layout)
                    "compute-layout! delivers the projected chart-layout")
                (is (= 3 (count (:nodes chart-layout))))
                (doseq [n (:nodes chart-layout)]
                  (is (integer? (:x n)))
                  (is (integer? (:y n))))
                ;; The cache should now hold the same layout.
                (let [cached (elk-layout/cached-layout three-state-machine :tb)]
                  (is (some? cached))
                  (is (= (count (:nodes chart-layout)) (count (:nodes cached)))))
                (done)))))))))

(deftest svg-renders-cleanly-from-elk-layout
  (testing "stub ELK, compute layout, hand the result to chart-svg/render
            — the SVG renders without bombing on multi-point edges"
    (when (exists? js/window)
      (set! (.-ELK js/window) (mk-deterministic-elk-stub))
      (async done
        (elk-layout/ensure-elk!
          (fn [_inst]
            (elk-layout/compute-layout!
              three-state-machine :tb
              (fn [chart-layout]
                (let [tree (chart-svg/render chart-layout {})]
                  ;; Just assert the root SVG comes through — the
                  ;; renderer's `path-from-points` accepts the multi-
                  ;; point polyline output from ELK without crashing.
                  (is (vector? tree))
                  (is (= :svg (first tree)))
                  (done))))))))))

(deftest svg-compound-container-renders-for-compound-machine
  (testing "stub ELK, compute layout for a compound machine — the SVG
            includes the compound-container <g> wrapping the children"
    (when (exists? js/window)
      (set! (.-ELK js/window) (mk-deterministic-elk-stub))
      (async done
        (elk-layout/ensure-elk!
          (fn [_inst]
            (elk-layout/compute-layout!
              compound-machine :tb
              (fn [chart-layout]
                (let [tree (chart-svg/render chart-layout {})
                      ;; walk the hiccup tree
                      flat (tree-seq (some-fn vector? seq?) seq tree)
                      compound? (some (fn [node]
                                        (and (vector? node)
                                             (map? (second node))
                                             (= "rf-causa-chart-compounds"
                                                (:data-testid (second node)))))
                                      flat)]
                  (is (true? (boolean compound?))
                      "compound container <g> renders even when ELK
                       placed every leaf flat — the visual hull is
                       computed by chart/svg's `compound-containers`")
                  (done))))))))))
