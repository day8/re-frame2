(ns day8.re-frame2-causa.panels.reactivity.views-group-by-reactivity-cljs-test
  "Sub-reactivity guard for the Views Group-By radio (rf2-dhoc9
  per-control-action test).

  `:rf.causa/views-set-group-by` writes the `:views/group-by` slot;
  `:rf.causa/views-group-by` re-fires and the composite `:rf.causa/
  views-data` recomposes with the new `:group-by` axis. Mirrors the
  rf2-dodq2 bug class: Views Group-By stuck on `:component` when the
  user clicked the `:sub` radio."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-causa.test-helpers.sub-reactivity :as h]))

(use-fixtures :each h/fixture)

(deftest views-group-by-sub-tracks-set-group-by
  (testing "rf2-dhoc9 — `:rf.causa/views-set-group-by` writes the slot;
            the `:rf.causa/views-group-by` sub re-fires."
    (h/setup-causa-frame!)
    (is (= :component (h/read-sub :rf.causa/views-group-by))
        "default group-by is :component")
    (h/dispatch-causa! [:rf.causa/views-set-group-by :sub])
    (is (= :sub (h/read-sub :rf.causa/views-group-by))
        "set-group-by :sub → sub re-fired with new value")
    (h/dispatch-causa! [:rf.causa/views-set-group-by :component])
    (is (= :component (h/read-sub :rf.causa/views-group-by))
        "set-group-by :component → sub re-fired back")))

(deftest views-data-composite-tracks-group-by-change
  (testing "rf2-dhoc9 — the composite `:rf.causa/views-data` re-fires
            with a new `:group-by` axis when the radio flips. The
            view consumes `:group-by` directly off the composite so
            its render branch flips in lockstep."
    (h/setup-causa-frame!)
    (h/seed-cascades! [(h/cascade :c1 :rf/default)])
    (let [data-1 (h/read-sub :rf.causa/views-data)]
      (is (= :component (:group-by data-1)))
      (h/dispatch-causa! [:rf.causa/views-set-group-by :sub])
      (let [data-2 (h/read-sub :rf.causa/views-data)]
        (is (= :sub (:group-by data-2))
            "composite's :group-by axis follows the radio")
        (is (not= data-1 data-2)
            "views-data composite re-fired on group-by flip")))))

(deftest views-set-group-by-nil-defaults-to-component
  (testing "rf2-dhoc9 — nil arg defaults to `:component` (per the
            event reducer). The sub re-fires to the default."
    (h/setup-causa-frame!)
    (h/dispatch-causa! [:rf.causa/views-set-group-by :sub])
    (is (= :sub (h/read-sub :rf.causa/views-group-by)))
    (h/dispatch-causa! [:rf.causa/views-set-group-by nil])
    (is (= :component (h/read-sub :rf.causa/views-group-by))
        "nil → :component default, sub re-fired")))
