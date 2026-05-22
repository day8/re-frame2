(ns day8.re-frame2-causa.panels.reactivity.trace-reactivity-cljs-test
  "Sub-reactivity guard for the Trace panel's primary composite
  (rf2-dhoc9; epoch-scoped rework rf2-td380).

  Per the rf2-70tkv affected-panels matrix Trace pivots on the spine's
  focus. Post-rf2-td380 the feed is EPOCH-scoped: `:rf.causa/trace-feed`
  reads the focused epoch record's `:trace-events` (joined from
  `:rf.causa/focus` + `:rf.causa/epoch-history`). This test pins that
  rebind so a regression in the trace-feed → focus reactive chain would
  surface in millis."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-causa.test-helpers.sub-reactivity :as h]))

(use-fixtures :each h/fixture)

(def cascades
  [(h/cascade :c1 :rf/default)
   (h/cascade :c2 :rf/default)])

(defn- seed-epochs!
  "Seed two epoch records, each carrying its own distinct
  `:trace-events` slice, paired to cascades :c1 / :c2."
  []
  (h/seed-epoch-history!
    [(h/mock-epoch 1 :c1 {} {:counter 1}
                   {:trace-events
                    [{:id 1 :op-type :rf.event :operation :rf.event/dispatched
                      :tags {:rf.trace/dispatch-id :c1 :frame :rf/default}}
                     {:id 2 :op-type :rf.sub :operation :rf.sub/run :tags {}}]})
     (h/mock-epoch 2 :c2 {:counter 1} {:counter 2}
                   {:trace-events
                    [{:id 3 :op-type :rf.event :operation :rf.event/dispatched
                      :tags {:rf.trace/dispatch-id :c2 :frame :rf/default}}
                     {:id 4 :op-type :rf.view :operation :rf.view/render :tags {}}
                     {:id 5 :op-type :rf.fx :operation :rf.fx/handled :tags {}}]})]))

(deftest trace-feed-tracks-focus-flip
  (testing "rf2-td380 — the trace-feed projection is epoch-scoped: it
            reads the focused epoch record's `:trace-events`. Flipping
            focus between epochs must change the projected feed."
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    (seed-epochs!)
    (h/focus-cascade! :c1)
    (let [feed-1 (h/read-sub :rf.causa/trace-feed)]
      (is (map? feed-1) "trace-feed returns the projected shape")
      (h/focus-cascade! :c2)
      (let [feed-2 (h/read-sub :rf.causa/trace-feed)]
        (is (not= feed-1 feed-2)
            "trace-feed re-fired on focus flip — the focused epoch's
             :epoch-id changed, so the projected rows changed")))))

(deftest trace-feed-scope-is-the-focused-epochs-trace-events
  (testing "rf2-td380 — the feed's rows are exactly the focused epoch's
            :trace-events (including the async nil-dispatch-id reactive
            rows). Verify by inspecting the projected row id sets."
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    (seed-epochs!)
    (h/focus-cascade! :c1)
    (let [feed-c1 (h/read-sub :rf.causa/trace-feed)]
      (is (= #{1 2} (set (map :id (:rows feed-c1))))
          "epoch 1's whole trail — event + the nil-dispatch-id :rf.sub/run")
      (h/focus-cascade! :c2)
      (let [feed-c2 (h/read-sub :rf.causa/trace-feed)]
        (is (= #{3 4 5} (set (map :id (:rows feed-c2))))
            "epoch 2's whole trail — event + view/render + fx")
        (is (not= (:rows feed-c1) (:rows feed-c2))
            "projected rows differ across focuses — epoch-scope changed")))))
