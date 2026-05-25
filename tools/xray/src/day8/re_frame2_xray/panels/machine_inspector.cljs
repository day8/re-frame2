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
            [day8.re-frame2-machines-viz.chart.layout :as chart-layout]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.cancellation-cascade :as cancellation-cascade]
            [day8.re-frame2-xray.panels.machine-canvas :as machine-canvas]
            [day8.re-frame2-xray.panels.machine-inspector-helpers :as h]
            [day8.re-frame2-xray.panels.machines.trace-state :as trace-state]
            [day8.re-frame2-xray.panels.machine-after-rings :as after-rings]
            [day8.re-frame2-xray.share :as share]
            ;; rf2-lxvn6 (phase 4 of rf2-oqa60) — the per-machine
            ;; snapshot drill-in surface mounts the first-class
            ;; data-display widget directly. Each machine gets its own
            ;; `:panel-id` qualifier so two machines' expansion state
            ;; stays independent. See spec/021 §10 widget contract.
            [day8.re-frame2-xray.views.data-display :as dd]
            [day8.re-frame2-xray.theme.tokens
             :as t
             :refer [tokens mono-stack sans-stack display-stack]]))

;; ---- safe-name helper ---------------------------------------------------

(defn- safe-name
  "Render `x` to a string suitable for `data-testid` suffixes. Belt-and-
  braces over the projection layer's `ref-display-id` (which normalises
  guard/action refs into keywords). If a future trace shape pipes a fn
  through unprojected the view still won't blow up — `cljs.core/name`
  on a fn throws `Doesn't support name: function ...` (rf2-ujra6)."
  [x]
  (cond
    (nil? x)                          ""
    (or (keyword? x) (symbol? x))     (name x)
    (string? x)                       x
    :else                             (str x)))

;; ---- focused-transition lens (rf2-2n34o · spec/003 §Focused-transition lens) -----
;;
;; The lens is the above-chart forensic block specified in
;; spec/003-Machine-Inspector.md §Focused-transition lens (rf2-99rhe).
;; It renders the EXACT shape:
;;
;;   Target Machine Instance: :title/flow-instance-42
;;   TRANSITION
;;     idle → loading
;;   GUARDS RUN
;;     :token?
;;       (fn [data] (get-in data [:session :token]))
;;       → return true
;;   ACTIONS RUN
;;     :fetch!
;;       (fn [data] {:fx [[:dispatch [:loading/complete]]]})
;;       → :fx :dispatch → [:loading/complete]
;;
;; Data sources (all available post-rf2-ypu5i, rf2-99rhe, rf2-8og3k):
;;   - target instance id + from→to: `:rf.machine/transition` tags
;;   - guard id + return: `:rf.machine/guard-evaluated` tags
;;   - guard / action fn-source: `(rf/handler-meta :machine-guard / :machine-action ...)`
;;   - action id + `:fx` output: `:rf.machine/action-ran` tags `:outcome`
;;
;; Dynamic-mode constraint (rf2-8og3k): the lens binds to EXACTLY ONE
;; instance — the first transition record in trace order (the upstream
;; projection already sorts cascade-document-order, so `first` is the
;; tiebreaker). When no machine transitioned, the panel renders only the
;; verbatim empty-state placeholder (see `blank-state`).

(defn- fn-source-line
  "Render the captured fn-source string under a guard / action id, or a
  muted fallback when production-elision dropped it (Spec 005
  §`reg-machine` / `reg-machine*`: programmatic registrations carry no
  source). The string is intentionally rendered raw — no syntax
  highlighting at v1, matching the spec's plain monospace treatment."
  [source]
  [:div {:style {:padding-left "16px"
                 :color (if source (:text-secondary tokens) (:text-tertiary tokens))
                 :font-style (when-not source "italic")
                 :font-family mono-stack
                 :font-size "11px"
                 :line-height 1.5
                 :white-space "pre-wrap"
                 :word-break "break-word"}}
   (or source "(fn source unavailable)")])

(defn- dispatch-vectors-from-fx
  "Extract `[:dispatch <event>]` entries from an action's returned
  `{:fx [...]}` map. Returns a vector of event-vectors (possibly empty).
  Tolerates `nil`, non-map outcomes, or :fx vectors carrying non-dispatch
  fx entries (those are skipped)."
  [outcome]
  (let [fx (when (map? outcome) (:fx outcome))]
    (->> (or fx [])
         (keep (fn [entry]
                 (when (and (vector? entry)
                            (= :dispatch (first entry)))
                   (second entry))))
         vec)))

(defn- lens-guard-block
  "Render one guard's block inside the lens GUARDS RUN section:

       :guard-id
         (fn source)
         → return <pass|fail>"
  [machine-id {:keys [guard-id outcome]}]
  (let [m       (try (rf/handler-meta :machine-guard [machine-id guard-id])
                     (catch :default _ nil))
        source  (:rf.handler/source m)
        return  (case outcome
                  :pass "true"
                  :fail "false"
                  (when (some? outcome) (pr-str outcome)))]
    [:div {:data-testid (str "rf-xray-machine-lens-guard-"
                             (safe-name guard-id))
           :data-guard-id (str guard-id)
           :data-outcome (when outcome (name outcome))
           :style {:font-family mono-stack
                   :font-size "12px"
                   :color (:text-primary tokens)
                   :margin "2px 0"}}
     [:div {:style {:padding-left "16px"
                    :color (:magenta tokens)}}
      (str guard-id)]
     (fn-source-line source)
     (when return
       [:div {:style {:padding-left "16px"
                      :color (:info tokens)}}
        (str "→ return " return)])]))

(defn- lens-action-block
  "Render one action's block inside the lens ACTIONS RUN section:

       :action-id
         (fn source)
         → :fx :dispatch → [<dispatch-vec>]

  The trailing dispatch lines surface child-cascade `:dispatch` entries
  pulled from the action's returned `{:fx [...]}` map. When no `:fx
  :dispatch` fired, the arrow line is suppressed."
  [machine-id {:keys [action-id outcome]}]
  (let [m         (try (rf/handler-meta :machine-action [machine-id action-id])
                       (catch :default _ nil))
        source    (:rf.handler/source m)
        dispatches (dispatch-vectors-from-fx outcome)]
    [:div {:data-testid (str "rf-xray-machine-lens-action-"
                             (safe-name action-id))
           :data-action-id (str action-id)
           :data-dispatch-count (str (count dispatches))
           :style {:font-family mono-stack
                   :font-size "12px"
                   :color (:text-primary tokens)
                   :margin "2px 0"}}
     [:div {:style {:padding-left "16px"
                    :color (:magenta tokens)}}
      (str action-id)]
     (fn-source-line source)
     (into [:<>]
           (for [[idx ev] (map-indexed vector dispatches)]
             ^{:key idx}
             [:div {:data-testid (str "rf-xray-machine-lens-action-dispatch-"
                                      (safe-name action-id) "-" idx)
                    :style {:padding-left "16px"
                            :color (:info tokens)}}
              (str "→ :fx :dispatch → " (pr-str ev))]))]))

(defn- focused-transition-lens
  "The above-chart forensic lens per spec/003 §Focused-transition lens.
  Reads `record` (the focused transition, picked via
  `h/pick-focused-transition` — see Dynamic-mode rule, rf2-8og3k) and
  renders the Target Machine Instance / TRANSITION / GUARDS RUN /
  ACTIONS RUN block in the normative order. Pure hiccup — fn-source is
  resolved via `rf/handler-meta` which is a pure registrar lookup."
  [{:keys [machine-id from-state to-state guards actions]}]
  [:div {:data-testid "rf-xray-machine-focused-transition-lens"
         :data-machine-id (str machine-id)
         :data-guard-count (str (count guards))
         :data-action-count (str (count actions))
         :style {:padding "12px 14px"
                 :background (:bg-1 tokens)
                 :border-bottom (str "1px solid " (:border-subtle tokens))
                 :font-family mono-stack
                 :font-size "12px"
                 :color (:text-primary tokens)
                 :line-height 1.55}}
   [:div {:data-testid "rf-xray-machine-lens-target-instance"
          :style {:margin-bottom "6px"}}
    [:span {:style {:color (:text-tertiary tokens)}}
     "Target Machine Instance: "]
    [:span {:style {:color (:magenta tokens)}}
     (h/format-machine-id machine-id)]]
   [:div {:data-testid "rf-xray-machine-lens-transition"
          :style {:margin "4px 0"}}
    [:div {:style {:color (:text-tertiary tokens)
                   :text-transform "uppercase"
                   :font-size "10px"
                   :letter-spacing "0.5px"}}
     "Transition"]
    [:div {:style {:padding-left "16px"}}
     [:span {:style {:color (:text-secondary tokens)}}
      (h/format-state from-state)]
     [:span {:style {:color (:accent tokens) :margin "0 6px"}} "→"]
     [:span {:style {:color (:text-primary tokens) :font-weight 600}}
      (h/format-state to-state)]]]
   (when (seq guards)
     [:div {:data-testid "rf-xray-machine-lens-guards-run"
            :style {:margin "4px 0"}}
      [:div {:style {:color (:text-tertiary tokens)
                     :text-transform "uppercase"
                     :font-size "10px"
                     :letter-spacing "0.5px"}}
       "Guards Run"]
      (into [:div]
            (for [g guards]
              ^{:key (str (:guard-id g))}
              (lens-guard-block machine-id g)))])
   (when (seq actions)
     [:div {:data-testid "rf-xray-machine-lens-actions-run"
            :style {:margin "4px 0"}}
      [:div {:style {:color (:text-tertiary tokens)
                     :text-transform "uppercase"
                     :font-size "10px"
                     :letter-spacing "0.5px"}}
       "Actions Run"]
      (into [:div]
            (for [a actions]
              ^{:key (str (:action-id a))}
              (lens-action-block machine-id a)))])])

;; ---- snapshot drill-in (rf2-lxvn6 · spec/021 §10 widget contract) -----
;;
;; Phase 4 of rf2-oqa60 wires the per-machine snapshot value through
;; the first-class data-display widget at
;; `day8.re-frame2-xray.views.data-display`. Each call site qualifies
;; with a per-machine `:panel-id` so two machines' (or before/after's
;; on the same machine) expansion state stays independent — the rule
;; per spec/021 §10.0.2 acceptance property 5 (per-call-site isolation
;; via mount-id) and property 1 (per-type colours via CSS variables).
;;
;; The drill-in shows the FULL `{:state X :data Y}` snapshot map so the
;; operator can inspect what `:data` carried at the moment of
;; transition — the bug class spec/003 §M.10 (Snapshot diff across
;; transitions) calls out: today the chart highlights state changes;
;; `:data` mutations are invisible unless the user opens the app-db
;; diff. The drill-in is the snapshot-visibility primitive that closes
;; that gap; phase 5 (D5=a) adds the diff overlay on top of the same
;; widget.

(defn- snapshot-panel-id
  "Compose a per-machine `:panel-id` qualifier for the snapshot
  drill-in mount. Each machine gets a distinct namespaced keyword so
  the widget's `:rf.xray.data-display/expansion` slot scopes by
  machine-id; expansion under `:auth/login` doesn't bleed into
  expansion under `:checkout/flow`.

  The `phase` suffix (`:before` / `:after` / `:current`) further
  scopes a single machine's before vs after vs live-current snapshot
  in the focused-event section so the operator can drill into both
  without one toggle clobbering the other.

  Returns a keyword shaped like `:rf.xray.machine-snapshot/auth.login-before`."
  [machine-id phase]
  (keyword "rf.xray.machine-snapshot"
           (str (some-> machine-id str (subs 1) (str/replace "/" "."))
                (when phase (str "-" (name phase))))))

(defn- machine-id-suffix
  "Render `machine-id` as a testid suffix that preserves the
  namespaced portion (e.g. `:auth/login` → `\"auth/login\"`). Mirrors
  the existing `focused-event-section-` testid convention so panel-
  level tests can assert by the same shape."
  [machine-id]
  (cond
    (nil? machine-id) ""
    (keyword? machine-id) (subs (str machine-id) 1)
    :else (str machine-id)))

(defn- snapshot-block
  "Render one machine snapshot map (`{:state X :data Y}`) via the
  first-class data-display widget (rf2-oqa60 phase 1, rf2-lxvn6 phase
  4). Tagged with a section heading + the per-mount testid so panel-
  level tests can assert presence per (machine-id, phase) pair.

  Returns `nil` when the snapshot is absent — the empty-state is
  handled by the caller (a top-level placeholder is more legible than
  a per-block nil chip)."
  [{:keys [machine-id phase label snapshot]}]
  (when (some? snapshot)
    [:div {:data-testid    (str "rf-xray-machine-snapshot-block-"
                                (machine-id-suffix machine-id)
                                "-" (name phase))
           :data-machine-id (str machine-id)
           :data-phase      (name phase)
           :style {:padding "8px 12px"
                   :border-bottom (str "1px solid " (:border-subtle tokens))
                   :background (:bg-1 tokens)}}
     [:div {:style {:color (:text-tertiary tokens)
                    :text-transform "uppercase"
                    :font-size "10px"
                    :letter-spacing "0.5px"
                    :font-family sans-stack
                    :margin-bottom "4px"}}
      label]
     [dd/data-display snapshot
      {:panel-id (snapshot-panel-id machine-id phase)
       ;; rf2-pvsxs — machine + phase are stable identifiers; the
       ;; operator's drill-into-data choices survive a Machines tab
       ;; leave-and-return round-trip.
       :site-id  [:rf.xray.machines/inspector-snapshot machine-id phase]
       :default-expanded-depth 2
       ;; rf2-l4625 — machine snapshots routinely carry deeply-nested
       ;; `:data` maps; the popup gives the operator a full-modal
       ;; inspection surface alongside the per-machine drill-in.
       :popup-affordance? true}]]))

(defn- snapshot-drill-in
  "Snapshot drill-in section beneath the focused-event chart. Renders
  the BEFORE and AFTER snapshot maps for the focused transition via
  the first-class data-display widget so the operator can inspect
  what `:data` carried on either side of the transition (spec/003
  §M.10 bug class — `:data` mutations invisible without app-db diff).

  Per spec/021 §10 widget contract every call site qualifies with a
  per-machine `:panel-id`; here the qualifier folds in the phase
  (`:before` / `:after`) so the two sibling mounts don't share
  expansion state on the same machine.

  Renders nothing when both snapshots are nil (legacy trace fixtures
  pre-dating the commit-or-finalize snapshot tagging — see
  `transition-record-from-trace` docstring)."
  [{:keys [machine-id before after]}]
  (when (or (some? before) (some? after))
    [:section
     {:data-testid     "rf-xray-machine-snapshot-drill-in"
      :data-machine-id (str machine-id)
      :data-has-before (str (some? before))
      :data-has-after  (str (some? after))
      :style {:border-bottom (str "1px solid " (:border-subtle tokens))
              :background (:bg-2 tokens)}}
     [:header {:style {:padding "8px 12px"
                       :background (:bg-3 tokens)
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :font-family sans-stack
                       :font-size "11px"
                       :color (:text-secondary tokens)}}
      [:span {:style {:color (:text-tertiary tokens)
                      :text-transform "uppercase"
                      :font-size "10px"
                      :letter-spacing "0.5px"
                      :margin-right "8px"}}
       "Snapshot"]
      [:span {:style {:color (:text-secondary tokens)}}
       "transition · before / after"]]
     (snapshot-block {:machine-id machine-id
                      :phase :before
                      :label "Before"
                      :snapshot before})
     (snapshot-block {:machine-id machine-id
                      :phase :after
                      :label "After"
                      :snapshot after})]))

;; ---- per-machine focused-event section ---------------------------------

(defn- focused-event-section
  "Render one section per transitioned machine. Lens (above the chart,
  rf2-2n34o) → header → chart → snapshot drill-in (rf2-lxvn6) →
  cancellation cascade (inline) → after-rings overlay (on the chart).
  Guards / actions detail lives in the lens, not in a separate strip
  below the chart."
  [{:keys [machine-id from-state to-state on-event event microstep?
           definition fired-edge-ids before after]
    :as record}]
  ;; rf2-gpzb4 (2026-05-21 xyflow migration) — the host-side ELK
  ;; layout dance (layout-or-fallback / ensure-elk! / compute-layout!)
  ;; is GONE. xyflow + elkjs now own positioning end-to-end inside
  ;; `mv-chart/MachineChart`; the panel only computes the from/to
  ;; node-ids for the focused-event lens highlight.
  (let [from-id    (when from-state (chart-layout/highlight-id from-state))
        to-id      (when to-state   (chart-layout/highlight-id to-state))
        engine     "xyflow+elkjs"]
    [:section
     {:data-testid (str "rf-xray-machine-focused-event-section-"
                        (when machine-id
                          (subs (str machine-id) 1)))
      :data-machine-id (str machine-id)
      :data-from-state (str from-state)
      :data-to-state (str to-state)
      :data-on-event (str on-event)
      :data-microstep (str (boolean microstep?))
      ;; rf2-zdfbm — the topology is the panel's centrepiece, so the
      ;; section grows to fill the focused-event host's available
      ;; height. A flex column lets the canvas chart (`flex 1` below)
      ;; expand into the panel instead of sitting in a fixed 320px box.
      :style {:margin "12px"
              :border (str "1px solid " (:border-default tokens))
              :border-radius "4px"
              :background (:bg-2 tokens)
              :flex "1 1 0"
              :min-height 0
              :display "flex"
              :flex-direction "column"}}
     ;; Right-click on the per-machine section header fires
     ;; `:rf.xray/filter-by-machine` with this section's machine-id
     ;; (rf2-piye4) — drops a typed `:machine` IN pill into the ribbon
     ;; so the L2 event list narrows to cascades involving this machine.
     [:header {:data-testid "rf-xray-machine-focused-event-header"
               :on-context-menu (fn [^js e]
                                  (when machine-id
                                    (.preventDefault e)
                                    (rf/dispatch
                                      [:rf.xray/filter-by-machine machine-id]
                                      {:frame :rf/xray})))
               :title "Right-click to filter the event list to this machine"
               :style {:padding "10px 12px"
                       :display "flex"
                       :align-items "center"
                       :gap "10px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :background (:bg-3 tokens)
                       :font-family mono-stack
                       :font-size "12px"
                       :color (:text-primary tokens)}}
      (when microstep?
        [:span {:style {:color (:text-tertiary tokens) :font-size "10px"}}
         "↳"])
      [:strong {:style {:color (:accent tokens)}}
       (h/format-machine-id machine-id)]
      [:span {:style {:color (:text-secondary tokens)}}
       (h/format-state from-state)]
      [:span {:style {:color (:accent tokens)}} "→"]
      [:span {:style {:color (:text-primary tokens) :font-weight 600}}
       (h/format-state to-state)]
      (when event
        [:span {:style {:color (:text-tertiary tokens)
                        :font-size "11px"
                        :margin-left "auto"}}
         (h/format-event event)])]
     ;; rf2-2n34o — focused-transition lens, ABOVE the chart per
     ;; spec/003 §Focused-transition lens. The lens is the panel's
     ;; forensic above-chart block; the chart below shows the same
     ;; transition's topology.
     (focused-transition-lens record)
     (cond
       (nil? definition)
       [:div {:data-testid "rf-xray-machine-focused-event-no-definition"
              :style {:padding "12px"
                      :font-family sans-stack
                      :font-size "11px"
                      :color (:text-tertiary tokens)}}
        "No introspectable definition — chart cannot render."]

       :else
       (let [view-mode @(rf/subscribe
                          [:rf.xray.machine-canvas/view-mode-for machine-id])]
         (case view-mode
           :list
           ;; List view — chrome-thin pseudo-section just rendering a
           ;; tiny banner; the guards/actions/cascade panes that come
           ;; AFTER this block carry the real list payload. The
           ;; view-mode toggle still has to appear in this mode so the
           ;; user can flip back to Canvas — it's tucked into the
           ;; section header with a 'List view' chip.
           [:div {:data-testid "rf-xray-machine-focused-event-list"
                  :data-layout-engine engine
                  :data-machine-id (str machine-id)
                  :data-view-mode "list"
                  :style {:padding "8px 12px"
                          :background (:bg-1 tokens)
                          :border-bottom (str "1px solid " (:border-subtle tokens))
                          :display "flex"
                          :align-items "center"
                          :gap "10px"}}
            (machine-canvas/view-mode-toggle
              {:machine-id machine-id :mode view-mode})
            [:span {:style {:color (:text-tertiary tokens)
                            :font-family sans-stack
                            :font-size "11px"}}
             "Chart hidden in List view — flip to Canvas to inspect the topology."]]

           ;; default — :canvas
           [:div {:data-testid "rf-xray-machine-focused-event-chart"
                  :data-layout-engine engine
                  :data-machine-id (str machine-id)
                  :data-from-highlight-id (or from-id "")
                  :data-to-highlight-id (or to-id "")
                  ;; rf2-qeemm (G3) — surface the focused epoch's fired
                  ;; edge-ids on the canvas wrapper (sorted, space-joined)
                  ;; so the JVM/hiccup suite + hosts pin the wiring without
                  ;; reaching into the xyflow canvas. "" when none fired.
                  :data-fired-edge-ids (str/join
                                         " " (sort (set fired-edge-ids)))
                  :data-view-mode "canvas"
                  ;; rf2-zdfbm — fill the section's available height so the
                  ;; topology chart (`machine-canvas/Chart` is `height
                  ;; 100%`) expands into the panel rather than collapsing
                  ;; to its 260px min. `flex 1` + `min-height 0` lets the
                  ;; chart grow inside the flex-column section; the
                  ;; min-height floor keeps xyflow's non-zero-parent-
                  ;; height requirement satisfied when the panel is short.
                  :style {:padding "12px"
                          :background (:bg-1 tokens)
                          :overflow "hidden"
                          :flex "1 1 0"
                          :min-height "320px"
                          :display "flex"
                          :flex-direction "column"
                          ;; position-relative so the after-rings overlay
                          ;; can absolute-position itself over the chart SVG.
                          :position "relative"}}
            ;; rf2-y3l8z — the chart is now wrapped in an interactive
            ;; viewport adapter (zoom/pan/fit + view-mode toggle +
            ;; controls toolbar). The adapter owns the after-rings
            ;; overlay so they stay co-located with the canvas.
            [machine-canvas/Chart
             {:definition         definition
              :machine-id         machine-id
              :from-highlight     from-state
              :to-highlight       to-state
              ;; rf2-qeemm (G3) — the focused epoch's traversed edges paint
              ;; the FIRED treatment on the live chart (canonical ids from
              ;; `extract-fired-edge-ids`, attached to the section record).
              :fired-edge-ids     fired-edge-ids
              :on-state-click     (fn [path]
                                    (rf/dispatch
                                      [:rf.xray/machine-state-clicked
                                       {:machine-id machine-id
                                        :path       path}]
                                      {:frame :rf/xray}))
              :show-after-rings?  true}]])))
     ;; rf2-lxvn6 (phase 4 of rf2-oqa60) — snapshot drill-in. Each
     ;; per-machine section renders the BEFORE / AFTER snapshot maps
     ;; through the first-class data-display widget (spec/021 §10).
     ;; Per-machine `:panel-id` qualifier keeps two machines' expansion
     ;; state independent; the `:before` / `:after` phase suffix scopes
     ;; the two sibling mounts on the same machine. The whole block
     ;; renders nothing when the trace tags lack the
     ;; commit-or-finalize snapshot pair (legacy fixtures).
     (snapshot-drill-in record)
     ;; rf2-2n34o — guards/actions detail lives in the
     ;; `focused-transition-lens` ABOVE the chart (per spec/003
     ;; §Focused-transition lens). The redundant ✓/✗ status strips
     ;; that used to render below the chart are gone — single source of
     ;; truth for the forensic block.
     ;; rf2-59e7k — Cancellation cascade inline (per machine). The
     ;; SidePanel reg-view short-circuits to nil when the focused
     ;; machine has no cancellation in the trace window, so the mount
     ;; is dormant in the common case.
     [cancellation-cascade/SidePanel]]))

;; ---- prev/next nav (per-machine epoch walking) -------------------------

(defn- prev-next-nav
  "Inline prev/next buttons for the currently-focused machine. Walks
  the epoch history to the prior / next epoch that ALSO touched the
  focused machine. Disabled when no machine is in scope."
  [machine-id]
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
                                   {:frame :rf/xray}))
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
                                   {:frame :rf/xray}))
       :title       (str "Next event touching " (h/format-machine-id machine-id))
       :style       {:background "transparent"
                     :border (str "1px solid " (:border-default tokens))
                     :color (:accent tokens)
                     :font-family sans-stack
                     :font-size "11px"
                     :padding "3px 10px"
                     :border-radius "10px"
                     :cursor "pointer"}}
      "Next ▶"]]))

;; ---- focused-event view + blank state ----------------------------------

(defn- focused-event-view
  "Top-level focused-event lens. Reads the
  `:rf.xray/machine-transitions-for-focused-event` composite sub and
  binds the panel to **exactly one** machine instance per the
  Dynamic-mode single-instance rule (rf2-8og3k): the first transition
  record in trace order. Returns nil when no machine transitioned in
  the focused event's cascade — the panel renders the empty-state
  placeholder in that case (see `blank-state`)."
  []
  (let [records @(rf/subscribe
                   [:rf.xray/machine-transitions-for-focused-event])
        ;; Dynamic-mode single-instance rule (spec/003 §Dynamic mode —
        ;; single-instance, event-driven, rf2-8og3k): pick the first
        ;; transition by trace order. The upstream projection already
        ;; sorts cascade-document-order, so `first` is the tiebreaker.
        record  (h/pick-focused-transition records)]
    (when record
      [:div {:data-testid "rf-xray-machine-focused-event"
             ;; The host carries the count of records the cascade
             ;; transitioned (1..N) but only the focused instance
             ;; renders — pinned so tests can assert the rule (one
             ;; section even when N > 1).
             :data-section-count "1"
             :data-cascade-transition-count (str (count records))
             ;; rf2-zdfbm — fill the focused-event host so the section
             ;; (`flex 1`) can grow its topology chart into the panel's
             ;; available height.
             :style {:display "flex"
                     :flex-direction "column"
                     :flex 1
                     :min-height 0}}
       (with-meta (focused-event-section record)
         {:key (str (:machine-id record) "-"
                    (:id record) "-"
                    (:from-state record) "-"
                    (:to-state record))})])))

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

;; ---- share button -------------------------------------------------------

(defn- share-button
  "Top-right Share button in the panel toolbar."
  []
  [:button
   {:data-testid "rf-xray-machine-inspector-share-button"
    :on-click    (fn [_]
                   (rf/dispatch [:rf.xray/share-modal-open] {:frame :rf/xray}))
    :title       "Share this view (URL with focus + mode + scrubber)"
    :style       {:background "transparent"
                  :border (str "1px solid " (:border-default tokens))
                  :color (:accent tokens)
                  :font-family sans-stack
                  :font-size "11px"
                  :font-weight 600
                  :padding "4px 12px"
                  :border-radius "10px"
                  :cursor "pointer"
                  :white-space "nowrap"}}
   "⤴ Share"])

;; ---- public view --------------------------------------------------------

(rf/reg-view Panel
  "The Machine Inspector panel's root view. Event-driven: BLANK when
  the focused event has no machine activity; one section per machine
  when it does. The header carries the Share button + the per-machine
  prev/next nav (when a machine is in scope)."
  []
  (let [{:keys [empty-kind]} @(rf/subscribe [:rf.xray/machine-inspector-data])
        records @(rf/subscribe [:rf.xray/machine-transitions-for-focused-event])
        ;; The first record's machine-id drives the prev/next nav (a
        ;; cascade may touch multiple machines; the nav's "this machine"
        ;; is the head section's machine — same default-focus pattern
        ;; the cascade SidePanel uses).
        scope-machine-id (some-> records first :machine-id)]
    [:section {:data-testid "rf-xray-machine-inspector"
               :data-view-mode "focused-event"
               :data-has-records (str (boolean (seq records)))
               :style {:height         "100%"
                       :display        "flex"
                       :flex-direction "column"
                       :background     (:bg-2 tokens)
                       :color          (:text-primary tokens)
                       :font-family    sans-stack
                       :font-size      "14px"}}
     [:header {:data-testid "rf-xray-machine-inspector-header"
               :style {:padding "16px 16px 8px 16px"
                       :display "flex"
                       :align-items "center"
                       :justify-content "space-between"
                       :gap "12px"}}
      ;; rf2-6xezz — Mike-direction 2026-05-21: the large h1 "Machine
      ;; inspector" heading is scrubbed; the L4 tab strip is the
      ;; panel-name source-of-truth. The header row keeps the nav +
      ;; share affordances on the right.
      [:div]
      (when (not= :no-machines empty-kind)
        [:div {:style {:display "flex"
                       :align-items "center"
                       :gap "8px"}}
         (prev-next-nav scope-machine-id)
         (share-button)])]
     (cond
       (= :no-machines empty-kind)
       (empty-state)

       (seq records)
       ;; rf2-zdfbm — flex column so the focused-event view fills the
       ;; host and the topology chart grows into the panel height.
       [:div {:data-testid "rf-xray-machine-inspector-focused-event-host"
              :style {:flex 1
                      :overflow "auto"
                      :display "flex"
                      :flex-direction "column"}}
        (focused-event-view)]

       :else
       (blank-state))]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Machine Inspector panel's Xray-side
  registrations. Post-collapse (rf2-y9xmf) the panel registers:

    - the per-machine projection composite (`:rf.xray/machine-inspector-data`)
    - the focused-event lens composite (`:rf.xray/machine-transitions-for-focused-event`)
    - the per-machine prev/next nav events
    - the scrubber-position slot (kept for share-URL compatibility;
      the scrubber UI is gone but the slot round-trips through share)
    - the rings install (`:after` countdown ring overlay)
    - the share-affordance install

  rf2-r4nao moved the Sim engine + UI into
  `static.machines.sim` — installed via
  `static.machines.panel/install!` further down the registry."
  []
  ;; Registered-machine vector (reads `(rf/machines)`).
  (rf/reg-sub :rf.xray/registered-machines
    (fn [db _query]
      (let [ov (get db :registered-machines-override)]
        (or ov
            (try (vec (rf/machines))
                 (catch :default _ []))))))

  (rf/reg-event-db :rf.xray/set-registered-machines-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :registered-machines-override)
        (assoc db :registered-machines-override ov))))

  ;; The live snapshots map for every registered machine.
  (rf/reg-sub :rf.xray/machine-snapshots
    :<- [:rf.xray/target-frame-db]
    (fn [target-frame-db _query]
      (when (map? target-frame-db)
        (get target-frame-db :rf/machines {}))))

  (rf/reg-sub :rf.xray/machine-snapshots-override
    (fn [db _query]
      (get db :machine-snapshots-override)))

  (rf/reg-event-db :rf.xray/set-machine-snapshots-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :machine-snapshots-override)
        (assoc db :machine-snapshots-override ov))))

  ;; The registered-machine-definition map for every machine.
  (rf/reg-sub :rf.xray/machine-definitions-override
    (fn [db _query]
      (get db :machine-definitions-override)))

  (rf/reg-sub :rf.xray/machine-definitions
    :<- [:rf.xray/registered-machines]
    :<- [:rf.xray/machine-definitions-override]
    (fn [[machines override] _query]
      (or override
          (into {}
                (keep (fn [id]
                        (let [m (try (rf/machine-meta id)
                                     (catch :default _ nil))]
                          (when m [id m]))))
                (or machines [])))))

  (rf/reg-event-db :rf.xray/set-machine-definitions-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :machine-definitions-override)
        (assoc db :machine-definitions-override ov))))

  ;; The user's per-panel machine selection (kept as a slot for the
  ;; Sim engine + share-URL round-trip; the collapsed Dynamic panel
  ;; itself drives focus off the event lens, not the picker slot).
  (rf/reg-sub :rf.xray/selected-machine-id
    (fn [db _query]
      (get db :selected-machine-id)))

  ;; The per-panel composite — one read produces every slot the panel
  ;; consumes. Kept post-collapse so callers (after-rings, share, sim)
  ;; that read `:selected-id` / `:empty-kind` keep working without
  ;; touching their wiring.
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
        (mapv (fn [{:keys [machine-id definition] :as rec}]
                (assoc rec :fired-edge-ids
                       (trace-state/extract-fired-edge-ids
                         definition events machine-id)))
              (h/project-focused-event-transitions events definitions)))))

  ;; Test-only overrides for the focused-event composite.
  (rf/reg-event-db :rf.xray/set-epoch-history-for-test
    (fn [db [_ history]]
      (if (nil? history)
        (dissoc db :epoch-history)
        (assoc db :epoch-history (vec history)))))

  (rf/reg-event-db :rf.xray/set-focus-epoch-id-for-test
    (fn [db [_ epoch-id]]
      (if (nil? epoch-id)
        (update db :focus dissoc :epoch-id)
        (update db :focus (fnil assoc {}) :epoch-id epoch-id))))

  ;; ---- Machine Inspector panel events -----------------------------

  (rf/reg-event-db :rf.xray/select-machine-id
    (fn [db [_ machine-id]]
      (assoc db :selected-machine-id machine-id)))

  (rf/reg-event-db :rf.xray/clear-machine-selection
    (fn [db _event]
      (dissoc db :selected-machine-id)))

  (rf/reg-event-db :rf.xray/machine-state-clicked
    (fn [db [_ _payload]]
      db))

  (rf/reg-event-db :rf.xray/machine-chart-layout-pulse
    (fn [db _event]
      (update db :machine-inspector/elk-pulse-tick (fnil inc 0))))

  ;; ---- per-machine prev/next nav (rf2-y9xmf) ---------------------

  ;; Walk the epoch-history to the prior / next epoch that ALSO
  ;; touched the focused machine. The focused-event lens picks the
  ;; head section's machine-id as scope; these events filter
  ;; epoch-history for that machine + step the spine's focus.
  (letfn [(epoch-touches-machine? [epoch machine-id]
            (some (fn [ev]
                    (and (h/transition-event? ev)
                         (= machine-id (h/machine-id-of ev))))
                  (or (:trace-events epoch) [])))
          (scope-machine-id [db]
            (let [history (vec (or (get db :epoch-history) []))
                  focus   (get db :focus)
                  record  (h/focused-epoch-record history focus)
                  events  (when record (:trace-events record))
                  records (h/project-focused-event-transitions events nil)]
              (or (some-> records first :machine-id)
                  (get db :selected-machine-id))))
          (step-focus [db direction]
            (let [history (vec (or (get db :epoch-history) []))
                  mid     (scope-machine-id db)
                  current (get-in db [:focus :epoch-id])
                  cur-idx (or (some (fn [[i r]]
                                      (when (= (:epoch-id r) current) i))
                                    (map-indexed vector history))
                              (dec (count history)))
                  step    (case direction :prev dec :next inc)
                  match?  (fn [r] (epoch-touches-machine? r mid))
                  pred    (case direction
                            :prev #(neg? %)
                            :next #(>= % (count history)))]
              (loop [i (step cur-idx)]
                (cond
                  (or (nil? mid) (pred i))
                  db

                  (match? (nth history i))
                  (update db :focus (fnil assoc {}) :epoch-id
                          (:epoch-id (nth history i)))

                  :else (recur (step i))))))]
    (rf/reg-event-db :rf.xray/machine-focus-prev
      (fn [db _event] (step-focus db :prev)))

    (rf/reg-event-db :rf.xray/machine-focus-next
      (fn [db _event] (step-focus db :next))))

  ;; ---- scrubber-position slot (share-URL compatibility) ----------

  ;; The scrubber UI is gone (rf2-y9xmf), but the slot survives because
  ;; share.cljs / share_modal.cljs round-trip the position through the
  ;; share URL. Reads default to `:present`. The companion `set-scrubber-
  ;; position` event keeps the contract bidirectional.
  (rf/reg-sub :rf.xray/machine-scrubber-position
    (fn [db _query]
      (get db :machine-inspector/scrubber-position :present)))

  (rf/reg-event-db :rf.xray/set-scrubber-position
    (fn [db [_ position]]
      (cond
        (= :present position)
        (assoc db :machine-inspector/scrubber-position :present)

        (integer? position)
        (assoc db :machine-inspector/scrubber-position position)

        (nil? position)
        (assoc db :machine-inspector/scrubber-position :present)

        :else db)))

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

  ;; ---- Share affordance (rf2-nqw0v) -----------------------------
  (share/install!)

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
