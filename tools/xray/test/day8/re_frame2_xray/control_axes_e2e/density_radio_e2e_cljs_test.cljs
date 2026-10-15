(ns day8.re-frame2-xray.control-axes-e2e.density-radio-e2e-cljs-test
  "Density changes stay in Xray across host dispatches."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.settings.effects :as settings-effects]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest xray-density-roundtrip
  (testing "compact → cosy round-trip"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-xray [:rf.xray/settings-update :general :density :compact])
        (e2e/dispatch-xray [:rf.xray/settings-update :general :density :cosy])
        (is (= :cosy (e2e/sub-xray [:rf.xray/density]))
            "density did not round-trip back to :cosy")))))

(deftest xray-density-survives-host-dispatch
  (testing "density is Xray-frame state"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-xray [:rf.xray/settings-update :general :density :compact])
        (e2e/dispatch-host [:counter/inc])
        (is (= :compact (e2e/sub-xray [:rf.xray/density]))
            "density reset on host dispatch — wrong-frame state class")))))

;; ---- CSS-var-equivalent (pure fn) mirrors the radio choice ----
;;
;; The radio writes `--rf-xray-font-size` via `effects/apply-density-font-
;; size!` so the whole type scale rescales in lockstep. In node-test there
;; is no `<html>` to inspect, but the pure CSS-var-equivalent helper
;; (`effects/density->px`) is the JVM-portable mirror — same lookup table,
;; same fallback. This reads the helper against the live density sub, so
;; a regression that decoupled the px map from the sub surfaces here.

(deftest xray-density-px-tracks-radio
  (e2e/with-host-and-xray-frames
    {:install-host counter/install-and-init!}
    (fn []
      (doseq [[density px] [[:compact 12] [:cosy 13]]]
        (e2e/dispatch-xray [:rf.xray/settings-update :general :density density])
        (is (= px (settings-effects/density->px
                    (e2e/sub-xray [:rf.xray/density]))))))))
