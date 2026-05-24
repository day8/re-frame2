(ns day8.re-frame2-xray.panels.reactivity.density-radio-reactivity-cljs-test
  "Sub-reactivity guard for the density-control slots NOT already
  pinned by the e2e harness (rf2-dhoc9 per-control-action test).

  De-dup note (rf2-dkmnm): the `:cosy`→`:compact`→`:cosy` round-trip
  of `:rf.xray/density` via `:rf.xray/settings-update` (plus the
  CSS-var-equivalent px helper and the wrong-frame
  `*-survives-host-dispatch` assertion) is owned by
  `control-axes-e2e/density-radio-e2e-cljs-test`. This file keeps
  only the slots that harness does NOT cover:

    - the parameterised `:rf.xray/setting` read sub, and
    - the `:comfy` → `:cosy` normalisation step (rf2-ttnst dropped
      the `:comfy` tier)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.test-helpers.sub-reactivity :as h]))

(use-fixtures :each h/fixture)

(deftest setting-sub-parameterised-read-tracks-write
  (testing "rf2-dhoc9 — the parameterised `:rf.xray/setting` sub
            re-fires on write to the same `[section key]` pair. Pin
            the broader settings-update reactive surface."
    (h/setup-xray-frame!)
    (let [v-0 (h/read-sub :rf.xray/setting :general :density)]
      (h/dispatch-xray! [:rf.xray/settings-update :general :density :compact])
      (let [v-1 (h/read-sub :rf.xray/setting :general :density)]
        (is (= :compact v-1)
            "setting :general :density reads :compact post-write")
        (is (not= v-0 v-1)
            "parameterised setting sub re-fired")))))

(deftest density-sub-normalises-comfy-to-cosy
  (testing "rf2-dhoc9 — rf2-ttnst dropped the `:comfy` tier; the sub
            normalises a stored `:comfy` (from a prior schema) to
            `:cosy`. The reactivity still holds across the
            normalisation step."
    (h/setup-xray-frame!)
    (h/dispatch-xray! [:rf.xray/settings-update :general :density :comfy])
    (is (= :cosy (h/read-sub :rf.xray/density))
        "stored :comfy → sub returns :cosy (normalisation)")))
