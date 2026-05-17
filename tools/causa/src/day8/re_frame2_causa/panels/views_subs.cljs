(ns day8.re-frame2-causa.panels.views-subs
  "Subscriptions for the Views panel (rf2-21ob3).

  Per `tools/causa/spec/012-Views.md` §Data sources the panel derives
  every value from data the framework already records:
    - `:rf.causa/focus` (spec/018 §6 Spine binding) — the selected
      cascade + frame; the panel is isolation-scoped to that frame
      ONLY (spec §I3).
    - `:rf.causa/epoch-history` — the per-frame epoch ring buffer
      (per `tools/causa/spec/018-Event-Spine.md` §5.2 / `spec/Spec-
      Schemas.md` §`:rf/epoch-record`). The composite reads the
      focused cascade's `:renders` + `:sub-runs`, plus the
      immediately-prior cascade's `:renders` for the cross-cascade
      mount/unmount derivation (per `views_helpers.cljc` §Mount/
      unmount derivation under v1 instrumentation).

  No new instrumentation; this is a pure consumer."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.views-helpers :as h]))

(defn- find-cascade-index
  "Walk `epoch-history` (a vector of epoch records) and return the
  index of the record whose `:event-id` / cascade discriminator
  matches `dispatch-id`. The spine carries `:dispatch-id`; the epoch
  record carries `:event-id` per Spec-Schemas — Tool-Pair's time-
  travel slot is keyed on event-id, but per spec/018 §6 the
  user-facing 'cascade id' the spine pins is the cascade's root
  dispatch-id. The renderer needs both signals to align — we look
  for either match (`:event-id` first, then `:trigger-event` head)
  so the projection works against both the live cascade list and
  legacy epoch records that key on event-id alone."
  [epoch-history dispatch-id]
  (when (and dispatch-id (seq epoch-history))
    (some (fn [[idx record]]
            (when (or (= dispatch-id (:event-id record))
                      (= dispatch-id (:dispatch-id record))
                      (= dispatch-id (first (:trigger-event record))))
              idx))
          (map-indexed vector epoch-history))))

(defn install!
  []
  ;; -- panel-local UI state slots --------------------------------------

  (rf/reg-sub :rf.causa/views-heatmap?
    (fn [db _query]
      (boolean (get db :views/heatmap?))))

  (rf/reg-sub :rf.causa/views-group-by
    (fn [db _query]
      (get db :views/group-by :component)))

  (rf/reg-sub :rf.causa/views-component-filter
    (fn [db _query]
      (get db :views/component-filter)))

  (rf/reg-sub :rf.causa/views-cluster-threshold
    (fn [db _query]
      (get db :views/cluster-threshold h/default-cluster-threshold)))

  (rf/reg-sub :rf.causa/views-expanded-rows
    (fn [db _query]
      (or (get db :views/expanded-rows) #{})))

  (rf/reg-sub :rf.causa/views-expanded-clusters
    (fn [db _query]
      (or (get db :views/expanded-clusters) #{})))

  ;; -- focused cascade pair (current + prior) --------------------------

  (rf/reg-sub :rf.causa/views-focused-cascade-pair
    :<- [:rf.causa/focus]
    :<- [:rf.causa/epoch-history]
    (fn [[focus history] _query]
      (let [history (vec history)
            idx     (or (find-cascade-index history (:dispatch-id focus))
                        ;; LIVE / no explicit focus → fall back to head.
                        (when (seq history) (dec (count history))))
            current (when idx (nth history idx nil))
            prior   (when (and idx (pos? idx)) (nth history (dec idx) nil))]
        {:focus   focus
         :current current
         :prior   prior
         :index   idx
         :total   (count history)})))

  ;; -- the composite views-data sub the view consumes ------------------

  (rf/reg-sub :rf.causa/views-data
    :<- [:rf.causa/views-focused-cascade-pair]
    :<- [:rf.causa/views-heatmap?]
    :<- [:rf.causa/views-group-by]
    :<- [:rf.causa/views-component-filter]
    :<- [:rf.causa/views-cluster-threshold]
    :<- [:rf.causa/views-expanded-rows]
    :<- [:rf.causa/views-expanded-clusters]
    (fn [[pair heatmap? group-by component-filter cluster-threshold
          expanded-rows expanded-clusters]
         _query]
      (let [current        (:current pair)
            prior          (:prior pair)
            focus          (:focus pair)
            current-renders (or (:renders current) [])
            prior-renders   (or (:renders prior) [])
            sub-runs        (or (:sub-runs current) [])
            projected       (h/build-views-data
                              current-renders
                              prior-renders
                              sub-runs
                              {:cluster-threshold    cluster-threshold
                               :heatmap?             heatmap?
                               :component-filter     component-filter})]
        (assoc projected
               :focus             focus
               :frame             (:frame focus)
               :dispatch-id       (:dispatch-id focus)
               :group-by          group-by
               :cluster-threshold cluster-threshold
               :expanded-rows     expanded-rows
               :expanded-clusters expanded-clusters
               :has-cascade?      (some? current)))))

  nil)
