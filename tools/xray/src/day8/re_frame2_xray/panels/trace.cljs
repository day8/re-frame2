(ns day8.re-frame2-xray.panels.trace
  "Trace panel — Phase 5 (rf2-argrj, parent rf2-5aw5v);
  epoch-scoped rework (rf2-o6yqq + rf2-td380 + rf2-gkczt).

  Per `tools/xray/spec/000-Vision.md` L89 (panel-inventory row) the
  Trace panel is the raw-event ribbon — every trace event in the
  FOCUSED EPOCH renders as one timestamped row.

  ## What this panel shows

  A scrollable, relative-timestamped ribbon scoped to the spine's
  focused epoch. Per the Figma design (rf2-ad7zx.8 — spec/021 §5.2 +
  `design-reference/xray_devtools_reference.cljs`, the `trace-panel`
  component) each row carries:

      relative-time · readable plain-language description · duration

  with the op-FAMILY colour rendered as a **3px left-border band**
  (dispatch = mode accent · db = changed · fx = warning · reactive =
  dim · machine = chart tone) — NOT an op-type dot. Child dispatches
  **indent** under their parent cascade (causal nesting), and the
  post-cascade reactive aftermath **collapses** under one expandable
  `▸ reactive aftermath (N subs, M renders)` group so the core
  dominoes stand out.

  Clicking a row toggles its inline payload expansion (no nav);
  clicking the reactive group toggles its children.

  ## Epoch-scoped feed (rf2-td380)

  Per spec/018 §6 every L4 panel is a lens on the spine's focused
  event, not a global ribbon. The Trace tab reads the FOCUSED EPOCH's
  `:trace-events` — the per-frame settling epoch record's raw trace
  slice — which folds the COMPLETE domino trail for one event: both
  the synchronous event-side rows (dispatch-id N) AND the async
  reactive rows (`:rf.sub/run` / `:rf.view/render`, nil dispatch-id) that
  fire post-cascade for that settling.

  The prior shape scoped the global trace bus by `:dispatch-id`,
  which DROPPED those async reactive rows (their dispatch-id is nil),
  so the rendered trail was incomplete (e.g. 4 rows shown vs the
  epoch's real 12). Reading the epoch record's `:trace-events` —
  resolved via the shared `panels.shared.focus-resolver` against
  `:rf.xray/focus` + `:rf.xray/epoch-history`, exactly as the
  Issues / App-DB Diff panels do — renders the whole trail: event →
  db-changed → subs ran → views rendered.

  Per-event epochs (merged) mean the focused epoch's `:trace-events`
  is the right scope: one epoch per event, each carrying its own
  reactive settling.

  ## No filtering (rf2-gkczt)

  The Trace panel surfaces the epoch-scoped rows with NO filtering
  UI — the focused epoch IS the scope, and the per-row payload-expand
  affordance is the drill-down. The 13-axis chip-filter rows, the
  per-row chip affordances, and the clear-filters control are gone.

  ## No header row (rf2-o6yqq)

  The duplicate film-strip nav (Prev/Next), the `X / Y in view`
  counts, and the `epoch #N · X ops` indicator are removed from this
  panel — the L2 events list already owns spine focus navigation
  (`:rf.xray/focus-cascade-prev` / `-next`), and the L4 tab strip is
  the panel-name source-of-truth.

  ## Empty states

    :no-events     → 'No events.' (the focused epoch carries no
                     trace events).
    :no-focus      → defensive: no focused epoch AND no history.
    :epoch-evicted → the focused epoch aged out of the
                     `:epoch-history` ring buffer.

  ## Pure hiccup (rf2-tijr)

  Same contract as every other Xray panel — pure hiccup, no
  Reagent / UIx / Helix references. Frame isolation comes from the
  enclosing `[rf/frame-provider {:frame :rf/xray}]` in shell.cljs.

  ## Helpers

  All pure-data logic — row projection, epoch-scoped feed projection,
  empty-state classification — lives in `trace_helpers.cljc` so the
  algebra runs under the JVM unit-test target."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.cancellation-cascade-helpers :as cch]
            [day8.re-frame2-xray.panels.event-detail :as event-detail]
            [day8.re-frame2-xray.panels.event.event-status-colour :as event-status]
            [day8.re-frame2-xray.panels.overflow-indicator :as overflow]
            [day8.re-frame2-xray.panels.shared.focus-resolver :as focus]
            [day8.re-frame2-xray.panels.trace-helpers :as h]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack]]
            [day8.re-frame2-xray.views.edn-widget.widget :as edn]))

;; ---- payload renderer ---------------------------------------------------
;;
;; Per spec/021 §5 the per-row "expand payload" surface shows the trace
;; event's CURRENT state — so per Mike-direction 2026-05-21 (rf2-dmso5)
;; it renders through the EDN widget's `browse` variant, which is the
;; re-frame-10x cljs-devtools look (type-coloured · nested · expanded).
;; The Trace panel passes the raw trace event as `:value`.

(defn- render-payload
  "Per-row payload renderer. Wires the EDN widget's current-state
  `browse` (cljs-devtools) to the row's raw trace event. Each row gets
  its own `:render-id` (`trace-row-<id>`) so the rendered container's
  testid is unique per row."
  [{:keys [id raw] :as _row}]
  [:div {:data-testid (str "rf-xray-trace-row-" id "-payload")
         :style       {:padding "8px 16px 12px 110px"
                       :background (:bg-1 tokens)
                       :border-bottom (str "1px solid " (:border-subtle tokens))}}
   (edn/browse
     {:value     raw
      :panel-id  :trace
      :render-id (str "trace-row-" id)})])

;; ---- row layout constants (rf2-ad7zx.8) --------------------------------

(def ^:private indent-px
  "Causal-nesting indent per depth level (spec/021 §5.2 — child
  dispatches indent under their parent cascade)."
  20)

(defn- op-row-cells
  "The inner grid of one op row — relative timestamp · readable
  description · per-op duration. Per spec/021 §5.2 the Figma row is
  `t+N.Nms · readable description · duration` with the op-family band
  as a 3px LEFT-BORDER on the row container (not a dot). The duration
  column shows elapsed for event/view rows; reactive point-in-time
  emits leave it blank."
  [{:keys [rel-time time description duration-ms] :as _row} row-test-id]
  [:div {:data-testid (str row-test-id "-summary")
         :style       {:display       "grid"
                       :grid-template-columns "84px minmax(0, 1fr) auto"
                       :gap           "12px"
                       :align-items   "center"
                       :padding       "6px 16px"}}
   ;; Relative timestamp (`t+N.Nms`); absolute clock as the hover title.
   [:span {:data-testid (str row-test-id "-time")
           :title       (or (h/format-time time) "")
           :style {:color (:text-tertiary tokens)
                   :font-size "11px"
                   :white-space "nowrap"}}
    (or rel-time "—")]
   ;; Readable plain-language description (the dense default line).
   [:span {:data-testid (str row-test-id "-description")
           :style       {:color         (:text-primary tokens)
                         :overflow      "hidden"
                         :text-overflow "ellipsis"
                         :white-space   "nowrap"}
           :title       description}
    description]
   ;; Per-op duration column (blank for point-in-time reactive emits).
   [:span {:data-testid (str row-test-id "-duration")
           :style {:color (:text-tertiary tokens)
                   :font-size "11px"
                   :white-space "nowrap"
                   :text-align "right"}}
    (or (h/format-duration duration-ms) "")]])

(defn- trace-row
  "One op row in the trace ribbon.

  Per rf2-z4fza: the React `:key` is the row's stable trace `:id`
  (via `h/row-key`). The earlier shape keyed on a tuple that mixed
  in the row's positional index inside the visible viewport — every
  new trace push shifted every visible row's index, so every key
  changed, and React unmounted+remounted the entire viewport on
  EVERY push. Same discipline class as rf2-kgn0c's `v:<variant-id>`
  cell-keying in the story workspace.

  Per rf2-ad7zx.8 (spec/021 §5.2 · design-reference/xray_devtools_reference.cljs,
  the `trace-panel` component):
  the op-family colour is a **3px left-border band** on the row
  container (dispatch = mode accent · db = changed · fx = warning ·
  reactive = dim · machine = chart tone) — NOT an op-type dot. Child
  dispatches **indent** under their parent cascade via `:depth`. The
  row body is the readable plain-language description with a per-op
  duration column at the right.

  Per spec/021 §5.4 + rf2-7dyi8 the row-click behaviour is **inline
  payload expansion** (NOT pivot to event-detail) — the Trace panel
  is already scoped to the focused epoch (rf2-ycoct), so pivoting
  would just navigate to the same cascade. Click toggles the row's
  membership in `:rf.xray/trace-expanded-row-ids`; expanded rows
  render the shared data-display renderer below the row inside the
  same `<li>` so the React-key + scroll-anchoring discipline holds."
  [{:keys [id operation source-coord dispatch-id op-family depth]
    :as row}
   {:keys [expanded?]}]
  (let [row-test-id (str "rf-xray-trace-row-" id)
        band-colour (h/op-family-colour row)
        depth*      (or depth 0)
        ;; rf2-59e7k — destroy-event rows surface a 'Show cancellation
        ;; cascade' action button. The classifier mirrors
        ;; helpers/destroy-operations so the trace ns has no upward dep
        ;; on the cascade view.
        destroy?    (cch/destroy-event? {:operation operation})]
    [:li {:key         (h/row-key row)
          :data-testid row-test-id
          :data-rf-xray-expanded (boolean expanded?)
          :data-rf-xray-op-family (some-> op-family name)
          :data-rf-xray-depth depth*
          :on-click    (fn []
                         ;; rf2-7dyi8 — toggle inline payload expansion
                         ;; rather than pivoting to event-detail (spec
                         ;; §5.4: 'Row → expand payload · Inline in
                         ;; panel (no nav)').
                         (rf/dispatch [:rf.xray/toggle-trace-row-expand id]
                                      {:frame :rf/xray}))
          :on-context-menu (when destroy?
                             (fn [e]
                               (.preventDefault e)
                               (.stopPropagation e)
                               (rf/dispatch
                                 [:rf.xray/cancellation-cascade-open
                                  {:kind :dispatch-id :id dispatch-id}]
                                 {:frame :rf/xray})))
          :style       {:display        "flex"
                        :align-items    "stretch"
                        ;; op-family colour band as a 3px LEFT-BORDER.
                        :border-left    (str "3px solid " band-colour)
                        ;; rf2-tha26 — Figma rounded hover-pill rows
                        ;; (the `trace-panel` component in
                        ;; `design-reference/xray_devtools_reference.cljs`):
                        ;; rows are discrete pills (`rounded` +
                        ;; `space-y-0.5`) lit by a `hover:bg` fill rather
                        ;; than flat rows divided by hairlines. Inline
                        ;; styles can't carry a `:hover` pseudo-class, so
                        ;; the hover fill is a scoped CSS rule in
                        ;; theme/global-styles keyed off the row testid;
                        ;; here we round the corners + add the 2px inter-
                        ;; row rhythm. An expanded row keeps its tinted
                        ;; fill so the open payload reads as one unit.
                        :border-radius  "4px"
                        :margin-bottom  "2px"
                        :background     (when expanded? (:bg-1 tokens))
                        ;; Causal-nesting indent (cascade tree).
                        :margin-left    (str (* depth* indent-px) "px")
                        :cursor         "pointer"
                        :color          (:text-primary tokens)
                        :font-family    mono-stack
                        :font-size      "12px"
                        :line-height    1.35}}
     [:div {:style {:flex 1 :min-width 0}}
      ;; Row grid + the per-row actions, held in an inner div so the
      ;; optional payload renders as a sibling inside the same `<li>`
      ;; without breaking the row's React-key discipline (rf2-z4fza).
      [:div {:style {:display "flex" :align-items "center"}}
       [:div {:style {:flex 1 :min-width 0}}
        (op-row-cells row row-test-id)]
       ;; Source-coord chip (when present).
       (when source-coord
         [:button {:data-testid (str row-test-id "-source-coord")
                   :on-click    (fn [e]
                                  (.stopPropagation e)
                                  (rf/dispatch [:rf.xray/open-in-editor
                                                {:source-coord source-coord}] {:frame :rf/xray}))
                   :style       {:background  "transparent"
                                 :color       (:accent tokens)
                                 :border      (str "1px solid " (:border-subtle tokens))
                                 :padding     "1px 6px"
                                 :margin-right "8px"
                                 :border-radius "3px"
                                 :cursor      "pointer"
                                 :font-family mono-stack
                                 :font-size   "10px"}}
          source-coord])
       ;; rf2-59e7k cancellation-cascade action (destroy rows only).
       (when destroy?
         [:button {:data-testid (str row-test-id "-cancellation-cascade")
                   :title       "Show cancellation cascade"
                   :on-click    (fn [e]
                                  (.stopPropagation e)
                                  (rf/dispatch
                                    [:rf.xray/cancellation-cascade-open
                                     {:kind :dispatch-id :id dispatch-id}]
                                    {:frame :rf/xray}))
                   :style       {:background  "transparent"
                                 :color       (or (:red tokens)
                                                  (:text-secondary tokens))
                                 :border      (str "1px solid " (:border-subtle tokens))
                                 :padding     "1px 6px"
                                 :margin-right "8px"
                                 :border-radius "3px"
                                 :cursor      "pointer"
                                 :font-family mono-stack
                                 :font-size   "10px"}}
          "⟲ cascade"])]
      ;; rf2-7dyi8 — inline payload expansion. Consumes the shared
      ;; rf2-jgip1 data-display renderer (#1739, landed in main).
      (when expanded? (render-payload row))]]))

;; ---- reactive-aftermath collapse group (rf2-ad7zx.8) -------------------

(defn- reactive-group-row
  "Render one collapsed reactive-aftermath group — the spec/021 §5.2
  `▸ reactive aftermath (N subs, M renders)` row. Clicking toggles the
  group open via `:rf.xray/toggle-trace-group-expand`; expanded groups
  render their child reactive rows (subs ran · views rendered) inline
  inside the same `<li>` so the React-key + scroll discipline holds.
  The group rides the dim op-family band so the core dominoes stand
  out."
  [{:keys [id rel-time summary children depth] :as _group}
   {:keys [expanded? expanded-row-ids]}]
  (let [grp-test-id (str "rf-xray-trace-group-" id)
        depth*      (or depth 0)
        {:keys [subs renders]} summary
        band-colour (h/op-family-colour {:op-family :reactive})]
    [:li {:key         (str "g:" id)
          :data-testid grp-test-id
          :data-rf-xray-expanded (boolean expanded?)
          :data-rf-xray-op-family "reactive"
          :on-click    (fn []
                         (rf/dispatch [:rf.xray/toggle-trace-group-expand id]
                                      {:frame :rf/xray}))
          :style       {:display       "block"
                        :border-left   (str "3px solid " band-colour)
                        ;; rf2-tha26 — rounded hover-pill rhythm matching
                        ;; the op rows (the group is a trace row too).
                        :border-radius "4px"
                        :margin-bottom "2px"
                        :margin-left   (str (* depth* indent-px) "px")
                        :cursor        "pointer"
                        :color         (:text-secondary tokens)
                        :font-family   mono-stack
                        :font-size     "12px"
                        :line-height   1.35}}
     [:div {:data-testid (str grp-test-id "-summary")
            :style {:display "grid"
                    :grid-template-columns "84px minmax(0, 1fr)"
                    :gap "12px"
                    :align-items "center"
                    :padding "6px 16px"}}
      [:span {:style {:color (:text-tertiary tokens)
                      :font-size "11px"
                      :white-space "nowrap"}}
       (or rel-time "—")]
      [:span {:style {:color (:text-secondary tokens)}}
       (str (if expanded? "▾" "▸")
            " reactive aftermath ("
            subs " sub" (when (not= 1 subs) "s") ", "
            renders " render" (when (not= 1 renders) "s") ")")]]
     ;; Expanded — render the child reactive rows inline.
     (when expanded?
       [:div {:data-testid (str grp-test-id "-children")
              :style {:padding-left "16px"
                      :background (:bg-1 tokens)}}
        (for [child children]
          ^{:key (h/row-key child)}
          (trace-row child
                     {:expanded? (contains? (or expanded-row-ids #{})
                                            (:id child))}))])]))

(defn- trace-node
  "Render one display node — either a `:reactive-group` collapse group
  or a plain op row. The view feeds the structural `:nodes` projection
  (rf2-ad7zx.8) through this so the reactive-aftermath group, causal
  nesting, and op-family bands all render from one pure-data tree."
  [node {:keys [expanded-row-ids expanded-group-ids] :as _state}]
  (if (= :reactive-group (:kind node))
    (reactive-group-row node
                        {:expanded? (contains? (or expanded-group-ids #{})
                                               (:id node))
                         :expanded-row-ids expanded-row-ids})
    (trace-row node
               {:expanded? (contains? (or expanded-row-ids #{})
                                      (:id node))})))

;; ---- footer legend ------------------------------------------------------

(defn- footer-legend
  "Reference footer legend (rf2-tha26 ·
  `design-reference/xray_devtools_reference.cljs`, the `trace-panel`
  component). Names the two reading
  conventions of the ribbon — rows are colour-banded by op-family
  (the 3px left border), and clicking a row expands its raw
  `:operation` + tags payload inline. Rendered below the feed (feed
  branch only)."
  []
  [:div {:data-testid "rf-xray-trace-footer-legend"
         :style       {:margin     "16px 16px 0 16px"
                       :padding-top "16px"
                       :border-top (str "1px solid " (:border-default tokens))
                       :color      (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size  "11px"}}
   "colour-banded by op-family · click a row → expand raw :operation + tags"])

;; ---- empty states -------------------------------------------------------

(defn- empty-state-message
  "Shared terse empty-state block. `kind` drives the data-testid +
  copy. Per rf2-td380 the Trace panel is epoch-scoped, so the empty
  states discriminate the focus-resolver's three statuses plus the
  'focused epoch carries no trace events' case."
  [kind copy]
  [:div {:data-testid (str "rf-xray-trace-empty-" (name kind))
         :style       {:padding     "24px"
                       :font-family sans-stack
                       :font-size   "13px"
                       :line-height 1.5
                       :color       (:text-secondary tokens)}}
   [:p {:style {:margin 0
                :color  (:text-tertiary tokens)}}
    copy]])

(defn- empty-state-no-events
  "The focused epoch carries no trace events."
  []
  (empty-state-message :no-events "No events."))

(defn- empty-state-no-focus
  "Defensive empty-state — no focused epoch AND no epoch history. Per
  rf2-639lc the default-focus rule should always land on a real
  cascade, so this branch should never render in practice; the terse
  copy + testid here let us spot a regression when it does."
  []
  (empty-state-message :no-focus "No focused event."))

(defn- empty-state-epoch-evicted
  "The focused epoch's record has aged out of the `:epoch-history`
  ring buffer. Mirrors the Issues / App-DB Diff placeholder per
  spec/021 §10.7."
  []
  (empty-state-message
    :epoch-evicted
    "This epoch has been evicted from the history buffer."))

;; ---- cascade status timeline bar (rf2-b76v4) ---------------------------

(defn- cascade-status-bar
  "Render the focused cascade's lifecycle-status bar above the trace
  ribbon. The Trace tab is cascade-scoped (rf2-ycoct) — every visible
  row already belongs to the focused cascade — so the bar fills the
  ribbon's full width with the canonical lifecycle colour. This is
  the bead's 'Trace timeline bar fill' surface: ONE pure-fn-driven
  bar that tells the user 'these rows are settled-success / errored /
  stale / paused / in-flight' at a glance, before they scan the
  per-row dots.

  The fn consumes `event-status/event-status-colour` — the same
  helper the L2 list rows + the Event L4 header dot consume — so the
  whole devtool speaks ONE lifecycle vocabulary."
  [{:keys [cascade focus]}]
  (let [status-state (event-status/cascade->state
                       cascade focus event-detail/cascade-outcome)
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
           :style {:height        "3px"
                   :background    status-hex
                   :flex-shrink   0}}]))

;; ---- public view --------------------------------------------------------

(rf/reg-view Panel
  "The Trace panel's root view. Subscribes to `:rf.xray/trace-feed`
  (the epoch-scoped feed, rf2-td380) and renders the focused epoch's
  domino-trail ribbon or an empty-state. No header row (rf2-o6yqq),
  no filtering UI (rf2-gkczt).

  ## rf2-b76v4 — cascade-status timeline bar

  A 3px bar above the ribbon fills with the focused cascade's
  lifecycle-status colour — ONE bar driven by `event-status-colour`,
  the same fn the L2 row + Event header consume."
  []
  (let [{:keys [nodes empty-kind] :as _data}
        @(rf/subscribe [:rf.xray/trace-feed])
        ;; rf2-b76v4 — pull the focused cascade + focus map so the
        ;; cascade-status timeline bar can render with the canonical
        ;; lifecycle colour. Both subs are cheap (constant-time
        ;; selectors over the spine slot + the cascade list).
        cascades       @(rf/subscribe [:rf.xray/cascades])
        focus          @(rf/subscribe [:rf.xray/focus])
        focused-id     (:dispatch-id focus)
        focused-cascade (when focused-id
                          (some #(when (= focused-id (:dispatch-id %)) %)
                                cascades))
        ;; rf2-7dyi8 — per-row inline payload expansion set; rf2-ad7zx.8
        ;; — per-group reactive-aftermath expansion set. The node-fn
        ;; reads both from the closure built here so it keeps the
        ;; single-arg shape `overflow/capped-list` requires.
        expanded-ids   @(rf/subscribe [:rf.xray/trace-expanded-row-ids])
        expanded-groups @(rf/subscribe [:rf.xray/trace-expanded-group-ids])
        node-with-state (fn [node]
                          (trace-node node
                                      {:expanded-row-ids   expanded-ids
                                       :expanded-group-ids expanded-groups}))]
    [:section {:data-testid "rf-xray-trace"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     ;; rf2-b76v4 — cascade-status timeline bar. Renders whenever a
     ;; cascade is in focus — the bar reflects the focused CASCADE's
     ;; lifecycle (sourced from `:rf.xray/cascades` + focus), which is
     ;; orthogonal to the epoch feed's empty-kind (rf2-td380: the feed
     ;; scopes by the focused epoch's `:trace-events`).
     (when focused-cascade
       (cascade-status-bar {:cascade focused-cascade
                            :focus   focus}))
     [:div {:style {:flex 1 :overflow "auto"}}
      (case empty-kind
        :no-events     (empty-state-no-events)
        :no-focus      (empty-state-no-focus)
        :epoch-evicted (empty-state-epoch-evicted)
        nil            [:<>
                        (overflow/capped-list
                          nodes
                          {:panel-id "trace"
                           :ul-attrs {:data-testid "rf-xray-trace-feed"
                                      :style       {:list-style "none"
                                                    :margin     0
                                                    :padding    "8px 16px 0 16px"}}
                           :row-fn   node-with-state})
                        (footer-legend)])]]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Trace panel's Xray-side registrations
  (Phase 5, rf2-argrj; epoch-scoped rework rf2-td380 + rf2-gkczt)."
  []
  ;; ---- Trace panel — epoch-scoped feed (rf2-td380) --------------------
  ;;
  ;; Per spec/018 §6 every L4 panel is a lens on the spine's focused
  ;; event, not a global ribbon. The Trace tab reads the FOCUSED
  ;; EPOCH's `:trace-events` — the per-frame settling epoch record's
  ;; raw trace slice, which folds the COMPLETE domino trail for one
  ;; event (the synchronous event-side dispatch-id-N rows AND the
  ;; async nil-dispatch-id reactive rows — `:rf.sub/run` / `:rf.view/render`
  ;; — that fire post-cascade for that settling). The prior shape
  ;; scoped the global trace bus by `:dispatch-id`, which dropped the
  ;; async reactive rows; reading the epoch record renders the whole
  ;; trail.
  ;;
  ;; The focused epoch record is resolved exactly as the Issues /
  ;; App-DB Diff panels resolve theirs: join `:rf.xray/focus`
  ;; (carrying `:epoch-id`) + `:rf.xray/epoch-history` (the
  ;; framework's per-frame ring of `:rf/epoch-record` maps), then run
  ;; the shared `panels.shared.focus-resolver` — which classifies the
  ;; focus status (`:no-focus` / `:focused` / `:epoch-evicted`, with
  ;; the rf2-h0120 head-fallback) and looks up the record.
  ;;
  ;; No filtering (rf2-gkczt): the focused epoch IS the scope, so the
  ;; feed is the epoch's domino trail with no chip narrowing.
  ;;
  ;; Shape of `:rf.xray/trace-feed`:
  ;;
  ;;     {:rows       [<row> ...]   ;; the epoch's domino trail, newest first
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

  ;; ---- rf2-7dyi8 — per-row inline payload expansion ------------------
  ;;
  ;; Per spec/021 §5.4 clicking a row expands its payload inline (no
  ;; nav). The expanded set lives in app-db so the toggle survives
  ;; sub-recomputes. The shared rf2-jgip1 renderer owns deeper-than-2
  ;; expansion state under its own `:rf.xray/data-display-expansion`
  ;; slot; this set gates whether the renderer is mounted for a row
  ;; at all.

  (rf/reg-sub :rf.xray/trace-expanded-row-ids
    (fn [db _query]
      (get db :trace-expanded-row-ids #{})))

  (rf/reg-event-db :rf.xray/toggle-trace-row-expand
    (fn [db [_ row-id]]
      (let [current (get db :trace-expanded-row-ids #{})]
        (assoc db :trace-expanded-row-ids
               (if (contains? current row-id)
                 (disj current row-id)
                 (conj current row-id))))))

  (rf/reg-event-db :rf.xray/clear-trace-expand
    (fn [db _event]
      (dissoc db :trace-expanded-row-ids :trace-expanded-group-ids)))

  ;; ---- rf2-ad7zx.8 — reactive-aftermath collapse groups --------------
  ;;
  ;; Per spec/021 §5.2 the post-cascade reactive emits collapse under
  ;; one expandable `▸ reactive aftermath (N subs, M renders)` group so
  ;; the core dominoes stand out. The expanded-group set lives in app-db
  ;; (keyed by the group's stable `react-grp:<id>` id) so the toggle
  ;; survives sub-recomputes, exactly as the per-row expansion set does.

  (rf/reg-sub :rf.xray/trace-expanded-group-ids
    (fn [db _query]
      (get db :trace-expanded-group-ids #{})))

  (rf/reg-event-db :rf.xray/toggle-trace-group-expand
    (fn [db [_ group-id]]
      (let [current (get db :trace-expanded-group-ids #{})]
        (assoc db :trace-expanded-group-ids
               (if (contains? current group-id)
                 (disj current group-id)
                 (conj current group-id))))))

  ;; rf2-2moh1 — register the Dynamic Trace tab with the internal L4
  ;; tab registry.
  (panel-registry/reg-l4-tab!
    {:id    :trace
     :label "Trace"
     :mnem  "t"
     :modes #{:dynamic}
     :order 3
     :panel Panel}))
