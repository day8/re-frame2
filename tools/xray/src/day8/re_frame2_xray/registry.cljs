(ns day8.re-frame2-xray.registry
  "Xray's framework registrations — events, subs, fxs under the
  `:rf.xray/*` namespace prefix.

  ## Namespace prefix is the collision contract

  The registrar is process-global; Xray's registrations share the
  registry with the host app. The `:rf.xray/*` prefix is the
  collision-avoidance contract: Xray never registers under a
  non-`:rf.xray/*` keyword, so a host registering `:user/login` and
  Xray registering `:rf.xray/buffer-cleared` cannot stamp on each
  other.

  ## Registrations target the `:rf/xray` frame

  The panel's state lives in a frame named `:rf/xray` — a sibling of
  the host's `:rf/default`. Subscribers / dispatchers wrapped inside
  `[rf/frame-provider {:frame :rf/xray} ...]` resolve to that frame;
  a Xray view subscribing to `:rf.xray/trace-buffer` reads
  `:rf/xray`'s app-db, not the host's. Prefix prevents id collision;
  frame-provider prevents db reads/writes from leaking into the host.

  ## Orchestrator

  This ns owns only the cross-panel primitives (the trace-buffer sub,
  the panel-selection slot, the shared event-bundles projection, and the
  three suppression-counter handlers) plus the orchestration call
  into each per-panel `install!`. Per-panel registrations live in the
  panel's own ns under `(defn install! [] ...)` so each
  panel owns its subs / events / fxs colocated with the view that
  reads them. Sub-registration order is purely cosmetic — re-frame
  resolves `:<-` chains lazily at subscribe time, not register time."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.config :as config]
            ;; Load the first-class edn-inspector widget ns so its
            ;; top-level `reg-sub` / `reg-event` calls land in
            ;; the registrar at orchestrator-load time. The widget
            ;; owns `:rf.xray.edn-inspector/*` events/subs and is the
            ;; SINGLE source of truth for browse + diff + mini.
            [day8.re-frame2-xray.views.edn-inspector]
            ;; Popup overlay infra. `install!` registers
            ;; the stack/entries subs + open/close/close-top/close-all
            ;; events at orchestrator-load time. The stack VIEW mount
            ;; lives in `shell.cljs`; per-panel "open in popup"
            ;; affordances flow through `[ei/edn-inspector value
            ;; {:popup-affordance? true …}]` and dispatch
            ;; `:rf.xray.edn-inspector-popup/open` against the surrounding
            ;; `:rf/xray` frame.
            [day8.re-frame2-xray.views.edn-inspector-popup
             :as edn-inspector-popup]
            ;; Shared draggable-column-resize affordance.
            ;; `install!` registers the `:rf.xray.column-widths/*`
            ;; events + sub that drive the per-table grid template.
            [day8.re-frame2-xray.views.resizable-table :as resizable-table]
            [day8.re-frame2-xray.defaults :as defaults]
            [day8.re-frame2-xray.epoch :as epoch]
            [day8.re-frame2-xray.filters :as filters]
            [day8.re-frame2-xray.frame-switcher :as frame-switcher]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.open-in-editor :as open-in-editor]
            [day8.re-frame2-xray.palette :as palette]
            [day8.re-frame2-xray.settings.editor-hint :as editor-hint]
            [day8.re-frame2-xray.settings.effects :as settings-effects]
            [day8.re-frame2-xray.settings.popup :as settings-popup]
            [day8.re-frame2-xray.spine :as spine]
            [day8.re-frame2-xray.spine-filters :as spine-filters]
            [day8.re-frame2-xray.static.flows.panel :as static-flows-panel]
            [day8.re-frame2-xray.static.interceptors.panel :as static-interceptors-panel]
            [day8.re-frame2-xray.static.machines.panel :as static-machines-panel]
            [day8.re-frame2-xray.static.persistence :as static-persistence]
            [day8.re-frame2-xray.static.routes.panel :as static-routes-panel]
            [day8.re-frame2-xray.static.schemas.panel :as static-schemas-panel]
            [day8.re-frame2-xray.static.shell :as static-shell]
            [day8.re-frame2-xray.self-noise :as self-noise]
            [day8.re-frame2-xray.panels.app-db-diff :as app-db-diff]
            [day8.re-frame2-xray.panels.app-db-segment-inspector
             :as app-db-segment-inspector]
            [day8.re-frame2-xray.panels.cancellation-cascade :as cancellation-cascade]
            [day8.re-frame2-xray.panels.epoch-panel :as epoch-panel]
            [day8.re-frame2-xray.panels.issues-ribbon-helpers :as issues-helpers]
            [day8.re-frame2-xray.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-xray.panels.managed-fx-subs :as managed-fx-subs]
            [day8.re-frame2-xray.panels.routing :as routing]
            [day8.re-frame2-xray.panels.resources :as resources]
            [day8.re-frame2-xray.panels.derivation-graph :as derivation-graph]
            [day8.re-frame2-xray.panels.module-view :as module-view]
            [day8.re-frame2-xray.panels.reactive-panel :as reactive-panel]
            [day8.re-frame2-xray.panels.trace :as trace]))

;; ---- defaults (re-exported) ---------------------------------------------
;;
;; The Var itself lives in `day8.re-frame2-xray.defaults` so the
;; per-panel `install!` fns can read it without forming a
;; registry→panel→registry cycle. Re-exported here so the test surface
;; reads `registry/default-target-frame` against the same source of
;; truth.

(def default-target-frame defaults/default-target-frame)

;; ---- idempotency sentinel ------------------------------------------------

(defonce ^:private registered?
  ;; Re-loading the namespace (shadow-cljs `:after-load`) must not
  ;; re-register the sub graph (would harmlessly replace each
  ;; handler, but emits a `:rf.warning/handler-replaced` trace per
  ;; registration — pollutes the dev console on every reload).
  (atom false))

(defn register-xray-handlers!
  "Idempotent registration of Xray's :rf.xray/* events, subs, fxs.
  Called from `day8.re-frame2-xray.preload` at load time. Safe to
  call multiple times — second + subsequent calls are no-ops."
  []
  (when (compare-and-set! registered? false true)
    ;; ---- cross-panel primitives ---------------------------------

    ;; Xray's trace-buffer sub returns a flat snapshot of every
    ;; registered frame's per-frame trace ring + the small frameless
    ;; secondary ring.
    ;; The slot lives in Xray's app-db at `:trace-buffer` and is
    ;; populated by `trace-collector/refresh-trace-rings!` — a
    ;; microtask-coalesced snapshot from the framework's per-frame
    ;; rings (`(re-frame.trace.tooling/trace-buffer fid {:flat true})`)
    ;; merged across `(rf/frame-ids)` and concatenated with the Xray-
    ;; side frameless secondary ring's contents, sorted by `:id`.
    ;;
    ;; The sub re-fires on the standard app-db-write reactive path so
    ;; panels re-render on the next microtask after each refresh. The
    ;; coalescer caps the mirror event-bundle at depth 1 regardless of host
    ;; trace-event volume; the router's drain-depth headroom cannot
    ;; gate the mirror under saturation.
    ;;
    ;; Returns the empty vector pre-mount (the slot is absent until
    ;; the first refresh microtask drains). Panel-side composites that
    ;; want richer projection (`group-by-event`, the L2 event list)
    ;; chain off `:rf.xray/event-bundles` rather than reading this slot
    ;; directly.
    (rf/reg-sub :rf.xray/trace-buffer
      (fn [db _query]
        (get db :trace-buffer [])))

    ;; Total count of :sensitive? trace events the collector has
    ;; suppressed under the current local-render egress profile
    ;; (`:rf.xray/egress-profile`). The shell's bottom-rail renders a `[● REDACTED N]`
    ;; hint when this is positive so the user sees why the buffer is
    ;; shorter than the runtime's actual emit count.
    ;;
    ;; The counter lives in Xray's app-db at
    ;; `:suppressed-counters` ({frame-id → count}); `config/note-
    ;; suppressed!` dispatches `:rf.xray/note-sensitive-suppressed`
    ;; in CLJS, so the sub fires on the standard app-db-write
    ;; reactive path and the bottom-rail re-renders IMMEDIATELY —
    ;; no dependency on sibling subs recomputing. The plain
    ;; `config/suppressed-counters` atom remains as the JVM-runnable
    ;; data primitive (sensitive_trace CLJC tests + the JVM-runnable
    ;; consumer surface in self_noise.cljc); the CLJS path
    ;; dual-writes via dispatch so the reactive surface stays
    ;; consistent.
    (rf/reg-sub :rf.xray/suppressed-sensitive-count
      (fn [db _query]
        (reduce + 0 (vals (get db :suppressed-counters {})))))

    ;; ---- 4-layer chrome — selected tab (spec/018) ----
    ;;
    ;; The L3 tab bar reads `:rf.xray/selected-tab` to pick which
    ;; projection of the focused event the L4 detail panel renders.
    ;; Default is `:epoch` — the Epoch panel is the canonical "what
    ;; happened in this epoch" surface and registers at `:order -1`
    ;; (leftmost).
    (rf/reg-sub :rf.xray/selected-tab
      (fn [db _query]
        (get db :selected-tab :epoch)))

    ;; ---- Machine tab fit-on-entry signal ----
    ;;
    ;; A monotonic counter bumped by `:rf.xray/select-tab :machines` and
    ;; `:rf.xray.static/select-tab :machines`. The Machine panel (Dynamic
    ;; `machine_inspector` + Static `static.machines` topology) reads it
    ;; and forwards the value as `MachineChart`'s `:fit-signal` so a
    ;; CHANGE re-fits the topology to view on panel-entry / tab-
    ;; activation. The layout-key auto-fit deliberately
    ;; preserves manual zoom/pan across non-layout re-renders, leaving a
    ;; re-entered chart at its stale (possibly off-screen) viewport; this
    ;; signal is the orthogonal entry-fit escape hatch. Defaults to 0 so
    ;; the first activation (1) is a change from the chart's `::unfit`
    ;; sentinel and frames the graph once.
    (rf/reg-sub :rf.xray/machine-tab-fit-signal
      (fn [db _query]
        (get db :machine-tab-activations 0)))

    ;; ---- Modal positioning ----
    ;;
    ;; Every Xray modal (Settings popup, auto-filter edit popup,
    ;; Share modal, Cancellation event-bundle popover) defaults to
    ;; `position: fixed; inset: 0; z-index: 2_147_483_64x`
    ;; — the right shape for production where the shell covers the
    ;; host app. In a Story testbed where multiple shell instances
    ;; render side-by-side in workspace cells, that geometry escapes
    ;; the cell and paints over the whole Story shell ("popup kills
    ;; window"). Story testbeds pass `:modal-positioning :absolute`
    ;; on `shell-view` so each cell's modals stay inside the cell.
    ;;
    ;; The opt lands here in app-db so every modal can read it via
    ;; one sub regardless of where in the tree it mounts (popovers
    ;; opened from keybindings, modals opened from imperative
    ;; dispatches, etc.). Default `:fixed` preserves production
    ;; behaviour; `:absolute` is the testbed-scoped containment mode.
    (rf/reg-sub :rf.xray/modal-positioning
      (fn [db _query]
        (get db :modal-positioning :fixed)))

    (rf/reg-event :rf.xray/set-modal-positioning
      {:rf.trace/no-emit? true}
      (fn [{:keys [db]} [_ positioning]]
        {:db (assoc db :modal-positioning (or positioning :fixed))}))

    ;; ---- Panel width (horizontal resize handle) -------
    ;;
    ;; The shell's left-edge resize handle drags the panel width.
    ;; `:rf.xray/panel-width-px` reads from the settings map (the
    ;; popup's `:rf.xray/setting` sub composes against the same
    ;; slot — one source of truth); the value flows into the
    ;; recommended `[data-rf-xray-host]` snippet via
    ;; `--rf-xray-inline-width` (host CSS reads
    ;; `var(--rf-xray-inline-width, 560px)` for its `flex-basis`).
    ;;
    ;; `:rf.xray/set-panel-width-px` is the drag handle's write
    ;; surface — clamps to [min, viewport×0.9], persists through the
    ;; same Settings round-trip every other `:rf.xray/settings-
    ;; update` uses, AND applies the CSS var to the host immediately
    ;; via `settings-effects/apply-panel-width!`. `:rf.trace/no-emit?`
    ;; matches the modal-positioning handler — drag events fire at
    ;; mousemove cadence (one dispatch per pixel of drag) and emitting
    ;; them would flood the trace buffer with shape that no panel
    ;; consumes. The clamp is applied at write-time so the persisted
    ;; payload is always in-range — a future viewport-resize that
    ;; would re-clamp would land on the next paint via the
    ;; `panel-width-px` sub.
    (rf/reg-sub :rf.xray/panel-width-px
      (fn [db _query]
        (or (get-in db [:settings :general :panel-width-px])
            (config/get-setting :general :panel-width-px)
            config/default-panel-width-px)))

    (rf/reg-event :rf.xray/set-panel-width-px
      {:rf.trace/no-emit? true}
      (fn [{:keys [db]} [_ px]]
        {:db (let [viewport (or (when (exists? js/window)
                             (.-innerWidth js/window))
                           2000)
              clamped  (config/clamp-panel-width-px px viewport)]
          ;; Dual-write: same pattern the settings-update event uses
          ;; (config atom drives localStorage round-trip; app-db slot
          ;; drives reactive re-render). Then push the CSS var so the
          ;; layout host's `flex-basis` re-evaluates this paint.
          (config/update-setting! :general :panel-width-px clamped)
          (settings-effects/apply-panel-width! clamped)
          (assoc-in db [:settings :general :panel-width-px] clamped))}))

    ;; Reset to default — bound to the resize handle's double-click.
    ;; Routes through the same write surface so persistence + DOM
    ;; event-bundle stay consistent. Separate event id so the palette /
    ;; key-binding affordance can wire to it without re-implementing
    ;; the clamp + persist logic.
    (rf/reg-event :rf.xray/reset-panel-width
      {:rf.trace/no-emit? true}
      (fn [_ _event]
        {:fx [[:dispatch [:rf.xray/set-panel-width-px
                          config/default-panel-width-px]]]}))

    ;; ---- L2 event-list height (seam resize handle) -----
    ;;
    ;; The L2/L3 seam carries a row-resize drag handle that drives the
    ;; events-list height. Mirrors the panel-width-px shape above:
    ;;
    ;;   - `:rf.xray/events-list-height-px` reads from the settings
    ;;     map; the L2 list's inline `:height` style binds to it so
    ;;     drag updates re-paint immediately.
    ;;   - `:rf.xray/set-events-list-height-px` clamps to
    ;;     [min, viewport×0.7] before persisting, mirroring the
    ;;     panel-width clamp at write-time.
    ;;   - `:rf.trace/no-emit?` matches the panel-width handler — drag
    ;;     emits one dispatch per pixel of motion; trace-buffering them
    ;;     would flood the bus with shape no panel consumes.
    ;;
    ;; The seam matches the panel-width handle's interaction model so
    ;; all resize surfaces share one mental model — a full-width
    ;; click-area with persistence and a keyboard affordance.
    (rf/reg-sub :rf.xray/events-list-height-px
      (fn [db _query]
        (or (get-in db [:settings :general :events-list-height-px])
            (config/get-setting :general :events-list-height-px)
            config/default-events-list-height-px)))

    (rf/reg-event :rf.xray/set-events-list-height-px
      {:rf.trace/no-emit? true}
      (fn [{:keys [db]} [_ px]]
        {:db (let [viewport (or (when (exists? js/window)
                             (.-innerHeight js/window))
                           1000)
              clamped  (config/clamp-events-list-height-px px viewport)]
          ;; Dual-write: same pattern the panel-width handler uses
          ;; (config atom drives localStorage round-trip; app-db slot
          ;; drives reactive re-render).
          (config/update-setting! :general :events-list-height-px clamped)
          (assoc-in db [:settings :general :events-list-height-px] clamped))}))

    ;; Reset to default — bound to the seam handle's double-click +
    ;; Enter/Space keyboard binding. Routes through the same write
    ;; surface so persistence stays consistent.
    (rf/reg-event :rf.xray/reset-events-list-height
      {:rf.trace/no-emit? true}
      (fn [_ _event]
        {:fx [[:dispatch [:rf.xray/set-events-list-height-px
                          config/default-events-list-height-px]]]}))

    ;; ---- L2 event-list resizable column widths -------
    ;;
    ;; The L2 event-list carries four columns — `event-id` (flex),
    ;; `source`, `timestamp`, `duration`. The trailing three are
    ;; user-resizable via drag handles between cells. State lives in
    ;; the same settings map every other persistence-bearing knob
    ;; uses (`:general :event-list-col-widths`), so widths survive
    ;; reload through `config/update-setting!` → localStorage.
    ;;
    ;; The sub resolves the persisted map over the defaults (defence-
    ;; in-depth: a stale / malformed payload yields a clamped, fully-
    ;; shaped map every consumer can read against). Header and rows
    ;; both read from the SAME sub so the two surfaces never drift
    ;; out of column alignment — the alignment guarantee.
    ;;
    ;; `:rf.trace/no-emit?` mirrors `:rf.xray/set-panel-width-px`:
    ;; drag emits one event per pixel of motion; emitting them would
    ;; flood the trace buffer with shape no panel consumes.
    (rf/reg-sub :rf.xray/event-list-col-widths
      (fn [db _query]
        (config/resolve-event-list-col-widths
          (or (get-in db [:settings :general :event-list-col-widths])
              (config/get-setting :general :event-list-col-widths)))))

    (rf/reg-event :rf.xray/set-event-list-col-width
      {:rf.trace/no-emit? true}
      (fn [{:keys [db]} [_ col-id px]]
        {:db (if-let [clamped (config/clamp-event-list-col-width col-id px)]
          (let [persisted (or (config/get-setting :general :event-list-col-widths)
                              config/event-list-col-default-widths)
                next-map  (assoc persisted col-id clamped)]
            ;; Dual-write: the atom (canonical, drives localStorage
            ;; round-trip via `update-setting!`) and app-db (drives
            ;; immediate reactive re-render of every header + row).
            (config/update-setting! :general :event-list-col-widths next-map)
            (assoc-in db [:settings :general :event-list-col-widths] next-map))
          ;; Unknown column-id (e.g. `event-id` — flex, never sized):
          ;; no-op. Defensive — the divider views only dispatch for
          ;; the three resizable ids.
          db)}))

    ;; Reset one column to its default — bound to a divider's
    ;; double-click and to the Enter/Space keyboard affordance.
    ;; Routes through the same write surface so persistence stays
    ;; consistent.
    (rf/reg-event :rf.xray/reset-event-list-col-width
      {:rf.trace/no-emit? true}
      (fn [_ [_ col-id]]
        (when-let [default-px (get config/event-list-col-default-widths col-id)]
          {:fx [[:dispatch [:rf.xray/set-event-list-col-width col-id default-px]]]})))

    ;; ---- 4-layer chrome — active filter pills (spec/018 §7) ----
    ;;
    ;; The ribbon's filter cluster reads `:rf.xray/active-filters` —
    ;; shape `{:in [{:pattern <str>}] :out [{:pattern <str>}]}` (pills
    ;; are keyed off event-id only; typed-predicate kinds add a
    ;; `{:kind … :params …}` shape via right-click affordances). The
    ;; `:rf.xray/filtered-event-bundles` sub (installed via
    ;; `filters/install!` further down) composes against this slot to
    ;; produce the filtered event-bundle list every consumer reads.
    ;;
    ;; The sub returns a well-shaped map even when one bucket is
    ;; absent — a save into just `:out` leaves `:in` as `nil` in
    ;; the raw db, but consumers downstream count on `(:in filters)`
    ;; resolving to a vector.
    (rf/reg-sub :rf.xray/active-filters
      (fn [db _query]
        (let [stored (get db :active-filters)]
          {:in  (vec (get stored :in []))
           :out (vec (get stored :out []))})))

    ;; Shared event-bundle projection. The event-detail and performance
    ;; composites all consume `projection/group-by-event` over the same
    ;; trace-buffer; routing them through one intermediate sub collapses
    ;; multiple O(buffer) passes per push to one. Each downstream
    ;; composite declares the dependency via `:<-` so the reactive graph
    ;; stays correct (and idle composites still don't pay for the
    ;; projection).
    ;;
    ;; The projection ALSO hard-filters Xray-internal event-bundles (any
    ;; event-bundle whose event-id is in the `rf.xray` namespace) at this
    ;; single point so every downstream consumer
    ;; — `:rf.xray/filtered-event-bundles`, the L2 event list, the spine,
    ;; the Trace / Views tabs — inherits the filter
    ;; automatically. The ingest-side `self-noise/xray-internal-event?`
    ;; guard catches self-emitted sub-reads + view-renders
    ;; inside Xray's frame scope, but `:rf.xray/*` events dispatched
    ;; WITHOUT a `{:frame :rf/xray}` option (palette quick-actions,
    ;; headless helpers) land on the host frame and slip past the
    ;; ingest filter. This filter closes that hole structurally without
    ;; forcing every call site to thread `:frame`. Pre-alpha posture:
    ;; no opt-out toggle — Xray's internals are not user-facing.
    (rf/reg-sub :rf.xray/event-bundles
      :<- [:rf.xray/trace-buffer]
      (fn [buffer _query]
        (self-noise/filtered-event-bundles buffer)))

    ;; ---- Focused-event-bundle composite -------------------------------
    ;;
    ;; `:rf.xray/focused-event-bundle-detail` produces the focused-event-bundle
    ;; record alongside the event-bundle vector + the effective dispatch-id/
    ;; frame so multiple consumers can read "the event-bundle the spine is
    ;; pointing at" in one shot. It is a cross-panel primitive (the
    ;; per-event-bundle managed-fx surface + the test corpus read it), so it
    ;; lives here next to `:rf.xray/event-bundles` it composes against. The
    ;; name describes its behaviour: "the focused event-bundle's detail
    ;; record".
    ;;
    ;; Reads the EFFECTIVE focused dispatch-id off the spine sub
    ;; (`:rf.xray/focus`); spine auto-advances to head in `:live` mode,
    ;; so the consumers never pin to a stale id. If the spine lands on
    ;; `:ungrouped` (the projection's catch-all bucket for registry-time
    ;; emits / frame lifecycle outside a drain), fall back to the most
    ;; recent ROUTED event-bundle so the default-focus never lands on the
    ;; projection's internal bucket.
    (rf/reg-sub :rf.xray/focused-event-bundle-detail
      :<- [:rf.xray/event-bundles]
      :<- [:rf.xray/focus]
      (fn [[event-bundles focus] _query]
        (let [focus-id       (:dispatch-id focus)
              focus-frame    (:frame focus)
              ungrouped?     (= :ungrouped focus-id)
              routed?        (fn [c] (vector? (:event c)))
              head           (when (or (nil? focus-id) ungrouped?)
                               (last (filterv routed? event-bundles)))
              selected-id    (cond
                               ungrouped?      (:dispatch-id head)
                               (nil? focus-id) (:dispatch-id head)
                               :else           focus-id)
              selected-frame (cond
                               ungrouped?      (:frame head)
                               (nil? focus-id) (:frame head)
                               :else           focus-frame)
              by-id          (when selected-id
                               (some (fn [c]
                                       (when (and (= selected-id (:dispatch-id c))
                                                  (or (nil? selected-frame)
                                                      (= selected-frame (:frame c))))
                                         c))
                                     event-bundles))]
          {:event-bundles                event-bundles
           :selected-dispatch-id    selected-id
           :selected-dispatch-frame selected-frame
           :selected-event-bundle        by-id})))

    ;; ---- Spine shim — focus by dispatch-id ------------------------
    ;;
    ;; `:rf.xray/select-dispatch-id` is the by-dispatch-id entry point
    ;; used by machine-inspector / trace / cancellation-cascade
    ;; / mcp-server / the cross-site event-status-colour e2e harness.
    ;; It writes through the spine via the same reducer the spec-018
    ;; `:rf.xray/focus-event` event uses. A multi-panel consumer, so
    ;; it lives here.
    ;;
    ;; Re-keys `:epoch-history` onto the selected event-bundle's
    ;; frame BEFORE resolving its settling epoch, exactly as the sibling
    ;; `:rf.xray/focus-event` handler does. With the picker
    ;; untouched the `:epoch-history` slot is keyed on the boot head
    ;; frame, so selecting a dispatch-id in a NON-head frame would
    ;; otherwise resolve its epoch against the wrong frame's ring →
    ;; epoch-id nil → the epoch-keyed panels strand on an empty/stale
    ;; state. The cross-frame ring is resolved via `rf/epoch-history` at
    ;; dispatch time; the re-seed is a no-op when the frame is unchanged.
    (rf/reg-event :rf.xray/select-dispatch-id
      (fn [{:keys [db]} [_ dispatch-id frame-id]]
        {:db (let [db       (spine/reseed-epoch-history-for-frame
                         db frame-id (rf/epoch-history frame-id))
              history  (get db :epoch-history [])
              epoch-id (spine/epoch-id-for-event-bundle history dispatch-id)
              head-id  (spine/focusable-head-id (spine/db->event-bundles db))]
          (spine/focus-event-bundle-reducer db dispatch-id frame-id epoch-id head-id))}))

    ;; Programmatic clear of the focused event-bundle. Resets the spine
    ;; focus back to LIVE (head-tracking) per the Phase A semantics.
    ;; A multi-panel consumer alongside the select event above.
    (rf/reg-event :rf.xray/clear-selected-dispatch-id
      (fn [{:keys [db]} _event]
        {:db (update db :focus (fnil assoc {})
                :dispatch-id nil
                :epoch-id    nil
                :mode        :live
                :previewing? false)}))

    ;; ---- L2 relative-time anchor ----------------------------------
    ;;
    ;; Every L2 row carries a small right-aligned chip showing how long
    ;; ago the event-bundle was dispatched ("5s" / "2m" / "1h" / "3d"). The
    ;; anchor — the "now" each row's relative-time computes against —
    ;; flips on EVENT ARRIVAL, not on a wall-clock tick.
    ;;
    ;; Relative time is meaningful BETWEEN events, not between seconds —
    ;; so the anchor is the dispatched-time of the most recent event-bundle,
    ;; which avoids the per-second flicker a wall-clock `setInterval`
    ;; would produce for negligible semantic gain. When a new event
    ;; arrives the anchor flips, older rows recompute (a row that was
    ;; "3s" now reads "8s"); in between events the list is frozen.
    ;;
    ;; The sub composes off `:rf.xray/event-bundles` so it inherits the
    ;; Xray-internal filter (event-bundle-internal ticks never become the
    ;; anchor). It returns nil when the buffer is empty or no event-bundle
    ;; carries a `:dispatched :time` — the view's render-time fallback
    ;; (`(interop/now-ms)`) covers that edge.
    (rf/reg-sub :rf.xray/relative-time-now-ms
      :<- [:rf.xray/event-bundles]
      (fn [event-bundles _query]
        (let [times (into []
                          (keep #(get-in % [:dispatched :time]))
                          event-bundles)]
          (when (seq times)
            (apply max times)))))

    ;; ---- 4-layer chrome events (spec/018) -------------------------

    ;; L3 tab bar — flip the active tab. Six valid ids:
    ;; :epoch :app-db :views :trace :machines :routing
    ;; Registry-driven; a new tab requires only a reg-l4-tab! call.
    ;; Issues surface inline in the Epoch panel, via the L2 event-row
    ;; pink-wash, and via the auto-open-on-error signal — there is no
    ;; dedicated Issues tab.
    (rf/reg-event :rf.xray/select-tab
      (fn [{:keys [db]} [_ tab-id]]
        ;; Activating the Machine tab bumps a monotonic
        ;; fit-signal counter so the Machine panel's topology re-fits to
        ;; view on entry (xyflow's one-shot `:fitView` + the layout-key
        ;; auto-fit both leave a re-entered chart at its stale pan/zoom).
        ;; The counter lands in app-db so the panel can read it via one
        ;; sub regardless of where the chart mounts; the Machine panel
        ;; forwards it as `MachineChart`'s `:fit-signal`. Counting (not a
        ;; boolean) guarantees a fresh value on every re-entry, including
        ;; the case where the prior selection was already `:machines`
        ;; (re-clicking the active tab still re-frames).
        {:db (cond-> (assoc db :selected-tab tab-id)
          (= tab-id :machines)
          (update :machine-tab-activations (fnil inc 0)))}))

    ;; ---- Issues feed composite ------------------------------------
    ;;
    ;; `:rf.xray/issues-ribbon` projects the focused epoch's
    ;; `:trace-events` into the issue subset (errors + warnings +
    ;; advisories per Spec 009 §Error event catalogue). Issues surface
    ;; inline in the Epoch panel, via the L2 event-row pink-wash, and
    ;; via the always-on issues ribbon signal (the auto-open-on-error
    ;; watcher); there is no dedicated Issues tab or session-wide
    ;; triage list. This composite is the canonical projection the
    ;; auto-open watcher reads (settings/effects.cljs/
    ;; install-auto-open-watcher!) — the cross-epoch "something is
    ;; wrong" signal. The pure-data projection lives in
    ;; `issues_ribbon_helpers.cljc` (also feeding the L2 pink-wash
    ;; predicate via `panels/l2-timeline/event-bundle-has-issue?`), so the
    ;; algebra runs under the JVM test target.
    ;;
    ;; Per spec/021 §1.2 the projection is focused-epoch-scoped — it
    ;; joins `:rf.xray/focus`'s `:epoch-id` against the per-frame
    ;; `:rf.xray/epoch-history`, classifies focus status (no-focus /
    ;; focused / evicted; head-fallback), looks up the epoch
    ;; record, and threads it through `project-feed`.
    (rf/reg-sub :rf.xray/issues-ribbon
      :<- [:rf.xray/focus]
      :<- [:rf.xray/epoch-history]
      (fn [[focus epoch-history] _query]
        (let [focus-epoch-id (:epoch-id focus)
              focus-status   (issues-helpers/resolve-focus-status focus-epoch-id
                                                                   epoch-history)
              record         (issues-helpers/find-epoch-record focus-epoch-id
                                                               epoch-history)]
          (issues-helpers/project-feed record focus-status))))

    ;; ---- Static-mode chrome ---------------------------------------
    ;;
    ;; Xray exposes TWO modes per `tools/xray/spec/007-UX-IA.md`
    ;; §Static mode: Dynamic (event-coupled spine) and Static
    ;; (registry browse). The mode slot lives on Xray's app-db under
    ;; `:mode` (default `:dynamic`); the Static tab selection lives
    ;; under `:rf.xray.static/selected-tab` (default `:machines`).
    ;;
    ;; Three event handlers drive the mode lifecycle:
    ;;
    ;;   - `:rf.xray/set-mode` writes a specific mode (used by the
    ;;     mode pill's per-segment click, by hydration after
    ;;     localStorage read, and by test fixtures).
    ;;   - `:rf.xray/toggle-mode` flips between modes (used by the
    ;;     Cmd-Shift-M chord — see `keybinding.cljs`).
    ;;   - `:rf.xray.static/select-tab` flips the Static-scoped tab
    ;;     (mirrors `:rf.xray/select-tab` but writes the Static
    ;;     surface's own slot so Dynamic + Static tab choices don't
    ;;     clobber each other).
    ;;
    ;; The set / toggle handlers attach the `:rf.xray.static/persist-
    ;; mode` fx so every mutation round-trips to localStorage in one
    ;; place. Per `static/persistence.cljs` — the fx swallows
    ;; localStorage errors so a quota / serialisation failure can't
    ;; poison the dispatch chain.

    (rf/reg-sub :rf.xray/mode
      (fn [db _query]
        (static-persistence/normalise-mode (get db :mode :dynamic))))

    (rf/reg-sub :rf.xray.static/selected-tab
      (fn [db _query]
        (get db :rf.xray.static/selected-tab static-shell/default-tab)))

    (rf/reg-event :rf.xray/set-mode
      (fn [{:keys [db]} [_ mode]]
        (let [next-mode (static-persistence/normalise-mode mode)
              next-db   (assoc db :mode next-mode)]
          {:db next-db
           :fx [[:rf.xray.static/persist-mode next-mode]]})))

    (rf/reg-event :rf.xray/toggle-mode
      (fn [{:keys [db]} _event]
        (let [current  (static-persistence/normalise-mode (get db :mode :dynamic))
              next-mode (if (= current :static) :dynamic :static)
              next-db   (assoc db :mode next-mode)]
          {:db next-db
           :fx [[:rf.xray.static/persist-mode next-mode]]})))

    (rf/reg-event :rf.xray.static/select-tab
      (fn [{:keys [db]} [_ tab-id]]
        {:db (if (contains? (static-shell/tab-ids) tab-id)
          ;; Static shares the same `:machines` tab id +
          ;; `machine-canvas/Chart`, so activating it bumps the same
          ;; fit-signal counter the Dynamic select-tab does. The Static
          ;; topology view forwards it as `:fit-signal` too.
          (cond-> (assoc db :rf.xray.static/selected-tab tab-id)
            (= tab-id :machines)
            (update :machine-tab-activations (fnil inc 0)))
          db)}))

    ;; The persistence fx installs idempotently — re-frame's registrar
    ;; replaces in place. Mounting here means the fx is available the
    ;; moment any `:rf.xray/set-mode` / `:toggle-mode` lands.
    (static-persistence/install-fx!)

    ;; L1 filter pills — add / remove. Mode is :in or :out; index
    ;; identifies the pill within its mode bucket. The pure reducers
    ;; live here so direct dispatchers (palette quick-actions, the
    ;; pill cluster's `×` remove button) stay history-clean; the rich
    ;; edit popup in `filters/save-edit-popup` composes against the
    ;; same slot but threads through the popup's draft. Both surfaces
    ;; share the `:rf.xray.filters/persist` fx so every mutation
    ;; round-trips to localStorage in one place.
    (rf/reg-event :rf.xray/add-filter
      (fn [{:keys [db]} [_ mode pill]]
        (let [next-db (update-in db [:active-filters mode] (fnil conj []) pill)]
          {:db next-db
           :fx [[:rf.xray.filters/persist (get next-db :active-filters)]]})))

    (rf/reg-event :rf.xray/remove-filter
      (fn [{:keys [db]} [_ mode idx]]
        (let [next-db
              (update-in db [:active-filters mode]
                         (fn [pills]
                           (let [v (or pills [])]
                             (vec (concat (subvec v 0 (min idx (count v)))
                                          (subvec v (min (inc idx) (count v))))))))]
          {:db next-db
           :fx [[:rf.xray.filters/persist (get next-db :active-filters)]]})))

    ;; Ribbon right-icon events. The chrome carries a VISIBLE `⛶`
    ;; pop-out button (shell.cljs `ribbon-right-icons`) that dispatches
    ;; `:rf.xray/popout-shell`. The event fires the DOM-side
    ;; `:rf.xray.fx/popout-shell` effect (mount/install-fx!) which calls
    ;; `mount/popout!` — the event/fx bridge keeps shell.cljs free of a
    ;; direct mount require (mirrors the close-shell bridge below). The
    ;; programmatic `(xray/popout!)` API is the secondary path.
    (rf/reg-event :rf.xray/open-settings
      (fn [{:keys [db]} _event]
        {:db (assoc db :settings-open? true)}))

    (rf/reg-event :rf.xray/popout-shell
      (fn [_cofx _event]
        ;; Open the second-window pop-out via the DOM-side effect. No db
        ;; change: the pop-out is a sibling mount surface, not a
        ;; reactive flag.
        {:fx [[:rf.xray.fx/popout-shell]]}))

    (rf/reg-event :rf.xray/close-shell
      (fn [{:keys [db]} _event]
        ;; Close intent. Two outcomes, kept in lock-step:
        ;;   :db  — set the reactive `:close-requested?` flag so the
        ;;          round-trip is observable/testable.
        ;;   :fx  — `:rf.xray.fx/hide-shell` performs the actual DOM
        ;;          hide via `mount/close!` (the same CSS-only toggle the
        ;;          Ctrl+Shift+C keybinding drives through `mount/toggle!`).
        {:db (assoc db :close-requested? true)
         :fx [[:rf.xray.fx/hide-shell]]}))

    ;; Install the DOM-side `:rf.xray.fx/hide-shell` effect that the
    ;; `:rf.xray/close-shell` event above fires. Idempotent — re-frame's
    ;; registrar replaces in place.
    (mount/install-fx!)

    ;; ---- trace-buffer mirror events -------------------------------
    ;;
    ;; The reactive surface for the layer-1 `:rf.xray/trace-buffer`
    ;; sub is Xray's `:rf/xray` app-db `:trace-buffer` slot. Two
    ;; events drive that slot, both flagged `:rf.trace/no-emit? true`
    ;; so the mirror dispatch does not re-enter the trace
    ;; fan-out and loop:
    ;;
    ;;   `:rf.xray/sync-trace-buffer` — wholesale overwrite with a
    ;;     fresh snapshot drawn from every registered frame's
    ;;     per-frame ring + the frameless secondary ring. Dispatched
    ;;     by `trace-collector/refresh-trace-rings!` (production
    ;;     microtask-coalesced via `request-mirror-sync!`; tests call
    ;;     `refresh-trace-rings!` directly for a deterministic sync
    ;;     entrypoint). Also seeds the slot at first mount.
    ;;
    ;;   `:rf.xray/clear-trace-buffer` — drop the slot entirely.
    ;;     Dispatched by `trace-collector/retroactive-scrub!` when the
    ;;     local-render egress profile narrows `:rf.egress/local-raw` →
    ;;     redacting default, and from the Settings popup's "Clear buffer
    ;;     now" affordance.

    ;; Clear the mirrored slot in lockstep with the framework's
    ;; per-frame rings + Xray's frameless secondary ring (dispatched
    ;; from `trace-collector/retroactive-scrub!`). The
    ;; `:rf.trace/no-emit?` flag avoids re-entering the trace fan-out.
    (rf/reg-event :rf.xray/clear-trace-buffer
      {:rf.trace/no-emit? true}
      (fn [{:keys [db]} _event]
        {:db (dissoc db :trace-buffer)}))

    ;; Wholesale overwrite of the mirrored slot. Dispatched from
    ;; `mount.cljs/open!` on first Ctrl+Shift+C to seed `:rf/xray`'s
    ;; app-db with whatever the framework's per-frame rings + Xray's
    ;; frameless secondary ring have accumulated before the shell was
    ;; opened, and from `trace-collector/refresh-trace-rings!` /
    ;; `request-mirror-sync!` on every microtask.
    ;; `:rf.trace/no-emit? true` for the same loop-avoidance reason.
    (rf/reg-event :rf.xray/sync-trace-buffer
      {:rf.trace/no-emit? true}
      (fn [{:keys [db]} [_ buffer]]
        {:db (assoc db :trace-buffer (vec buffer))}))

    ;; Bump the per-frame suppressed-events counter.
    ;; Dispatched from `trace-collector/collect-trace!` (CLJS) under
    ;; `:rf/xray` whenever the privacy gate drops a `:sensitive? true`
    ;; trace event. `frame-id` is the event's `:tags :frame` (the host
    ;; frame the trace targeted); `nil` falls under `:global`. Drives
    ;; the bottom-rail `[● REDACTED N]` indicator via the
    ;; `:rf.xray/suppressed-sensitive-count` sub — fully reactive.
    ;;
    ;; `:rf.trace/no-emit? true` opts the handler out of
    ;; framework trace emission. Without this, the dispatch fired by
    ;; `trace-collector/collect-trace!` would itself emit
    ;; `:rf.event/dispatched` etc. back through the trace-cb fan-out,
    ;; the collector would see
    ;; its own self-emit, and the event-bundle would loop until
    ;; `drain-depth-default` terminated it. The framework
    ;; short-circuits emission at the `emit!` / `emit-error!` /
    ;; `emit-dispatched-trace!` gates so no Xray-side `self-emitted?`
    ;; guard is needed.
    (rf/reg-event :rf.xray/note-sensitive-suppressed
      {:rf.trace/no-emit? true}
      (fn [{:keys [db]} [_ frame-id]]
        {:db (update-in db [:suppressed-counters (or frame-id :global)]
                   (fnil inc 0))}))

    ;; Reset the suppressed-events counter. With no arg,
    ;; clears every bucket; with a `frame-id`, drops just that bucket.
    ;; Dispatched from `trace-collector/retroactive-scrub!` (CLJS) —
    ;; clearing the trace ring buffer also drops the REDACTED
    ;; indicator state (the "you missed N events" overhang disappears
    ;; alongside the events that produced it).
    ;;
    ;; `:rf.trace/no-emit? true` (see
    ;; `:rf.xray/note-sensitive-suppressed` above for the rationale).
    (rf/reg-event :rf.xray/reset-suppressed-counters
      {:rf.trace/no-emit? true}
      (fn [{:keys [db]} [_ frame-id]]
        {:db (if frame-id
          (update db :suppressed-counters dissoc (or frame-id :global))
          (dissoc db :suppressed-counters))}))

    ;; ---- per-panel installations --------------------------------
    ;;
    ;; Each panel owns its own subs / events / fxs in
    ;; `panels/<panel>.cljs` under `(defn install! [] ...)`. Order is
    ;; alphabetised — re-frame resolves `:<-` chains lazily at
    ;; subscribe time so registration order is purely cosmetic.
    ;;
    ;; The open-in-editor install is cross-panel — its
    ;; `:rf.xray/open-in-editor` event-fx + `:rf.editor/open` fx are
    ;; dispatched from trace, mcp-server, and the
    ;; hydration debugger. Installed alongside the
    ;; per-panel installs so the registration order matches the
    ;; per-panel pattern.

    ;; Cross-cutting epoch primitives. MUST install before app-db-diff +
    ;; views + machine-inspector — their composites :<- onto
    ;; `:rf.xray/epoch-history` / `:rf.xray/target-frame` (and pivot on
    ;; the spine focus epoch via `:rf.xray/focus-epoch-id`). Registration
    ;; order is cosmetic (re-frame resolves :<- lazily), but the top-down
    ;; dependency read is clearer.
    (epoch/install!)
    (open-in-editor/install!)
    ;; Open-in-editor 'pick an editor in Settings' hint
    ;; toast. Installs before settings-popup since its
    ;; `:rf.xray/editor-hint-open-settings` event dispatches
    ;; `:rf.xray/settings-open` (registered by settings-popup), but the
    ;; registrar resolves dispatch targets lazily so the order is
    ;; cosmetic.
    (editor-hint/install!)
    (palette/install!)
    (settings-popup/install!)
    ;; Edn-inspector popup overlay (subs + events). The
    ;; stack view is mounted in `shell.cljs` alongside the other modal
    ;; mounts; per-panel "open in popup" affordances pass through
    ;; `[ei/edn-inspector value {:popup-affordance? true …}]` and
    ;; dispatch the `:rf.xray.edn-inspector-popup/open` event registered
    ;; here.
    (edn-inspector-popup/install!)
    ;; Shared draggable column-resize state for Xray
    ;; tables. Registers :rf.xray.column-widths/{for-table,resize-pair,
    ;; reset,hydrate} + the :rf.xray.column-widths/persist fx.
    ;;
    ;; Column-widths persist to localStorage under
    ;; `re-frame2.xray.column-widths.v1`. `install!` wires the fx +
    ;; events; `hydrate!` runs here (preload-time, before the frame is
    ;; registered, so it's a guarded no-op) AND from `mount.cljs`'s
    ;; `::hydrate-column-widths` first-mount hook (after the frame is
    ;; registered, where the dispatch actually lands). Mirrors the
    ;; static-mode hydrate pattern.
    (resizable-table/install!)
    (resizable-table/hydrate!)
    ;; Filters install AFTER `:rf.xray/active-filters` + the
    ;; add-filter / remove-filter events above are registered (the
    ;; filters facade adds `:rf.xray/filtered-event-bundles` + the edit-
    ;; popup events + the persistence fx + hydrates the slot from
    ;; localStorage). Hydration runs through the orchestrator so the
    ;; idempotency sentinel above prevents the hydrate-dispatch from
    ;; firing twice on shadow-cljs `:after-load`.
    (filters/install!)
    ;; Per-event-id mute filter. Installs the
    ;; `:rf.xray/muted-event-ids` slot + the mute / unmute / clear
    ;; events + the localStorage persistence fx + the unmute manager
    ;; modal open / close state. Installs AFTER `filters/install!` so
    ;; the `:rf.xray/filtered-event-bundles` sub it composes against is
    ;; already registered; the mute filter wraps the filtered-event-bundles
    ;; via the standalone `:rf.xray/spine-filtered-event-bundles` sub
    ;; below (the L2 event list + spine consumers swap their dependency
    ;; from `filtered-event-bundles` → `spine-filtered-event-bundles` so the
    ;; mute strip rides atop the existing IN/OUT pill filter).
    (spine-filters/install!)
    ;; Spine MUST install before the cross-panel selection shims —
    ;; the by-dispatch-id selection events write through the spine slot,
    ;; and the slot's reducer helpers live in spine.cljs.
    (spine/install!)
    ;; Frame-switcher slot — hardened L1 frame-switcher
    ;; contract. MUST install AFTER `spine/install!` so the canonical
    ;; `:rf.xray/select-frame` event-fx's `[:dispatch [:rf.xray/set-
    ;; frame ...]]` resolves at install time. Registers the
    ;; `:rf.xray/current-frame` + `:rf.xray/available-frames` subs,
    ;; the `:rf.xray/select-frame` event-fx, and the `:rf.xray.frame-
    ;; switcher/persist` localStorage write fx. Does NOT restore the pin
    ;; on init — the frame pin is a transient filter, reset
    ;; to unpinned on every page load by mount.cljs's
    ;; `::reset-transient-filters` hook.
    (frame-switcher/install!)
    (app-db-diff/install!)
    ;; App-DB segment-inspector popup — opens when any
    ;; path-segment in the App-DB Diff breadcrumb is clicked. Installs
    ;; after `app-db-diff` so the segment-inspector's
    ;; `:rf.xray/segment-inspector-value` sub can chain off
    ;; `:rf.xray/target-frame-db` (registered by app-db-diff's subs).
    ;; Registration order is cosmetic — re-frame resolves `:<-` lazily;
    ;; the top-down dependency reading is the rationale.
    (app-db-segment-inspector/install!)
    ;; Cancellation-cascade visualiser — installs the
    ;; subs + events for the Machines tab side-panel + the trace-row
    ;; popover. The view-side `reg-view`s are picked up at ns-load.
    ;; Order: registers AFTER spine + event-bundles (composes against
    ;; `:rf.xray/focus` + `:rf.xray/trace-buffer`); registry order is
    ;; cosmetic since re-frame resolves `:<-` chains lazily.
    (cancellation-cascade/install!)
    ;; Epoch panel — the canonical "what happened in this
    ;; epoch" surface; numbered event-bundle of every pipeline step. Reads
    ;; the spine's `:rf.xray/focus` + `:rf.xray/epoch-history`,
    ;; projects the focused record's `:trace-events` into ordered
    ;; step rows. Registers at order -1 (leftmost). The cross-panel
    ;; primitives it consumes — `:rf.xray/focused-event-bundle-detail`,
    ;; `:rf.xray/select-dispatch-id`, `:rf.xray/clear-selected-
    ;; dispatch-id` — live in the cross-panel block above.
    (epoch-panel/install!)
    (machine-inspector/install!)
    ;; Static Machines sub-tab — browses every registered
    ;; machine + Topology + JUMP-to-Dynamic + Cascade-dimmed surfaces.
    ;; Installs AFTER `machine-inspector/install!` so the static-machines
    ;; sub graph can :<- onto the existing `:rf.xray/registered-machines`,
    ;; `:rf.xray/machine-definitions`, and `:rf.xray/machine-snapshots`
    ;; subs without redeclaring them. Registration order is purely
    ;; cosmetic (re-frame resolves `:<-` chains lazily); the top-down
    ;; dependency read is clearer.
    (static-machines-panel/install!)
    ;; Managed-fx wire-boundary diff template — installs the
    ;; `:rf.xray/managed-fx-for-focused-event` sub + the
    ;; `:rf.xray/focus-event` cross-link event. The public
    ;; `mount-managed-fx!` aggregator entry on `panels.cljs` is its
    ;; mount surface. No L3 tab.
    (managed-fx-subs/install!)
    ;; Routing tab — Dynamic L3
    ;; tab carrying the focused-event lens (FROM/TO chips when the
    ;; focused event triggered navigation). Installs the registered-
    ;; routes / current-route-slice / routing-tab-data subs +
    ;; test-only overrides. Composes against `:rf.xray/target-frame-db`
    ;; (registered by `app-db-diff/install!` above) so the install
    ;; order is intentional, though re-frame's `:<-` resolution is
    ;; lazy and the order is purely cosmetic.
    (routing/install!)
    ;; Resources tab (Spec 016 §Xray and AI tooling) — Dynamic L3 tab
    ;; for declarative server-state. Installs the registered-resources /
    ;; resource-entries / resource-work-ledger / resource-sub-reads subs
    ;; + the `:rf.xray/resources-tab-data` composite + test-only
    ;; overrides. Reads the static registry via `(rf/registrations
    ;; :resource)` and the live cache/ledger off
    ;; `:rf.xray/target-frame-runtime-db` (registered by
    ;; `app-db-diff/install!` above) — decoupled from the optional
    ;; resources artefact (Xray does not :require it; bundle isolation).
    ;; Read-only: no `:rf.resource/*` dispatch (observing pins nothing).
    (resources/install!)
    ;; Derivation-Graph tab (EP-0014 prop-3) — Dynamic L3 tab:
    ;; the UNIFIED derivation/process graph composed by
    ;; `re-frame.derivation.graph` across the five algebra-view families.
    ;; Xray is the EP's NAMED FIRST CONSUMER of the structured graph
    ;; accessor. Installs the static/live mode toggle, the assembled-graph
    ;; sub (composing the subs/flows/routes tooling siblings Xray :requires),
    ;; and the `:rf.xray/derivation-graph-tab-data` composite. Reads the
    ;; registrar (static) + the observed target frame's runtime-db (live)
    ;; decoupled. ON-BOX raw (Security.md permits on-box); off-box egress
    ;; redaction is `derivation-graph-helpers/redact-graph-for-egress`.
    ;; Read-only: assembling the graph pins nothing, dispatches nothing.
    (derivation-graph/install!)
    ;; Module-view tab — Dynamic L4 tab: the EP-0023
    ;; `image -> frame -> event stream` public model — every live image-loaded
    ;; frame as an execution context carrying its resolved image's [kind id]
    ;; descriptors. Read-only: enumerating live frames + reading sealed
    ;; generations pins nothing, dispatches nothing.
    (module-view/install!)
    ;; Static Routes panel — Static-surface browse +
    ;; Simulate-URL + per-row inline expand + hermetic Simulate-
    ;; navigation preview. Installs the UI-state slots under
    ;; `:rf.xray.static.routes/*` + the `:rf.xray.static.routes/
    ;; tab-data` composite. Reads `:rf.xray/registered-routes` (just
    ;; registered above) — order is cosmetic since re-frame resolves
    ;; `:<-` chains lazily.
    (static-routes-panel/install!)
    ;; Static Schemas sub-tab — browse every registered
    ;; Malli schema across app-db slots + events + subs. Reads the
    ;; public `re-frame.schemas` façade (`rf/frame-ids` +
    ;; `app-schemas` + `app-schema-meta-at`) + `(rf/registrations
    ;; :event)` / `(rf/registrations :sub)` `:spec` slots. Source-coord
    ;; chip dispatches `:rf.xray/open-in-editor` (open-in-editor
    ;; installed above).
    (static-schemas-panel/install!)
    ;; Static Flows sub-tab — browse every flow registered
    ;; via `re-frame.flows/reg-flow`. Reads the public
    ;; `(rf/registrations :flow)` surface, regrouping the flat
    ;; `{flow-id meta}` shape by each entry's stamped `:frame`. Flows
    ;; are frame-scoped per Spec 013 — the sub flattens the two-level
    ;; shape into a single row vector for the view.
    (static-flows-panel/install!)
    (reactive-panel/install!)
    (trace/install!)
    ;; A11y dogfooding is Story's domain — Story ships the chrome-a11y
    ;; panel (`tools/story/src/re_frame/story/ui/chrome_a11y.cljs`) +
    ;; the variant a11y scanner (`tools/story/src/re_frame/story/ui/
    ;; a11y.cljs`), so Xray carries no a11y panel of its own.
    ;; Static sub-tabs: Flows · Interceptors · Routes · Schemas (+ the
    ;; densest Machines tab as the landing target).
    ;; Static Interceptors sub-tab — pure-browse lens
    ;; over every interceptor surfaced through the registered event
    ;; chains. Collapses by `:id` so each interceptor appears once
    ;; with a chain-count. No simulate — interceptors are composition
    ;; primitives, not independently simulable.
    (static-interceptors-panel/install!))
  nil)

(defn reset-for-test!
  "Reset the registry's idempotency sentinel so test fixtures can drive
  multiple registration cycles. Test-only — never call from production
  code."
  []
  (reset! registered? false)
  nil)
