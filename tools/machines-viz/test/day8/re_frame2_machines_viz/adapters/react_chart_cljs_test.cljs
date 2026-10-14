(ns day8.re-frame2-machines-viz.adapters.react-chart-cljs-test
  "The substrate-adapter React bridge, without a DOM: `chart-element` builds
  a React element of the once-reactified `MachineChart` class from a CLJS
  props map, which any React host (UIx / raw React) can mount. CLJS-only
  because the bridge requires reagent and the UIx shell, neither of which
  loads on the JVM."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-machines-viz.adapters.react-chart :as react-chart]
            [day8.re-frame2-machines-viz.adapters.uix :as mv-uix]))

(deftest chart-element-builds-a-react-element
  (testing "the element's type is the memoised MachineChart class, so a host
            re-render keeps the same component type and never remounts the
            chart"
    (is (identical? react-chart/MachineChartReactClass
                    (.-type (react-chart/chart-element {}))))))

;; The only test that requires the UIx shell, so the one that keeps it compiling.
(deftest uix-shell-is-defined
  (is (some? mv-uix/MachineChart)))
