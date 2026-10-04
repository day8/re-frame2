(ns day8.re-frame2-xray.panels.reactivity.density-radio-reactivity-cljs-test
  "Sub-reactivity guard for the density-control slots NOT already
  pinned by the e2e harness (a per-control-action test).

  De-dup note: the `:cosy`→`:compact`→`:cosy` round-trip
  of `:rf.xray/density` via `:rf.xray/settings-update` (plus the
  CSS-var-equivalent px helper and the wrong-frame
  `*-survives-host-dispatch` assertion) is owned by
  `control-axes-e2e/density-radio-e2e-cljs-test`. This file keeps
  only the slot that harness does NOT cover: the `:comfy` → `:cosy`
  normalisation step (there is no `:comfy` tier)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.test-helpers.sub-reactivity :as h]))

(use-fixtures :each h/fixture)

(deftest density-sub-normalises-comfy-to-cosy
  (testing "there is no `:comfy` tier; the sub
            normalises a stored `:comfy` (from a prior schema) to
            `:cosy`. The reactivity holds across the
            normalisation step."
    (h/setup-xray-frame!)
    (h/dispatch-xray! [:rf.xray/settings-update :general :density :comfy])
    (is (= :cosy (h/read-sub :rf.xray/density))
        "stored :comfy → sub returns :cosy (normalisation)")))
