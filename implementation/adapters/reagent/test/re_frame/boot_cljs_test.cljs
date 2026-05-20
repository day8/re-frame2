(ns re-frame.boot-cljs-test
  "Integration test: drives the boot example's headless test
   fixtures (rf2-dsm2). Each fixture spins a fresh frame via
   `make-frame`, drives the :app/boot machine through a canonical
   Pattern-Boot trajectory with canned :rf.http/managed stubs, and
   asserts the resulting machine state + app-db slices.

   The fixtures live under examples/reagent/boot/test/boot/ —
   extracted from the production example source so the example's
   .cljs files stay test-free (mirrors the realworld / nine-states
   layout).

   Per rf2-am9d this ns uses snapshot/restore via re-frame.test-support
   so the contract is uniform across CLJS fixtures: the snapshot
   captures the boot example's ns-load registrations
   (`:app/boot`, `:boot/loader`, `:app/initialise`, the subs and the
   demo fxs), and the restore on the way out leaves them intact for
   any subsequent test ns."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures]]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views]
            [boot.core]
            [boot.boot-test :as boot-t]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
    {:adapter reagent-adapter/adapter}))

(deftest boot-machine-progression
  (testing "happy path: boot machine traverses :configuring → :loading-deps → :hydrating → :ready and all slices land"
    (boot-t/machine-progression-test)))

(deftest boot-dependency-resolution
  (testing "per-child :data fns thread spawn-spec identity; no cross-talk between siblings"
    (boot-t/dependency-resolution-test)))

(deftest boot-failure-path
  (testing "a failure during the parallel phase routes the boot to :failed and records the error"
    (boot-t/failure-path-test)))
