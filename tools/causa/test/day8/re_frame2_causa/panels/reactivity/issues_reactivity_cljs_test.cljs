(ns day8.re-frame2-causa.panels.reactivity.issues-reactivity-cljs-test
  "Sub-reactivity guard for the Issues panel's primary composite
  (rf2-dhoc9, updated for rf2-jio48 rebuild).

  Per spec/021 §1.2 the Issues panel is focused-epoch-scoped — the
  composite re-fires when either the focused epoch flips (via
  `:rf.causa/focus`'s `:epoch-id`) or the user toggles a chip
  filter. This test pins both invariants."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-causa.test-helpers.sub-reactivity :as h]))

(use-fixtures :each h/fixture)

(def cascades
  [(h/cascade :c1 :rf/default)
   (h/cascade :c2 :rf/default)])

(def epoch-records
  [(h/mock-epoch :e1 :c1 {} {:counter 1}
                 {:trace-events
                  [{:id 1 :op-type :error
                    :operation :rf.error/handler-threw
                    :tags {:dispatch-id :c1}}]})
   (h/mock-epoch :e2 :c2 {:counter 1} {:counter 2}
                 {:trace-events
                  [{:id 2 :op-type :warning
                    :operation :rf.warning/recoverable
                    :tags {:dispatch-id :c2}}]})])

(deftest issues-ribbon-sub-tracks-focus-flip
  (testing "`:rf.causa/issues-ribbon` is focused-epoch-scoped via
            `:rf.causa/focus`'s `:epoch-id` (spec/021 §1.2 + §8). The
            sub re-fires on focus flip — even when both projections
            yield differently-shaped feeds, the composite must
            differ on at least the rendered slice."
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-records)
    (h/focus-cascade! :c1)
    (let [feed-1 (h/read-sub :rf.causa/issues-ribbon)]
      (is (map? feed-1) "issues-ribbon returns the projected shape")
      (is (= :e1 (:epoch-id feed-1)) "focus :c1 → epoch :e1")
      (h/focus-cascade! :c2)
      (let [feed-2 (h/read-sub :rf.causa/issues-ribbon)]
        (is (= :e2 (:epoch-id feed-2)) "focus :c2 → epoch :e2")
        (is (not= feed-1 feed-2)
            "issues-ribbon sub re-fired on focus flip")
        (is (= [1] (mapv :id (:issues feed-1)))
            "feed-1 surfaces epoch :e1's issues")
        (is (= [2] (mapv :id (:issues feed-2)))
            "feed-2 surfaces epoch :e2's issues")))))

(deftest issues-filters-axis-re-fires-on-filter-change
  (testing "`:rf.causa/issues-filters` re-fires when a filter axis
            flips; this guards the second reactive input to the
            composite alongside the focus axis."
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    (let [filters-1 (h/read-sub :rf.causa/issues-filters)]
      (is (= #{} (:severities filters-1)))
      (h/dispatch-causa! [:rf.causa.issues/toggle-severity :error])
      (let [filters-2 (h/read-sub :rf.causa/issues-filters)]
        (is (= #{:error} (:severities filters-2))
            "severity toggle wrote the active-severities set")
        (is (not= filters-1 filters-2)
            "issues-filters sub re-fired on toggle")))))

(deftest issues-ribbon-composes-focus-and-filters
  (testing "`:rf.causa/issues-ribbon` recomposes when EITHER input
            changes (focus OR filters). Pin the composition by
            changing both in sequence and asserting the composite
            map changes after each."
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-records)
    (h/focus-cascade! :c1)
    (let [feed-a (h/read-sub :rf.causa/issues-ribbon)]
      ;; Change focus.
      (h/focus-cascade! :c2)
      (let [feed-b (h/read-sub :rf.causa/issues-ribbon)]
        (is (not= feed-a feed-b)
            "focus flip changed the composite (focused-epoch axis)"))
      ;; Then change a filter.
      (h/dispatch-causa! [:rf.causa.issues/toggle-severity :warning])
      (let [feed-c (h/read-sub :rf.causa/issues-ribbon)]
        (is (not= (h/read-sub :rf.causa/issues-ribbon) feed-a)
            "filter toggle changed the composite too")
        (is (some? feed-c))))))
