(ns day8.re-frame2-causa.panels.issues-ribbon
  "Issues ribbon panel — Phase 5 (rf2-d1p4o, parent rf2-5aw5v).

  Per `tools/causa/spec/000-Vision.md` L94 + spec/007-UX-IA.md
  §Sidebar groups the Issues ribbon is the unified feed of errors,
  warnings, schema violations, and hydration mismatches. It ties to
  the spec/009-Instrumentation.md §Error event catalogue which
  enumerates the ~95 categories the runtime emits — the panel
  consumes that stream end-to-end.

  ## What this panel shows

  Each issue trace event from the buffer lands as one row. Per the
  bead's minimum-viable contract rows render:

      timestamp · category · severity · short description · jump-to-source

  Click a row → the parent dispatch is selected (`:rf.causa/select-
  dispatch-id`) and the user pivots to the event-detail panel for the
  cascade that produced the issue. Click the source-coord chip →
  the editor opens at that line (via the `:rf.causa/open-in-editor`
  event stub — the actual editor jump rides on the open-in-editor
  module).

  ## Filter axes (per the bead's contract)

      severity         — error / warning / advisory  (chip row)
      category-prefix  — :rf.error/* vs :rf.warning/* etc.  (chip row)
      since-ms         — within this many ms of now  (numeric input)

  Each axis is independent; empty filter sets disable the axis.

  ## Empty states

  Per the bead's contract:

    :no-issues  → 'No issues observed in this session.' with the
                  '✓ All clear' badge — the desired state.
    :no-matches → issues exist but the active filters hide them
                  all. Carries a 'Clear filters' affordance.

  ## Pure hiccup (rf2-tijr)

  Same contract as every other Causa panel — the view is pure
  hiccup, no Reagent / UIx / Helix references. Frame isolation
  comes from the enclosing `[rf/frame-provider {:frame :rf/causa}]`
  in `shell.cljs`.

  ## Helpers

  All pure-data logic — severity classification, category-prefix
  projection, filter application, empty-state classification —
  lives in `issues_ribbon_helpers.cljc` so the algebra runs under
  the JVM unit-test target."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.issues-ribbon-helpers :as h]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- chip helpers -------------------------------------------------------

(defn- chip
  "One toggleable filter chip. `active?` drives the highlighted
  styling. `on-click` fires the relevant toggle event-db."
  [{:keys [label active? on-click test-id colour]}]
  [:button {:data-testid test-id
            :on-click    on-click
            :style       {:background    (if active?
                                           (:bg-active tokens)
                                           "transparent")
                          :color         (if active?
                                           (:text-primary tokens)
                                           (or colour (:text-secondary tokens)))
                          :border        (str "1px solid "
                                              (if active?
                                                (or colour (:border-default tokens))
                                                (:border-subtle tokens)))
                          :border-radius "999px"
                          :padding       "2px 10px"
                          :cursor        "pointer"
                          :font-family   sans-stack
                          :font-size     "11px"
                          :font-weight   (if active? 600 400)
                          :letter-spacing "0.2px"
                          :margin-right  "6px"
                          :margin-bottom "4px"}}
   label])

(defn- severity-chips
  "Filter chip row for the three severity buckets. Each chip toggles
  membership in the active severity set."
  [active-severities counts]
  (into [:div {:data-testid "rf-causa-issues-severity-chips"
               :style       {:display "flex" :flex-wrap "wrap"}}]
        (for [severity h/severity-order
              :let [active? (contains? active-severities severity)
                    n       (get counts severity 0)
                    colour  (h/severity-colour severity)]]
          (chip {:label    (str (h/severity-label severity)
                                " · "
                                n)
                 :active?  active?
                 :colour   colour
                 :test-id  (str "rf-causa-issues-severity-chip-"
                                (name severity))
                 :on-click #(rf/dispatch [:rf.causa/toggle-issues-severity
                                          severity])}))))

(defn- prefix-chips
  "Filter chip row for the distinct category prefixes present in the
  feed. Per spec/009 the prefix carries the domain provenance — a
  programmer filtering for `:rf.ssr/*` is asking 'just SSR issues'."
  [active-prefixes prefixes]
  (when (seq prefixes)
    (into [:div {:data-testid "rf-causa-issues-prefix-chips"
                 :style       {:display "flex" :flex-wrap "wrap"
                               :margin-top "4px"}}]
          (for [prefix prefixes
                :let [active? (contains? active-prefixes prefix)]]
            (chip {:label    prefix
                   :active?  active?
                   :colour   (:accent-violet tokens)
                   :test-id  (str "rf-causa-issues-prefix-chip-" prefix)
                   :on-click #(rf/dispatch [:rf.causa/toggle-issues-prefix
                                            prefix])})))))

(defn- since-input
  "The `since-ms` filter — a numeric input rendered as a chip-row
  sibling. Values are in seconds for ergonomic typing; the helper
  converts to ms before dispatching."
  [since-ms]
  [:div {:data-testid "rf-causa-issues-since-input"
         :style {:display "flex"
                 :align-items "center"
                 :gap "6px"
                 :margin-top "4px"
                 :font-family sans-stack
                 :font-size "11px"
                 :color (:text-secondary tokens)}}
   [:span "since"]
   [:input {:type      "number"
            :min       "0"
            :step      "10"
            :value     (str (when (number? since-ms) (long (/ since-ms 1000))))
            :on-change (fn [e]
                         (let [v (.. e -target -value)
                               n (try
                                   (let [parsed (js/parseInt v 10)]
                                     (when-not (js/isNaN parsed) parsed))
                                   (catch :default _ nil))]
                           (rf/dispatch [:rf.causa/set-issues-since-seconds n])))
            :style     {:width "60px"
                        :background (:bg-3 tokens)
                        :color (:text-primary tokens)
                        :border (str "1px solid " (:border-subtle tokens))
                        :border-radius "3px"
                        :padding "2px 4px"
                        :font-family mono-stack
                        :font-size "11px"}}]
   [:span "s ago"]])

;; ---- header strip -------------------------------------------------------

(defn- header
  "Panel header — title + counts + filter chips."
  [{:keys [total rendered severity-counts active-severities
           active-prefixes distinct-prefixes since-ms any-filter?]}]
  [:header {:style {:padding "12px 16px 6px 16px"
                    :border-bottom (str "1px solid " (:border-subtle tokens))}}
   [:div {:style {:display     "flex"
                  :align-items "baseline"
                  :gap         "12px"}}
    [:h1 {:style {:font-size "16px"
                  :font-weight 600
                  :margin 0
                  :color  (:text-primary tokens)}}
     "Issues"]
    [:span {:data-testid "rf-causa-issues-counts"
            :style {:font-size   "11px"
                    :color       (:text-tertiary tokens)
                    :font-family mono-stack}}
     (str rendered " / " total " in view")]
    (when any-filter?
      [:button {:data-testid "rf-causa-issues-clear-filters"
                :on-click    #(rf/dispatch [:rf.causa/clear-issues-filters])
                :style       {:margin-left "auto"
                              :background  "transparent"
                              :color       (:cyan tokens)
                              :border      (str "1px solid " (:border-default tokens))
                              :padding     "2px 8px"
                              :border-radius "3px"
                              :cursor      "pointer"
                              :font-family sans-stack
                              :font-size   "11px"}}
       "Clear filters"])]
   [:div {:style {:margin-top "8px"}}
    (severity-chips active-severities severity-counts)
    (prefix-chips active-prefixes distinct-prefixes)
    (since-input since-ms)]])

;; ---- per-row -------------------------------------------------------------

(defn- issue-row
  "One row in the issues feed. Click the row → pivot to the cascade
  in the event-detail panel. Click the source-coord chip → open in
  editor."
  [{:keys [id time severity operation category-prefix description
           source-coord dispatch-id]
    :as _issue}]
  (let [row-test-id (str "rf-causa-issues-row-" id)
        colour      (h/severity-colour severity)]
    [:li {:key         id
          :data-testid row-test-id
          :on-click    (fn []
                         (when dispatch-id
                           (rf/dispatch [:rf.causa/select-dispatch-id dispatch-id])
                           (rf/dispatch [:rf.causa/select-panel :event-detail])))
          :style       {:display       "grid"
                        :grid-template-columns "84px 18px minmax(120px, 1fr) 2fr auto"
                        :gap           "10px"
                        :align-items   "center"
                        :padding       "6px 16px"
                        :border-bottom (str "1px solid " (:border-subtle tokens))
                        :cursor        (if dispatch-id "pointer" "default")
                        :color         (:text-primary tokens)
                        :font-family   mono-stack
                        :font-size     "12px"
                        :line-height   1.35}}
     ;; Timestamp
     [:span {:data-testid (str row-test-id "-time")
             :style {:color (:text-tertiary tokens)
                     :font-size "11px"
                     :white-space "nowrap"}}
      (or (h/format-time time) "—")]
     ;; Severity glyph
     [:span {:data-testid (str row-test-id "-severity")
             :title       (h/severity-label severity)
             :style       {:color colour
                           :font-weight 700
                           :text-align "center"}}
      (h/severity-glyph severity)]
     ;; Category prefix
     [:span {:data-testid (str row-test-id "-category")
             :style       {:color       (:accent-violet tokens)
                           :overflow    "hidden"
                           :text-overflow "ellipsis"
                           :white-space "nowrap"}
             :title       (str operation)}
      (or category-prefix "—")]
     ;; Description
     [:span {:data-testid (str row-test-id "-description")
             :style       {:color (:text-secondary tokens)
                           :overflow "hidden"
                           :text-overflow "ellipsis"
                           :white-space "nowrap"}
             :title       description}
      description]
     ;; Source-coord (when present)
     (if source-coord
       [:button {:data-testid (str row-test-id "-source")
                 :on-click    (fn [e]
                                ;; Stop propagation so the row's pivot
                                ;; handler doesn't also fire — the
                                ;; source-coord click is a distinct
                                ;; affordance per the bead's contract.
                                (.stopPropagation e)
                                (rf/dispatch [:rf.causa/open-in-editor
                                              {:source-coord source-coord}]))
                 :style       {:background  "transparent"
                               :color       (:cyan tokens)
                               :border      (str "1px solid " (:border-subtle tokens))
                               :padding     "1px 6px"
                               :border-radius "3px"
                               :cursor      "pointer"
                               :font-family mono-stack
                               :font-size   "10px"}}
        source-coord]
       [:span {:style {:color (:text-tertiary tokens)
                       :font-size "10px"}}
        "—"])]))

;; ---- empty states -------------------------------------------------------

(defn- empty-state-no-issues
  "Per the bead's contract — the desired state. 'No issues observed
  in this session.' with the '✓ All clear' badge."
  []
  [:div {:data-testid "rf-causa-issues-empty-no-issues"
         :style       {:padding     "24px"
                       :font-family sans-stack
                       :font-size   "13px"
                       :line-height 1.5
                       :color       (:text-secondary tokens)}}
   [:div {:style {:display "flex"
                  :align-items "center"
                  :gap "10px"
                  :margin-bottom "8px"}}
    [:span {:style {:color       (:green tokens)
                    :font-size   "16px"
                    :font-weight 700}}
     "✓"]
    [:span {:style {:color       (:green tokens)
                    :font-weight 600}}
     "All clear"]]
   [:p {:style {:margin 0
                :color  (:text-tertiary tokens)}}
    "No issues observed in this session."]])

(defn- empty-state-no-matches
  "Issues exist but the active filters hide them all. Carries a
  Clear filters affordance."
  []
  [:div {:data-testid "rf-causa-issues-empty-no-matches"
         :style       {:padding     "24px"
                       :font-family sans-stack
                       :font-size   "13px"
                       :line-height 1.5
                       :color       (:text-secondary tokens)}}
   [:p {:style {:margin "0 0 12px 0"
                :color (:text-primary tokens)
                :font-weight 600}}
    "No issues match the active filters."]
   [:p {:style {:margin "0 0 12px 0"
                :color (:text-tertiary tokens)}}
    "Adjust the severity / prefix / since-ms chips above to widen the feed."]
   [:button {:data-testid "rf-causa-issues-empty-clear-filters"
             :on-click    #(rf/dispatch [:rf.causa/clear-issues-filters])
             :style       {:background "transparent"
                           :color      (:cyan tokens)
                           :border     (str "1px solid " (:border-default tokens))
                           :padding    "4px 10px"
                           :border-radius "3px"
                           :cursor     "pointer"
                           :font-family sans-stack
                           :font-size  "12px"}}
    "Clear filters"]])

;; ---- public view --------------------------------------------------------

(defn issues-ribbon-view
  "The Issues ribbon panel's root view. Subscribes to
  `:rf.causa/issues-ribbon` and renders the empty-state or the feed."
  []
  (let [{:keys [issues total rendered severity-counts distinct-prefixes
                filters empty-kind]
         :as _data}
        @(rf/subscribe [:rf.causa/issues-ribbon])
        {:keys [severities prefixes since-ms]} filters
        any-filter? (boolean (or (seq severities)
                                 (seq prefixes)
                                 (some? since-ms)))]
    [:section {:data-testid "rf-causa-issues-ribbon"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     (header {:total              total
              :rendered           rendered
              :severity-counts    severity-counts
              :active-severities  (or severities #{})
              :active-prefixes    (or prefixes #{})
              :distinct-prefixes  distinct-prefixes
              :since-ms           since-ms
              :any-filter?        any-filter?})
     [:div {:style {:flex 1 :overflow "auto"}}
      (case empty-kind
        :no-issues  (empty-state-no-issues)
        :no-matches (empty-state-no-matches)
        nil         (into [:ul {:data-testid "rf-causa-issues-feed"
                                :style       {:list-style "none"
                                              :margin     0
                                              :padding    0}}]
                          (for [issue issues]
                            (issue-row issue))))]]))
