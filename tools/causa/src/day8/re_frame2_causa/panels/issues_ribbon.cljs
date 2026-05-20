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

    :no-issues  → '✓ All clear' badge — the desired state.
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
                   :on-click #(rf/dispatch [:rf.causa.issues/toggle-prefix
                                            prefix] {:frame :rf/causa})})))))

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
                           (rf/dispatch [:rf.causa.issues/set-since-seconds n] {:frame :rf/causa})))
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
    ;; rf2-5kfxe.8 — domain-coloured accent stripe (:red for Issues).
    ;; rf2-5kfxe.9 — display face (Fraunces) for L4 title contrast.
    [:h1 {:style (merge {:font-size "20px"
                         :font-family display-stack
                         :font-weight 600
                         :letter-spacing "-0.01em"
                         :margin 0
                         :color  (:text-primary tokens)
                         :display "flex" :align-items "center"
                         :gap "8px"}
                        (t/accent-stripe-style :issues))}
     ;; rf2-ezx8w — spec/021 §17.1.5 per-panel header icon. ⚠ in
     ;; :red (Issues' domain colour via panel-domain->token).
     [:span {:data-testid "rf-causa-issues-panel-icon"
             :aria-hidden "true"
             :style       (t/panel-icon-style :issues)}
      (:issues t/panel-icon)]
     "Issues"]
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
        colour      (h/severity-colour severity)
        ;; `:ungrouped` is the cascade sentinel for issues outside any
        ;; dispatch — there's no meaningful event to pivot to.
        pivotable?  (and dispatch-id (not= :ungrouped dispatch-id))]
    [:li {:key         id
          :data-testid row-test-id
          :on-click    (fn []
                         (when pivotable?
                           (rf/dispatch [:rf.causa/select-dispatch-id dispatch-id] {:frame :rf/causa})
                           ;; Flip the visible tab to Event so the row
                           ;; jump lands in event-detail. The legacy
                           ;; `:rf.causa/select-panel` slot is no longer
                           ;; read by the 4-layer shell (rf2-qy0nu).
                           (rf/dispatch [:rf.causa/select-tab :event] {:frame :rf/causa})))
          :style       {:display       "grid"
                        :grid-template-columns "84px 18px minmax(120px, 1fr) 2fr auto"
                        :gap           "10px"
                        :align-items   "center"
                        :padding       "6px 16px"
                        :border-bottom (str "1px solid " (:border-subtle tokens))
                        :cursor        (if pivotable? "pointer" "default")
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
  "Per the bead's contract — the desired state. The `✓ All clear`
  badge alone is the line."
  []
  [:div {:data-testid "rf-causa-issues-empty-no-issues"
         :style       {:padding     "24px"
                       :font-family sans-stack
                       :font-size   "13px"
                       :line-height 1.5
                       :color       (:text-secondary tokens)}}
   [:div {:style {:display "flex"
                  :align-items "center"
                  :gap "10px"}}
    [:span {:style {:color       (:green tokens)
                    :font-size   "16px"
                    :font-weight 700}}
     "✓"]
    [:span {:style {:color       (:green tokens)
                    :font-weight 600}}
     "All clear"]]])

(defn- empty-state-no-issues-for-event
  "Per rf2-u6dhp — the focused cascade has no issues. The buffer DOES
  carry issues elsewhere, but the panel honours the focused-event
  invariant: the lens is on this cascade alone. Silent-by-default per
  rf2-g3ghh; a single terse line so the panel-skeleton doesn't look
  broken."
  []
  [:div {:data-testid "rf-causa-issues-empty-no-issues-for-event"
         :style       {:padding     "24px"
                       :font-family sans-stack
                       :font-size   "13px"
                       :line-height 1.5
                       :color       (:text-tertiary tokens)}}
   "No issues for this event."])

;; ---- :ungrouped lane (rf2-2f40y) ---------------------------------------

(defn- ungrouped-lane
  "Dedicated surface for issues with `:dispatch-id :ungrouped` — issues
  emitted outside any dispatch context (e.g. `verify-hydration!` firing
  `:rf.ssr/hydration-mismatch`).

  Per the rf2-2f40y operator decision (option (a)) this lane is the
  escape hatch for the navigation hole that rf2-u6dhp (cascade-scoped
  Issues feed) + rf2-fzbrw (`:ungrouped` cascades structurally
  unfocusable) jointly opened. Both invariants are preserved; the lane
  is a deliberate, scoped UI affordance for an explicit, scoped runtime
  state.

  Visibility contract (read by the public `Panel` view): rendered when
  the focused cascade carries no issues AND `:ungrouped` issues exist.
  When the focused cascade has its own issues the lane stays hidden so
  it doesn't compete for attention with the user's current lens."
  [ungrouped-issues]
  [:section {:data-testid "rf-causa-issues-ungrouped-lane"
             :style       {:border-top (str "1px solid " (:border-subtle tokens))
                           :background (:bg-3 tokens)}}
   [:header {:data-testid "rf-causa-issues-ungrouped-lane-header"
             :style       {:padding     "8px 16px 4px 16px"
                           :font-family sans-stack
                           :font-size   "11px"
                           :font-weight 600
                           :letter-spacing "0.4px"
                           :text-transform "uppercase"
                           :color       (:text-tertiary tokens)}}
    "Issues outside any cascade"]
   [:p {:data-testid "rf-causa-issues-ungrouped-lane-help"
        :style       {:margin      "0 16px 6px 16px"
                      :font-family sans-stack
                      :font-size   "11px"
                      :color       (:text-tertiary tokens)
                      :line-height 1.4}}
    "Emitted outside any dispatch — no cascade to focus."]
   (into [:ul {:data-testid "rf-causa-issues-ungrouped-lane-feed"
               :style       {:list-style "none"
                             :margin     0
                             :padding    0}}]
         (mapv issue-row ungrouped-issues))])

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
  "The Issues ribbon panel's root view. Subscribes to
  `:rf.causa/issues-ribbon` and renders the empty-state or the feed.

  Per rf2-2f40y also subscribes to `:rf.causa.issues/ungrouped` and
  renders the `:ungrouped` lane below the empty-state when the focused
  cascade carries no issues but `:ungrouped` issues exist — the
  navigation escape hatch for issues emitted outside any dispatch
  context."
  []
  (let [{:keys [issues total rendered severity-counts distinct-prefixes
                filters empty-kind]
         :as _data}
        @(rf/subscribe [:rf.causa/issues-ribbon])
        {ungrouped-issues :issues
         ungrouped-total  :total}
        @(rf/subscribe [:rf.causa.issues/ungrouped])
        {:keys [severities prefixes since-ms]} filters
        any-filter? (boolean (or (seq severities)
                                 (seq prefixes)
                                 (some? since-ms)))
        ;; The lane renders only when the focused cascade has no
        ;; issues to show AND ungrouped issues exist — so it doesn't
        ;; compete for attention with the user's current lens.
        ;; `:empty-kind` discriminates the three "main feed is empty"
        ;; branches; any of them qualifies as "focus has nothing to
        ;; surface".
        show-ungrouped-lane? (and (some? empty-kind)
                                  (pos? ungrouped-total))]
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
        :no-issues           (empty-state-no-issues)
        :no-issues-for-event (empty-state-no-issues-for-event)
        :no-matches          (empty-state-no-matches)
        nil                  (overflow/capped-list
                      issues
                      {:panel-id "issues"
                       :ul-attrs {:data-testid "rf-causa-issues-feed"
                                  :style       {:list-style "none"
                                                :margin     0
                                                :padding    0}}
                       :row-fn   issue-row}))
      (when show-ungrouped-lane?
        (ungrouped-lane ungrouped-issues))]]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Issues ribbon panel's Causa-side
  registrations (Phase 5, rf2-d1p4o)."
  []
  ;; ---- Phase 5 (rf2-d1p4o) — Issues ribbon panel ---------------------
  ;;
  ;; Per `tools/causa/spec/000-Vision.md` L94 + spec/009-Instrumentation.md
  ;; §Error event catalogue the panel is the unified feed across errors,
  ;; warnings, schema violations, and hydration mismatches. It reads
  ;; from the same trace-buffer as every other panel; the helpers
  ;; classify each event into the ribbon's three severity buckets
  ;; (:error / :warning / :advisory) and project the per-row shape
  ;; (timestamp · category · severity · short description · jump-to-
  ;; source).
  ;;
  ;; Filter axes per the bead's minimum-viable contract:
  ;;
  ;;   :severities  #{:error :warning :advisory}
  ;;   :prefixes    #{"rf.error" "rf.warning" "rf.ssr" ...}
  ;;   :since-ms    relative time window in ms (nil = no restriction)
  ;;
  ;; Each axis is independent; empty filter sets / nil :since-ms
  ;; disable the axis.
  ;;
  ;; Shape of `:rf.causa/issues-ribbon`:
  ;;
  ;;     {:issues               [<row> ...]      ;; post-filter
  ;;      :total                <int>            ;; pre-filter count
  ;;      :rendered             <int>            ;; post-filter count
  ;;      :severity-counts      {sev count}
  ;;      :distinct-prefixes    [<prefix> ...]
  ;;      :filters              <pass-through>
  ;;      :empty-kind           <:no-issues / :no-matches / nil>}

  ;; Active filter state — the panel reads the three slots through
  ;; one sub so the view re-renders atomically when filters change.
  (rf/reg-sub :rf.causa/issues-filters
    (fn [db _query]
      {:severities (get db :issues-active-severities #{})
       :prefixes   (get db :issues-active-prefixes #{})
       :since-ms   (get db :issues-since-ms)}))

  ;; Composite — produces every slot the view consumes. The
  ;; helper's `project-feed` does the heavy lifting; the sub is a
  ;; thin wrapper that injects `now-ms` (so the since-ms axis is
  ;; meaningful) and reads the trace-buffer + filter state + the
  ;; spine focus through the reactive surface.
  ;;
  ;; Per rf2-u6dhp the panel honours the focused-event invariant:
  ;; only issues whose `:dispatch-id` matches the spine's focused
  ;; cascade pass to the feed. The spine's `:rf.causa/focus` carries
  ;; `:dispatch-id` (head in LIVE, pinned in RETRO) per spec/018
  ;; §Spine binding, so the lens follows the user's L2-list focus
  ;; automatically and rebinds on every cascade landing in LIVE.
  (rf/reg-sub :rf.causa/issues-ribbon
    :<- [:rf.causa/trace-buffer]
    :<- [:rf.causa/issues-filters]
    :<- [:rf.causa/focus]
    (fn [[buffer filters focus] _query]
      (h/project-feed buffer
                      (assoc filters :focus-dispatch-id (:dispatch-id focus))
                      (h/now-ms))))

  ;; :ungrouped escape-hatch lane (rf2-2f40y). The main feed is
  ;; cascade-scoped (rf2-u6dhp) and `:ungrouped` cascades are
  ;; structurally unfocusable (rf2-fzbrw) — together those invariants
  ;; left issues with `:dispatch-id :ungrouped` (e.g. `verify-hydration!`
  ;; firing `:rf.ssr/hydration-mismatch`) un-navigable. This sub feeds
  ;; the dedicated lane the panel renders below the cascade-scoped
  ;; feed when current focus has no issues but `:ungrouped` does.
  ;;
  ;; Shape: `{:issues [<row> ...] :total <int>}`. Newest first.
  (rf/reg-sub :rf.causa.issues/ungrouped
    :<- [:rf.causa/trace-buffer]
    (fn [buffer _query]
      (h/project-ungrouped-feed buffer)))

  ;; ---- Issues ribbon events --------------------------------------

  ;; Toggle a severity chip in/out of the active filter set. Per
  ;; the bead's contract each axis is independent.
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

  ;; Set the since-ms axis from a seconds-typed user input. The
  ;; view converts s → ms here so the helper's filter-application
  ;; stays uniform in ms. nil / non-positive values clear the axis.
  (rf/reg-event-db :rf.causa.issues/set-since-seconds
    (fn [db [_ seconds]]
      (if (and (number? seconds) (pos? seconds))
        (assoc db :issues-since-ms (* (long seconds) 1000))
        (dissoc db :issues-since-ms))))

  ;; Clear every filter axis in one shot. The Clear filters
  ;; affordance in the header + the no-matches empty state both
  ;; fire this.
  (rf/reg-event-db :rf.causa.issues/clear-filters
    (fn [db _event]
      (-> db
          (dissoc :issues-active-severities)
          (dissoc :issues-active-prefixes)
          (dissoc :issues-since-ms))))

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
