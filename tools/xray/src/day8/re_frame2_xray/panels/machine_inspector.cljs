(ns day8.re-frame2-xray.panels.machine-inspector
  "Machine Inspector panel — the Dynamic surface.

  The Dynamic Machines panel is
  **event-driven only**:

    - **BLANK** when the currently focused event is not machine-related
      (silent-by-default).
    - **When the focused event targeted a machine** the panel renders
      ONE section (the Dynamic-mode single-instance rule): the SHARED
      EVENT HANDLER mini-pipeline (microstep / guard / action rows) and
      the topology chart with FROM/TO highlighting and the `:after`
      countdown rings overlay (when armed timers exist).
    - **prev/next** affordance walks the spine's epoch-history to the
      prior / next event for THE FOCUSED MACHINE (not the full spine).

  ## What it does not carry

  The Dynamic panel's only job is to be the lens on the focused event,
  so it carries no ribbons: no Machine picker, no sub-strip (Topology /
  Sim / Instances / Cascade), no instance-tab or cluster views, no Sim
  ribbon, no Browse-all entry point, and no arc overlay or
  mini-scrubber. The Sim lives on the Static surface
  (`static/machines/sim.cljs`).

  ## What it carries

    - Topology chart (machines-viz `MachineChart`; xyflow + elkjs own
      layout).
    - Transition highlighting (from-state → to-state — dashed-origin /
      bold-landing visual grammar).
    - The SHARED EVENT HANDLER mini-pipeline (per-transition guard and
      action rows).
    - `:after` countdown rings overlay (when armed timers exist).
    - prev/next nav (per-machine epoch walking).

  ## Hiccup, and who renders it

  The markup is pure hiccup, but [[Panel]] is a `rf.fresco/defview`
  boundary rather than an `rf/reg-view`, so FRESCO renders it — not the
  substrate adapter installed via `rf/init!`. [[panel-tree]] holds the
  markup as a pure fn so the node lane can drive it.

  The topology chart mounts through `machine-canvas/Chart-view`, a Fresco
  head, so this panel needs no `as-element` island. Every helper in
  this file answers hiccup and is CALLED, never used as a hiccup head —
  the standard HD-016 repair, and what keeps this file to one boundary.

  Frame isolation comes from the
  enclosing `[rf/frame-provider {:frame :rf/xray}]` in `shell.cljs`,
  which writes the React context a boundary reads its frame from."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            ;; The snapshot-egress chokepoint (EP-0005). The
            ;; LIVE machine-snapshots sub reads the RAW runtime-db slot
            ;; `[:rf.runtime/machines :snapshots]` (EP-0001 —
            ;; machine snapshots are durable runtime-db state), which is NOT
            ;; egress-projected (it is the live frame-db value, not a trace). To
            ;; keep the panel's `:data` display honest, route each live
            ;; snapshot through the SAME `project-trace-event` chokepoint
            ;; the trace stream uses, so a `:sensitive?` / `:large?`
            ;; `[:schemas :data]` slot lands as `:rf/redacted` / the size
            ;; marker before any panel surface reads it — never raw.
            [re-frame.classification :as rf.classification]
            [re-frame.fresco :as rf.fresco]
            [day8.re-frame2-machines-viz.chart.layout :as chart-layout]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            ;; The SHARED EVENT HANDLER machine-cascade
            ;; mini-pipeline lives in the Epoch panel view; the Machine
            ;; tab consumes the SAME renderer + the SAME cascade
            ;; projection (no duplicate) so the two surfaces cannot
            ;; diverge.
            [day8.re-frame2-xray.panels.epoch.view :as epoch-view]
            [day8.re-frame2-xray.panels.epoch.projection :as epoch-proj]
            [day8.re-frame2-xray.panels.machine-canvas :as machine-canvas]
            [day8.re-frame2-xray.panels.machine-inspector-helpers :as h]
            ;; The declared-over-inferred static
            ;; Context-shape projection (EP-0005). The focused-event chart surfaces
            ;; the machine's declared `[:schemas :data]` Context shape (keys +
            ;; type captions) authoritatively, falling back to the
            ;; one-sample inference when no schema is declared — the SAME
            ;; `static-context-shape` / `static-context-inferred?` the
            ;; Static Topology view feeds its chart (no duplicate
            ;; derivation; both delegate to machines-viz `context-shape`).
            [day8.re-frame2-xray.panels.machines.topology-view :as topology-view]
            [day8.re-frame2-xray.panels.machines.trace-state :as trace-state]
            [day8.re-frame2-xray.panels.machine-after-rings :as after-rings]
            ;; The per-machine prev/next nav routes its focus
            ;; mutation through the spine's `focus-event-bundle-reducer` so the
            ;; jump stamps `:mode :retro` (and resolves the settling
            ;; dispatch-id) — a bare `[:focus :epoch-id]` write would be
            ;; silently overridden by `compose-focus`'s LIVE+unpaused
            ;; head-tracking, leaving the buttons dead on the live panel.
            [day8.re-frame2-xray.spine :as spine]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack spacing]]))

;; ---- section-level layout styles --------------------------------------
;;
;; Ns-top defs so the Panel + focused-event-section render
;; paths do not mint fresh `:style {...}` maps on every re-render. The
;; machine-inspector is event-driven (renders one focused-event-section
;; per cascade that transitioned a machine; BLANK otherwise), so the
;; allocation count per render is small relative to the per-row panels
;; (Trace, Cancellation cascade) — but the
;; hoist still removes ~10–15 layout/header allocations per Panel
;; re-render and matches the style-hoist pattern the other panels use.
;;
;; `tokens` values resolve to `var(--rf-xray-*)` CSS strings at ns load,
;; so the active theme toggle flips palette in lockstep
;; without re-evaluation.

(def ^:private panel-root-style
  "Outer `[:section]` chrome for the Machine Inspector panel."
  {:height         "100%"
   :display        "flex"
   :flex-direction "column"
   :background     (:bg-2 tokens)
   :color          (:text-primary tokens)
   :font-family    sans-stack
   :font-size      "14px"})

(def ^:private panel-header-style
  "Top header strip — carries the prev/next nav on the right (the L4
  tab strip is the panel-name source-of-truth, so there is no large
  title; Prev/Next is the only header affordance)."
  {:padding         "16px 16px 8px 16px"
   :display         "flex"
   :align-items     "center"
   :justify-content "space-between"
   :gap             "12px"})

(def ^:private panel-header-toolbar-style
  "Right-hand affordance cluster inside the panel header (prev/next).
  Suppressed when `:no-machines` empty-state is rendering."
  {:display     "flex"
   :align-items "center"
   :gap         "8px"})

(def ^:private focused-event-host-style
  "Wrapper around the focused-event view inside the Panel `cond`'s
  `(seq records)` branch. Flex column so the focused-event
  view fills the host and the topology chart grows into the panel
  height."
  {:flex           1
   :overflow       "auto"
   :display        "flex"
   :flex-direction "column"})

(def ^:private focused-event-view-host-style
  "Wrapper around the single focused-event-section the
  `focused-event-view` renders. Mirrors the host wrapper above so the
  section's `flex 1` rule has a tall column to grow into."
  {:display        "flex"
   :flex-direction "column"
   :flex           1
   :min-height     0})

(def ^:private focused-event-section-style
  "Outer `[:section]` chrome for one per-machine focused-event
  section: a gap between sibling sub-panels, a 16px margin from the
  panel host edge, and a flex column so the topology chart can grow."
  {:margin         (:gap-4 spacing)
   :border         (str "1px solid " (:border-default tokens))
   :border-radius  "4px"
   :background     (:bg-2 tokens)
   :flex           "1 1 0"
   :min-height     0
   :display        "flex"
   :flex-direction "column"
   :gap            (:gap-2 spacing)})


;; ---- live-snapshot egress redaction (EP-0005) ---------------------------
;;
;; The LIVE machine snapshots read straight off the runtime-db slot
;; `[:rf.runtime/machines :snapshots]` (EP-0001) have NOT passed through the
;; snapshot-egress redactor the `re-frame.classification` trace projection (that
;; runs at trace-emit, on the trace stream — not on a direct frame-db
;; read). A machine declaring a `:sensitive?` / `:large?` slot in its
;; `[:schemas :data]` schema (EP-0005) has those slots redacted in
;; EVERY transition / snapshot trace, but a raw frame-db read would still
;; surface them. `redact-live-snapshots` routes each `{:state :data}`
;; snapshot through the SAME `project-trace-event` chokepoint as a
;; synthetic `:rf.machine/snapshot-updated` event so the `:data` the
;; panel surfaces is identically redacted to the trace path.

(defn- redact-snapshot
  "Redact one live machine snapshot `{:state :data …}` for `machine-id`
  through the snapshot-egress chokepoint. Wraps the snapshot as a
  synthetic `:rf.machine/snapshot-updated` trace event — stamped with the
  TARGET `frame-id` — and runs `rf.classification/project-trace-event`, which calls
  `project-machine-tags` to redact `:snapshot.data` against the frame's
  classified snapshot-path declarations in the per-frame elision registry.
  EP-0025: machine `:data` classification rides the projection-relative
  machine declaration (lowered per actor under `:source :effect`) / the
  commit-plane classification effects — there is no frame `:sensitive` /
  `:large {:app-db …}` annotation. A frame that classifies no matching
  `:data` path leaves the snapshot unchanged. Returns the redacted snapshot
  (or the input verbatim when it is not a map, or when redaction is
  unavailable)."
  [frame-id machine-id snapshot]
  (if-not (map? snapshot)
    snapshot
    (let [ev  {:operation :rf.machine/snapshot-updated
               :tags      {:machine-id machine-id
                           :frame      frame-id
                           :snapshot   snapshot}}
          out (try (rf.classification/project-trace-event ev)
                   (catch :default _ ev))]
      (or (get-in out [:tags :snapshot]) snapshot))))

(defn- redact-live-snapshots
  "Redact a `{machine-id snapshot}` map of LIVE snapshots, per-slot, so a
  FRAME-declared sensitive / large `:data` path is `:rf/redacted` / size-
  marked before any panel surface reads it (EP-0025). `frame-id` is the
  inspected (target) frame whose declarations classify the snapshot paths.
  nil-safe; preserves nil snapshot values (uninitialised machines)."
  [frame-id snapshots]
  (when (map? snapshots)
    (reduce-kv (fn [acc machine-id snapshot]
                 (assoc acc machine-id (redact-snapshot frame-id machine-id snapshot)))
               {}
               snapshots)))


;; ---- what the Machine tab renders ---------------------------------------
;;
;; The Machine tab renders EXACTLY Prev/Next + the SHARED EVENT
;; HANDLER mini-pipeline (`epoch-view/machine-cascade-mini-pipeline` —
;; the SAME richer microstep-cascade renderer the Epoch panel uses, so
;; the two surfaces cannot diverge) + the chart (which carries its
;; own zoom/pan/fit toolbar). There is no bespoke forensic lens, snapshot
;; drill-in or chart-collapse chrome.

;; ---- per-machine focused-event section ---------------------------------

;; ---- per-mount inspector identity ---------------------------------------

(def ^:private owner-token
  "What this panel calls itself inside the SHARED cascade renderer's id
  namespace. See [[instance-token]] for why it is unconditional."
  "machine-inspector")

(defn instance-token
  "Normalise [[Panel]]'s optional `:instance-id` prop to the string that
  qualifies this mount's inspector `:mount-id`s inside the shared
  `epoch-view/machine-cascade-mini-pipeline`.

  ## IT NEVER ANSWERS NIL, AND THAT IS THE DIFFERENCE FROM EVERY SIBLING

  `app-db-diff`, `managed-fx`, `trace` and the Epoch panel all answer nil
  for an unnamed mount, so their ids carry no qualifier.
  Those panels each own the id namespace they compose into. THIS ONE DOES
  NOT: element 2 renders through the Epoch panel's cascade renderer
  (deliberately — so the two surfaces cannot diverge), and that
  renderer composes `epoch/machine-cascade-*` ids. An unqualified Machine
  Inspector and an Epoch panel over one cascade would therefore collide on
  the widget's lifecycle key and its width slot with NEITHER caller having
  done anything unusual, and neither could see it to work around it.

  Which panel is rendering is STATICALLY KNOWN, so it is answered
  statically: unnamed, this is [[owner-token]]; named, the caller's token
  rides BELOW it (`machine-inspector/left`) so two Machine Inspectors are
  also distinct, and so a Machine Inspector named `left` cannot collide
  with an Epoch panel named `left` either.

  A KEYWORD is accepted alongside a string, and its NAMESPACE is part of
  the name: `:left/machines` tokenises to `left/machines`. `(subs (str id)
  1)` is what preserves it; `cljs.core/name` would drop it and reintroduce
  the very collision this prevents — see [[Panel-bridge]], which
  tokenises BEFORE the Reagent crossing for exactly that reason. The fn is
  IDEMPOTENT on its own output, so the boundary's second call after the
  crossing is a no-op.

  It is this panel's own normaliser rather than a call into a sibling's:
  the panels are independent surfaces, they change on their own
  schedules, and the refusal has to name the caller's OWN panel to be
  worth reading."
  [instance-id]
  (let [named (cond
                (nil? instance-id)     nil
                (keyword? instance-id) (subs (str instance-id) 1)
                (string? instance-id)  (when (seq instance-id) instance-id)
                :else
                (throw (ex-info
                         (str "The Machine Inspector's :instance-id must be a "
                              "non-blank string or a keyword naming this "
                              "mount, or omitted. Got: " (pr-str instance-id))
                         {:rf.xray/instance-id instance-id})))]
    (cond
      (nil? named)                     owner-token
      (= named owner-token)            owner-token
      (str/starts-with? named (str owner-token "/")) named
      :else                            (str owner-token "/" named))))

(defn- focused-event-section
  "Render the focused machine's section. The
  Machine tab shows EXACTLY THREE elements: the Prev/Next nav (in
  the Panel header), the SHARED EVENT HANDLER mini-pipeline, and the
  topology chart. This section renders the latter two:

    1. the SHARED mini-pipeline — `epoch-view/machine-cascade-mini-
       pipeline` renders the focused epoch's already-projected
       machine-cascade (`cascade`, from
       `:rf.xray/machine-focused-epoch-cascade` which uses the SAME
       `epoch-proj/machine-cascade-rows` projection the Epoch panel
       does) into the SAME numbered cascade the Epoch panel's EVENT
       HANDLER step renders (microstep / guard / action rows with
       KIND+PHASE badges, verb links, source bodies, outcomes /
       data-writes). It is byte-for-byte the same renderer — no second
       bespoke forensic block.
    2. the chart — `machine-canvas/Chart-view` with the focused epoch's
       from/to/current/fired highlights. The chart carries its own
       zoom/pan/fit toolbar, so there is no bespoke collapse chrome.

  There is no bespoke focused-transition lens (Target Machine Instance /
  TRANSITION / GUARDS RUN / ACTIONS RUN), per-machine header ribbon,
  list/canvas view-mode wrapper, chart-collapse toggle + summary,
  snapshot drill-in or inline cancellation-cascade block: the shared
  mini-pipeline above the chart and the chart's own toolbar cover them.

  THIS PANEL HAS NO ISLAND. `machine-canvas/Chart`
  is an `rf/reg-view`, and a `reg-view` head grades `:invalid` under
  Fresco's codec down the IDENTICAL arm a plain `defn` does — the codec
  reads one own property, `frescoBoundary`, which only `rf.fresco/defview`
  sets. `machine-canvas/Chart-view` ships that boundary beside the
  `reg-view`, both one call to the same `machine-canvas/chart-tree`, so
  this panel heads it directly and no fn here takes an `as-child`
  parameter.

  The `reg-view` serves the two Static consumers, which reach the
  chart from inside `static/machines/definition_detail.cljs`'s own island
  and whose only inward door would convert the props map's values;
  nothing about it reaches this panel.

  The island lives ONE LEVEL DOWN, in `machine-canvas/chart-tree`, which
  crosses the machines-viz chart itself. Its end condition is
  machines-viz shipping a substrate-neutral head, which is not this
  tree's to schedule.

  ELEMENT 2 needs no island either — every head reachable through
  `epoch-view/machine-cascade-mini-pipeline` is head-free.

  `fit-signal` ARRIVES AS AN ARGUMENT; [[Panel]] reads
  `:rf.xray/machine-tab-fit-signal`. HD-016 would allow the read to sit
  here and donate upward, but
  a `rf.fresco/sub` raises outside a collector window, which would make
  this fn callable only inside a real React commit — and its markup is
  ordinary data → data that the node lane drives."
  [cascade
   {:keys [machine-id from-state to-state definition fired-edge-ids
           guard-blocked-edge-ids start? no-op?]
    :as _record}
   fit-signal
   instance]
  ;; xyflow + elkjs own positioning end-to-end inside `MachineChart`, so
  ;; there is no host-side ELK layout here. The panel only computes the
  ;; from/to node-ids for the data-attr highlight pins the tests read.
  (let [;; Render-time frame capture for the deferred chart
        ;; state-click dispatch.
        frame      (rf/current-frame-id)
        ;; A NO-OP suppresses the from→to highlight grammar
        ;; (no edge; the machine stayed put). The wrapper's highlight-id
        ;; data-attrs therefore read "" for a no-op, matching the chart
        ;; props below; the current state is surfaced via `:current-state`.
        from-id    (when (and from-state (not no-op?))
                     (chart-layout/highlight-id from-state))
        to-id      (when (and to-state (not no-op?))
                     (chart-layout/highlight-id to-state))
        ;; Fit-on-entry nonce (arrives as an argument). Bumped by
        ;; `:rf.xray/select-tab :machines`;
        ;; forwarded to the chart's `:fit-signal` so the topology
        ;; re-frames whenever the operator (re-)enters the Machine tab,
        ;; even when the focused machine (hence the chart's layout-key)
        ;; is unchanged.
        engine     "xyflow+elkjs"]
    [:section
     {:data-testid (str "rf-xray-machine-focused-event-section-"
                        (when machine-id
                          (subs (str machine-id) 1)))
      :data-machine-id (str machine-id)
      :data-from-state (str from-state)
      :data-to-state (str to-state)
      ;; BIRTH + NO-OP record markers sit on the
      ;; section so tests + hosts can pin that a `:rf.machine/started` /
      ;; `:rf.machine.event/unhandled-no-op` epoch renders the topology
      ;; (initial / current state highlighted) rather than the empty state.
      :data-start (str (boolean start?))
      :data-no-op (str (boolean no-op?))
      ;; The section is a flex column: the SHARED mini-pipeline
      ;; sits at the top (its natural height) and the chart grows into the
      ;; remaining height below it.
      :style focused-event-section-style}
     ;; ── ELEMENT 2 — the SHARED EVENT HANDLER mini-pipeline ──────────
     ;; The SAME renderer the Epoch panel's EVENT HANDLER
     ;; step uses (`epoch-view/machine-cascade-mini-pipeline`),
     ;; rendering the focused epoch's projected numbered
     ;; machine-cascade (microstep / guard / action rows with KIND+PHASE
     ;; badges, verb links, source bodies, outcomes / data-writes). Single
     ;; source of truth — the Machine tab and the Epoch panel cannot
     ;; diverge.
     [:div {:data-testid "rf-xray-machine-event-handler-mini-pipeline"
            :data-machine-id (str machine-id)
            :style {:padding "10px 14px"}}
      ;; `machine-id` IS the `:event` handler id `handler-meta` resolves
      ;; (a machine is registered as an `:event` handler carrying its spec
      ;; under `:rf/machine`), so the cascade rows' guard / action
      ;; source-coords resolve identically to the Epoch panel.
      (epoch-view/machine-cascade-mini-pipeline cascade machine-id instance)]
     ;; ── ELEMENT 3 — the topology chart ─────────────────────────────
     ;; The chart carries its OWN toolbar (zoom / pan / fit controls —
     ;; `machine-canvas/Chart-view`), so there is no bespoke list/canvas
     ;; wrapper or chart-collapse toggle/summary.
     ;; Highlights flow as reactive props off THIS focused
     ;; epoch, so Prev/Next repaints the chart together with the
     ;; mini-pipeline above.
     (if (nil? definition)
       [:div {:data-testid "rf-xray-machine-focused-event-no-definition"
              :style {:padding "12px"
                      :font-family sans-stack
                      :font-size "11px"
                      :color (:text-tertiary tokens)}}
        "No introspectable definition — chart cannot render."]
       [:div {:data-testid "rf-xray-machine-focused-event-chart"
              :data-layout-engine engine
              :data-machine-id (str machine-id)
              :data-from-highlight-id (or from-id "")
              :data-to-highlight-id (or to-id "")
              ;; The focused epoch's fired edge-ids on
              ;; the canvas wrapper (sorted, space-joined) so the
              ;; JVM/hiccup suite + hosts pin the wiring without reaching
              ;; into the xyflow canvas. "" when none fired.
              :data-fired-edge-ids (str/join " " (sort (set fired-edge-ids)))
              ;; The focused epoch's guard-blocked no-op edge
              ;; ids on the canvas wrapper (sorted, space-joined) so the
              ;; JVM/hiccup suite + hosts pin the wiring without reaching
              ;; into the xyflow canvas. "" when none blocked.
              :data-guard-blocked-edge-ids (str/join " " (sort (set guard-blocked-edge-ids)))
              ;; Fill the section's remaining height so the
              ;; topology chart expands into the panel. `flex 1` +
              ;; `min-height` floor keeps xyflow's non-zero-parent-height
              ;; requirement satisfied when the panel is short.
              :style {:background (:bg-2 tokens)
                      :display "flex"
                      :flex-direction "column"
                      :overflow "hidden"
                      :position "relative"
                      :flex "1 1 0"
                      :min-height "320px"}}
        [:div {:style {:flex "1 1 0"
                       :min-height 0
                       :padding "12px"
                       :background (:bg-1 tokens)
                       :display "flex"
                       :flex-direction "column"
                       ;; position-relative so the after-rings overlay can
                       ;; absolute-position itself over the chart SVG.
                       :position "relative"}}
         ;; The chart wraps an interactive viewport adapter
         ;; (zoom/pan/fit + controls toolbar) and owns the after-rings
         ;; overlay so they stay co-located with the canvas.
         ;;
         ;; `Chart-view` is the FRESCO head of the same
         ;; `machine-canvas/chart-tree` the `reg-view` `Chart` renders, so
         ;; this mount is an ordinary boundary head and crosses no
         ;; `as-child` island. The props map reaches it BY IDENTITY, which
         ;; is why the two-heads-one-body shape was needed rather than an
         ;; `as-component` bridge: a Reagent parent's `[:>]` converts first
         ;; and this map is nothing but values a conversion would destroy.
         [machine-canvas/Chart-view
          {:definition         definition
           :machine-id         machine-id
           ;; Surface the AUTHORITATIVE declared (EP-0005)
           ;; Context shape (keys + type captions) in the focused-event
           ;; chart's root Context band, with the declared-vs-inferred
           ;; indicator. When the machine declares a `[:schemas :data]` schema the
           ;; shape is read off the schema and `:context-band-inferred?`
           ;; is FALSE (the chart drops the `inferred from :data` badge and
           ;; shows `declared` — consistent with the Static Topology
           ;; view); absent a schema it falls back to the
           ;; one-sample inference (the inferred badge shows). This is the
           ;; SHAPE, not live `:data` VALUES — the live runtime `:data`
           ;; surfaces (egress-redacted) through the SHARED mini-pipeline's
           ;; cascade rows above, never raw here.
           :context-band       (topology-view/static-context-shape definition)
           :context-band-inferred? (topology-view/static-context-inferred? definition)
           ;; A NO-OP has no from→to edge; suppress the
           ;; from/to highlight grammar and surface the CURRENT state via
           ;; `:current-state` instead.
           :from-highlight     (when-not no-op? from-state)
           :to-highlight       (when-not no-op? to-state)
           ;; A BIRTH's initial state and a
           ;; NO-OP's unchanged current state both ride `:current-state`
           ;; so the chart highlights the one resting node.
           :current-state      (cond
                                 start? to-state
                                 no-op? to-state
                                 :else  nil)
           ;; The traversed edges paint the FIRED
           ;; treatment on the live chart.
           :fired-edge-ids     fired-edge-ids
           ;; The attempted-and-rejected edges (guard-blocked
           ;; no-op, e.g. door :door/close blocked by :may-close?) paint
           ;; the PINK guard-blocked treatment on the live chart so the
           ;; operator sees which edge the event hit + that a guard
           ;; rejected it (no transition fired, so the fired set is empty).
           :guard-blocked-edge-ids guard-blocked-edge-ids
           ;; Fit-on-entry nonce so re-entering the Machine
           ;; tab re-frames the topology.
           :fit-signal         fit-signal
           :on-state-click     (fn [path]
                                 (rf/dispatch
                                   [:rf.xray/machine-state-clicked
                                    {:machine-id machine-id
                                     :path       path}]
                                   {:frame frame}))
           :show-after-rings?  true}]]])]))

;; ---- prev/next nav (per-machine epoch walking) -------------------------

(defn- prev-next-nav
  "Inline prev/next buttons for the currently-focused machine. Walks
  the epoch history to the prior / next epoch that ALSO touched the
  focused machine. Disabled when no machine is in scope."
  [machine-id]
  ;; Render-time frame capture for the deferred nav clicks.
  (let [frame (rf/current-frame-id)]
   (when machine-id
    [:div {:data-testid "rf-xray-machine-inspector-prev-next-nav"
           :data-machine-id (str machine-id)
           :style {:display "flex"
                   :align-items "center"
                   :gap "6px"
                   :margin-left "auto"}}
     [:button
      {:data-testid "rf-xray-machine-inspector-prev"
       :on-click    (fn [_]
                      (rf/dispatch [:rf.xray/machine-focus-prev]
                                   {:frame frame}))
       :title       (str "Previous event touching " (h/format-machine-id machine-id))
       :style       {:background "transparent"
                     :border (str "1px solid " (:border-default tokens))
                     :color (:accent tokens)
                     :font-family sans-stack
                     :font-size "11px"
                     :padding "3px 10px"
                     :border-radius "10px"
                     :cursor "pointer"}}
      "◀ Prev"]
     [:button
      {:data-testid "rf-xray-machine-inspector-next"
       :on-click    (fn [_]
                      (rf/dispatch [:rf.xray/machine-focus-next]
                                   {:frame frame}))
       :title       (str "Next event touching " (h/format-machine-id machine-id))
       :style       {:background "transparent"
                     :border (str "1px solid " (:border-default tokens))
                     :color (:accent tokens)
                     :font-family sans-stack
                     :font-size "11px"
                     :padding "3px 10px"
                     :border-radius "10px"
                     :cursor "pointer"}}
      "Next ▶"]])))

;; ---- focused-event view + blank state ----------------------------------

(defn- focused-event-view
  "Top-level focused-event view. Accepts the focused-event
  `records` (pre-derefed by `Panel` from
  `:rf.xray/machine-transitions-for-focused-event`) and the focused
  epoch's `cascade` (the projected machine-cascade rows the SHARED
  mini-pipeline renders, off `:rf.xray/machine-focused-epoch-cascade`).
  Binds the panel to **exactly one** machine instance per the
  Dynamic-mode single-instance rule — that record drives the
  chart highlights, while the cascade rows show the WHOLE focused
  epoch's machine cascade (identical to the Epoch panel's EVENT HANDLER
  step). Returns nil when no machine transitioned in the focused event's
  cascade — the panel renders the empty-state placeholder in that case
  (see `blank-state`).

  `record` ARRIVES AS AN ARGUMENT rather than being picked
  here. [[panel-tree]] resolves it ONCE, through
  `h/pick-focused-transition`, and hands the same value to this view and
  to the Prev/Next nav beside it. That is deliberate and structural: the
  nav's buttons are LABELLED with their machine (\"Previous event
  touching …\"), so a nav scoped by one rule and a chart drawn by
  another would put a machine's name above a different machine's
  topology. One value,
  computed once, cannot drift from itself. `records` is here only for the
  cascade transition count the host records.

  `records` flows in as an arg so the panel reads the
  composite once per render, not twice.

  `fit-signal` is threaded straight through to
  [[focused-event-section]], the only place it is used; see that fn's
  docstring for why the nonce is an argument. `target-frame` likewise
  ARRIVES AS AN ARGUMENT rather than being read here, for the same reason:
  a `rf.fresco/sub` raises outside a collector window, and this fn is
  node-lane-driven."
  ;; `target-frame` is the
  ;; inspected frame id. Part of the STRUCTURAL section key below so the
  ;; L1 frame picker (which re-seeds the panel against a different
  ;; runtime) gets a clean section instance, while ordinary Prev/Next
  ;; within one frame+machine preserves it.
  [record records cascade fit-signal target-frame instance]
  (let [cascade-transition-count (count records)]
    (when record
      [:div {:data-testid "rf-xray-machine-focused-event"
             ;; The host carries the count of records the cascade
             ;; transitioned (1..N) but only the focused instance
             ;; renders — pinned so tests can assert the rule (one
             ;; section even when N > 1).
             :data-section-count "1"
             :data-cascade-transition-count (str cascade-transition-count)
             :style focused-event-view-host-style}
       ;; STRUCTURAL key (target-frame + machine-id), NOT
       ;; per-epoch. A key embedding `(:id)` / `(:from-state)` /
       ;; `(:to-state)`, all of which change on every Prev/Next, would make
       ;; React remount the whole section + the nested MachineChart on each
       ;; navigation — discarding the chart's per-instance parse/layout
       ;; caches and re-running ELK every time (a topology flicker).
       ;; Highlights flow as reactive props (`:from-highlight` /
       ;; `:to-highlight` / `:current-state` / `:fired-edge-ids`) and the
       ;; section's `:data-*` attrs recompute from `record` on each
       ;; ordinary re-render, so the per-epoch repaint needs no remount.
       ;; Re-fitting on navigation rides the orthogonal `:fit-signal`
       ;; nonce. See `h/focused-event-section-key`.
       ;; KEYED FRAGMENT rather than `with-meta` on the vector
       ;; the call returns. Reagent's `get-react-key` reads that metadata,
       ;; but Fresco's codec takes a literal `:key` from an ATTRIBUTE MAP
       ;; and reads Clojure metadata nowhere — so under a boundary this
       ;; section would keep one identity across epochs and the remount
       ;; this key exists to force would silently never happen.
       ;; `focused-event-section` answers hiccup whose own attribute map
       ;; is not ours to write into, so the key rides the fragment.
       [:<> {:key (h/focused-event-section-key target-frame record)}
        (focused-event-section cascade record fit-signal instance)]])))

(defn- blank-state
  "Rendered when the focused event has no machine activity in its
  cascade. Per spec/003 §Empty state — focused event does not target a
  state machine the panel renders ONLY the verbatim
  placeholder text — no chart, no lens, no history ribbon, no machine
  name, no instance picker, no hint. Just the single line:

      This event does not target a state machine

  Visual treatment: centered in the panel viewport, body weight,
  muted-foreground colour token per 007-UX-IA (matching the quiet
  empty-state pattern other Xray panels use)."
  []
  [:div {:data-testid "rf-xray-machine-inspector-blank"
         :style {:padding "16px"
                 :color (:text-tertiary tokens)
                 :font-family sans-stack
                 :font-size "14px"
                 :flex 1
                 :display "flex"
                 :flex-direction "column"
                 :align-items "center"
                 :justify-content "center"
                 :text-align "center"}}
   [:p {:data-testid "rf-xray-machine-inspector-blank-message"
        :style {:margin 0
                :font-weight 600
                :color (:text-tertiary tokens)}}
    h/empty-state-text]])

;; ---- empty state (no machines registered at all) -----------------------

(defn- empty-state
  "Rendered when no `:rf/machine?` registration is found — either the host
  app has not yet called `reg-machine`, or `day8/re-frame2-machines`
  is not on the classpath."
  []
  [:div {:data-testid "rf-xray-machine-inspector-empty"
         :style {:padding "16px"
                 :color (:text-tertiary tokens)
                 :font-family sans-stack
                 :font-size "13px"}}
   [:p {:style {:margin "0 0 8px 0"}}
    "No machines registered."]
   [:p {:style {:margin 0 :font-size "12px"}}
    "Register a machine with "
    [:code {:style {:font-family mono-stack :color (:accent tokens)}}
     "rf/reg-machine"]
    " to populate this panel."]])

;; ---- public view --------------------------------------------------------

(defn panel-tree
  "The Machine Inspector's markup, as a pure fn of the five values
  [[Panel]] reads plus its instance token. Shows EXACTLY THREE elements
  when the focused event targets a machine:

    1. the Prev/Next epoch nav (header — per-machine epoch walker),
    2. the SHARED EVENT HANDLER mini-pipeline (the SAME numbered
       machine-cascade the Epoch panel renders), and
    3. the topology chart (with its own toolbar + the focused epoch's
       highlights).

  Event-driven: BLANK when the focused event has no machine activity.
  Prev/Next moves the spine focus, which re-feeds the mini-pipeline AND
  the chart highlights together (both read the focused epoch).

  It lives apart from [[Panel]] by `defview`'s own documented
  extract-a-helper spelling rather than an invention. A
  boundary's body may only run inside a React render window, so `(Panel)`
  is not a callable that answers hiccup — while this panel's
  section algebra is ordinary data → data and is worth testing in the
  fast node lane rather than behind a real React commit.
  `machine_inspector_view_cljs_test` drives THIS fn with the values it
  takes from the subs directly; the boundary's own behaviour — first
  paint, liveness, frame targeting and teardown — is
  `machine_inspector_fresco_boundary_dom_cljs_test`'s subject.

  PURE: every helper it calls is a plain fn of its arguments.
  [[focused-event-view]]'s target-frame and [[focused-event-section]]'s
  fit-signal are consumed deep in the tree yet arrive as arguments, read
  once in [[Panel]]. HD-016 would let them donate upward instead, and
  that would be correct in production; they are arguments because a
  `rf.fresco/sub` raises outside a collector window, which would make
  this whole tree callable only inside a real React commit."
  ;; The 5-arity serves a direct caller that has no instance to name, and
  ;; answers this panel's OWN token for it rather than nil: the cascade
  ;; ids below are composed in the Epoch panel's id namespace, so
  ;; `no instance` still has to say which panel is rendering.
  ([data records cascade fit-signal target-frame]
   (panel-tree data records cascade fit-signal target-frame
               (instance-token nil)))
  ([{:keys [empty-kind selected-machine-id]} records cascade fit-signal
    target-frame instance]
  (let [;; THE ONE PLACE the Dynamic panel decides which
        ;; machine it is bound to. Both consumers below read THIS value:
        ;; the focused-event view (the chart) takes the record, and the
        ;; prev/next nav takes its machine-id. Two separate
        ;; `(first records)` spellings would agree only by coincidence
        ;; of both being `first`, and an explicit selection outranks
        ;; trace order, so they would disagree — the nav labelled
        ;; "Previous event touching A" over a chart drawing B.
        ;; Resolved once here, they cannot disagree.
        ;;
        ;; `selected-machine-id` is `project-data`'s RAW slot echo, NOT
        ;; its `:selected-id` — the effective one falls back to the
        ;; alphabetically-first machine, which would outrank trace order
        ;; when the operator has chosen nothing. See that fn's docstring.
        focused-record   (h/pick-focused-transition records
                                                   selected-machine-id)
        ;; The bound machine drives the prev/next nav (a cascade may
        ;; touch multiple machines; the nav's "this machine" is the
        ;; machine the panel is actually drawing).
        scope-machine-id (:machine-id focused-record)]
    [:section {:data-testid "rf-xray-machine-inspector"
               :data-view-mode "focused-event"
               :data-has-records (str (boolean (seq records)))
               :style panel-root-style}
     [:header {:data-testid "rf-xray-machine-inspector-header"
               :style panel-header-style}
      ;; There is no h1 heading: the L4 tab strip is the panel-name
      ;; source-of-truth. The header row carries the per-machine
      ;; prev/next nav on the right, its only toolbar affordance.
      [:div]
      (when (not= :no-machines empty-kind)
        [:div {:style panel-header-toolbar-style}
         (prev-next-nav scope-machine-id)])]
     (cond
       (= :no-machines empty-kind)
       (empty-state)

       (seq records)
       ;; Flex column so the focused-event view fills the
       ;; host and the topology chart grows into the panel height.
       ;; Pass `records` through so `focused-event-view`
       ;; does not duplicate-subscribe the same composite handle.
       [:div {:data-testid "rf-xray-machine-inspector-focused-event-host"
              :style focused-event-host-style}
        (focused-event-view focused-record records cascade fit-signal
                            target-frame instance)]

       :else
       (blank-state))])))

(rf.fresco/defview Panel
  "The Machine Inspector (Machine tab) root — a FRESCO BOUNDARY,
  not an `rf/reg-view`. The FIVE READS, and
  [[panel-tree]] for everything below them.

  The READS are `rf.fresco/sub` — plain calls the shipped collector
  records an edge for, with no deref and no reaction owned by the
  INSTALLED adapter, a coupling a first-paint smoke test cannot see.

  ALL FIVE OF THE PANEL'S READS ARE IN THIS BODY. Two of them are
  consumed deep in the tree ([[focused-event-view]]'s target-frame and
  [[focused-event-section]]'s fit-signal) and HD-016 would let them
  DONATE upward into this window, which is settled rather than contingent
  and would be correct. They are read here for a reason about
  TESTING rather than correctness: a `rf.fresco/sub` raises
  `:rf.error/fresco-sub-outside-render` outside a collector window, so a
  helper performing one is callable only inside a real React commit — and
  this panel's markup is ordinary data → data with a large fast node-lane
  suite over it. The cost is that both are read on every panel render
  rather than only when a focused-event section happens to render; neither
  widens invalidation in practice.

  The FRAME they resolve against comes from React context, which the
  enclosing frame boundary writes — `rf/frame-provider` and
  `rf.fresco/frame-provider` write the SAME context — so this boundary
  resolves `:rf/xray` identically under the Fresco root Xray owns
  and under an `rf/frame-provider` a Reagent parent writes.

  THIS PANEL HEADS NO ISLAND. The topology chart is
  `machine-canvas/Chart-view` — the Fresco head of the same body the
  `reg-view` `Chart` renders; [[focused-event-section]] says why that
  suffices. The island the chart needs for the machines-viz component
  lives inside `machine-canvas`, one level below anything this panel
  hands down.

  The argument is the ordinary one-props-map vector every `defview` takes.

  ## `:instance-id` — OPTIONAL, and this panel's default is NOT nil

  This panel reads no DATA from props: everything it renders comes from the
  five subs below. The one prop it takes is an IDENTITY, and it is passed
  to [[instance-token]] — whose contract differs from every sibling's in
  one deliberate way, for a reason that is about this panel's position
  rather than about taste.

  Element 2 is the SHARED mini-pipeline, `epoch-view/machine-cascade-mini-
  pipeline`, the very renderer the Epoch panel's EVENT HANDLER step uses.
  Its inspector `:mount-id`s are composed in that file's id
  namespace — `epoch/machine-cascade-transition-delta/<step>` and its
  siblings — so, unqualified, an Epoch panel and a Machine Inspector
  displaying one cascade would compose IDENTICAL ids, share one lifecycle
  entry, one ResizeObserver and one measured-width slot ACROSS TWO
  DIFFERENT PANELS, and detaching either would release the other's.

  That is not a collision a caller should have to name its way out of:
  which panel is rendering is statically known, and an embedder mounting
  one of each cannot see the collision to work around it. So this panel's
  token never answers nil — it names ITSELF, and a caller's `:instance-id`
  qualifies further on top, for the separate case of two MACHINE
  INSPECTORS in one frame.

  Accepted shapes and the Reagent-crossing rule are the siblings' — a
  non-blank string or a keyword whose NAMESPACE is part of the name,
  stable across that instance's renders, tokenised by
  [[Panel-bridge]] BEFORE the crossing."
  [{:keys [instance-id]}]
  (panel-tree (rf.fresco/sub [:rf.xray/machine-inspector-data])
              (rf.fresco/sub [:rf.xray/machine-transitions-for-focused-event])
              ;; The focused epoch's projected machine-cascade
              ;; rows for the SHARED mini-pipeline (element 2). Reads the
              ;; same focused epoch Prev/Next drives, so the mini-pipeline
              ;; and the chart move together.
              (:cascade (rf.fresco/sub [:rf.xray/machine-focused-epoch-cascade]))
              (rf.fresco/sub [:rf.xray/machine-tab-fit-signal])
              (rf.fresco/sub [:rf.xray/target-frame])
              ;; This mount's qualifier for the SHARED
              ;; mini-pipeline's inspector ids. Never nil; see the
              ;; docstring above and [[instance-token]].
              (instance-token instance-id)))

;; ---- the React-component bridge ------------------------------------------
;;
;; `panels/mount-machine-inspector!` mounts this panel BY NAME, and the
;; Fresco boundary sits on the natural name with a PUBLIC bridge passed
;; by the caller — the shape `resources/Panel-bridge` has too.
;;
;; THE BRIDGE IS NOT AN ALIAS. `Panel` is a Fresco boundary — a React
;; function component — and Xray's shell reaches the active tab across its L4
;; `as-child` seam as the hiccup head
;; `[(:panel tab)]` inside a Reagent island. `panel-registry/reg-l4-tab!`'s
;; `:pre` checks only `(fn? panel)`, which a React function component
;; passes; the hiccup head is what a React component cannot fill, because
;; Reagent calls a fn head as its own render fn rather than mounting it as
;; a React component.
;;
;; `rf.fresco/as-component` is Fresco's own outward door for exactly this:
;; it answers a real React component for a boundary, which a React parent
;; (Reagent, UIx or plain JavaScript) mounts UNDER THE FRAME IT IS ALREADY
;; IN, taking the frame from React context rather than from a second root.
;; So there is no second root here, no adapter-kind branch, and no props
;; ABI — and the `[rf/frame-provider {:frame :rf/xray}]` the shell already
;; wraps the panel in is what puts `:rf/xray` in that context.
;;
;; The Tier 4 sub-component (the after-rings overlay) renders UNDER
;; `Panel` and is not independently mountable, so it needs no bridge of
;; its own for this panel. It does carry one
;; (`machine_after_rings/AfterRingsOverlay-bridge`), but NOT for this
;; panel's sake: `machine-canvas/Chart-view` is a boundary and heads the
;; overlay directly. The bridge serves the `reg-view`
;; `machine-canvas/Chart`, which the two Static Reagent-island consumers
;; head.
;;
;; NOT SCAFFOLDING — THE PAIR IS PERMANENT. `panels/mount-machine-inspector!`
;; reaches this bridge through `render-panel!`, which is ratom-family,
;; so a Reagent parent heads it whatever the L4 registry does. The chain
;; is `[:>]` -> `as-component` -> `Panel`.
(def ^:private Panel-component
  "The React component [[Panel]] presents as, for a non-Fresco parent.
  Declared ONCE at top level beside the view, as `rf.fresco/as-component`'s
  own contract requires — deriving it per render would mint a fresh
  component type every pass and remount the panel, taking the xyflow
  chart's measured layout with it."
  (rf.fresco/as-component Panel))

(defn Panel-bridge
  "The callable `panels/mount-machine-inspector!` and the L4 tab
  registration both mount this panel through. Returns Reagent-shaped
  hiccup interoping to the React component above; the enclosing
  `rf/frame-provider` is what puts the frame in React context for it.

  PUBLIC, because this panel carries a `mount-machine-inspector!` facade
  and `panels/render-panel!` takes the view to mount as an ARGUMENT, so
  the embedding contract needs a name it can pass.

  The 1-arity is how a REAGENT parent names an instance when it
  renders two of these under one `frame-provider`:

      [Panel-bridge {:instance-id \"left\"}]

  The 0-arity exists because that is how the shell mounts an L4 tab
  (`[(:panel tab)]`) and how `render-panel!` mounts the standalone embed
  (`[panel-view]`) — one panel per frame, no instance to name. Note that
  the 0-arity is NOT the same as `no qualifier` here: [[instance-token]]
  answers this panel's own name for it, because the cascade ids it
  composes into belong to the Epoch panel.

  ## The prop is TOKENISED HERE, before the crossing

  `[:>]` converts each prop VALUE before React sees it, and Reagent's
  `convert-prop-value` converts a named value with `cljs.core/name` —
  which DROPS THE NAMESPACE. Passed through raw, `:left/machines` and
  `:right/machines` would both arrive as `\"machines\"`, so two panels the
  caller had deliberately named apart would compose the same ids.
  So the bridge runs [[instance-token]] — the SAME normaliser the boundary
  uses, idempotent on its own output — and a STRING crosses, which Reagent
  preserves intact; a refused shape throws naming the CALLER's value
  rather than whatever the crossing had turned it into."
  ([] (Panel-bridge nil))
  ([props]
   [:> Panel-component {:instance-id (instance-token (:instance-id props))}]))

;; ---- production value sources --------------------------------------------
;;
;; The raw values the production data subs read. Shared with the
;; test-override seam (`install-test-overrides!` below) so each override
;; branch lives in ONE place (the seam), not duplicated across the
;; production and test surfaces.

(defn- registered-machines-value
  "Registered-machine vector — the machine-ids of the HOST app (every `:event`
  registration whose metadata carries `:rf/machine? true`, the derivation
  Spec 005 §Querying machines documents).

  Derived from `(rf/registrations {:source :store :kind :event})` — the
  SOURCE-STORE read, which never consults a bound image generation — NOT from
  the generation-scoped `registrar/registrations :event`. This fn
  runs inside the `:rf.xray/registered-machines` sub COMPUTATION, and Xray seats
  in its OWN image-loaded `:rf/xray` frame, so a generation-scoped read would
  resolve through Xray's OWN image (no host machines) and the inspector would
  show no machines. Reading the host registrar directly yields the host's
  machine list. See spec/API.md §Public registrar query API."
  []
  (try
    (->> (rf/registrations {:source :store :kind :event})
         (keep (fn [[id m]] (when (:rf/machine? m) id)))
         vec)
    (catch :default _ [])))

(defn- machine-snapshots-value
  "The egress-redacted live snapshots map off the OBSERVED frame's
  runtime-db (`[:rf.runtime/machines :snapshots]`). `observed-frame-id` is the
  frame whose declarations classify the snapshot `:data` paths (EP-0025 —
  frame-owned redaction), and it must be the frame the runtime-db itself came
  from: the classification is the POLICY of the frame that owns the data, so a
  frame-id from any other axis applies a BORROWED policy. The
  caller pairs both arguments off `:rf.xray/observed-frame` for that reason."
  [observed-frame-id observed-runtime-db]
  (when (map? observed-runtime-db)
    (let [snapshots (get-in observed-runtime-db
                            [:rf.runtime/machines :snapshots] {})]
      (redact-live-snapshots observed-frame-id snapshots))))

(defn- machine-definitions-value
  "The `{machine-id meta}` definition map for `machines` — each machine's spec
  map (`:initial`, `:data`, `:states`, `:guards`, …) read from the HOST app's
  `:event` registrar's `:rf/machine` slot (the same value
  the `:rf/machine` registrar projection returns).

  Resolved via `(rf/handler-meta {:source :store :kind :event :id id})` — the
  SOURCE-STORE read, which never consults a bound image generation. The
  `{:source :store}` selector is load-bearing here: `registrar/lookup :event id`
  (what the artefact's own `resolver/spec-from-registry` uses) is
  generation-scoped.
  This fn runs inside the `:rf.xray/machine-definitions` sub COMPUTATION under
  Xray's OWN image-loaded `:rf/xray` frame, so a generation-scoped read would
  resolve through Xray's image (no host machines) and lose every definition.
  See spec/API.md §Public registrar query API."
  [machines]
  (into {}
        (keep (fn [id]
                (let [m (rf/handler-meta {:source :store :kind :event :id id})]
                  (when (:rf/machine? m) [id (:rf/machine m)]))))
        (or machines [])))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Machine Inspector panel's Xray-side
  registrations. The panel registers:

    - the per-machine projection composite (`:rf.xray/machine-inspector-data`)
    - the focused-event lens composite (`:rf.xray/machine-transitions-for-focused-event`)
    - the per-machine prev/next nav events
    - the scrubber-position slot (read by the `:after`-rings overlay to
      gate ring rendering to the `:present` position)
    - the rings install (`:after` countdown ring overlay)

  The Sim engine + UI live in `static.machines.sim`, installed via
  `static.machines.panel/install!` further down the registry."
  []
  ;; Registered-machine vector (the `:rf/machine?` filter over the generic
  ;; source-store read). The test-only
  ;; override seam (`:rf.xray/set-registered-machines-override-for-test`
  ;; + the `*-override` read) lives behind `install-test-overrides!` —
  ;; production registration carries no `-for-test` ids.
  (rf/reg-sub :rf.xray/registered-machines
    (fn [_db _query]
      (registered-machines-value)))

  ;; The live snapshots map for every registered machine.
  ;;
  ;; EGRESS-REDACTED (EP-0005). The raw slot
  ;; `[:rf.runtime/machines :snapshots]` (EP-0001 — runtime-db
  ;; partition) is the LIVE frame-db value, NOT
  ;; a trace, so it has NOT passed through the snapshot-egress redactor
  ;; (the `re-frame.classification` trace projection) the trace stream rides. The
  ;; trace-derived `:before` / `:after` the mini-pipeline renders ARE
  ;; redacted at emit (epoch-capture sees the projected event), but a
  ;; consumer reading THIS sub directly (the chart's live-snapshot
  ;; `:current-state-override` `:data`, after-rings, sim) would see RAW
  ;; `:data`. So each live snapshot is routed through the SAME
  ;; `project-trace-event` chokepoint as a synthetic
  ;; `:rf.machine/snapshot-updated` event STAMPED with the OBSERVED frame:
  ;; a FRAME-declared sensitive `:data` path lands as `:rf/redacted`, a
  ;; large one as the size marker, the plain siblings ride verbatim —
  ;; exactly the trace-path treatment (EP-0025 — durable machine
  ;; `:data` classification is frame-owned). A frame declaring no matching
  ;; `:data` path leaves the snapshot untouched (reference-preserving fast
  ;; path inside `project-machine-tags`).
  ;;
  ;; BOTH INPUTS PIVOT ON THE SAME FRAME. The data comes from
  ;; `:rf.xray/target-frame-runtime-db`, which pivots on
  ;; `:rf.xray/observed-frame` — `(or (:frame focus) target)` — and so does
  ;; the classification frame. Reading that frame from `:rf.xray/target-frame`
  ;; instead would split them, and in the posture the panel OPENS in they
  ;; differ: `compose-focus` takes `:frame` from the head event-bundle's own
  ;; record in LIVE mode, so focus resolves a real host frame while the picker
  ;; is untouched and `:target-frame` is nil (UNSELECTED, EP-0002).
  ;; `frame-snapshot-classification` answers nil for a nil frame, and with no
  ;; author classification `project-machine-tags` returns the tags UNCHANGED —
  ;; so a `:data` path the frame has EXPLICITLY DECLARED sensitive would
  ;; surface RAW. With a target selected but focus elsewhere it would fail the
  ;; other way, applying the collector target's policy to another frame's
  ;; value.
  ;;
  ;; The frame is NOT recovered from the payload the way the after-rings
  ;; timers recover it from the focused record's `:frame-id` stamp: a live
  ;; runtime-db read carries no such provenance, and it does not need to —
  ;; the owning frame is the COORDINATE THAT SELECTED THE VALUE, so pivoting
  ;; both inputs on `:rf.xray/observed-frame` makes them structurally
  ;; incapable of diverging. The sibling `:rf.xray/current-route-slice`
  ;; does the same with this same runtime-db (`panels/routing.cljs`), for
  ;; the same reason: the elision registry is per-frame, so any other axis
  ;; ships the value under a BORROWED policy. Focus-first with the
  ;; collector-target fallback is `:rf.xray/observed-frame`'s own
  ;; definition, so an unselected focus classifies against the target.
  (rf/reg-sub :rf.xray/machine-snapshots
    {:inputs [[:rf.xray/observed-frame] [:rf.xray/target-frame-runtime-db]]}
    (fn [[observed-frame-id observed-runtime-db] _query]
      (machine-snapshots-value observed-frame-id observed-runtime-db)))

  ;; The registered-machine-definition map for every machine. The
  ;; machine-snapshots / machine-definitions test-only override seams
  ;; live behind `install-test-overrides!` — production
  ;; registration carries no `-for-test` ids and no override branches.
  (rf/reg-sub :rf.xray/machine-definitions
    {:inputs [[:rf.xray/registered-machines]]}
    (fn [[machines] _query]
      (machine-definitions-value machines)))

  ;; The user's per-panel machine selection, written by
  ;; `:rf.xray/select-machine-id` (the Instances JUMP). Read by the
  ;; composite below, `:rf.xray/cancellation-cascade-for-focused-machine`
  ;; and the after-rings timers sub. The Dynamic panel itself drives
  ;; focus off the event lens, where the selection outranks trace order
  ;; (`pick-focused-transition`).
  (rf/reg-sub :rf.xray/selected-machine-id
    (fn [db _query]
      (get db :selected-machine-id)))

  ;; The per-panel composite — one read produces every slot the panel
  ;; consumes. [[panel-tree]] reads its `:empty-kind` and its RAW
  ;; `:selected-machine-id`.
  (rf/reg-sub :rf.xray/machine-inspector-data
    {:inputs [[:rf.xray/registered-machines]
              [:rf.xray/machine-snapshots]
              [:rf.xray/machine-definitions]
              [:rf.xray/trace-buffer]
              [:rf.xray/selected-machine-id]
              [:rf.xray/target-frame]]}
    (fn [[machines live-snapshots definitions buffer selected-id target-frame]
         _query]
      (h/project-data
        machines (or live-snapshots {}) definitions buffer selected-id target-frame)))

  ;; ---- focused-event lens composite ------------------------------

  (rf/reg-sub :rf.xray/machine-transitions-for-focused-event
    {:inputs [[:rf.xray/focus] [:rf.xray/epoch-history] [:rf.xray/machine-definitions]]}
    (fn [[focus history definitions] _query]
      (let [record (h/focused-epoch-record history focus)
            events (when record (:trace-events record))]
        ;; Attach the focused epoch's fired-edge-ids to
        ;; each per-machine section. `extract-fired-edge-ids`
        ;; (canonical) mints the SAME edge-ids the live chart mints off the
        ;; same definition, so the set lands on real chart edges. The view
        ;; threads it into `MachineChart` so the traversed arms paint the
        ;; FIRED treatment — every microstep / guard-fork candidate the
        ;; from/to lens cannot reach.
        ;; Also attach the guard-BLOCKED edge-ids: a
        ;; guard-blocked no-op emits NO `:rf.machine/transition` (so
        ;; `fired-edge-ids` is empty for it), but the runtime DOES emit
        ;; `:rf.machine/guard-evaluated` fail/threw carrying the named
        ;; guard, so `extract-guard-blocked-edge-ids` resolves the exact
        ;; attempted-and-rejected edge. The view threads it into
        ;; `MachineChart` so that edge paints the PINK guard-blocked
        ;; treatment instead of vanishing into the affordance-blue exits.
        (mapv (fn [{:keys [machine-id definition] :as rec}]
                (assoc rec
                  :fired-edge-ids
                  (trace-state/extract-fired-edge-ids
                    definition events machine-id)
                  :guard-blocked-edge-ids
                  (trace-state/extract-guard-blocked-edge-ids
                    definition events machine-id)))
              (h/project-focused-event-transitions events definitions)))))

  ;; ---- focused-epoch cascade events -------------------------------
  ;;
  ;; The SHARED EVENT HANDLER mini-pipeline (shared with the Epoch
  ;; panel — see `panels.epoch.view/machine-cascade-mini-pipeline`)
  ;; renders off the focused epoch's RAW `:trace-events`, projecting the
  ;; numbered machine-cascade itself. The Machine tab subscribes to this
  ;; sub for those events; Prev/Next moves the spine focus, which moves
  ;; the resolved focused epoch, which re-feeds BOTH this mini-pipeline
  ;; AND the chart highlights together. `:event-id` is the head record's
  ;; machine-id — the `:event` handler id `handler-meta` resolves so the
  ;; cascade rows' guard / action source-coords resolve identically to
  ;; the Epoch panel.
  (rf/reg-sub :rf.xray/machine-focused-epoch-cascade
    {:inputs [[:rf.xray/focus]
              [:rf.xray/epoch-history]
              [:rf.xray/machine-transitions-for-focused-event]]}
    (fn [[focus history records] _query]
      (let [record   (h/focused-epoch-record history focus)
            events   (when record (:trace-events record))
            event-id (some-> records first :machine-id)]
        {;; The projected machine-cascade rows the SHARED mini-pipeline
         ;; renders — the SAME `machine-cascade-rows` projection the
         ;; Epoch panel's HANDLER row uses (`projection/handler-row`),
         ;; so the two surfaces are byte-for-byte identical.
         :cascade  (epoch-proj/machine-cascade-rows (or events []))
         :event-id event-id})))

  ;; Test-only seeding events for the focused-event composite
  ;; (`:rf.xray/set-epoch-history-for-test`, `:rf.xray/set-focus-epoch-
  ;; id-for-test`) live behind `install-test-overrides!` —
  ;; production registration carries no `-for-test` ids.

  ;; ---- Machine Inspector panel events -----------------------------

  ;; `:rf.xray/select-machine-id` is registered further down, inside the
  ;; prev/next `letfn` block — it LANDS the selection on the panel
  ;; rather than merely recording it, and landing reuses that block's
  ;; epoch walk verbatim. See the comment above that registration.

  (rf/reg-event :rf.xray/clear-machine-selection
    (fn [{:keys [db]} _event]
      {:db (dissoc db :selected-machine-id)}))

  (rf/reg-event :rf.xray/machine-state-clicked
    (fn [{:keys [db]} [_ _payload]]
      {:db db}))

  (rf/reg-event :rf.xray/machine-chart-layout-pulse
    (fn [{:keys [db]} _event]
      {:db (update db :machine-inspector/elk-pulse-tick (fnil inc 0))}))

  ;; ---- per-machine prev/next nav ----------------------------------
  ;;
  ;; Step the spine's focus backwards / forwards to the adjacent epoch
  ;; whose cascade TARGETS THE CURRENTLY-VIEWED MACHINE — skipping
  ;; epochs whose cascade touched only other machines. The focused-event
  ;; lens binds to the head transition's machine-id; that machine is the
  ;; nav's scope.
  ;;
  ;; Two rules the walk depends on:
  ;;
  ;;   1. **Start from the COMPOSED focus, not the raw `:focus` slot.**
  ;;      In LIVE+unpaused mode `compose-focus` derives the effective
  ;;      `:epoch-id` to the head event-bundle's settling epoch, ignoring the
  ;;      stored slot. Walking from the raw slot's `:epoch-id` (often nil
  ;;      on a fresh session) would start the step from the wrong place
  ;;      and resolve the scope machine off the wrong epoch.
  ;;
  ;;   2. **Mutate focus through `spine/focus-event-bundle-reducer`, not a
  ;;      bare `[:focus :epoch-id]` write.** A bare epoch-id write is
  ;;      silently overridden by `compose-focus`'s LIVE+unpaused head-
  ;;      tracking (`eff-epoch-id` snaps back to head), so the panel
  ;;      would never move — the buttons would look dead. Routing through the
  ;;      reducer stamps `:mode :retro` + resolves the target epoch's
  ;;      settling `:dispatch-id`, the same focus mutation the L2 row
  ;;      click and the spine `[◀ ▶]` ribbon use, so the jump sticks.
  (letfn [;; The SAME three predicates
          ;; `project-focused-event-transitions` folds a record from, and
          ;; that identity is the point. The panel renders a section for a
          ;; machine BIRTH and for a guard-blocked / unhandled NO-OP as
          ;; well as a transition, so testing `transition-event?` ALONE
          ;; would step Prev/Next straight over epochs the panel itself
          ;; draws, and a machine whose only activity in the window was its
          ;; birth would be unreachable from the nav entirely. Anything the
          ;; projection will render a section for is somewhere the walk
          ;; must be able to stop.
          (epoch-touches-machine? [epoch machine-id]
            (some (fn [ev]
                    (and (or (h/transition-event? ev)
                             (h/started-event? ev)
                             (h/no-op-event? ev))
                         (= machine-id (h/machine-id-of ev))))
                  (or (:trace-events epoch) [])))
          ;; The composed focus the panel actually renders from — honours
          ;; LIVE head-tracking, the frame picker, and retro pins.
          (composed-focus [db]
            (spine/compose-focus (get db :focus)
                                 (spine/db->event-bundles db)
                                 (spine/db->show-ungrouped? db)
                                 (get db :epoch-history [])))
          ;; SCOPED BY THE MACHINE THE PANEL IS DRAWING, via
          ;; the very rule it draws by. An explicit selection outranks
          ;; trace order, so a bare `(first records)` would scope the walk
          ;; to a machine the operator is not looking at: JUMP to B, press
          ;; Prev, and the spine would step back through A's epochs. The
          ;; `:selected-machine-id` fallback below answers when the
          ;; focused epoch projects no records at all, so a selection only
          ;; ever gains precedence over trace order for a machine that
          ;; actually transitioned here.
          (scope-machine-id [db focus]
            (let [history  (vec (or (get db :epoch-history) []))
                  record   (h/focused-epoch-record history focus)
                  events   (when record (:trace-events record))
                  records  (h/project-focused-event-transitions events nil)
                  selected (get db :selected-machine-id)]
              (or (some-> (h/pick-focused-transition records selected)
                          :machine-id)
                  selected)))
          ;; Pin `target` (an epoch record) as the spine's focus. The JUMP
          ;; landing below and the Prev/Next walk both pin through here,
          ;; so they pin an epoch the SAME way — a second spelling could
          ;; let one call site write a bare epoch-id and go dead (rule 2
          ;; above), invisibly from the other.
          (pin-epoch [db history target head-id]
            (let [epoch-id    (:epoch-id target)
                  frame-id    (:frame target)
                  dispatch-id (spine/dispatch-id-for-epoch history epoch-id)]
              (if dispatch-id
                ;; Reuse the canonical spine focus mutation so the
                ;; jump stamps mode + dispatch-id and sticks.
                (spine/focus-event-bundle-reducer
                  db dispatch-id frame-id epoch-id head-id)
                ;; No settling dispatch-id (trace elided / synthetic
                ;; epoch) — pin the epoch-id directly AND force
                ;; :retro so compose-focus stops head-tracking and
                ;; the navigation holds. Mirrors the spine's
                ;; `:rf.xray/focus-epoch` no-dispatch-id fallback.
                (cond-> (update db :focus (fnil assoc {})
                                :epoch-id   epoch-id
                                :mode       :retro
                                :previewing? false)
                  frame-id (assoc-in [:focus :frame] frame-id)))))
          (head-dispatch-id [db]
            ;; The head event-bundle's dispatch-id so the reducer can
            ;; pick :live vs :retro correctly when the jump lands
            ;; back on head.
            (let [event-bundles   (spine/db->event-bundles db)
                  show-ungrouped? (spine/db->show-ungrouped? db)]
              (spine/focusable-head-id event-bundles show-ungrouped?)))
          ;; Land the spine on the NEWEST epoch that
          ;; touches `mid`. No-op when the history holds none, so a
          ;; selection made before any activity leaves focus alone.
          (focus-latest-for-machine [db mid]
            (let [history (vec (or (get db :epoch-history) []))
                  target  (some (fn [r]
                                  (when (epoch-touches-machine? r mid) r))
                                (reverse history))]
              (if target
                (pin-epoch db history target (head-dispatch-id db))
                db)))
          (step-focus [db direction]
            (let [history (vec (or (get db :epoch-history) []))
                  focus   (composed-focus db)
                  mid     (scope-machine-id db focus)
                  current (:epoch-id focus)
                  cur-idx (or (some (fn [[i r]]
                                      (when (= (:epoch-id r) current) i))
                                    (map-indexed vector history))
                              ;; No pin yet (composed focus lacks an
                              ;; epoch-id, or it is evicted) — anchor at
                              ;; the tail so :prev steps back from the
                              ;; newest epoch and :next is a no-op.
                              (dec (count history)))
                  step    (case direction :prev dec :next inc)
                  match?  (fn [r] (epoch-touches-machine? r mid))
                  pred    (case direction
                            :prev #(neg? %)
                            :next #(>= % (count history)))
                  head-id (head-dispatch-id db)]
              (loop [i (step cur-idx)]
                (cond
                  (or (nil? mid) (pred i))
                  db

                  (match? (nth history i))
                  (pin-epoch db history (nth history i) head-id)

                  :else (recur (step i))))))]
    (rf/reg-event :rf.xray/machine-focus-prev
      (fn [{:keys [db]} _event] {:db (step-focus db :prev)}))

    (rf/reg-event :rf.xray/machine-focus-next
      (fn [{:keys [db]} _event] {:db (step-focus db :next)}))

    ;; THE JUMP'S PRE-SELECT LANDS.
    ;;
    ;; `static/machines/instances_jump.cljs` telegraphs the Static →
    ;; Dynamic JUMP as three dispatches, of which this is the third, and
    ;; spec/003 §Instances promises the operator lands "with this machine
    ;; pre-selected". The Dynamic panel is an event-driven lens that binds
    ;; to the FOCUSED EPOCH's transition record, so writing
    ;; `:selected-machine-id` alone would change nothing the operator can
    ;; see: the JUMP would land on whatever the spine happened to be
    ;; pointing at — frequently another machine entirely, which is the one
    ;; outcome the affordance exists to prevent.
    ;;
    ;; So the selection LANDS: the spine focus moves to the newest
    ;; epoch touching `machine-id`, through the very walk Prev/Next uses
    ;; (`pin-epoch` → `spine/focus-event-bundle-reducer`), which is what
    ;; makes the move stick against `compose-focus`'s LIVE head-tracking.
    ;;
    ;; The SLOT WRITE is deliberate too:
    ;; `:rf.xray/cancellation-cascade-for-focused-machine` and the
    ;; after-rings timers read the slot, and the panel's own selection
    ;; rule gives it precedence over trace order.
    ;;
    ;; No-op when the machine has no epoch in the window — a selection
    ;; made before any activity leaves the spine where it was.
    (rf/reg-event :rf.xray/select-machine-id
      (fn [{:keys [db]} [_ machine-id]]
        {:db (cond-> (assoc db :selected-machine-id machine-id)
               (some? machine-id) (focus-latest-for-machine machine-id))})))

  ;; ---- scrubber-position slot ----------

  ;; There is no scrubber UI; the slot exists because the `:after`-rings
  ;; overlay reads it
  ;; (`machine_after_rings*` gate ring rendering to the `:present`
  ;; position). Reads default to `:present`. The companion `set-scrubber-
  ;; position` event keeps the contract bidirectional.
  (rf/reg-sub :rf.xray/machine-scrubber-position
    (fn [db _query]
      (get db :machine-inspector/scrubber-position :present)))

  (rf/reg-event :rf.xray/set-scrubber-position
    (fn [{:keys [db]} [_ position]]
      {:db (cond
        (= :present position)
        (assoc db :machine-inspector/scrubber-position :present)

        (integer? position)
        (assoc db :machine-inspector/scrubber-position position)

        (nil? position)
        (assoc db :machine-inspector/scrubber-position :present)

        :else db)}))

  ;; ---- Sim engine ------------------------------------------------
  ;;
  ;; The Sim engine + UI live under `static.machines.sim` (the
  ;; `:rf.xray.static.machines/sim-*` event/sub family), installed by the
  ;; Static Machines panel rather than here. See
  ;; `static.machines.panel/install!`.

  ;; ---- `:after` countdown rings ---------------------------------
  (after-rings/install!)

  ;; ---- Interactive viewport adapter -----------------------------
  (machine-canvas/install!)

  ;; Register the Dynamic Machines tab with the internal L4
  ;; tab registry.
  (panel-registry/reg-l4-tab!
    {:id    :machines
     ;; The Figma App labels the Dynamic L4 tab "Machine"
     ;; (singular · the focused-epoch lens is on ONE machine's topology).
     ;; The internal id is `:machines`, which the mnemonic and routing use.
     :label "Machine"
     :mnem  "m"
     :modes #{:dynamic}
     :order 4
     ;; `Panel-bridge`, not `Panel`. `Panel` is a React
     ;; component (a Fresco boundary); `reg-l4-tab!`'s `:pre` checks only
     ;; `(fn? panel)`, which it passes, but `shell/detail-panel` mounts
     ;; `:panel` as a Reagent hiccup head `[(:panel tab)]`, which a React
     ;; component cannot fill. The bridge is the one line between them and
     ;; is permanent: the shell is a Fresco tree and its L4 mount is a
     ;; Reagent island there.
     :panel Panel-bridge}))

;; ---- test-only override seam ----------------------------------------------

(defn install-test-overrides!
  "Install the Machine Inspector panel's test-only override seam:

    - `:rf.xray/set-registered-machines-override-for-test`,
      `:rf.xray/set-machine-snapshots-override-for-test`,
      `:rf.xray/set-machine-definitions-override-for-test` — override
      events + companion `*-override` subs, with the production
      `:rf.xray/registered-machines` / `:rf.xray/machine-snapshots` /
      `:rf.xray/machine-definitions` / `:rf.xray/machine-inspector-data`
      subs RE-registered to layer the override read on top.
    - `:rf.xray/set-epoch-history-for-test`,
      `:rf.xray/set-focus-epoch-id-for-test` — pure SEEDING events that
      write the real `:epoch-history` / `:focus` slots (no override sub).

  Tests opt in by calling this AFTER `register-xray-handlers!`
  (typically via `test-support/install-test-overrides!`). **Test-only —
  never call from production.**"
  []
  ;; Registered machines.
  (rf/reg-event :rf.xray/set-registered-machines-override-for-test
    (fn [{:keys [db]} [_ ov]]
      {:db (if (nil? ov)
        (dissoc db :registered-machines-override)
        (assoc db :registered-machines-override ov))}))
  (rf/reg-sub :rf.xray/registered-machines
    (fn [db _query]
      (or (get db :registered-machines-override)
          (registered-machines-value))))

  ;; Live snapshots.
  (rf/reg-sub :rf.xray/machine-snapshots-override
    (fn [db _query]
      (get db :machine-snapshots-override)))
  (rf/reg-event :rf.xray/set-machine-snapshots-override-for-test
    (fn [{:keys [db]} [_ ov]]
      {:db (if (nil? ov)
        (dissoc db :machine-snapshots-override)
        (assoc db :machine-snapshots-override ov))}))

  ;; Definitions.
  (rf/reg-sub :rf.xray/machine-definitions-override
    (fn [db _query]
      (get db :machine-definitions-override)))
  (rf/reg-sub :rf.xray/machine-definitions
    {:inputs [[:rf.xray/registered-machines] [:rf.xray/machine-definitions-override]]}
    (fn [[machines override] _query]
      (or override (machine-definitions-value machines))))
  (rf/reg-event :rf.xray/set-machine-definitions-override-for-test
    (fn [{:keys [db]} [_ ov]]
      {:db (if (nil? ov)
        (dissoc db :machine-definitions-override)
        (assoc db :machine-definitions-override ov))}))

  ;; The per-panel composite — re-register so the snapshots-override
  ;; flips the projection (same shape the Static Machines surface uses).
  (rf/reg-sub :rf.xray/machine-inspector-data
    {:inputs [[:rf.xray/registered-machines]
              [:rf.xray/machine-snapshots]
              [:rf.xray/machine-snapshots-override]
              [:rf.xray/machine-definitions]
              [:rf.xray/trace-buffer]
              [:rf.xray/selected-machine-id]
              [:rf.xray/target-frame]]}
    (fn [[machines live-snapshots snapshots-override definitions buffer selected-id target-frame]
         _query]
      (let [snapshots (or snapshots-override live-snapshots {})]
        (h/project-data
          machines snapshots definitions buffer selected-id target-frame))))

  ;; Focused-event composite seeding events (write the real slots).
  (rf/reg-event :rf.xray/set-epoch-history-for-test
    (fn [{:keys [db]} [_ history]]
      {:db (if (nil? history)
        (dissoc db :epoch-history)
        (assoc db :epoch-history (vec history)))}))
  (rf/reg-event :rf.xray/set-focus-epoch-id-for-test
    (fn [{:keys [db]} [_ epoch-id]]
      {:db (if (nil? epoch-id)
        (update db :focus dissoc :epoch-id)
        (update db :focus (fnil assoc {}) :epoch-id epoch-id))}))
  nil)
