(ns day8.re-frame2-causa.panels.reactivity.palette-invoke-reactivity-cljs-test
  "Sub-reactivity guard for Cmd-K palette open / invoke (rf2-dhoc9
  per-control-action test).

  `:rf.causa/palette-open` / `:rf.causa/palette-close` / `:rf.causa/
  palette-toggle` flip the `:palette-open?` slot; the sub re-fires so
  the modal mounts / unmounts. `:rf.causa/palette-invoke` with a
  `:palette/select-tab` action lowers into a `:rf.causa/select-tab`
  dispatch, which flips the tab sub. This mirrors rf2-rwhat Phase 3's
  Cmd-K browser flow."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-causa.test-helpers.sub-reactivity :as h]))

(use-fixtures :each h/fixture)

(deftest palette-open-sub-tracks-open-event
  (testing "rf2-dhoc9 — `:rf.causa/palette-open` flips the
            `:rf.causa/palette-open?` slot; the sub re-fires."
    (h/setup-causa-frame!)
    (is (false? (h/read-sub :rf.causa/palette-open?))
        "default closed")
    (h/dispatch-causa! [:rf.causa/palette-open])
    (is (true? (h/read-sub :rf.causa/palette-open?))
        "palette-open → sub re-fired with true")
    (h/dispatch-causa! [:rf.causa/palette-close])
    (is (false? (h/read-sub :rf.causa/palette-open?))
        "palette-close → sub re-fired with false")))

(deftest palette-toggle-flips-open-sub
  (testing "rf2-dhoc9 — the Cmd-K key binding fires `:rf.causa/
            palette-toggle`, which flips the open slot. Two toggles
            return to the original state."
    (h/setup-causa-frame!)
    (let [open-0 (h/read-sub :rf.causa/palette-open?)]
      (h/dispatch-causa! [:rf.causa/palette-toggle])
      (let [open-1 (h/read-sub :rf.causa/palette-open?)]
        (is (= true open-1)
            "first toggle opens the palette")
        (is (not= open-0 open-1))
        (h/dispatch-causa! [:rf.causa/palette-toggle])
        (let [open-2 (h/read-sub :rf.causa/palette-open?)]
          (is (= false open-2)
              "second toggle closes the palette")
          (is (not= open-1 open-2)))))))

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
