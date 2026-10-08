(ns day8.re-frame2-xray.static.machines.topology-cljs-test
  "Static Machines Topology → machine-canvas/Chart prop
  boundary.

  The Static Topology chart wrapper forwards the machine's STATIC
  context shape into `machine-canvas/Chart` so the root Context band
  renders without a live snapshot; a wrapper mounting only definition /
  machine-id would drop the band.

  The chart wrapper is the private `topology/chart` fn; we invoke it via
  its var so the `[machine-canvas/Chart {...}]` mount survives as data
  (a raw tree-seq, no fn-component expansion, finds its props)."
  (:require [cljs.test :refer-macros [deftest is]]
            [day8.re-frame2-xray.panels.machine-canvas :as machine-canvas]
            [day8.re-frame2-xray.static.machines.topology :as topology]))

(def ^:private inferred-definition
  "No [:schemas :data] → the context shape is INFERRED from one sample of the
  initial :data (the chart keeps the `inferred from :data` badge)."
  {:initial :idle
   :data    {:opened-count 0 :held-open? false :trail []}
   :states  {:idle {:on {:open :opening}}
             :opening {:final? true}}})

(defn- chart-props
  "Render the private `topology/chart` wrapper for `definition` and return
  the embedded machine-canvas/Chart props map (or nil if absent)."
  [definition]
  (let [tree       (#'topology/chart
                     identity
                     {:definition definition :machine-id :door/main})
        chart-node (some (fn [node]
                           (when (and (vector? node)
                                      (= machine-canvas/Chart (first node)))
                             node))
                         (tree-seq (some-fn vector? seq?) seq tree))]
    (second chart-node)))

(deftest topology-forwards-inferred-context-shape-to-chart
  (is (= [{:opened-count "number" :held-open? "boolean" :trail "vector"} true]
         ((juxt :context-band :context-band-inferred?)
          (chart-props inferred-definition)))))
