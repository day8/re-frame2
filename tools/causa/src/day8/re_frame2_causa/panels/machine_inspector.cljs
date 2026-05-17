(ns day8.re-frame2-causa.panels.machine-inspector
  "Machine Inspector panel — Phase 5+ (rf2-r9f9u, parent rf2-5aw5v).

  Per `tools/causa/spec/003-Machine-Inspector.md` this panel renders a
  Stately-quality state-chart per registered machine. The chart
  component itself lives in `tools/machines-viz/` per
  `tools/machines-viz/spec/API.md`; Causa embeds it as a thin wrapper
  that adds the machine picker + transition-history ribbon + (future)
  source-coord jump affordance.

  ## MachineChart placeholder

  **The `tools/machines-viz/` implementation does not exist yet.**
  Only the spec scaffold landed via rf2-x50eu (the `MachineChart`
  prop contract + share-URL encoding are normative). This panel
  embeds the contract via a **placeholder component** that renders
  the prop summary as text — the rendering is the chart component's
  job, and the contract is what matters here. When the impl ships
  the placeholder swaps for `[viz/MachineChart prop-map]` without
  touching the rest of the panel chrome.

  Per spec/003-Machine-Inspector.md §Embedding posture the dependency
  arrow is `tools/causa/ → tools/machines-viz/ → implementation/
  machines/`; nothing reverses. The placeholder lives inside Causa
  for now because there is nothing to depend on yet; the moment
  machines-viz publishes a CLJS bundle this ns swaps the placeholder
  for the require.

  ## What this panel shows (v1)

    1. **Machine picker** — a dropdown over `(rf/machines)`. Switching
       the selection re-binds the chart + transition-history ribbon to
       the new machine.

    2. **MachineChart placeholder** — a text/JSON summary of the prop
       map that the real component would consume (per
       `tools/machines-viz/spec/API.md` §Props). Includes a banner
       calling out the deferred impl + the source-of-truth links.

    3. **Active state** — read off the snapshot at
       `[:rf/machine <id>]`, surfaced on the picker + the placeholder.

    4. **Transition history ribbon** — filter the Causa trace buffer
       to `:rf.machine/transition` events for the selected machine.
       Click a row → `:rf.causa/select-dispatch-id` (pivots to
       event-detail, parity with every other Causa cross-panel jump).

    5. **Empty state** — when no machines are registered.

  ## What v1 does NOT include

    - **`:invoke-all` viz / `:after` countdown rings / share
      affordance** — those live in `tools/machines-viz/` per Spec 003
      §Embedding posture. This panel embeds the component; the
      rendering is the component's job.
    - **Source-coord jumps** — the cross-panel jump API hasn't
      stabilised (same v1 deferral the routes panel carries).
    - **Auto-pan toggle** — lives on the chart component itself per
      `tools/machines-viz/spec/API.md` §Props (`:auto-pan?`); the
      panel header gets the toggle when the component ships.

  ## Pure hiccup (rf2-tijr)

  Same contract as every other Causa panel — the view is pure hiccup,
  no Reagent / UIx / Helix references. Frame isolation comes from the
  enclosing `[rf/frame-provider {:frame :rf/causa}]` in `shell.cljs`.
  Every `subscribe` / `dispatch` here resolves to `:rf/causa`.

  ## Helpers

  All pure-data logic — `project-data`, `project-machine-rows`,
  `project-transitions`, `chart-props`, ... — lives in
  `machine_inspector_helpers.cljc` so the algebra runs under the JVM
  unit-test target. The view here is a thin renderer that reads the
  `:rf.causa/machine-inspector-data` composite sub."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.chart.layout :as chart-layout]
            [day8.re-frame2-causa.chart.elk-layout :as elk-layout]
            [day8.re-frame2-causa.chart.svg :as chart-svg]
            [day8.re-frame2-causa.panels.machine-inspector-helpers :as h]
            [day8.re-frame2-causa.panels.machine-inspector-cluster :as cluster]
            [day8.re-frame2-causa.panels.machine-inspector-cluster-helpers :as ch]
            [day8.re-frame2-causa.panels.machine-inspector-sim :as sim]
            [day8.re-frame2-causa.panels.machine-inspector-arc :as arc]
            [day8.re-frame2-causa.panels.machine-after-rings :as after-rings]
            [day8.re-frame2-causa.panels.machine-inspector-scrubber :as scrubber]
            [day8.re-frame2-causa.share :as share]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- machine picker -----------------------------------------------------

(defn- machine-picker
  "Dropdown over the registered machines. Per spec/003-Machine-
  Inspector.md §Selection and switching the picker shows the
  machine-id + current state for each option; selecting fires
  `:rf.causa/select-machine-id`."
  [rows selected-id]
  [:div {:data-testid "rf-causa-machine-inspector-picker"
         :style       {:display "flex"
                       :align-items "center"
                       :gap "8px"
                       :padding "10px 16px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))}}
   [:label {:style {:color (:text-tertiary tokens)
                    :font-family sans-stack
                    :font-size "10px"
                    :text-transform "uppercase"
                    :letter-spacing "0.5px"}}
    "Machine"]
   [:select {:data-testid "rf-causa-machine-inspector-picker-select"
             :value       (if selected-id (str selected-id) "")
             :on-change   (fn [e]
                            (let [v   (-> e .-target .-value)
                                  row (some #(when (= v (str (:machine-id %))) %) rows)]
                              (when-let [id (:machine-id row)]
                                (rf/dispatch [:rf.causa/select-machine-id id] {:frame :rf/causa}))))
             :style       {:flex 1
                           :background (:bg-3 tokens)
                           :border (str "1px solid " (:border-default tokens))
                           :color (:text-primary tokens)
                           :padding "4px 8px"
                           :border-radius "4px"
                           :font-family mono-stack
                           :font-size "12px"
                           :cursor "pointer"}}
    (for [{:keys [machine-id state]} rows]
      ^{:key (str machine-id)}
      [:option {:value (str machine-id)}
       (str (h/format-machine-id machine-id)
            "   "
            (h/format-state state))])]
   [:span {:style {:color (:text-tertiary tokens)
                   :font-family mono-stack
                   :font-size "11px"}}
    (str (count rows) " machine" (when-not (= 1 (count rows)) "s"))]])

;; ---- placeholder chart --------------------------------------------------

(defn- placeholder-banner
  "Header above the chart canvas. Per spec/003-Machine-Inspector.md
  §Definition view this banner names the current selection and (when
  no live instances exist) nudges Sim mode. rf2-v869p Phase 2 wires
  the toggle through to the `:rf.causa/sim-start` / `sim-stop` events
  (rf2-2tkza Phase 1 shipped the toggle stub).

  The fn keeps its historical name (`placeholder-banner`) so existing
  tests pin it via testid; the user-visible text reflects what is
  now actually shown — the rendered chart with live highlighting."
  [{:keys [machine-id state instance-count definition sim-active?]}]
  [:div {:data-testid "rf-causa-machine-inspector-placeholder-banner"
         :style       {:padding "10px 12px"
                       :margin "10px 12px 0 12px"
                       :display "flex"
                       :align-items "center"
                       :justify-content "space-between"
                       :gap "12px"
                       :border (str "1px solid "
                                    (if sim-active?
                                      (:yellow tokens)
                                      (:border-subtle tokens)))
                       :border-radius "4px"
                       :background (:bg-1 tokens)
                       :color (:text-secondary tokens)
                       :font-family sans-stack
                       :font-size "11px"
                       :line-height 1.5}}
   [:div
    [:strong {:style {:color (if sim-active?
                               (:yellow tokens)
                               (:accent-violet tokens))
                      :font-weight 600
                      :font-size "11px"
                      :text-transform "uppercase"
                      :letter-spacing "0.5px"}}
     (if sim-active? "Sim ●  Definition view" "Definition view")]
    [:p {:style {:margin "4px 0 0 0"
                 :color (:text-tertiary tokens)}}
     (cond
       sim-active?
       (str (h/format-machine-id machine-id)
            " — sim mode active; clicks walk the topology against a clone")

       (zero? (or instance-count 0))
       (str (h/format-machine-id machine-id)
            " — 0 live instances "
            (if definition
              "(toggle Sim ▶︎ to walk topology)"
              "(no introspectable definition — Sim unavailable)"))

       :else
       (str (h/format-machine-id machine-id)
            " — current state: "
            (h/format-state state)))]]
   ;; Sim toggle — active when a definition is available (rf2-v869p Phase 2).
   (let [enabled? (some? definition)]
     [:button
      {:data-testid "rf-causa-machine-inspector-sim-toggle"
       :on-click    (when enabled?
                      (fn [_]
                        (if sim-active?
                          (rf/dispatch
                            [:rf.causa/sim-stop {:machine-id machine-id}]
                            {:frame :rf/causa})
                          (rf/dispatch
                            [:rf.causa/sim-start
                             {:machine-id machine-id
                              :definition definition}]
                            {:frame :rf/causa}))))
       :title       (cond
                      (not enabled?) "Sim requires an introspectable machine definition"
                      sim-active?    "Exit sim — disposes the cloned snapshot"
                      :else          "Enter sim — clones the machine + walks transitions interactively")
       :data-active (str (boolean sim-active?))
       :style       {:color (cond
                              (not enabled?) (:text-tertiary tokens)
                              sim-active?    "#1c2030"
                              :else          (:yellow tokens))
                     :background (cond
                                   (not enabled?) "transparent"
                                   sim-active?    (:yellow tokens)
                                   :else          "transparent")
                     :font-family mono-stack
                     :font-size "11px"
                     :font-weight 600
                     :padding "3px 10px"
                     :border (str "1px solid "
                                  (cond
                                    (not enabled?) (:border-subtle tokens)
                                    :else          (:yellow tokens)))
                     :border-radius "10px"
                     :cursor (if enabled? "pointer" "not-allowed")
                     :opacity (if enabled? 1 0.6)}}
      (cond
        sim-active?    "Sim ●"
        enabled?       "Sim ▶︎"
        :else          "Sim ○")])])

(defn- prop-row
  "One labelled row inside the prop summary (used for the metadata
  rail when no definition is introspectable)."
  [label value]
  [:div {:data-testid (str "rf-causa-machine-inspector-prop-" label)
         :style       {:display "flex"
                       :flex-direction "column"
                       :gap "2px"
                       :padding "6px 0"
                       :border-bottom (str "1px dotted " (:border-subtle tokens))}}
   [:span {:style {:color (:text-tertiary tokens)
                   :font-family sans-stack
                   :font-size "10px"
                   :text-transform "uppercase"
                   :letter-spacing "0.5px"}}
    label]
   [:code {:style {:color (:text-primary tokens)
                   :font-family mono-stack
                   :font-size "12px"
                   :word-break "break-all"}}
    value]])

(defn- chart-fallback
  "Rendered when the panel has a selection but no machine-definition
  to layout (e.g. an event-handler that lacks the introspection
  metadata)."
  [props]
  [:div {:data-testid "rf-causa-machine-inspector-placeholder"
         :style       {:padding "12px"
                       :background (:bg-1 tokens)
                       :border (str "1px solid " (:border-subtle tokens))
                       :border-radius "4px"
                       :margin "12px"}}
   [prop-row "machine-id" (h/format-machine-id (:machine-id props))]
   [prop-row "frame-id"   (h/format-machine-id (:frame-id props))]
   (when-let [override (:current-state-override props)]
     [prop-row "current-state-override"
      (pr-str (select-keys override [:state :data]))])
   [:div {:style {:margin-top "10px"
                  :font-family sans-stack
                  :font-size "11px"
                  :color (:text-tertiary tokens)}}
    "No introspectable definition available — chart cannot render."]])

(defn- placeholder-chart
  "Renders the live MachineChart (Causa-internal SVG primitive at
  `day8.re-frame2-causa.chart.{layout,svg}`).

  Phase 1 (rf2-2tkza): the chart reads the registered definition from
  the composite sub's `:definition` slot, lays it out via the layered
  primitive in `chart/layout.cljc`, and renders to hiccup SVG via
  `chart/svg.cljc`. Live state highlighting reflects the snapshot's
  `:state` slot. The fn keeps the historical name `placeholder-chart`
  so existing tests pin it via testid; the user-visible surface is now
  the real chart, not a prop summary.

  Phase 2 (rf2-v869p): when `sim-snapshot` is non-nil the chart's
  highlight is sourced from the sim snapshot and the `:sim?` flag is
  flipped on `chart/svg.cljc`'s renderer so the highlight palette
  shifts from cyan (live) to amber (sim) per design §5.

  Phase 5 (rf2-nqw0v): when `arc-highlight-state` is non-nil (the
  user has scrubbed back through the instance's state-arc), the chart
  highlight is overridden to the scrubbed-to state and the per-
  instance arc overlay mounts on top of the chart SVG."
  ([props] (placeholder-chart props nil nil))
  ([props sim-snapshot] (placeholder-chart props sim-snapshot nil))
  ([props sim-snapshot arc-highlight-state]
   (cond
     (nil? props)
     [:div {:data-testid "rf-causa-machine-inspector-placeholder-empty"
            :style       {:padding "16px"
                          :color (:text-tertiary tokens)
                          :font-family sans-stack
                          :font-size "12px"}}
      "No machine selected — nothing to chart."]

     (nil? (:definition props))
     (chart-fallback props)

     :else
     (let [definition   (:definition props)
           sim?         (some? sim-snapshot)
           override     (:current-state-override props)
           ;; Phase 5: arc-highlight-state wins over the live snapshot
           ;; when the user has scrubbed back. Sim still wins over
           ;; arc — sim is the user's explicit "I'm walking topology"
           ;; intent, the arc is a historical view.
           state-value  (cond
                          sim?                        (:state sim-snapshot)
                          (some? arc-highlight-state) arc-highlight-state
                          :else                       (:state override))
           highlight-id (chart-layout/highlight-id state-value)
           ;; Phase 4 (rf2-m7co9): ELK.js layout with layered fallback.
           ;;
           ;; The layout direction defaults to :tb; per-machine override
           ;; lives in the panel app-db slot for a follow-on bead.
           ;;
           ;; Kick the loader (idempotent — second call after success
           ;; is a no-op fast-path). When the loader resolves and the
           ;; cache is empty, kick a layout pass; the layout's `.then`
           ;; writes the cache + dispatches the pulse event that re-
           ;; renders this view via the subscribe-driven recompute.
           direction    :tb
           _            (elk-layout/ensure-elk!
                          (fn [_inst]
                            (when (elk-layout/elk-status)
                              ;; If ELK is ready and the cache is empty
                              ;; for this combination, populate it.
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
           ;; When ELK is ready+cached → ELK. Otherwise → layered
           ;; fallback. Both produce the same `{:nodes :edges :width
           ;; :height :initial-id}` shape so the SVG renderer is
           ;; layout-engine-agnostic.
           positioned   (elk-layout/layout-or-fallback definition direction)
           engine       (if (some? (elk-layout/cached-layout definition direction))
                          "elk"
                          "layered")]
       [:div {:data-testid "rf-causa-machine-inspector-chart"
              :data-machine-id (str (:machine-id props))
              :data-highlight-id (or highlight-id "")
              :data-sim-active (str sim?)
              :data-arc-scrubbing (str (some? arc-highlight-state))
              :data-layout-engine engine
              :style       {:margin "12px"
                            :padding "12px"
                            :background (:bg-1 tokens)
                            :border (str "1px solid "
                                         (cond
                                           sim?                        (:yellow tokens)
                                           (some? arc-highlight-state) (:accent-violet tokens)
                                           :else                       (:border-subtle tokens)))
                            :border-radius "4px"
                            :overflow "auto"
                            ;; Phase 5: position-relative so the arc
                            ;; overlay can absolute-position itself
                            ;; over the chart SVG.
                            :position "relative"}}
        (chart-svg/render
          positioned
          {:highlight-id   highlight-id
           :sim?           sim?
           :on-state-click (fn [path]
                             (rf/dispatch
                               [:rf.causa/machine-state-clicked
                                {:machine-id (:machine-id props)
                                 :path       path}]
                               {:frame :rf/causa}))})
        ;; Phase 5: per-instance arc overlay. Hidden in sim mode (the
        ;; arc represents the LIVE trajectory; sim is a clone walk).
        (when (not sim?)
          [arc/ArcOverlay positioned])
        ;; rf2-7hwwe: `:after` countdown rings — drawn AROUND every
        ;; state-node that currently has an armed `:after` timer. Live
        ;; mode animates from full → empty; retrospective freezes at
        ;; the scrubber's anchor time. Hidden in sim mode for the same
        ;; reason the arc is — sim is a clone walk, not the live
        ;; trajectory whose timers the trace bus reflects.
        (when (not sim?)
          [after-rings/AfterRingsOverlay positioned])]))))

;; ---- transition history ribbon ------------------------------------------

(defn- transition-entry
  "One transition-history ribbon entry. Per spec/003-Machine-
  Inspector.md §Transition history ribbon clicking the entry jumps to
  event-detail for that transition (parity with the Issues ribbon /
  Trace panel)."
  [{:keys [id from to event dispatch-id microstep?]}]
  [:li {:data-testid (str "rf-causa-machine-inspector-transition-" id)
        :on-click    (when dispatch-id
                       #(rf/dispatch [:rf.causa/select-dispatch-id dispatch-id] {:frame :rf/causa}))
        :title       (h/format-event event)
        :style       {:display "inline-flex"
                      :align-items "center"
                      :gap "6px"
                      :padding (if microstep? "3px 8px 3px 16px" "5px 10px")
                      :margin "3px"
                      :background (if microstep?
                                    (:bg-1 tokens)
                                    (:bg-3 tokens))
                      :border (str "1px solid " (:border-subtle tokens))
                      :border-radius "3px"
                      :cursor (if dispatch-id "pointer" "default")
                      :font-family mono-stack
                      :font-size "11px"
                      :color (:text-primary tokens)
                      :white-space "nowrap"}}
   (when microstep?
     [:span {:style {:color (:text-tertiary tokens)
                     :font-size "10px"}}
      "↳"])
   [:span (h/format-state from)]
   [:span {:style {:color (:accent-violet tokens)}} "→"]
   [:span (h/format-state to)]])

(defn- transition-ribbon
  "Horizontal scrubbable list of transitions for the selected
  machine. Per spec/003-Machine-Inspector.md §Transition history
  ribbon — the v1 surface caps at 200 entries (per spec §Performance);
  the helper returns the full projection and the view applies the
  cap here so tests can assert against either shape."
  [transitions]
  (let [capped (h/cap-transitions transitions)]
    [:section {:data-testid "rf-causa-machine-inspector-ribbon"
               :style       {:border-top (str "1px solid " (:border-default tokens))
                             :background (:bg-2 tokens)}}
     [:header {:style {:padding "8px 12px"
                       :display "flex"
                       :align-items "center"
                       :justify-content "space-between"
                       :background (:bg-3 tokens)
                       :border-bottom (str "1px solid " (:border-subtle tokens))}}
      [:span {:style {:font-family sans-stack
                      :font-size "12px"
                      :font-weight 600
                      :color (:text-primary tokens)}}
       "Transition history"]
      [:span {:style {:color (:text-tertiary tokens)
                      :font-family mono-stack
                      :font-size "11px"}}
       (str (count capped)
            (if (< (count capped) (count transitions))
              (str " of " (count transitions))
              "")
            " transition"
            (when-not (= 1 (count capped)) "s"))]]
     (if (empty? capped)
       [:div {:data-testid "rf-causa-machine-inspector-ribbon-empty"
              :style {:padding "12px"
                      :color (:text-tertiary tokens)
                      :font-family sans-stack
                      :font-size "12px"}}
        "No transitions recorded for this machine yet."]
       (into [:ul {:data-testid "rf-causa-machine-inspector-ribbon-list"
                   :style {:list-style "none"
                           :margin 0
                           :padding "6px 8px"
                           :overflow-x "auto"
                           :white-space "nowrap"}}]
             (for [row capped]
               ^{:key (:id row)}
               (transition-entry row))))]))

;; ---- instance tabs (UC2 Mode B foundation) -----------------------------

(defn- instance-tabs
  "Renders the inline instance-tabs strip across the top of the
  diagram per spec/003-Machine-Inspector.md §UC2 Mode B. Each tab
  shows the instance id and its current state; clicking re-focuses.

  Phase 1 scaffold (rf2-2tkza): re-frame2's snapshot surface today is
  one snapshot per machine-id (`[:rf/machines <id>]`); explicit
  multi-instance via `:rf.machine/spawn` lands in a follow-on bead.
  The strip renders the single registered snapshot as the focused
  instance so the Mode B chrome is in place when the upstream
  multi-instance surface ships."
  [{:keys [machine-id state]} instance-count mode]
  (when (and (= mode :mode-b) (pos? instance-count))
    [:div {:data-testid "rf-causa-machine-inspector-instance-tabs"
           :data-mode (name mode)
           :style {:display "flex"
                   :align-items "center"
                   :gap "8px"
                   :padding "6px 12px"
                   :background (:bg-3 tokens)
                   :border-bottom (str "1px solid " (:border-subtle tokens))
                   :font-family mono-stack
                   :font-size "11px"
                   :overflow-x "auto"
                   :white-space "nowrap"}}
     (for [idx (range instance-count)]
       ^{:key idx}
       [:span {:data-testid (str "rf-causa-machine-inspector-instance-tab-" idx)
               :style {:padding "3px 10px"
                       :background (if (zero? idx) (:bg-1 tokens) "transparent")
                       :border (str "1px solid "
                                    (if (zero? idx)
                                      (:cyan tokens)
                                      (:border-subtle tokens)))
                       :border-radius "10px"
                       :color (:text-primary tokens)
                       :cursor "pointer"}}
        (str "● " (h/format-machine-id machine-id)
             "  " (h/format-state state))])]))

(defn view-mode
  "Per spec/003-Machine-Inspector.md §UC2 Mode A/B/C — pick the mode
  based on live instance count. Pure fn for unit-testability; not
  marked private so the view-test suite can pin the classifier
  directly without relying on `data-view-mode` round-trips.

  This is the *auto* classification — Mode A when there are no live
  instances (definition-only view), Mode B for a handful, Mode C when
  the population outgrows the per-tab strip. The Mode C threshold here
  is the back-compat conservative one (4+ instances); the panel's mode
  resolver below honours the user's forced selection and falls back to
  the cluster-helpers' `mode-c-suggested?` threshold (10 by default)
  for the auto-switch hint surfaced via the mode tab strip."
  [instance-count]
  (cond
    (or (nil? instance-count) (zero? instance-count)) :mode-a
    (<= instance-count 3) :mode-b
    :else :mode-c))

(defn resolve-mode
  "Combine the auto-classification with the user's forced selection.
  Pure fn for unit-testability.

  `forced` is one of `:mode-a / :mode-b / :mode-c / nil`. Nil falls
  back to `view-mode`. When a force is set but instance-count = 0 the
  resolver still honours the user's pick so the cluster view remains
  reachable in the empty-population case (useful for the test
  override path + future spawn workflow)."
  [instance-count forced]
  (if (some? forced)
    forced
    (view-mode instance-count)))

;; ---- mode tab strip (UC2 Mode A | B | C selector) ---------------------

(defn- mode-tab-strip
  "Three-tab strip across the header that lets the user force Mode A
  (definition), Mode B (instance tabs), or Mode C (cluster view) per
  spec/003-Machine-Inspector.md §UC2. Auto-classification still drives
  the default; clicking a tab sets a forced mode that overrides the
  auto pick. Clicking the active tab clears the force (back to auto)."
  [resolved-mode forced-mode instance-count]
  (let [tabs [{:id :mode-a :label "Definition"}
              {:id :mode-b :label "Instances"}
              {:id :mode-c :label "Cluster"}]
        suggest-c? (ch/mode-c-suggested? instance-count)]
    [:div {:data-testid "rf-causa-machine-inspector-mode-strip"
           :data-resolved-mode (name resolved-mode)
           :data-forced-mode (when forced-mode (name forced-mode))
           :data-suggest-mode-c (str suggest-c?)
           :style {:display "flex"
                   :align-items "center"
                   :gap "4px"
                   :padding "6px 12px"
                   :background (:bg-3 tokens)
                   :border-bottom (str "1px solid " (:border-subtle tokens))}}
     [:span {:style {:color (:text-tertiary tokens)
                     :font-family sans-stack
                     :font-size "10px"
                     :text-transform "uppercase"
                     :letter-spacing "0.5px"
                     :margin-right "6px"}}
      "View"]
     (for [{:keys [id label]} tabs]
       (let [active? (= id resolved-mode)
             ;; Suggest Mode C visually when the population is large
             ;; but the user hasn't already forced something.
             suggest? (and (= id :mode-c) suggest-c? (not active?))]
         ^{:key (name id)}
         [:button
          {:data-testid (str "rf-causa-machine-inspector-mode-tab-" (name id))
           :data-active (str active?)
           :on-click (fn [_]
                       (rf/dispatch
                         [:rf.causa/set-forced-machine-mode
                          (if (and active? (= forced-mode id))
                            nil  ;; clicking active forced tab → auto
                            id)]
                         {:frame :rf/causa}))
           :style {:padding "3px 10px"
                   :background (cond active?    (:bg-1 tokens)
                                     suggest?   "rgba(67, 195, 208, 0.10)"
                                     :else      "transparent")
                   :border (str "1px solid "
                                (cond active?  (:cyan tokens)
                                      suggest? (:cyan tokens)
                                      :else    (:border-subtle tokens)))
                   :color (cond active?  (:cyan tokens)
                                suggest? (:cyan tokens)
                                :else    (:text-secondary tokens))
                   :border-radius "10px"
                   :font-family mono-stack
                   :font-size "11px"
                   :font-weight (if active? 600 400)
                   :cursor "pointer"}}
          label
          (when suggest?
            [:span {:style {:margin-left "4px" :font-size "9px"}}
             "●"])]))
     [:span {:style {:flex 1}}]
     (when (and suggest-c? (not= :mode-c resolved-mode))
       [:span {:data-testid "rf-causa-machine-inspector-mode-suggest"
               :style {:color (:cyan tokens)
                       :font-family sans-stack
                       :font-size "10px"}}
        (str instance-count
             " instances — Cluster view recommended")])]))

;; ---- empty state --------------------------------------------------------

(defn- empty-state
  "Rendered when `(rf/machines)` returns nothing — either the host
  app has not yet called `reg-machine`, or `day8/re-frame2-machines`
  is not on the classpath. Per spec/003-Machine-Inspector.md §Empty
  state."
  []
  [:div {:data-testid "rf-causa-machine-inspector-empty"
         :style       {:padding "16px"
                       :color (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size "13px"}}
   [:p {:style {:margin "0 0 8px 0"}}
    "No machines registered."]
   [:p {:style {:margin 0
                :font-size "12px"}}
    "Register a machine with "
    [:code {:style {:font-family mono-stack :color (:accent-violet tokens)}}
     "rf/reg-machine"]
    " and it will appear here with its live state, transition history, "
    "and any :after countdowns."]])

;; ---- public view --------------------------------------------------------

;; ---- share button (Phase 5, rf2-nqw0v) ---------------------------------

(defn- share-button
  "Top-right Share button in the panel toolbar. Opens the Share
  modal (mounted at the shell root). Per spec §Share affordance the
  button is enabled whenever a machine is registered (the empty-state
  branch hides it implicitly — the empty-state replaces the toolbar)."
  []
  [:button
   {:data-testid "rf-causa-machine-inspector-share-button"
    :on-click    (fn [_]
                   (rf/dispatch [:rf.causa/share-modal-open] {:frame :rf/causa}))
    :title       "Share this view (URL with focus + mode + scrubber)"
    :style       {:background "transparent"
                  :border (str "1px solid " (:border-default tokens))
                  :color (:accent-violet tokens)
                  :font-family sans-stack
                  :font-size "11px"
                  :font-weight 600
                  :padding "4px 12px"
                  :border-radius "10px"
                  :cursor "pointer"
                  :white-space "nowrap"}}
   "⤴ Share"])

(rf/reg-view Panel
  "The Machine Inspector panel's root view. Subscribes to
  `:rf.causa/machine-inspector-data` and renders either the empty
  state (no machines registered) or the picker + chart placeholder +
  transition-history ribbon."
  []
  (let [{:keys [machines total selected selected-id chart-props transitions empty-kind]}
        @(rf/subscribe [:rf.causa/machine-inspector-data])
        ;; Phase 3 (rf2-juon8): instance-count is the length of the
        ;; per-machine instances vector. Production reads through the
        ;; Phase-1 single-snapshot widening; test override surfaces a
        ;; richer multi-instance projection so Mode C is exercisable
        ;; before spawn lands upstream.
        instances      @(rf/subscribe [:rf.causa/machine-instances])
        instance-count (count (or instances []))
        ;; The user's forced-mode override; nil = auto (defer to
        ;; `view-mode`). Set via the mode tab strip below.
        forced-mode    @(rf/subscribe [:rf.causa/forced-machine-mode])
        mode           (resolve-mode instance-count forced-mode)
        ;; Phase 2 (rf2-v869p): pull sim state via the per-machine sub.
        ;; When active, the chart highlight + banner shift to sim mode
        ;; and the side rail mounts between the chart and the ribbon.
        sim-state      @(rf/subscribe [:rf.causa/sim-state])
        sim-active?    (boolean (:active? sim-state))
        sim-snapshot   (when sim-active? (:snapshot sim-state))
        ;; Phase 5 (rf2-nqw0v): arc + scrubber + share.
        ;;
        ;; `arc-data` powers the per-instance state-arc overlay; the
        ;; scrubber-position governs which slice the chart highlight
        ;; shows. Hidden in Mode A (no instance) and in sim mode (sim
        ;; is its own walking-clone surface).
        arc-data           @(rf/subscribe [:rf.causa/machine-arc-data])
        scrubber-position  @(rf/subscribe [:rf.causa/machine-scrubber-position])
        arc-active?        (and (not sim-active?)
                                (contains? #{:mode-b :mode-c} mode)
                                (seq arc-data))
        arc-highlight      (when arc-active?
                             @(rf/subscribe
                                [:rf.causa/machine-arc-highlight-state]))]
    [:section {:data-testid "rf-causa-machine-inspector"
               :data-view-mode (name mode)
               :data-instance-count instance-count
               :data-forced-mode (when forced-mode (name forced-mode))
               :data-sim-active (str sim-active?)
               :data-arc-active (str (boolean arc-active?))
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     [:header {:data-testid "rf-causa-machine-inspector-header"
               :style {:padding "16px 16px 8px 16px"
                       :display "flex"
                       :align-items "flex-start"
                       :justify-content "space-between"
                       :gap "12px"}}
      [:div
       [:h1 {:style {:font-size "16px"
                     :font-weight 600
                     :margin 0
                     :color (:text-primary tokens)}}
        "Machine inspector"]
       [:p {:style {:font-size "12px"
                    :color     (:text-tertiary tokens)
                    :margin    "4px 0 0 0"}}
        "Pick a machine to inspect its current state and recent transitions."]]
      ;; Share button — visible whenever the panel has more than empty
      ;; state. Empty-state branch hides the whole inner div below so
      ;; the button only appears when there's something to share.
      (when (not= :no-machines empty-kind)
        (share-button))]
     (if (= :no-machines empty-kind)
       (empty-state)
       [:div {:style {:flex 1 :display "flex" :flex-direction "column"
                      :overflow "hidden"}}
        [machine-picker machines selected-id]
        [mode-tab-strip mode forced-mode instance-count]
        [instance-tabs (or selected {}) instance-count mode]
        [:div {:style {:flex 1 :overflow "auto"}}
         (placeholder-banner
           {:machine-id     (:machine-id selected)
            :state          (:state selected)
            :instance-count instance-count
            :definition     (:definition selected)
            :sim-active?    sim-active?})
         (placeholder-chart chart-props sim-snapshot arc-highlight)
         ;; Phase 5: scrubber strip beneath the chart. Visible whenever
         ;; the arc has at least one step (i.e. ≥1 transition or an
         ;; initial-state-only arc).
         (when arc-active?
           [scrubber/ScrubberStrip])
         (when (= :mode-c mode)
           [cluster/ClusterView])
         (when sim-active?
           [sim/SimSideRail])]
        (transition-ribbon transitions)])]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Machine Inspector panel's Causa-side
  registrations (Phase 5+, rf2-r9f9u)."
  []
  ;; ---- Phase 5+ (rf2-r9f9u) — Machine Inspector panel --------------
  ;;
  ;; Per `tools/causa/spec/003-Machine-Inspector.md` the panel
  ;; surfaces every registered machine (via `(rf/machines)` — Spec
  ;; 005 §Querying machines), the live `:rf/machine` snapshot per id
  ;; (via the framework `:rf/machine` sub — Spec 005 §Subscribing to
  ;; machines), and the Causa trace buffer's `:rf.machine/transition`
  ;; slice for the selected machine.
  ;;
  ;; The chart component itself lives in `tools/machines-viz/` per
  ;; `tools/machines-viz/spec/API.md`. At v1 the impl is deferred
  ;; (only the scaffold landed via rf2-x50eu); the panel embeds the
  ;; prop contract through a placeholder component (see panel ns
  ;; docstring for the swap-when-impl-ships plan).
  ;;
  ;; Tests stub the registered-machine + snapshot surfaces by
  ;; writing override slots to Causa's app-db (mirrors the routes
  ;; panel's `:rf.causa/set-registered-routes-override-for-test`
  ;; pattern) so the JVM + node-test suites can drive the projection
  ;; without booting a host with `rf/reg-machine` calls. Production
  ;; paths read through `rf/machines` + `subscribe [:rf/machine id]`
  ;; directly.
  ;;
  ;; Shape of `:rf.causa/machine-inspector-data`:
  ;;
  ;;     {:machines     [<machine-row> ...]
  ;;      :total        <int>
  ;;      :selected-id  <id-or-nil>
  ;;      :selected     <row-or-nil>
  ;;      :chart-props  <props-or-nil>
  ;;      :transitions  [<transition-row> ...]
  ;;      :empty-kind   <:no-machines / nil>}

  ;; Read the registered-machine vector. Reads `(rf/machines)` —
  ;; returns `[]` when the machines artefact is not on the
  ;; classpath (see implementation/core/src/re_frame/core_machines.cljc
  ;; §machines). The fallback path is wrapped in a `try` so any
  ;; future API change collapses to an empty registry rather than
  ;; throwing through the sub.
  (rf/reg-sub :rf.causa/registered-machines
    (fn [db _query]
      (let [ov (get db :registered-machines-override)]
        (or ov
            (try (vec (rf/machines))
                 (catch :default _ []))))))

  ;; Test-only override hook for the registered-machines surface.
  ;; Production code paths never dispatch this — the slot exists
  ;; only so JVM + node-test suites can drive the projection
  ;; without booting a host with `rf/reg-machine` calls.
  (rf/reg-event-db :rf.causa/set-registered-machines-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :registered-machines-override)
        (assoc db :registered-machines-override ov))))

  ;; The live snapshots map for every registered machine, keyed by
  ;; machine-id. Reads off the target-frame-db's `:rf/machines`
  ;; slot (per Spec 005 §Where snapshots live — `:rf/machines` is
  ;; the reserved app-db key); the test override slot mirrors the
  ;; registered-machines pattern so JVM tests write a snapshot map
  ;; without a live frame.
  (rf/reg-sub :rf.causa/machine-snapshots
    :<- [:rf.causa/target-frame-db]
    (fn [target-frame-db _query]
      (when (map? target-frame-db)
        (get target-frame-db :rf/machines {}))))

  (rf/reg-sub :rf.causa/machine-snapshots-override
    (fn [db _query]
      (get db :machine-snapshots-override)))

  (rf/reg-event-db :rf.causa/set-machine-snapshots-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :machine-snapshots-override)
        (assoc db :machine-snapshots-override ov))))

  ;; The registered-machine-definition map for every machine, keyed
  ;; by machine-id (rf2-2tkza Phase 1). Production path reads through
  ;; `(rf/machine-meta <id>)` for each registered id; the resulting
  ;; map is what the chart primitive consumes to lay out the SVG. The
  ;; per-id `try` is belt-and-braces — `machine-meta` is pure but a
  ;; future API change shouldn't tear the whole composite.
  (rf/reg-sub :rf.causa/machine-definitions-override
    (fn [db _query]
      (get db :machine-definitions-override)))

  (rf/reg-sub :rf.causa/machine-definitions
    :<- [:rf.causa/registered-machines]
    :<- [:rf.causa/machine-definitions-override]
    (fn [[machines override] _query]
      (or override
          (into {}
                (keep (fn [id]
                        (let [m (try (rf/machine-meta id)
                                     (catch :default _ nil))]
                          (when m [id m]))))
                (or machines [])))))

  ;; Test-only override for the definitions surface — mirrors the
  ;; snapshot / registered-machines test hooks so JVM + node-test
  ;; suites can drive the chart projection without booting a host
  ;; with `rf/reg-machine` calls.
  (rf/reg-event-db :rf.causa/set-machine-definitions-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :machine-definitions-override)
        (assoc db :machine-definitions-override ov))))

  ;; The user's per-panel machine selection (drives the picker
  ;; focus). nil = default to first row.
  (rf/reg-sub :rf.causa/selected-machine-id
    (fn [db _query]
      (get db :selected-machine-id)))

  ;; The composite — one read produces every slot the panel
  ;; consumes (matches the per-panel composite pattern every other
  ;; Causa panel uses).
  (rf/reg-sub :rf.causa/machine-inspector-data
    :<- [:rf.causa/registered-machines]
    :<- [:rf.causa/machine-snapshots]
    :<- [:rf.causa/machine-snapshots-override]
    :<- [:rf.causa/machine-definitions]
    :<- [:rf.causa/trace-buffer]
    :<- [:rf.causa/selected-machine-id]
    :<- [:rf.causa/target-frame]
    (fn [[machines live-snapshots snapshots-override definitions buffer selected-id target-frame]
         _query]
      (let [snapshots (or snapshots-override live-snapshots {})]
        (h/project-data
          machines snapshots definitions buffer selected-id target-frame))))

  ;; ---- Machine Inspector panel events ----------------------------

  (rf/reg-event-db :rf.causa/select-machine-id
    (fn [db [_ machine-id]]
      (assoc db :selected-machine-id machine-id)))

  (rf/reg-event-db :rf.causa/clear-machine-selection
    (fn [db _event]
      (dissoc db :selected-machine-id)))

  ;; Source-coord jump trigger (rf2-2tkza Phase 1 scaffold). Clicking
  ;; a state in the chart fires this event; Phase 2 (follow-on bead)
  ;; wires it through to `:rf.causa/open-in-editor` once the
  ;; `:rf.machine/source-coords {:state <path>}` slot stabilises in
  ;; spec/005. v1 swallows the event so the click surface is testable
  ;; without an editor-jump side effect leaking into smoke tests.
  (rf/reg-event-db :rf.causa/machine-state-clicked
    (fn [db [_ _payload]]
      db))

  ;; ---- Phase 4 (rf2-m7co9) — ELK layout pulse ---------------------
  ;;
  ;; The ELK layout pass is asynchronous (returns a Promise). When it
  ;; resolves, we need to nudge the subscribe-driven view to recompute
  ;; so the cached ELK positions land on the next render. A no-op
  ;; event handler tick on the panel's app-db is the simplest pulse —
  ;; it bumps the reactive graph without changing any user-visible
  ;; state. Mirrors the `:rf.causa/causality-popover-layout-pulse`
  ;; pattern in the Causality popover's ELK loader.
  (rf/reg-event-db :rf.causa/machine-chart-layout-pulse
    (fn [db _event]
      ;; Increment a counter so the app-db value actually changes —
      ;; some substrates short-circuit identical-value writes.
      (update db :machine-inspector/elk-pulse-tick (fnil inc 0))))

  ;; ---- Phase 3 (rf2-juon8) — UC2 Mode C forced-mode slot ----------

  ;; The user's forced view-mode override; nil = auto-classify via
  ;; `view-mode`. Set / cleared via `:rf.causa/set-forced-machine-mode`.
  ;; The Mode tab strip in the header writes this slot; the panel
  ;; reads it through `resolve-mode`. Stored under a `:mode-c/*`-style
  ;; namespaced key for visual coherence with the rest of Mode C's
  ;; app-db slots.
  (rf/reg-sub :rf.causa/forced-machine-mode
    (fn [db _query]
      (get db :machine-inspector/forced-mode)))

  (rf/reg-event-db :rf.causa/set-forced-machine-mode
    (fn [db [_ mode]]
      (if (or (nil? mode)
              (contains? #{:mode-a :mode-b :mode-c} mode))
        (if (nil? mode)
          (dissoc db :machine-inspector/forced-mode)
          (assoc db :machine-inspector/forced-mode mode))
        db)))

  ;; ---- Phase 2 (rf2-v869p) — UC1 Sim sub-mode ---------------------
  ;;
  ;; The sim sub family lives in its own ns
  ;; (`panels/machine_inspector_sim.cljs`) so the panel ns stays
  ;; focused on the live observation chrome. The sim install fn
  ;; registers `:rf.causa/sim-*` events + subs against the same
  ;; `:rf/causa` frame the panel reads.
  (sim/install!)

  ;; ---- Phase 3 (rf2-juon8) — UC2 Mode C cluster view --------------
  ;;
  ;; The cluster view + its sub/event family live in
  ;; `panels/machine_inspector_cluster.cljs`. The install fn registers
  ;; `:rf.causa/mode-c-*` events + subs against the same `:rf/causa`
  ;; frame the panel reads.
  (cluster/install!)

  ;; ---- Phase 5 (rf2-nqw0v) — per-instance arc + mini-scrubber -----
  ;;
  ;; The arc + scrubber sub/event family lives in
  ;; `panels/machine_inspector_arc.cljs`. The install fn registers
  ;; `:rf.causa/machine-arc-*` + `:rf.causa/set-scrubber-position` +
  ;; `:rf.causa/set-arc-hover` against the same `:rf/causa` frame the
  ;; panel reads. The arc-helper algebra is JVM-runnable and lives in
  ;; `machine_inspector_arc_helpers.cljc`.
  (arc/install!)

  ;; ---- rf2-7hwwe — `:after` timer countdown rings ----------------
  ;;
  ;; The rings sub/event family lives in
  ;; `panels/machine_after_rings.cljs`. The install fn registers
  ;; `:rf.causa/active-timers-for-focused-machine` +
  ;; `:rf.causa/timer-tick` + `:rf.causa/timer-hover` +
  ;; `:rf.causa/now-ms` (+ the test-only override) against the same
  ;; `:rf/causa` frame the panel reads. Pure projection lives in
  ;; `machine_after_rings_helpers.cljc`. MUST install AFTER the arc
  ;; install! above because the rings overlay consumes the scrubber-
  ;; position sub the arc family registers.
  (after-rings/install!)

  ;; ---- Phase 5 (rf2-nqw0v) — Share affordance --------------------
  ;;
  ;; The Share affordance encodes the current focus + mode + scrubber
  ;; position into a URL the user can paste anywhere. The infra lives
  ;; in `day8.re-frame2-causa.share` (subs + events + clipboard fx);
  ;; the modal view lives in `day8.re-frame2-causa.share-modal`. The
  ;; modal mounts at the shell root per the palette / settings popup
  ;; pattern; this install registers the sub/event/fx family.
  (share/install!))
