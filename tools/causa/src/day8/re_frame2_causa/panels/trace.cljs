(ns day8.re-frame2-causa.panels.trace
  "Trace panel — Phase 5 (rf2-argrj, parent rf2-5aw5v);
  epoch-scoped rework (rf2-o6yqq + rf2-td380 + rf2-gkczt).

  Per `tools/causa/spec/000-Vision.md` L89 (panel-inventory row) the
  Trace panel is the raw-event ribbon — every trace event in the
  FOCUSED EPOCH renders as one timestamped row.

  ## What this panel shows

  A scrollable, timestamped ribbon scoped to the spine's focused
  epoch. Each row carries:

      timestamp · op-type-dot · operation · description · source-coord

  Clicking a row toggles its inline payload expansion (no nav).

  ## Epoch-scoped feed (rf2-td380)

  Per spec/018 §6 every L4 panel is a lens on the spine's focused
  event, not a global ribbon. The Trace tab reads the FOCUSED EPOCH's
  `:trace-events` — the per-frame settling epoch record's raw trace
  slice — which folds the COMPLETE domino trail for one event: both
  the synchronous event-side rows (dispatch-id N) AND the async
  reactive rows (`:sub/run` / `:view/render`, nil dispatch-id) that
  fire post-cascade for that settling.

  The prior shape scoped the global trace bus by `:dispatch-id`,
  which DROPPED those async reactive rows (their dispatch-id is nil),
  so the rendered trail was incomplete (e.g. 4 rows shown vs the
  epoch's real 12). Reading the epoch record's `:trace-events` —
  resolved via the shared `panels.shared.focus-resolver` against
  `:rf.causa/focus` + `:rf.causa/epoch-history`, exactly as the
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
  (`:rf.causa/focus-cascade-prev` / `-next`), and the L4 tab strip is
  the panel-name source-of-truth.

  ## Empty states

    :no-events     → 'No events.' (the focused epoch carries no
                     trace events).
    :no-focus      → defensive: no focused epoch AND no history.
    :epoch-evicted → the focused epoch aged out of the
                     `:epoch-history` ring buffer.

  ## Pure hiccup (rf2-tijr)

  Same contract as every other Causa panel — pure hiccup, no
  Reagent / UIx / Helix references. Frame isolation comes from the
  enclosing `[rf/frame-provider {:frame :rf/causa}]` in shell.cljs.

  ## Helpers

  All pure-data logic — row projection, epoch-scoped feed projection,
  empty-state classification — lives in `trace_helpers.cljc` so the
  algebra runs under the JVM unit-test target."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panel-registry :as panel-registry]
            [day8.re-frame2-causa.panels.cancellation-cascade-helpers :as cch]
            [day8.re-frame2-causa.panels.event-detail :as event-detail]
            [day8.re-frame2-causa.panels.event.event-status-colour :as event-status]
            [day8.re-frame2-causa.panels.overflow-indicator :as overflow]
            [day8.re-frame2-causa.panels.shared.focus-resolver :as focus]
            [day8.re-frame2-causa.panels.trace-helpers :as h]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]
            [day8.re-frame2-causa.views.edn-widget.widget :as edn]))

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
  [:div {:data-testid (str "rf-causa-trace-row-" id "-payload")
         :style       {:padding "8px 16px 12px 110px"
                       :background (:bg-1 tokens)
                       :border-bottom (str "1px solid " (:border-subtle tokens))}}
   (edn/browse
     {:value     raw
      :panel-id  :trace
      :render-id (str "trace-row-" id)})])

(defn- trace-row
  "One row in the trace ribbon.

  Per rf2-z4fza: the React `:key` is the row's stable trace `:id`
  (via `h/row-key`). The earlier shape keyed on a tuple that mixed
  in the row's positional index inside the visible viewport — every
  new trace push shifted every visible row's index, so every key
  changed, and React unmounted+remounted the entire viewport on
  EVERY push. Same discipline class as rf2-kgn0c's `v:<variant-id>`
  cell-keying in the story workspace.

  Per spec/021 §5.4 + rf2-7dyi8 the row-click behaviour is **inline
  payload expansion** (NOT pivot to event-detail) — the Trace panel
  is already scoped to the focused epoch (rf2-ycoct), so pivoting
  would just navigate to the same cascade. Click toggles the row's
  membership in `:rf.causa/trace-expanded-row-ids`; expanded rows
  render the shared data-display renderer below the row inside the
  same `<li>` so the React-key + scroll-anchoring discipline holds."
  [{:keys [id time op-type operation description
           source-coord dispatch-id]
    :as row}
   {:keys [expanded?]}]
  (let [row-test-id (str "rf-causa-trace-row-" id)
        dot-colour  (h/op-type-colour op-type)
        ;; rf2-59e7k — destroy-event rows surface a 'Show cancellation
        ;; cascade' action button next to the source-coord cell. The
        ;; classifier mirrors helpers/destroy-operations so the trace
        ;; ns has no upward dep on the cascade view.
        destroy?    (cch/destroy-event? {:operation operation})]
    [:li {:key         (h/row-key row)
          :data-testid row-test-id
          :data-rf-causa-expanded (boolean expanded?)
          :on-click    (fn []
                         ;; rf2-7dyi8 — toggle inline payload expansion
                         ;; rather than pivoting to event-detail (spec
                         ;; §5.4: 'Row → expand payload · Inline in
                         ;; panel (no nav)').
                         (rf/dispatch [:rf.causa/toggle-trace-row-expand id]
                                      {:frame :rf/causa}))
          :on-context-menu (when destroy?
                             (fn [e]
                               (.preventDefault e)
                               (.stopPropagation e)
                               (rf/dispatch
                                 [:rf.causa/cancellation-cascade-open
                                  {:kind :dispatch-id :id dispatch-id}]
                                 {:frame :rf/causa})))
          :style       {:display        "block"
                        :border-bottom  (str "1px solid "
                                             (:border-subtle tokens))
                        :background     (when expanded? (:bg-1 tokens))
                        :cursor         "pointer"
                        :color          (:text-primary tokens)
                        :font-family    mono-stack
                        :font-size      "12px"
                        :line-height    1.35}}
     ;; Row grid — the dense single-line summary per spec/021 §5.2.
     ;; Held in an inner div so the optional payload can render as a
     ;; sibling inside the same `<li>` without breaking the row's
     ;; React-key stability discipline (rf2-z4fza).
     [:div {:data-testid (str row-test-id "-summary")
            :style       {:display       "grid"
                          :grid-template-columns
                          "84px 14px minmax(140px, 1fr) 2fr auto auto"
                          :gap           "10px"
                          :align-items   "center"
                          :padding       "6px 16px"}}
     ;; Timestamp
     [:span {:data-testid (str row-test-id "-time")
             :style {:color (:text-tertiary tokens)
                     :font-size "11px"
                     :white-space "nowrap"}}
      (or (h/format-time time) "—")]
     ;; op-type dot
     [:span {:data-testid (str row-test-id "-op-type-dot")
             :title       (str op-type)
             :style       {:color dot-colour
                           :font-weight 700
                           :text-align "center"}}
      "●"]
     ;; Operation
     [:span {:data-testid (str row-test-id "-operation")
             :style       {:color         (:accent-violet tokens)
                           :overflow      "hidden"
                           :text-overflow "ellipsis"
                           :white-space   "nowrap"}
             :title       (str operation)}
      (or (some-> operation str) "—")]
     ;; Description
     [:span {:data-testid (str row-test-id "-description")
             :style       {:color         (:text-secondary tokens)
                           :overflow      "hidden"
                           :text-overflow "ellipsis"
                           :white-space   "nowrap"}
             :title       description}
      description]
     ;; Source-coord (when present)
     (if source-coord
       [:button {:data-testid (str row-test-id "-source-coord")
                 :on-click    (fn [e]
                                (.stopPropagation e)
                                (rf/dispatch [:rf.causa/open-in-editor
                                              {:source-coord source-coord}] {:frame :rf/causa}))
                 :style       {:background  "transparent"
                               :color       (:cyan tokens)
                               :border      (str "1px solid " (:border-subtle tokens))
                               :padding     "1px 6px"
                               :border-radius "3px"
                               :cursor      "pointer"
                               :font-family mono-stack
                               :font-size   "10px"}}
        source-coord]
       [:span {:style {:color (:text-tertiary tokens)
                       :font-size "10px"}}
        "—"])
     ;; rf2-59e7k cancellation-cascade action (destroy rows only).
     ;; Opens the popover focused on this row's dispatch-id. Right-
     ;; clicking the row anywhere is the same action — this button is
     ;; the visible affordance for the same shortcut.
     (if destroy?
       [:button {:data-testid (str row-test-id "-cancellation-cascade")
                 :title       "Show cancellation cascade"
                 :on-click    (fn [e]
                                (.stopPropagation e)
                                (rf/dispatch
                                  [:rf.causa/cancellation-cascade-open
                                   {:kind :dispatch-id :id dispatch-id}]
                                  {:frame :rf/causa}))
                 :style       {:background  "transparent"
                               :color       (or (:red tokens)
                                                (:text-secondary tokens))
                               :border      (str "1px solid " (:border-subtle tokens))
                               :padding     "1px 6px"
                               :border-radius "3px"
                               :cursor      "pointer"
                               :font-family mono-stack
                               :font-size   "10px"}}
        "⟲ cascade"]
       [:span {:style {:color (:text-tertiary tokens)
                       :font-size "10px"
                       :min-width "10px"}}
        ""])]
     ;; rf2-7dyi8 — inline payload expansion. Consumes the shared
     ;; rf2-jgip1 data-display renderer (#1739, landed in main).
     (when expanded? (render-payload row))]))

;; ---- empty states -------------------------------------------------------

(defn- empty-state-message
  "Shared terse empty-state block. `kind` drives the data-testid +
  copy. Per rf2-td380 the Trace panel is epoch-scoped, so the empty
  states discriminate the focus-resolver's three statuses plus the
  'focused epoch carries no trace events' case."
  [kind copy]
  [:div {:data-testid (str "rf-causa-trace-empty-" (name kind))
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
    [:div {:data-testid (str "rf-causa-trace-cascade-status-bar-"
                             (name status-kw))
           :data-rf-causa-status (name status-kw)
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
  "The Trace panel's root view. Subscribes to `:rf.causa/trace-feed`
  (the epoch-scoped feed, rf2-td380) and renders the focused epoch's
  domino-trail ribbon or an empty-state. No header row (rf2-o6yqq),
  no filtering UI (rf2-gkczt).

  ## rf2-b76v4 — cascade-status timeline bar

  A 3px bar above the ribbon fills with the focused cascade's
  lifecycle-status colour — ONE bar driven by `event-status-colour`,
  the same fn the L2 row + Event header consume."
  []
  (let [{:keys [rows empty-kind] :as _data}
        @(rf/subscribe [:rf.causa/trace-feed])
        ;; rf2-b76v4 — pull the focused cascade + focus map so the
        ;; cascade-status timeline bar can render with the canonical
        ;; lifecycle colour. Both subs are cheap (constant-time
        ;; selectors over the spine slot + the cascade list).
        cascades       @(rf/subscribe [:rf.causa/cascades])
        focus          @(rf/subscribe [:rf.causa/focus])
        focused-id     (:dispatch-id focus)
        focused-cascade (when focused-id
                          (some #(when (= focused-id (:dispatch-id %)) %)
                                cascades))
        ;; rf2-7dyi8 — per-row inline payload expansion set. The
        ;; trace-row hiccup reads `expanded?` from the closure built
        ;; here so the row-fn keeps the single-arg shape `overflow/
        ;; capped-list` requires.
        expanded-ids   @(rf/subscribe [:rf.causa/trace-expanded-row-ids])
        row-with-state (fn [row]
                         (trace-row row
                                    {:expanded?
                                     (contains? (or expanded-ids #{})
                                                (:id row))}))]
    [:section {:data-testid "rf-causa-trace"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     ;; rf2-b76v4 — cascade-status timeline bar. Renders whenever a
     ;; cascade is in focus — the bar reflects the focused CASCADE's
     ;; lifecycle (sourced from `:rf.causa/cascades` + focus), which is
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
        nil            (overflow/capped-list
                         rows
                         {:panel-id "trace"
                          :ul-attrs {:data-testid "rf-causa-trace-feed"
                                     :style       {:list-style "none"
                                                   :margin     0
                                                   :padding    0}}
                          :row-fn   row-with-state}))]]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Trace panel's Causa-side registrations
  (Phase 5, rf2-argrj; epoch-scoped rework rf2-td380 + rf2-gkczt)."
  []
  ;; ---- Trace panel — epoch-scoped feed (rf2-td380) --------------------
  ;;
  ;; Per spec/018 §6 every L4 panel is a lens on the spine's focused
  ;; event, not a global ribbon. The Trace tab reads the FOCUSED
  ;; EPOCH's `:trace-events` — the per-frame settling epoch record's
  ;; raw trace slice, which folds the COMPLETE domino trail for one
  ;; event (the synchronous event-side dispatch-id-N rows AND the
  ;; async nil-dispatch-id reactive rows — `:sub/run` / `:view/render`
  ;; — that fire post-cascade for that settling). The prior shape
  ;; scoped the global trace bus by `:dispatch-id`, which dropped the
  ;; async reactive rows; reading the epoch record renders the whole
  ;; trail.
  ;;
  ;; The focused epoch record is resolved exactly as the Issues /
  ;; App-DB Diff panels resolve theirs: join `:rf.causa/focus`
  ;; (carrying `:epoch-id`) + `:rf.causa/epoch-history` (the
  ;; framework's per-frame ring of `:rf/epoch-record` maps), then run
  ;; the shared `panels.shared.focus-resolver` — which classifies the
  ;; focus status (`:no-focus` / `:focused` / `:epoch-evicted`, with
  ;; the rf2-h0120 head-fallback) and looks up the record.
  ;;
  ;; No filtering (rf2-gkczt): the focused epoch IS the scope, so the
  ;; feed is the epoch's domino trail with no chip narrowing.
  ;;
  ;; Shape of `:rf.causa/trace-feed`:
  ;;
  ;;     {:rows       [<row> ...]   ;; the epoch's domino trail, newest first
  ;;      :total      <int>         ;; the epoch's trace-event count
  ;;      :rendered   <int>         ;; same as :total (no filtering)
  ;;      :epoch-id   <int-or-nil>  ;; the focused epoch's id
  ;;      :empty-kind <:no-events / :no-focus / :epoch-evicted / nil>}
  (rf/reg-sub :rf.causa/trace-feed
    :<- [:rf.causa/focus]
    :<- [:rf.causa/epoch-history]
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
  ;; expansion state under its own `:rf.causa/data-display-expansion`
  ;; slot; this set gates whether the renderer is mounted for a row
  ;; at all.

  (rf/reg-sub :rf.causa/trace-expanded-row-ids
    (fn [db _query]
      (get db :trace-expanded-row-ids #{})))

  (rf/reg-event-db :rf.causa/toggle-trace-row-expand
    (fn [db [_ row-id]]
      (let [current (get db :trace-expanded-row-ids #{})]
        (assoc db :trace-expanded-row-ids
               (if (contains? current row-id)
                 (disj current row-id)
                 (conj current row-id))))))

  (rf/reg-event-db :rf.causa/clear-trace-expand
    (fn [db _event]
      (dissoc db :trace-expanded-row-ids)))

  ;; rf2-2moh1 — register the Dynamic Trace tab with the internal L4
  ;; tab registry.
  (panel-registry/reg-l4-tab!
    {:id    :trace
     :label "Trace"
     :mnem  "t"
     :modes #{:dynamic}
     :order 3
     :panel Panel}))
