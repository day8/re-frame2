(ns day8.re-frame2-xray.panels.machine-inspector
  "Machine Inspector panel — collapsed Dynamic surface (rf2-y9xmf).

  Per Mike's 2026-05-19 redesign, the Dynamic Machines panel is
  **event-driven only**:

    - **BLANK** when the currently focused event is not machine-related
      (per rf2-g3ghh silent-by-default).
    - **When the focused event triggered a machine transition** the
      panel renders one section per machine: topology chart with
      FROM/TO highlighting, the transition edge, guards / actions
      results, the cancellation cascade (when present), `:after`
      countdown rings overlay (when armed timers exist).
    - **prev/next** affordance walks the spine's epoch-history to the
      prior / next event for THE FOCUSED MACHINE (not the full spine).

  ## What was collapsed (rf2-y9xmf)

  The pre-collapse panel (1362 LoC) carried five orthogonal
  exploration surfaces piled into one Dynamic tab: a Machine picker, a
  sub-strip (Topology / Sim / Instances / Cascade), Mode A/B/C
  instance-tab + cluster views, the Sim ribbon UI, a Browse-all entry
  point, an arc overlay + mini-scrubber. None of those belong in a
  Dynamic panel whose only job is to be the lens on the focused event.
  The collapse drops every ribbon. Sim's engine + the browse-all index
  remain in the codebase (sibling bead rf2-r4nao re-hosts them under
  the future Static surface); only the UI ribbons go away.

  ## What stays

    - Topology renderer (ELK + layered fallback; SVG primitive in
      `chart/{layout,svg}`).
    - Transition highlighting (from-state → to-state — dashed-origin /
      bold-landing visual grammar).
    - Per-transition guards + actions lists.
    - Cancellation cascade inline (when the transition triggered one).
    - `:after` countdown rings overlay (when armed timers exist).
    - prev/next nav (per-machine epoch walking).

  ## Pure hiccup

  Same contract as every other Xray panel — the view is pure hiccup,
  no Reagent / UIx / Helix references. Frame isolation comes from the
  enclosing `[rf/frame-provider {:frame :rf/xray}]` in `shell.cljs`."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            ;; rf2-kq8nac (EP-0005) — the snapshot-egress chokepoint. The
            ;; LIVE machine-snapshots sub reads the RAW runtime-db slot
            ;; `[:rf.runtime/machines :snapshots]` (EP-0001 rf2-vzld77 —
            ;; machine snapshots are durable runtime-db state), which is NOT
            ;; egress-projected (it is the live frame-db value, not a trace). To
            ;; keep the panel's `:data` display honest, route each live
            ;; snapshot through the SAME `project-trace-event` chokepoint
            ;; the trace stream uses, so a `:sensitive?` / `:large?`
            ;; `[:schemas :data]` slot lands as `:rf/redacted` / the size
            ;; marker before any panel surface reads it — never raw.
            [re-frame.classification :as classification]
            [day8.re-frame2-machines-viz.chart.layout :as chart-layout]
            [day8.re-frame2-xray.host-registry :as host-registry]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            ;; rf2-g2axio — the SHARED EVENT HANDLER machine-cascade
            ;; mini-pipeline lives in the Epoch panel view; the Machine
            ;; tab consumes the SAME renderer + the SAME cascade
            ;; projection (no duplicate) so the two surfaces cannot
            ;; diverge again.
            [day8.re-frame2-xray.panels.epoch.view :as epoch-view]
            [day8.re-frame2-xray.panels.epoch.projection :as epoch-proj]
            [day8.re-frame2-xray.panels.machine-canvas :as machine-canvas]
            [day8.re-frame2-xray.panels.machine-inspector-helpers :as h]
            ;; rf2-kq8nac (EP-0005) — the declared-over-inferred static
            ;; Context-shape projection. The focused-event chart surfaces
            ;; the machine's declared `[:schemas :data]` Context shape (keys +
            ;; type captions) authoritatively, falling back to the
            ;; one-sample inference when no schema is declared — the SAME
            ;; `static-context-shape` / `static-context-inferred?` the
            ;; Static Topology view feeds its chart (no duplicate
            ;; derivation; both delegate to machines-viz `context-shape`).
            [day8.re-frame2-xray.panels.machines.topology-view :as topology-view]
            [day8.re-frame2-xray.panels.machines.trace-state :as trace-state]
            [day8.re-frame2-xray.panels.machine-after-rings :as after-rings]
            ;; rf2-nugvv — the per-machine prev/next nav routes its focus
            ;; mutation through the spine's `focus-event-bundle-reducer` so the
            ;; jump stamps `:mode :retro` (and resolves the settling
            ;; dispatch-id) — a bare `[:focus :epoch-id]` write is silently
            ;; overridden by `compose-focus`'s LIVE+unpaused head-tracking,
            ;; which is why the buttons were dead on the live panel.
            [day8.re-frame2-xray.spine :as spine]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack spacing]]))

;; ---- section-level layout styles (rf2-alsnz · audit F6) ----------------
;;
;; Hoisted to ns-top defs so the Panel + focused-event-section render
;; paths do not mint fresh `:style {...}` maps on every re-render. The
;; machine-inspector is event-driven (renders one focused-event-section
;; per cascade that transitioned a machine; BLANK otherwise), so the
;; allocation count per render is small relative to the per-row panels
;; (Trace, Event-detail, Cancellation cascade — F1/F2/F4) — but the
;; hoist still removes ~10–15 layout/header allocations per Panel
;; re-render + keeps the file consistent with the rf2-qx414 / rf2-xjgdk
;; / rf2-gjiog hoist pattern.
;;
;; `tokens` values resolve to `var(--rf-xray-*)` CSS strings at ns load,
;; so the active theme toggle continues to flip palette in lockstep
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
  title; rf2-6xezz. The Share affordance that also sat here was removed
  in rf2-nugvv — Prev/Next is now the only header affordance)."
  {:padding         "16px 16px 8px 16px"
   :display         "flex"
   :align-items     "center"
   :justify-content "space-between"
   :gap             "12px"})

(def ^:private panel-header-toolbar-style
  "Right-hand affordance cluster inside the panel header (prev/next;
  the share affordance that also lived here was removed in rf2-nugvv).
  Suppressed when `:no-machines` empty-state is rendering."
  {:display     "flex"
   :align-items "center"
   :gap         "8px"})

(def ^:private focused-event-host-style
  "Wrapper around the focused-event view inside the Panel `cond`'s
  `(seq records)` branch. rf2-zdfbm — flex column so the focused-event
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
  section. rf2-3d987 issue #1 (gap between sibling sub-panels), #8
  (16px margin from the panel host edge), + rf2-zdfbm (flex column so
  the topology chart can grow)."
  {:margin         (:gap-4 spacing)
   :border         (str "1px solid " (:border-default tokens))
   :border-radius  "4px"
   :background     (:bg-2 tokens)
   :flex           "1 1 0"
   :min-height     0
   :display        "flex"
   :flex-direction "column"
   :gap            (:gap-2 spacing)})


;; ---- live-snapshot egress redaction (rf2-kq8nac · EP-0005) --------------
;;
;; The LIVE machine snapshots read straight off the runtime-db slot
;; `[:rf.runtime/machines :snapshots]` (EP-0001 rf2-vzld77) have NOT passed through the
;; snapshot-egress redactor the `re-frame.classification` trace projection (that
;; runs at trace-emit, on the trace stream — not on a direct frame-db
;; read). A machine declaring a `:sensitive?` / `:large?` slot in its
;; `[:schemas :data]` schema (EP-0005 / rf2-w46fpt) has those slots redacted in
;; EVERY transition / snapshot trace, but a raw frame-db read would still
;; surface them. `redact-live-snapshots` routes each `{:state :data}`
;; snapshot through the SAME `project-trace-event` chokepoint as a
;; synthetic `:rf.machine/snapshot-updated` event so the `:data` the
;; panel surfaces is identically redacted to the trace path.

(defn- redact-snapshot
  "Redact one live machine snapshot `{:state :data …}` for `machine-id`
  through the snapshot-egress chokepoint. Wraps the snapshot as a
  synthetic `:rf.machine/snapshot-updated` trace event — stamped with the
  TARGET `frame-id` — and runs `classification/project-trace-event`, which calls
  `project-machine-tags` to redact `:snapshot.data` against the frame's
  classified snapshot-path declarations in the per-frame elision registry.
  EP-0025: machine `:data` classification rides the projection-relative
  machine declaration (lowered per actor under `:source :effect`) / the
  commit-plane classification effects — the frame `:sensitive` / `:large
  {:app-db …}` annotation is removed. A frame that classifies no matching
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
          out (try (classification/project-trace-event ev)
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


;; ---- snapshot-flat-diff styles (rf2-mndut) ------------------------------
;;
;; The snapshot drill-in's `:diff` body (rf2-yqjrd) renders N rows × 4-5
;; inline `:style {...}` maps per row inside a render loop. A 20-row diff
;; minted 80-100 fresh map allocations per render before this hoist;
;; combined with the per-render `engine/project` call upstream the whole
;; sub-section thrashed. Hoist to ns-top defs so React's reconciler sees
;; stable object identities across re-renders, with per-row glyph-colour
;; variation riding a single `assoc` overlay on a base map (same pattern
;; as `app-db-diff-state` flat-diff + `diff/hiccup_render`).
;;
;; `tokens` values resolve to `var(--rf-xray-*)` CSS strings at ns load
;; so the light/dark theme toggle continues to flip palette in lockstep
;; without re-evaluation (spec/007 §UX-IA).

;; rf2-vv3m6 (2026-05-29) — snapshot-flat-diff-* style hoists retired
;; alongside the snapshot drill-in's `:diff` mode body. FULL+DIFF is
;; the single rendering; the edn-inspector widget owns its chrome.

;; ---- rf2-g2axio — bespoke forensic lens + snapshot + collapse REMOVED ---
;;
;; The Machine tab's bespoke `focused-transition-lens` (Target Machine
;; Instance / TRANSITION / GUARDS RUN / ACTIONS RUN) + its helpers
;; (`safe-name`, `fn-source-line`, `dispatch-vectors-from-fx`,
;; `lens-guard-block`, `lens-action-block`), the snapshot drill-in
;; (`snapshot-panel-id`, `machine-id-suffix`, `snapshot-block`,
;; `snapshot-drill-in`), and the chart-collapse chrome
;; (`chart-collapse-toggle`, `chart-collapsed-summary`) are all GONE.
;; The Machine tab now renders EXACTLY Prev/Next + the SHARED EVENT
;; HANDLER mini-pipeline (`epoch-view/machine-cascade-mini-pipeline` —
;; the SAME richer microstep-cascade renderer the Epoch panel uses, so
;; the two surfaces cannot diverge again) + the chart (which carries its
;; own zoom/pan/fit toolbar).

;; ---- per-machine focused-event section ---------------------------------

(defn- focused-event-section
  "Render the focused machine's section (rf2-g2axio redesign). The
  Machine tab now shows EXACTLY THREE elements: the Prev/Next nav (in
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
    2. the chart — `machine-canvas/Chart` with the focused epoch's
       from/to/current/fired highlights. The chart carries its own
       zoom/pan/fit toolbar, so the bespoke collapse chrome is gone.

  REMOVED (rf2-g2axio): the bespoke focused-transition lens (Target
  Machine Instance / TRANSITION / GUARDS RUN / ACTIONS RUN), the
  per-machine header ribbon, the list/canvas view-mode wrapper, the
  chart-collapse toggle + summary, the snapshot drill-in, and the
  inline cancellation-cascade block — all subsumed by the shared
  mini-pipeline above the chart, or relocated to the chart's own
  toolbar."
  [cascade
   {:keys [machine-id from-state to-state definition fired-edge-ids
           guard-blocked-edge-ids start? no-op?]
    :as _record}]
  ;; rf2-gpzb4 (2026-05-21 xyflow migration) — the host-side ELK
  ;; layout dance is GONE; xyflow + elkjs own positioning end-to-end
  ;; inside `MachineChart`. The panel only computes the from/to node-ids
  ;; for the data-attr highlight pins the tests read.
  (let [;; rf2-nesy9 — render-time frame capture for the deferred chart
        ;; state-click dispatch.
        frame      (rf/current-frame-id)
        ;; rf2-skmc7 — a NO-OP suppresses the from→to highlight grammar
        ;; (no edge; the machine stayed put). The wrapper's highlight-id
        ;; data-attrs therefore read "" for a no-op, matching the chart
        ;; props below; the current state is surfaced via `:current-state`.
        from-id    (when (and from-state (not no-op?))
                     (chart-layout/highlight-id from-state))
        to-id      (when (and to-state (not no-op?))
                     (chart-layout/highlight-id to-state))
        engine     "xyflow+elkjs"
        ;; rf2-6tw7t — fit-on-entry nonce. Bumped by `:rf.xray/select-tab
        ;; :machines`; forwarded to the chart's `:fit-signal` so the
        ;; topology re-frames whenever the operator (re-)enters the
        ;; Machine tab, even when the focused machine (hence the chart's
        ;; layout-key) is unchanged.
        fit-signal @(rf/subscribe [:rf.xray/machine-tab-fit-signal])]
    [:section
     {:data-testid (str "rf-xray-machine-focused-event-section-"
                        (when machine-id
                          (subs (str machine-id) 1)))
      :data-machine-id (str machine-id)
      :data-from-state (str from-state)
      :data-to-state (str to-state)
      ;; rf2-eldze / rf2-skmc7 — BIRTH + NO-OP record markers stay on the
      ;; section so tests + hosts can pin that a `:rf.machine/started` /
      ;; `:rf.machine.event/unhandled-no-op` epoch renders the topology
      ;; (initial / current state highlighted) rather than the empty state.
      :data-start (str (boolean start?))
      :data-no-op (str (boolean no-op?))
      ;; rf2-zdfbm — the section is a flex column: the SHARED mini-pipeline
      ;; sits at the top (its natural height) and the chart grows into the
      ;; remaining height below it.
      :style focused-event-section-style}
     ;; ── ELEMENT 2 — the SHARED EVENT HANDLER mini-pipeline ──────────
     ;; rf2-g2axio — the SAME renderer the Epoch panel's EVENT HANDLER
     ;; step uses (`epoch-view/machine-cascade-mini-pipeline-for-events`),
     ;; projecting the focused epoch's `:trace-events` into the numbered
     ;; machine-cascade (microstep / guard / action rows with KIND+PHASE
     ;; badges, verb links, source bodies, outcomes / data-writes). Single
     ;; source of truth — the Machine tab and the Epoch panel cannot
     ;; diverge. Replaces the bespoke `focused-transition-lens` forensic
     ;; block (Target Machine Instance / TRANSITION / GUARDS RUN /
     ;; ACTIONS RUN) the Machine tab used to render.
     [:div {:data-testid "rf-xray-machine-event-handler-mini-pipeline"
            :data-machine-id (str machine-id)
            :style {:padding "10px 14px"}}
      ;; `machine-id` IS the `:event` handler id `handler-meta` resolves
      ;; (a machine is registered as an `:event` handler carrying its spec
      ;; under `:rf/machine`), so the cascade rows' guard / action
      ;; source-coords resolve identically to the Epoch panel.
      (epoch-view/machine-cascade-mini-pipeline cascade machine-id)]
     ;; ── ELEMENT 3 — the topology chart ─────────────────────────────
     ;; The chart carries its OWN toolbar (zoom / pan / fit controls —
     ;; `machine-canvas/Chart`), so the bespoke list/canvas wrapper +
     ;; the chart-collapse toggle/summary are gone
     ;; (rf2-g2axio). Highlights flow as reactive props off THIS focused
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
              ;; rf2-qeemm (G3) — the focused epoch's fired edge-ids on
              ;; the canvas wrapper (sorted, space-joined) so the
              ;; JVM/hiccup suite + hosts pin the wiring without reaching
              ;; into the xyflow canvas. "" when none fired.
              :data-fired-edge-ids (str/join " " (sort (set fired-edge-ids)))
              ;; rf2-fzrzlw — the focused epoch's guard-blocked no-op edge
              ;; ids on the canvas wrapper (sorted, space-joined) so the
              ;; JVM/hiccup suite + hosts pin the wiring without reaching
              ;; into the xyflow canvas. "" when none blocked.
              :data-guard-blocked-edge-ids (str/join " " (sort (set guard-blocked-edge-ids)))
              ;; rf2-zdfbm — fill the section's remaining height so the
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
         ;; rf2-y3l8z — the chart wraps an interactive viewport adapter
         ;; (zoom/pan/fit + controls toolbar) and owns the after-rings
         ;; overlay so they stay co-located with the canvas.
         [machine-canvas/Chart
          {:definition         definition
           :machine-id         machine-id
           ;; rf2-kq8nac (EP-0005) — surface the AUTHORITATIVE declared
           ;; Context shape (keys + type captions) in the focused-event
           ;; chart's root Context band, with the declared-vs-inferred
           ;; indicator. When the machine declares a `[:schemas :data]` schema the
           ;; shape is read off the schema and `:context-band-inferred?`
           ;; is FALSE (the chart drops the `inferred from :data` badge and
           ;; shows `declared` — consistent with the Static Topology view
           ;; from rf2-3q4k5b); absent a schema it falls back to the
           ;; one-sample inference (rf2-5tz9p's badge stays). This is the
           ;; SHAPE, not live `:data` VALUES — the live runtime `:data`
           ;; surfaces (egress-redacted) through the SHARED mini-pipeline's
           ;; cascade rows above, never raw here.
           :context-band       (topology-view/static-context-shape definition)
           :context-band-inferred? (topology-view/static-context-inferred? definition)
           ;; rf2-skmc7 — a NO-OP has no from→to edge; suppress the
           ;; from/to highlight grammar and surface the CURRENT state via
           ;; `:current-state` instead.
           :from-highlight     (when-not no-op? from-state)
           :to-highlight       (when-not no-op? to-state)
           ;; rf2-eldze / rf2-skmc7 — a BIRTH's initial state and a
           ;; NO-OP's unchanged current state both ride `:current-state`
           ;; so the chart highlights the one resting node.
           :current-state      (cond
                                 start? to-state
                                 no-op? to-state
                                 :else  nil)
           ;; rf2-qeemm (G3) — the traversed edges paint the FIRED
           ;; treatment on the live chart.
           :fired-edge-ids     fired-edge-ids
           ;; rf2-fzrzlw — the attempted-and-rejected edges (guard-blocked
           ;; no-op, e.g. door :door/close blocked by :may-close?) paint
           ;; the PINK guard-blocked treatment on the live chart so the
           ;; operator sees which edge the event hit + that a guard
           ;; rejected it (no transition fired, so the fired set is empty).
           :guard-blocked-edge-ids guard-blocked-edge-ids
           ;; rf2-6tw7t — fit-on-entry nonce so re-entering the Machine
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
  ;; rf2-nesy9 — render-time frame capture for the deferred nav clicks.
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
  "Top-level focused-event view (rf2-g2axio). Accepts the focused-event
  `records` (pre-derefed by `Panel` from
  `:rf.xray/machine-transitions-for-focused-event`) and the focused
  epoch's `cascade` (the projected machine-cascade rows the SHARED
  mini-pipeline renders, off `:rf.xray/machine-focused-epoch-cascade`).
  Binds the panel to **exactly one** machine instance per the
  Dynamic-mode single-instance rule (rf2-8og3k): the first transition
  record in trace order — that record drives the chart highlights, while
  the cascade rows show the WHOLE focused epoch's machine cascade
  (identical to the Epoch panel's EVENT HANDLER step). Returns nil when
  no machine transitioned in the focused event's cascade — the panel
  renders the empty-state placeholder in that case (see `blank-state`).

  rf2-alsnz — `records` flows in as an arg so the panel makes one
  Reaction handle per render instead of two."
  [records cascade]
  (let [;; Dynamic-mode single-instance rule (spec/003 §Dynamic mode —
        ;; single-instance, event-driven, rf2-8og3k): pick the first
        ;; transition by trace order. The upstream projection already
        ;; sorts cascade-document-order, so `first` is the tiebreaker.
        record (h/pick-focused-transition records)
        ;; rf2-un3gfo — the inspected frame id. Part of the STRUCTURAL
        ;; section key below so the L1 frame picker (which re-seeds the
        ;; panel against a different runtime) gets a clean section
        ;; instance, while ordinary Prev/Next within one frame+machine
        ;; preserves it.
        target-frame @(rf/subscribe [:rf.xray/target-frame])]
    (when record
      [:div {:data-testid "rf-xray-machine-focused-event"
             ;; The host carries the count of records the cascade
             ;; transitioned (1..N) but only the focused instance
             ;; renders — pinned so tests can assert the rule (one
             ;; section even when N > 1).
             :data-section-count "1"
             :data-cascade-transition-count (str (count records))
             :style focused-event-view-host-style}
       ;; rf2-un3gfo — STRUCTURAL key (target-frame + machine-id), NOT
       ;; per-epoch. The old key embedded `(:id)` / `(:from-state)` /
       ;; `(:to-state)`, all of which change on every Prev/Next, so React
       ;; remounted the whole section + the nested MachineChart on each
       ;; navigation — discarding the chart's per-instance parse/layout
       ;; caches and re-running ELK every time (the topology flicker).
       ;; Highlights flow as reactive props (`:from-highlight` /
       ;; `:to-highlight` / `:current-state` / `:fired-edge-ids`) and the
       ;; section's `:data-*` attrs recompute from `record` on each
       ;; ordinary re-render, so the per-epoch repaint needs no remount.
       ;; Re-fitting on navigation rides the orthogonal `:fit-signal`
       ;; nonce (rf2-6tw7t). See `h/focused-event-section-key`.
       (with-meta (focused-event-section cascade record)
         {:key (h/focused-event-section-key target-frame record)})])))

(defn- blank-state
  "Rendered when the focused event has no machine activity in its
  cascade. Per spec/003 §Empty state — focused event does not target a
  state machine (rf2-8og3k) the panel renders ONLY the verbatim
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
  "Rendered when `(rf/machines)` returns nothing — either the host
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

(rf/reg-view Panel
  "The Machine Inspector (Machine tab) root view (rf2-g2axio). Shows
  EXACTLY THREE elements when the focused event targets a machine:

    1. the Prev/Next epoch nav (header — per-machine epoch walker),
    2. the SHARED EVENT HANDLER mini-pipeline (the SAME numbered
       machine-cascade the Epoch panel renders), and
    3. the topology chart (with its own toolbar + the focused epoch's
       highlights).

  Event-driven: BLANK when the focused event has no machine activity.
  Prev/Next moves the spine focus, which re-feeds the mini-pipeline AND
  the chart highlights together (both read the focused epoch)."
  []
  (let [{:keys [empty-kind]} @(rf/subscribe [:rf.xray/machine-inspector-data])
        records @(rf/subscribe [:rf.xray/machine-transitions-for-focused-event])
        ;; rf2-g2axio — the focused epoch's projected machine-cascade rows
        ;; for the SHARED mini-pipeline (element 2). Reads the same focused
        ;; epoch Prev/Next drives, so the mini-pipeline + chart move
        ;; together.
        {:keys [cascade]} @(rf/subscribe [:rf.xray/machine-focused-epoch-cascade])
        ;; The first record's machine-id drives the prev/next nav (a
        ;; cascade may touch multiple machines; the nav's "this machine"
        ;; is the head section's machine).
        scope-machine-id (some-> records first :machine-id)]
    [:section {:data-testid "rf-xray-machine-inspector"
               :data-view-mode "focused-event"
               :data-has-records (str (boolean (seq records)))
               :style panel-root-style}
     [:header {:data-testid "rf-xray-machine-inspector-header"
               :style panel-header-style}
      ;; rf2-6xezz — Mike-direction 2026-05-21: the large h1 "Machine
      ;; inspector" heading is scrubbed; the L4 tab strip is the
      ;; panel-name source-of-truth. The header row keeps the per-machine
      ;; prev/next nav on the right.
      ;;
      ;; rf2-nugvv — the Share affordance is removed (Mike, 2026-06-04);
      ;; the prev/next nav is the only header toolbar affordance now.
      [:div]
      (when (not= :no-machines empty-kind)
        [:div {:style panel-header-toolbar-style}
         (prev-next-nav scope-machine-id)])]
     (cond
       (= :no-machines empty-kind)
       (empty-state)

       (seq records)
       ;; rf2-zdfbm — flex column so the focused-event view fills the
       ;; host and the topology chart grows into the panel height.
       ;; rf2-alsnz — pass `records` through so `focused-event-view`
       ;; does not duplicate-subscribe the same composite handle.
       [:div {:data-testid "rf-xray-machine-inspector-focused-event-host"
              :style focused-event-host-style}
        (focused-event-view records cascade)]

       :else
       (blank-state))]))

;; ---- production value sources --------------------------------------------
;;
;; The raw values the production data subs read. Shared with the
;; test-override seam (`install-test-overrides!` below) so each override
;; branch lives in ONE place (the seam), not duplicated across the
;; production and test surfaces (rf2-e8330v).

(defn- registered-machines-value
  "Registered-machine vector — the machine-ids of the HOST app (every `:event`
  registration whose metadata carries `:rf/machine? true`, the same derivation
  `re-frame.machines/machines` runs).

  Derived from `host-registry/registrations` (the generation-bypassing
  default-realm form), NOT `(machines/machines)`: the framework's `machines`
  reads `registrar/registrations :event`, which is generation-scoped. This fn
  runs inside the `:rf.xray/registered-machines` sub COMPUTATION, and Xray seats
  in its OWN image-loaded `:rf/xray` frame, so a generation-scoped read would
  resolve through Xray's OWN image (no host machines) and the inspector would
  show no machines. Reading the host registrar directly restores the host's
  machine list. See `day8.re-frame2-xray.host-registry`."
  []
  (try
    (->> (host-registry/registrations :event)
         (keep (fn [[id m]] (when (:rf/machine? m) id)))
         vec)
    (catch :default _ [])))

(defn- machine-snapshots-value
  "The egress-redacted live snapshots map off the target frame's
  runtime-db (`[:rf.runtime/machines :snapshots]`). `target-frame-id` is the
  inspected frame whose declarations classify the snapshot `:data` paths
  (EP-0025 — frame-owned redaction)."
  [target-frame-id target-runtime-db]
  (when (map? target-runtime-db)
    (let [snapshots (get-in target-runtime-db
                            [:rf.runtime/machines :snapshots] {})]
      (redact-live-snapshots target-frame-id snapshots))))

(defn- machine-definitions-value
  "The `{machine-id meta}` definition map for `machines` — each machine's spec
  map (`:initial`, `:data`, `:states`, `:guards`, …) read from the HOST app's
  `:event` registrar's `:rf/machine` slot (the same value
  `re-frame.machines/machine-meta` returns).

  Resolved via `host-registry/handler-meta` (the generation-bypassing
  default-realm form), NOT `(machines/machine-meta id)`: the framework's
  `machine-meta` reads `registrar/lookup :event id`, which is generation-scoped.
  This fn runs inside the `:rf.xray/machine-definitions` sub COMPUTATION under
  Xray's OWN image-loaded `:rf/xray` frame, so a generation-scoped read would
  resolve through Xray's image (no host machines) and lose every definition.
  See `day8.re-frame2-xray.host-registry`."
  [machines]
  (into {}
        (keep (fn [id]
                (let [m (host-registry/handler-meta :event id)]
                  (when (:rf/machine? m) [id (:rf/machine m)]))))
        (or machines [])))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Machine Inspector panel's Xray-side
  registrations. Post-collapse (rf2-y9xmf) the panel registers:

    - the per-machine projection composite (`:rf.xray/machine-inspector-data`)
    - the focused-event lens composite (`:rf.xray/machine-transitions-for-focused-event`)
    - the per-machine prev/next nav events
    - the scrubber-position slot (read by the `:after`-rings overlay to
      gate ring rendering to the `:present` position; rf2-nugvv removed
      the share-URL surface that previously also round-tripped it)
    - the rings install (`:after` countdown ring overlay)

  rf2-nugvv (2026-06-04) — the Share affordance (button + modal +
  share-URL infra) is removed; `install!` no longer installs it.

  rf2-r4nao moved the Sim engine + UI into
  `static.machines.sim` — installed via
  `static.machines.panel/install!` further down the registry."
  []
  ;; ---- Snapshot diff-mode toggle — RETIRED 2026-05-29 (rf2-vv3m6) -----
  ;;
  ;; The `[diff][full][full+diff]` toggle (rf2-yqjrd) retired alongside
  ;; its sibling toggles on the Epoch HANDLER `:db`, SUBSCRIPTIONS
  ;; value, and App-DB panel surfaces. FULL+DIFF is the single
  ;; rendering — `snapshot-drill-in` hard-wires that posture and this
  ;; install no longer registers the sub/event/slot trio.

  ;; Registered-machine vector (reads `(rf/machines)`). The test-only
  ;; override seam (`:rf.xray/set-registered-machines-override-for-test`
  ;; + the `*-override` read) lives behind `install-test-overrides!`
  ;; (rf2-e8330v) — production registration carries no `-for-test` ids.
  (rf/reg-sub :rf.xray/registered-machines
    (fn [_db _query]
      (registered-machines-value)))

  ;; The live snapshots map for every registered machine.
  ;;
  ;; rf2-kq8nac (EP-0005) — EGRESS-REDACTED. The raw slot
  ;; `[:rf.runtime/machines :snapshots]` (EP-0001 rf2-vzld77 — runtime-db
  ;; partition) is the LIVE frame-db value, NOT
  ;; a trace, so it has NOT passed through the snapshot-egress redactor
  ;; (the `re-frame.classification` trace projection) the trace stream rides. The
  ;; trace-derived `:before` / `:after` the mini-pipeline renders ARE
  ;; redacted at emit (epoch-capture sees the projected event), but a
  ;; consumer reading THIS sub directly (the chart's live-snapshot
  ;; `:current-state-override` `:data`, after-rings, sim) would see RAW
  ;; `:data`. So each live snapshot is routed through the SAME
  ;; `project-trace-event` chokepoint as a synthetic
  ;; `:rf.machine/snapshot-updated` event STAMPED with the target frame:
  ;; a FRAME-declared sensitive `:data` path lands as `:rf/redacted`, a
  ;; large one as the size marker, the plain siblings ride verbatim —
  ;; exactly the trace-path treatment (EP-0025, rf2-398kql — durable machine
  ;; `:data` classification is frame-owned). A frame declaring no matching
  ;; `:data` path leaves the snapshot untouched (reference-preserving fast
  ;; path inside `project-machine-tags`).
  (rf/reg-sub :rf.xray/machine-snapshots
    :<- [:rf.xray/target-frame]
    :<- [:rf.xray/target-frame-runtime-db]
    (fn [[target-frame-id target-runtime-db] _query]
      (machine-snapshots-value target-frame-id target-runtime-db)))

  ;; The registered-machine-definition map for every machine. The
  ;; machine-snapshots / machine-definitions test-only override seams
  ;; live behind `install-test-overrides!` (rf2-e8330v) — production
  ;; registration carries no `-for-test` ids and no override branches.
  (rf/reg-sub :rf.xray/machine-definitions
    :<- [:rf.xray/registered-machines]
    (fn [machines _query]
      (machine-definitions-value machines)))

  ;; The user's per-panel machine selection (kept as a slot for the
  ;; Sim engine + the Instances-jump focus landing; the share-URL
  ;; round-trip that also read it was removed in rf2-nugvv. The
  ;; collapsed Dynamic panel itself drives focus off the event lens,
  ;; not the picker slot).
  (rf/reg-sub :rf.xray/selected-machine-id
    (fn [db _query]
      (get db :selected-machine-id)))

  ;; The per-panel composite — one read produces every slot the panel
  ;; consumes. Kept post-collapse so callers (after-rings, sim) that
  ;; read `:selected-id` / `:empty-kind` keep working without touching
  ;; their wiring. (The share surface was a caller until rf2-nugvv
  ;; removed it.)
  (rf/reg-sub :rf.xray/machine-inspector-data
    :<- [:rf.xray/registered-machines]
    :<- [:rf.xray/machine-snapshots]
    :<- [:rf.xray/machine-definitions]
    :<- [:rf.xray/trace-buffer]
    :<- [:rf.xray/selected-machine-id]
    :<- [:rf.xray/target-frame]
    (fn [[machines live-snapshots definitions buffer selected-id target-frame]
         _query]
      (h/project-data
        machines (or live-snapshots {}) definitions buffer selected-id target-frame)))

  ;; ---- focused-event lens composite (rf2-a9cke) ------------------

  (rf/reg-sub :rf.xray/machine-transitions-for-focused-event
    :<- [:rf.xray/focus]
    :<- [:rf.xray/epoch-history]
    :<- [:rf.xray/machine-definitions]
    (fn [[focus history definitions] _query]
      (let [record (h/focused-epoch-record history focus)
            events (when record (:trace-events record))]
        ;; rf2-qeemm (G3) — attach the focused epoch's fired-edge-ids to
        ;; each per-machine section. `extract-fired-edge-ids` (B7,
        ;; canonical) mints the SAME edge-ids the live chart mints off the
        ;; same definition, so the set lands on real chart edges. The view
        ;; threads it into `MachineChart` so the traversed arms paint the
        ;; FIRED treatment — every microstep / guard-fork candidate the
        ;; from/to lens cannot reach.
        ;; rf2-fzrzlw — additionally attach the guard-BLOCKED edge-ids: a
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

  ;; ---- focused-epoch cascade events (rf2-g2axio) ------------------
  ;;
  ;; The SHARED EVENT HANDLER mini-pipeline (extracted from the Epoch
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
    :<- [:rf.xray/focus]
    :<- [:rf.xray/epoch-history]
    :<- [:rf.xray/machine-transitions-for-focused-event]
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
  ;; id-for-test`) live behind `install-test-overrides!` (rf2-e8330v) —
  ;; production registration carries no `-for-test` ids.

  ;; ---- Machine Inspector panel events -----------------------------

  (rf/reg-event :rf.xray/select-machine-id
    (fn [{:keys [db]} [_ machine-id]]
      {:db (assoc db :selected-machine-id machine-id)}))

  (rf/reg-event :rf.xray/clear-machine-selection
    (fn [{:keys [db]} _event]
      {:db (dissoc db :selected-machine-id)}))

  (rf/reg-event :rf.xray/machine-state-clicked
    (fn [{:keys [db]} [_ _payload]]
      {:db db}))

  (rf/reg-event :rf.xray/machine-chart-layout-pulse
    (fn [{:keys [db]} _event]
      {:db (update db :machine-inspector/elk-pulse-tick (fnil inc 0))}))

  ;; ---- per-machine prev/next nav (rf2-y9xmf · fixed rf2-nugvv) ----
  ;;
  ;; Step the spine's focus backwards / forwards to the adjacent epoch
  ;; whose cascade TARGETS THE CURRENTLY-VIEWED MACHINE — skipping
  ;; epochs whose cascade touched only other machines. The focused-event
  ;; lens binds to the head transition's machine-id; that machine is the
  ;; nav's scope.
  ;;
  ;; rf2-nugvv — two corrections over the original walk:
  ;;
  ;;   1. **Start from the COMPOSED focus, not the raw `:focus` slot.**
  ;;      In LIVE+unpaused mode `compose-focus` derives the effective
  ;;      `:epoch-id` to the head event-bundle's settling epoch, ignoring the
  ;;      stored slot. Walking from the raw slot's `:epoch-id` (often nil
  ;;      on a fresh session) made the step start from the wrong place
  ;;      and the scope machine resolve off the wrong epoch.
  ;;
  ;;   2. **Mutate focus through `spine/focus-event-bundle-reducer`, not a
  ;;      bare `[:focus :epoch-id]` write.** A bare epoch-id write is
  ;;      silently overridden by `compose-focus`'s LIVE+unpaused head-
  ;;      tracking (`eff-epoch-id` snaps back to head), so the panel
  ;;      never moved — the buttons looked dead. Routing through the
  ;;      reducer stamps `:mode :retro` + resolves the target epoch's
  ;;      settling `:dispatch-id`, the same focus mutation the L2 row
  ;;      click and the spine `[◀ ▶]` ribbon use, so the jump sticks.
  (letfn [(epoch-touches-machine? [epoch machine-id]
            (some (fn [ev]
                    (and (h/transition-event? ev)
                         (= machine-id (h/machine-id-of ev))))
                  (or (:trace-events epoch) [])))
          ;; The composed focus the panel actually renders from — honours
          ;; LIVE head-tracking, the frame picker, and retro pins.
          (composed-focus [db]
            (spine/compose-focus (get db :focus)
                                 (spine/db->event-bundles db)
                                 (spine/db->show-ungrouped? db)
                                 (get db :epoch-history [])))
          (scope-machine-id [db focus]
            (let [history (vec (or (get db :epoch-history) []))
                  record  (h/focused-epoch-record history focus)
                  events  (when record (:trace-events record))
                  records (h/project-focused-event-transitions events nil)]
              (or (some-> records first :machine-id)
                  (get db :selected-machine-id))))
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
                  ;; The head event-bundle's dispatch-id so the reducer can
                  ;; pick :live vs :retro correctly when the jump lands
                  ;; back on head.
                  event-bundles   (spine/db->event-bundles db)
                  show-ungrouped? (spine/db->show-ungrouped? db)
                  head-id         (spine/focusable-head-id event-bundles show-ungrouped?)]
              (loop [i (step cur-idx)]
                (cond
                  (or (nil? mid) (pred i))
                  db

                  (match? (nth history i))
                  (let [target      (nth history i)
                        epoch-id    (:epoch-id target)
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
                        frame-id (assoc-in [:focus :frame] frame-id))))

                  :else (recur (step i))))))]
    (rf/reg-event :rf.xray/machine-focus-prev
      (fn [{:keys [db]} _event] {:db (step-focus db :prev)}))

    (rf/reg-event :rf.xray/machine-focus-next
      (fn [{:keys [db]} _event] {:db (step-focus db :next)})))

  ;; ---- scrubber-position slot ----------

  ;; The scrubber UI is gone (rf2-y9xmf) and the share-URL surface that
  ;; round-tripped this slot is gone too (rf2-nugvv), but the slot
  ;; survives because the `:after`-rings overlay reads it
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
  ;; rf2-r4nao — Sim engine + UI rehosted under
  ;; `static.machines.sim` (event/sub family renamed to
  ;; `:rf.xray.static.machines/sim-*`). The Dynamic Machine Inspector
  ;; no longer installs Sim; the Static Machines panel does. See
  ;; `static.machines.panel/install!`.

  ;; ---- `:after` countdown rings (rf2-7hwwe) ---------------------
  (after-rings/install!)

  ;; ---- Interactive viewport adapter (rf2-y3l8z) -----------------
  (machine-canvas/install!)

  ;; rf2-nugvv (2026-06-04) — the Share affordance (rf2-nqw0v) is
  ;; removed. The machine panel was the sole UI entry point to the
  ;; share modal (`:rf.xray/share-modal-open`), so the button, the
  ;; modal, the shell mount, and the share-URL infra all go with it.

  ;; rf2-2moh1 — register the Dynamic Machines tab with the internal L4
  ;; tab registry.
  (panel-registry/reg-l4-tab!
    {:id    :machines
     ;; rf2-ad7zx.10 — Figma App labels the Dynamic L4 tab "Machine"
     ;; (singular · the focused-epoch lens is on ONE machine's topology).
     ;; The internal id stays `:machines` (mnemonic + routing unchanged).
     :label "Machine"
     :mnem  "m"
     :modes #{:dynamic}
     :order 4
     :panel Panel}))

;; ---- test-only override seam (rf2-e8330v / xxo3zz F3) ---------------------

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
    :<- [:rf.xray/registered-machines]
    :<- [:rf.xray/machine-definitions-override]
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
    :<- [:rf.xray/registered-machines]
    :<- [:rf.xray/machine-snapshots]
    :<- [:rf.xray/machine-snapshots-override]
    :<- [:rf.xray/machine-definitions]
    :<- [:rf.xray/trace-buffer]
    :<- [:rf.xray/selected-machine-id]
    :<- [:rf.xray/target-frame]
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
