(ns day8.re-frame2-xray.panels.reactivity.issues-reactivity-cljs-test
  "Sub-reactivity guard for the Issues panel's primary composite
  (rf2-dhoc9, updated for rf2-jio48 rebuild; rf2-ad7zx.9 Figma reconcile).

  Per spec/021 §1.2 the Issues panel is focused-epoch-scoped — the
  composite re-fires when the focused epoch flips (via
  `:rf.xray/focus`'s `:epoch-id`). The filter axis was dropped at
  rf2-ad7zx.9 (the Figma design renders pure rows, no filtering), so
  focus is now the single reactive input."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.test-helpers.sub-reactivity :as h]))

(use-fixtures :each h/fixture)

(def cascades
  [(h/cascade :c1 :rf/default)
   (h/cascade :c2 :rf/default)])

(def epoch-records
  [(h/mock-epoch :e1 :c1 {} {:counter 1}
                 {:trace-events
                  [{:id 1 :op-type :error
                    :operation :rf.error/handler-exception
                    :tags {:rf.trace/dispatch-id :c1}}]})
   (h/mock-epoch :e2 :c2 {:counter 1} {:counter 2}
                 {:trace-events
                  [{:id 2 :op-type :warning
                    :operation :rf.warning/recoverable
                    :tags {:rf.trace/dispatch-id :c2}}]})])

(deftest issues-ribbon-sub-tracks-focus-flip
  (testing "`:rf.xray/issues-ribbon` is focused-epoch-scoped via
            `:rf.xray/focus`'s `:epoch-id` (spec/021 §1.2 + §8). The
            sub re-fires on focus flip — even when both projections
            yield differently-shaped feeds, the composite must
            differ on at least the rendered slice."
    (h/setup-xray-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-records)
    (h/focus-cascade! :c1)
    (let [feed-1 (h/read-sub :rf.xray/issues-ribbon)]
      (is (map? feed-1) "issues-ribbon returns the projected shape")
      (is (= :e1 (:epoch-id feed-1)) "focus :c1 → epoch :e1")
      (h/focus-cascade! :c2)
      (let [feed-2 (h/read-sub :rf.xray/issues-ribbon)]
        (is (= :e2 (:epoch-id feed-2)) "focus :c2 → epoch :e2")
        (is (not= feed-1 feed-2)
            "issues-ribbon sub re-fired on focus flip")
        (is (= [1] (mapv :id (:issues feed-1)))
            "feed-1 surfaces epoch :e1's issues")
        (is (= [2] (mapv :id (:issues feed-2)))
            "feed-2 surfaces epoch :e2's issues")))))

;; rf2-ad7zx.9 — `issues-filters-axis-re-fires-on-filter-change` and
;; `issues-ribbon-composes-focus-and-filters` were removed with the
;; Issues panel's filter-chrome reconcile to the Figma design (pure
;; rows, no filtering — spec/021 §8.2). The `:rf.xray/issues-filters`
;; sub + the chip-toggle events no longer exist; focus is the single
;; reactive input, pinned by `issues-ribbon-sub-tracks-focus-flip`.
