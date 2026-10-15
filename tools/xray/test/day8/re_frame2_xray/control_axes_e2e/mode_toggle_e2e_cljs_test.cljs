(ns day8.re-frame2-xray.control-axes-e2e.mode-toggle-e2e-cljs-test
  "Mode changes stay in Xray across host dispatches."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest xray-mode-defaults-to-dynamic
  (e2e/with-host-and-xray-frames
    {:install-host counter/install-and-init!}
    (fn []
      (is (= :dynamic (e2e/sub-xray [:rf.xray/mode]))
          "default :rf.xray/mode is not :dynamic"))))

(deftest xray-toggle-mode-round-trip
  (e2e/with-host-and-xray-frames
    {:install-host counter/install-and-init!}
    (fn []
      (e2e/dispatch-xray [:rf.xray/toggle-mode])
      (e2e/dispatch-xray [:rf.xray/toggle-mode])
      (is (= :dynamic (e2e/sub-xray [:rf.xray/mode]))
          "second :rf.xray/toggle-mode did not return to :dynamic"))))

(deftest xray-mode-survives-host-dispatch
  (testing "flipping mode lives in :rf/xray frame, not host"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-xray [:rf.xray/toggle-mode])
        (e2e/dispatch-host [:counter/inc])
        (is (= :static (e2e/sub-xray [:rf.xray/mode]))
            "mode flipped on host dispatch — wrong-frame routing regression")))))
