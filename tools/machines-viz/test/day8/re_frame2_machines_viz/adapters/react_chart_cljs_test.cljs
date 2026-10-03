(ns day8.re-frame2-machines-viz.adapters.react-chart-cljs-test
  "Smoke tests for the substrate-adapter React bridge + the UIx
  shell.

  These pin the substrate-parity contract WITHOUT a DOM: the bridge
  reactifies the Reagent `MachineChart` to a plain React class once,
  and `chart-element` builds a valid React element from a CLJS props
  map that any React host (UIx / raw React) can mount. The
  full mount-and-assert visual-pin coverage lives in the browser-side
  `*_dom_cljs_test.cljs` suites.

  CLJS-only (`.cljs`) because the bridge requires reagent + the UIx
  component macros, none of which load on the JVM. Runs under
  `:node-test` (the `cljs-test$` regex matches this file)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            ["react" :as react]
            [day8.re-frame2-machines-viz.adapters.react-chart :as react-chart]
            [day8.re-frame2-machines-viz.adapters.uix :as mv-uix]))

(def ^:private sample-machine
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :done}}
             :done    {:final? true}}})

;; ---- shared React bridge ------------------------------------------------

(deftest chart-element-builds-a-react-element
  (testing "chart-element returns a valid React element for the bridge"
    (let [el (react-chart/chart-element
               {:machine-id :auth/flow :definition sample-machine})]
      (is (react/isValidElement el)
          "the bridge produces a mountable React element")
      (is (identical? react-chart/MachineChartReactClass (.-type el))
          "the element's type is the reactified MachineChart class"))))

(deftest chart-element-carries-props-through-argv
  (testing "props ride on the Reagent `argv` prop at index 1 (the reactified
            Reagent component reads its render arg there); nil props degrade
            to an empty map"
    (let [props {:machine-id :auth/flow :definition sample-machine
                 :current-state :loading}]
      (doseq [[label in expected] [["a props map" props props]
                                   ["nil props"   nil   {}]]]
        (is (= expected (aget (.. (react-chart/chart-element in) -props -argv) 1))
            label)))))

;; ---- substrate shells ---------------------------------------------------

(deftest uix-shell-is-defined
  (testing "the UIx shell exposes a MachineChart component"
    (is (some? mv-uix/MachineChart)
        "UIx MachineChart shell is present")))

