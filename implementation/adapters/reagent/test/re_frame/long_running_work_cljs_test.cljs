(ns re-frame.long-running-work-cljs-test
  "The shipped parent coordinator joins exact current children, cancels survivors and resets for another run."
  (:require [cljs.test :refer-macros [deftest use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]
            [long-running-work.worker])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil}))

(defn- parent-machine-snapshot
  [frame]
  (get-in (rf/frame-state-value frame) [:rf.db/runtime :rf.runtime/machines :snapshots :work/flow]))

(defn- join-state
  [frame]
  (get-in (rf/frame-state-value frame) [:rf.db/runtime :rf.runtime/machines :spawned :work/flow [:working]]))

(defn- new-frame
  []
  (rf.frame/make-anon-frame-record! {:initial-events [[:work/flow [:reset]]]}))

;; Sync dispatch does not fire child timers; carry the runtime's exact current attempt.
(defn- dispatch-synthetic-child-completion!
  [test-frame shard]
  (let [join-snapshot (join-state test-frame)]
    (rf/dispatch-sync
      [:work/flow [:rf.machine.spawn/done [:working]
                   {:result     shard
                    :error?     false
                    :parent-id  :work/flow
                    :invoke-id  [:working]
                    :child-id   shard
                    :spawned-id (get-in join-snapshot [:children shard])
                    :attempt    (:rf/attempt join-snapshot)}]]
      {:frame test-frame})))

(deftest long-running-work-happy-path-join
  (with-new-frame [f (new-frame)]
    (rf/dispatch-sync [:work/flow [:start]] {:frame f})

    (dispatch-synthetic-child-completion! f :s1)
    (dispatch-synthetic-child-completion! f :s2)
    (is (= [#{:s1 :s2} :working] [(:done (join-state f)) (:state (parent-machine-snapshot f))]))

    (dispatch-synthetic-child-completion! f :s3)
    (let [parent-snapshot (parent-machine-snapshot f)]
      (is (= [:complete :complete nil]
             [(:state parent-snapshot) (-> parent-snapshot :data :outcome) (join-state f)])))))

(deftest long-running-work-cancel-cascade
  (with-new-frame [f (new-frame)]
    (rf/dispatch-sync [:work/flow [:start]] {:frame f})

    (rf/dispatch-sync [:work/flow [:progress :s1 30 100]] {:frame f})
    (rf/dispatch-sync [:work/flow [:progress :s2 50 100]] {:frame f})
    (rf/dispatch-sync [:work/flow [:progress :s3 10 100]] {:frame f})

    (let [parent-snapshot (parent-machine-snapshot f)]
      (is (= [:working {:s1 30 :s2 50 :s3 10} 90 300]
             [(:state parent-snapshot) (-> parent-snapshot :data :progress)
              (rf/compute-sub [:work/items-done] (rf/frame-state-value f))
              (rf/compute-sub [:work/total-items] (rf/frame-state-value f))])))

    (rf/dispatch-sync [:work/flow [:cancel]] {:frame f})
    (let [parent-snapshot (parent-machine-snapshot f)]
      (is (= [:cancelled :cancelled nil {:s1 30 :s2 50 :s3 10}]
             [(:state parent-snapshot) (-> parent-snapshot :data :outcome)
              (join-state f) (-> parent-snapshot :data :progress)])))))

(deftest long-running-work-reset-round-trip
  (with-new-frame [f (new-frame)]
    (rf/dispatch-sync [:work/flow [:start]]               {:frame f})
    (rf/dispatch-sync [:work/flow [:progress :s1 42 100]] {:frame f})
    (rf/dispatch-sync [:work/flow [:cancel]]              {:frame f})

    (rf/dispatch-sync [:work/flow [:reset]] {:frame f})
    (let [parent-snapshot (parent-machine-snapshot f)]
      (is (= [:idle {:s1 0 :s2 0 :s3 0} nil]
             [(:state parent-snapshot) (-> parent-snapshot :data :progress)
              (-> parent-snapshot :data :outcome)])))))
