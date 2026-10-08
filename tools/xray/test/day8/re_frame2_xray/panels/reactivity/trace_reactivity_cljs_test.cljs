(ns day8.re-frame2-xray.panels.reactivity.trace-reactivity-cljs-test
  "Sub-reactivity guard for the Trace panel's primary composite:
  `:rf.xray/trace-feed` reads the focused epoch record's `:trace-events`, so
  a refocus must rebind it."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.test-helpers.sub-reactivity :as h]))

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

(deftest trace-feed-scope-is-the-focused-epochs-trace-events
  (testing "the feed's rows are exactly the focused epoch's :trace-events,
            the async nil-dispatch-id reactive rows included, and follow a
            refocus"
    (h/setup-xray-frame!)
    (h/seed-cascades! cascades)
    (seed-epochs!)
    (h/focus-cascade! :c1)
    (is (= #{1 2} (set (map :id (:rows (h/read-sub :rf.xray/trace-feed))))))
    (h/focus-cascade! :c2)
    (is (= #{3 4 5} (set (map :id (:rows (h/read-sub :rf.xray/trace-feed))))))))
