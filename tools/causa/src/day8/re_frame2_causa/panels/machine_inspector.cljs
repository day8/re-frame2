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
            [day8.re-frame2-causa.panels.machine-inspector-helpers :as h]))

;; ---- design tokens (mirrors routes.cljs / subscriptions.cljs) -----------

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
                                (rf/dispatch [:rf.causa/select-machine-id id]))))
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
  "Calls out the deferred `tools/machines-viz/` impl + cites the
  spec sources so a future reader (human or AI) knows the panel
  contract is live even though the rendering surface is a stub."
  []
  [:div {:data-testid "rf-causa-machine-inspector-placeholder-banner"
         :style       {:padding "10px 12px"
                       :margin "10px 12px 0 12px"
                       :border (str "1px dashed " (:accent-violet tokens))
                       :border-radius "4px"
                       :background "rgba(124, 92, 255, 0.08)"
                       :color (:text-secondary tokens)
                       :font-family sans-stack
                       :font-size "11px"
                       :line-height 1.5}}
   [:strong {:style {:color (:accent-violet tokens)
                     :font-weight 600
                     :font-size "11px"
                     :text-transform "uppercase"
                     :letter-spacing "0.5px"}}
    "MachineChart placeholder"]
   [:p {:style {:margin "4px 0 0 0"}}
    "The "
    [:code {:style {:font-family mono-stack :color (:text-primary tokens)}}
     "tools/machines-viz/"]
    " implementation is deferred — only the spec scaffold has landed "
    "(rf2-x50eu). This panel renders the prop summary the real "
    [:code {:style {:font-family mono-stack :color (:text-primary tokens)}}
     "MachineChart"]
    " component would consume per "
    [:code {:style {:font-family mono-stack :color (:text-primary tokens)}}
     "tools/machines-viz/spec/API.md"]
    ". When the impl ships, the placeholder swaps for the real "
    "component without touching the panel chrome."]])

(defn- prop-row
  "One labelled row inside the placeholder's prop summary."
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

(defn- placeholder-chart
  "Renders the prop map a real MachineChart would consume. This is
  the v1 surface — when machines-viz ships the placeholder becomes
  `[viz/MachineChart props]`."
  [props]
  (if (nil? props)
    [:div {:data-testid "rf-causa-machine-inspector-placeholder-empty"
           :style       {:padding "16px"
                         :color (:text-tertiary tokens)
                         :font-family sans-stack
                         :font-size "12px"}}
     "No machine selected — nothing to chart."]
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
                    :font-size "10px"
                    :color (:text-tertiary tokens)
                    :text-transform "uppercase"
                    :letter-spacing "0.5px"}}
      "Defaults applied at chart-mount (per tools/machines-viz/spec/API.md §Props):"]
     [:ul {:style {:margin "4px 0 0 0"
                   :padding "0 0 0 16px"
                   :color (:text-secondary tokens)
                   :font-family mono-stack
                   :font-size "11px"
                   :list-style "disc"}}
      [:li ":read-only? false"]
      [:li ":show-microsteps? true"]
      [:li ":show-after-rings? true"]
      [:li ":show-invoke-all? true"]
      [:li ":auto-pan? false"]]]))

;; ---- transition history ribbon ------------------------------------------

(defn- transition-entry
  "One transition-history ribbon entry. Per spec/003-Machine-
  Inspector.md §Transition history ribbon clicking the entry jumps to
  event-detail for that transition (parity with the Issues ribbon /
  Trace panel)."
  [{:keys [id from to event dispatch-id microstep?]}]
  [:li {:data-testid (str "rf-causa-machine-inspector-transition-" id)
        :on-click    (when dispatch-id
                       #(rf/dispatch [:rf.causa/select-dispatch-id dispatch-id]))
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
    "Once your app registers a machine via "
    [:code {:style {:font-family mono-stack :color (:accent-violet tokens)}}
     "rf/reg-machine"]
    " (Spec 005), it will appear here with:"]
   [:ul {:style {:margin "8px 0 0 0"
                 :padding "0 0 0 18px"
                 :font-size "12px"}}
    [:li "Live state-chart highlighting"]
    [:li "Transition history ribbon"]
    [:li ":after countdown rings (via "
     [:code {:style {:font-family mono-stack :color (:text-tertiary tokens)}}
      "tools/machines-viz/"]
     ")"]]])

;; ---- public view --------------------------------------------------------

(defn machine-inspector-view
  "The Machine Inspector panel's root view. Subscribes to
  `:rf.causa/machine-inspector-data` and renders either the empty
  state (no machines registered) or the picker + chart placeholder +
  transition-history ribbon."
  []
  (let [{:keys [machines total selected-id chart-props transitions empty-kind]}
        @(rf/subscribe [:rf.causa/machine-inspector-data])]
    [:section {:data-testid "rf-causa-machine-inspector"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     [:header {:style {:padding "16px 16px 8px 16px"}}
      [:h1 {:style {:font-size "16px"
                    :font-weight 600
                    :margin 0
                    :color (:text-primary tokens)}}
       "Machine inspector"]
      [:p {:style {:font-size "12px"
                   :color     (:text-tertiary tokens)
                   :margin    "4px 0 0 0"}}
       "State-chart per registered machine. Picker switches the focus; "
       "the chart placeholder mirrors the prop contract from "
       [:code {:style {:font-family mono-stack :color (:accent-violet tokens)}}
        "tools/machines-viz/"]
       "."]]
     (if (= :no-machines empty-kind)
       (empty-state)
       [:div {:style {:flex 1 :display "flex" :flex-direction "column"
                      :overflow "hidden"}}
        [machine-picker machines selected-id]
        [:div {:style {:flex 1 :overflow "auto"}}
         (placeholder-banner)
         (placeholder-chart chart-props)]
        (transition-ribbon transitions)])]))
