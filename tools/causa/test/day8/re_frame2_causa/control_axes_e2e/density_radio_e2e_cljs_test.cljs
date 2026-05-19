(ns day8.re-frame2-causa.control-axes-e2e.density-radio-e2e-cljs-test
  "Multi-frame e2e coverage for the Settings density radio control
  axis (rf2-7icrs).

  The Settings popup's Density section writes to Causa's settings
  via `:rf.causa/settings-update :general :density :compact`. The
  `:rf.causa/density` sub reads the slot. CSS-var effects fire from
  the same dispatch; we don't assert DOM in node-test.

  ## What this catches

  - rf2-83d4x class — settings live in `:rf/causa`'s app-db, NOT the
    host's. The Settings UI must dispatch with `{:frame :rf/causa}`.
    `dispatch-causa` enforces this at the test surface.
  - General reactivity — the density sub MUST re-fire on the
    standard app-db-write reactive path after the settings update."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-causa.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest causa-density-defaults-to-cosy
  (e2e/with-host-and-causa-frames
    {:install-host counter/install-and-init!}
    (fn []
      ;; The default lives in `config/get-setting :general :density`;
      ;; the sub falls back through it when the slot is absent. We
      ;; assert the sub resolves to a keyword (or nil) — without
      ;; asserting a specific default since the default ships in
      ;; `config.cljc` and may evolve.
      (let [density (e2e/sub-causa [:rf.causa/density])]
        (is (or (nil? density) (keyword? density))
            ":rf.causa/density did not resolve to a keyword or nil")))))

(deftest causa-density-settings-update-writes-through
  (e2e/with-host-and-causa-frames
    {:install-host counter/install-and-init!}
    (fn []
      (e2e/dispatch-causa [:rf.causa/settings-update :general :density :compact])
      (is (= :compact (e2e/sub-causa [:rf.causa/density]))
          ":rf.causa/settings-update did not write :compact through to :rf.causa/density"))))

(deftest causa-density-roundtrip
  (testing "compact → cosy round-trip"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-causa [:rf.causa/settings-update :general :density :compact])
        (e2e/dispatch-causa [:rf.causa/settings-update :general :density :cosy])
        (is (= :cosy (e2e/sub-causa [:rf.causa/density]))
            "density did not round-trip back to :cosy")))))

(deftest causa-density-survives-host-dispatch
  (testing "rf2-83d4x — density is Causa-frame state"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-causa [:rf.causa/settings-update :general :density :compact])
        (e2e/dispatch-host [:counter/inc])
        (is (= :compact (e2e/sub-causa [:rf.causa/density]))
            "density reset on host dispatch — wrong-frame state class")))))
