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
            [day8.re-frame2-causa.panels.schema-violation-timeline-helpers :as h]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

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
              :on-click    #(rf/dispatch [:rf.causa/clear-violation-selection] {:frame :rf/causa})
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
                              (rf/dispatch [:rf.causa/select-dispatch-id dispatch-id] {:frame :rf/causa})
                              (rf/dispatch [:rf.causa/select-panel :causality] {:frame :rf/causa}))
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
             :on-click    #(rf/dispatch [:rf.causa/set-schema-filter schema-id] {:frame :rf/causa})
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
      (for [{:keys [id recovery time where schema-id] :as v} violations
            :let [x (h/violation-x-position window track-width v)]
            :when (some? x)]
        (let [{:keys [fill stroke-width re-raised?]} (h/recovery->presentation recovery)]
          ^{:key id}
          [:circle {:data-testid (str row-test-id "-dot-" id)
                    :data-schema-kind (h/schema-row-label schema-id)
                    :data-recovery (str recovery)
                    :data-where    (str where)
                    :cx          x
                    :cy          (/ track-row-height 2)
                    :r           h/default-dot-radius
                    :fill        fill
                    :stroke      (if re-raised?
                                   (:text-primary tokens)
                                   fill)
                    :stroke-width stroke-width
                    :style       {:cursor "pointer"}
                    :on-click    #(rf/dispatch [:rf.causa/select-violation id] {:frame :rf/causa})}
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
                :on-click    #(rf/dispatch [:rf.causa/set-schema-filter nil] {:frame :rf/causa})
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

(rf/reg-view schema-violation-timeline-view
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

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Schema-violation Timeline panel's
  Causa-side registrations (Phase 5, rf2-htffa)."
  []
  ;; ---- Phase 5 (rf2-htffa) — Schema-violation Timeline subs ----
  ;;
  ;; Per tools/causa/spec/005-Schema-Timeline.md the panel reads
  ;; from three streams:
  ;;
  ;;   1. (rf/app-schemas frame-id)  — registered schemas → rows
  ;;   2. trace-buffer filtered to :rf.error/schema-validation-
  ;;      failure → dots
  ;;   3. the panel's own selection state — selected violation +
  ;;      schema filter + time-axis window
  ;;
  ;; The composite below produces one map the view consumes per
  ;; render. Per spec §Performance the projection is O(visible-
  ;; violations) — typically 0–5.

  ;; Registered schemas for the target frame — projected as a
  ;; vector of `path-or-id` row keys. `rf/app-schemas` returns a
  ;; `{path → schema}` map; the helper just iterates the map's
  ;; entry-keys to keep the row ordering stable. Returns [] when
  ;; the schemas artefact is not on the classpath.
  (rf/reg-sub :rf.causa/registered-schemas
    :<- [:rf.causa/target-frame]
    (fn [[target-frame] _query]
      (try
        (vec (keys (or (rf/app-schemas {:frame target-frame}) {})))
        (catch :default _
          []))))

  ;; The view's currently-selected violation id (the trace event's
  ;; :id, stable per-process). nil = no selection; the detail
  ;; side-panel hides.
  (rf/reg-sub :rf.causa/selected-violation-id
    (fn [db _query]
      (get db :selected-violation-id)))

  ;; Schema filter — when set, the panel narrows its rendered rows
  ;; to the matching schema only. Per spec §Per-violation detail
  ;; the 'Show me all violations of this schema' action sets this.
  (rf/reg-sub :rf.causa/schema-filter
    (fn [db _query]
      (get db :schema-filter)))

  ;; Time-axis window state — `{:t0 :t1}` in ms. nil falls back to
  ;; the newest trace event's clock-domain timestamp. Per spec §Layout the
  ;; window synchronises with the Trace panel's time-axis when
  ;; both are visible; the shared slot is what makes the
  ;; synchronisation a single-source-of-truth read.
  (rf/reg-sub :rf.causa/schema-timeline-window
    (fn [db _query]
      (or (get db :schema-timeline-window)
          (h/default-window-for-events (get db :trace-buffer)))))

  ;; Projected violations filtered to the current window. Returns
  ;; a vector of projected violation rows in chronological order
  ;; (oldest first); the composite groups by schema downstream.
  ;; Per spec §Aging out — out-of-window violations drop from the
  ;; rendered set without leaving the underlying buffer.
  (rf/reg-sub :rf.causa/schema-violations-window
    :<- [:rf.causa/trace-buffer]
    :<- [:rf.causa/schema-timeline-window]
    (fn [[buffer window] _query]
      (let [all (h/project-violations buffer)]
        (filterv #(h/violation-in-window? window %) all))))

  ;; Cache of the previously-rendered rows so the panel's flash
  ;; cue (per spec §Reading the timeline at a glance) can detect
  ;; the empty→non-empty transition. Updated on each composite
  ;; recompute; the view reads :first? off each row.
  (rf/reg-sub :rf.causa/schema-timeline-prev-rows
    (fn [db _query]
      (get db :schema-timeline-prev-rows)))

  ;; The composite the view consumes. Shape:
  ;;
  ;;     {:rows                [<row> ...]      ;; per-schema rows
  ;;      :window              {:t0 :t1}
  ;;      :total-violations    <int>            ;; pre-filter count
  ;;      :rendered-violations <int>            ;; post-filter
  ;;      :selected-violation  <projected|nil>
  ;;      :schema-filter       <schema-id|nil>}
  ;;
  ;; Per spec §Per-violation detail — `:selected-violation` is the
  ;; full projected row (resolved from :selected-violation-id); nil
  ;; when no selection OR when the id is no longer in the buffer.
  (rf/reg-sub :rf.causa/schema-violation-timeline
    :<- [:rf.causa/registered-schemas]
    :<- [:rf.causa/schema-violations-window]
    :<- [:rf.causa/schema-timeline-window]
    :<- [:rf.causa/selected-violation-id]
    :<- [:rf.causa/schema-filter]
    :<- [:rf.causa/schema-timeline-prev-rows]
    (fn [[schemas violations window selected-id schema-filter prev-rows] _query]
      (let [;; If a schema-filter is set, narrow registered-schemas
            ;; to that single row. Violations are filtered to the
            ;; same schema-id.
            schemas'    (if (some? schema-filter)
                          (filterv #(= % schema-filter) schemas)
                          schemas)
            violations' (if (some? schema-filter)
                          (filterv #(= schema-filter (:schema-id %))
                                   violations)
                          violations)
            rows        (h/project-rows
                          schemas' violations' window prev-rows)
            selected    (when selected-id
                          (h/find-violation violations selected-id))]
        {:rows                 rows
         :window               window
         :total-violations    (count violations)
         :rendered-violations (count violations')
         :selected-violation   selected
         :schema-filter        schema-filter})))

  ;; ---- Phase 5 (rf2-htffa) — Schema-violation Timeline events --

  ;; Clear the selected-violation-id (closes the detail side
  ;; panel). Per spec §Per-violation detail — the close affordance
  ;; on the detail panel header fires this.
  (rf/reg-event-db :rf.causa/clear-violation-selection
    (fn [db _event]
      (dissoc db :selected-violation-id)))

  ;; Select a violation by its trace-event :id (which is stable
  ;; per-process per Spec 009 §Trace event shape). The detail side
  ;; panel renders when this is set + the violation is still in
  ;; the buffer. Setting nil clears the selection (parity with
  ;; clear-violation-selection — the panel's dot click handler
  ;; uses select, the close button uses clear).
  (rf/reg-event-db :rf.causa/select-violation
    (fn [db [_ violation-id]]
      (if (some? violation-id)
        (assoc db :selected-violation-id violation-id)
        (dissoc db :selected-violation-id))))

  ;; Set the per-schema filter — narrows the rendered rows to one
  ;; schema. Per spec §Per-violation detail the 'Show me all
  ;; violations of this schema' action dispatches this. Passing
  ;; nil clears the filter (the Clear filter button in the
  ;; header).
  (rf/reg-event-db :rf.causa/set-schema-filter
    (fn [db [_ schema-id]]
      (if (some? schema-id)
        (assoc db :schema-filter schema-id)
        (dissoc db :schema-filter))))

  ;; Set the time-axis window. Per spec §Layout drag-zoom expands
  ;; the window; setting nil reverts to the default 60s window
  ;; ending at now (the composite sub reads default-window when
  ;; the slot is absent).
  (rf/reg-event-db :rf.causa/set-schema-timeline-window
    (fn [db [_ window]]
      (if (and (map? window)
               (number? (:t0 window))
               (number? (:t1 window))
               (< (:t0 window) (:t1 window)))
        (assoc db :schema-timeline-window window)
        (dissoc db :schema-timeline-window)))))
