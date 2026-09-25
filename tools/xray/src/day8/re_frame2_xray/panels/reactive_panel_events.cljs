(ns day8.re-frame2-xray.panels.reactive-panel-events
  "Events for the Reactive panel (spec/021 §3).

  Both events drive one control — the 'Show unchanged subs' disclosure
  toggle (§3.4): one flips it, one sets it. The settings-side override (`:rf.xray/show-
  unchanged-subs?` in `:general`) is the always-expand pin; the
  panel-local slot below is the per-event-bundle quick toggle."
  (:require [re-frame.core :as rf]))

(defn install!
  []
  (rf/reg-event :rf.xray/reactive-toggle-unchanged
    (fn [{:keys [db]} _]
      {:db (update db :reactive/show-unchanged? not)}))

  (rf/reg-event :rf.xray/reactive-set-unchanged
    (fn [{:keys [db]} [_ v?]]
      {:db (assoc db :reactive/show-unchanged? (boolean v?))}))
  nil)
