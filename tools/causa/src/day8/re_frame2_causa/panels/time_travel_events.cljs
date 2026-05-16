(ns day8.re-frame2-causa.panels.time-travel-events
  "Events and effects for the Time Travel scrubber panel."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.defaults :as defaults]
            [day8.re-frame2-causa.panels.time-travel-helpers :as h]))

(defonce restore-epoch-last-result
  ;; Captures the most-recent `:rf.causa.fx/restore-epoch` outcome.
  (atom nil))

(defn restore-epoch-fx-fn
  "Body of the `:rf.causa.fx/restore-epoch` reg-fx, exposed as a
  top-level fn so failure surfacing is unit-testable in isolation."
  [_ctx {:keys [frame-id epoch-id]}]
  (let [ok? (rf/restore-epoch frame-id epoch-id)]
    (reset! restore-epoch-last-result
            {:ok? ok? :frame-id frame-id :epoch-id epoch-id})
    (rf/dispatch [:rf.causa/bump-restore-epoch-tick] {:frame :rf/causa})
    (when-not ok?
      (rf/dispatch [:rf.causa/clear-selected-epoch] {:frame :rf/causa}))))

(defn install!
  "Install Time Travel scrubber events and effects."
  []
  (rf/reg-event-db :rf.causa/set-target-frame
    (fn [db [_ frame-id]]
      (let [target (or frame-id defaults/default-target-frame)]
        (cond-> (assoc db :epoch-history (vec (rf/epoch-history target)))
          (nil? frame-id) (dissoc :target-frame)
          (some? frame-id) (assoc :target-frame frame-id)))))

  (rf/reg-event-db :rf.causa/bump-restore-epoch-tick
    {:rf.trace/no-emit? true}
    (fn [db _]
      (update db :restore-epoch-tick (fnil inc 0))))

  (rf/reg-event-db :rf.causa/epoch-recorded
    {:rf.trace/no-emit? true}
    (fn [db [_ frame-id]]
      (let [target (get db :target-frame defaults/default-target-frame)]
        (if (= frame-id target)
          (assoc db :epoch-history (vec (rf/epoch-history target)))
          db))))

  (rf/reg-event-db :rf.causa/sync-epoch-history
    {:rf.trace/no-emit? true}
    (fn [db [_ history]]
      (assoc db :epoch-history (vec history))))

  (rf/reg-event-db :rf.causa/time-travel-set-label-input
    {:rf.trace/no-emit? true}
    (fn [db [_ text]]
      (assoc db :label-input (or text ""))))

  (rf/reg-event-db :rf.causa/select-epoch
    (fn [db [_ epoch-id]]
      (assoc db :selected-epoch-id epoch-id)))

  (rf/reg-event-db :rf.causa/clear-selected-epoch
    (fn [db _event]
      (dissoc db :selected-epoch-id)))

  ;; Per rf2-q4rvx the payload is a single map: `{:eid <epoch-id> :label
  ;; <string>}`. The map shape composes cleanly with optional keys we
  ;; may add later (e.g. `:source`, `:auto?`) without churning every
  ;; call site — the positional `[epoch-id label]` form was the v0
  ;; shape and is gone (pre-alpha; no back-compat shim).
  (rf/reg-event-db :rf.causa/pin-current
    (fn [db [_ {:keys [eid label]}]]
      (let [target  (get db :target-frame defaults/default-target-frame)
            history (vec (or (get db :epoch-history)
                             (rf/epoch-history target)))
            record  (h/find-epoch-in-history history eid)
            pin     (h/pin-from-epoch record label)]
        (if (some? pin)
          (let [{:keys [store overflow? dropped-pin]}
                (h/pin-snapshot (get db :pin-store {}) target pin)]
            (cond-> (assoc db :pin-store store)
              overflow? (assoc :pin-overflow-toast
                               {:dropped-label (:label dropped-pin)
                                :ts            (.getTime (js/Date.))})))
          db))))

  (rf/reg-event-db :rf.causa/unpin
    (fn [db [_ epoch-id]]
      (let [target (get db :target-frame defaults/default-target-frame)]
        (update db :pin-store h/unpin-snapshot target epoch-id))))

  (rf/reg-event-db :rf.causa/rename-pin
    (fn [db [_ epoch-id new-label]]
      (let [target (get db :target-frame defaults/default-target-frame)]
        (update db :pin-store h/rename-pin target epoch-id new-label))))

  (rf/reg-event-db :rf.causa/dismiss-pin-overflow-toast
    (fn [db _] (dissoc db :pin-overflow-toast)))

  (rf/reg-fx :rf.causa.fx/restore-epoch restore-epoch-fx-fn)

  (rf/reg-fx :rf.causa.fx/reset-frame-db!
    (fn [_ctx {:keys [frame-id frame-db]}]
      (rf/reset-frame-db! frame-id frame-db)))

  (rf/reg-event-fx :rf.causa/reset-to-epoch
    (fn [{:keys [db]} [_ epoch-id]]
      (let [target (get db :target-frame defaults/default-target-frame)]
        {:fx [[:rf.causa.fx/restore-epoch
               {:frame-id target :epoch-id epoch-id}]]})))

  (rf/reg-event-fx :rf.causa/reset-to-pinned
    (fn [{:keys [db]} [_ epoch-id]]
      (let [target (get db :target-frame defaults/default-target-frame)
            pin    (h/find-pin (get db :pin-store {}) target epoch-id)]
        (when pin
          {:fx [[:rf.causa.fx/reset-frame-db!
                 {:frame-id target :frame-db (:frame-db pin)}]]}))))

  nil)
