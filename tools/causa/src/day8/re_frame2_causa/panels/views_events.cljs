(ns day8.re-frame2-causa.panels.views-events
  "Event registrations for the Views panel (rf2-21ob3).

  Per `tools/causa/spec/012-Views.md`. Causa-internal panel state
  (expanded-rows set, cluster-expansion set, group-by toggle,
  component-filter) lives on `:rf/causa`'s app-db under the
  `:views/*` keys."
  (:require [re-frame.core :as rf]))

(defn install!
  []
  ;; -- per-row inline expansion (spec §Per-component drilldown) ---------
  ;;
  ;; Each row toggle keys on `:render-key` (or the cluster identity
  ;; tuple for cluster expansion). The set semantics let the user
  ;; keep multiple rows expanded for comparison.

  (rf/reg-event-db :rf.causa/views-toggle-row
    (fn [db [_ row-key]]
      (let [s (or (get db :views/expanded-rows) #{})]
        (assoc db :views/expanded-rows
               (if (contains? s row-key)
                 (disj s row-key)
                 (conj s row-key))))))

  (rf/reg-event-db :rf.causa/views-collapse-all-rows
    (fn [db _event]
      (dissoc db :views/expanded-rows)))

  ;; -- cluster expansion (spec §Grid-explosion clustering) -------------

  (rf/reg-event-db :rf.causa/views-toggle-cluster
    (fn [db [_ cluster-key]]
      (let [s (or (get db :views/expanded-clusters) #{})]
        (assoc db :views/expanded-clusters
               (if (contains? s cluster-key)
                 (disj s cluster-key)
                 (conj s cluster-key))))))

  ;; -- component filter ------------------------------------------------

  (rf/reg-event-db :rf.causa/views-set-component-filter
    (fn [db [_ view-id]]
      (if (nil? view-id)
        (dissoc db :views/component-filter)
        (assoc db :views/component-filter view-id))))

  ;; -- group-by toggle (spec §Group-by toggle) -------------------------

  (rf/reg-event-db :rf.causa/views-set-group-by
    (fn [db [_ group-by]]
      (assoc db :views/group-by (or group-by :component))))

  ;; -- cluster threshold (spec §Grid-explosion clustering) -------------
  ;;
  ;; Surfaces in Settings → Buffer → :views/cluster-threshold per spec;
  ;; the event lets a test or the settings popup write the slot.

  (rf/reg-event-db :rf.causa/views-set-cluster-threshold
    (fn [db [_ n]]
      (if (and (number? n) (pos? n))
        (assoc db :views/cluster-threshold (int n))
        (dissoc db :views/cluster-threshold))))

  nil)
