(ns day8.re-frame2-xray.panels.trace
  "Trace panel — the whole-epoch trace as a FLAT list (spec/023-Trace-Panel.md).

  ## What this panel shows

  Every trace operation the substrate emits during the focused epoch, in
  fire order, as a SINGLE FLAT LIST of rows (rf2-aqusw — the 4-band
  hierarchy is gone). Its contract is COMPLETENESS: every op-family in
  the Spec-009 vocabulary surfaces.

  The list reads top-down, oldest-first:

      +0.0  DISPATCH         EVENT     dispatched   [:counter-inc]      —
      +0.1  COEFFECT         COEFFECT  run          :now -> #inst      0.1 ms
      +0.2  EVENT HANDLER    EVENT     handler ran  reg-event          0.2 ms
      +0.3  FLOW             FLOW      computed     :totals            1.5 ms
      +1.8  EFFECT HANDLERS  DB        changed      [:counter] 1 -> 2   —
      +1.9  EFFECT HANDLERS  FX        :http-xhrio  GET /api/data       —
      +2.6  SUBSCRIPTIONS    SUB       recalculated :counter/value     0.3 ms
      +3.1  VIEWS            VIEW      re-rendered  counter-display    1.8 ms

  Each op is a row of six columns (rf2-aqusw):

      Δt · stage · area badge · what-happened · target/detail · duration

  ## Stage column + colour-coded left edge (rf2-aqusw)

  The STAGE column names the Epoch-panel pipeline step each op belongs
  to — DISPATCH · COEFFECT · EVENT HANDLER · FLOW · EFFECT HANDLERS ·
  SUBSCRIPTIONS · VIEWS — and the row's left EDGE is colour-coded with
  that step's colour. Both the label and the colour resolve through the
  Epoch panel's own `panels.epoch.badge` taxonomy (NOT a parallel
  palette) so the Trace stage column + edge match the Epoch numbered
  cascade exactly — one step model, DRY. The flat list recovers, at a
  glance, the phase information the removed hierarchy conveyed.

  The area badge is a NEUTRAL text badge (EVENT · COEFFECT · DB · FX ·
  FLOW · SUB · VIEW · MACHINE · ROUTING · EPOCH · ERROR · WARNING) — no
  per-family colour.

  Errors / warnings are cross-cutting (spec/023 §7) — they render inline
  at their chronological point in the flat list, emphasised so failures
  stand out (the left edge rides the severity colour over the stage
  colour). Clicking any row opens the edn-inspector on its raw
  trace-event MAP inline (spec/023 §3) via the first-class edn-inspector
  widget (spec/021 §10 / `views.edn-inspector`).

  ## Design system (PR #2089 · Handler panel idiom)

  The list is rendered in the established Xray devtools design language —
  the `--devtools-*` dark tokens via `theme/tokens`, the 13/12/11/10
  type scale, mono font for the data columns, and the `+`/`~`/`-` diff
  idiom for DB rows. The stage column + colour-coded left edge reuse the
  Epoch panel's `panels.epoch.badge` step taxonomy (rf2-aqusw).

  ## Epoch-scoped feed (spec/018 §6)

  Every L4 panel is a lens on the spine's FOCUSED EPOCH, not a global
  ribbon. The Trace tab reads the focused epoch record's `:trace-events`
  via `:rf.xray/trace-feed` — resolved through the shared
  `panels.shared.focus-resolver` against `:rf.xray/focus` +
  `:rf.xray/epoch-history`, exactly as the Issues / App-DB Diff panels
  do. No mock data is baked into the live panel; the arc is fed by real
  trace data throughout.

  ## Empty states

    :no-events     -> 'No events.' (focused epoch carries no trace events)
    :no-focus      -> 'Select an event to see its trace arc.' (spec/023 §14)
    :epoch-evicted -> the focused epoch aged out of the ring buffer.

  ## Pure hiccup

  Same contract as every other Xray panel — pure hiccup, no Reagent /
  UIx / Helix references. Frame isolation comes from the enclosing
  `[rf/frame-provider {:frame :rf/xray}]` in shell.cljs.

  ## Helpers

  All pure-data logic — row projection, area / stage / verb / target
  classification, the stage column + edge colour (via
  `panels.epoch.badge`), epoch-scoped feed, empty-state classification —
  lives in `trace_helpers.cljc` so the algebra runs under the JVM
  unit-test target. (The band-projection helpers are retained there for
  cross-panel consumers + tests; the flat panel no longer renders them.)"
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.app-db-diff-format :as f]
            [day8.re-frame2-xray.panels.cancellation-cascade-helpers
             :as cancellation-cascade-helpers]
            [day8.re-frame2-xray.panels.event.event-status-colour :as event-status]
            [day8.re-frame2-xray.panels.shared.coord-link :as coord-link]
            [day8.re-frame2-xray.panels.shared.focus-resolver :as focus]
            [day8.re-frame2-xray.spine :as spine]
            [day8.re-frame2-xray.panels.trace-helpers :as h]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack]]
            [day8.re-frame2-xray.views.edn-inspector :as ei]
            [day8.re-frame2-xray.views.edn-widget :as edn]
            [day8.re-frame2-xray.views.resizable-table :as rt]))

;; ---- resizable-table columns (rf2-jnxfj · rf2-aqusw) --------------------
;;
;; The op-row's 6-column grid — Δt · stage · area badge · what-happened ·
;; target/detail · duration — is driven by the shared `rt/resizable-table`
;; view. Δt + stage + badge + verb columns are fixed/compact; the
;; target/detail column is the flexible one and truncates first (spec/023
;; §14 — usable at the ≈420px docked width, no horizontal scroll);
;; duration right-aligns. The STAGE column (rf2-aqusw) names the Epoch
;; pipeline step each op belongs to — DISPATCH / COEFFECT / EVENT HANDLER /
;; FLOW / EFFECT HANDLERS / SUBSCRIPTIONS / VIEWS — recovering flatly the
;; phase information the (now-removed) hierarchy conveyed.
;;
;; One `:table-id :rf.xray.trace/ops` is shared between the single header
;; bar at the top of the panel and the flat row list, so a drag in the
;; header live-resizes every row. Column widths persist via the rf2-xzg1y
;; localStorage round-trip. The `minmax(0, 1fr)` on the target column
;; survives the resolver because `build-template` passes the
;; `:default-flex` string through verbatim when no px override is in the
;; slot.

(def ^:private trace-op-columns
  [{:id :time     :label "Δt"       :default-flex "56px"}
   {:id :stage    :label "stage"    :default-flex "104px"}
   {:id :badge    :label "area"     :default-flex "64px"}
   {:id :verb     :label "what"     :default-flex "92px"}
   {:id :target   :label "target"   :default-flex "minmax(0, 1fr)"}
   {:id :duration :label "duration" :default-flex "70px"}])

(def trace-ops-table-id
  "Shared table-id the header + the flat row list read against so a drag
  updates one slot that both read."
  :rf.xray.trace/ops)

(def ^:private trace-header-cell-style
  {:padding        "4px 8px"
   :color          (:text-tertiary tokens)
   :font-family    sans-stack
   :font-size      "10px"
   :font-weight    600
   :text-transform "uppercase"
   :letter-spacing "0.4px"
   :min-width      0})

(def ^:private trace-header-attrs
  {:data-testid "rf-xray-trace-ops-header"
   :style       {:background    (:bg-2 tokens)
                 :border-bottom (str "1px solid " (:border-subtle tokens))
                 :margin-bottom "4px"}})

;; ---- style primitives (rf2-5venq) ---------------------------------------
;;
;; All inline `:style {...}` maps in the row + band + envelope renderers
;; below are hoisted to ns-level defs (rf2-qx414 / rf2-xjgdk / rf2-gjiog
;; / rf2-alsnz pattern). The Trace panel arc routinely renders ~200 op
;; rows × ~9 style cells per row — without hoisting each Panel re-render
;; allocated ~1800 fresh JS objects to feed the React reconciler (audit
;; F1 of rf2-qa75r).
;;
;; Stable shapes live as plain maps; per-row variation (severity colour,
;; op-family border, outcome verb tint, expansion-state background) is
;; layered in via `cond->`-merged overlays at call sites. Tokens resolve
;; to `var(--rf-xray-*)` strings at ns load — the active theme class
;; (light / dark) picks the hex at paint time, so resolution-once is
;; correct across themes (spec/007 §CSS custom-property).

;; ---- render-payload (spec/023 §3) ---------------------------------------

(def ^:private payload-container-style
  {:padding       "6px 16px 10px 56px"
   :background    (:bg-1 tokens)
   :border-radius "0 0 4px 4px"})

;; ---- db-diff-row (spec/023 §APP-DB CHANGES) -----------------------------

(def ^:private db-diff-row-container-style
  {:display     "flex"
   :align-items "baseline"
   :flex-wrap   "wrap"
   :gap         "8px"
   :padding     "1px 16px 1px 56px"
   :font-family mono-stack
   :font-size   "11px"})

(def ^:private db-diff-glyph-base-style
  {:flex        "0 0 12px"
   :font-weight 700
   :text-align  "center"
   :user-select "none"})

(def ^:private db-diff-modified-cell-style
  {:display     "inline-flex"
   :align-items "baseline"
   :flex-wrap   "wrap"
   :gap         "6px"
   :min-width   0})

(def ^:private db-diff-modified-before-style
  {:color           (:text-tertiary tokens)
   :text-decoration "line-through"})

(def ^:private db-diff-modified-arrow-style
  {:color (:text-tertiary tokens)})

(def ^:private db-diff-modified-after-style
  {:color (:text-primary tokens)})

(def ^:private db-diff-added-value-style
  {:color (:text-primary tokens) :min-width 0})

(def ^:private db-diff-rows-container-style
  {:padding "2px 0 6px 0"})

;; ---- op-row (the hot path · spec/023 §3) --------------------------------

(def ^:private op-row-container-base-style
  "Shared shape for an op row's outer `:li`. The op-family / severity
  left-border + the severity / expansion background are layered in via
  a `cond->` overlay at the call site (per-row variation)."
  {:display       "block"
   :border-radius "4px"
   :margin-bottom "1px"
   :cursor        "pointer"
   :color         (:text-primary tokens)
   :font-family   mono-stack
   :font-size     "12px"
   :line-height   1.35})

(def ^:private op-row-time-base-style
  {:font-size   "11px"
   :white-space "nowrap"
   :text-align  "right"})

;; Default (non-severity) Δt style pre-merged so the common case pays
;; no per-row allocation; severity rows compose `sev-colour` + bold via
;; `assoc` over the base.
(def ^:private op-row-time-default-style
  (assoc op-row-time-base-style :color (:text-tertiary tokens)))

;; ---- stage column (rf2-aqusw) -------------------------------------------
;;
;; The flat list's STAGE column names the Epoch pipeline step (DISPATCH /
;; COEFFECT / HANDLER / FLOW / SIDE EFFECTS / SUBSCRIPTIONS / VIEWS). The
;; label rides the step's own colour (from `panels.epoch.badge`, the same
;; hue the colour-coded left edge paints) so the column reads as a tinted
;; pill-less label that ties to the edge at a glance.

(def ^:private op-row-stage-base-style
  {:font-size      "10px"
   :font-weight    600
   :letter-spacing "0.4px"
   :white-space    "nowrap"
   :overflow       "hidden"
   :text-overflow  "ellipsis"})

(def ^:private op-row-badge-base-style
  {:font-size      "10px"
   :font-weight    600
   :letter-spacing "0.4px"
   :white-space    "nowrap"})

;; The default (non-severity) badge merges base + the tertiary colour
;; ahead of time so non-severity rows pay zero allocation for the badge
;; style (the common case in a normal arc — only error / warning rows
;; need to compose `sev-colour` per render).
(def ^:private op-row-badge-default-style
  (assoc op-row-badge-base-style :color (:text-tertiary tokens)))

(def ^:private op-row-verb-base-style
  {:white-space   "nowrap"
   :overflow      "hidden"
   :text-overflow "ellipsis"})

(def ^:private op-row-target-container-style
  {:display     "flex"
   :align-items "baseline"
   :gap         "8px"
   :min-width   0})

(def ^:private op-row-target-text-style
  {:color         (:text-secondary tokens)
   :overflow      "hidden"
   :text-overflow "ellipsis"
   :white-space   "nowrap"
   :min-width     0})

(def ^:private op-row-source-coord-button-style
  {:flex-shrink 0
   :background  "transparent"
   :color       (:accent tokens)
   :border      "none"
   :padding     0
   :cursor      "pointer"
   :font-family mono-stack
   :font-size   "11px"})

(def ^:private op-row-cancellation-button-style
  {:flex-shrink 0
   :background  "transparent"
   :color       (or (:red tokens) (:text-secondary tokens))
   :border      "none"
   :padding     0
   :cursor      "pointer"
   :font-family mono-stack
   :font-size   "11px"})

(def ^:private op-row-duration-style
  {:color       (:text-tertiary tokens)
   :font-size   "11px"
   :white-space "nowrap"
   :text-align  "right"})

;; Severity row tints — `color-mix` over the semantic CSS-var keeps
;; light/dark theme correct (the var resolves at paint time).
(def ^:private op-row-bg-error
  "color-mix(in srgb, var(--rf-xray-red) 9%, transparent)")

(def ^:private op-row-bg-warning
  "color-mix(in srgb, var(--rf-xray-yellow) 9%, transparent)")

;; ---- flat row list (rf2-aqusw) ------------------------------------------
;;
;; The flat list (rf2-aqusw) renders every row in one container — no band
;; rails, no envelope chrome. The list reads top-down oldest-first; the
;; per-row STAGE column + colour-coded left edge recover the phase shape
;; the removed bands carried.

(def ^:private flat-rows-container-style
  {:list-style "none" :margin 0 :padding 0})

;; ---- empty-state-message (spec/023 §14) ---------------------------------

(def ^:private empty-state-container-style
  {:padding     "24px"
   :font-family sans-stack
   :font-size   "13px"
   :line-height 1.5
   :color       (:text-secondary tokens)})

(def ^:private empty-state-copy-style
  {:margin 0 :color (:text-tertiary tokens)})

;; ---- cascade-status-bar -------------------------------------------------

(def ^:private cascade-status-bar-base-style
  {:height      "3px"
   :flex-shrink 0})

;; ---- Panel root ---------------------------------------------------------

(def ^:private panel-root-style
  {:height         "100%"
   :display        "flex"
   :flex-direction "column"
   :background     (:bg-2 tokens)
   :color          (:text-primary tokens)
   :font-family    sans-stack
   :font-size      "14px"})

(def ^:private panel-scroll-container-style
  {:flex 1 :overflow "auto"})

(def ^:private panel-feed-container-style
  {:padding "8px 8px 16px 8px"})

;; ---- payload renderer (spec/023 §3) -------------------------------------
;;
;; Row click → expand the full raw trace-event EDN inline via the
;; first-class edn-inspector widget (spec/021 §10 widget contract /
;; spec/023 §3 row click → expand). The widget owns its own per-mount
;; expansion state keyed by `[panel-id mount-id path]`, so two rows
;; expanded simultaneously each keep an independent expansion tree.
;;
;; Per rf2-hhtbl (rf2-oqa60 phase 2) this call site invokes
;; `[ei/edn-inspector value opts]` directly — no facade hop through
;; `edn/browse`. The per-row `panel-id` qualifier embeds the row id so
;; two simultaneously-expanded rows can't collide on expansion state
;; even if their mount-ids alias (and the auto-id guarantees they
;; won't — this is belt-and-braces).

(defn- render-payload
  "Per-row payload renderer — the raw `:operation` · `:tags` · timing ·
  `:rf.trace/dispatch-id` trace-event EDN (spec/023 §3), rendered via
  the first-class edn-inspector widget."
  [{:keys [id raw] :as _row}]
  [:div {:data-testid (str "rf-xray-trace-row-" id "-payload")
         :style       payload-container-style}
   [ei/edn-inspector raw
    {:panel-id (keyword "rf.xray.trace" (str "row-" id))
     ;; rf2-pvsxs — trace rows survive tab leave-and-return because
     ;; the trace event-id is itself stable; the `:site-id` reuses it
     ;; so the operator's expansion choices persist across tab churn.
     :site-id  [:rf.xray.trace/row id]
     :default-expanded-depth 1
     ;; rf2-l4625 — trace rows expand within the row's narrow column;
     ;; tags + payload maps are routinely cramped. Popup gives the
     ;; operator a full-modal inspection surface.
     :popup-affordance? true}]])

;; ---- per-path db-changed diff rows (spec/023 §APP-DB CHANGES) -----------
;;
;; The trace event `:rf.event/db-changed` carries only `:event` + `:frame`
;; — no per-path diff payload (Mike-decided rf2-8q8i4 = (b), 2026-05-25:
;; PANEL-SIDE derive). Per-path before→after rows are derived at render
;; time from the focused epoch record's `:db-before` / `:db-after` slots
;; by `trace_helpers/db-changed-diff-triples` (which routes through
;; `app-db-diff-helpers/diff-paths` — the same structural-sharing engine
;; the App-DB Diff tab + the Event-panel APP-DB CHANGES section consume,
;; spec/004 §Changed-paths derivation). The feed projection attaches the
;; triples to every `:rf.event/db-changed` row's `:db-diff` slot so the
;; view stays dumb-and-pure.
;;
;; The row idiom (spec/021 §2.2 step 6 mockup):
;;
;;     + [:path] new            (added — green)
;;     ~ [:path] old → new      (modified — amber)
;;     - [:path]                (removed — red — path alone)
;;
;; The diff helper itself (`diff-paths`) is extracted to
;; `app_db_diff_helpers.cljc` (shared); the render-side helpers below
;; are this trace arc's changed-path renderer and are the SOLE copy of
;; this table-style idiom. The Epoch panel renders changed paths in a
;; deliberately distinct shape — single-line inline chrome
;; (`epoch/view.cljs`), not a table — so there is no cross-panel
;; duplication to dedupe.

(def ^:private diff-op->glyph
  "Cascade diff glyph per op (spec/021 §10.3)."
  {:added    "+"
   :modified "~"
   :removed  "-"})

(def ^:private diff-op->tone
  "Glyph + path colour per op (spec/021 §10.3)."
  {:added    :green
   :modified :yellow
   :removed  :red})

(defn- path-suffix
  "Stable testid suffix for a changed path — pr-str of the path vector
  with characters that break a data-testid selector folded to `_`."
  [path]
  (-> (str/join " " (map pr-str path))
      (str/replace #"\s+" "_")))

(defn- db-diff-row
  "Render one changed-path row beneath a `:rf.event/db-changed` op row.
  `triple` is one `app-db-diff-helpers/diff-paths` triple
  (`{:op :path :before :after}`). Pure hiccup; no nav (the parent row's
  click toggles the raw trace-event EDN, this sub-row is informational).
  Shape per spec/021 §2.2 step 6 mockup:

      ~ [:counter]  1 → 2
      + [:last-updated]  #inst \"…\"
      - [:stale]

  Indented under the parent op row so the diff reads as a sub-list of
  the db-changed event."
  [parent-row-id {:keys [op path before after] :as _triple}]
  (let [suffix     (path-suffix path)
        glyph      (get diff-op->glyph op "?")
        tone       (get tokens (get diff-op->tone op) (:text-secondary tokens))
        path-label (f/format-edn (vec path))
        row-test-id (str "rf-xray-trace-row-" parent-row-id
                         "-db-diff-row-" suffix)]
    [:div {:data-testid row-test-id
           :data-op     (name op)
           :on-click    (fn [e] (.stopPropagation e))
           :style       db-diff-row-container-style}
     [:span {:data-testid (str row-test-id "-glyph")
             :style       (assoc db-diff-glyph-base-style :color tone)}
      glyph]
     [:span {:data-testid (str row-test-id "-path")
             :style {:color tone}}
      path-label]
     (case op
       :modified
       [:span {:style db-diff-modified-cell-style}
        [:span {:style db-diff-modified-before-style}
         (edn/inspect-inline before)]
        [:span {:style db-diff-modified-arrow-style} "→"]
        [:span {:style db-diff-modified-after-style}
         (edn/inspect-inline after)]]

       :added
       [:span {:style db-diff-added-value-style}
        (edn/inspect-inline after)]

       ;; :removed — path alone (spec/021 line 229).
       nil)]))

(defn- db-diff-rows
  "Render the per-path diff list under a `:rf.event/db-changed` row. When
  the diff is empty (`db-before == db-after`) renders no list — the spec
  treats the empty diff section as the no-changes case (spec/023
  §APP-DB CHANGES). The container carries the testid
  `rf-xray-trace-row-<id>-db-diff` so tests can target the section
  regardless of contents."
  [parent-row-id triples]
  (when (seq triples)
    (into [:div {:data-testid (str "rf-xray-trace-row-" parent-row-id "-db-diff")
                 :style       db-diff-rows-container-style}]
          (for [{:keys [path] :as triple} triples]
            (with-meta (db-diff-row parent-row-id triple)
                       {:key (pr-str path)})))))

;; ---- one op row (spec/023 §3) -------------------------------------------
;;
;; rf2-jnxfj — the op row's 5-column grid is now driven by the shared
;; `rt/resizable-table` view (one `:table-id :rf.xray.trace/ops` per
;; arc, shared by header + every band so a drag re-aligns every row).
;; The pre-conversion `op-row` returned a single `:li` containing the
;; grid + db-diff + payload; resizable-table now owns the `:div` row
;; wrapper, and the per-row attrs / cells / extras are produced by the
;; three helpers below. testids land verbatim on the new structure so
;; the existing trace_view_cljs_test corpus still resolves them.

(defn- op-row-attrs
  "Per-row attrs for the resizable-table — preserves the click /
  context-menu handlers + the colour-coded STAGE left edge (rf2-aqusw)
  + severity / expansion backgrounds.

  Stamps `:key` into the attrs map alongside the meta-key
  resizable-table emits, so the rf2-l2f2g React-key contract surface
  (rf-xray-trace-row-N row vectors stably keyed by `(h/row-key row)`)
  is observable in the rendered hiccup — the test corpus reads
  `:key` from the row attrs map because the framework hiccup walker
  rebuilds inner vectors via `mapv` and drops their metadata."
  [{:keys [id operation area stage stage-colour dispatch-id] :as row} expanded?]
  ;; rf2-nesy9 — capture the surrounding instance frame at render time
  ;; so the deferred row handlers dispatch into it, not a `:rf/xray`
  ;; literal. op-row-attrs is invoked during the Trace Panel reg-view's
  ;; render, so `current-frame-id` resolves through the React-context tier.
  (let [frame       (rf/current-frame-id)
        row-test-id (str "rf-xray-trace-row-" id)
        severity?   (#{:error :warning} area)
        sev-colour  (when severity?
                      (get tokens (if (= area :error) :red :yellow)))
        destroy?    (cancellation-cascade-helpers/destroy-event? {:operation operation})]
    {:key                   (h/row-key row)
     :data-testid           row-test-id
     :data-rf-xray-expanded (boolean expanded?)
     :data-rf-xray-area     (some-> area name)
     ;; rf2-aqusw — the Epoch pipeline STAGE drives the column + edge.
     :data-rf-xray-stage    (some-> stage name)
     :data-rf-xray-severity (when severity? (name area))
     :on-click              (fn []
                              (rf/dispatch [:rf.xray/toggle-trace-row-expand id]
                                           {:frame frame}))
     :on-context-menu       (when destroy?
                              (fn [e]
                                (.preventDefault e)
                                (.stopPropagation e)
                                (rf/dispatch
                                  [:rf.xray/cancellation-cascade-open
                                   {:kind :dispatch-id :id dispatch-id}]
                                  {:frame frame})))
     ;; rf2-aqusw — colour-coded STAGE left edge: a 3px LEFT-BORDER in
     ;; the Epoch pipeline step's colour (reused via `panels.epoch.badge`
     ;; through `h/stage-colour`). Error / warning override the stage
     ;; colour so a failure stands out (spec/023 §7). Severity rows carry
     ;; a faint tinted fill (spec/023 §7); expanded rows show a bg-1
     ;; backdrop.
     :style                 (cond-> (assoc op-row-container-base-style
                                           :border-left
                                           (str "3px solid "
                                                (or sev-colour stage-colour)))
                              expanded?
                              (assoc :background (:bg-1 tokens))
                              (and (not expanded?) (= area :error))
                              (assoc :background op-row-bg-error)
                              (and (not expanded?) (= area :warning))
                              (assoc :background op-row-bg-warning))}))

(defn- op-row-cells
  "Per-row cells — the 6 hiccup nodes resizable-table interleaves into
  the grid template (rf2-aqusw — Δt · stage · badge · verb · target ·
  duration). Each carries `data-rf-xray-resizable-col` so the
  pointer-down handler can locate the adjacent cell off the live DOM,
  AND keeps the original `data-testid` so the trace_view tests resolve
  unchanged."
  [{:keys [id operation rel-time time area area-badge stage-label
           stage-colour verb target duration-ms source-coord dispatch-id]
    :as row}]
  ;; rf2-nesy9 — render-time frame capture for the deferred cell handlers.
  (let [frame       (rf/current-frame-id)
        row-test-id (str "rf-xray-trace-row-" id)
        verb-colour (h/outcome-colour row)
        severity?   (#{:error :warning} area)
        sev-colour  (when severity?
                      (get tokens (if (= area :error) :red :yellow)))
        destroy?    (cancellation-cascade-helpers/destroy-event? {:operation operation})]
    [;; ① Δt — ms offset from the epoch origin; `!` lead for severity rows.
     [:span {:data-rf-xray-resizable-col "time"
             :data-testid (str row-test-id "-time")
             :title       (or (h/format-time time) "")
             :style       (if severity?
                            (assoc op-row-time-base-style
                                   :color sev-colour
                                   :font-weight 700)
                            op-row-time-default-style)}
      (cond
        (and severity? rel-time) (str "!" (subs rel-time 1))
        rel-time                 rel-time
        :else                    "—")]
     ;; ② stage — the Epoch pipeline step (DISPATCH / COEFFECT / HANDLER /
     ;; FLOW / SIDE EFFECTS / SUBSCRIPTIONS / VIEWS) the op belongs to,
     ;; tinted with the step's own colour (rf2-aqusw — same hue the
     ;; colour-coded left edge paints, both via `panels.epoch.badge`).
     [:span {:data-rf-xray-resizable-col "stage"
             :data-testid (str row-test-id "-stage")
             :title       stage-label
             :style       (assoc op-row-stage-base-style :color stage-colour)}
      stage-label]
     ;; ③ area badge — neutral uppercase text badge (spec/023 §3); the
     ;; severity tiers ride their semantic colour so a failure's family
     ;; is unmistakeable (spec/023 §7 / §8).
     [:span {:data-rf-xray-resizable-col "badge"
             :data-testid (str row-test-id "-badge")
             :style       (if sev-colour
                            (assoc op-row-badge-base-style :color sev-colour)
                            op-row-badge-default-style)}
      area-badge]
     ;; ④ what-happened — the per-area verb, tinted by outcome tier.
     [:span {:data-rf-xray-resizable-col "verb"
             :data-testid (str row-test-id "-verb")
             :style       (assoc op-row-verb-base-style
                                 :color (if severity? sev-colour verb-colour))
             :title       verb}
      verb]
     ;; ⑤ target / detail — the op's subject; the flexible column that
     ;; truncates first (spec/023 §14). Source-coord ↗ rides at its end.
     [:span {:data-rf-xray-resizable-col "target"
             :data-testid (str row-test-id "-target")
             :style       op-row-target-container-style}
      [:span {:style op-row-target-text-style
              :title (or target "")}
       (or target "—")]
      (when source-coord
        ;; rf2-vw5pi — open-in-editor dispatch via the shared
        ;; `coord-link/open-in-editor!`. The `↗` text-glyph cell chrome
        ;; stays local (column layout, not the bespoke dispatch).
        [:button {:data-testid (str row-test-id "-source-coord")
                  :title       source-coord
                  :on-click    (fn [e]
                                 ;; rf2-nesy9 — route the open-in-editor
                                 ;; dispatch through the captured instance
                                 ;; frame, not the singleton default.
                                 (coord-link/open-in-editor!
                                   source-coord e
                                   #(rf/dispatch % {:frame frame})))
                  :style       op-row-source-coord-button-style}
         "↗"])
      (when destroy?
        [:button {:data-testid (str row-test-id "-cancellation-cascade")
                  :title       "Show cancellation cascade"
                  :on-click    (fn [e]
                                 (.stopPropagation e)
                                 (rf/dispatch
                                   [:rf.xray/cancellation-cascade-open
                                    {:kind :dispatch-id :id dispatch-id}]
                                   {:frame frame}))
                  :style       op-row-cancellation-button-style}
         "⟲"])]
     ;; ⑥ duration — `N.N ms` when timed, em-dash otherwise (spec/023 §6).
     [:span {:data-rf-xray-resizable-col "duration"
             :data-testid (str row-test-id "-duration")
             :style       op-row-duration-style}
      (or (h/format-duration duration-ms) "—")]]))

(defn- op-row-extras
  "Per-row extras rendered BELOW the grid (the resizable-table's
  `:row-extras` slot — see ns docstring of `views.resizable-table`).
  Returns the per-path db-diff sub-list (rf2-b3zw2) when the row is a
  `:rf.event/db-changed` op AND/OR the raw-EDN payload (spec/023 §3)
  when the row is expanded."
  [{:keys [id operation db-diff] :as row} expanded?]
  (let [diff?    (= operation :rf.event/db-changed)
        diff-h   (when diff? (db-diff-rows id db-diff))
        payload  (when expanded? (render-payload row))]
    (cond
      (and diff-h payload) [:<> diff-h payload]
      diff-h               diff-h
      payload              payload
      :else                nil)))

;; ---- flat row list (rf2-aqusw) ------------------------------------------
;;
;; The 4-band hierarchy + EPOCH OPEN / CLOSE envelope are GONE (rf2-aqusw).
;; Every op the focused epoch emitted — including the `:rf.epoch/*`
;; lifecycle ops (snapshotted / outcome / restored / …) that used to live
;; in the envelope — renders as an ORDINARY row in one flat list, in fire
;; order (oldest-first), exactly as the feed's `:rows` are ordered. The
;; epoch-lifecycle ops classify to the DISPATCH stage (their muted grey
;; edge), so the open/close lifecycle still surfaces, flatly. The phase
;; information the bands conveyed is recovered by each row's STAGE column
;; + colour-coded left edge.

(defn- flat-row-list
  "Render the focused epoch's whole trace as a SINGLE flat list of op
  rows (rf2-aqusw). Rows mount through `rt/resizable-table` with
  `:header? false` (the header lives once at the top of the panel) so
  the list shares the `:rf.xray.trace/ops` column-widths slot — a drag
  on the panel header re-aligns every row live."
  [rows expanded-row-ids]
  [rt/resizable-table
   {:table-id        trace-ops-table-id
    :header?         false
    :columns         trace-op-columns
    :container-attrs {:data-testid "rf-xray-trace-rows"
                      :style       flat-rows-container-style}
    :rows            rows
    :row-key         (fn [row _i] (h/row-key row))
    :row-attrs       (fn [row _i]
                       (op-row-attrs row
                                     (contains? (or expanded-row-ids #{})
                                                (:id row))))
    :row-cells       (fn [row _i] (op-row-cells row))
    :row-extras      (fn [row _i]
                       (op-row-extras row
                                      (contains? (or expanded-row-ids #{})
                                                 (:id row))))}])

;; ---- empty states (spec/023 §14) ----------------------------------------

(defn- empty-state-message
  "Shared terse empty-state block. `kind` drives the data-testid + copy."
  [kind copy]
  [:div {:data-testid (str "rf-xray-trace-empty-" (name kind))
         :style       empty-state-container-style}
   [:p {:style empty-state-copy-style}
    copy]])

(defn- empty-state-no-events []
  (empty-state-message :no-events "No events."))

(defn- empty-state-no-focus []
  ;; spec/023 §14 — "Select an event to see its trace arc."
  (empty-state-message :no-focus "Select an event to see its trace arc."))

(defn- empty-state-epoch-evicted []
  (empty-state-message
    :epoch-evicted
    "This epoch has been evicted from the history buffer."))

;; ---- cascade status timeline bar ----------------------------------------

(defn- cascade-status-bar
  "A 3px bar above the arc filling with the focused cascade's lifecycle-
  status colour — driven by `event-status/event-status-colour`, the same
  fn the L2 list rows + the Event L4 header dot consume, so the whole
  devtool speaks ONE lifecycle vocabulary."
  [{:keys [cascade focus]}]
  (let [status-state (event-status/cascade->state cascade focus)
        status-kw    (event-status/classify-status status-state)
        status-hex   (event-status/event-status-colour status-state)]
    [:div {:data-testid (str "rf-xray-trace-cascade-status-bar-"
                             (name status-kw))
           :data-rf-xray-status (name status-kw)
           :title (case status-kw
                    :in-flight       "Focused cascade — in-flight"
                    :settled-success "Focused cascade — settled (success)"
                    :settled-error   "Focused cascade — settled (error)"
                    :paused-by-tool  "Focused cascade — paused by tool"
                    :stale           "Focused cascade — stale (replayed / RETRO)"
                    (str "Focused cascade — " (name status-kw)))
           :style (assoc cascade-status-bar-base-style :background status-hex)}]))

;; ---- public view --------------------------------------------------------

(rf/reg-view Panel
  "The Trace panel's root view — the focused epoch's whole trace as a
  FLAT list (spec/023-Trace-Panel.md · rf2-aqusw). Subscribes to
  `:rf.xray/trace-feed` (the epoch-scoped feed) and renders the focused
  epoch's `:rows` as a single oldest-first list of op rows: each row
  carries a STAGE column + colour-coded left edge (the Epoch pipeline
  step, via `panels.epoch.badge`). Clicking a row opens the edn-inspector
  on its raw trace MAP inline. No bands, no envelope, no hierarchy. No
  mock data — fed by real trace data throughout."
  []
  (let [{:keys [rows empty-kind] :as _data}
        @(rf/subscribe [:rf.xray/trace-feed])
        ;; rf2-wcfsy — focused-cascade is a layer-3 composite over
        ;; `:rf.xray/cascades` + `:rf.xray/focus`, NOT an inline scan in
        ;; the render body. The composite memoises on its two input
        ;; signals so the scan only re-runs when cascades / focus
        ;; actually change (rather than every Panel render).
        focus           @(rf/subscribe [:rf.xray/focus])
        focused-cascade @(rf/subscribe [:rf.xray.trace/focused-cascade])
        expanded-ids    @(rf/subscribe [:rf.xray/trace-expanded-row-ids])]
    [:section {:data-testid "rf-xray-trace"
               :style       panel-root-style}
     (when focused-cascade
       (cascade-status-bar {:cascade focused-cascade :focus focus}))
     [:div {:style panel-scroll-container-style}
      (case empty-kind
        :no-events     (empty-state-no-events)
        :no-focus      (empty-state-no-focus)
        :epoch-evicted (empty-state-epoch-evicted)
        nil
        ;; The feed container keeps the `rf-xray-trace-feed` testid as the
        ;; external "the rows rendered" contract surface. Two children,
        ;; each explicitly keyed: the column-widths header bar + the flat
        ;; row list.
        [:div {:data-testid "rf-xray-trace-feed"
               :style panel-feed-container-style}
         ;; rf2-jnxfj — column-widths drag-handle bar. One header bar at
         ;; the top carries the gutter handles for the shared
         ;; `:rf.xray.trace/ops` table-id; the flat row list renders its
         ;; own resizable-table with `:header? false` reading the SAME
         ;; slot, so a drag here re-aligns every row.
         ^{:key "ops-header"}
         [rt/resizable-table
          {:table-id        trace-ops-table-id
           :columns         trace-op-columns
           :rows            []
           :row-key         (fn [_ _] "")
           :row-cells       (fn [_ _] [])
           :header-attrs    trace-header-attrs
           :header-cell-style trace-header-cell-style}]
         ;; rf2-aqusw — the flat list of every op the focused epoch
         ;; emitted, in fire order (oldest-first). No bands, no envelope.
         ^{:key "rows"}
         (flat-row-list rows expanded-ids)])]]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Trace panel's Xray-side registrations
  (spec/023-Trace-Panel.md)."
  []
  ;; ---- epoch-scoped feed (spec/018 §6) -------------------------------
  ;;
  ;; The Trace tab reads the FOCUSED EPOCH's `:trace-events` — the
  ;; per-frame settling epoch record's raw trace slice, which folds the
  ;; complete domino trail for one event (the synchronous event-side
  ;; dispatch-id-N rows AND the async nil-dispatch-id reactive rows).
  ;; The focused epoch record is resolved exactly as the Issues / App-DB
  ;; Diff panels resolve theirs: join `:rf.xray/focus` (carrying
  ;; `:epoch-id`) + `:rf.xray/epoch-history`, then run the shared
  ;; `panels.shared.focus-resolver` — which classifies the focus status
  ;; (`:no-focus` / `:focused` / `:epoch-evicted`) and looks up the
  ;; record. `h/project-feed-from-epoch` projects that record's
  ;; `:trace-events` into the feed shape. The flat panel (rf2-aqusw)
  ;; reads only `:rows` + `:empty-kind`; the `:envelope` / `:bands` /
  ;; `:outcome` slots are RETAINED for cross-panel consumers + the
  ;; band-projection helper tests, but the view no longer renders them.
  ;;
  ;; Shape of `:rf.xray/trace-feed`:
  ;;
  ;;     {:rows       [<row> ...]   ;; the epoch's domino trail, oldest-first
  ;;                                ;; (the flat list the panel renders)
  ;;      :envelope   [<row> ...]   ;; the :rf.epoch/* ops (retained — not rendered)
  ;;      :outcome    <:ok/:blocked/:error-or-nil>  ;; (retained — not rendered)
  ;;      :bands      [{:id :label :rows :count :empty?} ...]  ;; (retained — not rendered)
  ;;      :total      <int>         ;; the epoch's trace-event count
  ;;      :rendered   <int>         ;; same as :total (no filtering)
  ;;      :epoch-id   <int-or-nil>  ;; the focused epoch's id
  ;;      :empty-kind <:no-events / :no-focus / :epoch-evicted / nil>}
  (rf/reg-sub :rf.xray/trace-feed
    :<- [:rf.xray/focus]
    :<- [:rf.xray/epoch-history]
    (fn [[focus epoch-history] _query]
      (let [focus-epoch-id (:epoch-id focus)
            focus-status   (focus/resolve-focus-status focus-epoch-id
                                                       epoch-history)
            record         (focus/find-epoch-record focus-epoch-id
                                                    epoch-history)]
        (h/project-feed-from-epoch record focus-status))))

  ;; ---- focused cascade (rf2-wcfsy) -----------------------------------
  ;;
  ;; Layer-3 composite over `:rf.xray/cascades` + `:rf.xray/focus`. The
  ;; Trace panel reads this directly instead of scanning the cascades
  ;; vector in its render body (the previous shape: a linear
  ;; `(some #(when (= focused-id (:dispatch-id %)) %) cascades)` that
  ;; ran on every Panel render — O(N) over the cascades vector, defeats
  ;; memoisation because the result was reconstructed per render).
  ;;
  ;; As a layer-3 composite the scan only re-runs when its input
  ;; signals (cascades or focus) actually change. The result is the
  ;; same record `(some #(= focused-id (:dispatch-id %)) cascades)`
  ;; would return — so the downstream `cascade-status-bar` call site
  ;; continues to work verbatim.
  ;;
  ;; Why both input subs are still subscribed in the Panel: the Panel
  ;; passes `focus` itself (not just the focused-cascade) into
  ;; `cascade-status-bar`, so it still needs the focus signal. The
  ;; cascades sub feeds many other panels (L2 list, Issues ribbon, …);
  ;; reg-sub de-dupes the underlying signal so subscribing here costs
  ;; nothing extra.
  (rf/reg-sub :rf.xray.trace/focused-cascade
    :<- [:rf.xray/cascades]
    :<- [:rf.xray/focus]
    (fn [[cascades focus] _query]
      ;; rf2-bz7flo — resolve frame-strictly. Dispatch ids are unique only
      ;; within a frame, so a same-id cascade from a foreign frame could be
      ;; returned here when focus is on another frame. `cascade-by-focus`
      ;; keys by both `:frame` + `:dispatch-id` when focus carries a frame.
      (spine/cascade-by-focus cascades focus)))

  ;; ---- per-row inline payload expansion (spec/023 §3) ----------------
  ;;
  ;; Clicking a row expands its raw trace-event EDN inline (no nav). The
  ;; expanded set lives in app-db so the toggle survives sub-recomputes.

  (rf/reg-sub :rf.xray/trace-expanded-row-ids
    (fn [db _query]
      (get db :trace-expanded-row-ids #{})))

  (rf/reg-event :rf.xray/toggle-trace-row-expand
    (fn [{:keys [db]} [_ row-id]]
      {:db (let [current (get db :trace-expanded-row-ids #{})]
        (assoc db :trace-expanded-row-ids
               (if (contains? current row-id)
                 (disj current row-id)
                 (conj current row-id))))}))

  ;; rf2-aqusw — the flat list lost the collapsible phase bands; the
  ;; expand set is the only per-row UI state left to clear.
  (rf/reg-event :rf.xray/clear-trace-expand
    (fn [{:keys [db]} _event]
      {:db (dissoc db :trace-expanded-row-ids)}))

  ;; rf2-2moh1 — register the Dynamic Trace tab with the internal L4
  ;; tab registry. The tab keeps its `t` mnemonic + order-3 placement.
  (panel-registry/reg-l4-tab!
    {:id    :trace
     :label "Trace"
     :mnem  "t"
     :modes #{:dynamic}
     :order 3
     :panel Panel}))
