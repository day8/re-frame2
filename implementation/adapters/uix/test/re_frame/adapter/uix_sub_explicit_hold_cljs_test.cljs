(ns re-frame.adapter.uix-sub-explicit-hold-cljs-test
  "An explicit subscription survives watch removal until unsubscribe releases it and its inputs."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.test-support :as rf.test-support])
  (:require-macros [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter}))

(defn- slot [query-v]
  (get @(:sub-cache (rf.frame/frame :rf/default)) query-v))

(defn- dispose-counts
  "Dispose events counted by subscription ID."
  [traces]
  (frequencies (map #(-> % :tags :rf.sub/id) traces)))

(deftest explicit-hold-survives-a-dropped-watch
  (testing "an explicit subscribe, then a watch added and removed: the slot
   is kept at ref-count 1 and nothing is evicted, and the later
   rf/unsubscribe evicts the sub and both inputs, each exactly once"
    (rf/reg-event ::init (fn [_ _] {:db {:a 3 :b 4}}))
    (rf/reg-sub ::a (fn [db _] (:a db)))
    (rf/reg-sub ::b (fn [db _] (:b db)))
    (rf/reg-sub ::sum {:inputs [[::a] [::b]]} (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [::init])
    (with-trace-recorder! [traces {:pred #(= :rf.sub/dispose (:operation %))}]
      (let [held (rf/subscribe [::sum])]
        (is (= 7 @held) "precondition: the sub computes")
        (add-watch held ::w (fn [_ _ _ _] nil))
        (remove-watch held ::w)
        (is (= [0 1] [(count @traces) (:ref-count (slot [::sum]))])
            "dropping the watch evicted nothing; the explicit hold remains")
        (rf/unsubscribe held)
        (is (= [{::sum 1 ::a 1 ::b 1} [nil nil nil]]
               [(dispose-counts @traces) (mapv slot [[::sum] [::a] [::b]])])
            "the unsubscribe evicted the sub and both inputs, each exactly once")))))
