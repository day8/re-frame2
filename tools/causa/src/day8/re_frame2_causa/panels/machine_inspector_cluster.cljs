(ns day8.re-frame2-causa.panels.machine-inspector-cluster
  "Machine Inspector UC2 Mode C view + sub/event family
  (rf2-juon8, Phase 3, parent rf2-5aw5v).

  Per `tools/causa/spec/003-Machine-Inspector.md` §UC2 Mode C +
  `ai/findings/causa-machines-design-2026-05-17.md` §3.3 the cluster
  view replaces Mode B's per-instance tab strip when the population
  outgrows ~10 instances. The Mode-C surface is three stacked
  sections:

    1. **Cluster-by selector** — `:state | :context-key |
       :parent-machine`. The selection lives in Causa's app-db so the
       user's pick survives panel re-renders.

    2. **Cluster rows** — one row per group, sorted deterministically.
       Each row shows the cluster label, an instance count badge, and
       an inline sparkline glyph of recent state-change rate. Click
       toggles inline expansion; expansion lists individual instances
       within the cluster.

    3. **Compare-table** — rendered when ≥2 instances are selected via
       Shift+click. Cell-by-cell diff'd context + current-state.
       Divergent cells get an amber tint to spotlight the difference.

  ## What this ns owns

    - The `ClusterView` hiccup view + the cluster-by selector +
      cluster-row + compare-table sub-views.
    - The `:rf.causa/machine-mode-c-*` events and subs:
        `set-mode-c-cluster-by` (event)
        `toggle-mode-c-cluster-expanded` (event)
        `toggle-mode-c-selection` (event)
        `clear-mode-c-selection` (event)
        `set-mode-c-context-key` (event)
        `set-machine-instances-override-for-test` (event)

        `mode-c-cluster-by` (sub)
        `mode-c-context-key` (sub)
        `mode-c-expanded` (sub)
        `mode-c-selection` (sub)
        `machine-instances` (sub) — synthesises an instance vector
                                     for the focused machine from the
                                     snapshots map; tests can override
                                     via the test-only hook above.
        `mode-c-clusters` (sub)   — the composed cluster vector with
                                     sparklines attached.
        `mode-c-compare-table` (sub)

  ## Pure hiccup (rf2-tijr)

  Every subscribe / dispatch resolves against `:rf/causa` via the
  enclosing `[rf/frame-provider {:frame :rf/causa}]` in
  `shell.cljs`. No Reagent / UIx / Helix references.

  ## Helper algebra

  Pure-data logic lives in `machine_inspector_cluster_helpers.cljc`."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [day8.re-frame2-causa.chart.svg :as chart-svg]
            [day8.re-frame2-causa.panels.machine-inspector-cluster-helpers
             :as ch]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- now-ms reader (overridable for tests) -----------------------------

(defn- now-ms
  "Wall-clock ms. Tests can rebind via `with-redefs`. CLJS-only — the
  helper composite passes the result into the pure sparkline maths."
  []
  (.now js/Date))

;; ---- subscription installers -------------------------------------------

(defn install-subs!
  "Register the Mode C sub family. Idempotent — the enclosing
  `machine-inspector/install!` is itself guarded."
  []
  ;; The user's cluster-by selector (per-panel, defaults to :state).
  (rf/reg-sub :rf.causa/mode-c-cluster-by
    (fn [db _query]
      (get db :mode-c/cluster-by :state)))

  ;; When :cluster-by is :context-key, this slot carries the sub-key
  ;; (the key into the instance's :context map to group on).
  (rf/reg-sub :rf.causa/mode-c-context-key
    (fn [db _query]
      (get db :mode-c/context-key)))

  ;; The set of cluster keys whose inline-expansion is currently open.
  (rf/reg-sub :rf.causa/mode-c-expanded
    (fn [db _query]
      (get db :mode-c/expanded (ch/empty-expanded))))

  ;; The set of instance-ids selected via Shift+click for the
  ;; compare-table.
  (rf/reg-sub :rf.causa/mode-c-selection
    (fn [db _query]
      (get db :mode-c/selection (ch/empty-selection))))

  ;; Test-only override slot for the instance vector. Production reads
  ;; through `:rf.causa/machine-snapshots` (Phase 1 single-snapshot
  ;; path) → `ch/snapshots->instances` for the focused machine; tests
  ;; can drive a multi-instance projection directly so Mode C
  ;; behaviour doesn't wait on the upstream spawn surface.
  (rf/reg-sub :rf.causa/machine-instances-override
    (fn [db _query]
      (get db :machine-instances-override)))

  ;; The instance vector for the currently-selected machine. Production
  ;; path: widen the Phase 1 snapshot via `ch/snapshots->instances`.
  ;; Override path: dispatch the test event below.
  ;;
  ;; The effective `selected-id` comes from the composite (it falls back
  ;; to the first registered row when nothing is explicitly picked,
  ;; matching the picker's default-focus behaviour). Reading the raw
  ;; `:rf.causa/selected-machine-id` sub would return nil for the
  ;; first-mount case and Mode B / Mode C would never see the live
  ;; snapshot's instance.
  (rf/reg-sub :rf.causa/machine-instances
    :<- [:rf.causa/machine-inspector-data]
    :<- [:rf.causa/machine-snapshots]
    :<- [:rf.causa/machine-snapshots-override]
    :<- [:rf.causa/machine-instances-override]
    (fn [[mi-data live-snapshots snapshots-override instances-override]
         _query]
      (or instances-override
          (when-let [selected-id (:selected-id mi-data)]
            (let [snapshots (or snapshots-override live-snapshots {})]
              (ch/snapshots->instances selected-id snapshots))))))

  ;; The composed cluster vector with sparklines attached. The composite
  ;; pulls every input in one read so the view doesn't have to coordinate.
  (rf/reg-sub :rf.causa/mode-c-clusters
    :<- [:rf.causa/machine-instances]
    :<- [:rf.causa/mode-c-cluster-by]
    :<- [:rf.causa/mode-c-context-key]
    :<- [:rf.causa/trace-buffer]
    :<- [:rf.causa/selected-machine-id]
    (fn [[instances cluster-by context-key trace-buffer machine-id] _query]
      (let [clusters (ch/cluster-instances instances
                                           {:cluster-by  cluster-by
                                            :context-key context-key})]
        (ch/attach-sparkline-samples
          clusters
          {:trace-buffer trace-buffer
           :machine-id   machine-id
           :cluster-by   cluster-by
           :context-key  context-key
           :now-ms       (now-ms)}))))

  ;; The compare-table projection. Nil when fewer than 2 instances are
  ;; selected (the view hides the table entirely in that case).
  (rf/reg-sub :rf.causa/mode-c-compare-table
    :<- [:rf.causa/machine-instances]
    :<- [:rf.causa/mode-c-selection]
    (fn [[instances selection] _query]
      (ch/compare-table instances selection))))

;; ---- event installers --------------------------------------------------

(defn install-events!
  "Register the Mode C event family. Idempotent."
  []
  (rf/reg-event-db :rf.causa/set-mode-c-cluster-by
    (fn [db [_ cluster-by]]
      (if (ch/cluster-by-valid? cluster-by)
        (assoc db :mode-c/cluster-by cluster-by)
        db)))

  (rf/reg-event-db :rf.causa/set-mode-c-context-key
    (fn [db [_ context-key]]
      (if (nil? context-key)
        (dissoc db :mode-c/context-key)
        (assoc db :mode-c/context-key context-key))))

  (rf/reg-event-db :rf.causa/toggle-mode-c-cluster-expanded
    (fn [db [_ cluster-key]]
      (update db :mode-c/expanded ch/expanded-toggle cluster-key)))

  (rf/reg-event-db :rf.causa/toggle-mode-c-selection
    (fn [db [_ instance-id]]
      (update db :mode-c/selection ch/selection-toggle instance-id)))

  (rf/reg-event-db :rf.causa/clear-mode-c-selection
    (fn [db _event]
      (update db :mode-c/selection ch/selection-clear)))

  ;; Test-only override — production code paths never dispatch this.
  ;; Mirrors the pattern used by every other Causa test surface.
  (rf/reg-event-db :rf.causa/set-machine-instances-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :machine-instances-override)
        (assoc db :machine-instances-override ov)))))

;; ---- view helpers -------------------------------------------------------

(defn- cluster-by-selector
  "Dropdown that lets the user pick the cluster-by selector. Per design
  §3.3 the default is `:state`."
  [cluster-by context-key]
  [:div {:data-testid "rf-causa-mode-c-cluster-by-selector"
         :style       {:display "flex"
                       :align-items "center"
                       :gap "8px"
                       :padding "8px 12px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :background (:bg-3 tokens)}}
   [:label {:style {:color (:text-tertiary tokens)
                    :font-family sans-stack
                    :font-size "10px"
                    :text-transform "uppercase"
                    :letter-spacing "0.5px"}}
    "Cluster by"]
   [:select {:data-testid "rf-causa-mode-c-cluster-by-select"
             :value       (name (or cluster-by :state))
             :on-change   (fn [e]
                            (let [v (-> e .-target .-value)]
                              (rf/dispatch
                                [:rf.causa/set-mode-c-cluster-by (keyword v)]
                                {:frame :rf/causa})))
             :style       {:background (:bg-2 tokens)
                           :border (str "1px solid " (:border-default tokens))
                           :color (:text-primary tokens)
                           :padding "3px 6px"
                           :border-radius "3px"
                           :font-family mono-stack
                           :font-size "11px"
                           :cursor "pointer"}}
    (for [opt ch/cluster-by-options]
      ^{:key (name opt)}
      [:option {:value (name opt)} (name opt)])]
   (when (= :context-key cluster-by)
     [:input
      {:data-testid "rf-causa-mode-c-context-key-input"
       :type        "text"
       :placeholder ":userId"
       :value       (if context-key (str context-key) "")
       :on-change   (fn [e]
                      (let [v (-> e .-target .-value)]
                        (rf/dispatch
                          [:rf.causa/set-mode-c-context-key
                           (when-not (= "" v) (keyword (str/replace v ":" "")))]
                          {:frame :rf/causa})))
       :style       {:background (:bg-2 tokens)
                     :border (str "1px solid " (:border-default tokens))
                     :color (:text-primary tokens)
                     :padding "3px 6px"
                     :border-radius "3px"
                     :font-family mono-stack
                     :font-size "11px"
                     :width "120px"}}])])

(defn- instance-row
  "One instance entry inside an expanded cluster. Shift+click toggles
  the selection-set; plain click does nothing (the cluster expansion
  is the affordance for drill-down)."
  [{:keys [instance-id state context]} selected?]
  [:li {:data-testid (str "rf-causa-mode-c-instance-" (ch/format-instance-id instance-id))
        :data-selected (str selected?)
        :on-click (fn [e]
                    ;; React synthetic event — `.-shiftKey` is the
                    ;; cross-browser shift-key bit. Plain clicks
                    ;; intentionally do nothing so the user has to
                    ;; opt-in to multi-select via shift.
                    (when (.-shiftKey e)
                      (.preventDefault e)
                      (rf/dispatch [:rf.causa/toggle-mode-c-selection instance-id]
                                   {:frame :rf/causa})))
        :style {:display "flex"
                :align-items "center"
                :gap "8px"
                :padding "4px 8px 4px 22px"
                :margin "2px 0"
                :background (if selected?
                              "rgba(168, 124, 230, 0.12)"
                              "transparent")
                :border (str "1px solid "
                             (if selected?
                               (:accent-violet tokens)
                               (:border-subtle tokens)))
                :border-radius "3px"
                :cursor "pointer"
                :font-family mono-stack
                :font-size "11px"}}
   [:span {:style {:color (if selected?
                            (:accent-violet tokens)
                            (:text-tertiary tokens))
                   :min-width "12px"}}
    (if selected? "✓" "○")]
   [:span {:style {:color (:text-primary tokens)
                   :min-width "120px"}}
    (ch/format-instance-id instance-id)]
   [:span {:style {:color (:text-secondary tokens) :min-width "100px"}}
    (str state)]
   [:span {:style {:color (:text-tertiary tokens)
                   :font-size "10px"
                   :overflow "hidden"
                   :text-overflow "ellipsis"
                   :white-space "nowrap"
                   :flex 1}}
    (pr-str context)]])

(defn- cluster-row
  "One cluster row in the Mode-C surface."
  [{:keys [cluster-key cluster-label count instances rate-samples]} expanded? selection]
  [:li {:data-testid (str "rf-causa-mode-c-cluster-"
                          (ch/format-instance-id cluster-key))
        :data-cluster-key (pr-str cluster-key)
        :data-expanded (str expanded?)
        :style {:list-style "none"
                :margin "4px 0"}}
   [:div {:on-click (fn [_]
                      (rf/dispatch
                        [:rf.causa/toggle-mode-c-cluster-expanded cluster-key]
                        {:frame :rf/causa}))
          :style {:display "flex"
                  :align-items "center"
                  :gap "10px"
                  :padding "6px 10px"
                  :background (if expanded?
                                (:bg-1 tokens)
                                (:bg-3 tokens))
                  :border (str "1px solid " (:border-subtle tokens))
                  :border-radius "3px"
                  :cursor "pointer"}}
    [:span {:style {:color (:text-tertiary tokens)
                    :font-family mono-stack
                    :font-size "12px"
                    :min-width "14px"}}
     (if expanded? "▾" "▸")]
    [:span {:style {:color (:text-primary tokens)
                    :font-family mono-stack
                    :font-size "12px"
                    :font-weight 600
                    :min-width "140px"}}
     cluster-label]
    [:span {:data-testid (str "rf-causa-mode-c-cluster-count-"
                              (ch/format-instance-id cluster-key))
            :style {:padding "1px 8px"
                    :background (:bg-2 tokens)
                    :border (str "1px solid " (:cyan tokens))
                    :border-radius "10px"
                    :color (:cyan tokens)
                    :font-family mono-stack
                    :font-size "11px"
                    :font-weight 600
                    :min-width "30px"
                    :text-align "center"}}
     (str "● " count)]
    [:span {:style {:flex 1}}]
    [:span {:data-testid (str "rf-causa-mode-c-cluster-spark-"
                              (ch/format-instance-id cluster-key))
            :style {:opacity 0.85}}
     (chart-svg/sparkline
       rate-samples
       {:testid (str "rf-causa-mode-c-cluster-sparkline-"
                     (ch/format-instance-id cluster-key))
        :width 60 :height 16})]]
   (when expanded?
     (into [:ul {:data-testid (str "rf-causa-mode-c-cluster-expansion-"
                                   (ch/format-instance-id cluster-key))
                 :style {:list-style "none"
                         :margin "4px 0 8px 0"
                         :padding 0}}]
           (for [inst instances]
             ^{:key (str (:instance-id inst))}
             (instance-row inst (ch/selection-contains?
                                  selection (:instance-id inst))))))])

(defn- compare-cell
  "One cell inside the compare-table. Divergent cells get the amber
  tint per design §3.9."
  [value diff?]
  [:td {:style {:padding "4px 8px"
                :border (str "1px solid " (:border-subtle tokens))
                :font-family mono-stack
                :font-size "11px"
                :color (if diff?
                         (:yellow tokens)
                         (:text-secondary tokens))
                :background (if diff?
                              "rgba(251, 191, 36, 0.08)"
                              "transparent")
                :white-space "nowrap"
                :overflow "hidden"
                :text-overflow "ellipsis"
                :max-width "200px"}}
   (ch/format-context-value value)])

(defn- compare-row
  [{:keys [column values diff?]}]
  [:tr {:data-testid (str "rf-causa-mode-c-compare-row-"
                          (ch/format-instance-id (:key column)))
        :data-diff (str diff?)}
   [:th {:style {:padding "4px 8px"
                 :text-align "left"
                 :color (:text-tertiary tokens)
                 :font-family sans-stack
                 :font-size "10px"
                 :text-transform "uppercase"
                 :letter-spacing "0.5px"
                 :background (:bg-3 tokens)
                 :border (str "1px solid " (:border-subtle tokens))
                 :white-space "nowrap"}}
    (:label column)]
   (for [[idx v] (map-indexed vector values)]
     ^{:key idx}
     (compare-cell v diff?))])

(defn- compare-table-view
  "The compare-table side-panel. Renders below the cluster list when
  the selection-set has ≥2 instances. Cells that differ across the
  selection get the divergence tint."
  [{:keys [instances rows state-row] :as ct}]
  (when ct
    [:section
     {:data-testid "rf-causa-mode-c-compare-table"
      :data-instance-count (count instances)
      :style {:margin "12px 0"
              :padding "10px"
              :background (:bg-1 tokens)
              :border (str "1px solid " (:accent-violet tokens))
              :border-radius "4px"}}
     [:div {:style {:display "flex"
                    :align-items "center"
                    :justify-content "space-between"
                    :margin-bottom "8px"}}
      [:strong {:style {:color (:accent-violet tokens)
                        :font-family sans-stack
                        :font-size "11px"
                        :text-transform "uppercase"
                        :letter-spacing "0.5px"}}
       (str "Compare · " (count instances) " selected")]
      [:button
       {:data-testid "rf-causa-mode-c-compare-clear"
        :on-click (fn [_]
                    (rf/dispatch [:rf.causa/clear-mode-c-selection]
                                 {:frame :rf/causa}))
        :style {:background "transparent"
                :border (str "1px solid " (:border-subtle tokens))
                :color (:text-tertiary tokens)
                :font-family sans-stack
                :font-size "10px"
                :padding "2px 8px"
                :border-radius "10px"
                :cursor "pointer"}}
       "Clear"]]
     [:table {:style {:width "100%"
                      :border-collapse "collapse"
                      :font-family mono-stack
                      :font-size "11px"}}
      [:thead
       [:tr
        [:th {:style {:padding "4px 8px"
                      :background (:bg-3 tokens)
                      :color (:text-tertiary tokens)
                      :font-family sans-stack
                      :font-size "10px"
                      :text-align "left"
                      :border (str "1px solid " (:border-subtle tokens))}}
         "Field"]
        (for [{:keys [instance-id]} instances]
          ^{:key (str instance-id)}
          [:th {:style {:padding "4px 8px"
                        :background (:bg-3 tokens)
                        :color (:text-primary tokens)
                        :font-family mono-stack
                        :font-size "11px"
                        :text-align "left"
                        :border (str "1px solid " (:border-subtle tokens))}}
           (ch/format-instance-id instance-id)])]]
      [:tbody
       ;; state row first so the user sees the load-bearing divergence
       (compare-row state-row)
       (for [row rows]
         ^{:key (str (-> row :column :key))}
         (compare-row row))]]]))

;; ---- public view --------------------------------------------------------

(defn ClusterView
  "The Mode-C surface. Rendered inside the Machine Inspector panel
  when `(view-mode instance-count)` resolves to `:mode-c` (or when
  the user forces Mode C via the mode tab strip).

  Pure hiccup — every subscribe / dispatch resolves against
  `:rf/causa` via the enclosing frame-provider."
  []
  (let [cluster-by    @(rf/subscribe [:rf.causa/mode-c-cluster-by])
        context-key   @(rf/subscribe [:rf.causa/mode-c-context-key])
        clusters      @(rf/subscribe [:rf.causa/mode-c-clusters])
        expanded      @(rf/subscribe [:rf.causa/mode-c-expanded])
        selection     @(rf/subscribe [:rf.causa/mode-c-selection])
        compare-table @(rf/subscribe [:rf.causa/mode-c-compare-table])]
    [:section
     {:data-testid "rf-causa-mode-c"
      :data-cluster-by (name (or cluster-by :state))
      :data-cluster-count (count clusters)
      :data-selection-count (ch/selection-count selection)
      :style {:padding "12px"
              :display "flex"
              :flex-direction "column"
              :gap "8px"
              :background (:bg-2 tokens)
              :border-top (str "1px solid " (:border-default tokens))}}
     (cluster-by-selector cluster-by context-key)
     [:div {:data-testid "rf-causa-mode-c-summary"
            :style {:padding "6px 10px"
                    :color (:text-tertiary tokens)
                    :font-family sans-stack
                    :font-size "11px"}}
      (str (count clusters) " cluster"
           (when (not= 1 (count clusters)) "s")
           " · " (reduce + 0 (map :count clusters)) " instance"
           (when (not= 1 (reduce + 0 (map :count clusters))) "s")
           " · "
           "Shift+click to compare")]
     (if (empty? clusters)
       [:div {:data-testid "rf-causa-mode-c-empty"
              :style {:padding "12px"
                      :color (:text-tertiary tokens)
                      :font-family sans-stack
                      :font-size "12px"
                      :font-style "italic"}}
        "No instances for this cluster-by."]
       (into [:ul {:data-testid "rf-causa-mode-c-cluster-list"
                   :style {:list-style "none"
                           :margin 0
                           :padding 0}}]
             (for [cluster clusters]
               ^{:key (pr-str (:cluster-key cluster))}
               (cluster-row cluster
                            (ch/expanded-contains? expanded (:cluster-key cluster))
                            selection))))
     (when compare-table
       (compare-table-view compare-table))]))

;; ---- public install entry ----------------------------------------------

(defn install!
  "Idempotent install — called by `machine_inspector/install!`."
  []
  (install-subs!)
  (install-events!))
