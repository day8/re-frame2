(ns day8.re-frame2-xray.static.machines.topology
  "Topology mode renderer — Static-read view of a machine's state graph.

  ## What it renders

  The Static Topology body embeds `machine-canvas/Chart` — the SAME
  MachineChart surface the Dynamic Machines panel renders. So the
  Static and Dynamic surfaces are the same chart; pan / zoom / fit are
  driven by the mouse + xyflow's own
  on-canvas controls (drag to pan, wheel / pinch to zoom, the controls
  buttons to fit), NOT by Xray-owned keyboard shortcuts. There are NO
  implemented keyboard shortcuts (`+` / `-`, arrows, `f`, `0`) on either
  surface — viewport gestures are xyflow's.

  Static differs from Dynamic in two respects:

    1. NO focused-event lens — Static is a static-read; there's no
       `:from-highlight-id` / `:to-highlight-id` to pass, and the
       after-rings overlay is suppressed (`:show-after-rings?
       false`).
    2. Click on a state node fires
       `:rf.xray.static.machines/state-clicked` (with the clicked
       state's path) — not the Dynamic panel's inspector-lens
       navigation event.

  ## Viewport

  There is NO stored, shared per-machine viewport slot — Static and
  Dynamic do NOT hand a saved pan / zoom between each other. Each
  surface's viewport lives inside its own xyflow instance for the
  mount's lifetime. Fit-on-entry is the one piece Xray drives: entering
  the Static Machines tab bumps a `:fit-signal` nonce that
  re-frames the topology (xyflow's one-shot `:fitView` + the layout-key
  auto-fit both leave a re-entered chart stale), so the chart opens
  framed to its content. After that, pan / zoom is the user's via the
  mouse + xyflow controls.

  ## Empty states

  - No machine selected (rare — the panel always defaults to the
    first row when one exists).
  - Machine has no definition (registrar returns nil from
    `machine-meta`) — render a hint pointing at `reg-machine`.
  - Definition with no `:states` map (degenerate) — falls through
    to `mv-svg/render`'s built-in 'no states' message (rendered
    inside the canvas host).

  ## Definition-stale post-reload

  The chart re-renders on every spec change (the `machine-definitions`
  sub fires on framework hot-reload), so the data shown is always live.
  TODO: surface a 'definition reloaded' chip after a hot-reload changes
  the machine spec while the user is looking at it (the reload-detection
  wiring is not yet in place)."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.open-in-editor :as open-in-editor]
            [day8.re-frame2-xray.panels.machine-canvas :as machine-canvas]
            [day8.re-frame2-xray.panels.machines.topology-view :as topology-view]
            [day8.re-frame2-xray.static.machines.helpers :as h]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens sans-stack mono-stack type-scale]]))

;; ---- empty surfaces -----------------------------------------------------

(defn- no-definition []
  [:div {:data-testid "rf-xray-static-machines-topology-no-definition"
         :style {:padding "16px"
                 :color (:text-tertiary tokens)
                 :font-family sans-stack
                 :font-size (:caption type-scale)}}
   "Machine definition is not introspectable. The chart cannot render."])

;; ---- chart wrapper ------------------------------------------------------

(defn- chart
  "Render the interactive MachineChart canvas for `definition`. NO
  highlight + NO after-rings — Static Topology is a static-read.

  Delegates to `machine-canvas/Chart` (the same MachineChart surface
  the Dynamic Machines panel renders) so the user gets mouse /
  controls-driven xyflow zoom / pan / fit on the Static surface too (no
  Xray-owned keyboard shortcuts — viewport gestures are xyflow's). The
  static-flavoured opts are:

    :show-after-rings?      false  — no focused-event lens on static.
    :testid                        — overrides the inner SVG testid
                                     so the static-panel tests match.

  Forwards the STATIC context shape (keys + type captions) into the
  chart so the root Context band renders on the Static Topology
  surface, matching the Dynamic topology + focused-event charts.
  Derived via the SHARED `topology-view/static-context-shape` /
  `static-context-inferred?` helpers (which delegate to machines-viz
  `context-shape`) — no duplicate derivation. nil shape → the chart
  hides the panel; the declared-over-inferred provenance gates the
  `inferred from :data` badge."
  [dispatch {:keys [definition machine-id fit-signal]}]
  ;; ELK is driven internally by xyflow inside `mv-chart/MachineChart`;
  ;; the host does no layout itself.
  ;; `dispatch` is threaded from `body` (the Static Machines detail
  ;; subtree is invoked as Reagent components, so render-time frame
  ;; recovery is unavailable).
  (let [engine "xyflow+elkjs"]
    [:div {:data-testid    "rf-xray-static-machines-topology-chart"
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
       ;; Surface the STATIC context shape so the root Context band
       ;; renders without a live snapshot (matching the Dynamic
       ;; topology + focused-event charts). Shape + provenance come
       ;; from the shared machines-viz-backed helpers.
       :context-band           (topology-view/static-context-shape definition)
       :context-band-inferred? (topology-view/static-context-inferred? definition)
       :show-after-rings?      false
       ;; Fit-on-entry nonce so entering the Static Machines tab
       ;; re-frames the topology (xyflow's one-shot `:fitView` +
       ;; the layout-key auto-fit both leave a re-entered chart stale).
       :fit-signal             fit-signal
       :inner-testid           "rf-xray-static-machines-topology-svg"
       :on-state-click
       (fn [path]
         (dispatch [:rf.xray.static.machines/state-clicked
                    {:machine-id machine-id :path path}]))}]]))

;; ---- chart toolbar (open in popout) -------------------------------------

(defn- popout-affordance
  "'Open chart in popout' affordance. Wired to the
  `:rf.xray.static.machines/open-chart-popout` event (registered by
  the panel install). TODO: popout window geometry."
  [dispatch machine-id]
   [:button
   {:data-testid "rf-xray-static-machines-topology-popout"
    :on-click    (fn [_]
                   (dispatch
                     [:rf.xray.static.machines/open-chart-popout machine-id]))
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
        [:span {:data-testid "rf-xray-static-machines-topology-source-coord-text"
                :style {:font-family mono-stack
                        :font-size (:micro type-scale)
                        :color (:text-tertiary tokens)}}
         (h/format-source-coord source-coord)])))

(defn- chart-toolbar [dispatch {:keys [machine-id source-coord]}]
  [:div {:data-testid "rf-xray-static-machines-topology-toolbar"
         :style {:display     "flex"
                 :align-items "center"
                 :gap         "8px"
                 :padding     "8px 12px"
                 :background  (:bg-1 tokens)
                 :border-bottom (str "1px solid " (:border-subtle tokens))}}
   [popout-affordance dispatch machine-id]
   [:span {:style {:margin-left "auto"}}
    (source-coord-chip source-coord)]])

;; ---- public renderer ----------------------------------------------------

(defn body
  "Render Topology mode for `machine-id`. `definition` is the
  registrar's spec map; `source-coord` is its lifted coord (or nil).

  The embedded `machine-canvas/Chart` wraps the machines-viz xyflow
  `MachineChart`, which manages zoom / pan / fit internally — the body
  function itself is still pure hiccup.

  `dispatch` is threaded from `definition_detail/body` so the chart's
  state-click + the toolbar's pop-out land on the surrounding instance
  frame."
  [dispatch {:keys [machine-id definition source-coord fit-signal] :as args}]
  (cond
    (nil? definition)
    [:section {:data-testid     "rf-xray-static-machines-topology"
               :data-machine-id (str machine-id)
               :style {:height "100%"
                       :display "flex"
                       :flex-direction "column"
                       :background (:bg-2 tokens)
                       :color (:text-primary tokens)
                       :font-family sans-stack}}
     (no-definition)]

    :else
    [:section {:data-testid     "rf-xray-static-machines-topology"
               :data-machine-id (str machine-id)
               :style {:height "100%"
                       :display "flex"
                       :flex-direction "column"
                       :background (:bg-2 tokens)
                       :color (:text-primary tokens)
                       :font-family sans-stack}}
     [chart-toolbar dispatch {:machine-id machine-id :source-coord source-coord}]
     [chart dispatch {:definition definition :machine-id machine-id
                      :fit-signal fit-signal}]]))
