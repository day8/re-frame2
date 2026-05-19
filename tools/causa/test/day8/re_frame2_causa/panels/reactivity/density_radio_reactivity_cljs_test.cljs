(ns day8.re-frame2-causa.panels.reactivity.density-radio-reactivity-cljs-test
  "Sub-reactivity guard for the Settings density radio (rf2-dhoc9
  per-control-action test).

  `:rf.causa/settings-update :general :density <value>` writes the
  settings slot; `:rf.causa/density` (the convenience sub Views row
  padding + App-db diff row line-height read) re-fires. The DOM
  side-effect (CSS-var mutation via `effects/apply-density-font-
  size!`) is best-effort under node-test (no DOM), but the sub-chain
  to the CSS-var-equivalent surface IS what we test here — mirroring
  rf2-rwhat Phase 3's browser flow."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-causa.test-helpers.sub-reactivity :as h]))

(use-fixtures :each h/fixture)

(deftest density-sub-tracks-settings-update
  (testing "rf2-dhoc9 — `:rf.causa/settings-update :general :density`
            writes the slot; the convenience sub `:rf.causa/density`
            re-fires with the new value (normalised to the two-tier
            enum `:cosy` / `:compact` per rf2-ttnst)."
    (h/setup-causa-frame!)
    (let [density-0 (h/read-sub :rf.causa/density)]
      (is (= :cosy density-0)
          "default density is :cosy")
      (h/dispatch-causa! [:rf.causa/settings-update :general :density :compact])
      (let [density-1 (h/read-sub :rf.causa/density)]
        (is (= :compact density-1)
            "density flipped to :compact")
        (is (not= density-0 density-1)
            "density sub re-fired on settings-update")
        (h/dispatch-causa! [:rf.causa/settings-update :general :density :cosy])
        (let [density-2 (h/read-sub :rf.causa/density)]
          (is (= :cosy density-2)
              "density flipped back to :cosy")
          (is (not= density-1 density-2)
              "density sub re-fired on second settings-update"))))))

(deftest setting-sub-parameterised-read-tracks-write
  (testing "rf2-dhoc9 — the parameterised `:rf.causa/setting` sub
            re-fires on write to the same `[section key]` pair. Pin
            the broader settings-update reactive surface."
    (h/setup-causa-frame!)
    (let [v-0 (h/read-sub :rf.causa/setting :general :density)]
      (h/dispatch-causa! [:rf.causa/settings-update :general :density :compact])
      (let [v-1 (h/read-sub :rf.causa/setting :general :density)]
        (is (= :compact v-1)
            "setting :general :density reads :compact post-write")
        (is (not= v-0 v-1)
            "parameterised setting sub re-fired")))))

(deftest density-sub-normalises-comfy-to-cosy
  (testing "rf2-dhoc9 — rf2-ttnst dropped the `:comfy` tier; the sub
            normalises a stored `:comfy` (from a prior schema) to
            `:cosy`. The reactivity still holds across the
            normalisation step."
    (h/setup-causa-frame!)
    (h/dispatch-causa! [:rf.causa/settings-update :general :density :comfy])
    (is (= :cosy (h/read-sub :rf.causa/density))
        "stored :comfy → sub returns :cosy (normalisation)")))
