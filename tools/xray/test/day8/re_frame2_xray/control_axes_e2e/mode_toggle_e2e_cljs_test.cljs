(ns day8.re-frame2-xray.control-axes-e2e.mode-toggle-e2e-cljs-test
  "Multi-frame e2e coverage for the Mode toggle control axis
  (rf2-7icrs).

  Xray exposes two modes per `tools/xray/spec/007-UX-IA.md` §Static
  mode: Dynamic (event-coupled spine) and Static (registry browse).
  The mode lives at `:rf.xray/mode` (default `:dynamic`); the
  Cmd-Shift-M chord dispatches `:rf.xray/toggle-mode` which flips
  it.

  At the e2e level we assert:

    1. Default mode is `:dynamic` after Xray install.
    2. `:rf.xray/toggle-mode` dispatched into `:rf/xray` flips it
       to `:static`.
    3. A second toggle flips back to `:dynamic`.
    4. The mode survives a host dispatch (it is Xray-frame state,
       not host-frame state; rf2-83d4x wrong-frame-routing class)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest xray-mode-defaults-to-dynamic
  (e2e/with-host-and-xray-frames
    {:install-host counter/install-and-init!}
    (fn []
      (is (= :dynamic (e2e/sub-xray [:rf.xray/mode]))
          "default :rf.xray/mode is not :dynamic"))))

(deftest xray-toggle-mode-flips-dynamic-to-static
  (e2e/with-host-and-xray-frames
    {:install-host counter/install-and-init!}
    (fn []
      (e2e/dispatch-xray [:rf.xray/toggle-mode])
      (is (= :static (e2e/sub-xray [:rf.xray/mode]))
          ":rf.xray/toggle-mode did not flip mode to :static"))))

(deftest xray-toggle-mode-round-trip
  (e2e/with-host-and-xray-frames
    {:install-host counter/install-and-init!}
    (fn []
      (e2e/dispatch-xray [:rf.xray/toggle-mode])
      (e2e/dispatch-xray [:rf.xray/toggle-mode])
      (is (= :dynamic (e2e/sub-xray [:rf.xray/mode]))
          "second :rf.xray/toggle-mode did not return to :dynamic"))))

(deftest xray-mode-survives-host-dispatch
  (testing "rf2-83d4x — flipping mode lives in :rf/xray frame, not host"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-xray [:rf.xray/toggle-mode])
        (e2e/dispatch-host [:counter/inc])
        (is (= :static (e2e/sub-xray [:rf.xray/mode]))
            "mode flipped on host dispatch — wrong-frame routing regression")))))
