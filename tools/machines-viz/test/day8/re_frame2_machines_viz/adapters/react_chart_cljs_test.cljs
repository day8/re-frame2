(ns day8.re-frame2-machines-viz.adapters.react-chart-cljs-test
  "The substrate-adapter React bridge, without a DOM: `chart-element` builds
  a React element of the once-reactified `MachineChart` class from a CLJS
  props map, which any React host (UIx / raw React) can mount. CLJS-only
  because the bridge requires reagent and the UIx shell, neither of which
  loads on the JVM."
  (:require [cljs.test :refer-macros [deftest is testing]]
            ["react" :as react]
            [day8.re-frame2-machines-viz.adapters.react-chart :as react-chart]
            [day8.re-frame2-machines-viz.adapters.uix :as mv-uix]))

(def ^:private sample-machine
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :done}}
             :done    {:final? true}}})

(deftest chart-element-builds-a-react-element
  (testing "a valid React element of the memoised MachineChart class, whose
            props ride on the Reagent `argv` prop at index 1 (where the
            reactified component reads its render arg); nil props degrade to
            an empty map"
    (let [props {:machine-id :auth/flow :definition sample-machine
                 :current-state :loading}
          el    (react-chart/chart-element props)]
      (is (react/isValidElement el))
      (is (identical? react-chart/MachineChartReactClass (.-type el)))
      (is (= props (aget (.. el -props -argv) 1)))
      (is (= {} (aget (.. (react-chart/chart-element nil) -props -argv) 1))))))

;; The only test that requires the UIx shell, so the one that keeps it compiling.
(deftest uix-shell-is-defined
  (is (some? mv-uix/MachineChart)))
