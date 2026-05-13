(ns day8.re-frame2-causa.registry
  "Causa's framework registrations — events, subs, fxs under the
  `:rf.causa/*` namespace prefix.

  ## Why the namespace prefix matters (rf2-tijr Option C)

  Per rf2-tijr the registrar is process-global; Causa's registrations
  share the registry with the host app. The `:rf.causa/*` prefix is the
  collision-avoidance contract: Causa never registers under a
  non-`:rf.causa/*` keyword, so a host registering `:user/login` and
  Causa registering `:rf.causa/buffer-cleared` cannot stamp on each
  other.

  ## Why the registrations target the `:rf/causa` frame

  Per rf2-tijr Option C the panel's state lives in a frame named
  `:rf/causa` — a sibling of the host's `:rf/default`. Subscribers /
  dispatchers wrapped inside `[rf/frame-provider {:frame :rf/causa}
  ...]` resolve to that frame; a Causa view subscribing to
  `:rf.causa/trace-buffer` reads `:rf/causa`'s app-db, not the host's.

  Even though the registrar is process-global, each registered handler
  operates *against the active frame's db* — so the registry namespace
  prefix and the frame isolation work together: prefix prevents id
  collision, frame-provider prevents db reads/writes from leaking into
  the host.

  ## Phase 2 scope (rf2-op3bz)

  Phase 1 (rf2-n6x4q) shipped only `:rf.causa/trace-buffer`. Phase 2
  adds the event-detail panel's wiring:

    - `:rf.causa/selected-panel`           sub — current panel-id
    - `:rf.causa/select-panel`             event-db — set current panel
    - `:rf.causa/selected-dispatch-id`     sub — focused cascade
    - `:rf.causa/event-detail`             sub — projected cascade
    - `:rf.causa/select-dispatch-id`       event-db — set focused
    - `:rf.causa/clear-selected-dispatch-id` event-db — clear focus

  ## Phase 3 scope (rf2-t53ze) — Time Travel panel

  Adds the scrubber's wiring against the framework's epoch-history
  surface. The panel's :selected-epoch-id holds the *view* selection
  (per spec §The passive-scrubbing rule — scrubbing rebases panels;
  rewind is opt-in). :pinned-snapshots is the per-frame pin store
  (per spec §Pinned snapshots, Lock 4 session-scoped).

    - `:rf.causa/epoch-history`            sub — :rf.causa/target-frame's history
    - `:rf.causa/selected-epoch-id`        sub — view's selected epoch
    - `:rf.causa/pinned-snapshots`         sub — vector of chip states
    - `:rf.causa/time-travel`              sub — composite for the panel
    - `:rf.causa/select-epoch`             event-db — set view selection (passive)
    - `:rf.causa/clear-selected-epoch`     event-db — drop view selection
    - `:rf.causa/pin-current`              event — eager-copy a pin
    - `:rf.causa/unpin`                    event-db — drop a pin
    - `:rf.causa/rename-pin`               event-db — rewrite a pin's :label
    - `:rf.causa/reset-to-epoch`           event-fx — restore-epoch via fx
    - `:rf.causa/reset-to-pinned`          event-fx — reset-frame-db! via fx

  Two effects route the framework writes — `:rf.causa.fx/restore-epoch`
  and `:rf.causa.fx/reset-frame-db!`. They are reg-fx'd thin
  delegations to `rf/restore-epoch` / `rf/reset-frame-db!`. The
  indirection lets test fixtures stub the writes and assert the
  correct framework call site is reached (Reset to pinned uses
  reset-frame-db!, not restore-epoch, per spec §Why reset-frame-db!
  not restore-epoch).

  ## Phase 4 scope (rf2-4rqs1) — Causality Graph panel

  Reuses the Phase 2 / Phase 3 surface (trace-buffer, selected-
  dispatch-id, selected-epoch-id) and adds one composite sub that
  projects + lays out the graph data the panel consumes:

    - `:rf.causa/causality-graph-data` sub — composite

  No new events are required: the graph reuses
  `:rf.causa/select-dispatch-id` (shared with the event-detail hero
  per spec §10 Lock 7) and `:rf.causa/clear-selected-epoch` for the
  cascade-filter affordance.

  Subsequent panel beads add their own per-panel events / subs / fxs."
  (:require [re-frame.core :as rf]
            [re-frame.trace.projection :as projection]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.time-travel-helpers :as tt-helpers]
            [day8.re-frame2-causa.panels.causality-graph-helpers :as cg-helpers]
            [day8.re-frame2-causa.panels.hydration-debugger-helpers :as hd-helpers]
            [day8.re-frame2-causa.panels.schema-violation-timeline-helpers :as svt-helpers]
            [day8.re-frame2-causa.panels.subscriptions-helpers :as subs-helpers]))

;; ---- defaults ------------------------------------------------------------

(def default-panel-id
  "The hero panel — `:event-detail` — is Causa's default landing per
  spec/007-UX-IA.md §The default landing view + §10 Lock 7. Exposed
  as a Var so the shell and tests share the source of truth."
  :event-detail)

(def default-target-frame
  "The host frame Causa's time-travel scrubber inspects by default.
  Per spec/002-Time-Travel.md §Cross-frame scrubbing the scrubber is
  per-frame — the frame picker (rf2-xxx, not Phase 3 scope) lets
  the user pick a different host frame. Until that picker lands the
  scrubber is hard-bound to :rf/default — the canonical host frame
  per Tool-Pair §Frame naming.

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
;; recompute.
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
      ;; Signal layer: depend on the trace-buffer sub +
      ;; selected-dispatch-id sub so this composite recomputes when
      ;; either changes. The `:<-` chain is the only sub-registration
      ;; form in v2 (per Spec 002 §Subscriptions composing —
      ;; reg-sub-raw is dropped; see `re-frame.subs/parse-reg-sub-args`).
      :<- [:rf.causa/trace-buffer]
      :<- [:rf.causa/selected-dispatch-id]
      (fn [[buffer selected-id] _query]
        (let [cascades (projection/group-cascades buffer)
              by-id    (when selected-id
                         (some #(when (= selected-id (:dispatch-id %)) %)
                               cascades))]
          {:cascades             cascades
           :selected-dispatch-id selected-id
           :selected-cascade     by-id})))

    ;; ---- Phase 3 (rf2-t53ze) — Time Travel scrubber subs ---------

    ;; Target frame the scrubber inspects. Hard-bound to :rf/default
    ;; until the frame picker (rf2-xxx) lands; the sub abstracts so
    ;; the picker can drop in without rewiring every consumer.
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
      :<- [:rf.causa/trace-buffer]
      :<- [:rf.causa/selected-dispatch-id]
      :<- [:rf.causa/selected-epoch-id]
      :<- [:rf.causa/epoch-history]
      (fn [[buffer selected-id selected-epoch-id history] _query]
        (let [cascades         (projection/group-cascades buffer)
              enriched         (cg-helpers/enrich-cascades cascades buffer)
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

    ;; ---- events --------------------------------------------------
    (rf/reg-event-db :rf.causa/select-panel
      (fn [db [_ panel-id]]
        (assoc db :selected-panel panel-id)))

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
              ;; Phase 5 ships with target-frame = :rf/default (the
              ;; canonical host frame); cross-frame swimlanes ride a
              ;; follow-on bead (rf2-xxx) once the picker lands.
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
              ;; epoch-record yet (rf2-xxx will surface it); fall
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
          (dissoc db :schema-timeline-window)))))
  nil)

(defn reset-for-test!
  "Reset the registry's idempotency sentinel so test fixtures can drive
  multiple registration cycles. Test-only — never call from production
  code."
  []
  (reset! registered? false)
  nil)
