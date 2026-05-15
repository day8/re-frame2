(ns day8.re-frame2-causa.panels.subscriptions-subs
  "Subscriptions/read-model registrations for the Subscriptions panel."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.defaults :as defaults]
            [day8.re-frame2-causa.panels.subscriptions-helpers :as h]))

(defn install!
  []
  (rf/reg-sub :rf.causa/sub-cache
    (fn [db _query]
      (let [target (get db :target-frame defaults/default-target-frame)
            ov     (get db :sub-cache-override)]
        (or ov (rf/sub-cache target)))))

  (rf/reg-sub :rf.causa/sub-error-cache
    (fn [db _query]
      (get db :sub-error-cache {})))

  (rf/reg-sub :rf.causa/selected-sub
    (fn [db _query]
      (get db :selected-sub)))

  (rf/reg-sub :rf.causa/sub-filters
    (fn [db _query]
      (get db :sub-filters #{})))

  (rf/reg-sub :rf.causa/sub-chain-open?
    (fn [db _query]
      (boolean (get db :sub-chain-open?))))

  (rf/reg-sub :rf.causa/subscriptions-data
    :<- [:rf.causa/sub-cache]
    :<- [:rf.causa/epoch-history]
    :<- [:rf.causa/sub-error-cache]
    :<- [:rf.causa/selected-sub]
    :<- [:rf.causa/sub-filters]
    :<- [:rf.causa/sub-chain-open?]
    (fn [[sub-cache history error-cache selected-q-v filters chain-open?]
         _query]
      (let [latest-epoch   (peek history)
            sub-runs       (:sub-runs latest-epoch)
            changed-paths  (:changed-paths latest-epoch)
            rows           (h/project-rows sub-cache sub-runs error-cache)
            active-filters (or filters #{})
            filtered-rows  (h/filter-by-status rows active-filters)
            counts         (h/status-counts rows)
            chain          (when (and chain-open? selected-q-v)
                             (h/compute-chain
                               selected-q-v sub-cache sub-runs
                               error-cache changed-paths))]
        {:rows             rows
         :filtered-rows    filtered-rows
         :status-counts    counts
         :total            (count rows)
         :selected-query-v selected-q-v
         :active-filters   active-filters
         :chain-open?      (boolean chain-open?)
         :chain            chain}))))
