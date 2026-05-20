(ns day8.re-frame2-causa.control-axes-e2e.mode-toggle-e2e-cljs-test
  "Multi-frame e2e coverage for the Mode toggle control axis
  (rf2-7icrs).

  Causa exposes two modes per `tools/causa/spec/007-UX-IA.md` §Static
  mode: Runtime (event-coupled spine) and Static (registry browse).
  The mode lives at `:rf.causa/mode` (default `:runtime`); the
  Cmd-Shift-M chord dispatches `:rf.causa/toggle-mode` which flips
  it.

  At the e2e level we assert:

    1. Default mode is `:runtime` after Causa install.
    2. `:rf.causa/toggle-mode` dispatched into `:rf/causa` flips it
       to `:static`.
    3. A second toggle flips back to `:runtime`.
    4. The mode survives a host dispatch (it is Causa-frame state,
       not host-frame state; rf2-83d4x wrong-frame-routing class)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-causa.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest causa-mode-defaults-to-runtime
  (e2e/with-host-and-causa-frames
    {:install-host counter/install-and-init!}
    (fn []
      (is (= :runtime (e2e/sub-causa [:rf.causa/mode]))
          "default :rf.causa/mode is not :runtime"))))

(deftest causa-toggle-mode-flips-runtime-to-static
  (e2e/with-host-and-causa-frames
    {:install-host counter/install-and-init!}
    (fn []
      (e2e/dispatch-causa [:rf.causa/toggle-mode])
      (is (= :static (e2e/sub-causa [:rf.causa/mode]))
          ":rf.causa/toggle-mode did not flip mode to :static"))))

(deftest causa-toggle-mode-round-trip
  (e2e/with-host-and-causa-frames
    {:install-host counter/install-and-init!}
    (fn []
      (e2e/dispatch-causa [:rf.causa/toggle-mode])
      (e2e/dispatch-causa [:rf.causa/toggle-mode])
      (is (= :runtime (e2e/sub-causa [:rf.causa/mode]))
          "second :rf.causa/toggle-mode did not return to :runtime"))))

(deftest causa-mode-survives-host-dispatch
  (testing "rf2-83d4x — flipping mode lives in :rf/causa frame, not host"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-causa [:rf.causa/toggle-mode])
        (e2e/dispatch-host [:counter/inc])
        (is (= :static (e2e/sub-causa [:rf.causa/mode]))
            "mode flipped on host dispatch — wrong-frame routing regression")))))
