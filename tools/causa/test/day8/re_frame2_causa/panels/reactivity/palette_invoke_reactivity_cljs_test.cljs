(ns day8.re-frame2-causa.panels.reactivity.palette-invoke-reactivity-cljs-test
  "Sub-reactivity guard for the palette-invoke routing slot NOT
  already pinned by the e2e harness (rf2-dhoc9 per-control-action
  test).

  De-dup note (rf2-dkmnm): the `:rf.causa/palette-open` /
  `:rf.causa/palette-close` / `:rf.causa/palette-toggle` open-slot
  round-trips (plus query-update, the `:toggle-theme` invoke path and
  the wrong-frame `*-survives-host-dispatch` assertion) are owned by
  `control-axes-e2e/palette-invoke-e2e-cljs-test`. This file keeps
  only the slot that harness does NOT cover: `:rf.causa/palette-invoke`
  with a `:palette/select-panel` action lowering into a
  `:rf.causa/select-tab` dispatch that flips the selected-tab sub."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-causa.test-helpers.sub-reactivity :as h]))

(use-fixtures :each h/fixture)

(deftest palette-invoke-select-tab-flips-selected-tab-sub
  (testing "rf2-dhoc9 — `:rf.causa/palette-invoke` with action
            `:palette/select-panel <tab-id>` dispatches `:rf.causa/
            select-tab <tab-id>`. The `:rf.causa/selected-tab` sub
            re-fires with the new tab id. End-to-end the palette's
            tab-jump verb flips the chrome via the reactive path."
    (h/setup-causa-frame!)
    (is (= :event (h/read-sub :rf.causa/selected-tab))
        "default selected-tab is :event")
    (h/dispatch-causa!
      [:rf.causa/palette-invoke
       {:source :command
        :id :causa.jump-views
        :action [:palette/select-panel :views]}
       false])
    (is (= :views (h/read-sub :rf.causa/selected-tab))
        "palette-invoke routed to select-tab → sub re-fired")))
