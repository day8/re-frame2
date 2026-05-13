(ns day8.re-frame2-causa.panels.schema-violation-timeline
  "Schema-violation Timeline panel — Phase 5 (rf2-htffa, parent rf2-5aw5v).

  Per `tools/causa/spec/005-Schema-Timeline.md` this panel surfaces
  Spec 010 schema-validation failures temporally — one row per
  registered schema, coloured dots per failure, recovery-mode →
  colour mapping. Silent schema violations are real bugs in disguise;
  the timeline makes them impossible to ignore.

  ## What this panel shows

  Each `:rf.error/schema-validation-failure` trace event lands as a
  dot on its schema's row. The x-axis is time (default 60s window,
  drag-zoom expands); the y-axis groups by registered schema. Dot
  colour encodes recovery:

      :skip-handler          → red
      :skip-fx               → red
      :rollback-db           → red
      :replaced-with-default → yellow
      :re-raised             → red, thicker stroke

  Clicking a dot opens the per-violation detail side panel (schema /
  path / value / expected / recovery + Malli explanation + Triggered
  by + Registered at). Hover surfaces a 240ms tooltip with the one-
  line cause.

  ## Pure hiccup (rf2-tijr)

  Same contract as every other Causa panel — the view is pure
  hiccup, no Reagent / UIx / Helix references. SVG hiccup hosts the
  per-row track + dots; the substrate adapter mounts it via
  `rf/render`. Frame isolation comes from the enclosing
  `[rf/frame-provider {:frame :rf/causa}]` in `shell.cljs`.

  ## Helpers

  All pure-data logic — `recovery->colour`, `recovery->stroke-width`,
  `project-violations`, `project-rows`, `empty-state-kind` — lives in
  `schema_violation_timeline_helpers.cljc` so the algebra runs under
  the JVM unit-test target."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.schema-violation-timeline-helpers :as h]))

;; ---- design tokens (mirrors event_detail.cljs / time_travel.cljs) -------

(def ^:private tokens
  "Subset of shell.cljs's dark-theme tokens used by this panel."
  {:bg-1            "#15171B"
   :bg-2            "#1B1E24"
   :bg-3            "#232730"
   :bg-active       "#2A2F3D"
   :border-subtle   "#232730"
   :border-default  "#2F3441"
   :text-primary    "#E8EAF0"
   :text-secondary  "#A8AEC0"
   :text-tertiary   "#6B7080"
   :accent-violet   "#7C5CFF"
   :cyan            "#43C3D0"
   :green           "#4ADE80"
   :yellow          "#FBBF24"
   :red             "#F87171"
   :magenta         "#E879F9"})

(def ^:private mono-stack
  "JetBrains Mono stack per spec/007-UX-IA.md §Typography."
  "JetBrains Mono, ui-monospace, SF Mono, Menlo, monospace")

(def ^:private sans-stack
  "Inter stack per spec/007-UX-IA.md §Typography."
  "Inter, system-ui, -apple-system, Segoe UI, sans-serif")

;; ---- layout constants ----------------------------------------------------

(def ^:private label-column-width
  "Pixel width of the schema-name label column on the left of each
  row. Sized for typical schema-id text (`:schema/checkout` plus
  some padding)."
  180)

(def ^:private track-min-width
  "Minimum pixel width for the timeline track (the right side of
  each row). The view falls back to this when the canvas hasn't
  reported a size yet — keeps the SVG renderable in tests."
  480)

(def ^:private track-row-height 24)

;; ---- empty-state views --------------------------------------------------

(defn- empty-state-no-schemas
  "Per spec §Empty state — when no schemas are registered."
  []
  [:div {:data-testid "rf-causa-schema-timeline-empty-no-schemas"
         :style       {:padding     "24px"
                       :color       (:text-secondary tokens)
                       :font-family sans-stack
                       :font-size   "13px"
                       :line-height 1.5}}
   [:p {:style {:margin "0 0 12px 0"
                :color  (:text-primary tokens)
                :font-weight 600}}
    "No schemas registered."]
   [:p {:style {:margin "0 0 12px 0"}}
    "Once your app registers schemas via "
    [:code {:style {:font-family mono-stack
                    :color       (:accent-violet tokens)}}
     "reg-app-schema"]
    " (Spec 010), validation events will appear here:"]
   [:ul {:style {:margin     "0 0 12px 0"
                 :padding    "0 0 0 20px"
                 :color      (:text-tertiary tokens)
                 :list-style "disc"}}
    [:li "Schema violations"]
    [:li "Path-typed lookups"]
    [:li "Schema-replaced-with-default events"]]])

(defn- empty-state-no-violations
  "Per spec §Empty state — schemas registered but none in-window.
  'The empty timeline is a *result*, not a problem. Causa says so.'"
  []
  [:div {:data-testid "rf-causa-schema-timeline-empty-no-violations"
         :style       {:padding     "16px 24px"
                       :color       (:green tokens)
                       :font-family sans-stack
                       :font-size   "13px"
                       :line-height 1.5}}
   [:p {:style {:margin "0 0 6px 0"
                :font-weight 600}}
    "All schemas clean in the last 60s."]
   [:p {:style {:margin "0"
                :color  (:text-tertiary tokens)
                :font-size "12px"}}
    "This is the desired state."]])

;; ---- per-violation detail side panel ------------------------------------

(defn- detail-row
  "One labeled key/value row inside the detail panel."
  [k v]
  [:div {:style {:display "flex"
                 :gap     "8px"
                 :padding "2px 0"
                 :font-family mono-stack
                 :font-size "12px"}}
   [:span {:style {:color     (:text-tertiary tokens)
                   :min-width "92px"}}
    k]
   [:span {:style {:color       (:text-primary tokens)
                   :word-break  "break-all"}}
    v]])

(defn- violation-detail-panel
  "Per spec §Per-violation detail — the side panel that opens when a
  dot is clicked. Surfaces schema / where / path / value / recovery +
  Malli explanation + Triggered-by + Registered-at + the three
  click-through actions."
  [{:keys [id schema-id where path value explanation recovery dispatch-id]
    :as _violation}]
  [:aside {:data-testid (str "rf-causa-schema-violation-detail-" id)
           :style       {:padding        "12px 16px"
                         :background     (:bg-2 tokens)
                         :border-left    (str "1px solid " (:border-default tokens))
                         :overflow-y     "auto"
                         :max-width      "420px"
                         :min-width      "280px"
                         :flex           "0 0 auto"
                         :color          (:text-primary tokens)
                         :font-family    sans-stack}}
   [:div {:style {:display "flex"
                  :align-items "center"
                  :justify-content "space-between"
                  :margin-bottom "8px"}}
    [:h2 {:style {:font-size "13px"
                  :font-weight 600
                  :margin 0
                  :color (:text-primary tokens)}}
     "schema violation"]
    [:button {:data-testid "rf-causa-schema-violation-detail-close"
              :on-click    #(rf/dispatch [:rf.causa/clear-violation-selection])
              :style       {:background "transparent"
                            :border     "none"
                            :color      (:text-tertiary tokens)
                            :cursor     "pointer"
                            :padding    "0 2px"
                            :font-family mono-stack
                            :font-size "12px"}
              :title       "Close"}
     "✕"]]
   (detail-row "schema"   (h/schema-row-label schema-id))
   (when where  (detail-row "where"    (str where)))
   (when path   (detail-row "path"     (pr-str path)))
   (detail-row "value"    (try (pr-str value) (catch :default _ "<unprintable>")))
   (detail-row "recovery" (str recovery))

   [:div {:style {:margin "12px 0 4px 0"
                  :color (:text-secondary tokens)
                  :font-size "11px"
                  :text-transform "uppercase"
                  :letter-spacing "0.6px"}}
    "Malli explanation"]
   [:pre {:style {:background  (:bg-3 tokens)
                  :color       (:text-primary tokens)
                  :padding     "8px"
                  :border-radius "4px"
                  :font-family mono-stack
                  :font-size   "11px"
                  :white-space "pre-wrap"
                  :overflow-x  "auto"
                  :margin      0}}
    (try (pr-str explanation) (catch :default _ "<unprintable>"))]

   [:div {:style {:margin "12px 0 4px 0"
                  :color (:text-secondary tokens)
                  :font-size "11px"
                  :text-transform "uppercase"
                  :letter-spacing "0.6px"}}
    "Triggered by"]
   [:div {:style {:font-family mono-stack
                  :font-size "12px"
                  :color (:text-primary tokens)}}
    (if dispatch-id (str "dispatch " dispatch-id) "—")]
   (when dispatch-id
     [:button {:data-testid "rf-causa-schema-violation-detail-open-in-graph"
               :on-click    (fn []
                              (rf/dispatch [:rf.causa/select-dispatch-id dispatch-id])
                              (rf/dispatch [:rf.causa/select-panel :causality]))
               :style       {:background  "transparent"
                             :color       (:cyan tokens)
                             :border      (str "1px solid " (:border-default tokens))
                             :padding     "3px 8px"
                             :border-radius "4px"
                             :cursor      "pointer"
                             :font-family sans-stack
                             :font-size   "11px"
                             :margin-top  "6px"}}
      "Open in causality graph"])

   [:div {:style {:margin "12px 0 4px 0"
                  :color (:text-secondary tokens)
                  :font-size "11px"
                  :text-transform "uppercase"
                  :letter-spacing "0.6px"}}
    "Filter"]
   [:button {:data-testid "rf-causa-schema-violation-detail-filter-row"
             :on-click    #(rf/dispatch [:rf.causa/set-schema-filter schema-id])
             :style       {:background  "transparent"
                           :color       (:cyan tokens)
                           :border      (str "1px solid " (:border-default tokens))
                           :padding     "3px 8px"
                           :border-radius "4px"
                           :cursor      "pointer"
                           :font-family sans-stack
                           :font-size   "11px"}}
    "Show me all violations of this schema"]])

;; ---- per-row timeline track --------------------------------------------

(defn- row-track
  "Render one schema row — label column + SVG track of dots."
  [{:keys [schema-id label violations first?]} window track-width]
  (let [row-test-id (str "rf-causa-schema-timeline-row-"
                         (if (vector? schema-id)
                           (pr-str schema-id)
                           (str schema-id)))]
    [:div {:data-testid row-test-id
           :style       {:display     "flex"
                         :align-items "center"
                         :height      (str track-row-height "px")
                         :gap         "8px"}}
     [:div {:data-testid (str row-test-id "-label")
            :style       {:flex          "0 0 auto"
                          :width         (str label-column-width "px")
                          :overflow      "hidden"
                          :text-overflow "ellipsis"
                          :white-space   "nowrap"
                          :color         (:text-secondary tokens)
                          :font-family   mono-stack
                          :font-size     "11px"
                          :animation     (when first?
                                           (str "rf-causa-flash "
                                                h/default-flash-duration-ms
                                                "ms ease-out 1"))}}
      label]
     [:svg {:data-testid (str row-test-id "-track")
            :width       (str track-width)
            :height      (str track-row-height)
            :style       {:display    "block"
                          :background (:bg-1 tokens)
                          :border-radius "2px"
                          :flex       "1 1 auto"}}
      ;; row baseline guide
      [:line {:x1 0 :y1 (/ track-row-height 2)
              :x2 track-width :y2 (/ track-row-height 2)
              :stroke (:border-subtle tokens)
              :stroke-width 1}]
      (for [{:keys [id recovery time] :as v} violations
            :let [x (h/violation-x-position window track-width v)]
            :when (some? x)]
        (let [{:keys [fill stroke-width re-raised?]} (h/recovery->presentation recovery)]
          ^{:key id}
          [:circle {:data-testid (str row-test-id "-dot-" id)
                    :cx          x
                    :cy          (/ track-row-height 2)
                    :r           h/default-dot-radius
                    :fill        fill
                    :stroke      (if re-raised?
                                   (:text-primary tokens)
                                   fill)
                    :stroke-width stroke-width
                    :style       {:cursor "pointer"}
                    :on-click    #(rf/dispatch [:rf.causa/select-violation id])}
           [:title (h/format-tooltip v)]]))]]))

;; ---- header strip -------------------------------------------------------

(defn- header
  "Per spec §Layout the panel header carries the schema-filter
  indicator + a clear-filter affordance when a filter is active."
  [{:keys [schema-filter total-violations rendered-violations]}]
  [:header {:style {:padding "12px 16px 6px 16px"}}
   [:div {:style {:display     "flex"
                  :align-items "baseline"
                  :gap         "12px"}}
    [:h1 {:style {:font-size "16px"
                  :font-weight 600
                  :margin 0
                  :color  (:text-primary tokens)}}
     "Schema violations"]
    [:span {:style {:font-size   "11px"
                    :color       (:text-tertiary tokens)
                    :font-family mono-stack}}
     (str rendered-violations " / " total-violations " in view")]]
   (when schema-filter
     [:div {:data-testid "rf-causa-schema-timeline-filter-active"
            :style       {:margin-top "6px"
                          :display "flex"
                          :align-items "center"
                          :gap "8px"
                          :font-size "12px"
                          :color (:text-secondary tokens)
                          :font-family sans-stack}}
      [:span "filtered to "]
      [:code {:style {:color       (:accent-violet tokens)
                      :font-family mono-stack
                      :font-size   "11px"}}
       (h/schema-row-label schema-filter)]
      [:button {:data-testid "rf-causa-schema-timeline-clear-filter"
                :on-click    #(rf/dispatch [:rf.causa/set-schema-filter nil])
                :style       {:background "transparent"
                              :color      (:cyan tokens)
                              :border     (str "1px solid " (:border-default tokens))
                              :padding    "2px 6px"
                              :border-radius "3px"
                              :cursor     "pointer"
                              :font-family sans-stack
                              :font-size  "11px"}}
       "Clear filter"]])])

;; ---- public view --------------------------------------------------------

(defn schema-violation-timeline-view
  "The Schema-violation Timeline panel's root view. Subscribes to
  `:rf.causa/schema-violation-timeline` and renders the empty-state
  or the per-schema track stack."
  []
  (let [{:keys [rows window selected-violation schema-filter
                total-violations rendered-violations]
         :as _data}
        @(rf/subscribe [:rf.causa/schema-violation-timeline])
        empty-kind  (h/empty-state-kind rows)
        track-width track-min-width]
    [:section {:data-testid "rf-causa-schema-violation-timeline"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "row"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     [:div {:style {:flex            1
                    :display         "flex"
                    :flex-direction  "column"
                    :overflow        "hidden"}}
      (header {:schema-filter      schema-filter
               :total-violations   (or total-violations 0)
               :rendered-violations (or rendered-violations 0)})
      [:div {:style {:flex 1 :overflow "auto" :padding "0 16px 16px 16px"}}
       (case empty-kind
         :no-schemas    (empty-state-no-schemas)
         :no-violations (empty-state-no-violations)
         nil            (into [:div {:data-testid "rf-causa-schema-timeline-rows"
                                     :style       {:display "flex"
                                                   :flex-direction "column"
                                                   :gap "4px"
                                                   :padding-top "8px"}}]
                              (for [{:keys [schema-id] :as row} rows]
                                ^{:key (pr-str schema-id)}
                                (row-track row window track-width))))]]
     (when selected-violation
       (violation-detail-panel selected-violation))]))
