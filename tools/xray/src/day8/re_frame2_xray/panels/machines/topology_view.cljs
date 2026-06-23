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
  look. The pure `topology/project` projector + its deterministic grid
  layout (`topology/grid-positions`) are NOT on this render path; they
  survive as a self-contained, JVM-portable fallback projection +
  style catalogue (still unit-tested), but the live view never hands
  their output to React.

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
       `:state` keyword, off `(get-in runtime-db [:rf.runtime/machines :snapshots <id>])`).
       Used when the buffer carries no transition for this machine
       at all — most-recent-known state from the live snapshot.

  ## Pure-hiccup contract

  This view returns hiccup; it does not subscribe to re-frame
  directly — the parent panel is responsible for pulling the
  definition + traces off the substrate and passing them in. Keeps
  the view testable in isolation."
  (:require [day8.re-frame2-machines-viz.context-shape :as context-shape]
            [day8.re-frame2-xray.panels.machine-canvas :as machine-canvas]
            [day8.re-frame2-xray.panels.machines.topology :as topology]
            [day8.re-frame2-xray.panels.machines.trace-state :as trace-state]
            [day8.re-frame2-xray.theme.tokens :refer [tokens]]))

(defn static-context-shape
  "rf2-vcnvj; rf2-3q4k5b (EP-0005) — derive the STATIC context shape from a
  machine definition for the chart's root Context panel. Returns a map of
  `{key type-caption}` so the root chrome shows the operator the context
  KEYS + their TYPE shape (e.g. `{:opened-count \"number\" :trail
  \"vector\"}`) even with no live snapshot in hand. This is deliberately
  the SHAPE, not the live values — the live runtime `:data` overlay
  stays a separate diagnostic (per the bead). Returns nil when the
  definition declares neither a `[:schemas :data]` schema nor a map `:data` (so the
  panel stays hidden).

  Declared over inferred (rf2-3q4k5b): when the machine declares a
  `[:schemas :data]` schema, the shape is read AUTHORITATIVELY off the schema; otherwise
  it is INFERRED from one sample of the initial `:data`. The provenance is
  reported by `static-context-inferred?` (drives the chart's
  `:context-band-inferred?` badge gate). Delegates to the machines-viz
  `context-shape` helper so the derivation lives with the chart it feeds.

  Pure fn — testable in isolation."
  [definition]
  (:shape (context-shape/static-context-shape definition)))

(defn static-context-inferred?
  "rf2-3q4k5b (EP-0005) — true when `static-context-shape` for this definition
  is INFERRED from one sample of `:data` (rf2-5tz9p's caveat applies — the
  chart shows the `inferred from :data` badge); false when it is AUTHORITATIVE
  from a declared `[:schemas :data]` schema (the chart drops the inferred badge and shows
  a `declared` badge). Feeds the chart's `:context-band-inferred?` prop.

  Defaults to true when there is no shape at all, so a host that threads it
  unconditionally keeps the historical inferred-by-default posture. Pure."
  [definition]
  (let [result (context-shape/static-context-shape definition)]
    (if result (:inferred? result) true)))

(defn- resolve-current-state-path
  "Per-spec precedence (high → low): explicit > focused-epoch traces >
  epoch-history walk-back > live snapshot `:state` > nil.

  The walk-back + snapshot fallbacks are the case-B refinement per
  spec/021 §6.2 / §17.4.1 (rf2-dbi87) — even when the focused epoch
  has no transition, render the topology with the most-recent-known
  state annotated as `:current`.

  The live-snapshot fallback preserves ALL THREE Spec 005 `:state`
  arms so the chart's multi-active highlight (`chart.layout/highlight-ids`,
  threaded via `machine-canvas/Chart`'s `:current-state`) can light EVERY
  active leaf of a PARALLEL machine. A region-map (`{:data :loading
  :form :neutral}`) is passed through UNCHANGED — narrowing it to
  keyword/vector dropped the parallel snapshot before the chart could
  render the N-active highlight (a live parallel machine showed no
  active regions while the wrapper still claimed `data-current-state-source
  = \"snapshot\"`). See machines-viz `API.md` §`:current-state`."
  [machine-id current-state-path trace-events epoch-history snapshot-state]
  (or current-state-path
      (trace-state/current-state-from-traces trace-events machine-id)
      (trace-state/current-state-from-epoch-history epoch-history machine-id)
      ;; Live-snapshot fallback. The snapshot's `:state` slot is one of
      ;; the three Spec 005 `:state` arms: a flat keyword (`:authing`),
      ;; a hierarchical path (`[:auth :authing]`), or — for a PARALLEL
      ;; machine — a region-map (`{:data :loading :form :neutral}`) of N
      ;; simultaneously-active leaves. All three forward through to
      ;; `:current-state`; the chart resolves whichever arm via
      ;; `chart.layout/highlight-ids` (which subsumes all three).
      (when (some? snapshot-state)
        (cond
          (keyword? snapshot-state) [snapshot-state]
          (vector? snapshot-state)  snapshot-state
          ;; PARALLEL region-map — pass through unchanged so every active
          ;; region leaf lights up (the multi-active highlight). Dropping
          ;; it here is the rf2-di7mda bug: the chart CAN render N-active,
          ;; but the caller blanked the topology for a live parallel
          ;; machine.
          (map? snapshot-state)     snapshot-state
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
    :snapshot-state      — optional live snapshot state — any of the
                           three Spec 005 §State arms: a flat keyword
                           (`:authing`), a hierarchical path
                           (`[:auth :authing]`), or — for a PARALLEL
                           machine — a region-map (`{:data :loading
                           :form :neutral}`) of N simultaneously-active
                           leaves. Used as the final fallback for the
                           current-state ● annotation when neither the
                           focused epoch nor the history buffer carries
                           a transition for this machine. A region-map
                           forwards through to `:current-state` so the
                           chart lights EVERY active region leaf (the
                           multi-active highlight — rf2-di7mda).
    :height              — outer wrapper height (default `'100%'` so the
                           chart fills its container — the topology is the
                           centrepiece and should not sit in a fixed box).
                           A `min-height` floor keeps xyflow's non-zero-
                           parent-height requirement satisfied when the
                           container itself is auto-height.
    :show-controls?      — pass-through to `machine-canvas/Chart`
                           (default true).
    :fit-signal          — rf2-6tw7t. Opaque fit-on-entry nonce forwarded
                           to `machine-canvas/Chart`'s `:fit-signal`. A
                           host bumps it on panel-entry / tab-activation
                           so the topology re-frames to view. nil → inert.
    :testid              — testid stamped on the outer container; the
                           inner chart is stamped `<testid>-canvas` via
                           `machine-canvas/Chart`'s `:inner-testid`
                           (default `'rf-xray-machines-topology'`).

  Returns hiccup."
  [{:keys [machine-id definition current-state-path trace-events
           epoch-history snapshot-state height show-controls? testid
           fit-signal]
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
        ;; rf2-fzrzlw — the guard-blocked no-op edge ids for the focused
        ;; epoch. A guard-blocked transition emits NO `:rf.machine/transition`
        ;; (so `fired-ids` is empty for it), but the runtime DOES emit
        ;; `:rf.machine/guard-evaluated` fail/threw — so the attempted-and-
        ;; rejected edge can still be marked PINK on the chart.
        guard-blocked-ids (trace-state/extract-guard-blocked-edge-ids
                            definition trace-events machine-id)
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
         ;; rf2-xf5on — forward the fired-this-epoch edge ids the view
         ;; already computes (line ~166) into the chart so the traversed
         ;; edges paint the FIRED treatment. The docstring's `:trace-events`
         ;; contract promises `fired-this-epoch` edge highlights; `Chart`
         ;; exposes + forwards `:fired-edge-ids`, so the wiring must reach
         ;; it. `#{}` (case-B / no transition this epoch) → no fired
         ;; highlight, identical to passing none.
         :fired-edge-ids         fired-ids
         ;; rf2-fzrzlw — forward the guard-blocked no-op edge ids so the
         ;; attempted-and-rejected edge paints PINK even though it fired
         ;; no transition (the door :door/close blocked by :may-close?).
         :guard-blocked-edge-ids guard-blocked-ids
         ;; rf2-vcnvj — surface the STATIC context shape (keys + type
         ;; captions) so the root Context chrome renders on the blank-state
         ;; topology. nil when the machine declares neither a `[:schemas :data]`
         ;; schema nor a map `:data` (panel stays hidden).
         :context-band           (static-context-shape definition)
         ;; rf2-3q4k5b (EP-0005) — declared over inferred. False when the
         ;; shape came from a declared `[:schemas :data]` schema (authoritative — the
         ;; chart drops the `inferred from :data` badge); true when inferred
         ;; from one sample of `:data` (rf2-5tz9p's badge stays). The chart's
         ;; prop already defaults true, but threading it explicitly keeps the
         ;; declared path correct.
         :context-band-inferred? (static-context-inferred? definition)
         :show-after-rings?      false
         :show-controls?         show-controls?
         ;; rf2-6tw7t — fit-on-entry nonce pass-through; hosts that mount
         ;; this consumer surface inside a tab/panel bump it on entry so
         ;; the topology re-frames. nil → inert.
         :fit-signal             fit-signal
         :inner-testid           (str testid "-canvas")}]])))
