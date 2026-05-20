(ns day8.re-frame2-causa.panels.machines.topology-view
  "Reagent view ↔ xyflow projector composition (rf2-uwvyj · spec/021
  §6 + §17.4).

  This is the public consumer surface: the Machines Canvas panel
  (or any other Causa panel rendering a single machine's topology)
  mounts `[topology-view {:machine-id ... :definition ...}]` and
  gets the styled xyflow canvas back.

  ## Composition

      machine-id + definition  ──→  topology/project   ──→  {:nodes :edges}
                                          │
                                          ▼ (xyflow-style/node-style + edge-style)
                                          │
                                          ▼ xyflow-wrapper/xyflow-canvas
                                          │
                                          ▼
                                  <ReactFlow> in React tree

  ## current-state overlay

  Two sources feed the `current-state-path` arg:

    1. Caller-supplied `:current-state-path` (highest priority).
    2. Caller-supplied `:trace-events` (the focused-epoch's
       `:rf.machine/transition` slice) — `topology/current-state-
       from-traces` resolves the `:to` of the most recent matching
       trace.

  ## Pure-hiccup contract

  This view returns hiccup; it does not subscribe to re-frame
  directly — the parent panel is responsible for pulling the
  definition + traces off the substrate and passing them in. Keeps
  the view testable in isolation."
  (:require [day8.re-frame2-causa.panels.machines.topology :as topology]
            [day8.re-frame2-causa.panels.machines.xyflow-style :as xstyle]
            [day8.re-frame2-causa.panels.machines.xyflow-wrapper :as wrapper]
            [day8.re-frame2-causa.theme.tokens :as t :refer [tokens]]))

(defn- resolve-current-state-path
  "Per-spec precedence: explicit > traces > nil."
  [machine-id current-state-path trace-events]
  (or current-state-path
      (topology/current-state-from-traces trace-events machine-id)))

(defn Topology
  "Render a machine's topology via xyflow. Args (map):

    :machine-id          — keyword; used for testid stamping +
                           trace-filter scoping.
    :definition          — machine definition map (required; nil
                           renders the no-definition fallback).
    :current-state-path  — optional explicit current-state path (a
                           vector of keywords). Overrides trace-
                           derived current-state.
    :trace-events        — optional vector of focused-epoch trace
                           events. Used to derive the current-state +
                           `fired-this-epoch` edge highlights.
    :height              — outer wrapper height (default `'320px'`).
                           xyflow requires a non-zero parent height.
    :show-controls?      — pass-through to wrapper (default true).
    :testid              — pass-through wrapper testid (default
                           `'rf-causa-machines-topology'`).

  Returns hiccup."
  [{:keys [machine-id definition current-state-path trace-events
           height show-controls? testid]
    :or   {height          "320px"
           show-controls?  true
           testid          "rf-causa-machines-topology"}}]
  (let [cur-path  (resolve-current-state-path machine-id
                                              current-state-path
                                              trace-events)
        fired-ids (topology/extract-fired-edge-ids trace-events machine-id)
        graph     (topology/project
                    {:definition         definition
                     :current-state-path cur-path
                     :fired-edge-ids     fired-ids
                     :node-style-fn      xstyle/node-style
                     :edge-style-fn      xstyle/edge-style
                     :edge-animated-fn   xstyle/animated?})]
    (cond
      (nil? definition)
      [:div {:data-testid (str testid "-no-definition")
             :data-machine-id (str machine-id)
             :style {:padding "16px"
                     :color (:text-tertiary tokens)
                     :background (:bg-2 tokens)
                     :border (str "1px dashed " (:border-default tokens))
                     :border-radius "6px"}}
       "Machine definition is not introspectable — no topology to render."]

      (empty? (:nodes graph))
      [:div {:data-testid (str testid "-empty")
             :data-machine-id (str machine-id)
             :style {:padding "16px"
                     :color (:text-tertiary tokens)}}
       "Machine has no states."]

      :else
      [:div {:data-testid testid
             :data-machine-id (str machine-id)
             :data-current-state (when cur-path (pr-str cur-path))
             :data-node-count (str (count (:nodes graph)))
             :data-edge-count (str (count (:edges graph)))
             :style {:position "relative"
                     :width  "100%"
                     :height height
                     :background (:bg-1 tokens)
                     :border (str "1px solid " (:border-default tokens))
                     :border-radius "6px"
                     :overflow "hidden"}}
       [wrapper/xyflow-canvas
        {:nodes          (:nodes graph)
         :edges          (:edges graph)
         :show-controls? show-controls?
         :testid         (str testid "-canvas")}]])))
