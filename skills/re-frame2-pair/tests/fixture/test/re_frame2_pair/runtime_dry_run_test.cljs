(ns re-frame2-pair.runtime-dry-run-test
  (:require [cljs.test :refer [deftest is]]
            [re-frame.core :as rf]
            [re-frame.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame2-pair.runtime :as rt]))

(defn- with-probe-frame [f]
  (rf/init! rf.substrate.plain-atom/adapter)
  (let [frame-id :review/dry-run
        handler-calls (atom 0)
        effect-calls (atom 0)]
    (rf/make-frame
      {:id frame-id
       :images [(rf/image
                  {:id :review/dry-run-image
                   :registrations
                   {:reg-event
                    [[:review/seed (fn [_ _] {:db {:n 0}})]
                     [:review/probe
                      (fn [{:keys [db]} _]
                        (swap! handler-calls inc)
                        {:db (update db :n (fnil inc 0))
                         :fx [[:review/side-effect :payload]]})]]
                    :reg-fx [[:review/side-effect
                              (fn [_ _] (swap! effect-calls inc))]]}})]})
    (try
      (f frame-id handler-calls effect-calls)
      (finally (rf/destroy-frame! frame-id)))))

(defn- assert-simulation-restores [frame-id handler-calls effect-calls]
  (let [before (rf/frame-state-value frame-id)
        calls-before @handler-calls
        result (rt/dispatch-dry-run [:review/probe] {:frame frame-id})]
    (is (true? (:ok? result)))
    (is (true? (:rolled-back? result)))
    (is (= (inc calls-before) @handler-calls) "the reducer really ran")
    (is (zero? @effect-calls) "declared effects never ran")
    (is (= [{:fx-id :review/side-effect :args :payload}]
           (:would-fire-effects result)))
    (is (= (inc (get-in before [:rf.db/app :n] 0))
           (get-in result [:db-state-after-simulation :n])))
    (is (= before (rf/frame-state-value frame-id)) "both partitions restored")
    (is (= :rf.epoch/db-replaced (:event-id (peek (rf/epoch-history frame-id))))
        "rollback is an observable synthetic epoch")))

(deftest first-event-can-be-simulated-without-a-retained-anchor
  (with-probe-frame
    (fn [frame-id handler-calls effect-calls]
      (is (empty? (rf/epoch-history frame-id)))
      (assert-simulation-restores frame-id handler-calls effect-calls))))

(deftest repeated-simulations-restore-actual-state-not-the-history-head
  (with-probe-frame
    (fn [frame-id handler-calls effect-calls]
      (rf/dispatch-sync [:review/seed] {:frame frame-id})
      (is (rf/replace-frame-state! frame-id {:rf.db/runtime {:review/checkpoint {:token 7}}}))
      (let [before (rf/frame-state-value frame-id)]
        (assert-simulation-restores frame-id handler-calls effect-calls)
        (assert-simulation-restores frame-id handler-calls effect-calls)
        (is (= before (rf/frame-state-value frame-id)))
        (is (= 2 @handler-calls))))))

(deftest one-slot-history-does-not-lose-the-rollback-state
  (let [depth (get-in (rf/current-config) [:epoch-history :depth])]
    (try
      (rf/configure! {:epoch-history {:depth 1}})
      (with-probe-frame
        (fn [frame-id handler-calls effect-calls]
          (rf/dispatch-sync [:review/seed] {:frame frame-id})
          (assert-simulation-restores frame-id handler-calls effect-calls)
          (is (= 1 (count (rf/epoch-history frame-id))))))
      (finally (rf/configure! {:epoch-history {:depth depth}})))))

(deftest disabled-history-refuses-before-running-the-handler
  (let [depth (get-in (rf/current-config) [:epoch-history :depth])]
    (try
      (rf/configure! {:epoch-history {:depth 0}})
      (with-probe-frame
        (fn [frame-id handler-calls effect-calls]
          (let [before (rf/frame-state-value frame-id)
                result (rt/dispatch-dry-run [:review/probe] {:frame frame-id})]
            (is (false? (:ok? result)))
            (is (= :no-epoch-recorded (:reason result)))
            (is (zero? @handler-calls))
            (is (zero? @effect-calls))
            (is (= before (rf/frame-state-value frame-id)))
            (is (empty? (rf/epoch-history frame-id))))))
      (finally (rf/configure! {:epoch-history {:depth depth}})))))
