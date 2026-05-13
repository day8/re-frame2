(ns day8.re-frame2-causa.shell
  "The Causa shell — empty-pane layout per tools/causa/spec/007-UX-IA.md.

  Phase 1 (rf2-n6x4q) shipped the *structure* without the panels; per
  Phase 2 (rf2-op3bz) the hero `:event-detail` panel is now live. The
  remaining sidebar slots still render the 'Coming soon — rf2-xxx'
  stub; their live panel views land in subsequent beads under
  rf2-5aw5v.

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
            ;; ── mcp-server panel begin ──
            [day8.re-frame2-causa.panels.mcp-server :as mcp-server]
            ;; ── mcp-server panel end ──
            [day8.re-frame2-causa.panels.performance :as performance]
            [day8.re-frame2-causa.panels.schema-violation-timeline :as schema-violation-timeline]
            [day8.re-frame2-causa.panels.subscriptions :as subscriptions]
            [day8.re-frame2-causa.panels.trace :as trace]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.open-in-editor :as open-in-editor]))

;; ---- design tokens (dark theme per spec/007-UX-IA.md) --------------------

(def ^:private tokens
  "Colour + size tokens lifted from spec/007-UX-IA.md §Dark theme
  tokens. Phase 1 uses inline styles so the foundation ships without
  a CSS asset pipeline; the v1.0 styling pass replaces these with
  CSS variables when the per-panel beads land."
  {:bg-0          "#0E0F12"
   :bg-1          "#15171B"
   :bg-2          "#1B1E24"
   :bg-active     "#2A2F3D"
   :border-subtle "#232730"
   :border-default "#2F3441"
   :text-primary  "#E8EAF0"
   :text-secondary "#A8AEC0"
   :text-tertiary "#6B7080"
   :accent-violet "#7C5CFF"
   :magenta       "#E879F9"})

;; ---- sidebar items -------------------------------------------------------

(def ^:private sidebar-items
  "The sidebar's panel list. Per spec/007-UX-IA.md §Sidebar groups —
  three groups, divider-separated. Phase 2 (rf2-op3bz) lit up
  `:event-detail`; Phase 3 (rf2-t53ze) adds `:time-travel`. Remaining
  items still render the Phase 1 stub when selected."
  [{:id :event-detail :label "Event detail" :bead "rf2-op3bz" :live? true}
   {:id :time-travel  :label "Time travel"  :bead "rf2-t53ze" :live? true}
   {:id :app-db       :label "App-db"        :bead "rf2-jps1o" :live? true}
   {:id :causality    :label "Causality"     :bead "rf2-4rqs1" :live? true}
   {:id :subs         :label "Subscriptions" :bead "rf2-x0f5v" :live? true}
   ;; ── effects panel begin ──
   {:id :fx           :label "Effects"       :bead "rf2-ts41u" :live? true}
   ;; ── effects panel end ──
   {:id :trace        :label "Trace"         :bead "rf2-argrj" :live? true}
   {:id :machines     :label "Machines"      :bead "rf2-xxx"}
   {:id :flows        :label "Flows"         :bead "rf2-83irn" :live? true}
   {:id :performance  :label "Performance"   :bead "rf2-75121" :live? true}
   {:id :issues       :label "Issues"        :bead "rf2-d1p4o" :live? true}
   {:id :schemas      :label "Schemas"       :bead "rf2-htffa" :live? true}
   ;; Phase 5 (rf2-pzxsr). Per spec/006-Hydration-Debugger.md §Visibility
   ;; the panel is dormant until at least one :rf.ssr/hydration-mismatch
   ;; trace lands. The sidebar entry's `:dormant?` flag drives the `◌`
   ;; marker; once a mismatch fires the entry lights up and the entry
   ;; behaves like every other live panel.
   {:id :hydration    :label "Hydration"     :bead "rf2-pzxsr" :live? true :dormant? true}
   ;; ── mcp-server panel begin ──
   ;; rf2-81qjj — DECISION (a): a dedicated MCP entry in the sidebar
   ;; surfaces 'what is the agent doing' in one place. Placed adjacent
   ;; to Co-pilot since both are AI / agent surfaces, per spec/007-UX-
   ;; IA.md §Sidebar groups (third group is the AI surfaces).
   {:id :mcp-server   :label "MCP"           :bead "rf2-81qjj" :live? true}
   ;; ── mcp-server panel end ──
   {:id :copilot      :label "Co-pilot"      :bead "rf2-rccf3" :live? true}])

;; ---- regions -------------------------------------------------------------

(defn- top-strip
  "Top strip (56px). Per spec/007-UX-IA.md §The five regions item 1:
  causality strip + frame picker + global actions (Issues badge,
  epoch counter, command palette, help, close).

  Phase 1 stub: brand mark + version label + close-affordance text.
  Live causality strip / frame picker / Issues badge land with their
  respective panel beads (rf2-xxx)."
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
  [{:keys [id label live? dormant?]} active?]
  [:li {:data-testid (str "rf-causa-sidebar-item-" (name id))
        :on-click    #(rf/dispatch [:rf.causa/select-panel id])
        :style       {:padding         "6px 16px"
                      :cursor          "pointer"
                      :background      (if active? (:bg-active tokens) "transparent")
                      :color           (cond
                                         active?  (:text-primary tokens)
                                         dormant? (:text-tertiary tokens)
                                         live?    (:text-secondary tokens)
                                         :else    (:text-tertiary tokens))
                      :font-weight     (if active? 600 400)}}
   [:span {:style {:margin-right "8px"
                   :color        (if active?
                                   (:accent-violet tokens)
                                   (:text-tertiary tokens))}}
    (cond
      active?  "◉"
      dormant? "◌"   ; per spec/006-Hydration-Debugger.md §Visibility
      :else    "○")]
   label
   (when (and (not active?) live? (not dormant?))
     [:span {:style {:margin-left "8px"
                     :color       (:accent-violet tokens)
                     :font-size   "10px"
                     :text-transform "uppercase"
                     :letter-spacing "0.5px"}}
      "live"])])

(defn- sidebar
  "Sidebar (192px) — panel navigation + density toggle. Per spec/007-
  UX-IA.md §Sidebar groups three groups (events/app-db/causality/...,
  conditional-with-activity, dormant) divider-separated.

  Phase 2: the active panel is driven by `:rf.causa/selected-panel`.
  Clicking a row dispatches `:rf.causa/select-panel`.

  ## Hydration dormant gate (rf2-pzxsr)

  Per `tools/causa/spec/006-Hydration-Debugger.md` §Visibility the
  Hydration sidebar entry is dormant (`◌`) until at least one
  `:rf.ssr/hydration-mismatch` trace lands. The gate reads the
  panel's composite — if `:has-mismatch?` is truthy the dormant flag
  is dropped and the entry behaves like every other live panel.

  The lift here is intentionally a one-line override rather than a
  per-item subscribe — the composite already exists for the panel,
  re-using it keeps the reactive graph minimal."
  []
  (let [active (or @(rf/subscribe [:rf.causa/selected-panel])
                   registry/default-panel-id)
        hydration-awake? (boolean
                           (get @(rf/subscribe [:rf.causa/hydration-debugger-data])
                                :has-mismatch?))
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

(defn- stub-panel
  "Phase 1 'Coming soon' placeholder still used by every non-hero
  sidebar item until its own bead lands."
  [{:keys [label bead]}]
  [:main {:style {:flex             1
                  :overflow         "auto"
                  :padding          "24px"
                  :background       (:bg-2 tokens)
                  :color            (:text-primary tokens)
                  :font-family      "Inter, system-ui, -apple-system, Segoe UI, sans-serif"}}
   [:div {:style {:max-width "640px"}}
    [:h1 {:style {:font-size "16px" :font-weight 600 :margin "0 0 12px 0"
                  :color     (:text-primary tokens)}}
     label]
    [:p {:style {:font-size "14px" :line-height 1.5
                 :color     (:text-secondary tokens)
                 :margin    "0 0 16px 0"}}
     "Coming soon — this panel ships under "
     [:code {:style {:font-family "JetBrains Mono, ui-monospace, SF Mono, Menlo, monospace"
                     :font-size   "13px"
                     :color       (:accent-violet tokens)}}
      bead]
     " of the rf2-5aw5v Causa epic."]]])

(defn- canvas
  "Canvas — renders the active panel's content. Phase 2 mounts the
  live `:event-detail` panel; every other slot still renders the
  Phase 1 'Coming soon' stub.

  The match is on `:rf.causa/selected-panel`. When a row dispatches
  `:rf.causa/select-panel <id>` the registry sets `:selected-panel`
  on the `:rf/causa` frame's app-db and this canvas recomputes."
  []
  (let [selected (or @(rf/subscribe [:rf.causa/selected-panel])
                     registry/default-panel-id)
        item     (some #(when (= (:id %) selected) %) sidebar-items)]
    (case selected
      :event-detail [event-detail/event-detail-view]
      :time-travel  [time-travel/time-travel-view]
      :app-db       [app-db-diff/app-db-diff-view]
      :causality    [causality-graph/causality-graph-view]
      ;; ── effects panel begin ──
      :fx           [effects/effects-view]
      ;; ── effects panel end ──
      :flows        [flows/flows-view]
      :schemas      [schema-violation-timeline/schema-violation-timeline-view]
      :subs         [subscriptions/subscriptions-view]
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
      [stub-panel (or item {:label "Unknown panel"
                            :bead  "rf2-xxx"})])))

(defn- bottom-rail
  "Bottom rail (40px) — time-travel scrubber + frame info + issues
  badge. Per spec/007-UX-IA.md §The five regions item 5.

  Phase 1 stub: minimal frame-info text. Live scrubber lands with the
  time-travel panel bead."
  []
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
   [:span "◀◀  ────●────  ▶▶  (scrubber — rf2-xxx)"]
   [:span "epoch — / —"]])

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
