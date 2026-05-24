(ns day8.re-frame2-xray.panels.reactivity.mode-toggle-reactivity-cljs-test
  "Sub-reactivity guard for the mode-control slots NOT already pinned
  by the e2e harness (rf2-dhoc9 per-control-action test).

  De-dup note (rf2-dkmnm): the `:dynamic`→`:static`→`:dynamic`
  round-trip via `:rf.xray/toggle-mode` is owned by
  `control-axes-e2e/mode-toggle-e2e-cljs-test` (which also pins the
  wrong-frame `*-survives-host-dispatch` assertion). This file keeps
  only the slots that harness does NOT cover:

    - `:rf.xray/set-mode` (the per-segment pill click writes a
      specific mode, not a toggle).
    - `:rf.xray.static/select-tab` (the Static-scoped tab lives on
      its own slot)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.test-helpers.sub-reactivity :as h]))

(use-fixtures :each h/fixture)

(deftest set-mode-writes-specific-mode
  (testing "rf2-dhoc9 — `:rf.xray/set-mode` writes a specific mode
            (used by the per-segment pill click). The sub re-fires
            with the new value."
    (h/setup-xray-frame!)
    (is (= :dynamic (h/read-sub :rf.xray/mode)))
    (h/dispatch-xray! [:rf.xray/set-mode :static])
    (is (= :static (h/read-sub :rf.xray/mode))
        "set-mode :static is honoured")
    (h/dispatch-xray! [:rf.xray/set-mode :dynamic])
    (is (= :dynamic (h/read-sub :rf.xray/mode))
        "set-mode :dynamic is honoured")))

(deftest static-selected-tab-sub-tracks-static-select-tab
  (testing "rf2-dhoc9 — the Static-scoped tab selection lives on its
            own slot (`:rf.xray.static/selected-tab`); the event
            `:rf.xray.static/select-tab` writes it and the sub
            re-fires."
    (h/setup-xray-frame!)
    (let [tab-0 (h/read-sub :rf.xray.static/selected-tab)]
      (is (= :machines tab-0)
          "default Static tab is :machines per static-shell config")
      (h/dispatch-xray! [:rf.xray.static/select-tab :routes])
      (let [tab-1 (h/read-sub :rf.xray.static/selected-tab)]
        (is (= :routes tab-1)
            "static-tab sub re-fired on select-tab :routes")
        (is (not= tab-0 tab-1))))))
