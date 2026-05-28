(ns day8.re-frame2-xray.panels.machines.topology-view
  "Reagent view ↔ xyflow projector composition (rf2-uwvyj · spec/021
  §6 + §17.4).

  This is the public consumer surface: the Static Machines panel's
  Topology mode (or any other Xray panel rendering a single
  machine's topology) mounts `[topology-view {:machine-id ...
  :definition ...}]` and gets the styled xyflow canvas back.

  ## Composition

      machine-id + definition  ──→  resolve current-state-path (4-source)
                                          │
                                          ▼ machine-canvas/Chart
                                          │     (mv-chart/MachineChart —
                                          │      xyflow + elkjs hierarchical
                                          │      layout, custom Stately-style
                                          │      nodes/edges + arrowheads)
                                          ▼
                                  <ReactFlow> in React tree

  ## Layout (rf2-5qsxo)

  This view renders through `machine-canvas/Chart` → the shared
  `mv-chart/MachineChart` so the blank-state (Case-B) topology gets the
  SAME elkjs hierarchical-layered layout, sized state nodes, arrowheads,
  and Controls chrome as the focused-event chart — the Stately/xstate
  look. The previous deterministic grid (`topology/grid-positions` via
  `xyflow-wrapper/xyflow-canvas`) is no longer the render path; the pure
  `topology/project` projector + its grid layout survive as a
  self-contained, JVM-portable fallback projection (still unit-tested)
  but are not on this view's hot path.

  ## current-state overlay

  Four sources feed the `current-state-path` arg (precedence high → low):

    1. Caller-supplied `:current-state-path` (explicit override).
    2. Caller-supplied `:trace-events` (the focused-epoch's
       `:rf.machine/transition` slice) — `trace-state/current-state-
       from-traces` resolves the `:to` of the most recent matching
       trace.
    3. Caller-supplied `:epoch-history` (oldest-first vector of epoch
       records) — `trace-state/current-state-from-epoch-history` walks
       backward through prior epochs for the most recent transition
       this machine took. This is the **case-B refinement** per
       spec/021 §6.2 / §17.4.1 (rf2-dbi87): when the focused epoch
       has no transition, the topology is STILL rendered and the
       last-seen state is annotated as `:current`.
    4. Caller-supplied `:snapshot-state` (the machine's live
       `:state` keyword, off `(get-in app-db [:rf/runtime :machines :snapshots <id>])`).
       Used when the buffer carries no transition for this machine
       at all — most-recent-known state from the live snapshot.

  ## Pure-hiccup contract

  This view returns hiccup; it does not subscribe to re-frame
  directly — the parent panel is responsible for pulling the
  definition + traces off the substrate and passing them in. Keeps
  the view testable in isolation."
  (:require [day8.re-frame2-xray.panels.machine-canvas :as machine-canvas]
            [day8.re-frame2-xray.panels.machines.topology :as topology]
            [day8.re-frame2-xray.panels.machines.trace-state :as trace-state]
            [day8.re-frame2-xray.theme.tokens :as t :refer [tokens]]))

(defn- resolve-current-state-path
  "Per-spec precedence (high → low): explicit > focused-epoch traces >
  epoch-history walk-back > live snapshot `:state` > nil.

  The walk-back + snapshot fallbacks are the case-B refinement per
  spec/021 §6.2 / §17.4.1 (rf2-dbi87) — even when the focused epoch
  has no transition, render the topology with the most-recent-known
  state annotated as `:current`."
  [machine-id current-state-path trace-events epoch-history snapshot-state]
  (or current-state-path
      (trace-state/current-state-from-traces trace-events machine-id)
      (trace-state/current-state-from-epoch-history epoch-history machine-id)
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
    :height              — outer wrapper height (default `'100%'` so the
                           chart fills its container — the topology is the
                           centrepiece and should not sit in a fixed box).
                           A `min-height` floor keeps xyflow's non-zero-
                           parent-height requirement satisfied when the
                           container itself is auto-height.
    :show-controls?      — pass-through to wrapper (default true).
    :testid              — pass-through wrapper testid (default
                           `'rf-xray-machines-topology'`).

  Returns hiccup."
  [{:keys [machine-id definition current-state-path trace-events
           epoch-history snapshot-state height show-controls? testid]
    :or   {height          "100%"
           show-controls?  true
           testid          "rf-xray-machines-topology"}}]
  (let [cur-path  (resolve-current-state-path machine-id
                                              current-state-path
                                              trace-events
                                              epoch-history
                                              snapshot-state)
        fired-ids (trace-state/extract-fired-edge-ids definition trace-events
                                                      machine-id)
        ;; Case-B detection (rf2-dbi87 / spec/021 §6.2): the focused
        ;; epoch fired no transitions for this machine. The view STILL
        ;; renders the topology — only the fired-this-epoch overlay is
        ;; absent. Surfaces as a data attribute so tests + downstream
        ;; views can assert the empty-state shape.
        no-transition-this-epoch? (empty? fired-ids)
        ;; rf2-5qsxo — node/edge counts come straight off the pure
        ;; `parse-definition` (no positions/styling needed just to count)
        ;; so the data attributes downstream tests assert still hold while
        ;; the actual render now flows through the elkjs chart.
        {:keys [nodes edges]} (topology/parse-definition definition)]
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

      (empty? nodes)
      [:div {:data-testid (str testid "-empty")
             :data-machine-id (str machine-id)
             :style {:padding "16px"
                     :color (:text-tertiary tokens)}}
       "Machine has no states."]

      :else
      [:div {:data-testid testid
             :data-machine-id (str machine-id)
             :data-current-state (when cur-path (pr-str cur-path))
             :data-node-count (str (count nodes))
             :data-edge-count (str (count edges))
             :data-no-transition-this-epoch (str no-transition-this-epoch?)
             :data-current-state-source (cond
                                          current-state-path "explicit"
                                          (trace-state/current-state-from-traces
                                            trace-events machine-id) "trace-events"
                                          (trace-state/current-state-from-epoch-history
                                            epoch-history machine-id) "epoch-history"
                                          (some? snapshot-state) "snapshot"
                                          :else "none")
             :style {:position "relative"
                     :width  "100%"
                     :height height
                     ;; rf2-zdfbm — non-zero-parent-height floor so xyflow
                     ;; still mounts when `:height` resolves against an
                     ;; auto-height container (the default is now `100%`,
                     ;; letting the topology fill its panel instead of
                     ;; sitting in a fixed 320px box).
                     :min-height "320px"
                     :background (:bg-1 tokens)
                     :border (str "1px solid " (:border-default tokens))
                     :border-radius "6px"
                     :overflow "hidden"}}
       ;; rf2-5qsxo — render through the shared elkjs MachineChart so the
       ;; Case-B blank-state topology gets the Stately/xstate look (sized
       ;; nodes, arrowheads, hierarchical layout, Controls). The resolved
       ;; current-state path drives the active-state highlight; there is
       ;; no focused-event lens here (no from/to highlight) and no
       ;; after-rings (no live timers on a blank epoch).
       [machine-canvas/Chart
        {:definition             definition
         :machine-id             machine-id
         :current-state          cur-path
         :show-after-rings?      false
         :show-view-mode-toggle? false
         :show-controls?         show-controls?
         :inner-testid           (str testid "-canvas")}]])))
