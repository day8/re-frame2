(ns day8.re-frame2-causa.panels.app-db-diff-events
  "Events and effects for the App-DB Diff panel."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.defaults :as defaults]
            [day8.re-frame2-causa.panels.app-db-diff-helpers :as h]))

(defn install!
  "Install the App-DB Diff events and effects."
  []
  (rf/reg-event-db :rf.causa/pin-slice
    (fn [db [_ path]]
      (let [target (get db :target-frame defaults/default-target-frame)]
        (update db :pinned-slices-store
                h/pin-path target path))))

  (rf/reg-event-db :rf.causa/unpin-slice
    (fn [db [_ path]]
      (let [target (get db :target-frame defaults/default-target-frame)]
        (update db :pinned-slices-store
                h/unpin-path target path))))

  (rf/reg-event-db :rf.causa/reorder-pinned-slices
    (fn [db [_ new-order]]
      (let [target (get db :target-frame defaults/default-target-frame)]
        (update db :pinned-slices-store
                h/reorder-paths target new-order))))

  (rf/reg-event-db :rf.causa/focus-slice-path
    (fn [db [_ path]]
      (assoc db :focused-slice-path path)))

  (rf/reg-event-db :rf.causa/clear-slice-focus
    (fn [db _event]
      (dissoc db :focused-slice-path)))

  (rf/reg-fx :rf.causa.fx/copy-to-clipboard
    (fn [_ctx {:keys [text]}]
      (try
        (when (and (exists? js/navigator)
                   (.-clipboard js/navigator))
          (.writeText (.-clipboard js/navigator) (str text)))
        (catch :default _ nil))))

  (rf/reg-event-fx :rf.causa/copy-value-to-clipboard
    (fn [_ctx [_ value]]
      {:fx [[:rf.causa.fx/copy-to-clipboard {:text (pr-str value)}]]}))

  (rf/reg-event-fx :rf.causa/copy-path-to-clipboard
    (fn [_ctx [_ path]]
      {:fx [[:rf.causa.fx/copy-to-clipboard {:text (pr-str path)}]]})))
