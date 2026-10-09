(ns re-frame.flows-user-fx-lifecycle-settle-cljs-test
  "Spec 013 §Sequencing, on both hosts: flow lifecycle calls and frame-state
  writes made from a USER fx settle before the dispatch returns.

  The `:fx` walk runs after the event's flow pass. A clear, or a `reg-flow`
  that moves an output path, from a user fx queues its vacation for a pass
  that has already run, so the lifecycle call requests the settle itself; from
  a handler body the request is a no-op and the event's own pass applies it.
  Separately, the walk settles a flow-holding frame whose state container an
  fx changed. A user-fx clear touches only side tables, so the flows here are
  unclassified to keep the two triggers apart."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.flows :as rf.flows]
   [re-frame.frame :as rf.frame]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter substrate/adapter}))

(defn- db [] (rf/app-db-value :rf/default))

(defn- reg-chain-and-seed!
  "`:p3/a` [:x] -> [:a], `:p3/b` [:a] -> [:b], seeded to {:x 2 :a 2 :b 2}."
  []
  (rf/reg-flow :p3/a {:inputs [[:x]] :output-path [:a]} identity)
  (rf/reg-flow :p3/b {:inputs [[:a]] :output-path [:b]} identity)
  (rf/reg-event :seed (fn [_ _] {:db {:x 2}}))
  (rf/reg-event :noop (fn [_ _] {}))
  (rf/dispatch-sync [:seed]))

(deftest a-clear-from-a-user-fx-settles-in-one-dispatch
  (reg-chain-and-seed!)
  (rf/reg-fx :p3/clear-a (fn [_ _] (rf/clear :flow :p3/a)))
  (rf/reg-event :go (fn [_ _] {:fx [[:p3/clear-a nil]]}))
  (rf/dispatch-sync [:go])
  (is (not (contains? (get (rf.flows/flows-snapshot) :rf/default) :p3/a)))
  (is (= {:x 2 :b nil} (db)) "the leaf is vacated and the dependent derived from its absence")
  (rf/dispatch-sync [:noop])
  (is (= {:x 2 :b nil} (db)) "an unrelated drain finds nothing left to repair"))

(deftest a-path-moving-reg-flow-from-a-user-fx-settles-in-one-dispatch
  (reg-chain-and-seed!)
  (rf/reg-fx :p3/move-a
    (fn [{:keys [frame]} _]
      (rf/reg-flow :p3/a {:frame frame :inputs [[:x]] :output-path [:a2]} identity)))
  (rf/reg-event :go (fn [_ _] {:fx [[:p3/move-a nil]]}))
  (rf/dispatch-sync [:go])
  (is (= {:x 2 :a2 2 :b nil} (db))
      "the old leaf is vacated, the moved flow materialised, the dependent settled"))

(deftest a-clear-from-a-handler-body-is-unchanged
  (reg-chain-and-seed!)
  (rf/reg-event :go (fn [{:keys [db]} _]
                      (rf/clear :flow :p3/a)
                      {:db db}))
  (rf/dispatch-sync [:go])
  (is (= {:x 2 :b nil} (db))))

(deftest a-user-fx-writing-frame-state-settles-in-one-dispatch
  ;; Without the settle the flow's pass runs before the write and reads nil.
  (rf/reg-flow :p/rt {:inputs [[:rf.db/runtime :probe/n]] :output-path [:rt-n]} identity)
  (rf/reg-fx :p/bump-runtime
    (fn [{:keys [frame]} _] (rf.frame/swap-runtime-db! frame update :probe/n (fnil inc 0))))
  (rf/reg-event :go (fn [_ _] {:fx [[:p/bump-runtime nil]]}))
  (rf/dispatch-sync [:go])
  (is (= 1 (:rt-n (db)))))
