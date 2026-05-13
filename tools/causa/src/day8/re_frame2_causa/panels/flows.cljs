(ns day8.re-frame2-causa.panels.flows
  "Flows panel — Phase 5 (rf2-83irn, parent rf2-5aw5v).

  Surfaces re-frame2's registered flows + their per-flow inputs / output
  path / live recomputation indicator. Consumer of:

    - Spec 013 (Flows)             — the registered-flow surface;
                                     `(rf/handlers :flow)` is the
                                     `{flow-id metadata}` projection
                                     the composite sub reads.
    - Spec 009 (Instrumentation)   — the `:rf.flow/*` trace event
                                     vocabulary. The panel filters the
                                     Causa trace buffer to `:op-type
                                     :flow` and derives a per-flow
                                     status from the latest event.

  ## What this panel shows

  One row per registered flow, with — left to right:

    - status badge (`:failed` / `:computing` / `:skipping` / `:idle`),
    - the flow-id (mono column),
    - the inputs paths (` · `-separated),
    - the output path,
    - a `RAN` cue when the flow recomputed *this* cascade (the live
      recomputation indicator),
    - the most-recent operation in a small caption (for at-a-glance
      flow-state debugging).

  Clicking a row selects that flow — Phase-5 minimum-viable contract
  carries the selection; the deeper detail strip (recent events,
  jump-to-event-detail) lands when the cross-panel wiring stabilises.

  Empty state: \"No flows registered.\"

  ## Pure hiccup

  Same contract as every other Causa panel — the view is pure hiccup,
  no Reagent / UIx / Helix references. Frame isolation comes from the
  enclosing `[rf/frame-provider {:frame :rf/causa}]` in `shell.cljs`.
  Every `subscribe` / `dispatch` here resolves to the `:rf/causa`
  frame.

  ## Helpers

  All pure-data logic — `project-rows`, `compute-status`, `latest-
  cascade-dispatch-id`, ... — lives in `flows_helpers.cljc` so the
  algebra runs under the JVM unit-test target."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.flows-helpers :as h]))

;; ---- design tokens (mirrors subscriptions.cljs / event_detail.cljs) ----

(def ^:private tokens
  "Subset of shell.cljs's dark-theme tokens used by this panel. Kept
  in sync manually for now — the v1.0 styling pass replaces these
  with CSS variables across all panels."
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

;; ---- pure helpers -------------------------------------------------------

(defn- status-colour
  "Resolve a status's colour token to its hex via the panel's
  `tokens` map. Single point of indirection so the test suite can
  assert against the token without touching the hex."
  [status]
  (let [tok (get h/status->token status :text-tertiary)]
    (get tokens tok (:text-tertiary tokens))))

;; ---- status badge -------------------------------------------------------

(defn- status-badge
  "Colour + shape + tooltip-label, 14px on cosy density. Sits at the
  left margin of every flow row. `status` is one of the canonical
  four — `#{:computing :idle :skipping :failed}`."
  [status]
  (let [glyph   (get h/status->glyph status "?")
        colour  (status-colour status)
        tooltip (get h/status->tooltip status "Unknown")]
    [:span {:data-testid     (str "rf-causa-flow-badge-" (name status))
            :title           tooltip
            :aria-label      tooltip
            :style           {:display        "inline-block"
                              :width          "14px"
                              :height         "14px"
                              :line-height    "14px"
                              :text-align     "center"
                              :color          colour
                              :font-family    mono-stack
                              :font-size      "14px"
                              :font-weight    700
                              :margin-right   "8px"
                              :flex-shrink    0}}
     glyph]))

;; ---- flow row -----------------------------------------------------------

(defn- frame-pill
  "Compact frame indicator — flows are frame-scoped per Spec 013
  §Frame-scoping; surfacing the frame on the row lets the panel
  carry multi-frame setups without ambiguity. nil-safe."
  [frame]
  [:span {:style {:display       "inline-block"
                  :padding       "1px 6px"
                  :margin-right  "8px"
                  :border-radius "3px"
                  :background    (:bg-3 tokens)
                  :color         (:text-secondary tokens)
                  :font-family   mono-stack
                  :font-size     "10px"}}
   (if (nil? frame) "—" (str frame))])

(defn- flow-row
  "One row in the flow list. Clicking the row fires
  `:rf.causa/select-flow-id` so a follow-on cross-panel affordance
  can drill into the event-detail panel filtered to this flow's
  recent recomputations."
  [{:keys [flow-id frame inputs path status recomputing? last-operation]
    :as _row}
   selected?]
  [:li {:data-testid (str "rf-causa-flow-row-" (h/format-flow-id flow-id))
        :on-click   #(rf/dispatch [:rf.causa/select-flow-id flow-id])
        :style      {:display       "flex"
                     :align-items   "center"
                     :padding       "6px 12px"
                     :background    (if selected?
                                      (:bg-active tokens)
                                      "transparent")
                     :border-bottom (str "1px solid " (:border-subtle tokens))
                     :cursor        "pointer"
                     :font-family   mono-stack
                     :font-size     "13px"
                     :color         (:text-primary tokens)}}
   (status-badge status)
   (frame-pill frame)
   [:span {:style {:flex 1
                   :min-width 0
                   :overflow "hidden"
                   :text-overflow "ellipsis"
                   :white-space "nowrap"}}
    [:span {:data-testid (str "rf-causa-flow-id-" (h/format-flow-id flow-id))
            :style {:color (:text-primary tokens) :margin-right "8px"}}
     (h/format-flow-id flow-id)]
    [:span {:data-testid (str "rf-causa-flow-inputs-" (h/format-flow-id flow-id))
            :style {:color (:text-secondary tokens) :margin-right "8px"
                    :font-size "11px"}}
     (h/format-inputs inputs)]
    [:span {:style {:color (:text-tertiary tokens) :margin-right "6px"
                    :font-size "11px"}}
     "→"]
    [:span {:data-testid (str "rf-causa-flow-path-" (h/format-flow-id flow-id))
            :style {:color (:accent-violet tokens) :font-size "11px"}}
     (h/format-path path)]]
   (when recomputing?
     [:span {:data-testid (str "rf-causa-flow-row-recomputing-"
                               (h/format-flow-id flow-id))
             :style {:color (:cyan tokens)
                     :font-size "10px"
                     :margin-left "8px"
                     :text-transform "uppercase"
                     :letter-spacing "0.5px"}}
      "ran"])
   (when last-operation
     [:span {:style {:color (:text-tertiary tokens)
                     :font-size "10px"
                     :margin-left "8px"
                     :font-family sans-stack}}
      (case last-operation
        :rf.flow/computed   "computed"
        :rf.flow/skip       "skipped"
        :rf.flow/failed     "failed"
        :rf.flow/registered "registered"
        :rf.flow/cleared    "cleared"
        (str last-operation))])])

;; ---- flow list ----------------------------------------------------------

(defn- flow-list
  "The default list view. Renders one row per registered flow in the
  canonical sort order (failures first, then computing, skipping,
  idle)."
  [rows selected-flow-id]
  (into [:ul {:data-testid "rf-causa-flows-list"
              :style {:list-style "none"
                      :margin     0
                      :padding    0
                      :background (:bg-2 tokens)}}]
        (for [row rows]
          ^{:key (h/format-flow-id (:flow-id row))}
          (flow-row row (= (:flow-id row) selected-flow-id)))))

;; ---- summary header -----------------------------------------------------

(defn- summary-header
  "Per-status tally + total count, mirroring the Subscriptions
  panel's filter header (sans the filter chips — the four-status
  taxonomy is small enough that v1 sorts rather than filters)."
  [status-counts total]
  [:div {:data-testid "rf-causa-flows-summary"
         :style       {:padding "8px 12px"
                       :display "flex"
                       :align-items "center"
                       :gap "8px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :color (:text-tertiary tokens)
                       :font-family mono-stack
                       :font-size "11px"}}
   (for [s h/statuses
         :let [n (get status-counts s 0)]
         :when (pos? n)]
     ^{:key s}
     [:span {:data-testid (str "rf-causa-flows-summary-" (name s))
             :style {:color (status-colour s)
                     :margin-right "6px"}}
      (str (get h/status->glyph s) " " n " " (name s))])
   [:span {:style {:flex 1}}]
   [:span (str total " flow" (if (= 1 total) "" "s"))]])

;; ---- empty state --------------------------------------------------------

(defn- empty-state
  "Rendered when no flows are registered. The empty surface matches
  the bead's minimum-viable contract — exactly the copy 'No flows
  registered.' and a one-line orienting caption."
  []
  [:div {:data-testid "rf-causa-flows-empty"
         :style       {:padding "16px"
                       :color   (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size "13px"}}
   [:p {:style {:margin "0 0 8px 0"
                :color  (:text-secondary tokens)}}
    "No flows registered."]
   [:p {:style {:margin 0
                :font-size "12px"
                :color (:text-tertiary tokens)}}
    "Register a flow via "
    [:code {:style {:font-family mono-stack
                    :color       (:accent-violet tokens)}}
     "rf/reg-flow"]
    " — the panel populates as flows register. See "
    [:code {:style {:font-family mono-stack
                    :color       (:accent-violet tokens)}}
     "spec/013-Flows.md"]
    "."]])

;; ---- public view --------------------------------------------------------

(defn flows-view
  "The Flows panel's root view. Subscribes to
  `:rf.causa/flows-data` and renders the summary header + flow
  list (or the empty state when no flows are registered)."
  []
  (let [{:keys [rows status-counts total selected-flow-id]}
        @(rf/subscribe [:rf.causa/flows-data])]
    [:section {:data-testid "rf-causa-flows"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     [:header {:style {:padding "16px 16px 8px 16px"}}
      [:h1 {:style {:font-size   "16px"
                    :font-weight 600
                    :margin      0
                    :color       (:text-primary tokens)}}
       "Flows"]
      [:p {:style {:font-size "12px"
                   :color     (:text-tertiary tokens)
                   :margin    "4px 0 0 0"}}
       "Registered flows + their inputs / output paths. The "
       [:em "ran"]
       " cue pulses when a flow recomputes this cascade."]]
     (when (pos? total)
       (summary-header status-counts total))
     [:div {:style {:flex 1 :overflow "auto"}}
      (if (zero? total)
        (empty-state)
        (flow-list rows selected-flow-id))]]))
