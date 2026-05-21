(ns day8.re-frame2-machines-viz.adapters.react-chart-cljs-test
  "Smoke tests for the substrate-adapter React bridge + the UIx /
  Helix shells (rf2-yg9he · xyflow Phase 2).

  These pin the substrate-parity contract WITHOUT a DOM: the bridge
  reactifies the Reagent `MachineChart` to a plain React class once,
  and `chart-element` builds a valid React element from a CLJS props
  map that any React host (UIx / Helix / raw React) can mount. The
  full mount-and-assert visual-pin coverage lives in the browser-side
  `*_dom_cljs_test.cljs` suites.

  CLJS-only (`.cljs`) because the bridge requires reagent + the UIx /
  Helix component macros, none of which load on the JVM. Runs under
  `:node-test` (the `cljs-test$` regex matches this file)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            ["react" :as react]
            [day8.re-frame2-machines-viz.adapters.react-chart :as react-chart]
            [day8.re-frame2-machines-viz.adapters.uix :as mv-uix]
            [day8.re-frame2-machines-viz.adapters.helix :as mv-helix]))

(def ^:private sample-machine
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :done}}
             :done    {:final? true}}})

;; ---- shared React bridge ------------------------------------------------

(deftest reactified-class-is-stable-by-reference
  (testing "the Reagent MachineChart is reactified ONCE (React caches
            component types by reference; a per-render reactify would
            churn the subtree)"
    (is (some? react-chart/MachineChartReactClass))
    (is (identical? react-chart/MachineChartReactClass
                    react-chart/MachineChartReactClass))))

(deftest chart-element-builds-a-react-element
  (testing "chart-element returns a valid React element for the bridge"
    (let [el (react-chart/chart-element
               {:machine-id :auth/flow :definition sample-machine})]
      (is (react/isValidElement el)
          "the bridge produces a mountable React element")
      (is (identical? react-chart/MachineChartReactClass (.-type el))
          "the element's type is the reactified MachineChart class"))))

(deftest chart-element-carries-props-through-argv
  (testing "props ride on the Reagent `argv` prop at index 1 (the
            reactified Reagent component reads its render arg there)"
    (let [props {:machine-id :auth/flow :definition sample-machine
                 :current-state :loading}
          el    (react-chart/chart-element props)
          argv  (.. el -props -argv)]
      (is (= props (aget argv 1))
          "the props map sits at argv[1]"))))

(deftest chart-element-tolerates-nil-props
  (is (react/isValidElement (react-chart/chart-element nil))
      "nil props degrade to an empty map, still a valid element"))

;; ---- substrate shells ---------------------------------------------------

(deftest uix-shell-is-defined
  (testing "the UIx shell exposes a MachineChart component"
    (is (some? mv-uix/MachineChart)
        "UIx MachineChart shell is present")))

(deftest helix-shell-is-defined
  (testing "the Helix shell exposes a MachineChart component"
    (is (some? mv-helix/MachineChart)
        "Helix MachineChart shell is present")))
