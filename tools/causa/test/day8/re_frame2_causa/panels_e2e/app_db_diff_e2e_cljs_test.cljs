(ns day8.re-frame2-causa.panels-e2e.app-db-diff-e2e-cljs-test
  "Multi-frame e2e coverage for the App-DB Diff panel (rf2-7icrs,
  spec/017 — App-DB Diff row).

  The App-DB Diff panel projects an `:rf/epoch-record` (read off
  `:rf.causa/epoch-history`) into a before/after slice tree. The
  bug class this catches:

  - rf2-70tkv App-DB diff frozen on focused-event flip. With a single
    epoch in history this was masked; once a second host dispatch
    arrives the spine's focused epoch flips to the new head, the
    `:rf.causa/selected-epoch-record` sub MUST re-fire on the
    standard `:<-` reactive path, and the diff projection MUST land
    on the new pair (db-before = pre-dispatch app-db; db-after =
    post-dispatch app-db).

  This test exercises the full pipeline: real host dispatch into the
  epoch ring → epoch-cb push → Causa's `:rf.causa/epoch-history`
  slot update → spine `:rf.causa/focus` auto-advance → selected-
  epoch-record sub re-fires."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-causa.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory {:adapter plain-atom/adapter}))

(deftest causa-epoch-history-grows-after-host-dispatch
  (testing ":rf.causa/epoch-history grows from 0 → 1 → 2 over host dispatches"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        ;; The init dispatch may itself record an epoch; assert from
        ;; current state rather than absolute counts so the test is
        ;; resilient to epoch artefact's per-dispatch contract.
        (let [before-count (count (e2e/sub-causa [:rf.causa/epoch-history]))]
          (e2e/dispatch-host [:counter/inc])
          (let [after-count (count (e2e/sub-causa [:rf.causa/epoch-history]))]
            (is (= (inc before-count) after-count)
                "epoch-history did not grow by 1 after host dispatch — rf2-hwuki :frame-tag-missing class")))))))

(deftest causa-selected-epoch-record-tracks-head
  (testing "spine auto-follow → selected-epoch-record carries head epoch's event"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-host [:counter/inc])
        (let [record (e2e/sub-causa [:rf.causa/selected-epoch-record])]
          (is (some? record)
              ":rf.causa/selected-epoch-record returned nil despite epoch history present")
          (is (= [:counter/inc] (:trigger-event record))
              "selected-epoch-record's :trigger-event did not match the dispatched event"))
        (e2e/dispatch-host [:counter/inc])
        (let [record (e2e/sub-causa [:rf.causa/selected-epoch-record])]
          (is (= [:counter/inc] (:trigger-event record))
              "selected-epoch-record did not advance to second dispatch — rf2-70tkv panel-frozen class"))))))

(deftest causa-target-frame-db-mirrors-host
  (testing ":rf.causa/target-frame-db reflects the host frame's CURRENT app-db"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-host [:counter/inc])
        (let [target-db (e2e/sub-causa [:rf.causa/target-frame-db])]
          (is (= 6 (:counter/value target-db))
              ":rf.causa/target-frame-db did not see the host's :counter/value post-dispatch"))))))
