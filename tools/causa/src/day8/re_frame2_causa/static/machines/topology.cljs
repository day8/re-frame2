(ns day8.re-frame2-causa.static.machines.topology
  "Topology mode renderer — Static-read view of a machine's state graph
  (rf2-o5f5f.2).

  ## What it renders

  The same `chart/svg` MachineChart primitive the Runtime panel uses
  (per findings §5.1 — single implementation). **The Static-mode
  rendering passes NO `:highlight-id`** — Topology in Static is a
  static-read; there's no active state to spotlight.

  Click on a state node fires `:rf.causa.static.machines/state-clicked`
  with the clicked state's path; the right pane carries a metadata
  rail showing incoming / outgoing edges + a source-coord chip (when
  the definition lifts a coord onto the state). The chip degrades
  gracefully when the coord is missing.

  ## Empty states

  - No machine selected (rare — the panel always defaults to the
    first row when one exists).
  - Machine has no definition (registrar returns nil from
    `machine-meta`) — render a hint pointing at `reg-machine`.
  - Definition with no `:states` map (degenerate) — falls through to
    `chart/svg/render`'s built-in 'no states' message.

  ## Definition-stale post-reload

  Per the bead's §Topology mode + findings §3.3 the chart should
  surface a 'definition reloaded' chip after a hot-reload changes
  the machine spec while the user is looking at it. v1 ships the
  scaffold without the reload-detection wiring — the chip will land
  with a follow-on bead. The chart re-renders on every spec change
  (the `machine-definitions` sub fires on framework hot-reload), so
  the data shown is always live."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.chart.svg :as chart-svg]
            [day8.re-frame2-causa.chart.elk-layout :as elk-layout]
            [day8.re-frame2-causa.open-in-editor :as open-in-editor]
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
  "Render the chart SVG for `definition`. NO highlight — Static
  Topology is a static-read."
  [{:keys [definition machine-id]}]
  (let [direction  :tb
        positioned (elk-layout/layout-or-fallback definition direction)
        engine     (if (some? (elk-layout/cached-layout definition direction))
                     "elk"
                     "layered")]
    ;; Trigger ELK to take over post-mount if available; layered serves
    ;; the first paint either way. Same wire-up the Runtime panel uses
    ;; (per `panels/machine_inspector.cljs/focused-event-section`).
    (elk-layout/ensure-elk!
      (fn [_inst]
        (when (and (= :ready (elk-layout/elk-status))
                   (nil? (elk-layout/cached-layout definition direction)))
          (elk-layout/compute-layout!
            definition direction
            (fn [chart-layout]
              (when chart-layout
                (rf/dispatch [:rf.causa/machine-chart-layout-pulse]
                             {:frame :rf/causa})))))))
    [:div {:data-testid    "rf-causa-static-machines-topology-chart"
           :data-machine-id (str machine-id)
           :data-layout-engine engine
           :style {:padding    "12px"
                   :background (:bg-1 tokens)
                   :overflow   "auto"
                   :position   "relative"}}
     (chart-svg/render
       positioned
       {:testid         "rf-causa-static-machines-topology-svg"
        :on-state-click
        (fn [path]
          (rf/dispatch [:rf.causa.static.machines/state-clicked
                        {:machine-id machine-id :path path}]
                       {:frame :rf/causa}))})]))

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

  Pure-ish — reads no subscribes (the panel feeds the props). The
  embedded `chart-svg/render` is pure hiccup."
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
