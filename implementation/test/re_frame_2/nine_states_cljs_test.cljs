(ns re-frame-2.nine-states-cljs-test
  "Integration test: load examples/nine_states/core and run its 9
  headless test fixtures. Each fixture drives app-db into one of the
  canonical UI states and asserts the matching state-sub fires.

  The example registers its handlers / subs / views / machines at
  namespace-load time. CLJS has no runtime (require :reload), so this
  test runs WITHOUT resetting the registrar — the registrations land
  once when the example's ns is loaded by shadow-cljs and stay live
  for the test run. Each test-state-N fixture inside the example uses
  make-frame to spin up a fresh frame, so per-test isolation comes
  from frame creation, not registry resets."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame-2.core :as rf]
            [re-frame-2.substrate.adapter :as adapter]
            [re-frame-2.substrate.reagent :as reagent-adapter]
            [re-frame-2.views]
            [nine-states.core :as ns-core]))

(deftest nine-states-runs-end-to-end
  ;; Ensure the Reagent adapter is installed (the example's tests use
  ;; subscribe-value, which needs an adapter for derived values).
  (when-not (adapter/current-adapter)
    (rf/init! reagent-adapter/adapter))
  (is (= :ok (ns-core/run-all-tests))
      "nine-states.core/run-all-tests should walk all 9 UI states and return :ok"))
