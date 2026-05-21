(ns day8.re-frame2-causa.panels.machines.topology-view
  "Reagent view ↔ xyflow projector composition (rf2-uwvyj · spec/021
  §6 + §17.4).

  This is the public consumer surface: the Static Machines panel's
  Topology mode (or any other Causa panel rendering a single
  machine's topology) mounts `[topology-view {:machine-id ...
  :definition ...}]` and gets the styled xyflow canvas back.

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

  Four sources feed the `current-state-path` arg (precedence high → low):

    1. Caller-supplied `:current-state-path` (explicit override).
    2. Caller-supplied `:trace-events` (the focused-epoch's
       `:rf.machine/transition` slice) — `topology/current-state-
       from-traces` resolves the `:to` of the most recent matching
       trace.
    3. Caller-supplied `:epoch-history` (oldest-first vector of epoch
       records) — `topology/current-state-from-epoch-history` walks
       backward through prior epochs for the most recent transition
       this machine took. This is the **case-B refinement** per
       spec/021 §6.2 / §17.4.1 (rf2-dbi87): when the focused epoch
       has no transition, the topology is STILL rendered and the
       last-seen state is annotated as `:current`.
    4. Caller-supplied `:snapshot-state` (the machine's live
       `:state` keyword, off `(get-in app-db [:rf/machines <id>])`).
       Used when the buffer carries no transition for this machine
       at all — most-recent-known state from the live snapshot.

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
  "Per-spec precedence (high → low): explicit > focused-epoch traces >
  epoch-history walk-back > live snapshot `:state` > nil.

  The walk-back + snapshot fallbacks are the case-B refinement per
  spec/021 §6.2 / §17.4.1 (rf2-dbi87) — even when the focused epoch
  has no transition, render the topology with the most-recent-known
  state annotated as `:current`."
  [machine-id current-state-path trace-events epoch-history snapshot-state]
  (or current-state-path
      (topology/current-state-from-traces trace-events machine-id)
      (topology/current-state-from-epoch-history epoch-history machine-id)
      ;; Live-snapshot fallback. The snapshot's `:state` slot is a
      ;; bare keyword per Spec 005 §State; coerce to a path vector
      ;; via `normalise-path` (vector / keyword / nil are all handled).
      (when (some? snapshot-state)
        (cond
          (keyword? snapshot-state) [snapshot-state]
          (vector? snapshot-state)  snapshot-state
          :else                     nil))))

(defn Topology
  "Render a machine's topology via xyflow. Args (map):

    :machine-id          — keyword; used for testid stamping +
                           trace-filter scoping.
    :definition          — machine definition map (required; nil
                           renders the no-definition fallback).
    :current-state-path  — optional explicit current-state path (a
                           vector of keywords). Highest-precedence
                           current-state source.
    :trace-events        — optional vector of focused-epoch trace
                           events. Used to derive the current-state +
                           `fired-this-epoch` edge highlights.
    :epoch-history       — optional oldest-first vector of epoch
                           records. Walked backwards for the most-
                           recent-known transition for this machine
                           when the focused epoch has none (case B
                           per spec/021 §6.2).
    :snapshot-state      — optional live snapshot state (a keyword or
                           a path vector — per Spec 005 §State).
                           Used as the final fallback for the
                           current-state ● annotation when neither the
                           focused epoch nor the history buffer carries
                           a transition for this machine.
    :height              — outer wrapper height (default `'320px'`).
                           xyflow requires a non-zero parent height.
    :show-controls?      — pass-through to wrapper (default true).
    :testid              — pass-through wrapper testid (default
                           `'rf-causa-machines-topology'`).

  Returns hiccup."
  [{:keys [machine-id definition current-state-path trace-events
           epoch-history snapshot-state height show-controls? testid]
    :or   {height          "320px"
           show-controls?  true
           testid          "rf-causa-machines-topology"}}]
  (let [cur-path  (resolve-current-state-path machine-id
                                              current-state-path
                                              trace-events
                                              epoch-history
                                              snapshot-state)
        fired-ids (topology/extract-fired-edge-ids trace-events machine-id)
        ;; Case-B detection (rf2-dbi87 / spec/021 §6.2): the focused
        ;; epoch fired no transitions for this machine. The view STILL
        ;; renders the topology — only the fired-this-epoch overlay is
        ;; absent. Surfaces as a data attribute so tests + downstream
        ;; views can assert the empty-state shape.
        no-transition-this-epoch? (empty? fired-ids)
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
             :data-no-transition-this-epoch (str no-transition-this-epoch?)
             :data-current-state-source (cond
                                          current-state-path "explicit"
                                          (topology/current-state-from-traces
                                            trace-events machine-id) "trace-events"
                                          (topology/current-state-from-epoch-history
                                            epoch-history machine-id) "epoch-history"
                                          (some? snapshot-state) "snapshot"
                                          :else "none")
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
