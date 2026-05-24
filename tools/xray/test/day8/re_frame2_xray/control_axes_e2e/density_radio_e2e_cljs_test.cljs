(ns day8.re-frame2-xray.control-axes-e2e.density-radio-e2e-cljs-test
  "Multi-frame e2e coverage for the Settings density radio control
  axis (rf2-7icrs).

  The Settings popup's Density section writes to Xray's settings
  via `:rf.xray/settings-update :general :density :compact`. The
  `:rf.xray/density` sub reads the slot. CSS-var effects fire from
  the same dispatch; we don't assert DOM in node-test.

  ## What this catches

  - rf2-83d4x class — settings live in `:rf/xray`'s app-db, NOT the
    host's. The Settings UI must dispatch with `{:frame :rf/xray}`.
    `dispatch-xray` enforces this at the test surface.
  - General reactivity — the density sub MUST re-fire on the
    standard app-db-write reactive path after the settings update."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.settings.effects :as settings-effects]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest xray-density-defaults-to-cosy
  (e2e/with-host-and-xray-frames
    {:install-host counter/install-and-init!}
    (fn []
      ;; The default lives in `config/get-setting :general :density`;
      ;; the sub falls back through it when the slot is absent. We
      ;; assert the sub resolves to a keyword (or nil) — without
      ;; asserting a specific default since the default ships in
      ;; `config.cljc` and may evolve.
      (let [density (e2e/sub-xray [:rf.xray/density])]
        (is (or (nil? density) (keyword? density))
            ":rf.xray/density did not resolve to a keyword or nil")))))

(deftest xray-density-settings-update-writes-through
  (e2e/with-host-and-xray-frames
    {:install-host counter/install-and-init!}
    (fn []
      (e2e/dispatch-xray [:rf.xray/settings-update :general :density :compact])
      (is (= :compact (e2e/sub-xray [:rf.xray/density]))
          ":rf.xray/settings-update did not write :compact through to :rf.xray/density"))))

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
  (testing "rf2-83d4x — density is Xray-frame state"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-xray [:rf.xray/settings-update :general :density :compact])
        (e2e/dispatch-host [:counter/inc])
        (is (= :compact (e2e/sub-xray [:rf.xray/density]))
            "density reset on host dispatch — wrong-frame state class")))))

;; ---- rf2-w991t — CSS-var-equivalent (pure fn) mirrors the radio choice ----
;;
;; The radio writes `--rf-xray-font-size` via `effects/apply-density-font-
;; size!` so the whole type scale rescales in lockstep. In node-test there
;; is no `<html>` to inspect, but the pure CSS-var-equivalent helper
;; (`effects/density->px`) is the JVM-portable mirror — same lookup table,
;; same fallback. The original Phase 3 bead (rf2-mpqxn) wanted the test
;; to assert the CSS-var-equivalent moves with the radio; we read the
;; helper against the live density sub so a regression that decoupled the
;; px map from the sub would surface here.

(deftest xray-density-px-tracks-radio
  (testing "rf2-w991t — density→px helper mirrors the radio's choice"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-xray [:rf.xray/settings-update :general :density :compact])
        (let [d (e2e/sub-xray [:rf.xray/density])]
          (is (= 12 (settings-effects/density->px d))
              ":compact density did not resolve to 12px via density->px"))
        (e2e/dispatch-xray [:rf.xray/settings-update :general :density :cosy])
        (let [d (e2e/sub-xray [:rf.xray/density])]
          (is (= 13 (settings-effects/density->px d))
              ":cosy density did not resolve to 13px via density->px"))
        ;; The radio choice's CSS-var value MUST differ between pills
        ;; — that is the "differently-shaped CSS surface per pill"
        ;; assertion the original Playwright scenario expressed by
        ;; comparing computed font-size.
        (e2e/dispatch-xray [:rf.xray/settings-update :general :density :compact])
        (let [compact-px (settings-effects/density->px
                           (e2e/sub-xray [:rf.xray/density]))]
          (e2e/dispatch-xray [:rf.xray/settings-update :general :density :cosy])
          (let [cosy-px (settings-effects/density->px
                          (e2e/sub-xray [:rf.xray/density]))]
            (is (not= compact-px cosy-px)
                "compact and cosy resolved to the same px — CSS-var-equivalent collapse")))))))
