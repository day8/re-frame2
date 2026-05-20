(ns day8.re-frame2-causa.panels.machines-canvas.panel
  "Machines Canvas — Runtime L4 sub-domain tab (rf2-mkpnb).

  ## Why a separate tab

  Per Mike's cohesive-sub-domain rule (`ai/MEMORY` →
  `cohesive_subdomains_get_their_own_tab`): when a sub-domain inside
  an existing panel earns its own dominant view, it earns top-level
  L3 navigation parity. The interactive `machine-canvas/Chart`
  adapter (rf2-y3l8z, PR #1578) currently lives inside the Machines
  Inspector's per-machine focused-event sections — useful for the
  event-lens (`Machines` tab — `m`) but a poor home when the user's
  question is *'what does this machine LOOK like, independent of the
  spine'*. That browse verb earns its own tab.

  The split:

    - **Machines** (`m`) — event-driven inspector. BLANK when the
      focused event has no machine activity; per-machine section
      with topology + guards/actions + cancellation cascade +
      `:after` rings when it does. Spine-coupled lens.
    - **Machines Canvas** (`c`) — spine-INDEPENDENT canvas
      browser. Pick a machine from the left rail; the dominant view
      is the interactive chart (zoom / pan / fit + keyboard
      shortcuts) for that machine's full topology. No focused-event
      lens — the canvas always shows the machine's whole graph.

  Static mode already carries a comparable surface
  (`static/machines/topology.cljs`); this tab is its Runtime
  counterpart for users running with the Runtime chrome.

  ## Shape

  Master-detail. Per the Static Machines panel's existing layout
  (`static/machines/panel.cljs`):

      ┌──────────────────────┬────────────────────────────────────┐
      │ L4-left (~240px)     │  L4-right (fills)                  │
      │ ─ machine list       │  ─ chart canvas                    │
      │   (mono accent-      │     (zoom / pan / fit + keyboard;  │
      │   violet · selection │      no after-rings overlay; no    │
      │   glyph · state-     │      view-mode toggle)             │
      │   count chip)        │  ─ machine-id heading              │
      └──────────────────────┴────────────────────────────────────┘

  ## App-db slots

  Per-tab selection lives on `:rf.causa.machines-canvas/selected-id`
  in the `:rf/causa` frame's app-db. The selection slot is reactive
  (subscribe → rerender) but NOT persisted to localStorage v1 — the
  Static Machines panel already owns a persisted browse selection
  for the Static surface; the Runtime canvas tab's selection lives
  fresh-per-session. Persistence can land as a follow-on if usage
  signals demand.

  The viewport (zoom / pan) slot IS the shared
  `:rf.causa/machine-canvas :viewports` map that `panels/machine_
  canvas.cljs` already owns — same persistence + same per-machine
  scoping. If the user pans/zooms a machine in this tab, the
  Machines Inspector's per-machine section picks up the same
  viewport (and vice versa).

  ## Frame isolation

  Same discipline as every other Causa panel — the view body is
  pure hiccup with no Reagent / UIx / Helix references. Frame
  isolation comes from the enclosing
  `[rf/frame-provider {:frame :rf/causa}]` in `shell.cljs` (Runtime
  L4 mount path) or the `mount-machines-canvas!` mount fn (when the
  panel is embedded in isolation per spec/008)."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.chart.elk-layout :as elk-layout]
            [day8.re-frame2-causa.open-in-editor :as open-in-editor]
            [day8.re-frame2-causa.panel-registry :as panel-registry]
            [day8.re-frame2-causa.panels.machine-canvas :as machine-canvas]
            [day8.re-frame2-causa.panels.machine-inspector-helpers :as h]
            [day8.re-frame2-causa.panels.machines.topology-view :as topology-view]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens sans-stack mono-stack display-stack type-scale]]))

;; ---- helpers (pure) -----------------------------------------------------

(defn- machine-row-data
  "Project one row for the picker. Pure data → data."
  [machine-id definitions]
  (let [definition (get definitions machine-id)
        states     (when definition (get definition :states))]
    {:machine-id   machine-id
     :definition   definition
     :state-count  (if (map? states) (count states) 0)
     :source-coord (when (map? definition)
                     (or (:source-coord definition)
                         (:source definition)))}))

(defn- project-rows
  "Project the picker rows from `[machine-ids definitions]`. Sorted
  alphabetically by machine-id name so the order is deterministic
  across renders."
  [machine-ids definitions]
  (->> (or machine-ids [])
       (map #(machine-row-data % definitions))
       (sort-by (comp str :machine-id))
       vec))

;; ---- left-pane picker ---------------------------------------------------

(defn- picker-row
  "One picker row. Selection glyph + machine-id + state-count chip."
  [{:keys [machine-id state-count]} active?]
  [:button
   {:data-testid     (str "rf-causa-machines-canvas-picker-row-"
                          (when machine-id (subs (str machine-id) 1)))
    :data-machine-id (str machine-id)
    :data-active     (str (boolean active?))
    :role            "tab"
    :aria-selected   (str (boolean active?))
    :on-click        (fn [_]
                       (rf/dispatch
                         [:rf.causa.machines-canvas/select machine-id]
                         {:frame :rf/causa}))
    :title           (str (h/format-machine-id machine-id)
                          " — " state-count " state"
                          (if (= 1 state-count) "" "s"))
    :style {:display        "flex"
            :align-items    "center"
            :gap            "8px"
            :width          "100%"
            :padding        "6px 10px"
            :background     (if active? (:bg-3 tokens) "transparent")
            :border         "none"
            :border-left    (str "2px solid "
                                 (if active?
                                   (:accent-violet tokens)
                                   "transparent"))
            :color          (:text-primary tokens)
            :font-family    sans-stack
            :font-size      (:body-tight type-scale)
            :cursor         "pointer"
            :text-align     "left"}}
   [:span {:style {:color (if active?
                            (:accent-violet tokens)
                            (:text-tertiary tokens))
                   :font-family mono-stack
                   :font-size   "11px"
                   :width       "10px"}}
    (if active? "◉" "○")]
   [:span {:style {:color (:accent-violet tokens)
                   :font-family mono-stack
                   :font-size (:body-tight type-scale)
                   :flex 1
                   :overflow "hidden"
                   :text-overflow "ellipsis"
                   :white-space "nowrap"}}
    (h/format-machine-id machine-id)]
   [:span {:style {:color (:text-tertiary tokens)
                   :font-family mono-stack
                   :font-size (:micro type-scale)
                   :margin-left "auto"}}
    state-count " st"]])

(defn- picker
  "L4-left pane. List of registered machines + selection."
  []
  (let [machine-ids @(rf/subscribe [:rf.causa/registered-machines])
        definitions @(rf/subscribe [:rf.causa/machine-definitions])
        selected-id @(rf/subscribe [:rf.causa.machines-canvas/selected-id])
        rows        (project-rows machine-ids definitions)]
    [:nav {:data-testid "rf-causa-machines-canvas-picker"
           :role        "tablist"
           :aria-label  "Registered machines"
           :style {:display        "flex"
                   :flex-direction "column"
                   :height         "100%"
                   :overflow-y     "auto"
                   :background     (:bg-1 tokens)
                   :border-right   (str "1px solid " (:border-subtle tokens))}}
     (if (empty? rows)
       [:div {:data-testid "rf-causa-machines-canvas-picker-empty"
              :style {:padding "12px"
                      :color (:text-tertiary tokens)
                      :font-family sans-stack
                      :font-size (:caption type-scale)}}
        "No machines registered."]
       (into [:div {:data-testid "rf-causa-machines-canvas-picker-rows"
                    :style {:display "flex"
                            :flex-direction "column"}}]
             (for [{:keys [machine-id] :as row} rows]
               ^{:key (str machine-id)}
               (picker-row row (= machine-id selected-id)))))]))

;; ---- right-pane canvas --------------------------------------------------

(defn- canvas-header
  "Heading strip at the top of the canvas pane — machine-id +
  state-count + source-coord chip."
  [{:keys [machine-id state-count source-coord]}]
  [:header {:data-testid "rf-causa-machines-canvas-header"
            :data-machine-id (str machine-id)
            :style {:display "flex"
                    :align-items "center"
                    :gap "12px"
                    :padding "12px 16px"
                    :border-bottom (str "1px solid " (:border-subtle tokens))
                    :background (:bg-2 tokens)}}
   ;; rf2-5kfxe.8 — domain-coloured accent stripe (:green for Machines).
   ;; rf2-5kfxe.9 — display face (Fraunces) for L4 title contrast.
   [:h1 {:style (merge {:font-size "20px"
                        :font-family display-stack
                        :font-weight 600
                        :letter-spacing "-0.01em"
                        :margin 0
                        :color (:text-primary tokens)}
                       (t/accent-stripe-style :machines))}
    "Machines canvas"]
   (when machine-id
     [:span {:style {:color (:accent-violet tokens)
                     :font-family mono-stack
                     :font-size (:caption type-scale)
                     :margin-left "8px"}}
      (h/format-machine-id machine-id)])
   (when (and machine-id state-count (pos? state-count))
     [:span {:style {:color (:text-tertiary tokens)
                     :font-family mono-stack
                     :font-size (:micro type-scale)}}
      state-count " state" (when (not= 1 state-count) "s")])
   (when source-coord
     [:span {:style {:margin-left "auto"}}
      (or (open-in-editor/open-chip source-coord)
          [:span {:data-testid "rf-causa-machines-canvas-source-coord-text"
                  :style {:font-family mono-stack
                          :font-size (:micro type-scale)
                          :color (:text-tertiary tokens)}}])])])

(defn- canvas-body
  "L4-right canvas. Renders the interactive Chart for the selected
  machine. The Chart adapter owns zoom / pan / fit + keyboard
  shortcuts; this body is a thin wrapper that picks the definition
  + drives the ELK layout."
  [{:keys [machine-id definition]}]
  (let [direction  :tb
        positioned (when definition
                     (elk-layout/layout-or-fallback definition direction))
        engine     (if (and definition
                            (some? (elk-layout/cached-layout
                                     definition direction)))
                     "elk"
                     "layered")]
    (when definition
      (elk-layout/ensure-elk!
        (fn [_inst]
          (when (and (= :ready (elk-layout/elk-status))
                     (nil? (elk-layout/cached-layout
                             definition direction)))
            (elk-layout/compute-layout!
              definition direction
              (fn [chart-layout]
                (when chart-layout
                  (rf/dispatch
                    [:rf.causa/machine-chart-layout-pulse]
                    {:frame :rf/causa}))))))))
    [:div {:data-testid "rf-causa-machines-canvas-body"
           :data-machine-id (str machine-id)
           :data-layout-engine engine
           :style {:flex "1 1 auto"
                   :min-height "0"
                   :display "flex"
                   :flex-direction "column"
                   :overflow "hidden"
                   :background (:bg-1 tokens)
                   :position "relative"}}
     (cond
       (nil? machine-id)
       [:div {:data-testid "rf-causa-machines-canvas-no-selection"
              :style {:flex 1
                      :display "flex"
                      :align-items "center"
                      :justify-content "center"
                      :padding "24px"
                      :color (:text-tertiary tokens)
                      :font-family sans-stack
                      :font-size (:body type-scale)
                      :text-align "center"}}
        [:div
         [:p {:style {:margin "0 0 6px 0"}}
          "Pick a machine to inspect its topology."]
         [:p {:style {:margin 0 :font-size (:caption type-scale)}}
          "Zoom / pan / fit + keyboard shortcuts work on the canvas."]]]

       (nil? definition)
       [:div {:data-testid "rf-causa-machines-canvas-no-definition"
              :style {:padding "16px"
                      :color (:text-tertiary tokens)
                      :font-family sans-stack
                      :font-size (:caption type-scale)}}
        "Machine definition is not introspectable. The chart cannot render."]

       :else
       [:<>
        [machine-canvas/Chart
         {:positioned             positioned
          :machine-id             machine-id
          ;; No focused-event lens on this tab — this is a
          ;; spine-INDEPENDENT canvas browser. No from/to highlight,
          ;; no after-rings overlay, no view-mode toggle (the toggle
          ;; is a Runtime concept that belongs in the event-driven
          ;; Machines Inspector tab).
          :show-after-rings?      false
          :show-view-mode-toggle? false
          :on-state-click
          (fn [path]
            (rf/dispatch [:rf.causa.machines-canvas/state-clicked
                          {:machine-id machine-id :path path}]
                         {:frame :rf/causa}))}]
        ;; rf2-uwvyj — xyflow render (spec/021 §6.0 Path B). Sits
        ;; alongside the legacy ELK SVG chart while the xyflow path
        ;; proves out. Future bead can flip the legacy chart off; for
        ;; now both surfaces render so the operator can compare.
        [:div {:data-testid "rf-causa-machines-canvas-xyflow-section"
               :style {:padding "8px 16px"
                       :border-top (str "1px solid " (:border-subtle tokens))}}
         [:div {:style {:color (:text-tertiary tokens)
                        :font-family sans-stack
                        :font-size "10px"
                        :text-transform "uppercase"
                        :letter-spacing "0.5px"
                        :margin "0 0 6px 0"}}
          "xyflow (Path B)"]
         [topology-view/Topology
          {:machine-id machine-id
           :definition definition
           :height     "280px"
           :testid     "rf-causa-machines-canvas-xyflow"}]]])]))

;; ---- public Panel view --------------------------------------------------

(def ^:private left-pane-width "240px")

(rf/reg-view Panel
  "The Machines Canvas tab's root view. Master-detail with a machine
  picker on the left and the interactive canvas on the right.

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/causa`."
  []
  (let [machine-ids @(rf/subscribe [:rf.causa/registered-machines])
        definitions @(rf/subscribe [:rf.causa/machine-definitions])
        selected-id @(rf/subscribe [:rf.causa.machines-canvas/selected-id])
        ;; If no machine is explicitly selected, default to the first
        ;; row's machine-id so the canvas pane is populated on first
        ;; mount. The picker still renders the first row as inactive
        ;; until the user clicks — the default-pick is a render-time
        ;; convenience, not a state mutation.
        effective-id (or selected-id
                         (first (sort-by (comp str identity)
                                         (or machine-ids []))))
        row          (when effective-id
                       (machine-row-data effective-id definitions))]
    [:section {:data-testid "rf-causa-machines-canvas"
               :data-selected-machine-id (str effective-id)
               :style {:display          "flex"
                       :flex-direction   "row"
                       :height           "100%"
                       :background       (:bg-2 tokens)
                       :color            (:text-primary tokens)
                       :font-family      sans-stack
                       :font-size        (:body type-scale)}}
     ;; ---- left pane ----
     [:div {:data-testid "rf-causa-machines-canvas-left"
            :style {:flex          (str "0 0 " left-pane-width)
                    :min-width     left-pane-width
                    :max-width     left-pane-width
                    :height        "100%"
                    :overflow      "hidden"
                    :display       "flex"
                    :flex-direction "column"}}
      [picker]]
     ;; ---- right pane ----
     [:div {:data-testid "rf-causa-machines-canvas-right"
            :style {:flex        "1 1 auto"
                    :min-width   "0"
                    :height      "100%"
                    :display     "flex"
                    :flex-direction "column"
                    :background  (:bg-2 tokens)}}
      (canvas-header row)
      (canvas-body row)]]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Machines Canvas tab's Causa-side
  registrations. Registers:

    - `:rf.causa.machines-canvas/selected-id`   — per-tab selection sub
    - `:rf.causa.machines-canvas/select`        — selection write event
    - `:rf.causa.machines-canvas/state-clicked` — no-op stub for the
        chart's `:on-state-click`; future follow-on can wire a
        metadata rail or JUMP-to-Inspector verb.

  The tab itself is registered against the L4 tab registry under
  `:id :machines-canvas`, `:mnem c`, `:modes #{:runtime}`, `:order
  5`. Routing + Issues shift to orders 6 + 7 (see
  `panels/routing.cljs` + `panels/issues_ribbon.cljs`)."
  []
  (rf/reg-sub :rf.causa.machines-canvas/selected-id
    (fn [db _query]
      (get db :rf.causa.machines-canvas/selected-id)))

  (rf/reg-event-db :rf.causa.machines-canvas/select
    (fn [db [_ machine-id]]
      (assoc db :rf.causa.machines-canvas/selected-id machine-id)))

  ;; No-op landing slot for the chart's `:on-state-click` dispatch so
  ;; the click doesn't emit a `:rf.warning/no-handler` trace. Wiring
  ;; a metadata rail / JUMP-to-Inspector verb is a follow-on bead.
  (rf/reg-event-db :rf.causa.machines-canvas/state-clicked
    (fn [db [_ _payload]] db))

  ;; rf2-mkpnb — register the Runtime Machines Canvas tab with the
  ;; internal L4 tab registry. Sits between Machines (4) and Routing
  ;; (now 6) so the two machine sub-domain tabs are adjacent in the
  ;; tab bar.
  (panel-registry/reg-l4-tab!
    {:id    :machines-canvas
     :label "Machines Canvas"
     :mnem  "c"
     :modes #{:runtime}
     :order 5
     :panel Panel})

  nil)
