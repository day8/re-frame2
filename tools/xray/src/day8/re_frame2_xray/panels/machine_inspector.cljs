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
            [day8.re-frame2-xray.diff.engine :as diff-engine]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.cancellation-cascade :as cancellation-cascade]
            [day8.re-frame2-xray.panels.machine-canvas :as machine-canvas]
            [day8.re-frame2-xray.panels.machine-inspector-helpers :as h]
            [day8.re-frame2-xray.panels.machines.trace-state :as trace-state]
            [day8.re-frame2-xray.panels.machine-after-rings :as after-rings]
            [day8.re-frame2-xray.share :as share]
            ;; rf2-lxvn6 (phase 4 of rf2-oqa60) — the per-machine
            ;; snapshot drill-in surface mounts the first-class
            ;; edn-inspector widget directly. Each machine gets its own
            ;; `:panel-id` qualifier so two machines' expansion state
            ;; stays independent. See spec/021 §10 widget contract.
            [day8.re-frame2-xray.views.diff-mode-toggle :as diff-mode]
            [day8.re-frame2-xray.views.edn-inspector :as ei]
            [day8.re-frame2-xray.theme.tokens
             :as t
             :refer [tokens mono-stack sans-stack display-stack spacing]]))

;; ---- shared layout constants (rf2-3d987) ------------------------------
;;
;; The Machine panel's 8 layout fixes (rf2-3d987) split header styling
;; into two tiers — OUTER (section header at the top of focused-event-
;; section; ribbon background, larger font) and NESTED (sub-section
;; headers inside the same section; lighter chrome, smaller font,
;; bottom-border separator instead of full-ribbon background). Issue #7
;; fix: nested headers MUST be visually distinguishable from outer
;; headers so the operator's eye reads hierarchy at a glance.
;;
;; Both tiers consume tokens (`tokens` map · CSS variables) so the
;; light/dark theme toggle continues to flip palette in lockstep.

(def ^:private nested-header-base-style
  "Style map for any sub-section header NESTED inside the outer
  focused-event-section. Per rf2-3d987 issue #7 these headers use a
  smaller font, no ribbon background, and a bottom-border separator —
  so the operator can tell a nested header from the outer section
  header at a glance."
  {:padding "6px 12px"
   :background "transparent"
   :border-bottom (str "1px solid " (:border-subtle tokens))
   :font-family sans-stack
   :font-size "11px"
   :color (:text-secondary tokens)
   :display "flex"
   :align-items "center"
   :gap "8px"})

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
  "Top header strip — carries the prev/next nav + share affordance on
  the right (the L4 tab strip is the panel-name source-of-truth, so
  there is no large title; rf2-6xezz)."
  {:padding         "16px 16px 8px 16px"
   :display         "flex"
   :align-items     "center"
   :justify-content "space-between"
   :gap             "12px"})

(def ^:private panel-header-toolbar-style
  "Right-hand affordance cluster inside the panel header (prev/next +
  share). Suppressed when `:no-machines` empty-state is rendering."
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

(def ^:private focused-event-section-header-style
  "OUTER section header — ribbon background + slightly larger font
  than nested headers (rf2-3d987 issue #7 differential). Carries the
  machine-id + from→to transition path; right-click filters the
  event list to this machine (rf2-piye4)."
  {:padding                 "10px 12px"
   :display                 "flex"
   :align-items             "center"
   :gap                     "10px"
   :background              (:bg-3 tokens)
   :font-family             mono-stack
   :font-size               "13px"
   :color                   (:text-primary tokens)
   :border-top-left-radius  "4px"
   :border-top-right-radius "4px"})

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

(def ^:private snapshot-flat-diff-root-style
  "Outer `[:div]` wrapper for the snapshot-flat-diff body."
  {:padding    "8px 12px"
   :background (:bg-2 tokens)
   :min-width  0})

(def ^:private snapshot-flat-diff-empty-style
  "Italic muted `(no changes)` placeholder body."
  {:font-family sans-stack
   :font-size   "11px"
   :font-style  "italic"
   :color       (:text-tertiary tokens)})

(def ^:private snapshot-flat-diff-list-style
  "Inner `[:div]` host for the flat-diff row list — mono base."
  {:font-family mono-stack
   :font-size   "12px"})

(def ^:private snapshot-flat-diff-row-style
  "One change-list row inside snapshot-flat-diff-body."
  {:display     "flex"
   :gap         "8px"
   :align-items "baseline"
   :padding     "2px 0"
   :flex-wrap   "wrap"})

(def ^:private snapshot-flat-diff-glyph-base-style
  "Per-row glyph span — only `:color` varies, applied via assoc."
  {:font-weight 700
   :min-width   "1ch"})

(def ^:private snapshot-flat-diff-path-style
  "Path-prefix span — the `[:foo :bar]` text inside one diff row."
  {:color      (:text-secondary tokens)
   :word-break "break-word"})

(def ^:private snapshot-flat-diff-modified-before-style
  "The `~`-modified row's BEFORE-value mini inspector — strikethrough."
  {:color           (:text-tertiary tokens)
   :text-decoration "line-through"})

(def ^:private snapshot-flat-diff-modified-arrow-style
  "The `→` separator between BEFORE and AFTER on `~`-modified rows."
  {:color (:text-tertiary tokens)})

(def ^:private snapshot-flat-diff-modified-after-style
  "The `~`-modified row's AFTER-value mini inspector."
  {:color (:success tokens)})

(def ^:private snapshot-flat-diff-added-style
  "The `+`-added row's value mini inspector — success-coloured, flex 1."
  {:color     (:success tokens)
   :flex      1
   :min-width 0})

(def ^:private snapshot-flat-diff-removed-style
  "The `−`-removed row's value mini inspector — strikethrough error."
  {:color           (:error tokens)
   :text-decoration "line-through"
   :flex            1
   :min-width       0})

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
  resolved via `rf/handler-meta` which is a pure registrar lookup.

  rf2-3d987 issue #6 (option b): lens is metadata about the focused
  transition — chrome dims relative to the interactive chart so the
  operator's eye reads `chart = primary, lens = secondary`. Same body
  background as the rest of the section interior (`bg-2`); padding-only
  separation; section labels carry weight differential per issue #5."
  [{:keys [machine-id from-state to-state guards actions]}]
  [:div {:data-testid "rf-xray-machine-focused-transition-lens"
         :data-machine-id (str machine-id)
         :data-guard-count (str (count guards))
         :data-action-count (str (count actions))
         ;; Issue #6 option (b) — secondary-metadata treatment. No
         ;; coloured body (matches the section's `bg-2`); slightly
         ;; smaller mono font; lighter default text colour. The lens
         ;; reads as supplementary rather than co-equal with the chart.
         :style {:padding "10px 14px"
                 :background "transparent"
                 :font-family mono-stack
                 :font-size "11px"
                 :color (:text-secondary tokens)
                 :line-height 1.55}}
   [:div {:data-testid "rf-xray-machine-lens-target-instance"
          :style {:margin-bottom "6px"}}
    [:span {:style {:color (:text-tertiary tokens)
                    :font-weight 600}}
     "Target Machine Instance"]
    [:span {:style {:color (:text-tertiary tokens)}} " · "]
    [:span {:style {:color (:magenta tokens)}}
     (h/format-machine-id machine-id)]]
   [:div {:data-testid "rf-xray-machine-lens-transition"
          :style {:margin "4px 0"}}
    ;; Issue #5 — `<strong>` weight differential on the section
    ;; label so the eye picks it out from the path that follows.
    [:strong {:style {:color (:text-tertiary tokens)
                      :text-transform "uppercase"
                      :font-size "10px"
                      :letter-spacing "0.5px"
                      :font-weight 700}}
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
      [:strong {:style {:color (:text-tertiary tokens)
                       :text-transform "uppercase"
                       :font-size "10px"
                       :letter-spacing "0.5px"
                       :font-weight 700}}
       "Guards Run"]
      (into [:div]
            (for [g guards]
              ^{:key (str (:guard-id g))}
              (lens-guard-block machine-id g)))])
   (when (seq actions)
     [:div {:data-testid "rf-xray-machine-lens-actions-run"
            :style {:margin "4px 0"}}
      [:strong {:style {:color (:text-tertiary tokens)
                       :text-transform "uppercase"
                       :font-size "10px"
                       :letter-spacing "0.5px"
                       :font-weight 700}}
       "Actions Run"]
      (into [:div]
            (for [a actions]
              ^{:key (str (:action-id a))}
              (lens-action-block machine-id a)))])])

;; ---- snapshot drill-in (rf2-lxvn6 · spec/021 §10 widget contract) -----
;;
;; Phase 4 of rf2-oqa60 wires the per-machine snapshot value through
;; the first-class edn-inspector widget at
;; `day8.re-frame2-xray.views.edn-inspector`. Each call site qualifies
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
  the widget's `:rf.xray.edn-inspector/expansion` slot scopes by
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

(defn- snapshot-flat-diff-rows
  "Project the (before, after) snapshot pair through the shared
  Editscript engine to a flat list of `[path before after change-kind]`
  triples — the same shape the App-DB / Epoch HANDLER `:diff` lens
  consumes. Used by the Machine Inspector's `:diff` mode (rf2-yqjrd).

  rf2-5j7ch — runs the engine's `expand-empty-root-replacement`
  post-processor so a snapshot going `{} → {:state :idle …}` (cold-
  boot first-transition) reads as per-key adds, not a wholesale
  root replacement. Mirrors the App-DB `:diff` sub.

  Returns an empty vector when no Editscript edits surface (identical
  snapshots) so the renderer can paint a `— (no changes)` placeholder."
  [before after]
  (let [proj (diff-engine/project before after)
        rows (diff-engine/expand-empty-root-replacement (:flat-rows proj))]
    (mapv (fn [{:keys [path op before after]}]
            (let [kind (case op
                         :added    :added
                         :removed  :removed
                         :modified :modified
                         :modified)]
              [path before after kind]))
          rows)))

(defn- snapshot-flat-diff-body
  "Render the `:diff` lens for the snapshot drill-in — flat path-
  prefixed rows showing the Editscript projection of `(before, after)`.
  Mirrors the App-DB `:diff` body shape so the operator sees the same
  flat-list grammar regardless of surface."
  [{:keys [machine-id before after]}]
  (let [rows  (snapshot-flat-diff-rows before after)
        empty? (empty? rows)]
    [:div {:data-testid (str "rf-xray-machine-snapshot-diff-"
                             (machine-id-suffix machine-id))
           :data-machine-id (str machine-id)
           :data-empty (str empty?)
           :style snapshot-flat-diff-root-style}
     (if empty?
       [:div {:style snapshot-flat-diff-empty-style}
        "— (no changes)"]
       ;; rf2-9ec65 — eager realisation via `map-indexed` so any future
       ;; subscribe deref inside the row body registers in this render's
       ;; reactive scope (spec/006 §Lazy-seq deref tracking, rf2-atqkg).
       ;; `into` already realises the seq, but the named-fn shape aligns
       ;; with the canonical eager idiom used in `diff/hiccup_render.cljs`.
       (into [:div {:style snapshot-flat-diff-list-style}]
             (map-indexed
               (fn [i [path before-v after-v kind]]
                 (let [glyph        (case kind
                                      :added    "+"
                                      :removed  "−"
                                      :modified "~"
                                      "~")
                       glyph-colour (case glyph
                                      "+" (:success tokens)
                                      "−" (:error tokens)
                                      (:warning tokens))]
                   [:div {:key (str "row-" i)
                          :data-testid (str "rf-xray-machine-snapshot-diff-row-"
                                            (machine-id-suffix machine-id) "-" i)
                          :style snapshot-flat-diff-row-style}
                    [:span {:style (assoc snapshot-flat-diff-glyph-base-style
                                          :color glyph-colour)}
                     glyph]
                    [:span {:style snapshot-flat-diff-path-style}
                     (pr-str path)]
                    (when (= "~" glyph)
                      [:<>
                       [:span {:style snapshot-flat-diff-modified-before-style}
                        [ei/mini before-v 40]]
                       [:span {:style snapshot-flat-diff-modified-arrow-style} "→"]
                       [:span {:style snapshot-flat-diff-modified-after-style}
                        [ei/mini after-v 40]]])
                    (when (= "+" glyph)
                      [:span {:style snapshot-flat-diff-added-style}
                       [ei/mini after-v 80]])
                    (when (= "−" glyph)
                      [:span {:style snapshot-flat-diff-removed-style}
                       [ei/mini before-v 80]])])))
               rows))]))

(defn- snapshot-block
  "Render a machine snapshot map (`{:state X :data Y}`) via the
  first-class edn-inspector widget (rf2-oqa60 phase 1, rf2-lxvn6 phase
  4). One mount; the view-mode toggle (rf2-yqjrd) drives whether
  `:before` is threaded as the diff pre-image.

  Replaces the prior side-by-side Before/After grid (retired
  2026-05-27 per rf2-yqjrd) — mode-3 (`:full+diff`) carries the same
  meanings the two-column layout did (full state + diff context +
  comparison) in a single unified surface, and the `:diff` mode
  carries the pure-diff lens for operators who want only the
  changes.

  Mode behaviour:

  - `:full`      — render the AFTER snapshot with no `:before` (plain
                   browse).
  - `:full+diff` — render the AFTER snapshot with `:before` threaded
                   so changed leaves carry inline `← changed from X`
                   annotations + row chrome (added/modified/removed).
                   Default.
  - `:diff`      — render via `snapshot-flat-diff-body` (handled by
                   `snapshot-drill-in`, not here).

  Renders `nil` when the snapshot is absent."
  [{:keys [machine-id snapshot before-snapshot mode]}]
  (when (some? snapshot)
    [:div {:data-testid    (str "rf-xray-machine-snapshot-block-"
                                (machine-id-suffix machine-id))
           :data-machine-id (str machine-id)
           ;; rf2-xvu24 — canonical `data-rf-xray-diff-mode` axis (was
           ;; the drifted `data-view-mode`).
           :data-rf-xray-diff-mode (when (keyword? mode) (name mode))
           ;; Issue #2 (option b): match the outer section's `bg-2`
           ;; rather than the brighter `bg-1` so the snapshot reads as
           ;; continuation of the body, not as a second card layer.
           :style {:padding "8px 12px"
                   :background (:bg-2 tokens)
                   :min-width 0}}
     [ei/edn-inspector snapshot
      (cond-> {:panel-id (snapshot-panel-id machine-id :after)
               ;; rf2-pvsxs — machine + phase identifiers; the operator's
               ;; drill-into-data choices survive a Machines tab leave-
               ;; and-return round-trip. `:after` is the canonical phase
               ;; suffix for the single-mount post-rf2-yqjrd shape (the
               ;; old `:before` mount was retired with the side-by-side
               ;; grid).
               :site-id  [:rf.xray.machines/inspector-snapshot machine-id :after]
               :default-expanded-depth (case mode :full+diff 3 2)
               ;; rf2-l4625 — machine snapshots routinely carry deeply-
               ;; nested `:data` maps; the popup gives the operator a
               ;; full-modal inspection surface alongside the per-
               ;; machine drill-in.
               :popup-affordance? true}
        ;; Mode-3: thread the BEFORE snapshot as the diff pre-image so
        ;; the widget paints inline `← changed from X` annotations.
        ;; Mode `:full` skips the threading — pure-data browse.
        (and (= mode :full+diff) (some? before-snapshot))
        (assoc :before before-snapshot :full-with-diff? true))]]))

(defn- snapshot-mode-toggle
  "Three-button toggle bar `[diff][full][full+diff]` for the snapshot
  drill-in (rf2-yqjrd). Shared widget; dispatches frame-anchored to
  `:rf/xray` per the rf2-p56sk / rf2-7sdja pattern."
  [mode]
  [diff-mode/diff-mode-toggle
   {:mode      mode
    ;; rf2-7vv8f — testid prefix normalised to `rf-xray-<surface>-diff-mode`.
    ;; rf2-shuxd — `<surface>` matches the sub-id namespace
    ;; (`:rf.xray.machine-inspector`) for one naming root across DevTools
    ;; (testid) + Trace (sub-id). Prior `machine-snapshot` testid split the
    ;; root from the dispatching sub; aligned to `machine-inspector` here.
    :testid    "rf-xray-machine-inspector-diff-mode"
    ;; rf2-fytu4 — uniform "View" discoverability label.
    :label     "View"
    :on-change (fn [m]
                 (rf/with-frame :rf/xray
                   (rf/dispatch [:rf.xray.machine-inspector/set-diff-mode m])))}])

(defn- snapshot-drill-in
  "Snapshot drill-in section beneath the focused-event chart. Renders
  the focused-transition snapshot via the first-class edn-inspector
  widget so the operator can inspect what `:data` carried on either
  side of the transition (spec/003 §M.10 bug class — `:data` mutations
  invisible without app-db diff).

  rf2-yqjrd — RETIRES the prior Before/After side-by-side grid
  (rf2-3d987 issue #3). The two-column layout is replaced by a SINGLE
  mount + the universal three-mode toggle `[diff][full][full+diff]`.
  Mode-3 (`:full+diff`, default) carries the same meanings the side-
  by-side did — full AFTER state + diff context + comparison against
  BEFORE — in one unified surface via the R1-R8 grammar (rf2-n2jig).
  The `:diff` mode adds a pure-diff lens (flat path list) for
  operators who want only the changes. Pre-alpha posture; no shim.

  Per spec/021 §10 widget contract every call site qualifies with a
  per-machine `:panel-id`; the post-rf2-yqjrd shape uses a single
  `:after`-phase qualifier (the second mount + its `:before`
  qualifier retired with the side-by-side grid).

  Renders nothing when the AFTER snapshot is absent (legacy trace
  fixtures pre-dating the commit-or-finalize snapshot tagging — see
  `transition-record-from-trace` docstring)."
  [{:keys [machine-id before after]}]
  (when (or (some? before) (some? after))
    (let [mode @(rf/subscribe :rf/xray
                              [:rf.xray.machine-inspector/diff-mode])]
      [:section
       {:data-testid     "rf-xray-machine-snapshot-drill-in"
        :data-machine-id (str machine-id)
        :data-has-before (str (some? before))
        :data-has-after  (str (some? after))
        ;; rf2-xvu24 — canonical `data-rf-xray-diff-mode` axis (was
        ;; the drifted `data-view-mode`).
        :data-rf-xray-diff-mode (when (keyword? mode) (name mode))
        :style {:background (:bg-2 tokens)}}
       ;; Header carries the section label + the universal three-mode
       ;; toggle. Same shape as the Epoch HANDLER `:db` and App-DB
       ;; panel headers post-rf2-yqjrd.
       [:header {:data-testid "rf-xray-machine-snapshot-drill-in-header"
                 :style (assoc nested-header-base-style
                               :display "flex"
                               :align-items "center"
                               :gap "8px")}
        [:strong {:style {:color (:text-tertiary tokens)
                          :text-transform "uppercase"
                          :font-size "10px"
                          :letter-spacing "0.5px"
                          :font-weight 700}}
         "Snapshot"]
        [:span {:style {:color (:text-tertiary tokens)}} "·"]
        [:span {:style {:color (:text-secondary tokens)}}
         "transition"]
        (snapshot-mode-toggle mode)]
       ;; Body — pure-diff lens vs single-mount snapshot, driven by mode.
       (case mode
         :diff
         (snapshot-flat-diff-body {:machine-id machine-id
                                   :before before
                                   :after  after})

         (snapshot-block {:machine-id machine-id
                          :snapshot   (or after before)
                          :before-snapshot before
                          :mode mode}))])))

;; ---- per-machine focused-event section ---------------------------------

(defn- chart-collapse-toggle
  "Inline ▾ / ▸ button that toggles the chart-collapsed state for the
  per-machine focused-event-section (rf2-3d987 issue #4). Persisted
  via `machine-canvas`'s chart-collapsed-by-id slot (localStorage round-
  trip identical to view-mode-by-id) so the operator's choice survives
  reloads.

  `collapsed?` is the current state; click flips it via the
  `:set-chart-collapsed` event with mode `:toggle`."
  [{:keys [machine-id collapsed?]}]
  [:button
   {:data-testid (str "rf-xray-machine-chart-toggle-"
                      (machine-id-suffix machine-id))
    :data-machine-id (str machine-id)
    :data-collapsed (str (boolean collapsed?))
    :aria-expanded (str (not collapsed?))
    :title (if collapsed?
             "Expand chart"
             "Collapse chart (frees space for the snapshot pair)")
    :on-click (fn [_]
                (rf/dispatch
                  [:rf.xray.machine-canvas/set-chart-collapsed
                   {:machine-id machine-id :mode :toggle}]
                  {:frame :rf/xray}))
    :style {:background "transparent"
            :border "none"
            :color (:text-secondary tokens)
            :font-family sans-stack
            :font-size "11px"
            :font-weight 600
            :padding "2px 6px"
            :cursor "pointer"
            :border-radius "4px"
            :display "inline-flex"
            :align-items "center"
            :gap "6px"}}
   [:span {:style {:font-size "10px"}}
    (if collapsed? "▸" "▾")]
   [:span "Chart"]])

(defn- chart-collapsed-summary
  "One-line summary that replaces the expanded chart when the operator
  collapses it (rf2-3d987 issue #4). Communicates topology
  shape (node-count / transition-count) so the operator sees the chart
  is still here, just hidden."
  [{:keys [machine-id definition]}]
  (let [states     (:states definition)
        node-count (count states)
        ;; Each state's `:on` map is a `{event target-or-vec}` entry;
        ;; a state may also carry `:after` (one entry) producing
        ;; transitions to a single target. Conservative count: sum
        ;; the `:on` cardinalities plus 1 per `:after` (when present).
        transitions
        (reduce
          (fn [acc [_state-id m]]
            (+ acc (count (or (:on m) {})) (if (:after m) 1 0)))
          0
          states)]
    [:div {:data-testid (str "rf-xray-machine-chart-collapsed-summary-"
                             (machine-id-suffix machine-id))
           :data-machine-id (str machine-id)
           :data-node-count (str node-count)
           :data-transition-count (str transitions)
           :style {:padding "8px 12px"
                   :background (:bg-1 tokens)
                   :font-family sans-stack
                   :font-size "11px"
                   :color (:text-tertiary tokens)
                   :font-style "italic"}}
     (str "Machine topology · " node-count " "
          (if (= 1 node-count) "node" "nodes") " · "
          transitions " "
          (if (= 1 transitions) "transition" "transitions")
          " · click ▸ to expand")]))

(defn- focused-event-section
  "Render one section per transitioned machine. Lens (above the chart,
  rf2-2n34o) → header → chart → snapshot drill-in (rf2-lxvn6) →
  cancellation cascade (inline) → after-rings overlay (on the chart).
  Guards / actions detail lives in the lens, not in a separate strip
  below the chart.

  rf2-3d987 layout fixes:
   - issue #1: `gap: 8px` between sibling sub-panels via flex gap.
   - issue #4: chart is collapsible via the per-machine
     `:chart-collapsed` flag; toggle in the chart's nested header.
   - issue #5: outer header uses `<strong>` weight differential on the
     machine-id (already there) + the path uses an arrow separator.
   - issue #7: nested headers use lighter chrome than the outer header.
   - issue #8: outer margin bumped to 16px so the section has visible
     breathing room from the panel host edge."
  [{:keys [machine-id from-state to-state on-event event microstep?
           definition fired-edge-ids]
    :as record}]
  ;; rf2-gpzb4 (2026-05-21 xyflow migration) — the host-side ELK
  ;; layout dance (layout-or-fallback / ensure-elk! / compute-layout!)
  ;; is GONE. xyflow + elkjs now own positioning end-to-end inside
  ;; `mv-chart/MachineChart`; the panel only computes the from/to
  ;; node-ids for the focused-event lens highlight.
  (let [from-id    (when from-state (chart-layout/highlight-id from-state))
        to-id      (when to-state   (chart-layout/highlight-id to-state))
        engine     "xyflow+elkjs"
        collapsed? @(rf/subscribe
                      [:rf.xray.machine-canvas/chart-collapsed-for machine-id])]
    [:section
     {:data-testid (str "rf-xray-machine-focused-event-section-"
                        (when machine-id
                          (subs (str machine-id) 1)))
      :data-machine-id (str machine-id)
      :data-from-state (str from-state)
      :data-to-state (str to-state)
      :data-on-event (str on-event)
      :data-microstep (str (boolean microstep?))
      :data-chart-collapsed (str collapsed?)
      ;; rf2-zdfbm — the topology is the panel's centrepiece, so the
      ;; section grows to fill the focused-event host's available
      ;; height. A flex column lets the canvas chart (`flex 1` below)
      ;; expand into the panel instead of sitting in a fixed 320px box.
      ;;
      ;; rf2-3d987 issue #1 — `gap` on the flex column gives every
      ;; sibling sub-panel (lens / chart / snapshot drill-in /
      ;; cascade) visible breathing room. Background shows through
      ;; the gap so three concerns no longer read as one wall of grey.
      ;;
      ;; rf2-3d987 issue #8 — outer margin bumped from 12px → 16px so
      ;; the section has visible breathing room from the panel host
      ;; edge at every viewport width.
      :style focused-event-section-style}
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
               ;; OUTER header — ribbon background + slightly larger
               ;; font than nested headers (issue #7 differential).
               :style focused-event-section-header-style}
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
           ;; rf2-3d987 issue #4 — collapsible chart. The chart wrapper
           ;; carries its own nested header with a ▾/▸ toggle. When
           ;; collapsed the chart is replaced by a one-line summary
           ;; so the snapshot pair sits within the operator's foveal
           ;; band without scrolling.
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
                  :data-chart-collapsed (str collapsed?)
                  ;; rf2-zdfbm — fill the section's available height so the
                  ;; topology chart (`machine-canvas/Chart` is `height
                  ;; 100%`) expands into the panel rather than collapsing
                  ;; to its 260px min. `flex 1` + `min-height 0` lets the
                  ;; chart grow inside the flex-column section; the
                  ;; min-height floor keeps xyflow's non-zero-parent-
                  ;; height requirement satisfied when the panel is short.
                  ;;
                  ;; When collapsed the wrapper drops `flex 1` + the
                  ;; min-height floor so the row only consumes header +
                  ;; summary height — freeing screen real-estate for the
                  ;; snapshot pair below (issue #4).
                  :style (merge
                           {:background (:bg-2 tokens)
                            :display "flex"
                            :flex-direction "column"
                            :overflow "hidden"
                            :position "relative"}
                           (if collapsed?
                             {:flex "0 0 auto"}
                             {:flex "1 1 0"
                              :min-height "320px"}))}
            ;; Nested header (issue #7 — lighter chrome, smaller font,
            ;; bottom-border separator). Carries the ▾ / ▸ toggle.
            [:header {:data-testid "rf-xray-machine-focused-event-chart-header"
                      :style nested-header-base-style}
             (chart-collapse-toggle
               {:machine-id machine-id :collapsed? collapsed?})]
            (if collapsed?
              (chart-collapsed-summary
                {:machine-id machine-id :definition definition})
              [:div {:style {:flex "1 1 0"
                             :min-height 0
                             :padding "12px"
                             :background (:bg-1 tokens)
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
                 :show-after-rings?  true}]])])))
     ;; rf2-lxvn6 (phase 4 of rf2-oqa60) — snapshot drill-in. Each
     ;; per-machine section renders the BEFORE / AFTER snapshot maps
     ;; through the first-class edn-inspector widget (spec/021 §10).
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
  "Top-level focused-event lens. Accepts the focused-event lens'
  `records` (pre-derefed by `Panel` from
  `:rf.xray/machine-transitions-for-focused-event`) and binds the
  panel to **exactly one** machine instance per the Dynamic-mode
  single-instance rule (rf2-8og3k): the first transition record in
  trace order. Returns nil when no machine transitioned in the
  focused event's cascade — the panel renders the empty-state
  placeholder in that case (see `blank-state`).

  rf2-alsnz — `records` flows in as an arg so the panel makes one
  Reaction handle per render instead of two; Panel already derefs the
  composite sub for its `:data-has-records` flag + the scope-machine-
  id-driven prev/next nav, so this view re-derefing the same handle
  was a duplicate sub-graph touch."
  [records]
  (let [;; Dynamic-mode single-instance rule (spec/003 §Dynamic mode —
        ;; single-instance, event-driven, rf2-8og3k): pick the first
        ;; transition by trace order. The upstream projection already
        ;; sorts cascade-document-order, so `first` is the tiebreaker.
        record (h/pick-focused-transition records)]
    (when record
      [:div {:data-testid "rf-xray-machine-focused-event"
             ;; The host carries the count of records the cascade
             ;; transitioned (1..N) but only the focused instance
             ;; renders — pinned so tests can assert the rule (one
             ;; section even when N > 1).
             :data-section-count "1"
             :data-cascade-transition-count (str (count records))
             :style focused-event-view-host-style}
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
               :style panel-root-style}
     [:header {:data-testid "rf-xray-machine-inspector-header"
               :style panel-header-style}
      ;; rf2-6xezz — Mike-direction 2026-05-21: the large h1 "Machine
      ;; inspector" heading is scrubbed; the L4 tab strip is the
      ;; panel-name source-of-truth. The header row keeps the nav +
      ;; share affordances on the right.
      [:div]
      (when (not= :no-machines empty-kind)
        [:div {:style panel-header-toolbar-style}
         (prev-next-nav scope-machine-id)
         (share-button)])]
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
        (focused-event-view records)]

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
  ;; ---- Machine Inspector snapshot diff-mode toggle (rf2-yqjrd / rf2-0cyjm) -----
  ;;
  ;; Universal three-mode toggle for the snapshot drill-in section.
  ;; Replaces the prior Before/After side-by-side grid — one mount,
  ;; one toggle, same R1-R8 grammar every other Xray diff surface
  ;; uses (rf2-n2jig). Sub `:rf.xray.machine-inspector/diff-mode` +
  ;; event `:rf.xray.machine-inspector/set-diff-mode` + slot are
  ;; installed by the shared helper from `views.diff-mode-toggle` so
  ;; every Xray diff surface uses identical naming.
  (diff-mode/reg-mode-sub+event! :rf.xray.machine-inspector)

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
