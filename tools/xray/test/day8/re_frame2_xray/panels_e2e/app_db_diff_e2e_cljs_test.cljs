(ns day8.re-frame2-xray.panels-e2e.app-db-diff-e2e-cljs-test
  "Host dispatches reach Xray epoch history and current app-db."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest xray-epoch-history-grows-after-host-dispatch
  (testing ":rf.xray/epoch-history grows from 0 → 1 → 2 over host dispatches"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        ;; The init dispatch may itself record an epoch; assert from
        ;; current state rather than absolute counts so the test is
        ;; resilient to epoch artefact's per-dispatch contract.
        (let [before-count (count (e2e/sub-xray [:rf.xray/epoch-history]))]
          (e2e/dispatch-host [:counter/inc])
          (let [after-count (count (e2e/sub-xray [:rf.xray/epoch-history]))]
            (is (= (inc before-count) after-count)
                "epoch-history did not grow by 1 after host dispatch — :frame-tag-missing class")))))))

(deftest xray-target-frame-db-mirrors-host
  (testing ":rf.xray/target-frame-db reflects the host frame's CURRENT app-db"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-host [:counter/inc])
        (let [target-db (e2e/sub-xray [:rf.xray/target-frame-db])]
          (is (= 6 (:counter/value target-db))
              ":rf.xray/target-frame-db did not see the host's :counter/value post-dispatch"))))))
