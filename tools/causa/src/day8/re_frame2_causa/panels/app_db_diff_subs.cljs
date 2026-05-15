(ns day8.re-frame2-causa.panels.app-db-diff-subs
  "Subscriptions and read-models for the App-DB Diff panel."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.app-db-diff-helpers :as h]
            [day8.re-frame2-causa.panels.time-travel-helpers
             :as tt-helpers]))

(defonce diff-cache
  ;; Per-`:epoch-id` cache for the diff triples computed by the
  ;; `:rf.causa/selected-epoch-diff` sub. Tests reset this atom
  ;; between cases; production callers should only write via the sub.
  (atom {}))

(defn install!
  "Install the App-DB Diff subscriptions."
  []
  (rf/reg-sub :rf.causa/target-frame-db
    :<- [:rf.causa/target-frame]
    :<- [:rf.causa/epoch-history]
    (fn [[target _epoch-history] _query]
      (rf/get-frame-db target)))

  (rf/reg-sub :rf.causa/selected-epoch-record
    :<- [:rf.causa/epoch-history]
    :<- [:rf.causa/selected-epoch-id]
    (fn [[history selected-id] _query]
      (when selected-id
        (tt-helpers/find-epoch-in-history history selected-id))))

  (rf/reg-sub :rf.causa/selected-epoch-diff
    :<- [:rf.causa/epoch-history]
    :<- [:rf.causa/selected-epoch-id]
    (fn [[history selected-id] _query]
      (let [record (if selected-id
                     (tt-helpers/find-epoch-in-history history selected-id)
                     (peek history))]
        (when record
          (let [epoch-id (:epoch-id record)
                cached   (get @diff-cache epoch-id ::miss)]
            (if (not= ::miss cached)
              cached
              (let [diff (h/diff-paths (:db-before record)
                                       (:db-after  record))
                    live (into #{} (map :epoch-id) history)]
                (swap! diff-cache
                       (fn [m]
                         (-> m
                             (select-keys live)
                             (assoc epoch-id diff))))
                diff)))))))

  (rf/reg-sub :rf.causa/pinned-slices-store
    (fn [db _query]
      (get db :pinned-slices-store {})))

  (rf/reg-sub :rf.causa/pinned-slices
    :<- [:rf.causa/pinned-slices-store]
    :<- [:rf.causa/target-frame]
    :<- [:rf.causa/target-frame-db]
    (fn [[store target db] _query]
      (h/live-pinned-slices store target db)))

  (rf/reg-sub :rf.causa/focused-slice-path
    (fn [db _query]
      (get db :focused-slice-path)))

  (rf/reg-sub :rf.causa/show-me-when-this-changed-result
    :<- [:rf.causa/focused-slice-path]
    :<- [:rf.causa/epoch-history]
    (fn [[focused-path history] _query]
      (if focused-path
        (h/epochs-touching-path history focused-path)
        [])))

  (rf/reg-sub :rf.causa/app-db-diff
    :<- [:rf.causa/target-frame]
    :<- [:rf.causa/target-frame-db]
    :<- [:rf.causa/selected-epoch-diff]
    :<- [:rf.causa/pinned-slices]
    :<- [:rf.causa/focused-slice-path]
    :<- [:rf.causa/show-me-when-this-changed-result]
    :<- [:rf.causa/epoch-history]
    (fn [[target db diff-triples pinned focused-path focused-hits history]
         _query]
      (let [{:keys [non-reserved]} (h/partition-reserved
                                     (or diff-triples []))]
        {:target-frame          target
         :history-empty?        (empty? history)
         :changed-non-reserved  non-reserved
         :changed-reserved      (h/reserved-summary db)
         :pinned-slices         pinned
         :focused-path          focused-path
         :focused-hits          focused-hits}))))
