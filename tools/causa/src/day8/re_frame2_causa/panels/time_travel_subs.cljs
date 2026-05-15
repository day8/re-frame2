(ns day8.re-frame2-causa.panels.time-travel-subs
  "Subscriptions for the Time Travel scrubber panel."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.defaults :as defaults]
            [day8.re-frame2-causa.panels.time-travel-events :as events]
            [day8.re-frame2-causa.panels.time-travel-helpers :as h]))

(defn install!
  "Install Time Travel scrubber subscriptions."
  []
  (rf/reg-sub :rf.causa/target-frame
    (fn [db _query]
      (get db :target-frame defaults/default-target-frame)))

  (rf/reg-sub :rf.causa/epoch-history
    (fn [db _query]
      (get db :epoch-history [])))

  (rf/reg-sub :rf.causa/selected-epoch-id
    (fn [db _query]
      (get db :selected-epoch-id)))

  (rf/reg-sub :rf.causa/last-restore-failure
    :<- [:rf.causa/restore-epoch-tick]
    (fn [_tick _query]
      (let [{:keys [ok? frame-id epoch-id]} @events/restore-epoch-last-result]
        (when (and (some? ok?) (not ok?))
          {:frame-id frame-id :epoch-id epoch-id}))))

  (rf/reg-sub :rf.causa/restore-epoch-tick
    (fn [db _query]
      (get db :restore-epoch-tick 0)))

  (rf/reg-sub :rf.causa/pin-store
    (fn [db _query]
      (get db :pin-store {})))

  (rf/reg-sub :rf.causa/pinned-snapshots
    :<- [:rf.causa/pin-store]
    :<- [:rf.causa/target-frame]
    (fn [[pin-store target-frame] _query]
      (h/epoch-pins-for-frame pin-store target-frame)))

  (rf/reg-sub :rf.causa/time-travel-label-input
    (fn [db _query]
      (get db :label-input "")))

  (rf/reg-sub :rf.causa/time-travel
    :<- [:rf.causa/target-frame]
    :<- [:rf.causa/epoch-history]
    :<- [:rf.causa/selected-epoch-id]
    :<- [:rf.causa/pinned-snapshots]
    :<- [:rf.causa/time-travel-label-input]
    (fn [[target-frame history selected-id pins label-input] _query]
      (let [selected-record (when selected-id
                              (h/find-epoch-in-history history selected-id))]
        {:target-frame      target-frame
         :history           history
         :selected-epoch-id selected-id
         :selected-record   selected-record
         :selected-index    (h/epoch-index-in-history history selected-id)
         :pins              pins
         :label-input       label-input
         :chip-states       (h/chip-states history pins)
         :cap-reached?      (>= (count pins) h/default-pin-cap)})))

  nil)
