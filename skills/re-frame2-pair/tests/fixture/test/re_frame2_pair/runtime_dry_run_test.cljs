(ns re-frame2-pair.runtime-dry-run-test
  (:require [cljs.test :refer [deftest is]]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.late-bind :as rf.late-bind]
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
    (is (= [true true (inc calls-before) 0
            [{:fx-id :review/side-effect :args :payload}]
            (inc (get-in before [:rf.db/app :n] 0)) before :rf.epoch/db-replaced]
           [(:ok? result) (:rolled-back? result) @handler-calls @effect-calls
            (:would-fire-effects result) (get-in result [:db-state-after-simulation :n])
            (rf/frame-state-value frame-id) (:event-id (peek (rf.epoch/epoch-history frame-id)))]))))

(deftest first-event-can-be-simulated-without-a-retained-anchor
  (with-probe-frame
    (fn [frame-id handler-calls effect-calls]
      (is (empty? (rf.epoch/epoch-history frame-id)))
      (assert-simulation-restores frame-id handler-calls effect-calls))))

(deftest repeated-simulations-restore-actual-state-not-the-history-head
  (with-probe-frame
    (fn [frame-id handler-calls effect-calls]
      (rf/dispatch-sync [:review/seed] {:frame frame-id})
      (is (:ok? (rf.epoch/replace-frame-state! frame-id {:rf.db/runtime {:review/checkpoint {:token 7}}})))
      (assert-simulation-restores frame-id handler-calls effect-calls)
      (assert-simulation-restores frame-id handler-calls effect-calls))))

(deftest one-slot-history-does-not-lose-the-rollback-state
  (let [depth (get-in (rf/current-config) [:epoch-history :depth])]
    (try
      (rf/configure! {:epoch-history {:depth 1}})
      (with-probe-frame
        (fn [frame-id handler-calls effect-calls]
          (rf/dispatch-sync [:review/seed] {:frame frame-id})
          (assert-simulation-restores frame-id handler-calls effect-calls)
          (is (= 1 (count (rf.epoch/epoch-history frame-id))))))
      (finally (rf/configure! {:epoch-history {:depth depth}})))))

(deftest a-refused-rollback-is-a-failure-naming-the-refusal
  ;; A refusal envelope is a truthy map, so the rollback must be read by its
  ;; :ok?, never by truthiness.
  (with-probe-frame
    (fn [frame-id _ _]
      (rf/dispatch-sync [:review/seed] {:frame frame-id})
      (let [replace! (rf.late-bind/get-fn :epoch/replace-frame-state!)]
        (try
          (rf.late-bind/set-fn! :epoch/replace-frame-state!
                                (fn [f _]
                                  {:ok? false :reason :rf.epoch/replace-schema-mismatch
                                   :frame f :failing-paths [[:n]]}))
          (let [result (rt/dispatch-dry-run [:review/probe] {:frame frame-id})]
            (is (= [false :rollback-failed false :rf.epoch/replace-schema-mismatch]
                   ((juxt :ok? :reason :rolled-back? :rollback-reason) result))))
          (finally (rf.late-bind/set-fn! :epoch/replace-frame-state! replace!)))))))

(deftest disabled-history-refuses-before-running-the-handler
  (let [depth (get-in (rf/current-config) [:epoch-history :depth])]
    (try
      (rf/configure! {:epoch-history {:depth 0}})
      (with-probe-frame
        (fn [frame-id handler-calls effect-calls]
          (let [before (rf/frame-state-value frame-id)
                result (rt/dispatch-dry-run [:review/probe] {:frame frame-id})]
            (is (= [false :no-epoch-recorded 0 0 before []]
                   [(:ok? result) (:reason result) @handler-calls @effect-calls
                    (rf/frame-state-value frame-id) (rf.epoch/epoch-history frame-id)])))))
      (finally (rf/configure! {:epoch-history {:depth depth}})))))
