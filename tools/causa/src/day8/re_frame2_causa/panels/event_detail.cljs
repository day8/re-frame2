(ns day8.re-frame2-causa.panels.event-detail
  "Event-detail hero panel — §10 Lock 7 default view (Phase 2, rf2-op3bz).

  Per `tools/causa/spec/007-UX-IA.md` §The default landing view the
  hero panel is Events / event-detail; per Phase 1 (rf2-n6x4q) the
  foundation supplies a trace ring buffer + Causa frame isolation. This
  ns adds the first live panel on top of that foundation.

  ## What this panel shows

  When a dispatch-id is selected (via the cascade list — for now, by
  clicking any row), the panel renders the six-domino cascade for that
  dispatch:

    1. The event vector (the cascade root — from `:event/dispatched`).
    2. The handler trace event (the `:run-end` emit — populated by
       `re-frame.trace.projection/absorb`).
    3. The `:event/do-fx` emit (the effects map about to be walked).
    4. The list of `:rf.fx/handled` / override / skipped effects.
    5. The list of `:sub/run` + `:sub/create` events.
    6. The list of `:view/render` events.

  Plus an `:other` bucket for traces that don't fit the six-domino
  vocabulary (errors, warnings, machine transitions, etc.).

  When no dispatch-id is selected the panel shows the cascade list —
  one row per cascade, oldest first — so the user can click to drill
  in. The cascade list is the placeholder until rf2-5yriz lands a
  dedicated actions panel.

  ## Pure hiccup (rf2-tijr)

  Per the substrate-agnostic contract the view is pure hiccup. The
  substrate adapter installed via `rf/init!` handles the render — this
  ns never references Reagent / UIx / Helix directly. Frame isolation
  is provided by the enclosing `[rf/frame-provider {:frame :rf/causa}]`
  in `shell.cljs`; every `subscribe` / `dispatch` here resolves to the
  `:rf/causa` frame.

  ## Source-coord click-through

  When a trace event carries `:rf.trace/trigger-handler` (per rf2-3nn8
  / rf2-lf84g — handler scope rides on every emit), the source-coord
  is shown as a small mono-font caption beneath the event. The click-
  to-jump-to-editor hook (rf2-evgf5) is in flight; until it lands the
  coord is non-interactive plain text per the bead's contract."
  (:require [re-frame.core :as rf]
            [re-frame.trace.projection :as projection]
            [day8.re-frame2-causa.panels.overflow-indicator :as overflow]
            [day8.re-frame2-causa.panels.managed-fx-helpers :as managed-fx-h]
            [day8.re-frame2-causa.panels.managed-fx-template :as managed-fx]
            [day8.re-frame2-causa.spine :as spine]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]
            [day8.re-frame2-causa.theme.perf-tier :as perf-tier]
            [day8.re-frame2-causa.theme.data-inspector :as inspector]))

;; ---- pure helpers --------------------------------------------------------

(defn- format-edn
  "Best-effort EDN-like format of an arbitrary value. Used to render
  event vectors and trace `:tags` payloads in the mono column. Falls
  back to `str` for unprintable values."
  [v]
  (try
    (pr-str v)
    (catch :default _
      (str v))))

(defn- trigger-handler-coord
  "Pluck the source-coord (file + line) off an event's
  `:rf.trace/trigger-handler` slot. nil when the slot is absent."
  [ev]
  (when-let [trigger (:rf.trace/trigger-handler ev)]
    (let [{:keys [file line]} (:source-coord trigger)]
      (when file
        (cond-> file
          line (str ":" line))))))

(defn- selected-ref
  "Normalise the selection slot. Newer callers pass
  `{:dispatch-id <id> :frame <frame-id>}` so non-default frames can be
  distinguished even if a host implementation scopes dispatch ids per
  frame. Older callers that still pass the raw id continue to resolve
  by id only."
  [selection]
  (if (map? selection)
    selection
    {:dispatch-id selection}))

(defn- cascade-has-event?
  "True iff `cascade` carries a real `:event` vector. The `:ungrouped`
  bucket produced by `re-frame.trace.projection/group-cascades` for
  registry-time emits / frame lifecycle outside a drain / REPL evals
  carries no event vector. Per rf2-639lc head-cascade selection skips
  this bucket so the L4 default-focus lands on the most recent ROUTED
  cascade, not the projection's internal bucket."
  [cascade]
  (vector? (:event cascade)))

(defn- default-head-cascade
  "Pick the head (most recent) routed cascade from the cascade vector,
  or nil when none exist. `:ungrouped` cascades are filtered out so a
  registry-time emit can never become the default-focused row.
  Cascades are oldest-first per group-cascades' contract; `last`
  returns the head."
  [cascades]
  (last (filterv cascade-has-event? cascades)))

(defn- cascade-matches-selection?
  [{:keys [dispatch-id frame]} selection]
  (let [{selected-id :dispatch-id selected-frame :frame} (selected-ref selection)]
    (and (= selected-id dispatch-id)
         (or (nil? selected-frame)
             (= selected-frame frame)))))

;; ---- row renderers -------------------------------------------------------

(defn- domino-row
  "One labelled row inside the six-domino layout. `label` is the
  short marker (e.g. \"event\", \"fx\", \"sub\"); `body` is hiccup
  rendered to the right of the label. `tone` is one of the token
  accent keys (`:accent-violet`, `:cyan`, etc.)."
  [{:keys [label tone]} body]
  [:div {:style {:display     "flex"
                 :align-items "flex-start"
                 :gap         "12px"
                 :padding     "8px 12px"
                 :border-bottom (str "1px solid " (:border-subtle tokens))}}
   [:div {:style {:flex          "0 0 96px"
                  :color         (tone tokens)
                  :font-family   sans-stack
                  :font-size     "11px"
                  :font-weight   600
                  :text-transform "uppercase"
                  :letter-spacing "0.5px"
                  :padding-top   "2px"}}
    label]
   [:div {:style {:flex          1
                  :min-width     0
                  :font-family   mono-stack
                  :font-size     "13px"
                  :color         (:text-primary tokens)
                  :word-break    "break-word"}}
    body]])

(defn- coord-line
  "Render the `:rf.trace/trigger-handler` coord underneath a row's
  body. nil when no coord is present."
  [ev]
  (when-let [coord (trigger-handler-coord ev)]
    [:div {:style {:font-family   mono-stack
                   :font-size     "11px"
                   :color         (:text-tertiary tokens)
                   :margin-top    "4px"}}
     "source • " coord]))

(defn- event-row
  "First domino: the dispatched event vector + (when available) the
  trigger-handler source-coord lifted off the `:event/dispatched`
  emit. `event-vec` is the event vector (e.g. `[:counter/inc]`)."
  [event-vec handler-coord]
  [domino-row {:label "event" :tone :accent-violet}
   [:div
    [:div {:style {:font-weight 600}}
     (inspector/inspect event-vec "event-detail/event")]
    (when handler-coord
      [:div {:style {:font-family   mono-stack
                     :font-size     "11px"
                     :color         (:text-tertiary tokens)
                     :margin-top    "4px"}}
       "source • " handler-coord])]])

(defn- tier-dot
  "Render a perf-tier coloured dot + label for `duration-ms`. Per
  `tools/causa/spec/007-UX-IA.md` §Colour system §Perf scale —
  same ladder the Performance panel uses, hoisted into
  `theme.perf-tier` per the rf2-6ja23 audit so every panel that
  surfaces a per-node duration reads the same swatch / glyph / label.

  Renders inline; the colour swatch carries the tier, the
  `aria-label` carries the tier name + duration so the colour-blind
  path doesn't need hue."
  [duration-ms]
  (when (number? duration-ms)
    (let [tier   (perf-tier/classify-tier duration-ms)
          colour (perf-tier/tier-colour tier)
          glyph  (perf-tier/tier-glyph tier)
          label  (perf-tier/tier-label tier)]
      [:span {:data-testid (str "rf-causa-event-detail-tier-dot-" (name tier))
              :aria-label  (str label " (" duration-ms "ms)")
              :title       (str label " — " duration-ms "ms")
              :style       {:display      "inline-flex"
                            :align-items  "center"
                            :gap          "6px"
                            :margin-left  "8px"
                            :color        colour
                            :font-weight  600}}
       [:span {:style {:font-size "12px"}} glyph]
       [:span {:style {:font-family mono-stack
                       :font-size   "11px"
                       :color       (:text-secondary tokens)}}
        (str duration-ms "ms")]])))

(defn- handler-row
  "Second domino: the handler-ran emit. `ev` is the `:run-end` trace
  event.

  ## Tier dot (rf2-6ja23)

  When `:duration-ms` is present on the emit's `:tags`, render the
  perf-tier coloured dot + label alongside the raw `:phase` text.
  Same ladder as the Performance panel (`theme.perf-tier`); the
  raw `:duration-ms` number is kept in the EDN dump for power users."
  [ev]
  (let [duration-ms (get-in ev [:tags :duration-ms])]
    [domino-row {:label "handler" :tone :cyan}
     [:div
      [:div {:style {:display     "flex"
                     :align-items "center"
                     :flex-wrap   "wrap"}}
       [:span (inspector/inspect (select-keys (:tags ev) [:phase :duration-ms])
                                 "event-detail/handler-tags")]
       (tier-dot duration-ms)]
      (coord-line ev)]]))

(defn- fx-row
  "Third domino: the `:event/do-fx` emit (the effects map about to be
  walked)."
  [ev]
  [domino-row {:label "fx" :tone :cyan}
   [:div
    [:div (inspector/inspect (:tags ev) "event-detail/fx-tags")]
    (coord-line ev)]])

(defn- db-changed-events
  "Pluck the `:event/db-changed` traces from a cascade's `:other`
  bucket. Per Spec 002 §Drain-loop pseudocode every `:db` commit
  emits one `:event/db-changed` trace; the projection routes those
  to `:other` (they don't fit a domino slot per Spec 009
  §`:op-type` vocabulary). For the EFFECTS row this is the only
  cascade-side signal that the handler returned a `:db` effect — no
  `:rf.fx/handled` is emitted for `:db` because the commit happens
  in the interceptor chain, not in the fx walk."
  [other]
  (filter (fn [ev]
            (and (= :event (:op-type ev))
                 (= :event/db-changed (:operation ev))))
          other))

(defn effects-entries
  "Build the EFFECTS row's display entries from a cascade. Combines:

    1. `:event/db-changed` traces (if any) — surfaced as a virtual
       `:db` entry per rf2-s0s5x Phase A so events that committed
       only a `:db` effect (the common `reg-event-db` case) don't
       lie with `EFFECTS (none)`.
    2. `:op-type :fx` traces from the `:effects` slot — every fx
       handler invocation (`:rf.fx/handled` /
       `:rf.fx/override-applied` / `:rf.fx/skipped-on-platform`).

  Returns a sequence of `{:fx-id <kw> :operation <kw> :id <int>}`
  display rows in cascade order (`:db` first, then fx vector
  entries). Empty when neither signal is present."
  [cascade]
  (let [db-rows (for [ev (db-changed-events (:other cascade))]
                  {:fx-id     :db
                   :operation (:operation ev)
                   :id        (:id ev)
                   :ev        ev})
        fx-rows (for [ev (:effects cascade)]
                  {:fx-id     (get-in ev [:tags :fx-id])
                   :operation (:operation ev)
                   :id        (:id ev)
                   :ev        ev})]
    (concat db-rows fx-rows)))

(defn- effects-row
  "Fourth domino: the effects the cascade actually committed. Renders
  a stacked list of fx-id + operation, with a virtual `:db` row when
  the cascade emitted `:event/db-changed` (the cascade-side signal
  for the `:db` effect — see `db-changed-events`).

  Per rf2-s0s5x Phase A: previously this row read only the
  projection's `:effects` slot (built from `:op-type :fx` events),
  which left pure `reg-event-db` handlers showing `(none)` even
  though `:db` had been committed — the EFFECTS lie the bead calls
  out. The composite `effects-entries` folds in `:event/db-changed`
  from the `:other` bucket so the row reflects the cascade's actual
  outcome."
  [cascade]
  (let [entries (effects-entries cascade)]
    [domino-row {:label "effects" :tone :green}
     (if (empty? entries)
       [:span {:data-testid "rf-causa-event-detail-effects-none"
               :style {:color (:text-tertiary tokens)}}
        "(none)"]
       (into [:div {:data-testid "rf-causa-event-detail-effects"}]
             (for [{:keys [fx-id operation id]} entries]
               ^{:key id}
               [:div {:data-testid (str "rf-causa-event-detail-effects-row-"
                                        (name (or fx-id :unknown)))
                      :style {:padding "2px 0"}}
                [:span {:style {:color (:accent-violet tokens) :margin-right "8px"}}
                 (str fx-id)]
                [:span {:style {:color (:text-secondary tokens)}}
                 (str operation)]])))]))

(defn- subs-row
  "Fifth domino: subscription work (`:sub/run` + `:sub/create`)."
  [subs]
  [domino-row {:label "subs" :tone :cyan}
   (if (empty? subs)
     [:span {:style {:color (:text-tertiary tokens)}} "(none)"]
     (into [:div]
           (for [ev subs]
             ^{:key (:id ev)}
             [:div {:style {:padding "2px 0"}}
              [:span {:style {:color (:accent-violet tokens) :margin-right "8px"}}
               (str (get-in ev [:tags :sub-id]))]
              [:span {:style {:color (:text-tertiary tokens)
                              :font-size "11px"}}
               (str (:operation ev))]])))])

(defn- renders-row
  "Sixth domino: view renders."
  [renders]
  [domino-row {:label "renders" :tone :magenta}
   (if (empty? renders)
     [:span {:style {:color (:text-tertiary tokens)}} "(none)"]
     (into [:div]
           (for [ev renders]
             ^{:key (:id ev)}
             [:div {:style {:padding "2px 0"
                            :color   (:text-primary tokens)}}
              (inspector/inspect (get-in ev [:tags :render-key])
                                 (str "event-detail/render/" (:id ev)))])))])

(defn- other-row
  "Spec 009 §`:op-type` vocabulary calls out non-domino traces (errors,
  warnings, machine transitions, etc.). Surfaced under a final row so
  the cascade record is fully accountable to its input."
  [other]
  (when (seq other)
    [domino-row {:label "other" :tone :yellow}
     (into [:div]
           (for [ev other]
             ^{:key (:id ev)}
             [:div {:style {:padding "2px 0"}}
              [:span {:style {:color (:yellow tokens) :margin-right "8px"}}
               (str (:op-type ev))]
              [:span {:style {:color (:text-secondary tokens)}}
               (str (:operation ev))]]))]))

;; ---- cascade list (when no dispatch-id selected) ------------------------

(defn- cascade-list-row
  "One row in the cascade-list view. Clicking the row fires the
  `:rf.causa/select-dispatch-id` event-db so the panel switches into
  cascade-detail mode."
  [{:keys [dispatch-id frame event] :as _cascade}]
  [:li {:key       [frame dispatch-id]
        :data-testid (str "rf-causa-cascade-row-" dispatch-id)
        :on-click   #(rf/dispatch
                       [:rf.causa/select-dispatch-id dispatch-id frame]
                       {:frame :rf/causa})
        :style      {:padding      "8px 12px"
                     :border-bottom (str "1px solid " (:border-subtle tokens))
                     :cursor       "pointer"
                     :font-family  mono-stack
                     :font-size    "13px"
                     :color        (:text-primary tokens)}}
   [:span {:style {:color (:accent-violet tokens) :margin-right "8px"}}
    (str "#" dispatch-id)]
   (when frame
     [:span {:style {:color (:text-tertiary tokens) :margin-right "8px"}}
      (str frame)])
   (format-edn (or event :ungrouped))])

(defn- cascade-list
  "Empty-state list of cascades for the user to click into. Until the
  actions panel lands (rf2-5yriz) this is the primary way to select a
  dispatch-id.

  Silent-by-default (rf2-b9f6z) — no prose; the panel reflects the L2
  event-list focus like every other panel. When the buffer has
  cascades, the list itself is the only affordance; when the buffer is
  empty, the container renders empty."
  [cascades]
  [:div {:data-testid "rf-causa-event-detail-empty"
         :style       {:padding "16px"}}
   (when (seq cascades)
     (overflow/capped-list
       cascades
       {:panel-id "event-detail"
        :ul-attrs {:data-testid "rf-causa-cascade-list"
                   :style {:list-style "none"
                           :margin     0
                           :padding    0
                           :border     (str "1px solid " (:border-subtle tokens))
                           :border-radius "4px"
                           :background (:bg-3 tokens)}}
        :row-fn   cascade-list-row}))])

;; ---- cascade detail (when a dispatch-id is selected) --------------------

(defn- cascade-detail
  "The six-domino cascade for the selected dispatch-id. `cascade` is a
  single cascade record per `re-frame.trace.projection/group-cascades`.

  When the cascade contains managed-fx invocations (per
  `panels/managed-fx-helpers/cascade->managed-fx-records`), the
  wire-boundary diff template (rf2-uyp86 / F-C2) mounts below the
  six-domino window as an inline `<div>` per record. Cascades without
  managed-fx invocations render unchanged."
  [{:keys [dispatch-id frame event handler fx subs renders other] :as cascade}]
  (let [managed-fx-records (managed-fx-h/cascade->managed-fx-records cascade)]
    [:div {:data-testid "rf-causa-event-detail-cascade"
           :data-dispatch-id (str dispatch-id)
           :data-frame (str frame)
           :data-managed-fx-count (str (count managed-fx-records))
           :style       {:padding "8px 0"}}
     [:div {:style {:padding "0 12px 8px 12px"
                    :font-family sans-stack
                    :font-size   "12px"
                    :color       (:text-tertiary tokens)}}
      [:span "Cascade"]]
     [:div {:style {:border-top (str "1px solid " (:border-subtle tokens))}}
      [event-row event (trigger-handler-coord (or handler fx))]
      (when handler [handler-row handler])
      (when fx [fx-row fx])
      [effects-row cascade]
      [subs-row subs]
      [renders-row renders]
      (other-row other)]
     (when (seq managed-fx-records)
       (managed-fx/records-list managed-fx-records))]))

;; ---- public view --------------------------------------------------------

(rf/reg-view Panel
  "The hero panel's root view. Subscribes to
  `:rf.causa/event-detail` and renders either the cascade-detail
  layout (when a dispatch-id is selected) or the cascade-list empty
  state (when not).

  Note: this panel has no internal back-link affordance — under the
  4-layer chrome (rf2-lv9bc) the L2 event list is always visible
  alongside the L4 detail, so a 'back to events' affordance is
  meaningless. Selection is cleared by clicking a different cascade
  row, or programmatically via `:rf.causa/clear-selected-dispatch-id`."
  []
  (let [{:keys [selected-dispatch-id selected-dispatch-frame selected-cascade cascades]}
        @(rf/subscribe [:rf.causa/event-detail])]
    [:section {:data-testid "rf-causa-event-detail"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     [:header {:style {:padding "16px 16px 8px 16px"}}
      [:h1 {:style {:font-size   "16px"
                    :font-weight 600
                    :margin      0
                    :color       (:text-primary tokens)}}
       "Event detail"]]
     [:div {:style {:flex 1 :overflow "auto"}}
      (cond
        (and selected-dispatch-id selected-cascade)
        (cascade-detail selected-cascade)

        selected-dispatch-id
        [:div {:data-testid "rf-causa-event-detail-orphaned"
               :style       {:padding "16px"
                             :color   (:text-tertiary tokens)
                             :font-family sans-stack
                             :font-size "13px"}}
         "Selected dispatch-id "
         [:code {:style {:color (:accent-violet tokens) :font-family mono-stack}}
          (str selected-dispatch-id)]
         (when selected-dispatch-frame
           [:span " in frame "
            [:code {:style {:color (:accent-violet tokens) :font-family mono-stack}}
             (str selected-dispatch-frame)]])
         " is no longer in the trace buffer. Pick another cascade from the event list."]

        :else
        (cascade-list cascades))]]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Event Detail panel's Causa-side
  registrations (Phase 2, rf2-op3bz). Owns the selection slot the
  panel + its cross-panel `:rf.causa/cascades` consumers read off:

    - `:rf.causa/selected-dispatch-id` sub (cascade selection)
    - `:rf.causa/event-detail` composite sub
    - `:rf.causa/select-dispatch-id` event
    - `:rf.causa/clear-selected-dispatch-id` event

  The cross-panel `:rf.causa/cascades` projection itself lives in
  `registry.cljs` — it is shared with the Causality Graph and
  Performance panels."
  []
  ;; The dispatch-id the user has drilled into. nil = empty state
  ;; (cascade list, per spec/007-UX-IA.md §The default landing view).
  (rf/reg-sub :rf.causa/selected-dispatch-id
    (fn [db _query]
      (:dispatch-id (selected-ref (or (get db :selected-dispatch)
                                      (get db :selected-dispatch-id))))))

  (rf/reg-sub :rf.causa/selected-dispatch-frame
    (fn [db _query]
      (:frame (selected-ref (or (get db :selected-dispatch)
                                (get db :selected-dispatch-id))))))

  ;; Event-detail composite — produces everything the panel needs in
  ;; one read so the view stays a thin renderer. Shape:
  ;;
  ;;     {:cascades             [...]   ; all cascades, oldest first
  ;;      :selected-dispatch-id <id>    ; nil when no selection
  ;;      :selected-cascade     {...}}  ; nil when no selection
  ;;                                    ; OR when the id is no
  ;;                                    ; longer in the buffer
  ;;
  ;; The projection runs against the live buffer on every recompute.
  ;; Per spec/007-UX-IA.md §Performance budget the panel renders at
  ;; most ~200 cascades; the projection is O(n) over the buffer.
  ;;
  ;; ## Spine-driven focus (rf2-s0s5x Phase A)
  ;;
  ;; The composite reads the EFFECTIVE focused dispatch-id off the
  ;; spine sub (`:rf.causa/focus`) rather than the legacy
  ;; `:selected-dispatch-id` slot. The spine composer auto-advances
  ;; the effective id to head in `:live` mode (rf2-s0s5x §Live
  ;; auto-follow); reading the legacy slot would leave this panel
  ;; pinned to a stale id that `focus-cascade-reducer` last wrote.
  ;; The legacy slot remains as a shim for older direct readers (the
  ;; orphaned-branch caption, MCP probes) but the focused-cascade
  ;; computation here is canonically off the spine.
  (rf/reg-sub :rf.causa/event-detail
    ;; Signal layer: depend on the shared `:rf.causa/cascades`
    ;; projection + the spine focus so this composite recomputes
    ;; when either changes. The `:<-` chain is the only sub-
    ;; registration form in v2 (per Spec 002 §Subscriptions composing
    ;; — reg-sub-raw is dropped; see `re-frame.subs/parse-reg-sub-args`).
    ;;
    ;; Per rf2-639lc Bug 2: when the user has not explicitly selected a
    ;; cascade (legacy `:selected-dispatch-id` slot nil), default-focus
    ;; the head cascade so the L4 Event tab renders cascade DETAIL on
    ;; first mount rather than the cascade-LIST landing view (the L2
    ;; event list is the list affordance under the 4-layer chrome;
    ;; rf2-lv9bc folded the panel-internal list into a landing
    ;; placeholder, but on first mount with a populated buffer the
    ;; user expects the detail of the latest event). The head-fallback
    ;; lives at the composite layer (not the view) so consumers of
    ;; `:rf.causa/event-detail` (the panel + its tests) all see the
    ;; same effective selection.
    :<- [:rf.causa/cascades]
    :<- [:rf.causa/focus]
    (fn [[cascades focus] _query]
      ;; Phase A spine wiring: read the EFFECTIVE focused id off the
      ;; spine sub. The spine composer already snaps to head in :live
      ;; mode (compose-focus, rf2-s0s5x), so this composite no longer
      ;; needs the legacy head-fallback when the slot is nil.
      ;;
      ;; rf2-639lc Bug 1 preserved: if the spine landed on `:ungrouped`
      ;; (the projection's catch-all bucket for registry-time emits /
      ;; frame lifecycle outside a drain), fall back to the most recent
      ;; ROUTED cascade so the L4 default-focus never lands on the
      ;; projection's internal bucket. The spine's head-cascade picks
      ;; the structural last entry; this panel-side filter keeps the
      ;; user-visible default focused on a real cascade.
      (let [focus-id       (:dispatch-id focus)
            focus-frame    (:frame focus)
            ungrouped?     (= :ungrouped focus-id)
            head           (when (or (nil? focus-id) ungrouped?)
                             (default-head-cascade cascades))
            selected-id    (cond
                             ungrouped?      (:dispatch-id head)
                             (nil? focus-id) (:dispatch-id head)
                             :else           focus-id)
            selected-frame (cond
                             ungrouped?      (:frame head)
                             (nil? focus-id) (:frame head)
                             :else           focus-frame)
            selection      (when selected-id
                             {:dispatch-id selected-id
                              :frame       selected-frame})
            by-id          (when selection
                             (some #(when (cascade-matches-selection? % selection) %)
                                   cascades))]
        {:cascades                cascades
         :selected-dispatch-id    selected-id
         :selected-dispatch-frame selected-frame
         :selected-cascade        by-id})))

  ;; Spine shim (rf2-adve5) — `:rf.causa/select-dispatch-id` is the
  ;; legacy entry point used by causality / machine-inspector /
  ;; issues-ribbon / performance / routes / schema-violation-timeline
  ;; / trace / mcp-server. It now writes through the spine via the
  ;; same reducer the spec-018 `:rf.causa/focus-cascade` event uses,
  ;; so a click in any of those existing panels updates the
  ;; `:rf.causa/focus` sub the next-generation Layer-2 event list
  ;; will read. The reducer also continues writing the
  ;; `:selected-dispatch-id` + `:selected-dispatch` legacy slots so
  ;; this panel's own sub graph (and every consumer of those subs)
  ;; keeps reading what it always has — the shim is transparent.
  (rf/reg-event-db :rf.causa/select-dispatch-id
    (fn [db [_ dispatch-id frame-id]]
      (let [history (get db :epoch-history [])
            epoch-id (spine/epoch-id-for-cascade history dispatch-id)]
        (spine/focus-cascade-reducer db dispatch-id frame-id epoch-id))))

  ;; Programmatic clear of the focused cascade. Resets the spine focus
  ;; back to LIVE (head-tracking) per the rf2-s0s5x Phase A semantics —
  ;; clearing means "go back to following the live stream", same
  ;; outcome the user gets by clicking the LIVE pill.
  (rf/reg-event-db :rf.causa/clear-selected-dispatch-id
    (fn [db _event]
      (-> db
          (dissoc :selected-dispatch :selected-dispatch-id :selected-epoch-id)
          (update :focus (fnil assoc {})
                  :dispatch-id nil
                  :epoch-id    nil
                  :mode        :live
                  :previewing? false)))))
