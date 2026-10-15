(ns day8.re-frame2-xray.panels-e2e.machine-inspector-e2e-cljs-test
  "Host machine snapshots and focused transitions reach Xray subscriptions."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.deep-machine :as deep-machine]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest xray-registered-machines-includes-host-machine
  (testing ":rf.xray/registered-machines reflects host's reg-machine call"
    (e2e/with-host-and-xray-frames
      {:install-host deep-machine/install!}
      (fn []
        (let [machines (e2e/sub-xray [:rf.xray/registered-machines])]
          (is (contains? (set machines) :deep/main)
              ":deep/main missing from :rf.xray/registered-machines"))))))

(deftest xray-machine-snapshots-include-host-machine-post-bootstrap
  (e2e/with-host-and-xray-frames
    {:install-host deep-machine/install-and-init!}
    (fn []
      (is (= :idle (get-in (e2e/sub-xray [:rf.xray/machine-snapshots])
                          [:deep/main :state]))))))

(deftest xray-machine-inspector-data-resolves-shape
  (testing ":rf.xray/machine-inspector-data composite resolves"
    (e2e/with-host-and-xray-frames
      {:install-host deep-machine/install-and-init!}
      (fn []
        (let [data (e2e/sub-xray [:rf.xray/machine-inspector-data])]
          (is (map? data)
              ":rf.xray/machine-inspector-data did not return a map"))))))

;; ---- focused-event lens populates for a REAL machine ---------------------
;;
;; The chart-render contract end-to-end: a real `reg-machine` transition
;; MUST surface in `:rf.xray/machine-transitions-for-focused-event` after
;; flowing through the REAL pipeline — trace-bus → epoch-capture (the
;; `:frame`-tag admission gate) → the focused epoch's `:trace-events` →
;; the focused-event lens. NO `:rf.xray/set-epoch-history-for-test`
;; injection seam: a lens test that injects synthetic trace events cannot
;; lock the real path, so the chart could read empty for real machines
;; while every injected test passed.

(deftest machine-inspector-focused-event-lens-populates-for-real-transition
  (e2e/with-host-and-xray-frames
    {:install-host deep-machine/install-and-init!}
    (fn []
      (e2e/dispatch-host [:deep/main [:work/go]])
      (is (= [{:machine-id :deep/main :from-state :idle
               :to-state :active :event [:work/go]}]
             (mapv #(select-keys % [:machine-id :from-state :to-state :event])
                   (e2e/sub-xray [:rf.xray/machine-transitions-for-focused-event])))))))

(deftest machine-inspector-focused-event-lens-tracks-second-real-transition
  (e2e/with-host-and-xray-frames
    {:install-host deep-machine/install-and-init!}
    (fn []
      (e2e/dispatch-host [:deep/main [:work/go]])
      (is (= :active (:to-state
                      (first (e2e/sub-xray
                               [:rf.xray/machine-transitions-for-focused-event])))))
      (e2e/dispatch-host [:deep/main [:work/reset]])
      (is (= [{:from-state :active :to-state :idle}]
             (mapv #(select-keys % [:from-state :to-state])
                   (e2e/sub-xray [:rf.xray/machine-transitions-for-focused-event])))))))
