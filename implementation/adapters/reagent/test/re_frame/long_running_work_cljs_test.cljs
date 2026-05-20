(ns re-frame.long-running-work-cljs-test
  "Integration test: drives the long-running-work example's headless
   test fixtures (rf2-o9fg). Each fixture spins a fresh frame via
   `make-frame`, walks the :work/flow parent through a flow
   (spawn cascade, happy-path join, mid-flight cancel, parent
   unmount, reset round-trip), and asserts the resulting
   [:rf/machines :work/flow] snapshot + the runtime-owned
   [:rf/spawned :work/flow [:working]] join-state slot.

   The fixtures live under examples/reagent/long_running_work/test/
   long_running_work/worker_test.cljs — mirrors the realworld /
   nine_states test-extraction pattern so the production source
   stays test-free.

   Per rf2-am9d this ns uses snapshot/restore via re-frame.test-support
   so the contract is uniform across CLJS fixtures — the snapshot
   captures the example's ns-load registrations (the :work/flow
   parent + :work/processor child machines and the views' framework
   subs), and the restore on the way out leaves them intact for any
   subsequent test ns."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures]]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views]
            ;; Require the worker ns directly — its ns-load reg-machine
            ;; calls install :work/flow + :work/processor + the related
            ;; subs. We don't need long-running-work.core (the entry
            ;; point) since the integration tests bypass mount and
            ;; React entirely; the views ns is exercised by the
            ;; Playwright smoke.
            [long-running-work.worker]
            [long-running-work.worker-test :as wt]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
    {:adapter reagent-adapter/adapter}))

(deftest long-running-work-spawn-cascade
  (testing ":start spawns 3 children via :invoke-all and seeds the join-state"
    (wt/test-spawn-cascade)))

(deftest long-running-work-happy-path-join
  (testing "synthesised :on-child-done events resolve the :all join and stamp :complete"
    (wt/test-happy-path-join)))

(deftest long-running-work-cancel-cascade
  (testing "mid-flight :cancel tears down every surviving child via the :invoke-all exit"
    (wt/test-cancel-cascade)))

(deftest long-running-work-parent-unmount-cascade
  (testing "view-unmount path: same :cancel dispatch from r/with-let cleanup"
    (wt/test-parent-unmount-cascade)))

(deftest long-running-work-reset-round-trip
  (testing ":cancelled → :reset returns the parent to :idle with cleared :progress"
    (wt/test-reset-after-cancel)))
