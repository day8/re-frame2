(ns day8.re-frame2-causa.panels.issues-ribbon
  "Issues panel — focused-epoch lens (rf2-jio48, per spec/021 §8).

  Per `tools/causa/spec/021-Dynamic-Panel-Designs.md` §1.2 every L4
  panel is focused-epoch-scoped — the Issues panel reads ONLY the
  focused epoch's `:trace-events`, projects the issue subset (errors
  + warnings + advisories), and answers spec/021 §8.1's question:

      \"What's wrong in this epoch?\"

  No aggregate-across-epochs view, no `:ungrouped` lane — the L2 event
  timeline carries per-row issue badges (spec/021 §1.2) for cross-epoch
  navigation; this panel is the per-epoch lens.

  ## What this panel shows

  Each issue trace event from the focused epoch lands as one row. Per
  spec/021 §8.2 + §0 (density-is-binding) rows render:

      timestamp · category · severity · short description · jump-to-source

  Click a row → the parent dispatch is selected (`:rf.causa/select-
  dispatch-id`) and the user pivots to the Event panel for the cascade
  that produced the issue. Click the source-coord chip → the editor
  opens at that line (via the `:rf.causa/open-in-editor` event — the
  actual editor jump rides on the open-in-editor module).

  ## Filter axes (per spec/021 §8)

      severity         — error / warning / advisory  (chip row)
      category-prefix  — :rf.error/* vs :rf.warning/* etc.  (chip row)

  Each axis is independent; empty filter sets disable the axis. The
  legacy `since-ms` axis is gone — focused-epoch scope makes it
  meaningless (an epoch's lifetime is shorter than any time window
  the operator would type).

  ## Empty states (per spec/021 §8.2 + §10.7)

    :no-focus       → 'No epoch focused.' — cold start; spine has
                      not landed on any epoch AND no epochs are in
                      history yet. Per rf2-h0120 a nil-focus with
                      non-empty history falls back to the head epoch
                      and renders the feed, not this empty state.
    :epoch-evicted  → canonical placeholder per spec/021 §10.7:
                      'Epoch evicted from buffer.
                       Increase :epoch-history to retain more.'
    :no-issues      → 'No issues in this epoch.' — positive state
                      per spec/021 §8.2 (single-line empty).
    :no-matches     → issues exist but the active filters hide them
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
            [day8.re-frame2-causa.panel-registry :as panel-registry]
            [day8.re-frame2-causa.panels.issues-ribbon-helpers :as h]
            [day8.re-frame2-causa.panels.overflow-indicator :as overflow]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens mono-stack sans-stack display-stack]]))

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
                 :on-click #(rf/dispatch [:rf.causa.issues/toggle-severity
                                          severity] {:frame :rf/causa})}))))

(defn- prefix-chips
  "Filter chip row for the distinct category prefixes present in the
  focused epoch's issues. Per spec/009 the prefix carries the domain
  provenance — a programmer filtering for `:rf.ssr/*` is asking 'just
  SSR issues'."
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
                   :on-click #(rf/dispatch [:rf.causa.issues/toggle-prefix
                                            prefix] {:frame :rf/causa})})))))

;; ---- header strip -------------------------------------------------------

(defn- header
  "Panel header — title + per-epoch counts + filter chips.

  Per spec/021 §8.2 the header line is

      ┌─ ISSUES · epoch #42 ────────────────... ─┐

  When no epoch is focused (or evicted) the epoch chip is omitted —
  the panel body carries the explanatory placeholder."
  [{:keys [epoch-id total rendered severity-counts active-severities
           active-prefixes distinct-prefixes any-filter?]}]
  [:header {:style {:padding "12px 16px 6px 16px"
                    :border-bottom (str "1px solid " (:border-subtle tokens))}}
   [:div {:style {:display     "flex"
                  :align-items "baseline"
                  :gap         "12px"}}
    ;; rf2-5kfxe.8 — domain-coloured accent stripe (:red for Issues).
    ;; rf2-5kfxe.9 — display face (Fraunces) for L4 title contrast.
    [:h1 {:style (merge {:font-size "20px"
                         :font-family display-stack
                         :font-weight 600
                         :letter-spacing "-0.01em"
                         :margin 0
                         :color  (:text-primary tokens)}
                        (t/accent-stripe-style :issues))}
     "Issues"]
    (when (some? epoch-id)
      [:span {:data-testid "rf-causa-issues-epoch-chip"
              :style {:font-size   "11px"
                      :color       (:text-secondary tokens)
                      :font-family mono-stack
                      :letter-spacing "0.2px"}}
       (str "epoch #" epoch-id)])
    [:span {:data-testid "rf-causa-issues-counts"
            :style {:font-size   "11px"
                    :color       (:text-tertiary tokens)
                    :font-family mono-stack}}
     (str rendered " / " total " in view")]
    (when any-filter?
      [:button {:data-testid "rf-causa-issues-clear-filters"
                :on-click    #(rf/dispatch [:rf.causa.issues/clear-filters] {:frame :rf/causa})
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
    (prefix-chips active-prefixes distinct-prefixes)]])

;; ---- per-row -------------------------------------------------------------

(defn- issue-row
  "One row in the issues feed. Click the row → pivot to the Event
  panel. Click the source-coord chip → open in editor.

  Within a focused-epoch lens the parent cascade is the focused epoch
  itself, so the row pivot lands on the panel that already drives the
  focus — the click is a convenience surface, not a cascade switch.
  Source-coord click stops propagation so the source affordance stays
  distinct from the row pivot."
  [{:keys [id time severity operation category-prefix description
           source-coord]
    :as _issue}]
  (let [row-test-id (str "rf-causa-issues-row-" id)
        colour      (h/severity-colour severity)]
    [:li {:key         id
          :data-testid row-test-id
          :on-click    (fn []
                         ;; Flip the visible tab to Event so the row
                         ;; pivot lands in the event lens.
                         (rf/dispatch [:rf.causa/select-tab :event] {:frame :rf/causa}))
          :style       {:display       "grid"
                        :grid-template-columns "84px 18px minmax(120px, 1fr) 2fr auto"
                        :gap           "10px"
                        :align-items   "center"
                        :padding       "6px 16px"
                        :border-bottom (str "1px solid " (:border-subtle tokens))
                        :cursor        "pointer"
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
                                ;; affordance per spec/021 §8.4.
                                (.stopPropagation e)
                                (rf/dispatch [:rf.causa/open-in-editor
                                              {:source-coord source-coord}] {:frame :rf/causa}))
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
  "Per spec/021 §8.2 — the focused epoch has no issues. The 'No issues
  in this epoch.' line alone is the body; positive-state."
  []
  [:div {:data-testid "rf-causa-issues-empty-no-issues"
         :style       {:padding     "24px"
                       :font-family sans-stack
                       :font-size   "13px"
                       :line-height 1.5
                       :color       (:text-secondary tokens)}}
   "No issues in this epoch."])

(defn- empty-state-no-focus
  "Defensive — the spine has not landed on a focused epoch yet. Single
  terse line so the panel-skeleton doesn't look broken."
  []
  [:div {:data-testid "rf-causa-issues-empty-no-focus"
         :style       {:padding     "24px"
                       :font-family sans-stack
                       :font-size   "13px"
                       :line-height 1.5
                       :color       (:text-tertiary tokens)}}
   "No epoch focused."])

(defn- empty-state-epoch-evicted
  "Per spec/021 §10.7 — canonical evicted-epoch placeholder.

  Triggered when the operator scrubs onto an epoch whose record has
  been evicted from the framework's `:epoch-history` ring buffer.
  Every panel surfaces the same three-line block per §10.7 so the
  operator's experience is uniform across the four-tab L4 surface."
  [epoch-id]
  [:div {:data-testid "rf-causa-issues-empty-epoch-evicted"
         :style       {:padding     "24px"
                       :font-family sans-stack
                       :font-size   "13px"
                       :line-height 1.6
                       :color       (:text-secondary tokens)}}
   (when (some? epoch-id)
     [:div {:data-testid "rf-causa-issues-evicted-header"
            :style       {:font-family mono-stack
                          :font-size   "11px"
                          :color       (:text-tertiary tokens)
                          :margin-bottom "8px"}}
      (str "epoch #" epoch-id)])
   [:p {:style {:margin "0 0 4px 0"
                :color (:text-primary tokens)
                :font-weight 600}}
    "Epoch evicted from buffer."]
   [:p {:style {:margin "0 0 4px 0"
                :color (:text-tertiary tokens)
                :font-family mono-stack
                :font-size "12px"}}
    "Increase :epoch-history to retain more."]
   [:p {:style {:margin 0
                :color (:text-tertiary tokens)
                :font-size "12px"}}
    "Settings → General → Epoch history."]])

(defn- empty-state-no-matches
  "Issues exist in the focused epoch but the active filters hide them
  all. Carries a Clear filters affordance."
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
    "Adjust the severity / prefix chips above to widen the view."]
   [:button {:data-testid "rf-causa-issues-empty-clear-filters"
             :on-click    #(rf/dispatch [:rf.causa.issues/clear-filters] {:frame :rf/causa})
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

(rf/reg-view Panel
  "The Issues panel's root view. Subscribes to `:rf.causa/issues-ribbon`
  and renders the empty-state or the focused epoch's issue feed.

  Per spec/021 §1.2 the panel is focused-epoch-scoped — there is no
  global firehose / aggregate view, and no `:ungrouped` escape-hatch
  lane (those navigation needs live on the L2 timeline's per-row
  badges per §1.2)."
  []
  (let [{:keys [issues total rendered severity-counts distinct-prefixes
                filters epoch-id empty-kind]
         :as _data}
        @(rf/subscribe [:rf.causa/issues-ribbon])
        {:keys [severities prefixes]} filters
        any-filter? (boolean (or (seq severities) (seq prefixes)))]
    [:section {:data-testid "rf-causa-issues-ribbon"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     (header {:epoch-id           epoch-id
              :total              total
              :rendered           rendered
              :severity-counts    severity-counts
              :active-severities  (or severities #{})
              :active-prefixes    (or prefixes #{})
              :distinct-prefixes  distinct-prefixes
              :any-filter?        any-filter?})
     [:div {:style {:flex 1 :overflow "auto"}}
      (case empty-kind
        :no-focus      (empty-state-no-focus)
        :epoch-evicted (empty-state-epoch-evicted epoch-id)
        :no-issues     (empty-state-no-issues)
        :no-matches    (empty-state-no-matches)
        nil            (overflow/capped-list
                         issues
                         {:panel-id "issues"
                          :ul-attrs {:data-testid "rf-causa-issues-feed"
                                     :style       {:list-style "none"
                                                   :margin     0
                                                   :padding    0}}
                          :row-fn   issue-row}))]]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Issues panel's Causa-side registrations
  (rf2-jio48 rebuild per spec/021 §8)."
  []
  ;; ---- spec/021 §8 — Issues panel (focused-epoch lens) --------------------
  ;;
  ;; Per spec/021 §1.2 every L4 panel is focused-epoch-scoped. The
  ;; Issues panel reads the focused epoch's `:trace-events`, projects
  ;; the issue subset (errors + warnings + advisories) per spec/009
  ;; §Error event catalogue, applies the chip-filter axes, and renders.
  ;;
  ;; Filter axes per spec/021 §8:
  ;;
  ;;   :severities  #{:error :warning :advisory}
  ;;   :prefixes    #{\"rf.error\" \"rf.warning\" \"rf.ssr\" ...}
  ;;
  ;; Empty-kind discriminator (drives the view's branching):
  ;;
  ;;   :no-focus       — spine has no focused epoch (cold start)
  ;;   :epoch-evicted  — focused epoch record evicted from history
  ;;                     (per :epoch-history capping); the view paints
  ;;                     the canonical placeholder per spec/021 §10.7
  ;;   :no-issues      — focused epoch carries no issues
  ;;   :no-matches     — issues exist but chip filters hide them all
  ;;   nil             — render the feed

  ;; Active filter state — the panel reads the two slots through one
  ;; sub so the view re-renders atomically when filters change.
  (rf/reg-sub :rf.causa/issues-filters
    (fn [db _query]
      {:severities (get db :issues-active-severities #{})
       :prefixes   (get db :issues-active-prefixes #{})}))

  ;; Composite — produces every slot the view consumes. Reactive
  ;; surface: focus + epoch-history + filter state. The helper's
  ;; `project-feed` does the heavy lifting; the sub is a thin wrapper
  ;; that classifies focus status (no-focus / focused / evicted),
  ;; looks up the focused epoch record, and threads it through.
  ;;
  ;; Per spec/021 §1.2 the panel is focused-epoch-scoped — the sub
  ;; joins on `:rf.causa/focus`'s `:epoch-id` against the per-frame
  ;; `:rf.causa/epoch-history`, NOT the global trace bus. Cascade-
  ;; scope filtering is gone (the record IS the scope); chip filters
  ;; (severity / prefix) AND on top of the focused-epoch view.
  (rf/reg-sub :rf.causa/issues-ribbon
    :<- [:rf.causa/focus]
    :<- [:rf.causa/epoch-history]
    :<- [:rf.causa/issues-filters]
    (fn [[focus epoch-history filters] _query]
      (let [focus-epoch-id (:epoch-id focus)
            focus-status   (h/resolve-focus-status focus-epoch-id
                                                   epoch-history)
            record         (h/find-epoch-record focus-epoch-id
                                                epoch-history)]
        (h/project-feed record filters focus-status))))

  ;; ---- Issues panel events --------------------------------------------

  ;; Toggle a severity chip in/out of the active filter set. Per
  ;; spec/021 §8 each axis is independent.
  (rf/reg-event-db :rf.causa.issues/toggle-severity
    (fn [db [_ severity]]
      (let [current (get db :issues-active-severities #{})]
        (assoc db :issues-active-severities
               (if (contains? current severity)
                 (disj current severity)
                 (conj current severity))))))

  ;; Toggle a category-prefix chip. Same shape as the severity
  ;; toggle — multi-select set; empty set = no restriction.
  (rf/reg-event-db :rf.causa.issues/toggle-prefix
    (fn [db [_ prefix]]
      (let [current (get db :issues-active-prefixes #{})]
        (assoc db :issues-active-prefixes
               (if (contains? current prefix)
                 (disj current prefix)
                 (conj current prefix))))))

  ;; Clear every filter axis in one shot. The Clear filters
  ;; affordance in the header + the no-matches empty state both
  ;; fire this.
  (rf/reg-event-db :rf.causa.issues/clear-filters
    (fn [db _event]
      (-> db
          (dissoc :issues-active-severities)
          (dissoc :issues-active-prefixes))))

  ;; rf2-2moh1 — register the Runtime Issues tab with the internal L4
  ;; tab registry. rf2-mkpnb — order bumped 6 → 7 to make room for the
  ;; new Machines Canvas tab at order 5 + Routing at order 6.
  (panel-registry/reg-l4-tab!
    {:id    :issues
     :label "Issues"
     :mnem  "i"
     :modes #{:runtime}
     :order 7
     :panel Panel}))
