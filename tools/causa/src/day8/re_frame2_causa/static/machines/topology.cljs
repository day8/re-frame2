(ns day8.re-frame2-causa.static.machines.topology
  "Topology mode renderer — Static-read view of a machine's state graph
  (rf2-o5f5f.2; rf2-md9oz upgrade to interactive Chart adapter).

  ## What it renders

  The Static Topology body embeds `machine-canvas/Chart` — the same
  interactive viewport adapter the Dynamic Machines panel uses — so
  Static users get zoom / pan / fit with the same gestures and the
  same keyboard shortcuts (`+` / `-`, arrows, `f`, `0`). Stately and
  XState's static / read-only chart views get the same affordances;
  Causa's static surface should match.

  Static differs from Dynamic in three respects:

    1. NO focused-event lens — Static is a static-read; there's no
       `:from-highlight-id` / `:to-highlight-id` to pass, and the
       after-rings overlay is suppressed (`:show-after-rings?
       false`).
    2. NO Canvas/List view-mode toggle inside the canvas — the
       Static Machines panel already owns a per-machine sub-mode
       pill strip at L3 (Topology / Sim / Instances / Cascade); a
       second toggle inside the canvas would be redundant noise.
       (`:show-view-mode-toggle? false`.)
    3. Click on a state node fires
       `:rf.causa.static.machines/state-clicked` (with the clicked
       state's path) — not the Dynamic panel's inspector-lens
       navigation event.

  The per-machine viewport slot is shared with the Dynamic Machine
  Inspector — if the user pans / zooms in either surface, the other
  picks up the same viewport when it next mounts. That matches the
  user model: 'this is the chart for machine X, parked in this
  view.'

  ## Empty states

  - No machine selected (rare — the panel always defaults to the
    first row when one exists).
  - Machine has no definition (registrar returns nil from
    `machine-meta`) — render a hint pointing at `reg-machine`.
  - Definition with no `:states` map (degenerate) — falls through
    to `mv-svg/render`'s built-in 'no states' message (rendered
    inside the canvas host).

  ## Definition-stale post-reload

  Per the bead's §Topology mode + findings §3.3 the chart should
  surface a 'definition reloaded' chip after a hot-reload changes
  the machine spec while the user is looking at it. v1 ships the
  scaffold without the reload-detection wiring — the chip will land
  with a follow-on bead. The chart re-renders on every spec change
  (the `machine-definitions` sub fires on framework hot-reload), so
  the data shown is always live."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.open-in-editor :as open-in-editor]
            [day8.re-frame2-causa.panels.machine-canvas :as machine-canvas]
            [day8.re-frame2-causa.static.machines.helpers :as h]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens sans-stack mono-stack type-scale]]))

;; ---- empty surfaces -----------------------------------------------------

(defn- no-definition []
  [:div {:data-testid "rf-causa-static-machines-topology-no-definition"
         :style {:padding "16px"
                 :color (:text-tertiary tokens)
                 :font-family sans-stack
                 :font-size (:caption type-scale)}}
   "Machine definition is not introspectable. The chart cannot render."])

;; ---- chart wrapper ------------------------------------------------------

(defn- chart
  "Render the interactive MachineChart canvas for `definition`. NO
  highlight + NO after-rings — Static Topology is a static-read.

  rf2-md9oz — delegates to `machine-canvas/Chart` (same adapter the
  Dynamic Machines panel uses) so the user gets zoom / pan / fit
  + keyboard shortcuts on the Static surface too. The static-
  flavoured opts are:

    :show-view-mode-toggle? false  — Static panel owns sub-mode at L3.
    :show-after-rings?      false  — no focused-event lens on static.
    :testid                        — overrides the inner SVG testid
                                     so the existing static-panel
                                     tests still match."
  [{:keys [definition machine-id]}]
  ;; rf2-gpzb4 (2026-05-21 xyflow migration) — ELK is now driven
  ;; internally by xyflow inside `mv-chart/MachineChart`; the
  ;; host-side layout-or-fallback dance is gone.
  (let [engine "xyflow+elkjs"]
    [:div {:data-testid    "rf-causa-static-machines-topology-chart"
           :data-machine-id (str machine-id)
           :data-layout-engine engine
           :style {:padding    "12px"
                   :background (:bg-1 tokens)
                   :overflow   "hidden"
                   :position   "relative"
                   ;; The canvas-host fills this wrapper — must give it
                   ;; vertical room so the chart isn't clipped to 0px.
                   :flex       "1 1 auto"
                   :min-height "260px"}}
     [machine-canvas/Chart
      {:definition             definition
       :machine-id             machine-id
       :show-after-rings?      false
       :show-view-mode-toggle? false
       :inner-testid           "rf-causa-static-machines-topology-svg"
       :on-state-click
       (fn [path]
         (rf/dispatch [:rf.causa.static.machines/state-clicked
                       {:machine-id machine-id :path path}]
                      {:frame :rf/causa}))}]]))

;; ---- chart toolbar (open in popout) -------------------------------------

(defn- popout-affordance
  "Per the bead — 'Open chart in popout' affordance. v1 scaffolds the
  affordance against the existing `:rf.causa.static.machines/open-
  chart-popout` event (registered by the panel install). Popout
  geometry lands in a follow-on bead (sibling of the second-window
  rf2-u3qm1 work)."
  [machine-id]
  [:button
   {:data-testid "rf-causa-static-machines-topology-popout"
    :on-click    (fn [_]
                   (rf/dispatch
                     [:rf.causa.static.machines/open-chart-popout machine-id]
                     {:frame :rf/causa}))
    :title       "Open chart in pop-out window"
    :aria-label  (str "Open the chart for " machine-id
                      " in a pop-out window")
    :style {:background    "transparent"
            :border        (str "1px solid " (:border-default tokens))
            :border-radius "10px"
            :color         (:text-secondary tokens)
            :cursor        "pointer"
            :font-family   sans-stack
            :font-size     (:micro type-scale)
            :padding       "1px 8px"
            :white-space   "nowrap"}}
   "↗ Pop out"])

(defn- source-coord-chip [source-coord]
  (when (some? source-coord)
    (or (open-in-editor/open-chip source-coord)
        [:span {:data-testid "rf-causa-static-machines-topology-source-coord-text"
                :style {:font-family mono-stack
                        :font-size (:micro type-scale)
                        :color (:text-tertiary tokens)}}
         (h/format-source-coord source-coord)])))

(defn- chart-toolbar [{:keys [machine-id source-coord]}]
  [:div {:data-testid "rf-causa-static-machines-topology-toolbar"
         :style {:display     "flex"
                 :align-items "center"
                 :gap         "8px"
                 :padding     "8px 12px"
                 :background  (:bg-1 tokens)
                 :border-bottom (str "1px solid " (:border-subtle tokens))}}
   [popout-affordance machine-id]
   [:span {:style {:margin-left "auto"}}
    (source-coord-chip source-coord)]])

;; ---- public renderer ----------------------------------------------------

(defn body
  "Render Topology mode for `machine-id`. `definition` is the
  registrar's spec map; `source-coord` is its lifted coord (or nil).

  The embedded `machine-canvas/Chart` (rf2-md9oz) subscribes to
  `:rf.causa.machine-canvas/viewport-for` internally so zoom / pan
  state lives in app-db — the body function itself is still pure
  hiccup; the subscribe lands one level down in the canvas
  adapter."
  [{:keys [machine-id definition source-coord] :as args}]
  (cond
    (nil? definition)
    [:section {:data-testid     "rf-causa-static-machines-topology"
               :data-machine-id (str machine-id)
               :style {:height "100%"
                       :display "flex"
                       :flex-direction "column"
                       :background (:bg-2 tokens)
                       :color (:text-primary tokens)
                       :font-family sans-stack}}
     (no-definition)]

    :else
    [:section {:data-testid     "rf-causa-static-machines-topology"
               :data-machine-id (str machine-id)
               :style {:height "100%"
                       :display "flex"
                       :flex-direction "column"
                       :background (:bg-2 tokens)
                       :color (:text-primary tokens)
                       :font-family sans-stack}}
     [chart-toolbar {:machine-id machine-id :source-coord source-coord}]
     [chart {:definition definition :machine-id machine-id}]]))
