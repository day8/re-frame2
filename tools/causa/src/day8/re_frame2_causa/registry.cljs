(ns day8.re-frame2-causa.registry
  "Causa's framework registrations — events, subs, fxs under the
  `:rf.causa/*` namespace prefix.

  ## Namespace prefix is the collision contract

  The registrar is process-global; Causa's registrations share the
  registry with the host app. The `:rf.causa/*` prefix is the
  collision-avoidance contract: Causa never registers under a
  non-`:rf.causa/*` keyword, so a host registering `:user/login` and
  Causa registering `:rf.causa/buffer-cleared` cannot stamp on each
  other.

  ## Registrations target the `:rf/causa` frame

  The panel's state lives in a frame named `:rf/causa` — a sibling of
  the host's `:rf/default`. Subscribers / dispatchers wrapped inside
  `[rf/frame-provider {:frame :rf/causa} ...]` resolve to that frame;
  a Causa view subscribing to `:rf.causa/trace-buffer` reads
  `:rf/causa`'s app-db, not the host's. Prefix prevents id collision;
  frame-provider prevents db reads/writes from leaking into the host.

  ## Catalogue

  The registrar wires the full Causa surface: trace-buffer, selected-
  panel / dispatch-id, the event-detail / causality-graph / time-
  travel / app-db-diff / machine-inspector / schema-violation-timeline
  / hydration-debugger / issues-ribbon / effects / flows / routes /
  subscriptions / trace / mcp-server / performance / co-pilot
  composites, and the small mutating event-db / event-fx surface that
  drives the pins, scrubbing, slice focus, clipboard copy, restore-
  epoch and reset-frame-db! effects. See the inline section markers
  below — and the per-panel `*-helpers` namespaces for the data
  primitives the composites consume."
  (:require [re-frame.core :as rf]
            [re-frame.trace.projection :as projection]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.ai-co-pilot :as ai-co-pilot]
            [day8.re-frame2-causa.panels.time-travel-helpers :as tt-helpers]
            [day8.re-frame2-causa.panels.causality-graph-helpers :as cg-helpers]
            [day8.re-frame2-causa.panels.app-db-diff-helpers :as diff-helpers]
            [day8.re-frame2-causa.panels.effects-helpers :as effects-helpers]
            [day8.re-frame2-causa.panels.flows-helpers :as flows-helpers]
            [day8.re-frame2-causa.panels.hydration-debugger-helpers :as hd-helpers]
            [day8.re-frame2-causa.panels.issues-ribbon-helpers :as issues-helpers]
            [day8.re-frame2-causa.panels.machine-inspector-helpers :as machine-helpers]
            [day8.re-frame2-causa.panels.mcp-server-helpers :as mcp-helpers]
            [day8.re-frame2-causa.panels.performance-helpers :as perf-helpers]
            [day8.re-frame2-causa.panels.routes-helpers :as routes-helpers]
            [day8.re-frame2-causa.panels.schema-violation-timeline-helpers :as svt-helpers]
            [day8.re-frame2-causa.panels.subscriptions-helpers :as subs-helpers]
            [day8.re-frame2-causa.panels.trace-helpers :as trace-helpers]))

;; ---- defaults ------------------------------------------------------------

(def default-panel-id
  "The hero panel — `:event-detail` — is Causa's default landing per
  spec/007-UX-IA.md §The default landing view + §10 Lock 7. Exposed
  as a Var so the shell and tests share the source of truth."
  :event-detail)

(def default-target-frame
  "The host frame Causa's time-travel scrubber inspects by default.
  Per spec/002-Time-Travel.md §Cross-frame scrubbing the scrubber is
  per-frame — once a frame picker ships it lets the user pick a
  different host frame. Until then the scrubber is hard-bound to
  :rf/default — the canonical host frame per Tool-Pair §Frame
  naming.

  Note this is the *host*'s frame, not :rf/causa — Causa's own
  state (selection, pin store) lives in :rf/causa via the shell's
  frame-provider; the *target* of restore-epoch / reset-frame-db! is
  the host's :rf/default."
  :rf/default)

;; ---- subscriptions -------------------------------------------------------

;; Causa's trace-buffer sub returns the Causa-side ring buffer
;; contents (NOT the framework's `(rf/trace-buffer)`). Reading directly
;; from `trace-bus/buffer` is the right shape here because the buffer
;; is process-global, not per-frame — every Causa shell mounted across
;; any frame should see the same trace stream. The sub thunks the
;; pure-data accessor so reactive contexts get a fresh read on every
;; recompute; layer-1 re-fires on the host's next dispatch and picks
;; up whatever the trace-cb has accumulated. See `trace_bus.cljc`
;; §Reactivity for the trade-off and the refactor path that would
;; re-introduce immediate-update reactivity without the layering
;; hazard.
(defonce ^:private registered?
  ;; Idempotency sentinel. Re-loading the namespace (shadow-cljs
  ;; `:after-load`) must not re-register the sub (would harmlessly
  ;; replace the handler, but emits a `:rf.warning/handler-replaced`
  ;; trace that pollutes the dev console on every reload).
  (atom false))

(defn register-causa-handlers!
  "Idempotent registration of Causa's :rf.causa/* events, subs, fxs.
  Called from `day8.re-frame2-causa.preload` at load time. Safe to
  call multiple times — second + subsequent calls are no-ops."
  []
  (when (compare-and-set! registered? false true)
    ;; ---- subs ----------------------------------------------------
    (rf/reg-sub :rf.causa/trace-buffer
      (fn [_db _query]
        (trace-bus/buffer)))

    ;; Total count of :sensitive? trace events the collector has
    ;; suppressed under the current `:trace/show-sensitive?` setting
    ;; (rf2-azls9). The shell's bottom-rail renders a `[● REDACTED N]`
    ;; hint when this is positive so the user sees why the buffer is
    ;; shorter than the runtime's actual emit count.
    ;;
    ;; Per rf2-0vxdn the counter lives in Causa's app-db at
    ;; `:suppressed-counters` ({frame-id → count}); `config/note-
    ;; suppressed!` dispatches `:rf.causa/note-sensitive-suppressed`
    ;; in CLJS, so the sub fires on the standard app-db-write
    ;; reactive path and the bottom-rail re-renders IMMEDIATELY —
    ;; no dependency on sibling subs recomputing. The plain
    ;; `config/suppressed-counters` atom remains as the JVM-runnable
    ;; data primitive (sensitive_trace CLJC tests + trace-bus' JVM
    ;; data-shape coverage); the CLJS path dual-writes via dispatch
    ;; so the reactive surface stays consistent.
    (rf/reg-sub :rf.causa/suppressed-sensitive-count
      (fn [db _query]
        (reduce + 0 (vals (get db :suppressed-counters {})))))

    ;; Currently-active panel — drives the canvas's switch logic in
    ;; shell.cljs. Default is the hero panel per §10 Lock 7.
    (rf/reg-sub :rf.causa/selected-panel
      (fn [db _query]
        (get db :selected-panel default-panel-id)))

    ;; The dispatch-id the user has drilled into. nil = empty state
    ;; (cascade list, per spec/007-UX-IA.md §The default landing view).
    (rf/reg-sub :rf.causa/selected-dispatch-id
      (fn [db _query]
        (get db :selected-dispatch-id)))

    ;; Shared cascade projection. The event-detail, causality-graph,
    ;; and performance composites all consume `projection/group-cascades`
    ;; over the same trace-buffer; routing them through one intermediate
    ;; sub collapses three O(buffer) passes per push to one. Each
    ;; downstream composite declares the dependency via `:<-` so the
    ;; reactive graph stays correct (and idle composites still don't pay
    ;; for the projection).
    (rf/reg-sub :rf.causa/cascades
      :<- [:rf.causa/trace-buffer]
      (fn [buffer _query]
        (projection/group-cascades buffer)))

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
    (rf/reg-sub :rf.causa/event-detail
      ;; Signal layer: depend on the shared `:rf.causa/cascades`
      ;; projection + selected-dispatch-id so this composite recomputes
      ;; when either changes. The `:<-` chain is the only sub-
      ;; registration form in v2 (per Spec 002 §Subscriptions composing
      ;; — reg-sub-raw is dropped; see `re-frame.subs/parse-reg-sub-args`).
      :<- [:rf.causa/cascades]
      :<- [:rf.causa/selected-dispatch-id]
      (fn [[cascades selected-id] _query]
        (let [by-id (when selected-id
                      (some #(when (= selected-id (:dispatch-id %)) %)
                            cascades))]
          {:cascades             cascades
           :selected-dispatch-id selected-id
           :selected-cascade     by-id})))

    ;; ---- Phase 3 (rf2-t53ze) — Time Travel scrubber subs ---------

    ;; Target frame the scrubber inspects. Hard-bound to :rf/default
    ;; until a frame picker lands; the sub abstracts so the picker can
    ;; drop in without rewiring every consumer.
    (rf/reg-sub :rf.causa/target-frame
      (fn [db _query]
        (get db :target-frame default-target-frame)))

    ;; Cached snapshot of the target frame's epoch history, pumped
    ;; by `:rf.causa/epoch-recorded` (dispatched from the epoch-cb in
    ;; preload). The cache is necessary because rf/epoch-history is a
    ;; side-effecting read of the epoch artefact's atom — a sub fn
    ;; can call it but the sub graph won't re-fire when the atom
    ;; mutates. Routing history through Causa's app-db makes the sub
    ;; reactive on its own write path.
    (rf/reg-sub :rf.causa/epoch-history
      (fn [db _query]
        (get db :epoch-history [])))

    ;; The view's currently-selected epoch — nil = newest (no scrub
    ;; in flight). Per spec §The passive-scrubbing rule, scrubbing
    ;; rebases panels but does NOT rewind app-db.
    (rf/reg-sub :rf.causa/selected-epoch-id
      (fn [db _query]
        (get db :selected-epoch-id)))

    ;; Per-frame pin store, keyed by target-frame. Persisted into
    ;; Causa's app-db only — never localStorage / disk (Lock 4 per
    ;; spec §Session-scoped — pins do not survive reload).
    (rf/reg-sub :rf.causa/pin-store
      (fn [db _query]
        (get db :pin-store {})))

    ;; The pin vector for the current target-frame — a flat sequence
    ;; the view iterates. Decoupled from :rf.causa/pin-store so the
    ;; view doesn't re-render when an unrelated frame's pins mutate.
    (rf/reg-sub :rf.causa/pinned-snapshots
      :<- [:rf.causa/pin-store]
      :<- [:rf.causa/target-frame]
      (fn [[pin-store target-frame] _query]
        (tt-helpers/pins-for-frame pin-store target-frame)))

    ;; Composite for the panel — one read produces every slot the
    ;; view needs. Mirrors the Phase-2 `:rf.causa/event-detail`
    ;; composite shape. The :chip-states projection runs chip-state
    ;; over each pin against the current history so detached pins
    ;; carry the visible signal per spec §Pins on the scrubber.
    (rf/reg-sub :rf.causa/time-travel
      :<- [:rf.causa/target-frame]
      :<- [:rf.causa/epoch-history]
      :<- [:rf.causa/selected-epoch-id]
      :<- [:rf.causa/pinned-snapshots]
      (fn [[target-frame history selected-id pins] _query]
        (let [selected-record (when selected-id
                                (tt-helpers/find-epoch-in-history
                                  history selected-id))]
          {:target-frame    target-frame
           :history         history
           :selected-epoch-id selected-id
           :selected-record selected-record
           :selected-index  (tt-helpers/epoch-index-in-history
                              history selected-id)
           :pins            pins
           :chip-states     (tt-helpers/chip-states history pins)
           :cap-reached?    (>= (count pins) tt-helpers/default-pin-cap)})))

    ;; ---- Phase 4 (rf2-4rqs1) — Causality Graph composite sub -----
    ;;
    ;; The graph reads from the same trace-buffer as the event-detail
    ;; panel. It projects the buffer via group-cascades, enriches each
    ;; cascade with its :event/dispatched trace event (so :origin /
    ;; :parent-dispatch-id are available), then folds into nodes +
    ;; arrows and computes a top-down layout. When the Time Travel
    ;; scrubber has a selected-epoch whose settling cascade-id is in
    ;; the graph, the graph filters to that cascade family.
    ;;
    ;; Shape:
    ;;
    ;;     {:graph                {:nodes [...] :arrows [...] ...}
    ;;      :layout               {:positions {...} :width :height ...}
    ;;      :selected-dispatch-id <id-or-nil>
    ;;      :selected-epoch-id    <id-or-nil>
    ;;      :filtered?            <bool>}
    ;;
    ;; Per spec §Performance the v1 helper runs O(n) over the buffer.
    ;; The composite recomputes when any of its signals change — the
    ;; reactive surface is the same as the event-detail composite.
    (rf/reg-sub :rf.causa/causality-graph-data
      ;; The graph still depends on the raw `trace-buffer` for the
      ;; `enrich-cascades` walk (it surfaces `:event/dispatched` traces
      ;; that aren't preserved in the projected cascade vector). The
      ;; cascade vector itself is read from the shared
      ;; `:rf.causa/cascades` projection so the O(buffer) `group-
      ;; cascades` pass happens once per push instead of three times.
      :<- [:rf.causa/cascades]
      :<- [:rf.causa/trace-buffer]
      :<- [:rf.causa/selected-dispatch-id]
      :<- [:rf.causa/selected-epoch-id]
      :<- [:rf.causa/epoch-history]
      (fn [[cascades buffer selected-id selected-epoch-id history] _query]
        (let [enriched         (cg-helpers/enrich-cascades cascades buffer)
              graph            (cg-helpers/project-cascades-to-graph enriched)
              ;; When Time Travel's selected-epoch resolves to a
              ;; cascade-id, filter the graph to that cascade family.
              epoch-record     (when selected-epoch-id
                                 (tt-helpers/find-epoch-in-history
                                   history selected-epoch-id))
              cascade-id-filter (some-> epoch-record
                                        cg-helpers/dispatch-id-of-epoch)
              filterable?      (and cascade-id-filter
                                    (some #(= cascade-id-filter (:dispatch-id %))
                                          (:nodes graph)))
              graph'           (if filterable?
                                 (cg-helpers/filter-to-cascade
                                   graph cascade-id-filter)
                                 graph)
              layout           (cg-helpers/compute-layout graph')]
          {:graph                graph'
           :layout               layout
           :selected-dispatch-id selected-id
           :selected-epoch-id    selected-epoch-id
           :filtered?            (boolean filterable?)})))

    ;; ---- Phase 5 (rf2-htffa) — Schema-violation Timeline subs ----
    ;;
    ;; Per tools/causa/spec/005-Schema-Timeline.md the panel reads
    ;; from three streams:
    ;;
    ;;   1. (rf/app-schemas frame-id)  — registered schemas → rows
    ;;   2. trace-buffer filtered to :rf.error/schema-validation-
    ;;      failure → dots
    ;;   3. the panel's own selection state — selected violation +
    ;;      schema filter + time-axis window
    ;;
    ;; The composite below produces one map the view consumes per
    ;; render. Per spec §Performance the projection is O(visible-
    ;; violations) — typically 0–5.

    ;; Registered schemas for the target frame — projected as a
    ;; vector of `path-or-id` row keys. `rf/app-schemas` returns a
    ;; `{path → schema}` map; the helper just iterates the map's
    ;; entry-keys to keep the row ordering stable. Returns [] when
    ;; the schemas artefact is not on the classpath.
    (rf/reg-sub :rf.causa/registered-schemas
      :<- [:rf.causa/target-frame]
      (fn [[target-frame] _query]
        (try
          (vec (keys (or (rf/app-schemas {:frame target-frame}) {})))
          (catch :default _
            []))))

    ;; The view's currently-selected violation id (the trace event's
    ;; :id, stable per-process). nil = no selection; the detail
    ;; side-panel hides.
    (rf/reg-sub :rf.causa/selected-violation-id
      (fn [db _query]
        (get db :selected-violation-id)))

    ;; Schema filter — when set, the panel narrows its rendered rows
    ;; to the matching schema only. Per spec §Per-violation detail
    ;; the 'Show me all violations of this schema' action sets this.
    (rf/reg-sub :rf.causa/schema-filter
      (fn [db _query]
        (get db :schema-filter)))

    ;; Time-axis window state — `{:t0 :t1}` in ms. nil falls back to
    ;; the default 60s window ending at now. Per spec §Layout the
    ;; window synchronises with the Trace panel's time-axis when
    ;; both are visible; the shared slot is what makes the
    ;; synchronisation a single-source-of-truth read.
    (rf/reg-sub :rf.causa/schema-timeline-window
      (fn [db _query]
        (or (get db :schema-timeline-window)
            (svt-helpers/default-window))))

    ;; Projected violations filtered to the current window. Returns
    ;; a vector of projected violation rows in chronological order
    ;; (oldest first); the composite groups by schema downstream.
    ;; Per spec §Aging out — out-of-window violations drop from the
    ;; rendered set without leaving the underlying buffer.
    (rf/reg-sub :rf.causa/schema-violations-window
      :<- [:rf.causa/trace-buffer]
      :<- [:rf.causa/schema-timeline-window]
      (fn [[buffer window] _query]
        (let [all (svt-helpers/project-violations buffer)]
          (filterv #(svt-helpers/violation-in-window? window %) all))))

    ;; Cache of the previously-rendered rows so the panel's flash
    ;; cue (per spec §Reading the timeline at a glance) can detect
    ;; the empty→non-empty transition. Updated on each composite
    ;; recompute; the view reads :first? off each row.
    (rf/reg-sub :rf.causa/schema-timeline-prev-rows
      (fn [db _query]
        (get db :schema-timeline-prev-rows)))

    ;; The composite the view consumes. Shape:
    ;;
    ;;     {:rows                [<row> ...]      ;; per-schema rows
    ;;      :window              {:t0 :t1}
    ;;      :total-violations    <int>            ;; pre-filter count
    ;;      :rendered-violations <int>            ;; post-filter
    ;;      :selected-violation  <projected|nil>
    ;;      :schema-filter       <schema-id|nil>}
    ;;
    ;; Per spec §Per-violation detail — `:selected-violation` is the
    ;; full projected row (resolved from :selected-violation-id); nil
    ;; when no selection OR when the id is no longer in the buffer.
    (rf/reg-sub :rf.causa/schema-violation-timeline
      :<- [:rf.causa/registered-schemas]
      :<- [:rf.causa/schema-violations-window]
      :<- [:rf.causa/schema-timeline-window]
      :<- [:rf.causa/selected-violation-id]
      :<- [:rf.causa/schema-filter]
      :<- [:rf.causa/schema-timeline-prev-rows]
      (fn [[schemas violations window selected-id schema-filter prev-rows] _query]
        (let [;; If a schema-filter is set, narrow registered-schemas
              ;; to that single row. Violations are filtered to the
              ;; same schema-id.
              schemas'    (if (some? schema-filter)
                            (filterv #(= % schema-filter) schemas)
                            schemas)
              violations' (if (some? schema-filter)
                            (filterv #(= schema-filter (:schema-id %))
                                     violations)
                            violations)
              rows        (svt-helpers/project-rows
                            schemas' violations' window prev-rows)
              selected    (when selected-id
                            (svt-helpers/find-violation violations selected-id))]
          {:rows                 rows
           :window               window
           :total-violations    (count violations)
           :rendered-violations (count violations')
           :selected-violation   selected
           :schema-filter        schema-filter})))

    ;; ---- Phase 5 (rf2-jps1o) — App-DB Diff subs ------------------

    ;; The host frame's current app-db value. Read via rf/get-frame-db
    ;; against the Phase 3 :rf.causa/target-frame. Wrapped in a sub so
    ;; the panel reacts to host writes via the same reactive surface as
    ;; everything else; the sub fn itself is side-effecting (rf/get-
    ;; frame-db hits a frame atom), but the reactivity is driven by
    ;; epoch-history updates pumped into Causa's app-db on every settle.
    (rf/reg-sub :rf.causa/target-frame-db
      :<- [:rf.causa/target-frame]
      :<- [:rf.causa/epoch-history]
      (fn [[target _epoch-history] _query]
        ;; Depend on :rf.causa/epoch-history so the sub re-fires on
        ;; every settled epoch. The actual read is via rf/get-frame-db
        ;; — the framework's canonical accessor.
        (rf/get-frame-db target)))

    ;; The selected epoch's record from :rf.causa/epoch-history. nil
    ;; when no selection or the selection has aged out of the ring
    ;; buffer.
    (rf/reg-sub :rf.causa/selected-epoch-record
      :<- [:rf.causa/epoch-history]
      :<- [:rf.causa/selected-epoch-id]
      (fn [[history selected-id] _query]
        (when selected-id
          (tt-helpers/find-epoch-in-history history selected-id))))

    ;; The diff triples for the currently-selected epoch. When no
    ;; epoch is selected, the diff is between the newest epoch's
    ;; :db-before and :db-after (the most recent settle).
    ;;
    ;; Per spec §Changed-paths derivation the diff is computed
    ;; lazily on panel mount and the result cached per :epoch-id.
    ;; The composite sub recomputes only when the underlying
    ;; (history × selection) tuple changes — re-renders of the same
    ;; epoch return identical?-equal triples, which is the v1
    ;; caching model.
    (rf/reg-sub :rf.causa/selected-epoch-diff
      :<- [:rf.causa/epoch-history]
      :<- [:rf.causa/selected-epoch-id]
      (fn [[history selected-id] _query]
        (let [record (if selected-id
                       (tt-helpers/find-epoch-in-history history selected-id)
                       (peek (vec history)))]
          (when record
            (diff-helpers/diff-paths (:db-before record)
                                     (:db-after  record))))))

    ;; The pinned-slices store — `{frame-id [path-1 path-2 ...]}`.
    ;; Separate from Phase 3's :pin-store (which pins whole epoch
    ;; snapshots); this is per-frame slice-path pinning.
    (rf/reg-sub :rf.causa/pinned-slices-store
      (fn [db _query]
        (get db :pinned-slices-store {})))

    ;; The live-derefed pinned slices for the current target-frame.
    ;; Each entry is `{:path <vec> :value <current-value>}`.
    (rf/reg-sub :rf.causa/pinned-slices
      :<- [:rf.causa/pinned-slices-store]
      :<- [:rf.causa/target-frame]
      :<- [:rf.causa/target-frame-db]
      (fn [[store target db] _query]
        (diff-helpers/live-pinned-slices store target db)))

    ;; The 'Show me when this changed' focused path. nil when no
    ;; focus is in flight; the view falls back to the slice mini-
    ;; panels in that case.
    (rf/reg-sub :rf.causa/focused-slice-path
      (fn [db _query]
        (get db :focused-slice-path)))

    ;; The 'Show me when this changed' result — a vector of hit
    ;; maps for epochs that touched the focused path. Empty vector
    ;; when no focus is set or no epoch in the ring buffer touched
    ;; the path.
    (rf/reg-sub :rf.causa/show-me-when-this-changed-result
      :<- [:rf.causa/focused-slice-path]
      :<- [:rf.causa/epoch-history]
      (fn [[focused-path history] _query]
        (if focused-path
          (diff-helpers/epochs-touching-path history focused-path)
          [])))

    ;; Top-level composite for the App-DB Diff panel. One read
    ;; produces every slot the view needs (matches the Phase-2 /
    ;; Phase-3 / Phase-4 composite pattern).
    (rf/reg-sub :rf.causa/app-db-diff
      :<- [:rf.causa/target-frame]
      :<- [:rf.causa/target-frame-db]
      :<- [:rf.causa/selected-epoch-diff]
      :<- [:rf.causa/pinned-slices]
      :<- [:rf.causa/focused-slice-path]
      :<- [:rf.causa/show-me-when-this-changed-result]
      :<- [:rf.causa/epoch-history]
      (fn [[target db diff-triples pinned focused-path focused-hits history]
           _query]
        (let [history-empty? (empty? history)
              {:keys [reserved non-reserved]}
              (diff-helpers/partition-reserved (or diff-triples []))]
          {:target-frame          target
           :history-empty?        history-empty?
           :changed-non-reserved  non-reserved
           ;; The [runtime] group always renders the current `:rf/*`
           ;; slot contents off the current db as a one-line summary
           ;; (path + value). Per spec §Reserved-keys group the group
           ;; is informational — it's not gated on whether THIS epoch
           ;; touched a reserved key; the programmer reads it to
           ;; orient against the runtime's state.
           :changed-reserved      (diff-helpers/reserved-summary db)
           :pinned-slices         pinned
           :focused-path          focused-path
           :focused-hits          focused-hits})))

    ;; ---- events --------------------------------------------------
    (rf/reg-event-db :rf.causa/select-panel
      (fn [db [_ panel-id]]
        (assoc db :selected-panel panel-id)))

    ;; Bump the per-frame suppressed-events counter (rf2-0vxdn).
    ;; Dispatched from `trace-bus/collect-trace!` (CLJS) under
    ;; `:rf/causa` whenever the privacy gate drops a `:sensitive? true`
    ;; trace event. `frame-id` is the event's `:tags :frame` (the host
    ;; frame the trace targeted); `nil` falls under `:global`. Drives
    ;; the bottom-rail `[● REDACTED N]` indicator via the
    ;; `:rf.causa/suppressed-sensitive-count` sub — fully reactive.
    ;;
    ;; Per rf2-qsjda: `:rf.trace/no-emit? true` opts the handler out of
    ;; framework trace emission. Without this, the dispatch fired by
    ;; `trace-bus/collect-trace!` would itself emit `:event/dispatched`
    ;; etc. back through the trace-cb fan-out, the collector would see
    ;; its own self-emit, and the cascade would loop until
    ;; `drain-depth-default` terminated it. The framework now
    ;; short-circuits emission at the `emit!` / `emit-error!` /
    ;; `emit-dispatched-trace!` gates — the predecessor Causa-side
    ;; `self-emitted?` guard (rf2-nk01x) is obsolete.
    (rf/reg-event-db :rf.causa/note-sensitive-suppressed
      {:rf.trace/no-emit? true}
      (fn [db [_ frame-id]]
        (update-in db [:suppressed-counters (or frame-id :global)]
                   (fnil inc 0))))

    ;; Reset the suppressed-events counter (rf2-0vxdn). With no arg,
    ;; clears every bucket; with a `frame-id`, drops just that bucket.
    ;; Dispatched from `trace-bus/clear-buffer!` (CLJS) — clearing the
    ;; trace ring buffer also drops the REDACTED indicator state (the
    ;; "you missed N events" overhang disappears alongside the events
    ;; that produced it).
    ;;
    ;; Per rf2-qsjda: `:rf.trace/no-emit? true` (see
    ;; `:rf.causa/note-sensitive-suppressed` above for the rationale).
    (rf/reg-event-db :rf.causa/reset-suppressed-counters
      {:rf.trace/no-emit? true}
      (fn [db [_ frame-id]]
        (if frame-id
          (update db :suppressed-counters dissoc (or frame-id :global))
          (dissoc db :suppressed-counters))))

    (rf/reg-event-db :rf.causa/select-dispatch-id
      (fn [db [_ dispatch-id]]
        (assoc db :selected-dispatch-id dispatch-id)))

    (rf/reg-event-db :rf.causa/clear-selected-dispatch-id
      (fn [db _event]
        (dissoc db :selected-dispatch-id)))

    ;; ---- Phase 3 (rf2-t53ze) — Time Travel scrubber events -------

    ;; Pump the latest epoch-history snapshot for the target frame
    ;; into Causa's app-db. Dispatched from the epoch-cb registered
    ;; in preload.cljs on every settled epoch. We don't pass the
    ;; vector across the dispatch boundary — we re-read from the
    ;; framework's `rf/epoch-history` so the snapshot is always
    ;; consistent with the framework's view (the cb fires AFTER the
    ;; record is appended; a stale arg would be off-by-one only on
    ;; the boundary, but threading the live read keeps the contract
    ;; simple).
    (rf/reg-event-db :rf.causa/epoch-recorded
      (fn [db [_ frame-id]]
        (let [target (get db :target-frame default-target-frame)]
          (if (= frame-id target)
            (assoc db :epoch-history (vec (rf/epoch-history target)))
            db))))

    ;; Set the view's selected-epoch (passive scrub). Per spec §The
    ;; passive-scrubbing rule — this DOES NOT call restore-epoch.
    (rf/reg-event-db :rf.causa/select-epoch
      (fn [db [_ epoch-id]]
        (assoc db :selected-epoch-id epoch-id)))

    (rf/reg-event-db :rf.causa/clear-selected-epoch
      (fn [db _event]
        (dissoc db :selected-epoch-id)))

    ;; Pin the epoch at `epoch-id` under the current target-frame
    ;; with `label`. The handler eagerly copies :db-after off the
    ;; live history record (per spec §What a pin captures — eager
    ;; capture). Enforces the 32-pin cap; surfaces `:overflow?` via
    ;; the toast slot the view reads on next render.
    (rf/reg-event-db :rf.causa/pin-current
      (fn [db [_ epoch-id label]]
        (let [target  (get db :target-frame default-target-frame)
              history (vec (or (get db :epoch-history)
                               (rf/epoch-history target)))
              record  (tt-helpers/find-epoch-in-history history epoch-id)
              pin     (tt-helpers/pin-from-epoch record label)]
          (if (some? pin)
            (let [{:keys [store overflow? dropped-pin]}
                  (tt-helpers/pin-snapshot (get db :pin-store {})
                                           target pin)]
              (cond-> (assoc db :pin-store store)
                overflow? (assoc :pin-overflow-toast
                                 {:dropped-label (:label dropped-pin)
                                  :ts            (.getTime (js/Date.))})))
            db))))

    ;; Drop a pin from the current target-frame's pin store.
    (rf/reg-event-db :rf.causa/unpin
      (fn [db [_ epoch-id]]
        (let [target (get db :target-frame default-target-frame)]
          (update db :pin-store
                  tt-helpers/unpin-snapshot target epoch-id))))

    ;; Inline-rename a pin's label. The 4-tuple's other slots are
    ;; immutable (spec §Pin actions §Rename pin).
    (rf/reg-event-db :rf.causa/rename-pin
      (fn [db [_ epoch-id new-label]]
        (let [target (get db :target-frame default-target-frame)]
          (update db :pin-store
                  tt-helpers/rename-pin target epoch-id new-label))))

    ;; Dismiss the cap-reached toast surface.
    (rf/reg-event-db :rf.causa/dismiss-pin-overflow-toast
      (fn [db _] (dissoc db :pin-overflow-toast)))

    ;; ---- write effects (the two confirmed-rewind paths) ----------

    ;; Reset to current epoch — uses restore-epoch (the ring-buffer
    ;; path). Per spec §The passive-scrubbing rule §rewind = explicit:
    ;; this is the confirmed-rewind branch. Per re-frame v2's reg-fx
    ;; contract (Spec API.md §reg-fx) the handler signature is
    ;; (fn [ctx args] ...).
    (rf/reg-fx :rf.causa.fx/restore-epoch
      (fn [_ctx {:keys [frame-id epoch-id]}]
        (rf/restore-epoch frame-id epoch-id)))

    ;; Reset to pinned — uses reset-frame-db! (the value-direct path).
    ;; Per spec §Why reset-frame-db! not restore-epoch — pins hold the
    ;; value directly, so the rewind works even after the underlying
    ;; epoch ages out of the ring buffer.
    (rf/reg-fx :rf.causa.fx/reset-frame-db!
      (fn [_ctx {:keys [frame-id frame-db]}]
        (rf/reset-frame-db! frame-id frame-db)))

    (rf/reg-event-fx :rf.causa/reset-to-epoch
      (fn [{:keys [db]} [_ epoch-id]]
        (let [target (get db :target-frame default-target-frame)]
          ;; Per Spec MIGRATION §Effect map shape — re-frame2's canonical
          ;; fx return is `{:db ... :fx [[fx-id args] ...]}`. Top-level
          ;; effect keys other than :db / :fx are not part of the
          ;; contract; the registered fx is invoked via the :fx vector.
          {:fx [[:rf.causa.fx/restore-epoch
                 {:frame-id target :epoch-id epoch-id}]]})))

    (rf/reg-event-fx :rf.causa/reset-to-pinned
      (fn [{:keys [db]} [_ epoch-id]]
        (let [target (get db :target-frame default-target-frame)
              pin    (tt-helpers/find-pin (get db :pin-store {})
                                          target epoch-id)]
          (when pin
            {:fx [[:rf.causa.fx/reset-frame-db!
                   {:frame-id target :frame-db (:frame-db pin)}]]}))))

    ;; ---- Phase 5 (rf2-pzxsr) — Hydration Debugger panel ---------
    ;;
    ;; Per `tools/causa/spec/006-Hydration-Debugger.md` the panel is
    ;; dormant until at least one :rf.ssr/hydration-mismatch trace
    ;; lands; once it does, the composite sub surfaces the mismatch
    ;; list + the selected-mismatch detail (per spec §Multi-mismatch
    ;; case + §Layout). The panel reads from the same trace-buffer as
    ;; every other Causa panel — Spec 011 §Hydration-mismatch
    ;; detection is the source of the trace events.

    ;; Currently-selected mismatch id (the trace event's :id). nil =
    ;; no selection → composite picks the latest mismatch.
    (rf/reg-sub :rf.causa/selected-mismatch-id
      (fn [db _query]
        (get db :selected-mismatch-id)))

    ;; Re-root path for the side-by-side tree view (per spec §Render-
    ;; tree hash bisector — click any hash chip → the panel re-roots
    ;; at that node). nil = render the full subtree from the mismatch
    ;; trace event's :path.
    (rf/reg-sub :rf.causa/hydration-reroot-path
      (fn [db _query]
        (get db :hydration-reroot-path)))

    ;; The composite the panel consumes. Shape:
    ;;
    ;;     {:has-mismatch?        <bool>
    ;;      :mismatch-summary     [{:id :path :summary ...} ...]
    ;;      :selected-mismatch-id <id-or-nil>
    ;;      :detail               {:path :server-tree :client-tree
    ;;                             :divergence-kind :hypothesis
    ;;                             :bisector-path ...}
    ;;      :source-coord         {:coord :annotation}
    ;;      :re-root-path         <path-or-nil>
    ;;      :target-frame         <frame-id>}
    ;;
    ;; Per spec §Frame awareness the panel shows mismatches for the
    ;; active frame. The frame picker isn't wired in Phase 5 — the
    ;; composite reads `:target-frame` (the same key the time-travel
    ;; scrubber uses) and falls back to nil (= all frames) when the
    ;; user hasn't pinned one. The behaviour matches Phase 3 / 4 —
    ;; the picker drops in without rewiring consumers.
    (rf/reg-sub :rf.causa/hydration-debugger-data
      :<- [:rf.causa/trace-buffer]
      :<- [:rf.causa/selected-mismatch-id]
      :<- [:rf.causa/hydration-reroot-path]
      :<- [:rf.causa/target-frame]
      (fn [[buffer selected-id reroot-path target-frame] _query]
        (let [;; Frame-awareness: when the panel is showing mismatches
              ;; for one frame the summary filters; when no frame is
              ;; pinned every mismatch surfaces. The view's header
              ;; reads :target-frame to label the active filter.
              ;;
              ;; The default surface ships with target-frame =
              ;; :rf/default (the canonical host frame); cross-frame
              ;; swimlanes ride a follow-on once a picker lands.
              summary           (hd-helpers/mismatch-list-summary
                                  buffer target-frame)
              has-mismatch?     (boolean (seq summary))
              ;; Selection-resolution: prefer the user's explicit
              ;; selection; fall back to the latest mismatch.
              latest-id         (some-> (last summary) :id)
              resolved-id       (or (and selected-id
                                         (some #(when (= selected-id (:id %)) %)
                                               summary)
                                         selected-id)
                                    latest-id)
              selected-trace    (when resolved-id
                                  (hd-helpers/select-mismatch buffer resolved-id))
              detail            (hd-helpers/mismatch-detail selected-trace)
              source-coord      (hd-helpers/source-coord-for-mismatch
                                  selected-trace)]
          {:has-mismatch?         has-mismatch?
           :mismatch-summary      summary
           :selected-mismatch-id  resolved-id
           :detail                detail
           :source-coord          source-coord
           :re-root-path          reroot-path
           :target-frame          target-frame})))

    ;; ---- Phase 5 (rf2-pzxsr) — Hydration Debugger events --------

    ;; Select a specific mismatch — drives the side-by-side rebase
    ;; per spec §Multi-mismatch case.
    (rf/reg-event-db :rf.causa/select-mismatch
      (fn [db [_ mismatch-id]]
        (-> db
            (assoc :selected-mismatch-id mismatch-id)
            ;; New selection → drop the re-root (the path is
            ;; subtree-specific).
            (dissoc :hydration-reroot-path))))

    (rf/reg-event-db :rf.causa/clear-mismatch-selection
      (fn [db _event]
        (-> db
            (dissoc :selected-mismatch-id)
            (dissoc :hydration-reroot-path))))

    ;; Re-root the side-by-side tree view at `path` per spec
    ;; §Render-tree hash bisector. Click a hash chip → this fires.
    (rf/reg-event-db :rf.causa/reroot-tree-view
      (fn [db [_ path]]
        (if (empty? path)
          (dissoc db :hydration-reroot-path)
          (assoc db :hydration-reroot-path (vec path)))))

    ;; Open-in-editor stub. The full handler lives in
    ;; `open-in-editor.cljs` (rf2-evgf5); this event-db is a thin
    ;; record-the-attempt so the panel can surface a UX cue when the
    ;; user clicks a source-coord. The actual editor jump runs via
    ;; the open-in-editor module's effect when wired.
    (rf/reg-event-db :rf.causa/open-in-editor
      (fn [db [_ coord]]
        (assoc db :last-open-in-editor-coord coord)))

    ;; ---- Phase 5 (rf2-x0f5v) — Subscriptions panel --------------
    ;;
    ;; The panel reads three surfaces — the target frame's live
    ;; sub-cache (`(rf/sub-cache target-frame)` — CLJS-only), the
    ;; just-settled epoch's :sub-runs projection (lifted off the
    ;; newest record in :rf.causa/epoch-history), and Causa's own
    ;; per-sub error cache (populated from `:op-type :error` trace
    ;; events that carry a :sub-id) — and folds them via
    ;; `subs-helpers/project-rows` into one row per cached sub.
    ;;
    ;; Per spec/012-Subscriptions.md §JVM behaviour the panel is
    ;; CLJS-only; on JVM the sub-cache read returns nil and the
    ;; panel renders its empty state.
    ;;
    ;; Shape of `:rf.causa/subscriptions-data`:
    ;;
    ;;     {:rows             [<row> ...]
    ;;      :status-counts    {status count}
    ;;      :total            <int>
    ;;      :selected-query-v <query-v-or-nil>
    ;;      :active-filters   #{:fresh :re-running ...}
    ;;      :chain-open?      <bool>
    ;;      :chain            {<chain projection> or nil}}

    ;; Read the target frame's live sub-cache. CLJS-only — on JVM
    ;; `rf/sub-cache` returns nil; the panel's empty state surfaces
    ;; in that case. Tests stub the sub-cache by writing to
    ;; `:sub-cache-override` on Causa's app-db (via
    ;; `:rf.causa/set-sub-cache-override-for-test`) so the suite can
    ;; assert against a deterministic cache shape without booting a
    ;; substrate's reactive cache.
    (rf/reg-sub :rf.causa/sub-cache
      (fn [db _query]
        (let [target (get db :target-frame default-target-frame)
              ov     (get db :sub-cache-override)]
          (or ov (rf/sub-cache target)))))

    ;; Test-only override hook for the sub-cache reader. Production
    ;; code paths never dispatch this — the override slot exists
    ;; only so JVM + node-test suites can drive the projection
    ;; without a live substrate.
    (rf/reg-event-db :rf.causa/set-sub-cache-override-for-test
      (fn [db [_ ov]]
        (if (nil? ov)
          (dissoc db :sub-cache-override)
          (assoc db :sub-cache-override ov))))

    ;; The Causa-internal per-sub error cache. Populated on every
    ;; :op-type :error trace event that carries a :sub-id. The shape
    ;; is {query-v <error-info>} — the helper treats any non-nil
    ;; value as 'this sub threw'. v1 wiring keeps the cache empty
    ;; until a downstream bead lands the error-collector plumbing;
    ;; the panel is read-ready today.
    (rf/reg-sub :rf.causa/sub-error-cache
      (fn [db _query]
        (get db :sub-error-cache {})))

    ;; The user's per-panel sub selection (drives the chain affordance).
    (rf/reg-sub :rf.causa/selected-sub
      (fn [db _query]
        (get db :selected-sub)))

    ;; The active filter chip set (per spec §Filtering and grouping).
    (rf/reg-sub :rf.causa/sub-filters
      (fn [db _query]
        (get db :sub-filters #{})))

    ;; Whether the chain affordance is open in the panel.
    (rf/reg-sub :rf.causa/sub-chain-open?
      (fn [db _query]
        (boolean (get db :sub-chain-open?))))

    ;; The composite — one read produces every slot the panel
    ;; consumes. Per spec §Performance the projection is O(rows) +
    ;; O(depth-1) for the chain; the cache shape is small (per-frame
    ;; materialised subs cap at the substrate's own reactive graph
    ;; size) so the fold runs cheaply on every recompute.
    (rf/reg-sub :rf.causa/subscriptions-data
      :<- [:rf.causa/sub-cache]
      :<- [:rf.causa/epoch-history]
      :<- [:rf.causa/sub-error-cache]
      :<- [:rf.causa/selected-sub]
      :<- [:rf.causa/sub-filters]
      :<- [:rf.causa/sub-chain-open?]
      (fn [[sub-cache history error-cache selected-q-v filters chain-open?]
           _query]
        (let [latest-epoch    (peek (vec history))
              sub-runs        (:sub-runs latest-epoch)
              ;; v1 has no first-class :changed-paths slot on the
              ;; epoch-record yet (a follow-on will surface it); fall
              ;; back to nil so the chain shows every layer-1 input
              ;; path it knows about.
              changed-paths   (:changed-paths latest-epoch)
              rows            (subs-helpers/project-rows
                                sub-cache sub-runs error-cache)
              counts          (subs-helpers/status-counts rows)
              chain           (when (and chain-open? selected-q-v)
                                (subs-helpers/compute-chain
                                  selected-q-v sub-cache sub-runs
                                  error-cache changed-paths))]
          {:rows             rows
           :status-counts    counts
           :total            (count rows)
           :selected-query-v selected-q-v
           :active-filters   (or filters #{})
           :chain-open?      (boolean chain-open?)
           :chain            chain})))

    ;; ---- Phase 5 (rf2-x0f5v) — Subscriptions panel events ------

    (rf/reg-event-db :rf.causa/select-sub
      (fn [db [_ query-v]]
        (assoc db :selected-sub query-v)))

    (rf/reg-event-db :rf.causa/clear-selected-sub
      (fn [db _event]
        (-> db
            (dissoc :selected-sub)
            (dissoc :sub-chain-open?))))

    (rf/reg-event-db :rf.causa/toggle-sub-filter
      (fn [db [_ status]]
        (let [current (get db :sub-filters #{})]
          (assoc db :sub-filters
                 (if (contains? current status)
                   (disj current status)
                   (conj current status))))))

    (rf/reg-event-db :rf.causa/show-invalidation-chain
      (fn [db [_ query-v]]
        (cond-> (assoc db :sub-chain-open? true)
          query-v (assoc :selected-sub query-v))))

    (rf/reg-event-db :rf.causa/hide-invalidation-chain
      (fn [db _event]
        (dissoc db :sub-chain-open?)))

    ;; ---- Phase 5 (rf2-htffa) — Schema-violation Timeline events --

    ;; Clear the selected-violation-id (closes the detail side
    ;; panel). Per spec §Per-violation detail — the close affordance
    ;; on the detail panel header fires this.
    (rf/reg-event-db :rf.causa/clear-violation-selection
      (fn [db _event]
        (dissoc db :selected-violation-id)))

    ;; Select a violation by its trace-event :id (which is stable
    ;; per-process per Spec 009 §Trace event shape). The detail side
    ;; panel renders when this is set + the violation is still in
    ;; the buffer. Setting nil clears the selection (parity with
    ;; clear-violation-selection — the panel's dot click handler
    ;; uses select, the close button uses clear).
    (rf/reg-event-db :rf.causa/select-violation
      (fn [db [_ violation-id]]
        (if (some? violation-id)
          (assoc db :selected-violation-id violation-id)
          (dissoc db :selected-violation-id))))

    ;; Set the per-schema filter — narrows the rendered rows to one
    ;; schema. Per spec §Per-violation detail the 'Show me all
    ;; violations of this schema' action dispatches this. Passing
    ;; nil clears the filter (the Clear filter button in the
    ;; header).
    (rf/reg-event-db :rf.causa/set-schema-filter
      (fn [db [_ schema-id]]
        (if (some? schema-id)
          (assoc db :schema-filter schema-id)
          (dissoc db :schema-filter))))

    ;; Set the time-axis window. Per spec §Layout drag-zoom expands
    ;; the window; setting nil reverts to the default 60s window
    ;; ending at now (the composite sub reads default-window when
    ;; the slot is absent).
    (rf/reg-event-db :rf.causa/set-schema-timeline-window
      (fn [db [_ window]]
        (if (and (map? window)
                 (number? (:t0 window))
                 (number? (:t1 window))
                 (< (:t0 window) (:t1 window)))
          (assoc db :schema-timeline-window window)
          (dissoc db :schema-timeline-window))))

    ;; ---- Phase 5 (rf2-jps1o) — App-DB Diff events ----------------

    ;; Pin a slice path to the per-frame pinned-slices store. Per
    ;; spec §Pinned slices. Duplicates are dropped at the helper
    ;; layer (re-pin is a no-op).
    (rf/reg-event-db :rf.causa/pin-slice
      (fn [db [_ path]]
        (let [target (get db :target-frame default-target-frame)]
          (update db :pinned-slices-store
                  diff-helpers/pin-path target path))))

    (rf/reg-event-db :rf.causa/unpin-slice
      (fn [db [_ path]]
        (let [target (get db :target-frame default-target-frame)]
          (update db :pinned-slices-store
                  diff-helpers/unpin-path target path))))

    ;; Replace the per-frame pin order with `new-order`. The caller
    ;; (the drag-reorder UI) computes the permutation.
    (rf/reg-event-db :rf.causa/reorder-pinned-slices
      (fn [db [_ new-order]]
        (let [target (get db :target-frame default-target-frame)]
          (update db :pinned-slices-store
                  diff-helpers/reorder-paths target new-order))))

    ;; Set the 'Show me when this changed' focused path. The
    ;; :rf.causa/show-me-when-this-changed-result sub re-fires
    ;; against the new focus and the panel switches into result-list
    ;; mode (per spec §'Show me when this changed').
    (rf/reg-event-db :rf.causa/focus-slice-path
      (fn [db [_ path]]
        (assoc db :focused-slice-path path)))

    (rf/reg-event-db :rf.causa/clear-slice-focus
      (fn [db _event]
        (dissoc db :focused-slice-path)))

    ;; The clipboard fx — best-effort write via the browser
    ;; clipboard API. On non-browser targets (Node test, JVM) the
    ;; effect is a no-op; tests assert the fx fires, not the OS-side
    ;; outcome. Per re-frame v2's reg-fx contract: (fn [ctx args] ...).
    (rf/reg-fx :rf.causa.fx/copy-to-clipboard
      (fn [_ctx {:keys [text]}]
        (try
          (when (and (exists? js/navigator)
                     (.-clipboard js/navigator))
            (.writeText (.-clipboard js/navigator) (str text)))
          (catch :default _ nil))))

    (rf/reg-event-fx :rf.causa/copy-value-to-clipboard
      (fn [_ctx [_ value]]
        {:fx [[:rf.causa.fx/copy-to-clipboard {:text (pr-str value)}]]}))

    (rf/reg-event-fx :rf.causa/copy-path-to-clipboard
      (fn [_ctx [_ path]]
        {:fx [[:rf.causa.fx/copy-to-clipboard {:text (pr-str path)}]]}))

    ;; ---- Phase 5 (rf2-d1p4o) — Issues ribbon panel ---------------------
    ;;
    ;; Per `tools/causa/spec/000-Vision.md` L94 + spec/009-Instrumentation.md
    ;; §Error event catalogue the panel is the unified feed across errors,
    ;; warnings, schema violations, and hydration mismatches. It reads
    ;; from the same trace-buffer as every other panel; the helpers
    ;; classify each event into the ribbon's three severity buckets
    ;; (:error / :warning / :advisory) and project the per-row shape
    ;; (timestamp · category · severity · short description · jump-to-
    ;; source).
    ;;
    ;; Filter axes per the bead's minimum-viable contract:
    ;;
    ;;   :severities  #{:error :warning :advisory}
    ;;   :prefixes    #{"rf.error" "rf.warning" "rf.ssr" ...}
    ;;   :since-ms    relative time window in ms (nil = no restriction)
    ;;
    ;; Each axis is independent; empty filter sets / nil :since-ms
    ;; disable the axis.
    ;;
    ;; Shape of `:rf.causa/issues-ribbon`:
    ;;
    ;;     {:issues               [<row> ...]      ;; post-filter
    ;;      :total                <int>            ;; pre-filter count
    ;;      :rendered             <int>            ;; post-filter count
    ;;      :severity-counts      {sev count}
    ;;      :distinct-prefixes    [<prefix> ...]
    ;;      :filters              <pass-through>
    ;;      :empty-kind           <:no-issues / :no-matches / nil>}

    ;; Active filter state — the panel reads the three slots through
    ;; one sub so the view re-renders atomically when filters change.
    (rf/reg-sub :rf.causa/issues-filters
      (fn [db _query]
        {:severities (get db :issues-active-severities #{})
         :prefixes   (get db :issues-active-prefixes #{})
         :since-ms   (get db :issues-since-ms)}))

    ;; Composite — produces every slot the view consumes. The
    ;; helper's `project-feed` does the heavy lifting; the sub is a
    ;; thin wrapper that injects `now-ms` (so the since-ms axis is
    ;; meaningful) and reads the trace-buffer + filter state through
    ;; the reactive surface.
    (rf/reg-sub :rf.causa/issues-ribbon
      :<- [:rf.causa/trace-buffer]
      :<- [:rf.causa/issues-filters]
      (fn [[buffer filters] _query]
        (issues-helpers/project-feed buffer filters (issues-helpers/now-ms))))

    ;; ---- Issues ribbon events --------------------------------------

    ;; Toggle a severity chip in/out of the active filter set. Per
    ;; the bead's contract each axis is independent.
    (rf/reg-event-db :rf.causa/toggle-issues-severity
      (fn [db [_ severity]]
        (let [current (get db :issues-active-severities #{})]
          (assoc db :issues-active-severities
                 (if (contains? current severity)
                   (disj current severity)
                   (conj current severity))))))

    ;; Toggle a category-prefix chip. Same shape as the severity
    ;; toggle — multi-select set; empty set = no restriction.
    (rf/reg-event-db :rf.causa/toggle-issues-prefix
      (fn [db [_ prefix]]
        (let [current (get db :issues-active-prefixes #{})]
          (assoc db :issues-active-prefixes
                 (if (contains? current prefix)
                   (disj current prefix)
                   (conj current prefix))))))

    ;; Set the since-ms axis from a seconds-typed user input. The
    ;; view converts s → ms here so the helper's filter-application
    ;; stays uniform in ms. nil / non-positive values clear the axis.
    (rf/reg-event-db :rf.causa/set-issues-since-seconds
      (fn [db [_ seconds]]
        (if (and (number? seconds) (pos? seconds))
          (assoc db :issues-since-ms (* (long seconds) 1000))
          (dissoc db :issues-since-ms))))

    ;; Clear every filter axis in one shot. The Clear filters
    ;; affordance in the header + the no-matches empty state both
    ;; fire this.
    (rf/reg-event-db :rf.causa/clear-issues-filters
      (fn [db _event]
        (-> db
            (dissoc :issues-active-severities)
            (dissoc :issues-active-prefixes)
            (dissoc :issues-since-ms))))

    ;; ---- Phase 5 (rf2-83irn) — Flows panel ----------------------------
    ;;
    ;; Surfaces re-frame2's registered flows + their per-flow inputs /
    ;; output path / live recomputation indicator. Consumer of Spec 013
    ;; (the registered-flow surface) + Spec 009 (the `:rf.flow/*` trace
    ;; event vocabulary).
    ;;
    ;; The panel reads two surfaces — the framework's registered-flow
    ;; map (`(rf/handlers :flow)`) and the Causa trace-buffer's
    ;; `:op-type :flow` slice — and folds them via
    ;; `flows-helpers/project-rows` into one row per registered flow.
    ;;
    ;; Tests stub the registered-flows surface by writing
    ;; `:registered-flows-override` to Causa's app-db (via
    ;; `:rf.causa/set-registered-flows-override-for-test`) so the suite
    ;; can assert against a deterministic flow set without booting the
    ;; flows artefact.
    ;;
    ;; Shape of `:rf.causa/flows-data`:
    ;;
    ;;     {:rows             [<row> ...]
    ;;      :status-counts    {status count}
    ;;      :total            <int>
    ;;      :selected-flow-id <flow-id-or-nil>}

    ;; Read the registered-flow map. Reads `(rf/handlers :flow)` —
    ;; per Spec 001 §The public registrar query API the registrar is
    ;; process-global so this surfaces every registered flow across
    ;; every frame. CLJS-only — `rf/handlers` exists under both
    ;; targets, but the v1 wiring threads it through the override
    ;; slot so the JVM test target can drive the projection without
    ;; booting the flows artefact.
    (rf/reg-sub :rf.causa/registered-flows
      (fn [db _query]
        (let [ov (get db :registered-flows-override)]
          (or ov (rf/handlers :flow)))))

    ;; Test-only override hook for the registered-flows surface.
    ;; Production code paths never dispatch this — the slot exists
    ;; only so JVM + node-test suites can drive the projection
    ;; without booting the flows artefact's registrar.
    (rf/reg-event-db :rf.causa/set-registered-flows-override-for-test
      (fn [db [_ ov]]
        (if (nil? ov)
          (dissoc db :registered-flows-override)
          (assoc db :registered-flows-override ov))))

    ;; The Causa trace-buffer's `:op-type :flow` slice. Pure-data
    ;; filter — the helper's predicate is JVM-runnable so tests can
    ;; drive it without a CLJS runtime.
    ;;
    ;; Note the single-signal `:<-` arity: re-frame2's `reg-sub`
    ;; passes the upstream value DIRECTLY (not vector-wrapped) when
    ;; there's exactly one `:<-` chain entry — per Spec 002 §The
    ;; reg-sub forms + subs.cljc parse-reg-sub-args. Multi-signal
    ;; `:<-` chains pass `[a b c]`; single-signal passes `a`.
    (rf/reg-sub :rf.causa/flow-trace-events
      :<- [:rf.causa/trace-buffer]
      (fn [buffer _query]
        (flows-helpers/filter-flow-events buffer)))

    ;; The user's per-panel flow selection. Drives a follow-on
    ;; cross-panel affordance (click flow → event-detail filtered to
    ;; that flow's recent recomputations); v1 wiring carries the
    ;; selection only — the cross-panel jump lands when the
    ;; cross-panel filter API stabilises.
    (rf/reg-sub :rf.causa/selected-flow-id
      (fn [db _query]
        (get db :selected-flow-id)))

    ;; The composite the panel consumes. One read produces every slot
    ;; the view needs (matches the per-panel composite pattern every
    ;; other Causa panel uses).
    (rf/reg-sub :rf.causa/flows-data
      :<- [:rf.causa/registered-flows]
      :<- [:rf.causa/flow-trace-events]
      :<- [:rf.causa/selected-flow-id]
      (fn [[flows-map flow-events selected-flow-id] _query]
        (let [rows   (flows-helpers/project-rows flows-map flow-events)
              counts (flows-helpers/status-counts rows)]
          {:rows             rows
           :status-counts    counts
           :total            (count rows)
           :selected-flow-id selected-flow-id})))

    ;; ---- Phase 5 (rf2-83irn) — Flows panel events --------------------

    (rf/reg-event-db :rf.causa/select-flow-id
      (fn [db [_ flow-id]]
        (assoc db :selected-flow-id flow-id)))

    (rf/reg-event-db :rf.causa/clear-flow-selection
      (fn [db _event]
        (dissoc db :selected-flow-id)))

    ;; ---- Phase 5 (rf2-ts41u) — Effects panel ---------------------------
    ;;
    ;; Surfaces re-frame2's registered fxs + their per-fx invocations,
    ;; outcome status, and stub indicator. Consumer of Spec 002 §reg-fx
    ;; (the registered-fx surface) + Spec 009 (the `:rf.fx/*` trace event
    ;; vocabulary).
    ;;
    ;; The panel reads two surfaces — the framework's registered-fx map
    ;; (`(rf/handlers :fx)`) and the Causa trace-buffer's fx-related
    ;; slice (`:op-type :fx` plus the fx-layer error categories) — and
    ;; folds them via `effects-helpers/project-rows` into one row per
    ;; registered fx.
    ;;
    ;; Tests stub the registered-fx surface by writing
    ;; `:registered-fxs-override` to Causa's app-db (via
    ;; `:rf.causa/set-registered-fxs-override-for-test`) so the suite
    ;; can assert against a deterministic fx set without booting a host
    ;; runtime that registers fxs.
    ;;
    ;; Shape of `:rf.causa/effects-data`:
    ;;
    ;;     {:rows           [<row> ...]
    ;;      :outcome-counts {outcome count}
    ;;      :total          <int>
    ;;      :selected-fx-id <fx-id-or-nil>}

    ;; Read the registered-fx map. Reads `(rf/handlers :fx)` — per
    ;; Spec 001 §The public registrar query API the registrar is
    ;; process-global so this surfaces every registered fx across
    ;; every frame. The v1 wiring threads it through the override
    ;; slot so the JVM test target can drive the projection without
    ;; booting a substrate that registers fxs.
    (rf/reg-sub :rf.causa/registered-fxs
      (fn [db _query]
        (let [ov (get db :registered-fxs-override)]
          (or ov (rf/handlers :fx)))))

    ;; Test-only override hook for the registered-fxs surface.
    ;; Production code paths never dispatch this — the slot exists
    ;; only so JVM + node-test suites can drive the projection
    ;; without booting the fx registrar.
    (rf/reg-event-db :rf.causa/set-registered-fxs-override-for-test
      (fn [db [_ ov]]
        (if (nil? ov)
          (dissoc db :registered-fxs-override)
          (assoc db :registered-fxs-override ov))))

    ;; The Causa trace-buffer's fx-related slice. Pure-data filter —
    ;; the helper's predicate is JVM-runnable so tests can drive it
    ;; without a CLJS runtime.
    ;;
    ;; Note the single-signal `:<-` arity: re-frame2's `reg-sub`
    ;; passes the upstream value DIRECTLY (not vector-wrapped) when
    ;; there's exactly one `:<-` chain entry — per Spec 002 §The
    ;; reg-sub forms + subs.cljc parse-reg-sub-args.
    (rf/reg-sub :rf.causa/fx-trace-events
      :<- [:rf.causa/trace-buffer]
      (fn [buffer _query]
        (effects-helpers/filter-fx-events buffer)))

    ;; The user's per-panel fx selection. Drives a cross-panel
    ;; affordance (click fx → event-detail filtered to that fx's
    ;; recent invocations); v1 wiring carries the selection only —
    ;; the cross-panel jump lands when the cross-panel filter API
    ;; stabilises.
    (rf/reg-sub :rf.causa/selected-fx-id
      (fn [db _query]
        (get db :selected-fx-id)))

    ;; The composite the panel consumes. One read produces every slot
    ;; the view needs (matches the per-panel composite pattern every
    ;; other Causa panel uses).
    (rf/reg-sub :rf.causa/effects-data
      :<- [:rf.causa/registered-fxs]
      :<- [:rf.causa/fx-trace-events]
      :<- [:rf.causa/selected-fx-id]
      (fn [[fxs-map fx-events selected-fx-id] _query]
        (let [rows   (effects-helpers/project-rows fxs-map fx-events)
              counts (effects-helpers/outcome-counts rows)]
          {:rows           rows
           :outcome-counts counts
           :total          (count rows)
           :selected-fx-id selected-fx-id})))

    ;; ---- Phase 5 (rf2-ts41u) — Effects panel events ------------------

    (rf/reg-event-db :rf.causa/select-fx-id
      (fn [db [_ fx-id]]
        (assoc db :selected-fx-id fx-id)))

    (rf/reg-event-db :rf.causa/clear-fx-selection
      (fn [db _event]
        (dissoc db :selected-fx-id)))

    ;; ---- Phase 5 (rf2-75121) — Performance panel -----------------------
    ;;
    ;; Per `tools/causa/spec/000-Vision.md` L92 the Performance panel
    ;; surfaces per-cascade duration capture, perf-tier colour mapping,
    ;; and budget-warning markers. The runtime substrate is
    ;; `spec/009-Instrumentation.md §Performance instrumentation` (the
    ;; default-off User Timing channel); v1 reads the dev-build trace
    ;; stream's `:time` deltas instead so the panel works against the
    ;; same buffer every other Causa panel consumes.
    ;;
    ;; Shape of `:rf.causa/performance-data`:
    ;;
    ;;     {:rows               [<row> ...]    ;; newest first
    ;;      :total              <int>
    ;;      :tier-counts        {tier count}
    ;;      :over-budget-count  <int>
    ;;      :budget-ms          <number>
    ;;      :empty?             <bool>}
    ;;
    ;; No new events are required — the panel reuses
    ;; `:rf.causa/select-dispatch-id` + `:rf.causa/select-panel` for the
    ;; pivot-into-event-detail affordance (parity with the Issues
    ;; ribbon's row-click). The over-budget threshold is sub-readable
    ;; via `:rf.causa/performance-budget-ms` so a follow-on bead can
    ;; surface a slider in the panel header without rewiring consumers.
    (rf/reg-sub :rf.causa/performance-budget-ms
      (fn [db _query]
        (get db :performance-budget-ms perf-helpers/default-budget-ms)))

    (rf/reg-sub :rf.causa/performance-data
      :<- [:rf.causa/cascades]
      :<- [:rf.causa/performance-budget-ms]
      (fn [[cascades budget-ms] _query]
        (perf-helpers/project-feed cascades budget-ms)))

    ;; Set the over-budget threshold. Pass nil to reset to default.
    (rf/reg-event-db :rf.causa/set-performance-budget-ms
      (fn [db [_ budget-ms]]
        (if (and (number? budget-ms) (pos? budget-ms))
          (assoc db :performance-budget-ms budget-ms)
          (dissoc db :performance-budget-ms))))

    ;; ---- Phase 5 (rf2-argrj) — Trace panel ------------------------------
    ;;
    ;; The Trace panel is the UI consumer of the canonical 13-axis filter
    ;; vocabulary documented in spec/009-Instrumentation.md §Filter
    ;; vocabulary. Where the Issues ribbon collapses the stream to issues
    ;; only, this panel surfaces the raw stream so a programmer can grep
    ;; across every op-type / operation / source / origin / frame / etc.
    ;;
    ;; Shape of `:rf.causa/trace-feed`:
    ;;
    ;;     {:rows         [<row> ...]      ;; post-filter, newest first
    ;;      :total        <int>            ;; pre-filter count
    ;;      :rendered     <int>            ;; post-filter count
    ;;      :distinct     {<axis> [...]}   ;; per-axis chip values
    ;;      :counts       {<axis> {<value> <int>}}
    ;;      :filters      <pass-through normalised>
    ;;      :any-filter?  <bool>
    ;;      :empty-kind   <:no-events / :no-matches / nil>}

    ;; The current 13-axis filter map. Each axis is independent; the
    ;; helper's `normalise-filters` drops nil / empty values before
    ;; applying — the view sets axis = nil to clear an axis.
    (rf/reg-sub :rf.causa/trace-filters
      (fn [db _query]
        (get db :trace-filters {})))

    ;; Composite — produces every slot the view consumes. Reactive
    ;; surface: trace-buffer + filter state. The helper's
    ;; `project-feed` does the heavy lifting.
    (rf/reg-sub :rf.causa/trace-feed
      :<- [:rf.causa/trace-buffer]
      :<- [:rf.causa/trace-filters]
      (fn [[buffer filters] _query]
        (trace-helpers/project-feed buffer filters)))

    ;; Set or clear one axis. Passing nil-value clears that axis;
    ;; setting a value replaces any existing value on that axis (the
    ;; chip-filter UI is single-value per axis for v1; multi-value
    ;; rides a follow-on bead).
    (rf/reg-event-db :rf.causa/set-trace-filter
      (fn [db [_ axis value]]
        (let [current (get db :trace-filters {})]
          (assoc db :trace-filters
                 (if (nil? value)
                   (dissoc current axis)
                   (assoc current axis value))))))

    ;; Clear every filter axis in one shot. Wired from the header's
    ;; 'Clear filters' button + the no-matches empty state.
    (rf/reg-event-db :rf.causa/clear-trace-filters
      (fn [db _event]
        (dissoc db :trace-filters)))

    ;; ── mcp-server panel begin ──
    ;;
    ;; Phase 5 (rf2-81qjj) — MCP Server panel
    ;;
    ;; Per `tools/causa/spec/010-MCP-Server.md` §Origin tagging +
    ;; `tools/causa-mcp/spec/Principles.md` §Origin tagging is the
    ;; convention the panel filters the trace-buffer to events tagged
    ;; `:tags :origin :causa-mcp` (the canonical tag the causa-mcp jar
    ;; stamps on every side-effect it performs). The composite is a
    ;; thin wrapper over `mcp-helpers/project-feed`; the panel is a
    ;; read-only feed of agent activity in the host.
    ;;
    ;; Shape of `:rf.causa/mcp-server`:
    ;;
    ;;     {:rows              [<row> ...]
    ;;      :total             <int>
    ;;      :rendered          <int>
    ;;      :op-type-counts    {op-type count}
    ;;      :distinct-op-types [<op-type> ...]
    ;;      :filters           <pass-through>
    ;;      :agent-attached?   <bool>
    ;;      :empty-kind        <:no-activity / :no-matches / nil>}
    ;;
    ;; ## INFERENTIAL DECISIONS (rf2-81qjj — spec-deficient bead)
    ;;
    ;; (a) Dedicated sidebar panel — yes. Parallels every other Phase 5
    ;;     panel; gives users one entry point for 'what is the agent
    ;;     doing'.
    ;; (b) Origin colour for `:causa-mcp` — cyan #06B6D4 (see helpers
    ;;     ns). Distinct from :pair indigo (locked) and :story/:test
    ;;     light-cyan (#43C3D0).
    ;; (c) Bidirectional Causa→agent surface — out of scope (causa-mcp
    ;;     jar implementation concern).
    ;;
    ;; Each is a follow-on bead candidate.

    ;; Active filter state — the panel reads the two slots through one
    ;; sub so the view re-renders atomically when filters change.
    (rf/reg-sub :rf.causa/mcp-filters
      (fn [db _query]
        {:op-types (get db :mcp-active-op-types #{})
         :since-ms (get db :mcp-since-ms)}))

    ;; Composite — produces every slot the view consumes. The
    ;; helper's `project-feed` does the heavy lifting; the sub is a
    ;; thin wrapper that injects `now-ms` (so the since-ms axis is
    ;; meaningful) and reads the trace-buffer + filter state through
    ;; the reactive surface.
    (rf/reg-sub :rf.causa/mcp-server
      :<- [:rf.causa/trace-buffer]
      :<- [:rf.causa/mcp-filters]
      (fn [[buffer filters] _query]
        (mcp-helpers/project-feed buffer filters (mcp-helpers/now-ms))))

    ;; The cross-panel highlight toggle. When true, other panels MAY
    ;; honour this (Trace / Event-detail / Causality) to dim non-agent
    ;; events. Default false — the toggle is an opt-in.
    ;;
    ;; The cross-panel wiring (other panels reading this sub) is a
    ;; follow-on bead; this panel ships the toggle so the surface is
    ;; in place and any consumer that subscribes honours it
    ;; immediately. Filed as: 'Causa: cross-panel :causa-mcp origin
    ;; highlight.'
    (rf/reg-sub :rf.causa/mcp-origin-filter-enabled?
      (fn [db _query]
        (boolean (get db :mcp-origin-filter-enabled? false))))

    ;; ---- MCP Server panel events --------------------------------------

    ;; Toggle an op-type chip in/out of the active filter set.
    (rf/reg-event-db :rf.causa/toggle-mcp-op-type
      (fn [db [_ op-type]]
        (let [current (get db :mcp-active-op-types #{})]
          (assoc db :mcp-active-op-types
                 (if (contains? current op-type)
                   (disj current op-type)
                   (conj current op-type))))))

    ;; Set the since-ms axis from a seconds-typed user input. The
    ;; view converts s → ms here so the helper's filter-application
    ;; stays uniform in ms. nil / non-positive values clear the axis.
    (rf/reg-event-db :rf.causa/set-mcp-since-seconds
      (fn [db [_ seconds]]
        (if (and (number? seconds) (pos? seconds))
          (assoc db :mcp-since-ms (* (long seconds) 1000))
          (dissoc db :mcp-since-ms))))

    ;; Clear every filter axis in one shot. The Clear filters
    ;; affordance in the header + the no-matches empty state both
    ;; fire this.
    (rf/reg-event-db :rf.causa/clear-mcp-filters
      (fn [db _event]
        (-> db
            (dissoc :mcp-active-op-types)
            (dissoc :mcp-since-ms))))

    ;; Toggle the cross-panel origin-filter highlight. Wired from the
    ;; Settings sub-pane checkbox in the MCP panel.
    (rf/reg-event-db :rf.causa/toggle-mcp-origin-filter
      (fn [db _event]
        (update db :mcp-origin-filter-enabled? not)))
    ;; ── mcp-server panel end ──

    ;; ---- Phase 5 (rf2-6blai) — Routes panel ----------------------------
    ;;
    ;; Per `spec/012-Routing.md` the panel surfaces three pieces of the
    ;; routing surface: the registered-route set (`(rf/handlers
    ;; :route)`), the active `:rf/route` slice on the target frame
    ;; (Spec 012 §The `:rf/route` slice), and recent navigation history
    ;; (the `:rf.route.nav-token/*` + `:rf.route/url-changed` trace event
    ;; stream per Spec 012 §Trace events).
    ;;
    ;; Tests stub the registered-routes surface by writing
    ;; `:registered-routes-override` to Causa's app-db (via
    ;; `:rf.causa/set-registered-routes-override-for-test`) and the
    ;; active-route slice via
    ;; `:rf.causa/set-active-route-slice-override-for-test` so the
    ;; suite can assert against a deterministic registry + slice without
    ;; booting a host with `rf/reg-route` calls. Production paths read
    ;; through `rf/handlers` + `rf/get-frame-db` directly.
    ;;
    ;; Shape of `:rf.causa/routes-data`:
    ;;
    ;;     {:rows              [<row> ...]
    ;;      :total             <int>
    ;;      :active-route      <projected-or-nil>
    ;;      :selected-route-id <id-or-nil>
    ;;      :history           [<entry> ...]
    ;;      :empty-kind        <:no-routes / nil>}

    ;; Read the registered-route map. Reads `(rf/handlers :route)` —
    ;; per Spec 001 §The public registrar query API the registrar is
    ;; process-global so this surfaces every registered route across
    ;; every frame. The v1 wiring threads `rf/handlers` through the
    ;; override slot so the JVM test target can drive the projection
    ;; without a populated registrar. The fallback path is wrapped in
    ;; a `try` so a missing-kind exception (older builds without the
    ;; `:route` kind) collapses to an empty registry rather than
    ;; throwing through the sub.
    (rf/reg-sub :rf.causa/registered-routes
      (fn [db _query]
        (let [ov (get db :registered-routes-override)]
          (or ov
              (try (rf/handlers :route)
                   (catch :default _ {}))))))

    ;; Test-only override hook for the registered-routes surface.
    ;; Production code paths never dispatch this — the slot exists
    ;; only so JVM + node-test suites can drive the projection
    ;; without booting a host with `rf/reg-route` calls.
    (rf/reg-event-db :rf.causa/set-registered-routes-override-for-test
      (fn [db [_ ov]]
        (if (nil? ov)
          (dissoc db :registered-routes-override)
          (assoc db :registered-routes-override ov))))

    ;; The active `:rf/route` slice on the target frame. Reads
    ;; `:rf/route` off the target-frame's app-db via the same
    ;; `:rf.causa/target-frame-db` sub the App-DB Diff panel uses, so
    ;; the panel re-fires on every settled epoch on the host frame.
    ;; The override slot mirrors the registered-routes pattern — JVM
    ;; tests write a slice without a live frame; production reads
    ;; through the live frame's app-db.
    (rf/reg-sub :rf.causa/active-route-slice
      :<- [:rf.causa/target-frame-db]
      (fn [target-frame-db _query]
        (when (map? target-frame-db)
          (:rf/route target-frame-db))))

    ;; Override-aware reader. Returns the override when set; falls
    ;; through to the live slice otherwise. Wired this way (rather
    ;; than reading the override inside `:rf.causa/active-route-slice`)
    ;; so test fixtures can override the slice without disturbing the
    ;; target-frame-db chain — important when an integration test
    ;; needs the live target-frame chain wired but wants to inject a
    ;; deterministic slice value.
    (rf/reg-sub :rf.causa/active-route-slice-override
      (fn [db _query]
        (get db :active-route-slice-override)))

    (rf/reg-event-db :rf.causa/set-active-route-slice-override-for-test
      (fn [db [_ ov]]
        (if (nil? ov)
          (dissoc db :active-route-slice-override)
          (assoc db :active-route-slice-override ov))))

    ;; The Causa trace-buffer's route-history slice — filtered to the
    ;; three operations Spec 012 §Trace events enumerates. Pure-data
    ;; filter — the helper's predicate is JVM-runnable so tests can
    ;; drive it without a CLJS runtime.
    (rf/reg-sub :rf.causa/route-history-events
      :<- [:rf.causa/trace-buffer]
      (fn [buffer _query]
        (routes-helpers/filter-history-events buffer)))

    ;; The user's per-panel route selection. Drives the row highlight
    ;; in the registered-routes list. v1 carries the selection only;
    ;; the cross-panel jump (click → open-in-editor at the route's
    ;; source coord) lands when the cross-panel jump API stabilises.
    (rf/reg-sub :rf.causa/selected-route-id
      (fn [db _query]
        (get db :selected-route-id)))

    ;; The composite the panel consumes. One read produces every slot
    ;; the view needs (matches the per-panel composite pattern every
    ;; other Causa panel uses).
    (rf/reg-sub :rf.causa/routes-data
      :<- [:rf.causa/registered-routes]
      :<- [:rf.causa/active-route-slice]
      :<- [:rf.causa/active-route-slice-override]
      :<- [:rf.causa/route-history-events]
      :<- [:rf.causa/selected-route-id]
      (fn [[routes-map live-slice slice-override history-events selected-id]
           _query]
        (let [slice (or slice-override live-slice)]
          (routes-helpers/project-feed
            routes-map slice history-events selected-id))))

    ;; ---- Routes panel events ----------------------------------------

    (rf/reg-event-db :rf.causa/select-route
      (fn [db [_ route-id]]
        (assoc db :selected-route-id route-id)))

    (rf/reg-event-db :rf.causa/clear-route-selection
      (fn [db _event]
        (dissoc db :selected-route-id)))

    ;; ---- Phase 5+ (rf2-r9f9u) — Machine Inspector panel --------------
    ;;
    ;; Per `tools/causa/spec/003-Machine-Inspector.md` the panel
    ;; surfaces every registered machine (via `(rf/machines)` — Spec
    ;; 005 §Querying machines), the live `:rf/machine` snapshot per id
    ;; (via the framework `:rf/machine` sub — Spec 005 §Subscribing to
    ;; machines), and the Causa trace buffer's `:rf.machine/transition`
    ;; slice for the selected machine.
    ;;
    ;; The chart component itself lives in `tools/machines-viz/` per
    ;; `tools/machines-viz/spec/API.md`. At v1 the impl is deferred
    ;; (only the scaffold landed via rf2-x50eu); the panel embeds the
    ;; prop contract through a placeholder component (see panel ns
    ;; docstring for the swap-when-impl-ships plan).
    ;;
    ;; Tests stub the registered-machine + snapshot surfaces by
    ;; writing override slots to Causa's app-db (mirrors the routes
    ;; panel's `:rf.causa/set-registered-routes-override-for-test`
    ;; pattern) so the JVM + node-test suites can drive the projection
    ;; without booting a host with `rf/reg-machine` calls. Production
    ;; paths read through `rf/machines` + `subscribe [:rf/machine id]`
    ;; directly.
    ;;
    ;; Shape of `:rf.causa/machine-inspector-data`:
    ;;
    ;;     {:machines     [<machine-row> ...]
    ;;      :total        <int>
    ;;      :selected-id  <id-or-nil>
    ;;      :selected     <row-or-nil>
    ;;      :chart-props  <props-or-nil>
    ;;      :transitions  [<transition-row> ...]
    ;;      :empty-kind   <:no-machines / nil>}

    ;; Read the registered-machine vector. Reads `(rf/machines)` —
    ;; returns `[]` when the machines artefact is not on the
    ;; classpath (see implementation/core/src/re_frame/core_machines.cljc
    ;; §machines). The fallback path is wrapped in a `try` so any
    ;; future API change collapses to an empty registry rather than
    ;; throwing through the sub.
    (rf/reg-sub :rf.causa/registered-machines
      (fn [db _query]
        (let [ov (get db :registered-machines-override)]
          (or ov
              (try (vec (rf/machines))
                   (catch :default _ []))))))

    ;; Test-only override hook for the registered-machines surface.
    ;; Production code paths never dispatch this — the slot exists
    ;; only so JVM + node-test suites can drive the projection
    ;; without booting a host with `rf/reg-machine` calls.
    (rf/reg-event-db :rf.causa/set-registered-machines-override-for-test
      (fn [db [_ ov]]
        (if (nil? ov)
          (dissoc db :registered-machines-override)
          (assoc db :registered-machines-override ov))))

    ;; The live snapshots map for every registered machine, keyed by
    ;; machine-id. Reads off the target-frame-db's `:rf/machines`
    ;; slot (per Spec 005 §Where snapshots live — `:rf/machines` is
    ;; the reserved app-db key); the test override slot mirrors the
    ;; registered-machines pattern so JVM tests write a snapshot map
    ;; without a live frame.
    (rf/reg-sub :rf.causa/machine-snapshots
      :<- [:rf.causa/target-frame-db]
      (fn [target-frame-db _query]
        (when (map? target-frame-db)
          (get target-frame-db :rf/machines {}))))

    (rf/reg-sub :rf.causa/machine-snapshots-override
      (fn [db _query]
        (get db :machine-snapshots-override)))

    (rf/reg-event-db :rf.causa/set-machine-snapshots-override-for-test
      (fn [db [_ ov]]
        (if (nil? ov)
          (dissoc db :machine-snapshots-override)
          (assoc db :machine-snapshots-override ov))))

    ;; The user's per-panel machine selection (drives the picker
    ;; focus). nil = default to first row.
    (rf/reg-sub :rf.causa/selected-machine-id
      (fn [db _query]
        (get db :selected-machine-id)))

    ;; The composite — one read produces every slot the panel
    ;; consumes (matches the per-panel composite pattern every other
    ;; Causa panel uses).
    (rf/reg-sub :rf.causa/machine-inspector-data
      :<- [:rf.causa/registered-machines]
      :<- [:rf.causa/machine-snapshots]
      :<- [:rf.causa/machine-snapshots-override]
      :<- [:rf.causa/trace-buffer]
      :<- [:rf.causa/selected-machine-id]
      :<- [:rf.causa/target-frame]
      (fn [[machines live-snapshots snapshots-override buffer selected-id target-frame]
           _query]
        (let [snapshots (or snapshots-override live-snapshots {})]
          (machine-helpers/project-data
            machines snapshots buffer selected-id target-frame))))

    ;; ---- Machine Inspector panel events ----------------------------

    (rf/reg-event-db :rf.causa/select-machine-id
      (fn [db [_ machine-id]]
        (assoc db :selected-machine-id machine-id)))

    (rf/reg-event-db :rf.causa/clear-machine-selection
      (fn [db _event]
        (dissoc db :selected-machine-id)))

    ;; ---- Phase 5 (rf2-rccf3) — AI Co-Pilot panel -----------------------
    ;;
    ;; The Co-Pilot owns its own subs / events / fxs (chip parsing, slash
    ;; commands, conversation buffer, provider streaming). Per the panel
    ;; convention the panel-ns exposes `install!` which the registry calls
    ;; from inside its own `compare-and-set!` gate so the panel's
    ;; registrations are idempotent under shadow-cljs `:after-load`.
    (ai-co-pilot/install!))
  nil)

(defn reset-for-test!
  "Reset the registry's idempotency sentinel so test fixtures can drive
  multiple registration cycles. Test-only — never call from production
  code."
  []
  (reset! registered? false)
  nil)
