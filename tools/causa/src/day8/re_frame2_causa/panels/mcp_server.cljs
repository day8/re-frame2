(ns day8.re-frame2-causa.panels.mcp-server
  "MCP Server panel — Phase 5 (rf2-81qjj, parent rf2-5aw5v).

  Causa's panel-side surface for the `tools/causa-mcp/` agent server.
  This panel shows what the AI agent is doing in the host app, by
  filtering the trace-buffer to events tagged `:tags :origin
  :causa-mcp` (the canonical tag the causa-mcp jar stamps on every
  side-effect it performs — per
  `tools/causa/spec/010-MCP-Server.md` §Origin tagging +
  `tools/causa-mcp/spec/Principles.md` §Origin tagging is the
  convention).

  ## What this panel shows

  A read-only, scrollable, timestamped ribbon of every trace event
  the agent has produced this session. Each row carries:

      timestamp · op-type · operation · tool · description · source-coord

  Click a row → the parent dispatch-id is selected
  (`:rf.causa/select-dispatch-id`) and the user pivots to the
  event-detail panel for the cascade. Empty `:dispatch-id` events
  (registry-time / lifecycle) stay non-clickable.

  ## Settings sub-pane

  Two settings surface inline above the feed (in lieu of a separate
  Settings modal section — those land with the broader Settings work
  per spec/007-UX-IA.md §Modal layers):

  - **Origin colour** — read-only swatch + label. Currently locked to
    cyan `#06B6D4` per the v1 inferential decision (see
    mcp_server_helpers.cljc §INFERENTIAL DECISIONS). A follow-on bead
    promotes this to spec/007-UX-IA.md §Colour system and surfaces a
    full picker.

  - **Origin-filter enable / disable** — drives the
    `:rf.causa/mcp-origin-filter-enabled?` toggle which the Trace +
    Causality + Event-detail panels can read to dim non-agent events
    (the cross-panel wiring is a follow-on bead; this panel ships
    the toggle so Mike can flick it at any time and any consumer
    that reads the sub honours it immediately).

  ## INFERENTIAL DECISIONS (rf2-81qjj — spec-deficient bead)

  Three open questions the bead-filing agent flagged; each is
  documented inline as a `;; DECISION` comment so a follow-on bead
  can refine without spelunking history:

  - (a) **Sidebar panel y/n** → yes (dedicated `:mcp-server` panel).
  - (b) **Origin colour for `:causa-mcp`** → cyan `#06B6D4`.
  - (c) **Bidirectional Causa→agent surface** → out of scope for v1
        (causa-mcp jar implementation concern, not a Causa panel
        concern).

  See mcp_server_helpers.cljc for the rationale on each.

  ## Pure hiccup (rf2-tijr)

  Same contract as every other Causa panel — pure hiccup, no
  Reagent / UIx / Helix references. Frame isolation comes from the
  enclosing `[rf/frame-provider {:frame :rf/causa}]` in shell.cljs.

  ## Helpers

  All pure-data logic — origin classification, row projection, filter
  application, empty-state classification — lives in
  `mcp_server_helpers.cljc` so the algebra runs under the JVM
  unit-test target."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.mcp-server-helpers :as h]))

;; ---- design tokens (mirrors event_detail.cljs / issues_ribbon.cljs) -----

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
   :magenta         "#E879F9"
   ;; DECISION (b) — `:origin :causa-mcp` gets cyan #06B6D4. Distinct
   ;; from :pair indigo (locked) and :story/:test cyan (#43C3D0).
   ;; Follow-on bead promotes this to spec/007-UX-IA.md §Colour system.
   :causa-mcp-cyan  h/causa-mcp-origin-colour})

(def ^:private mono-stack
  "JetBrains Mono stack per spec/007-UX-IA.md §Typography."
  "JetBrains Mono, ui-monospace, SF Mono, Menlo, monospace")

(def ^:private sans-stack
  "Inter stack per spec/007-UX-IA.md §Typography."
  "Inter, system-ui, -apple-system, Segoe UI, sans-serif")

;; ---- chip helpers -------------------------------------------------------

(defn- chip
  "One toggleable filter chip. `active?` drives the highlighted
  styling. Mirrors `issues_ribbon.cljs`'s chip helper so the visual
  rhythm matches across the two feeds."
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

(defn- op-type-chips
  "Filter chip row over the distinct op-types present in the feed.
  Each chip toggles membership in the active op-type filter set."
  [active-op-types distinct-op-types op-type-counts]
  (when (seq distinct-op-types)
    (into [:div {:data-testid "rf-causa-mcp-op-type-chips"
                 :style       {:display "flex" :flex-wrap "wrap"}}]
          (for [op-type distinct-op-types
                :let [active? (contains? active-op-types op-type)
                      n       (get op-type-counts op-type 0)]]
            (chip {:label    (str (name op-type) " · " n)
                   :active?  active?
                   :colour   (:causa-mcp-cyan tokens)
                   :test-id  (str "rf-causa-mcp-op-type-chip-" (name op-type))
                   :on-click #(rf/dispatch [:rf.causa/toggle-mcp-op-type
                                            op-type])})))))

(defn- since-input
  "The `since-ms` filter — a numeric input rendered as a chip-row
  sibling. Values are in seconds for ergonomic typing; the helper
  converts to ms before dispatching. Mirrors the Issues ribbon's
  identical surface."
  [since-ms]
  [:div {:data-testid "rf-causa-mcp-since-input"
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
                           (rf/dispatch [:rf.causa/set-mcp-since-seconds n])))
            :style     {:width "60px"
                        :background (:bg-3 tokens)
                        :color (:text-primary tokens)
                        :border (str "1px solid " (:border-subtle tokens))
                        :border-radius "3px"
                        :padding "2px 4px"
                        :font-family mono-stack
                        :font-size "11px"}}]
   [:span "s ago"]])

;; ---- settings sub-pane --------------------------------------------------

(defn- settings-sub-pane
  "Inline Settings strip — origin colour swatch + origin-filter
  enable/disable toggle. Per the bead's contract these settings
  surface here (not in a separate Settings modal section) until the
  broader Settings work lands."
  [{:keys [origin-filter-enabled?]}]
  [:section {:data-testid "rf-causa-mcp-settings"
             :style       {:padding "8px 16px"
                           :border-bottom (str "1px solid " (:border-subtle tokens))
                           :background (:bg-1 tokens)
                           :font-family sans-stack
                           :font-size "11px"
                           :color (:text-secondary tokens)
                           :display "flex"
                           :align-items "center"
                           :gap "16px"
                           :flex-wrap "wrap"}}
   ;; Origin colour swatch (read-only v1; picker is a follow-on bead).
   [:div {:data-testid "rf-causa-mcp-origin-swatch"
          :style {:display "flex" :align-items "center" :gap "6px"}}
    [:span {:style {:display "inline-block"
                    :width "10px"
                    :height "10px"
                    :border-radius "50%"
                    :background (:causa-mcp-cyan tokens)
                    :border (str "1px solid " (:border-default tokens))}}]
    [:span {:style {:color (:text-tertiary tokens)}}
     "origin :causa-mcp →"]
    [:code {:style {:font-family mono-stack
                    :font-size "10px"
                    :color (:causa-mcp-cyan tokens)}}
     h/causa-mcp-origin-colour]]
   ;; Origin-filter enable / disable toggle.
   [:label {:data-testid "rf-causa-mcp-origin-filter-toggle"
            :style {:display "flex"
                    :align-items "center"
                    :gap "6px"
                    :cursor "pointer"
                    :color (:text-secondary tokens)}}
    [:input {:type      "checkbox"
             :checked   (boolean origin-filter-enabled?)
             :on-change #(rf/dispatch [:rf.causa/toggle-mcp-origin-filter])
             :style     {:cursor "pointer"}}]
    [:span "Highlight :causa-mcp events across panels"]]])

;; ---- header strip -------------------------------------------------------

(defn- header
  "Panel header — title + agent-attached badge + counts + filter
  chips. Mirrors `issues_ribbon.cljs`'s header for visual continuity."
  [{:keys [agent-attached? total rendered active-op-types
           distinct-op-types op-type-counts since-ms any-filter?]}]
  [:header {:style {:padding "12px 16px 6px 16px"
                    :border-bottom (str "1px solid " (:border-subtle tokens))}}
   [:div {:style {:display     "flex"
                  :align-items "baseline"
                  :gap         "12px"}}
    [:h1 {:style {:font-size "16px"
                  :font-weight 600
                  :margin 0
                  :color  (:text-primary tokens)}}
     "MCP"]
    [:span {:data-testid "rf-causa-mcp-attached-badge"
            :style {:font-size   "11px"
                    :font-family sans-stack
                    :color       (if agent-attached?
                                   (:causa-mcp-cyan tokens)
                                   (:text-tertiary tokens))
                    :border      (str "1px solid "
                                      (if agent-attached?
                                        (:causa-mcp-cyan tokens)
                                        (:border-subtle tokens)))
                    :border-radius "999px"
                    :padding     "1px 8px"}}
     (if agent-attached?
       "agent attached"
       "no activity")]
    [:span {:data-testid "rf-causa-mcp-counts"
            :style {:font-size   "11px"
                    :color       (:text-tertiary tokens)
                    :font-family mono-stack}}
     (str rendered " / " total " in view")]
    (when any-filter?
      [:button {:data-testid "rf-causa-mcp-clear-filters"
                :on-click    #(rf/dispatch [:rf.causa/clear-mcp-filters])
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
    (op-type-chips active-op-types distinct-op-types op-type-counts)
    (since-input since-ms)]])

;; ---- per-row -------------------------------------------------------------

(defn- mcp-row
  "One row in the agent-activity feed. Click the row → pivot to the
  cascade in the event-detail panel. Click the source-coord chip →
  open in editor (via the open-in-editor module's stub event)."
  [{:keys [id time op-type operation tool description
           source-coord dispatch-id]
    :as _row}]
  (let [row-test-id (str "rf-causa-mcp-row-" id)]
    [:li {:key         id
          :data-testid row-test-id
          :on-click    (fn []
                         (when dispatch-id
                           (rf/dispatch [:rf.causa/select-dispatch-id dispatch-id])
                           (rf/dispatch [:rf.causa/select-panel :event-detail])))
          :style       {:display       "grid"
                        :grid-template-columns "84px 90px minmax(120px, 1fr) auto 2fr auto"
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
     ;; op-type
     [:span {:data-testid (str row-test-id "-op-type")
             :style       {:color       (:causa-mcp-cyan tokens)
                           :overflow    "hidden"
                           :text-overflow "ellipsis"
                           :white-space "nowrap"
                           :font-weight 500}}
      (if op-type (name op-type) "—")]
     ;; operation
     [:span {:data-testid (str row-test-id "-operation")
             :style       {:color       (:accent-violet tokens)
                           :overflow    "hidden"
                           :text-overflow "ellipsis"
                           :white-space "nowrap"}}
      (if operation (str operation) "—")]
     ;; tool (the causa-mcp tool name lifted off :tags :tool)
     (if tool
       [:span {:data-testid (str row-test-id "-tool")
               :title       "causa-mcp tool"
               :style       {:color       (:causa-mcp-cyan tokens)
                             :font-size   "10px"
                             :background  (:bg-3 tokens)
                             :border      (str "1px solid " (:border-subtle tokens))
                             :border-radius "3px"
                             :padding     "1px 6px"
                             :white-space "nowrap"}}
        (str tool)]
       [:span {:style {:color (:text-tertiary tokens) :font-size "10px"}} "—"])
     ;; description
     [:span {:data-testid (str row-test-id "-description")
             :style       {:color (:text-secondary tokens)
                           :overflow "hidden"
                           :text-overflow "ellipsis"
                           :white-space "nowrap"}
             :title       description}
      description]
     ;; source-coord (when present)
     (if source-coord
       [:button {:data-testid (str row-test-id "-source")
                 :on-click    (fn [e]
                                ;; Stop propagation so the row's pivot
                                ;; handler doesn't also fire.
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
       [:span {:style {:color (:text-tertiary tokens) :font-size "10px"}}
        "—"])]))

;; ---- empty states -------------------------------------------------------

(defn- empty-state-no-activity
  "Rendered when no causa-mcp-tagged events have landed in the
  buffer this session. The desired state when no agent is attached
  (or when an attached agent hasn't yet performed any side-effect).
  Carries pointer copy about origin-tagging discipline so the user
  knows what they're looking at."
  []
  [:div {:data-testid "rf-causa-mcp-empty-no-activity"
         :style       {:padding     "24px"
                       :font-family sans-stack
                       :font-size   "13px"
                       :line-height 1.5
                       :color       (:text-secondary tokens)}}
   [:div {:style {:display "flex"
                  :align-items "center"
                  :gap "10px"
                  :margin-bottom "8px"}}
    [:span {:style {:color       (:causa-mcp-cyan tokens)
                    :font-size   "16px"
                    :font-weight 700}}
     "⌬"]
    [:span {:style {:color       (:text-primary tokens)
                    :font-weight 600}}
     "No agent activity"]]
   [:p {:style {:margin "0 0 8px 0" :color (:text-tertiary tokens)}}
    "Causa-MCP (the agent server) tags every operation it performs "
    "with " [:code {:style {:font-family mono-stack
                            :color (:causa-mcp-cyan tokens)}}
             ":origin :causa-mcp"]
    ". Nothing tagged that way has landed in the buffer this session."]
   [:p {:style {:margin 0 :color (:text-tertiary tokens)
                :font-size "12px"
                :font-style "italic"}}
    "Once an agent connects via the causa-mcp jar and performs an op, "
    "this feed lights up."]])

(defn- empty-state-no-matches
  "Events exist but the active filters hide them all."
  []
  [:div {:data-testid "rf-causa-mcp-empty-no-matches"
         :style       {:padding     "24px"
                       :font-family sans-stack
                       :font-size   "13px"
                       :line-height 1.5
                       :color       (:text-secondary tokens)}}
   [:p {:style {:margin "0 0 12px 0"
                :color (:text-primary tokens)
                :font-weight 600}}
    "No agent events match the active filters."]
   [:p {:style {:margin "0 0 12px 0" :color (:text-tertiary tokens)}}
    "Adjust the op-type / since-ms chips above to widen the feed."]
   [:button {:data-testid "rf-causa-mcp-empty-clear-filters"
             :on-click    #(rf/dispatch [:rf.causa/clear-mcp-filters])
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

(defn mcp-server-view
  "The MCP Server panel's root view. Subscribes to
  `:rf.causa/mcp-server` (composite) + `:rf.causa/mcp-origin-filter-
  enabled?` (settings sub-pane state)."
  []
  (let [{:keys [rows total rendered op-type-counts distinct-op-types
                filters agent-attached? empty-kind]
         :as _data}
        @(rf/subscribe [:rf.causa/mcp-server])
        origin-filter-enabled?
        @(rf/subscribe [:rf.causa/mcp-origin-filter-enabled?])
        {:keys [op-types since-ms]} filters
        any-filter? (boolean (or (seq op-types) (some? since-ms)))]
    [:section {:data-testid "rf-causa-mcp-server"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     (header {:agent-attached?    agent-attached?
              :total              total
              :rendered           rendered
              :active-op-types    (or op-types #{})
              :distinct-op-types  distinct-op-types
              :op-type-counts     op-type-counts
              :since-ms           since-ms
              :any-filter?        any-filter?})
     (settings-sub-pane {:origin-filter-enabled? origin-filter-enabled?})
     [:div {:style {:flex 1 :overflow "auto"}}
      (case empty-kind
        :no-activity (empty-state-no-activity)
        :no-matches  (empty-state-no-matches)
        nil          (into [:ul {:data-testid "rf-causa-mcp-feed"
                                 :style       {:list-style "none"
                                               :margin     0
                                               :padding    0}}]
                           (for [row rows]
                             (mcp-row row))))]]))
