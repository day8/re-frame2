(ns day8.re-frame2-causa.shell
  "The Causa shell — empty-pane layout per tools/causa/spec/007-UX-IA.md.
  Every sidebar entry routes to a live panel view fn through `canvas`'s
  case-switch; an unrecognised id falls through to a defensive
  `unknown-panel` branch.

  ## Layout

  Per spec/007-UX-IA.md §The five regions:

      ┌─────────────────────────────────────────────────────────┐
      │ ◆ Top strip (56px)                              ?   ✕   │
      ├──────────────┬──────────────────────────────────────────┤
      │              │                                          │
      │  Sidebar     │  Canvas                                  │
      │  (152px)     │  (active panel)                          │
      │              │                                          │
      │              │                                          │
      ├──────────────┴──────────────────────────────────────────┤
      │ Bottom rail (40px)                                      │
      └─────────────────────────────────────────────────────────┘

  ## Density (rf2-pcitk)

  Typography sizes and the sidebar width are read from
  `theme.tokens/type-scale` + `theme.tokens/layout`. The shell ships
  denser than spec-cosy (closer to compact) because Causa is an
  info-dense dev surface — see the docstrings on those two defs for
  the one-knob tuning model.

  ## Frame isolation (rf2-tijr Option C + rf2-in6l2)

  The whole shell is wrapped in `[rf/frame-provider {:frame :rf/causa}
  ...]`. Every `subscribe` / `dispatch` inside the shell resolves to
  the `:rf/causa` frame; the host's `:rf/default` is untouched. Causa
  registrations under `:rf.causa/*` (see registry.cljs) operate
  against `:rf/causa`'s db when called from inside the shell.

  Per rf2-in6l2 every subscribing region of the shell is `reg-view`-
  registered so its rendered React component carries `:contextType
  frame-context` — the closest enclosing Provider's `:rf/causa`
  flows through React-context and `(rf/subscribe …)` inside the body
  resolves to the registered frame. With plain `defn`s the
  React-context tier would be skipped (Spec 004 §Plain Reagent fns
  do not pick up the surrounding frame) and subscribe would fall
  through to `:rf/default` — silently routing every Causa panel
  query into the host's app-db. The frame is lazy-registered by
  `mount.cljs/open!` (`rf/init!` must run before `reg-frame` can
  succeed; the preload runs too early).

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
            [day8.re-frame2-causa.theme.tokens :refer [tokens type-scale layout]]))

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

(rf/reg-view top-strip
  "Top strip (56px). Per spec/007-UX-IA.md §The five regions item 1:
  causality strip + frame picker + global actions (Issues badge,
  epoch counter, command palette, help, close).

  v1 stub: brand mark + version label + close-affordance text.
  Live causality strip / frame picker / Issues badge land as
  follow-on work.

  Per rf2-in6l2 the body subscribes (`:rf.causa/copilot-open?`) so
  the component is `reg-view`-registered — the wrapper attaches
  `:contextType frame-context` so the surrounding `[rf/frame-provider
  {:frame :rf/causa}]` (in `shell-view` below) reaches the subscribe
  via React context."
  [_props]
  [:div {:style {:display          "flex"
                 :align-items      "center"
                 :justify-content  "space-between"
                 :height           (:top-strip-height layout)
                 :padding          "0 16px"
                 :background       (:bg-1 tokens)
                 :border-bottom    (str "1px solid " (:border-subtle tokens))
                 :color            (:text-primary tokens)
                 :font-family      "Inter, system-ui, -apple-system, Segoe UI, sans-serif"
                 :font-size        (:body type-scale)
                 :font-weight      600}}
   [:div {:style {:display "flex" :align-items "center" :gap "12px"}}
    [:span {:style {:color (:accent-violet tokens)}} "◆"]
    [:span "Causa"]
    [:span {:style {:color       (:text-tertiary tokens)
                    :font-size   (:caption type-scale)
                    :font-weight 400}}
     "Phase 5 (rf2-pzxsr)"]]
   [:div {:style {:display "flex" :align-items "center" :gap "12px"
                  :color    (:text-secondary tokens)
                  :font-size (:caption type-scale)
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
        :on-click    #(rf/dispatch [:rf.causa/select-panel id] {:frame :rf/causa})
        :style       {:padding         "4px 12px"
                      :cursor          "pointer"
                      :background      (if active? (:bg-active tokens) "transparent")
                      :color           (cond
                                         active?  (:text-primary tokens)
                                         dormant? (:text-tertiary tokens)
                                         :else    (:text-secondary tokens))
                      :font-weight     (if active? 600 400)
                      :white-space     "nowrap"
                      :overflow        "hidden"
                      :text-overflow   "ellipsis"}}
   [:span {:style {:margin-right "8px"
                   :color        (if active?
                                   (:accent-violet tokens)
                                   (:text-tertiary tokens))}}
    (cond
      active?  "◉"
      dormant? "◌"   ; per spec/006-Hydration-Debugger.md §Visibility
      :else    "○")]
   label])

(rf/reg-view sidebar
  "Sidebar (152px, density-token driven) — panel navigation + density
  toggle. Per spec/007-UX-IA.md §Sidebar groups three groups
  (events/app-db/causality/..., conditional-with-activity, dormant)
  divider-separated. Width comes from
  `theme.tokens/layout :sidebar-width` (rf2-pcitk) — change in one
  place to retune density.

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
  shell re-render.

  Per rf2-in6l2 `reg-view`-registered so the subscribes route through
  React context to `:rf/causa`."
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
    [:nav {:style {:width            (:sidebar-width layout)
                   :flex-shrink      0
                   :background       (:bg-1 tokens)
                   :border-right     (str "1px solid " (:border-subtle tokens))
                   :overflow-y       "auto"
                   :overflow-x       "hidden"
                   :font-family      "Inter, system-ui, -apple-system, Segoe UI, sans-serif"
                   :font-size        (:body-tight type-scale)
                   :line-height      (:line-height-tight type-scale)
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
   [:p {:style {:font-size (:body type-scale) :color (:text-secondary tokens)}}
    "Unknown panel: " [:code (pr-str selected)]]])

(rf/reg-view canvas
  "Canvas — renders the active panel's content. The match is on
  `:rf.causa/selected-panel`. When a row dispatches
  `:rf.causa/select-panel <id>` the registry sets `:selected-panel`
  on the `:rf/causa` frame's app-db and this canvas recomputes.

  ## Contrast safety net (rf2-q8154)

  The wrapping `<div>` owns `flex: 1`, fills the row slot, and paints
  the dark canvas surface (`bg-2`) so any panel whose root section
  fails to fill or fails to set its own background still renders text
  on a dark surface — never on the host body's default white. Every
  Panel still sets its own `:background (:bg-2 tokens)` for parity
  with the rest of the shell; the canvas paint is a defence-in-depth
  layer, not a license to omit the panel-level styling.

  Per rf2-in6l2 `reg-view`-registered so the subscribe routes
  through React context to `:rf/causa`."
  []
  (let [selected (or @(rf/subscribe [:rf.causa/selected-panel])
                     registry/default-panel-id)]
    [:div {:style {:flex        1
                   :min-width   0
                   :display     "flex"
                   :flex-direction "column"
                   :background  (:bg-2 tokens)
                   :color       (:text-primary tokens)}}
     (case selected
       :event-detail [event-detail/Panel]
       :time-travel  [time-travel/Panel]
       :app-db       [app-db-diff/Panel]
       :causality    [causality-graph/Panel]
       ;; ── effects panel begin ──
       :fx           [effects/Panel]
       ;; ── effects panel end ──
       :flows        [flows/Panel]
       :routes       [routes/Panel]
       :schemas      [schema-violation-timeline/Panel]
       :subs         [subscriptions/Panel]
       :machines     [machine-inspector/Panel]
       :hydration    [hydration-debugger/Panel]
       :issues       [issues-ribbon/Panel]
       :trace        [trace/Panel]
       :performance  [performance/Panel]
       ;; ── mcp-server panel begin ──
       :mcp-server   [mcp-server/Panel]
       ;; ── mcp-server panel end ──
       ;; Sidebar Co-pilot row renders the panel-style view in the
       ;; canvas; the rail still lives in the shell's right margin per
       ;; spec/007-UX-IA.md §The five regions item 4.
       :copilot      [ai-co-pilot/Panel]
       [unknown-panel selected])]))

(rf/reg-view bottom-rail
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
  the events flow (the counter never bumps in that case).

  Per rf2-in6l2 `reg-view`-registered so the subscribe routes
  through React context to `:rf/causa`."
  []
  (let [redacted-count @(rf/subscribe [:rf.causa/suppressed-sensitive-count])]
    [:footer {:style {:height           (:bottom-rail-height layout)
                      :display          "flex"
                      :align-items      "center"
                      :justify-content  "space-between"
                      :padding          "0 16px"
                      :background       (:bg-1 tokens)
                      :border-top       (str "1px solid " (:border-subtle tokens))
                      :color            (:text-tertiary tokens)
                      :font-family      "Inter, system-ui, -apple-system, Segoe UI, sans-serif"
                      :font-size        (:caption type-scale)}}
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

;; Right-edge rail gate (rf2-in6l2). Extracted as its own `reg-view`
;; so the `:rf.causa/copilot-open?` subscribe routes through React
;; context to `:rf/causa` — the parent `shell-view` itself is mounted
;; at the React-context default (no enclosing Provider above it), so
;; a subscribe in its own body would route to `:rf/default`. Child
;; components rendered INSIDE the frame-provider see `:rf/causa` via
;; the React-context tier.
(rf/reg-view rail-gate
  "Conditional right-edge rail per spec/007-UX-IA.md §The five regions
  item 4. Collapsed by default (Lock 8); renders only when
  `:rf.causa/copilot-open?` is true. The cue glyph in the top strip is
  the affordance for opening it when collapsed."
  []
  (when @(rf/subscribe [:rf.causa/copilot-open?])
    [ai-co-pilot/ai-co-pilot-rail]))

(defn panel-content
  "Return the hiccup view for a panel id. Dispatch table for the shell
  canvas — every sidebar selection resolves to the matching panel view."
  [selected]
  (case selected
    :event-detail [event-detail/Panel]
    :time-travel  [time-travel/Panel]
    :app-db       [app-db-diff/Panel]
    :causality    [causality-graph/Panel]
    ;; ── effects panel begin ──
    :fx           [effects/Panel]
    ;; ── effects panel end ──
    :flows        [flows/Panel]
    :routes       [routes/Panel]
    :schemas      [schema-violation-timeline/Panel]
    :subs         [subscriptions/Panel]
    :machines     [machine-inspector/Panel]
    :hydration    [hydration-debugger/Panel]
    :issues       [issues-ribbon/Panel]
    :trace        [trace/Panel]
    :performance  [performance/Panel]
    ;; ── mcp-server panel begin ──
    :mcp-server   [mcp-server/Panel]
    ;; ── mcp-server panel end ──
    :copilot      [ai-co-pilot/Panel]
    [unknown-panel selected]))

(rf/reg-view shell-view
  "The full Causa shell. Wraps every panel region in a `:rf/causa`
  frame-provider so descendant `subscribe` / `dispatch` resolve to
  the isolated frame. Default `:inline` mode renders in normal document
  flow inside the app-provided right layout host. `:overlay` and
  `:popout` remain available debug/manual modes.

  Per rf2-in6l2 `reg-view`-registered for parity with every other
  shell region. The shell-view itself sits OUTSIDE its own frame-
  provider (it's the mount root) so React-context inside `shell-view`'s
  body still resolves to the default — every subscribing child is its
  own reg-view component so the surrounding `:rf/causa` Provider
  reaches them via React context."
  [& [{:keys [mode] :or {mode :inline}}]]
  [rf/frame-provider {:frame :rf/causa}
   [:div {:data-testid "rf-causa-shell"
          ;; Per rf2-zkfiz Q1-9: the spec-published mode axis is
          ;; `data-rf-causa-mode` (mount.cljs writes it on both the
          ;; root and the shell node). The previous `data-mode` echo
          ;; was a duplicate axis and is gone — tests + testbeds read
          ;; the rf-causa-prefixed name everywhere.
          :data-rf-causa-mode (name mode)
          :style       (merge
                         {:width            "100%"
                          :height           "100%"
                          :min-height       "100vh"
                          :display          "flex"
                          :flex-direction   "column"
                          :background       (:bg-0 tokens)
                          :color            (:text-primary tokens)
                          :font-family      "Inter, system-ui, -apple-system, Segoe UI, sans-serif"
                          :font-size        (:body type-scale)
                          :line-height      (:line-height-tight type-scale)}
                         (case mode
                           :inline
                           {:position   "relative"
                            :min-width  "320px"
                            :box-shadow "rgba(0, 0, 0, 0.28) 8px 0 20px"}

                           :popout
                           {:position "relative"}

                           {:position   "fixed"
                            :top        0
                            :right      0
                            :bottom     0
                            :width      "40%"
                            :min-width  "560px"
                            :z-index    2147483000
                            :box-shadow "rgba(0, 0, 0, 0.4) -8px 0 24px"}))}
    [top-strip {}]
    [:div {:style {:flex          1
                   :display       "flex"
                   :flex-direction "row"
                   :overflow      "hidden"}}
     [sidebar]
     [canvas]
     [rail-gate]]
    [bottom-rail]]])

