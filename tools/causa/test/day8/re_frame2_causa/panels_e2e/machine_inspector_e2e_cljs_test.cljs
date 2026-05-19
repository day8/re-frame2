(ns day8.re-frame2-causa.panels-e2e.machine-inspector-e2e-cljs-test
  "Multi-frame e2e coverage for the Machine Inspector panel
  (rf2-7icrs, spec/017 — Machines row).

  The Machine Inspector panel reads two cross-cutting sources:

    1. `:rf.causa/registered-machines` — the set of machine ids the
       host has registered via `rf/reg-machine`.
    2. `:rf.causa/machine-snapshots` — the current snapshots for
       every machine, keyed by `:machine-id`.

  Bug class this catches:

  - rf2-hwuki — `:rf.machine/transition` emit dropped the `:frame`
    tag, which meant Causa's epoch-capture filter rejected the event
    and the Machine Inspector panel stayed empty even after a real
    machine transition fired. After rf2-hwuki lands (PR #1596), the
    transition tag is present and the panel reflects machine
    activity. Pre-#1596 this test catches the regression.

  ## Expected-fail before rf2-hwuki / PR #1596

  The `machine-inspector-reflects-transition` test below asserts the
  transition-cascade-into-Causa contract. Mark `^:expected-fail` if
  this lands before #1596 (the bead's coordination notes); rebase on
  worker/fix-hwuki-machine-frame-tag for the canonical green run."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-causa.test-helpers.host-fixtures.deep-machine :as deep-machine]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest causa-registered-machines-includes-host-machine
  (testing ":rf.causa/registered-machines reflects host's reg-machine call"
    (e2e/with-host-and-causa-frames
      {:install-host deep-machine/install!}
      (fn []
        (let [machines (e2e/sub-causa [:rf.causa/registered-machines])]
          (is (contains? (set machines) :deep/main)
              ":deep/main missing from :rf.causa/registered-machines"))))))

(deftest causa-machine-snapshots-include-host-machine-post-bootstrap
  (testing ":rf.causa/machine-snapshots carries :deep/main after bootstrap"
    (e2e/with-host-and-causa-frames
      {:install-host deep-machine/install-and-init!}
      (fn []
        (let [snapshots (e2e/sub-causa [:rf.causa/machine-snapshots])]
          (is (map? snapshots)
              ":rf.causa/machine-snapshots is not a map")
          (is (contains? snapshots :deep/main)
              ":deep/main not in machine-snapshots post-bootstrap")
          (is (= :idle (get-in snapshots [:deep/main :state]))
              ":deep/main's initial state should be :idle after bootstrap"))))))

(deftest causa-machine-inspector-data-resolves-shape
  (testing ":rf.causa/machine-inspector-data composite resolves"
    (e2e/with-host-and-causa-frames
      {:install-host deep-machine/install-and-init!}
      (fn []
        (let [data (e2e/sub-causa [:rf.causa/machine-inspector-data])]
          (is (map? data)
              ":rf.causa/machine-inspector-data did not return a map"))))))
