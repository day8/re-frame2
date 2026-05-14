(ns day8.re-frame2-causa.panels.performance
  "Performance panel — Phase 5 (rf2-75121, parent rf2-5aw5v).

  Per `tools/causa/spec/000-Vision.md` L92 (panel-inventory row) the
  Performance panel surfaces per-cascade duration capture, perf-tier
  colour mapping, and budget-warning markers. It's the UI consumer of
  the runtime substrate Spec 009 §Performance instrumentation publishes
  (the User Timing channel, plus the trace stream's per-event :time
  fields the v1 derives durations from).

  ## What this panel shows

  Each dispatch cascade in the trace buffer renders as a row:

      tier-glyph · dispatch-id · event vector · duration · per-step bar

  The tier-glyph + colour follow `tools/causa/spec/007-UX-IA.md`
  §Colour system §Perf scale (per spec §Colour is never alone — every
  hue pairs with a shape):

      ● green   :fast      <16ms
      ● yellow  :medium    16-50ms
      ▲ orange  :slow      50-100ms
      ▲ red     :blocking  >=100ms

  Rows whose duration crosses the active budget threshold (`default-
  budget-ms`, currently 16ms = one frame at 60fps) carry an extra
  `over-budget` marker chip on the right edge — the budget warning is
  the panel's hero affordance per the bead's contract. Clicking the
  marker pivots to the event-detail panel for that cascade so the
  programmer can dig into 'why was this slow?'.

  ## Pure hiccup (rf2-tijr)

  Same contract as every other Causa panel — the view is pure hiccup,
  no Reagent / UIx / Helix references. Frame isolation comes from the
  enclosing `[rf/frame-provider {:frame :rf/causa}]` in `shell.cljs`.

  ## Helpers

  All pure-data logic — tier classification, step-breakdown, format /
  truncate, composite projection — lives in `performance_helpers.cljc`
  so the algebra runs under the JVM unit-test target. The view here
  is a thin renderer that reads the `:rf.causa/performance-data`
  composite sub.

  ## What v1 does NOT include

  Per the bead's minimum-viable contract:

    - No `PerformanceObserver` integration (INP / long-task / layout-
      shift overlays). These ride a follow-on bead once the shared
      `rf.causa.fx/install-performance-observer!` effect lands (the
      Trace panel will share that effect once both panels are live).
    - No drag-zoom horizontal ribbon. v1 ships the row view; the
      ribbon canvas is follow-on work alongside the time-axis
      synchronisation with the Trace panel.
    - No re-render-counts-per-epoch projection from the epoch-record's
      `:renders` slot. The row's render-count is a cheap proxy
      (count of `:view/render` traces in the cascade) until that slot
      surfaces."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.performance-helpers :as h]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- tier chip helpers ---------------------------------------------------

(defn- tier-chip
  "One tier-histogram chip in the header. Shows tier label + count;
  colour follows the spec's perf scale."
  [tier count]
  [:span {:data-testid (str "rf-causa-perf-tier-chip-" (name tier))
          :style       {:display       "inline-flex"
                        :align-items   "center"
                        :gap           "4px"
                        :padding       "1px 8px"
                        :border        (str "1px solid "
                                            (:border-subtle tokens))
                        :border-radius "999px"
                        :color         (h/tier-colour tier)
                        :font-family   sans-stack
                        :font-size     "11px"
                        :font-weight   600
                        :margin-right  "6px"}}
   [:span {:style {:font-weight 700}} (h/tier-glyph tier)]
   [:span {:style {:color (:text-secondary tokens)}} (h/tier-label tier)]
   [:span {:style {:color (:text-tertiary tokens) :font-family mono-stack}}
    (str count)]])

(defn- header
  "Panel header — title + total count + tier histogram + budget caption."
  [{:keys [total tier-counts over-budget-count budget-ms]}]
  [:header {:style {:padding       "12px 16px 8px 16px"
                    :border-bottom (str "1px solid " (:border-subtle tokens))}}
   [:div {:style {:display     "flex"
                  :align-items "baseline"
                  :gap         "12px"}}
    [:h1 {:style {:font-size   "16px"
                  :font-weight 600
                  :margin      0
                  :color       (:text-primary tokens)}}
     "Performance"]
    [:span {:data-testid "rf-causa-perf-totals"
            :style {:font-size   "11px"
                    :color       (:text-tertiary tokens)
                    :font-family mono-stack}}
     (str total " cascade" (if (= 1 total) "" "s"))]
    (when (pos? over-budget-count)
      [:span {:data-testid "rf-causa-perf-over-budget-count"
              :style {:margin-left "auto"
                      :color       (:red tokens)
                      :font-family mono-stack
                      :font-size   "11px"
                      :font-weight 600}}
       (str "▲ " over-budget-count " over " budget-ms "ms")])]
   [:div {:data-testid "rf-causa-perf-tier-chips"
          :style {:margin-top "8px"
                  :display    "flex"
                  :flex-wrap  "wrap"}}
    (for [tier h/tier-order]
      ^{:key tier}
      [tier-chip tier (get tier-counts tier 0)])]])

;; ---- per-step bar --------------------------------------------------------

(defn- step-bar
  "Render the per-step duration breakdown as a horizontal stacked bar.
  Each slice's width is proportional to its share of the cascade's
  total duration; the slice colour follows the per-domino tones used
  by `event_detail.cljs` so the cross-panel visual stays consistent."
  [{:keys [breakdown duration-ms] :as _row}]
  (let [segs (h/breakdown-segments breakdown duration-ms)]
    [:div {:data-testid "rf-causa-perf-step-bar"
           :style {:display       "flex"
                   :height        "8px"
                   :width         "100%"
                   :background    (:bg-3 tokens)
                   :border-radius "4px"
                   :overflow      "hidden"}}
     (for [{:keys [key ms width-pct]} segs]
       ^{:key key}
       [:div {:data-testid (str "rf-causa-perf-step-segment-" (name key))
              :title (str (h/breakdown-label key) " · "
                          (h/format-duration ms))
              :style {:width      (str width-pct "%")
                      :background (h/breakdown-colour key)
                      :height     "100%"}}])]))

;; ---- per-row -------------------------------------------------------------

(defn- perf-row
  "One row in the performance feed. Click the row → pivot to the
  cascade in the event-detail panel (parity with the Issues ribbon's
  pivot affordance)."
  [{:keys [dispatch-id event tier duration-ms over-budget?
           render-count effect-count sub-count]
    :as row}]
  (let [row-test-id (str "rf-causa-perf-row-" dispatch-id)
        colour      (h/tier-colour tier)]
    [:li {:key         dispatch-id
          :data-testid row-test-id
          :data-tier   (name tier)
          :data-over-budget (str (boolean over-budget?))
          :on-click    (fn []
                         (rf/dispatch [:rf.causa/select-dispatch-id dispatch-id])
                         (rf/dispatch [:rf.causa/select-panel :event-detail]))
          :style       {:display       "grid"
                        :grid-template-columns "18px 60px minmax(140px, 1fr) 70px 110px 1.2fr 70px"
                        :gap           "10px"
                        :align-items   "center"
                        :padding       "6px 16px"
                        :border-bottom (str "1px solid " (:border-subtle tokens))
                        :cursor        "pointer"
                        :color         (:text-primary tokens)
                        :font-family   mono-stack
                        :font-size     "12px"
                        :line-height   1.35}}
     ;; Tier glyph
     [:span {:data-testid (str row-test-id "-tier")
             :title       (h/tier-label tier)
             :style       {:color       colour
                           :font-weight 700
                           :text-align  "center"}}
      (h/tier-glyph tier)]
     ;; Dispatch-id
     [:span {:data-testid (str row-test-id "-id")
             :style       {:color (:accent-violet tokens)
                           :overflow "hidden"
                           :text-overflow "ellipsis"
                           :white-space "nowrap"}}
      (str "#" dispatch-id)]
     ;; Event vector
     [:span {:data-testid (str row-test-id "-event")
             :style       {:color       (:text-primary tokens)
                           :overflow    "hidden"
                           :text-overflow "ellipsis"
                           :white-space "nowrap"}
             :title       (h/format-event event)}
      (h/truncate (h/format-event event) 48)]
     ;; Total duration
     [:span {:data-testid (str row-test-id "-duration")
             :style       {:color colour
                           :font-weight 600
                           :text-align "right"}}
      (h/format-duration duration-ms)]
     ;; Counts (sub-label)
     [:span {:data-testid (str row-test-id "-counts")
             :style       {:color (:text-tertiary tokens)
                           :font-size "11px"
                           :white-space "nowrap"}}
      (str "fx " effect-count " · v " render-count " · s " sub-count)]
     ;; Per-step bar
     [step-bar row]
     ;; Over-budget marker (right edge)
     (if over-budget?
       [:span {:data-testid (str row-test-id "-over-budget")
               :style       {:color       (:red tokens)
                             :font-family sans-stack
                             :font-size   "10px"
                             :font-weight 700
                             :text-align  "right"}
               :title       "Over budget — click to inspect"}
        "▲ over"]
       [:span {:style {:color     (:text-tertiary tokens)
                       :font-size "10px"
                       :text-align "right"}}
        "—"])]))

;; ---- empty state ---------------------------------------------------------

(defn- empty-state
  "Per the bead's contract — 'No performance data yet — perform actions
  in the app to capture cascades.'"
  []
  [:div {:data-testid "rf-causa-perf-empty"
         :style       {:padding     "24px"
                       :font-family sans-stack
                       :font-size   "13px"
                       :line-height 1.5
                       :color       (:text-secondary tokens)}}
   [:p {:style {:margin "0 0 8px 0"
                :color  (:text-primary tokens)
                :font-weight 600}}
    "No performance data yet"]
   [:p {:style {:margin 0
                :color  (:text-tertiary tokens)}}
    "Perform actions in the app to capture cascades. Each dispatch lands "
    "as one row with a per-step duration breakdown."]])

;; ---- public view --------------------------------------------------------

(defn performance-view
  "The Performance panel's root view. Subscribes to
  `:rf.causa/performance-data` and renders the empty-state or the
  feed."
  []
  (let [{:keys [rows total tier-counts over-budget-count budget-ms empty?]
         :as _data}
        @(rf/subscribe [:rf.causa/performance-data])]
    [:section {:data-testid "rf-causa-performance"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     (header {:total              total
              :tier-counts        tier-counts
              :over-budget-count  over-budget-count
              :budget-ms          budget-ms})
     [:div {:style {:flex 1 :overflow "auto"}}
      (if empty?
        (empty-state)
        (into [:ul {:data-testid "rf-causa-perf-feed"
                    :style       {:list-style "none"
                                  :margin     0
                                  :padding    0}}]
              (for [row rows]
                (perf-row row))))]]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Performance panel's Causa-side
  registrations (Phase 5, rf2-75121)."
  []
  ;; ---- Phase 5 (rf2-75121) — Performance panel -----------------------
  ;;
  ;; Per `tools/causa/spec/000-Vision.md` L92 the Performance panel
  ;; surfaces per-cascade duration capture, perf-tier colour mapping,
  ;; and budget-warning markers. The runtime substrate is
  ;; `spec/009-Instrumentation.md §Performance instrumentation` (the
  ;; default-off User Timing channel); v1 reads the dev-build trace
  ;; stream's `:time` deltas instead so the panel works against the
  ;; same buffer every other Causa panel consumes.
  ;;
  ;; Shape of `:rf.causa/performance-data`:
  ;;
  ;;     {:rows               [<row> ...]    ;; newest first
  ;;      :total              <int>
  ;;      :tier-counts        {tier count}
  ;;      :over-budget-count  <int>
  ;;      :budget-ms          <number>
  ;;      :empty?             <bool>}
  ;;
  ;; No new events are required — the panel reuses
  ;; `:rf.causa/select-dispatch-id` + `:rf.causa/select-panel` for the
  ;; pivot-into-event-detail affordance (parity with the Issues
  ;; ribbon's row-click). The over-budget threshold is sub-readable
  ;; via `:rf.causa/performance-budget-ms` so a follow-on bead can
  ;; surface a slider in the panel header without rewiring consumers.
  (rf/reg-sub :rf.causa/performance-budget-ms
    (fn [db _query]
      (get db :performance-budget-ms h/default-budget-ms)))

  (rf/reg-sub :rf.causa/performance-data
    :<- [:rf.causa/cascades]
    :<- [:rf.causa/performance-budget-ms]
    (fn [[cascades budget-ms] _query]
      (h/project-feed cascades budget-ms)))

  ;; Set the over-budget threshold. Pass nil to reset to default.
  (rf/reg-event-db :rf.causa/set-performance-budget-ms
    (fn [db [_ budget-ms]]
      (if (and (number? budget-ms) (pos? budget-ms))
        (assoc db :performance-budget-ms budget-ms)
        (dissoc db :performance-budget-ms)))))
