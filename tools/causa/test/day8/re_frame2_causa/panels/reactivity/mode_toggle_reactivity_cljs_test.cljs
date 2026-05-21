(ns day8.re-frame2-causa.panels.reactivity.mode-toggle-reactivity-cljs-test
  "Sub-reactivity guard for the Cmd-Shift-M mode toggle (rf2-dhoc9
  per-control-action test).

  `:rf.causa/toggle-mode` flips between `:dynamic` and `:static`. The
  `:rf.causa/mode` sub the shell's chrome reads MUST re-fire so the
  L3 tab bar / silhouette switch in lockstep. This is the unit-level
  mirror of rf2-rwhat Phase 3's browser interaction; runs in millis."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-causa.test-helpers.sub-reactivity :as h]))

(use-fixtures :each h/fixture)

(deftest toggle-mode-flips-mode-sub
  (testing "rf2-dhoc9 — `:rf.causa/toggle-mode` flips `:rf.causa/mode`
            from `:dynamic` (default) to `:static` and back. The
            mode sub re-fires on each toggle."
    (h/setup-causa-frame!)
    (let [mode-0 (h/read-sub :rf.causa/mode)]
      (is (= :dynamic mode-0)
          "default mode is :dynamic per spec/007-UX-IA.md §Static mode")
      (h/dispatch-causa! [:rf.causa/toggle-mode])
      (let [mode-1 (h/read-sub :rf.causa/mode)]
        (is (= :static mode-1)
            "first toggle flips to :static")
        (is (not= mode-0 mode-1)
            "mode sub re-fired on toggle")
        (h/dispatch-causa! [:rf.causa/toggle-mode])
        (let [mode-2 (h/read-sub :rf.causa/mode)]
          (is (= :dynamic mode-2)
              "second toggle flips back to :dynamic")
          (is (not= mode-1 mode-2)
              "mode sub re-fired on second toggle"))))))

(deftest set-mode-writes-specific-mode
  (testing "rf2-dhoc9 — `:rf.causa/set-mode` writes a specific mode
            (used by the per-segment pill click). The sub re-fires
            with the new value."
    (h/setup-causa-frame!)
    (is (= :dynamic (h/read-sub :rf.causa/mode)))
    (h/dispatch-causa! [:rf.causa/set-mode :static])
    (is (= :static (h/read-sub :rf.causa/mode))
        "set-mode :static is honoured")
    (h/dispatch-causa! [:rf.causa/set-mode :dynamic])
    (is (= :dynamic (h/read-sub :rf.causa/mode))
        "set-mode :dynamic is honoured")))

(deftest static-selected-tab-sub-tracks-static-select-tab
  (testing "rf2-dhoc9 — the Static-scoped tab selection lives on its
            own slot (`:rf.causa.static/selected-tab`); the event
            `:rf.causa.static/select-tab` writes it and the sub
            re-fires."
    (h/setup-causa-frame!)
    (let [tab-0 (h/read-sub :rf.causa.static/selected-tab)]
      (is (= :machines tab-0)
          "default Static tab is :machines per static-shell config")
      (h/dispatch-causa! [:rf.causa.static/select-tab :routes])
      (let [tab-1 (h/read-sub :rf.causa.static/selected-tab)]
        (is (= :routes tab-1)
            "static-tab sub re-fired on select-tab :routes")
        (is (not= tab-0 tab-1))))))
