(ns day8.re-frame2-causa.shell
  "The Causa shell — empty-pane layout per tools/causa/spec/007-UX-IA.md.
  Every sidebar entry routes to a live panel view fn through `canvas`'s
  case-switch; an unrecognised id falls through to a defensive
  `unknown-panel` branch.

  ## Layout

  Per spec/007-UX-IA.md §The five regions:

      ┌─────────────────────────────────────────────────────────┐
      │ ◆ Top strip (56px)                          ⌘K  ?  ✕    │
      ├──────────────┬──────────────────────────────────────────┤
      │              │                                          │
      │  Sidebar     │  Canvas                                  │
      │  (192px)     │  (active panel)                          │
      │              │                                          │
      │              │                                          │
      ├──────────────┴──────────────────────────────────────────┤
      │ Bottom rail (40px)                                      │
      └─────────────────────────────────────────────────────────┘

  ## Frame isolation (rf2-tijr Option C)

  The whole shell is wrapped in `[rf/frame-provider {:frame :rf/causa}
  ...]`. Every `subscribe` / `dispatch` inside the shell resolves to
  the `:rf/causa` frame; the host's `:rf/default` is untouched. Causa
  registrations under `:rf.causa/*` (see registry.cljs) operate
  against `:rf/causa`'s db when called from inside the shell.

  ## Pure hiccup

  Per rf2-tijr the view code is pure hiccup. The substrate adapter's
  render fn (`rf/render`) handles the substrate-specific mount in
  `mount.cljs`. No per-substrate switches in view code."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.ai-co-pilot :as ai-co-pilot]
            [day8.re-frame2-causa.panels.app-db-diff :as app-db-diff]
            [day8.re-frame2-causa.panels.event-detail :as event-detail]
            [day8.re-frame2-causa.panels.time-travel :as time-travel]
            [day8.re-frame2-causa.panels.causality-graph :as causality-graph]
            ;; ── effects panel begin ──
            [day8.re-frame2-causa.panels.effects :as effects]
            ;; ── effects panel end ──
            [day8.re-frame2-causa.panels.flows :as flows]
            [day8.re-frame2-causa.panels.hydration-debugger :as hydration-debugger]
            [day8.re-frame2-causa.panels.issues-ribbon :as issues-ribbon]
            [day8.re-frame2-causa.panels.machine-inspector :as machine-inspector]
            ;; ── mcp-server panel begin ──
            [day8.re-frame2-causa.panels.mcp-server :as mcp-server]
            ;; ── mcp-server panel end ──
            [day8.re-frame2-causa.panels.performance :as performance]
            [day8.re-frame2-causa.panels.routes :as routes]
            [day8.re-frame2-causa.panels.schema-violation-timeline :as schema-violation-timeline]
            [day8.re-frame2-causa.panels.subscriptions :as subscriptions]
            [day8.re-frame2-causa.panels.trace :as trace]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.open-in-editor :as open-in-editor]
            [day8.re-frame2-causa.theme.tokens :refer [tokens]]))

;; ---- sidebar items -------------------------------------------------------

(def ^:private sidebar-items
  "The sidebar's panel list. Per spec/007-UX-IA.md §Sidebar groups —
  three groups, divider-separated. Every panel is live; the
  Hydration entry carries `:dormant?` until the first
  `:rf.ssr/hydration-mismatch` trace lands (per spec/006-Hydration-
  Debugger.md §Visibility)."
  [{:id :event-detail :label "Event detail"}
   {:id :time-travel  :label "Time travel"}
   {:id :app-db       :label "App-db"}
   {:id :causality    :label "Causality"}
   {:id :subs         :label "Subscriptions"}
   ;; ── effects panel begin ──
   {:id :fx           :label "Effects"}
   ;; ── effects panel end ──
   {:id :trace        :label "Trace"}
   {:id :machines     :label "Machines"}
   {:id :flows        :label "Flows"}
   {:id :routes       :label "Routes"}
   {:id :performance  :label "Performance"}
   {:id :issues       :label "Issues"}
   {:id :schemas      :label "Schemas"}
   {:id :hydration    :label "Hydration"    :dormant? true}
   ;; ── mcp-server panel begin ──
   {:id :mcp-server   :label "MCP"}
   ;; ── mcp-server panel end ──
   {:id :copilot      :label "Co-pilot"}])

;; ---- regions -------------------------------------------------------------

(defn- top-strip
  "Top strip (56px). Per spec/007-UX-IA.md §The five regions item 1:
  causality strip + frame picker + global actions (Issues badge,
  epoch counter, command palette, help, close).

  v1 stub: brand mark + version label + close-affordance text.
  Live causality strip / frame picker / Issues badge land as
  follow-on work."
  [_props]
  [:div {:style {:display          "flex"
                 :align-items      "center"
                 :justify-content  "space-between"
                 :height           "56px"
                 :padding          "0 16px"
                 :background       (:bg-1 tokens)
                 :border-bottom    (str "1px solid " (:border-subtle tokens))
                 :color            (:text-primary tokens)
                 :font-family      "Inter, system-ui, -apple-system, Segoe UI, sans-serif"
                 :font-size        "14px"
                 :font-weight      600}}
   [:div {:style {:display "flex" :align-items "center" :gap "12px"}}
    [:span {:style {:color (:accent-violet tokens)}} "◆"]
    [:span "Causa"]
    [:span {:style {:color       (:text-tertiary tokens)
                    :font-size   "12px"
                    :font-weight 400}}
     "Phase 5 (rf2-pzxsr)"]]
   [:div {:style {:display "flex" :align-items "center" :gap "12px"
                  :color    (:text-secondary tokens)
                  :font-size "12px"
                  :font-weight 400}}
    ;; Collapsed-cue affordance per spec/007-UX-IA.md §The AI co-pilot
    ;; collapsed cue — the magenta `◇` glyph pulses every 8 seconds
    ;; until the user has used the co-pilot once. Renders only when the
    ;; rail is closed; clicking it toggles the rail open.
    (when-not @(rf/subscribe [:rf.causa/copilot-open?])
      [ai-co-pilot/ai-co-pilot-cue])
    [:span "Ctrl+Shift+C to toggle"]]])

(defn- sidebar-item
  "Render one sidebar item. Clicking the row fires
  `:rf.causa/select-panel`; the live row highlights based on the
  `:rf.causa/selected-panel` sub the parent reads.

  ## Dormant marker (rf2-pzxsr — hydration-debugger panel visibility)

  Per `tools/causa/spec/006-Hydration-Debugger.md` §Visibility a
  panel can be marked `:dormant?` — the entry then renders the `◌`
  marker instead of `○` until activity wakes it. The dormant fn
  passes `dormant?` here so the visibility gate is one place; the
  parent decides per-row what 'awake' means."
  [{:keys [id label dormant?]} active?]
  [:li {:data-testid (str "rf-causa-sidebar-item-" (name id))
        :on-click    #(rf/dispatch [:rf.causa/select-panel id])
        :style       {:padding         "6px 16px"
                      :cursor          "pointer"
                      :background      (if active? (:bg-active tokens) "transparent")
                      :color           (cond
                                         active?  (:text-primary tokens)
                                         dormant? (:text-tertiary tokens)
                                         :else    (:text-secondary tokens))
                      :font-weight     (if active? 600 400)}}
   [:span {:style {:margin-right "8px"
                   :color        (if active?
                                   (:accent-violet tokens)
                                   (:text-tertiary tokens))}}
    (cond
      active?  "◉"
      dormant? "◌"   ; per spec/006-Hydration-Debugger.md §Visibility
      :else    "○")]
   label])

(defn- sidebar
  "Sidebar (192px) — panel navigation + density toggle. Per spec/007-
  UX-IA.md §Sidebar groups three groups (events/app-db/causality/...,
  conditional-with-activity, dormant) divider-separated.

  Phase 2: the active panel is driven by `:rf.causa/selected-panel`.
  Clicking a row dispatches `:rf.causa/select-panel`.

  ## Hydration dormant gate (rf2-pzxsr / rf2-qym6e)

  Per `tools/causa/spec/006-Hydration-Debugger.md` §Visibility the
  Hydration sidebar entry is dormant (`◌`) until at least one
  `:rf.ssr/hydration-mismatch` trace lands. The gate reads the
  cheap presence sub `:rf.causa/hydration-has-mismatch?` (rf2-qym6e)
  — boolean-only, so the sidebar's reactive path doesn't pull the
  full mismatch-detail composite (which resolves selection, computes
  the side-by-side detail, and walks the source-coord) on every
  shell re-render."
  []
  (let [active (or @(rf/subscribe [:rf.causa/selected-panel])
                   registry/default-panel-id)
        hydration-awake? @(rf/subscribe [:rf.causa/hydration-has-mismatch?])
        items-resolved
        (mapv (fn [item]
                (cond-> item
                  (and (= :hydration (:id item)) hydration-awake?)
                  (assoc :dormant? false)))
              sidebar-items)]
    [:nav {:style {:width            "192px"
                   :flex-shrink      0
                   :background       (:bg-1 tokens)
                   :border-right     (str "1px solid " (:border-subtle tokens))
                   :overflow-y       "auto"
                   :font-family      "Inter, system-ui, -apple-system, Segoe UI, sans-serif"
                   :font-size        "13px"
                   :color            (:text-primary tokens)}}
     (into [:ul {:style {:list-style    "none"
                         :margin        0
                         :padding       "8px 0"}}]
           (for [{:keys [id] :as item} items-resolved]
             ^{:key id}
             [sidebar-item item (= id active)]))]))

(defn- unknown-panel
  "Defensive fallback for `:rf.causa/selected-panel` carrying an id
  that isn't in the canvas's case table. Every sidebar entry maps to
  a live panel; this branch only fires if the host writes an
  unrecognised id via direct dispatch."
  [selected]
  [:main {:style {:flex        1
                  :overflow    "auto"
                  :padding     "24px"
                  :background  (:bg-2 tokens)
                  :color       (:text-primary tokens)
                  :font-family "Inter, system-ui, -apple-system, Segoe UI, sans-serif"}}
   [:p {:style {:font-size "14px" :color (:text-secondary tokens)}}
    "Unknown panel: " [:code (pr-str selected)]]])

(defn- canvas
  "Canvas — renders the active panel's content. The match is on
  `:rf.causa/selected-panel`. When a row dispatches
  `:rf.causa/select-panel <id>` the registry sets `:selected-panel`
  on the `:rf/causa` frame's app-db and this canvas recomputes."
  []
  (let [selected (or @(rf/subscribe [:rf.causa/selected-panel])
                     registry/default-panel-id)]
    (case selected
      :event-detail [event-detail/event-detail-view]
      :time-travel  [time-travel/time-travel-view]
      :app-db       [app-db-diff/app-db-diff-view]
      :causality    [causality-graph/causality-graph-view]
      ;; ── effects panel begin ──
      :fx           [effects/effects-view]
      ;; ── effects panel end ──
      :flows        [flows/flows-view]
      :routes       [routes/routes-view]
      :schemas      [schema-violation-timeline/schema-violation-timeline-view]
      :subs         [subscriptions/subscriptions-view]
      :machines     [machine-inspector/machine-inspector-view]
      :hydration    [hydration-debugger/hydration-debugger-view]
      :issues       [issues-ribbon/issues-ribbon-view]
      :trace        [trace/trace-view]
      :performance  [performance/performance-view]
      ;; ── mcp-server panel begin ──
      :mcp-server   [mcp-server/mcp-server-view]
      ;; ── mcp-server panel end ──
      ;; Sidebar Co-pilot row renders the panel-style view in the
      ;; canvas; the rail still lives in the shell's right margin per
      ;; spec/007-UX-IA.md §The five regions item 4.
      :copilot      [ai-co-pilot/ai-co-pilot-view]
      [unknown-panel selected])))

(defn- bottom-rail
  "Bottom rail (40px) — time-travel scrubber + frame info + issues
  badge. Per spec/007-UX-IA.md §The five regions item 5.

  Phase 1 stub: minimal frame-info text. Live scrubber lands with the
  time-travel panel bead.

  ## Redaction indicator (rf2-azls9)

  When Causa's trace collector has dropped one or more `:sensitive?
  true` events under the default privacy posture, render a
  `[● REDACTED N]` hint in the centre of the rail. The hint
  disappears on `trace-bus/clear-buffer!` (counter resets together
  with the buffer) and when the host calls
  `(causa-config/configure! {:trace/show-sensitive? true})` BEFORE
  the events flow (the counter never bumps in that case)."
  []
  (let [redacted-count @(rf/subscribe [:rf.causa/suppressed-sensitive-count])]
    [:footer {:style {:height           "40px"
                      :display          "flex"
                      :align-items      "center"
                      :justify-content  "space-between"
                      :padding          "0 16px"
                      :background       (:bg-1 tokens)
                      :border-top       (str "1px solid " (:border-subtle tokens))
                      :color            (:text-tertiary tokens)
                      :font-family      "Inter, system-ui, -apple-system, Segoe UI, sans-serif"
                      :font-size        "12px"}}
     [:span "◀◀  ────●────  ▶▶  (scrubber)"]
     (when (pos? redacted-count)
       [:span {:data-testid "rf-causa-redacted-indicator"
               :title       (str "Spec 009 §Privacy: " redacted-count
                                 " sensitive trace event"
                                 (when (not= 1 redacted-count) "s")
                                 " suppressed by default. Set "
                                 ":trace/show-sensitive? true via "
                                 "(causa-config/configure! ...) to "
                                 "surface them.")
               :style       {:color       (:magenta tokens)
                             :font-weight 600}}
        (str "● REDACTED " redacted-count)])
     [:span "epoch — / —"]]))

;; ---- shell view ----------------------------------------------------------

(defn shell-view
  "The full Causa shell. Wraps every panel region in a `:rf/causa`
  frame-provider so descendant `subscribe` / `dispatch` resolve to
  the isolated frame. The shell's outer container is a fixed-position
  overlay along the right edge of the viewport (40% width per
  spec/007-UX-IA.md §Layout)."
  []
  [rf/frame-provider {:frame :rf/causa}
   [:div {:data-testid "rf-causa-shell"
          :style       {:position         "fixed"
                        :top              0
                        :right            0
                        :bottom           0
                        :width            "40%"
                        :min-width        "560px"
                        :display          "flex"
                        :flex-direction   "column"
                        :background       (:bg-0 tokens)
                        :color            (:text-primary tokens)
                        :z-index          2147483000
                        :box-shadow       "rgba(0, 0, 0, 0.4) -8px 0 24px"
                        :font-family      "Inter, system-ui, -apple-system, Segoe UI, sans-serif"
                        :font-size        "14px"
                        :line-height      1.5}}
    [top-strip {}]
    [:div {:style {:flex          1
                   :display       "flex"
                   :flex-direction "row"
                   :overflow      "hidden"}}
     [sidebar]
     [canvas]
     ;; Right-edge rail per spec/007-UX-IA.md §The five regions item 4.
     ;; Collapsed by default (Lock 8); renders only when
     ;; `:rf.causa/copilot-open?` is true. The cue glyph in the top
     ;; strip is the affordance for opening it when collapsed.
     (when @(rf/subscribe [:rf.causa/copilot-open?])
       [ai-co-pilot/ai-co-pilot-rail])]
    [bottom-rail]]])
