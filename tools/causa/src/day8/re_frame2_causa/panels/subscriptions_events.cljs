(ns day8.re-frame2-causa.panels.subscriptions-events
  "Event registrations for the Subscriptions panel."
  (:require [re-frame.core :as rf]))

(defn install!
  []
  (rf/reg-event-db :rf.causa/set-sub-cache-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :sub-cache-override)
        (assoc db :sub-cache-override ov))))

  (rf/reg-event-db :rf.causa/select-sub
    (fn [db [_ query-v]]
      (assoc db :selected-sub query-v)))

  (rf/reg-event-db :rf.causa/clear-selected-sub
    (fn [db _event]
      (-> db
          (dissoc :selected-sub)
          (dissoc :sub-chain-open?))))

  (rf/reg-event-db :rf.causa/toggle-sub-filter
    (fn [db [_ status]]
      (let [current (get db :sub-filters #{})]
        (assoc db :sub-filters
               (if (contains? current status)
                 (disj current status)
                 (conj current status))))))

  (rf/reg-event-db :rf.causa/show-invalidation-chain
    (fn [db [_ query-v]]
      (cond-> (assoc db :sub-chain-open? true)
        query-v (assoc :selected-sub query-v))))

  (rf/reg-event-db :rf.causa/hide-invalidation-chain
    (fn [db _event]
      (dissoc db :sub-chain-open?))))
