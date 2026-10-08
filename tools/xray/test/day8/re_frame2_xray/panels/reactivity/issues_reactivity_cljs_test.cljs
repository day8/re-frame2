(ns day8.re-frame2-xray.panels.reactivity.issues-reactivity-cljs-test
  "Sub-reactivity guard for the `:rf.xray/issues-ribbon` composite.

  Per spec/021 §1.2 the issue projection is focused-epoch-scoped — the
  composite re-fires when the focused epoch flips (via
  `:rf.xray/focus`'s `:epoch-id`). There is no filter axis (the
  Figma design renders pure rows, no filtering), so focus is the
  single reactive input."
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
    (is (= [1] (mapv :id (:issues (h/read-sub :rf.xray/issues-ribbon))))
        "focus :c1 surfaces epoch :e1's issues")
    (h/focus-cascade! :c2)
    (is (= [2] (mapv :id (:issues (h/read-sub :rf.xray/issues-ribbon))))
        "focus :c2 surfaces epoch :e2's issues — the sub re-fired on the flip")))
