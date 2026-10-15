(ns day8.re-frame2-xray.control-axes-e2e.event-id-mute-e2e-cljs-test
  "Mute settings stay in Xray and can be removed or cleared."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest xray-muted-event-ids-defaults-empty
  (e2e/with-host-and-xray-frames
    {:install-host counter/install-and-init!}
    (fn []
      (is (empty? (e2e/sub-xray [:rf.xray/muted-event-ids]))
          ":rf.xray/muted-event-ids should default to an empty set"))))

(deftest xray-unmute-event-id-removes-from-set
  (e2e/with-host-and-xray-frames
    {:install-host counter/install-and-init!}
    (fn []
      (e2e/dispatch-xray [:rf.xray/mute-event-id :counter/inc])
      (e2e/dispatch-xray [:rf.xray/unmute-event-id :counter/inc])
      (is (empty? (e2e/sub-xray [:rf.xray/muted-event-ids]))
          "unmute did not remove :counter/inc"))))

(deftest xray-clear-muted-clears-set
  (e2e/with-host-and-xray-frames
    {:install-host counter/install-and-init!}
    (fn []
      (e2e/dispatch-xray [:rf.xray/mute-event-id :counter/inc])
      (e2e/dispatch-xray [:rf.xray/mute-event-id :counter/dec])
      (e2e/dispatch-xray [:rf.xray/clear-muted-event-ids])
      (is (empty? (e2e/sub-xray [:rf.xray/muted-event-ids]))
          "clear-muted did not clear the set"))))

(deftest xray-mute-state-survives-host-dispatch
  (testing "muted-event-ids lives in :rf/xray, not host"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-xray [:rf.xray/mute-event-id :counter/inc])
        (e2e/dispatch-host [:counter/inc])
        (is (contains? (e2e/sub-xray [:rf.xray/muted-event-ids]) :counter/inc)
            "mute set cleared on host dispatch — wrong-frame state class")))))
