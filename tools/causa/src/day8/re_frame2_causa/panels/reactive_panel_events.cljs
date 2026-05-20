(ns day8.re-frame2-causa.panels.reactive-panel-events
  "Events for the Reactive panel (rf2-wyvf2 · spec/021 §3).

  v1 surfaces a single event — the 'Show unchanged subs' disclosure
  toggle (§3.4). The settings-side override (`:rf.causa/show-
  unchanged-subs?` in `:general`) is the always-expand pin; the
  panel-local slot below is the per-cascade quick toggle."
  (:require [re-frame.core :as rf]))

(defn install!
  []
  (rf/reg-event-db :rf.causa/reactive-toggle-unchanged
    (fn [db _]
      (update db :reactive/show-unchanged? not)))

  (rf/reg-event-db :rf.causa/reactive-set-unchanged
    (fn [db [_ v?]]
      (assoc db :reactive/show-unchanged? (boolean v?))))
  nil)
