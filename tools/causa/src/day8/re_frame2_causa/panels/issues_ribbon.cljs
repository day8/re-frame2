(ns day8.re-frame2-causa.panels.issues-ribbon
  "Issues panel — focused-epoch lens (rf2-jio48; Figma reconcile
  rf2-ad7zx.9, per spec/021 §8).

  Per `tools/causa/spec/021-Dynamic-Panel-Designs.md` §1.2 every L4
  panel is focused-epoch-scoped — the Issues panel reads ONLY the
  focused epoch's `:trace-events`, projects the issue subset (errors
  + warnings + advisories), and answers spec/021 §8.1's question:

      \"What's wrong in this epoch?\"

  No aggregate-across-epochs view, no `:ungrouped` lane — the L2 event
  timeline carries per-row issue badges (spec/021 §1.2) for cross-epoch
  navigation; this panel is the per-epoch lens.

  ## What this panel shows (Figma design — rf2-ad7zx)

  Reconciled to `design-reference/components/IssuesPanel.tsx` + spec/021
  §8.2 — each issue trace event from the focused epoch lands as ONE row:

      ▌ SEVERITY  category   short description        timestamp  ↗

  where the leading `▌` is a 3px LEFT-BORDER coloured by severity, the
  SEVERITY badge is uppercase TEXT (ERROR / WARNING / ADVISORY) in its
  severity colour, the category is muted, the description is primary,
  and the timestamp is mono + RIGHT-aligned. The trailing `↗` opens
  the responsible handler at `file:line`.

  Click a row → flips the visible tab to Event so the user pivots to
  the Event panel for the cascade that produced the issue. Click the
  `↗` source affordance → the editor opens at that line (via the
  `:rf.causa/open-in-editor` event — the actual editor jump rides on
  the open-in-editor module).

  ## No filtering (rf2-ad7zx.9)

  The Figma design renders pure rows with NO filter chrome — the
  focused epoch IS the scope, and issues are rare-but-high-signal so
  every one reads inline at a glance. The legacy severity / prefix
  filter-chip header, the per-epoch counts, the clear-filters control,
  and the `:no-matches` empty state are dropped, mirroring the Trace
  panel's no-filter reconcile (rf2-gkczt).

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
                      per spec/021 §8.2 (single calm line).

  ## Pure hiccup (rf2-tijr)

  Same contract as every other Causa panel — the view is pure
  hiccup, no Reagent / UIx / Helix references. Frame isolation
  comes from the enclosing `[rf/frame-provider {:frame :rf/causa}]`
  in `shell.cljs`.

  ## Helpers

  All pure-data logic — severity classification, category projection,
  empty-state classification — lives in `issues_ribbon_helpers.cljc`
  so the algebra runs under the JVM unit-test target."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panel-registry :as panel-registry]
            [day8.re-frame2-causa.panels.issues-ribbon-helpers :as h]
            [day8.re-frame2-causa.panels.overflow-indicator :as overflow]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- per-row -------------------------------------------------------------

(defn- issue-row
  "One row in the issues feed, reconciled to the Figma design
  (`design-reference/components/IssuesPanel.tsx` + spec/021 §8.2):

      ▌ SEVERITY  category   short description       timestamp  ↗

  The leading `▌` is a 3px severity-coloured LEFT-BORDER; the SEVERITY
  cell is an uppercase TEXT badge in its severity colour; the category
  is muted; the description fills; the timestamp is mono + RIGHT-aligned;
  and `↗` is the open-in-editor source affordance.

  Click the row → pivot to the Event panel. Click `↗` → open in editor.
  Within a focused-epoch lens the parent cascade is the focused epoch
  itself, so the row pivot lands on the panel that already drives the
  focus — the click is a convenience surface, not a cascade switch.
  Source-coord click stops propagation so the source affordance stays
  distinct from the row pivot."
  [{:keys [id time severity category description source-coord]
    :as _issue}]
  (let [row-test-id (str "rf-causa-issues-row-" id)
        colour      (h/severity-colour severity)]
    [:li {:key         id
          :data-testid row-test-id
          :on-click    (fn []
                         ;; Flip the visible tab to Event so the row
                         ;; pivot lands in the event lens.
                         (rf/dispatch [:rf.causa/select-tab :event] {:frame :rf/causa}))
          ;; rf2-tha26 — the category column is a FIXED width (≈ Figma
          ;; `w-36`) so the description column is the SOLE flexible track
          ;; (`1fr` = the reference's `flex-1`). The prior `0.6fr`
          ;; category shared the flexible width with the description, so
          ;; a long category starved the description into a `:r…`
          ;; ellipsis even when the row had room — the description now
          ;; fills all the space the fixed columns leave.
          :style       {:display       "grid"
                        :grid-template-columns "78px 144px minmax(0, 1fr) auto 18px"
                        :gap           "16px"
                        :align-items   "center"
                        :padding       "8px 12px"
                        :border        (str "1px solid " (:border-default tokens))
                        :border-left   (str "3px solid " colour)
                        :border-radius "4px"
                        :margin-bottom "8px"
                        :cursor        "pointer"
                        :color         (:text-primary tokens)
                        :font-family   sans-stack
                        :font-size     "12px"
                        :line-height   1.35}}
     ;; Severity TEXT badge (uppercase, in its severity colour)
     [:span {:data-testid (str row-test-id "-severity")
             :title       (name severity)
             :style       {:color          colour
                           :font-weight    600
                           :font-size      "10px"
                           :letter-spacing "0.4px"
                           :white-space    "nowrap"}}
      (h/severity-badge-label severity)]
     ;; Category (muted)
     [:span {:data-testid (str row-test-id "-category")
             :style       {:color         (:text-tertiary tokens)
                           :overflow      "hidden"
                           :text-overflow "ellipsis"
                           :white-space   "nowrap"}
             :title       (str category)}
      (or category "—")]
     ;; Description (primary)
     [:span {:data-testid (str row-test-id "-description")
             :style       {:color         (:text-primary tokens)
                           :overflow      "hidden"
                           :text-overflow "ellipsis"
                           :white-space   "nowrap"}
             :title       description}
      description]
     ;; Timestamp (mono, muted, RIGHT-aligned)
     [:span {:data-testid (str row-test-id "-time")
             :style {:color       (:text-tertiary tokens)
                     :font-family mono-stack
                     :font-size   "11px"
                     :text-align  "right"
                     :white-space "nowrap"}}
      (or (h/format-time time) "—")]
     ;; Source-coord affordance (`↗`, when present)
     (if source-coord
       [:button {:data-testid (str row-test-id "-source")
                 :title       source-coord
                 :on-click    (fn [e]
                                ;; Stop propagation so the row's pivot
                                ;; handler doesn't also fire — the
                                ;; source-coord click is a distinct
                                ;; affordance per spec/021 §8.4.
                                (.stopPropagation e)
                                (rf/dispatch [:rf.causa/open-in-editor
                                              {:source-coord source-coord}] {:frame :rf/causa}))
                 :style       {:background  "transparent"
                               :color       (:accent tokens)
                               :border      "none"
                               :padding     0
                               :cursor      "pointer"
                               :font-size   "13px"
                               :line-height 1}}
        "↗"]
       [:span {:style {:color (:text-tertiary tokens)
                       :font-size "13px"
                       :text-align "center"}}
        "·"])]))

;; ---- footer hint --------------------------------------------------------

(defn- footer-hint
  "Reference footer hint (rf2-tha26 ·
  `design-reference/components/IssuesPanel.tsx`). Spells out the row's
  two affordances — click the row pivots to the Event panel (the
  cascade that produced the issue); click `↗` opens the responsible
  handler at `file:line`. Rendered below the feed (feed branch only) so
  it shares the focused-epoch lens with the rows it describes."
  []
  [:div {:data-testid "rf-causa-issues-footer-hint"
         :style       {:margin-top  "16px"
                       :padding-top "16px"
                       :border-top  (str "1px solid " (:border-default tokens))
                       :color       (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size   "11px"}}
   "click a row → Event panel (the cascade) · click ↗ → source file:line"])

;; ---- empty states -------------------------------------------------------

(defn- empty-state-no-issues
  "Per spec/021 §8.2 — the focused epoch has no issues. The 'No issues
  in this epoch.' line alone is the body; positive-state."
  []
  [:div {:data-testid "rf-causa-issues-empty-no-issues"
         :style       {:padding     "32px"
                       :text-align  "center"
                       :font-family sans-stack
                       :font-size   "13px"
                       :line-height 1.5
                       :color       (:text-tertiary tokens)}}
   "No issues in this epoch."])

(defn- empty-state-no-focus
  "Defensive — the spine has not landed on a focused epoch yet. Single
  terse line so the panel-skeleton doesn't look broken."
  []
  [:div {:data-testid "rf-causa-issues-empty-no-focus"
         :style       {:padding     "32px"
                       :text-align  "center"
                       :font-family sans-stack
                       :font-size   "13px"
                       :line-height 1.5
                       :color       (:text-tertiary tokens)}}
   "No epoch focused."])

(defn- empty-state-epoch-evicted
  "Per spec/021 §10.7 — canonical evicted-epoch placeholder.

  Triggered when the operator scrubs onto an epoch whose record has
  been evicted from the framework's `:epoch-history` ring buffer.
  Every panel surfaces the same block per §10.7 so the operator's
  experience is uniform across the L4 surface."
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

;; ---- public view --------------------------------------------------------

(rf/reg-view Panel
  "The Issues panel's root view. Subscribes to `:rf.causa/issues-ribbon`
  and renders the empty-state or the focused epoch's issue feed.

  Per spec/021 §1.2 the panel is focused-epoch-scoped — there is no
  global firehose / aggregate view, and no `:ungrouped` escape-hatch
  lane (those navigation needs live on the L2 timeline's per-row
  badges per §1.2). No filter chrome (rf2-ad7zx.9) — pure rows per
  the Figma design."
  []
  (let [{:keys [issues epoch-id empty-kind] :as _data}
        @(rf/subscribe [:rf.causa/issues-ribbon])]
    [:section {:data-testid "rf-causa-issues-ribbon"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     [:div {:style {:flex 1 :overflow "auto" :padding "16px"}}
      (case empty-kind
        :no-focus      (empty-state-no-focus)
        :epoch-evicted (empty-state-epoch-evicted epoch-id)
        :no-issues     (empty-state-no-issues)
        nil            [:<>
                        (overflow/capped-list
                          issues
                          {:panel-id "issues"
                           :ul-attrs {:data-testid "rf-causa-issues-feed"
                                      :style       {:list-style "none"
                                                    :margin     0
                                                    :padding    0}}
                           :row-fn   issue-row})
                        (footer-hint)])]]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Issues panel's Causa-side registrations
  (rf2-jio48 rebuild; Figma reconcile rf2-ad7zx.9, per spec/021 §8)."
  []
  ;; ---- spec/021 §8 — Issues panel (focused-epoch lens) --------------------
  ;;
  ;; Per spec/021 §1.2 every L4 panel is focused-epoch-scoped. The
  ;; Issues panel reads the focused epoch's `:trace-events`, projects
  ;; the issue subset (errors + warnings + advisories) per spec/009
  ;; §Error event catalogue, and renders one row per issue.
  ;;
  ;; No filtering (rf2-ad7zx.9) — the Figma design renders pure rows;
  ;; the focused epoch IS the scope.
  ;;
  ;; Empty-kind discriminator (drives the view's branching):
  ;;
  ;;   :no-focus       — spine has no focused epoch (cold start)
  ;;   :epoch-evicted  — focused epoch record evicted from history
  ;;                     (per :epoch-history capping); the view paints
  ;;                     the canonical placeholder per spec/021 §10.7
  ;;   :no-issues      — focused epoch carries no issues
  ;;   nil             — render the feed

  ;; Composite — produces every slot the view consumes. Reactive
  ;; surface: focus + epoch-history. The helper's `project-feed` does
  ;; the heavy lifting; the sub is a thin wrapper that classifies focus
  ;; status (no-focus / focused / evicted), looks up the focused epoch
  ;; record, and threads it through.
  ;;
  ;; Per spec/021 §1.2 the panel is focused-epoch-scoped — the sub
  ;; joins on `:rf.causa/focus`'s `:epoch-id` against the per-frame
  ;; `:rf.causa/epoch-history`, NOT the global trace bus. The epoch
  ;; record IS the scope.
  (rf/reg-sub :rf.causa/issues-ribbon
    :<- [:rf.causa/focus]
    :<- [:rf.causa/epoch-history]
    (fn [[focus epoch-history] _query]
      (let [focus-epoch-id (:epoch-id focus)
            focus-status   (h/resolve-focus-status focus-epoch-id
                                                   epoch-history)
            record         (h/find-epoch-record focus-epoch-id
                                                epoch-history)]
        (h/project-feed record focus-status))))

  ;; rf2-2moh1 — register the Dynamic Issues tab with the internal L4
  ;; tab registry. Order 7 — Routing sits at order 6 (rf2-nrbs9).
  (panel-registry/reg-l4-tab!
    {:id    :issues
     :label "Issues"
     :mnem  "i"
     :modes #{:dynamic}
     :order 7
     :panel Panel}))
