(ns re-frame.nine-states-cljs-test
  "Integration test: load examples/reagent/nine_states/core and run its 9
  headless test fixtures. Each fixture drives app-db into one of the
  canonical UI states and asserts the matching state-sub fires.

  The example registers its handlers / subs / views / machines at
  namespace-load time. CLJS has no runtime (require :reload), so this
  test relies on those registrations staying live for the test run.
  Each test-state-N fixture inside the example uses make-frame to spin
  up a fresh frame, so per-test isolation comes from frame creation,
  not registry resets.

  Per rf2-am9d the fixture uses snapshot/restore via
  re-frame.test-support so the contract is uniform across CLJS
  fixtures — the snapshot captures the example's ns-load
  registrations, and the restore on the way out leaves them intact for
  any subsequent test ns."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.substrate.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views]
            [nine-states.core :as ns-core]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(deftest nine-states-runs-end-to-end
  (is (= :ok (ns-core/run-all-tests))
      "nine-states.core/run-all-tests should walk all 9 UI states and return :ok"))
