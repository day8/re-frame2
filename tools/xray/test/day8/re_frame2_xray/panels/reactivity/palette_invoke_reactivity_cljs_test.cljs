(ns day8.re-frame2-xray.panels.reactivity.palette-invoke-reactivity-cljs-test
  "Sub-reactivity guard for the palette-invoke routing slot NOT
  already pinned by the e2e harness (rf2-dhoc9 per-control-action
  test).

  De-dup note (rf2-dkmnm): the `:rf.xray/palette-open` /
  `:rf.xray/palette-close` / `:rf.xray/palette-toggle` open-slot
  round-trips (plus query-update, the `:toggle-theme` invoke path and
  the wrong-frame `*-survives-host-dispatch` assertion) are owned by
  `control-axes-e2e/palette-invoke-e2e-cljs-test`. This file keeps
  only the slot that harness does NOT cover: `:rf.xray/palette-invoke`
  with a `:palette/select-panel` action lowering into a
  `:rf.xray/select-tab` dispatch that flips the selected-tab sub."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.test-helpers.sub-reactivity :as h]))

(use-fixtures :each h/fixture)

(deftest palette-invoke-select-tab-flips-selected-tab-sub
  (testing "rf2-dhoc9 — `:rf.xray/palette-invoke` with action
            `:palette/select-panel <tab-id>` dispatches `:rf.xray/
            select-tab <tab-id>`. The `:rf.xray/selected-tab` sub
            re-fires with the new tab id. End-to-end the palette's
            tab-jump verb flips the chrome via the reactive path."
    (h/setup-xray-frame!)
    (is (= :epoch (h/read-sub :rf.xray/selected-tab))
        "default selected-tab is :epoch (post rf2-5gl5r — supersedes :event)")
    (h/dispatch-xray!
      [:rf.xray/palette-invoke
       {:source :command
        :id :xray.jump-views
        :action [:palette/select-panel :views]}
       false])
    (is (= :views (h/read-sub :rf.xray/selected-tab))
        "palette-invoke routed to select-tab → sub re-fired")))
