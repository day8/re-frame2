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

  ## Orchestrator

  This ns owns only the cross-panel primitives (the trace-buffer sub,
  the panel-selection slot, the shared cascades projection, and the
  three suppression-counter handlers) plus the orchestration call
  into each per-panel `install!`. Per-panel registrations live in the
  panel's own ns under `(defn install! [] ...)` (rf2-d4xda) so each
  panel owns its subs / events / fxs colocated with the view that
  reads them. Sub-registration order is purely cosmetic — re-frame
  resolves `:<-` chains lazily at subscribe time, not register time."
  (:require [re-frame.core :as rf]
            [re-frame.trace.projection :as projection]
            [day8.re-frame2-causa.config :as config]
            ;; rf2-jgip1 — load the shared data-display renderer ns so
            ;; its top-level `reg-sub` / `reg-event-db` calls land in
            ;; the registrar at orchestrator-load time (same posture as
            ;; the existing `theme.data-inspector` ns which is loaded
            ;; transitively via the diff renderer). Per
            ;; `tools/causa/spec/021-Dynamic-Panel-Designs.md` §10.
            [day8.re-frame2-causa.data-display.render]
            [day8.re-frame2-causa.defaults :as defaults]
            [day8.re-frame2-causa.epoch :as epoch]
            [day8.re-frame2-causa.filters :as filters]
            [day8.re-frame2-causa.frame-switcher :as frame-switcher]
            [day8.re-frame2-causa.open-in-editor :as open-in-editor]
            [day8.re-frame2-causa.palette :as palette]
            [day8.re-frame2-causa.settings.effects :as settings-effects]
            [day8.re-frame2-causa.settings.popup :as settings-popup]
            [day8.re-frame2-causa.spine :as spine]
            [day8.re-frame2-causa.spine-filters :as spine-filters]
            [day8.re-frame2-causa.static.flows.panel :as static-flows-panel]
            [day8.re-frame2-causa.static.machines.panel :as static-machines-panel]
            [day8.re-frame2-causa.static.persistence :as static-persistence]
            [day8.re-frame2-causa.static.routes.panel :as static-routes-panel]
            [day8.re-frame2-causa.static.schemas.panel :as static-schemas-panel]
            [day8.re-frame2-causa.static.shell :as static-shell]
            [day8.re-frame2-causa.static.views.panel :as static-views-panel]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.app-db-diff :as app-db-diff]
            [day8.re-frame2-causa.panels.app-db-segment-inspector
             :as app-db-segment-inspector]
            [day8.re-frame2-causa.panels.cancellation-cascade :as cancellation-cascade]
            [day8.re-frame2-causa.panels.chrome-a11y.panel :as chrome-a11y-panel]
            [day8.re-frame2-causa.panels.event-detail :as event-detail]
            [day8.re-frame2-causa.panels.issues-ribbon :as issues-ribbon]
            [day8.re-frame2-causa.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-causa.panels.machines-canvas.panel :as machines-canvas-panel]
            [day8.re-frame2-causa.panels.managed-fx-subs :as managed-fx-subs]
            [day8.re-frame2-causa.panels.routing :as routing]
            [day8.re-frame2-causa.panels.reactive-panel :as reactive-panel]
            [day8.re-frame2-causa.panels.trace :as trace]
            [day8.re-frame2-causa.panels.trace-helpers :as trace-helpers]))

;; ---- defaults (re-exported for back-compat callers) ---------------------
;;
;; The Var itself lives in `day8.re-frame2-causa.defaults` so the
;; per-panel `install!` fns can read it without forming a
;; registry→panel→registry cycle. Re-exported here so the existing
;; test surface keeps reading `registry/default-target-frame` — same
;; source of truth. (`default-panel-id` was dropped with rf2-qy0nu —
;; the legacy `:rf.causa/selected-panel` slot is no longer read.)

(def default-target-frame defaults/default-target-frame)

;; ---- idempotency sentinel ------------------------------------------------

(defonce ^:private registered?
  ;; Re-loading the namespace (shadow-cljs `:after-load`) must not
  ;; re-register the sub graph (would harmlessly replace each
  ;; handler, but emits a `:rf.warning/handler-replaced` trace per
  ;; registration — pollutes the dev console on every reload).
  (atom false))

(defn register-causa-handlers!
  "Idempotent registration of Causa's :rf.causa/* events, subs, fxs.
  Called from `day8.re-frame2-causa.preload` at load time. Safe to
  call multiple times — second + subsequent calls are no-ops."
  []
  (when (compare-and-set! registered? false true)
    ;; ---- cross-panel primitives ---------------------------------

    ;; Causa's trace-buffer sub returns the Causa-side ring buffer
    ;; contents (NOT the framework's `(rf/trace-buffer)`). Per
    ;; rf2-in6l2 + rf2-wq6gx the slot lives in Causa's app-db at
    ;; `:trace-buffer`; `trace-bus/request-mirror-sync!` coalesces
    ;; every same-tick mirror request into ONE
    ;; `:rf.causa/sync-trace-buffer` dispatch into `:rf/causa` that
    ;; writes the atom's current snapshot — the sub re-fires on the
    ;; standard app-db-write reactive path so panels re-render on the
    ;; next microtask after a flush. The coalesced design caps the
    ;; cascade depth at 1 regardless of host trace-event volume,
    ;; structurally eliminating the drain-depth saturation that the
    ;; original per-event mirror exhibited under a synthetic 1000-
    ;; event flood (rf2-wq6gx). The prior rf2-e9s81 shape thunked
    ;; `trace-bus/buffer` directly so visible delay was bounded by
    ;; the host's next dispatch.
    ;;
    ;; The pre-mount fallback `(trace-bus/buffer)` keeps the sub useful
    ;; if a consumer reaches in before `mount.cljs/open!` has registered
    ;; the `:rf/causa` frame + seeded the slot (e.g. headless tests
    ;; that drive `collect-trace!` without opening the shell). In a
    ;; live session the seed lands at first Ctrl+Shift+C and the
    ;; app-db slot is the authoritative reactive source from there on.
    (rf/reg-sub :rf.causa/trace-buffer
      (fn [db _query]
        (or (get db :trace-buffer) (trace-bus/buffer))))

    ;; Total count of :sensitive? trace events the collector has
    ;; suppressed under the current `:rf.privacy/show-sensitive?` setting
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

    ;; ---- 4-layer chrome — selected tab (rf2-xy4yb / spec/018) ----
    ;;
    ;; The L3 tab bar reads `:rf.causa/selected-tab` to pick which
    ;; projection of the focused event the L4 detail panel renders.
    ;; Default is `:event` per spec/018 §5 — the Event tab carries
    ;; the fattened event detail (handler return + db writes + fx +
    ;; fx-handlers that ran).
    (rf/reg-sub :rf.causa/selected-tab
      (fn [db _query]
        (get db :selected-tab :event)))

    ;; ---- Modal positioning (rf2-om6fa) ----
    ;;
    ;; Every Causa modal (Settings popup, auto-filter edit popup,
    ;; Share modal, Cancellation cascade popover) defaults to
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
    (rf/reg-sub :rf.causa/modal-positioning
      (fn [db _query]
        (get db :modal-positioning :fixed)))

    (rf/reg-event-db :rf.causa/set-modal-positioning
      {:rf.trace/no-emit? true}
      (fn [db [_ positioning]]
        (assoc db :modal-positioning (or positioning :fixed))))

    ;; ---- Panel width (rf2-x8h9y horizontal resize handle) -------
    ;;
    ;; The shell's left-edge resize handle drags the panel width.
    ;; `:rf.causa/panel-width-px` reads from the settings map (the
    ;; popup's `:rf.causa/setting` sub composes against the same
    ;; slot — one source of truth); the value flows into the
    ;; recommended `[data-rf-causa-host]` snippet via
    ;; `--rf-causa-inline-width` (host CSS reads
    ;; `var(--rf-causa-inline-width, 560px)` for its `flex-basis`).
    ;;
    ;; `:rf.causa/set-panel-width-px` is the drag handle's write
    ;; surface — clamps to [min, viewport×0.9], persists through the
    ;; same Settings round-trip every other `:rf.causa/settings-
    ;; update` uses, AND applies the CSS var to the host immediately
    ;; via `settings-effects/apply-panel-width!`. `:rf.trace/no-emit?`
    ;; matches the modal-positioning handler — drag events fire at
    ;; mousemove cadence (one dispatch per pixel of drag) and emitting
    ;; them would flood the trace buffer with shape that no panel
    ;; consumes. The clamp is applied at write-time so the persisted
    ;; payload is always in-range — a future viewport-resize that
    ;; would re-clamp would land on the next paint via the
    ;; `panel-width-px` sub.
    (rf/reg-sub :rf.causa/panel-width-px
      (fn [db _query]
        (or (get-in db [:settings :general :panel-width-px])
            (config/get-setting :general :panel-width-px)
            config/default-panel-width-px)))

    (rf/reg-event-db :rf.causa/set-panel-width-px
      {:rf.trace/no-emit? true}
      (fn [db [_ px]]
        (let [viewport (or (when (exists? js/window)
                             (.-innerWidth js/window))
                           2000)
              clamped  (config/clamp-panel-width-px px viewport)]
          ;; Dual-write: same pattern the settings-update event uses
          ;; (config atom drives localStorage round-trip; app-db slot
          ;; drives reactive re-render). Then push the CSS var so the
          ;; layout host's `flex-basis` re-evaluates this paint.
          (config/update-setting! :general :panel-width-px clamped)
          (settings-effects/apply-panel-width! clamped)
          (assoc-in db [:settings :general :panel-width-px] clamped))))

    ;; Reset to default — bound to the resize handle's double-click.
    ;; Routes through the same write surface so persistence + DOM
    ;; cascade stay consistent. Separate event id so the palette /
    ;; key-binding affordance can wire to it without re-implementing
    ;; the clamp + persist logic.
    (rf/reg-event-fx :rf.causa/reset-panel-width
      {:rf.trace/no-emit? true}
      (fn [_ _event]
        {:fx [[:dispatch [:rf.causa/set-panel-width-px
                          config/default-panel-width-px]]]}))

    ;; ---- 4-layer chrome — active filter pills (rf2-xy4yb / spec/018 §7) ----
    ;;
    ;; The ribbon's filter cluster reads `:rf.causa/active-filters` —
    ;; shape `{:in [{:pattern <str> :scope #{:event-id ...}}]
    ;;         :out [{:pattern <str> :scope #{...}}]}`. The
    ;; `:rf.causa/filtered-cascades` sub (rf2-ak4ms, installed via
    ;; `filters/install!` further down) composes against this slot to
    ;; produce the filtered cascade list every consumer reads.
    ;;
    ;; The sub returns a well-shaped map even when one bucket is
    ;; absent — a save into just `:out` leaves `:in` as `nil` in
    ;; the raw db, but consumers downstream count on `(:in filters)`
    ;; resolving to a vector.
    (rf/reg-sub :rf.causa/active-filters
      (fn [db _query]
        (let [stored (get db :active-filters)]
          {:in  (vec (get stored :in []))
           :out (vec (get stored :out []))})))

    ;; Shared cascade projection. The event-detail and performance
    ;; composites all consume `projection/group-cascades` over the same
    ;; trace-buffer; routing them through one intermediate sub collapses
    ;; multiple O(buffer) passes per push to one. Each downstream
    ;; composite declares the dependency via `:<-` so the reactive graph
    ;; stays correct (and idle composites still don't pay for the
    ;; projection).
    ;;
    ;; Per rf2-g1pt8 the projection ALSO hard-filters Causa-internal
    ;; cascades (any cascade whose event-id is in the `rf.causa`
    ;; namespace) at this single point so every downstream consumer
    ;; — `:rf.causa/filtered-cascades`, the L2 event list, the spine,
    ;; the Trace / Issues / Event / Views tabs — inherits the filter
    ;; automatically. The ingest-side `trace-bus/causa-internal-event?`
    ;; guard (rf2-xs8vu) catches self-emitted sub-reads + view-renders
    ;; inside Causa's frame scope, but `:rf.causa/*` events dispatched
    ;; WITHOUT a `{:frame :rf/causa}` option (palette quick-actions,
    ;; headless helpers) land on the host frame and slip past the
    ;; ingest filter. This filter closes that hole structurally without
    ;; forcing every call site to thread `:frame`. Pre-alpha posture:
    ;; no opt-out toggle — Causa's internals are not user-facing.
    (rf/reg-sub :rf.causa/cascades
      :<- [:rf.causa/trace-buffer]
      (fn [buffer _query]
        (let [cascades (projection/group-cascades buffer)]
          (into [] (remove trace-bus/causa-internal-cascade?) cascades))))

    ;; ---- L2 relative-time anchor (rf2-vbbq0 / rf2-0s2at) ----------
    ;;
    ;; Every L2 row carries a small right-aligned chip showing how long
    ;; ago the cascade was dispatched ("5s" / "2m" / "1h" / "3d"). The
    ;; anchor — the "now" each row's relative-time computes against —
    ;; flips on EVENT ARRIVAL, not on a wall-clock tick (rf2-0s2at).
    ;;
    ;; Mike's design call (2026-05-19, watching the parallel-frames
    ;; testbed live): a per-second `setInterval` re-rendered the L2
    ;; list every second, producing constant flicker for negligible
    ;; semantic gain. Relative time is meaningful BETWEEN events, not
    ;; between seconds — so the anchor is the dispatched-time of the
    ;; most recent cascade. When a new event arrives the anchor flips,
    ;; older rows recompute (a row that was "3s" now reads "8s"); in
    ;; between events the list is frozen.
    ;;
    ;; The sub composes off `:rf.causa/cascades` so it inherits the
    ;; Causa-internal filter (cascade-internal ticks never become the
    ;; anchor). It returns nil when the buffer is empty or no cascade
    ;; carries a `:dispatched :time` — the view's render-time fallback
    ;; (`(interop/now-ms)`) covers that edge.
    (rf/reg-sub :rf.causa/relative-time-now-ms
      :<- [:rf.causa/cascades]
      (fn [cascades _query]
        (let [times (into []
                          (keep #(get-in % [:dispatched :time]))
                          cascades)]
          (when (seq times)
            (apply max times)))))

    ;; ---- 4-layer chrome events (rf2-xy4yb / spec/018) -------------

    ;; L3 tab bar — flip the active tab. Six valid ids per spec/018 §5:
    ;; :event :app-db :views :trace :machines :machines-canvas :routing :issues
    ;; (rf2-2moh1 registry-driven; new tab requires only a reg-l4-tab! call).
    (rf/reg-event-db :rf.causa/select-tab
      (fn [db [_ tab-id]]
        (assoc db :selected-tab tab-id)))

    ;; ---- Static-mode chrome (rf2-o5f5f.1) -------------------------
    ;;
    ;; Causa exposes TWO modes per `tools/causa/spec/007-UX-IA.md`
    ;; §Static mode: Runtime (event-coupled spine) and Static
    ;; (registry browse). The mode slot lives on Causa's app-db under
    ;; `:mode` (default `:runtime`); the Static tab selection lives
    ;; under `:rf.causa.static/selected-tab` (default `:machines`).
    ;;
    ;; Three event handlers drive the mode lifecycle:
    ;;
    ;;   - `:rf.causa/set-mode` writes a specific mode (used by the
    ;;     mode pill's per-segment click, by hydration after
    ;;     localStorage read, and by test fixtures).
    ;;   - `:rf.causa/toggle-mode` flips between modes (used by the
    ;;     Cmd-Shift-M chord — see `keybinding.cljs`).
    ;;   - `:rf.causa.static/select-tab` flips the Static-scoped tab
    ;;     (mirrors `:rf.causa/select-tab` but writes the Static
    ;;     surface's own slot so Runtime + Static tab choices don't
    ;;     clobber each other).
    ;;
    ;; The set / toggle handlers attach the `:rf.causa.static/persist-
    ;; mode` fx so every mutation round-trips to localStorage in one
    ;; place. Per `static/persistence.cljs` — the fx swallows
    ;; localStorage errors so a quota / serialisation failure can't
    ;; poison the dispatch chain.

    (rf/reg-sub :rf.causa/mode
      (fn [db _query]
        (static-persistence/normalise-mode (get db :mode :runtime))))

    (rf/reg-sub :rf.causa.static/selected-tab
      (fn [db _query]
        (get db :rf.causa.static/selected-tab static-shell/default-tab)))

    (rf/reg-event-fx :rf.causa/set-mode
      (fn [{:keys [db]} [_ mode]]
        (let [next-mode (static-persistence/normalise-mode mode)
              next-db   (assoc db :mode next-mode)]
          {:db next-db
           :fx [[:rf.causa.static/persist-mode next-mode]]})))

    (rf/reg-event-fx :rf.causa/toggle-mode
      (fn [{:keys [db]} _event]
        (let [current  (static-persistence/normalise-mode (get db :mode :runtime))
              next-mode (if (= current :static) :runtime :static)
              next-db   (assoc db :mode next-mode)]
          {:db next-db
           :fx [[:rf.causa.static/persist-mode next-mode]]})))

    (rf/reg-event-db :rf.causa.static/select-tab
      (fn [db [_ tab-id]]
        (if (contains? (static-shell/tab-ids) tab-id)
          (assoc db :rf.causa.static/selected-tab tab-id)
          db)))

    ;; The persistence fx installs idempotently — re-frame's registrar
    ;; replaces in place. Mounting here means the fx is available the
    ;; moment any `:rf.causa/set-mode` / `:toggle-mode` lands.
    (static-persistence/install-fx!)

    ;; L1 filter pills — add / remove. Mode is :in or :out; index
    ;; identifies the pill within its mode bucket. The pure reducers
    ;; live here so direct dispatchers (palette quick-actions, the
    ;; pill cluster's `×` remove button) stay history-clean; the rich
    ;; edit popup in `filters/save-edit-popup` composes against the
    ;; same slot but threads through the popup's draft. Both surfaces
    ;; share the `:rf.causa.filters/persist` fx so every mutation
    ;; round-trips to localStorage in one place (rf2-ak4ms).
    (rf/reg-event-fx :rf.causa/add-filter
      (fn [{:keys [db]} [_ mode pill]]
        (let [next-db (update-in db [:active-filters mode] (fnil conj []) pill)]
          {:db next-db
           :fx [[:rf.causa.filters/persist (get next-db :active-filters)]]})))

    (rf/reg-event-fx :rf.causa/remove-filter
      (fn [{:keys [db]} [_ mode idx]]
        (let [next-db
              (update-in db [:active-filters mode]
                         (fn [pills]
                           (let [v (or pills [])]
                             (vec (concat (subvec v 0 (min idx (count v)))
                                          (subvec v (min (inc idx) (count v))))))))]
          {:db next-db
           :fx [[:rf.causa.filters/persist (get next-db :active-filters)]]})))

    ;; Ribbon right-icon stubs — wired to events so the click round-
    ;; trips through the registry surface even though the user-facing
    ;; close behaviour is follow-on work. Per rf2-u3qm1 the pop-out
    ;; ribbon button + `:rf.causa/popout` stub are gone — pop-out is
    ;; programmatic-only via `(causa/popout!)` until the second-window
    ;; UX lands (spec/011-Launch-Modes.md). No broken-claim button in
    ;; chrome (silent-by-default — rf2-g3ghh / rf2-yn86j).
    (rf/reg-event-db :rf.causa/open-settings
      (fn [db _event]
        ;; Settings popup lands behind rf2-pending-settings-modal.
        (assoc db :settings-open? true)))

    (rf/reg-event-db :rf.causa/close-shell
      (fn [db _event]
        ;; Close intent — mount.cljs reads the slot in production to
        ;; drive the CSS-only hide. The reactive flag is set here so
        ;; tests can assert the round-trip.
        (assoc db :close-requested? true)))

    ;; ---- trace-buffer mirror events (rf2-in6l2 / rf2-wq6gx) -------
    ;;
    ;; The reactive surface for the layer-1 `:rf.causa/trace-buffer`
    ;; sub is Causa's `:rf/causa` app-db `:trace-buffer` slot. Three
    ;; events drive that slot:
    ;;
    ;;   `:rf.causa/sync-trace-buffer` — wholesale overwrite with a
    ;;     full buffer vector. The PRODUCTION write path under
    ;;     rf2-wq6gx: `trace-bus/request-mirror-sync!` coalesces every
    ;;     same-tick mirror request (collect-trace! / clear-buffer! /
    ;;     set-buffer-depth!) into ONE dispatch of this event carrying
    ;;     `@trace-bus/buffer-state` — capping the cascade at depth 1
    ;;     regardless of host trace-event volume. Also used by
    ;;     `mount.cljs/ensure-causa-frame!` for the first-mount seed.
    ;;
    ;;   `:rf.causa/note-trace-event` — single-event append with
    ;;     capped-vector eviction. The legacy per-event mirror under
    ;;     rf2-in6l2; the production trace-bus collector no longer
    ;;     dispatches this (replaced by the coalesced sync above to
    ;;     fix the drain-depth saturation regression — rf2-wq6gx).
    ;;     Retained as a registered handler for direct callers
    ;;     (consumer-test surface in
    ;;     `tools/causa/test/.../registry_cljs_test.cljs` and
    ;;     `panels/trace_view_cljs_test.cljs`).
    ;;
    ;;   `:rf.causa/clear-trace-buffer` — drop the slot entirely.
    ;;     The production clear path under rf2-wq6gx routes through
    ;;     the coalesced sync (an empty atom snapshot clears the
    ;;     slot), but this event is retained for direct callers and
    ;;     for the test surface that asserts the documented contract.
    ;;
    ;; Per rf2-qsjda + the matching `:rf.causa/note-sensitive-
    ;; suppressed` pattern: `:rf.trace/no-emit? true` opts every
    ;; handler in this group out of framework trace emission. Without
    ;; it the dispatch would emit `:event/dispatched` etc. back
    ;; through the trace-cb fan-out, the collector would see its own
    ;; self-emit, and the cascade would loop until `drain-depth-
    ;; default` terminated it.
    ;;
    ;; ## Why snapshot semantics obviate the rf2-z4fza dedup walk
    ;;
    ;; The original rf2-in6l2 per-event mirror dispatched
    ;; `:rf.causa/note-trace-event` for every trace event AND seeded
    ;; via `:rf.causa/sync-trace-buffer` at mount time. The seed and
    ;; the per-event mirror raced inside `ensure-causa-frame!`: the
    ;; `(rf/reg-frame :rf/causa {})` call synchronously emitted a
    ;; `:frame/created` trace event whose note-trace-event dispatch
    ;; queued before the seed's dispatch-sync ran; once the queued
    ;; dispatch drained, it re-pushed the same `:id` already inside
    ;; the seeded slot, producing duplicate React keys (per rf2-z4fza
    ;; the Trace panel keys `<li>`s on `t:<id>`). The fix was an
    ;; O(slot) dedup walk inside `:rf.causa/note-trace-event`.
    ;;
    ;; Under the rf2-wq6gx coalesced-snapshot design the production
    ;; path NEVER appends per-event into the slot — every mirror is a
    ;; wholesale overwrite. Seeds and post-seed syncs idempotently
    ;; write the atom's current contents; a duplicate `:id` is
    ;; structurally impossible in production. The dedup walk inside
    ;; `:rf.causa/note-trace-event` is retained because the handler
    ;; is still callable by tests that exercise the legacy per-event
    ;; shape — there it preserves the rf2-z4fza contract those tests
    ;; assert.
    (rf/reg-event-db :rf.causa/note-trace-event
      {:rf.trace/no-emit? true}
      (fn [db [_ event]]
        (let [buf   (get db :trace-buffer [])
              depth (trace-bus/current-depth)
              ev-id (:id event)]
          (if (and ev-id (some #(= ev-id (:id %)) buf))
            db
            ;; Dual-write: keep the raw `:trace-buffer` slot in
            ;; lockstep with the incrementally-maintained
            ;; `:trace-feed-state` snapshot (rf2-44vzy). The trace
            ;; panel's `:rf.causa/trace-feed` sub reads
            ;; `:trace-feed-state`; every other consumer that holds
            ;; a raw event vector keeps reading `:trace-buffer`.
            ;; `feed-state-push` mirrors `trace-bus/push`'s capped
            ;; eviction so the snapshot stays bounded by the same
            ;; depth contract.
            (let [feed-state (or (get db :trace-feed-state)
                                 (trace-helpers/init-feed-state))]
              (-> db
                  (assoc :trace-buffer
                         (trace-bus/push buf depth event))
                  (assoc :trace-feed-state
                         (trace-helpers/feed-state-push
                           feed-state depth event))))))))

    ;; Clear the mirrored slot in lockstep with the trace-bus atom
    ;; (dispatched from `trace-bus/clear-buffer!` in CLJS). Per
    ;; rf2-qsjda the `:rf.trace/no-emit?` flag applies for the same
    ;; loop-avoidance reason as `:rf.causa/note-trace-event`.
    ;;
    ;; Per rf2-44vzy: drop `:trace-feed-state` alongside the raw
    ;; buffer so the precomputed projection inherits the same
    ;; retroactive-scrub guarantee `trace-bus/clear-buffer!`
    ;; provides — no projection residue surviving a privacy toggle
    ;; (rf2-lqmje §Privacy / retroactive-scrub).
    (rf/reg-event-db :rf.causa/clear-trace-buffer
      {:rf.trace/no-emit? true}
      (fn [db _event]
        (-> db
            (dissoc :trace-buffer)
            (dissoc :trace-feed-state))))

    ;; Wholesale overwrite of the mirrored slot. Dispatched from
    ;; `mount.cljs/open!` on first Ctrl+Shift+C to seed `:rf/causa`'s
    ;; app-db with whatever the trace-bus atom has accumulated before
    ;; the shell was opened, and from `trace-bus/set-buffer-depth!`
    ;; when the depth shrinks so the mirror reflects the post-shrink
    ;; contents. Per rf2-qsjda `:rf.trace/no-emit? true` for the same
    ;; loop-avoidance reason.
    ;;
    ;; Per rf2-44vzy: rebuild `:trace-feed-state` from the seeded
    ;; buffer so the incremental projection starts in lockstep with
    ;; the slot. The rebuild is O(buffer × axes); paid once on seed
    ;; (mount-time or post-shrink) — every subsequent note-trace-
    ;; event runs in O(axes).
    (rf/reg-event-db :rf.causa/sync-trace-buffer
      {:rf.trace/no-emit? true}
      (fn [db [_ buffer]]
        (let [buf (vec buffer)]
          (-> db
              (assoc :trace-buffer buf)
              (assoc :trace-feed-state
                     (trace-helpers/rebuild-feed-state buf))))))

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

    ;; ---- per-panel installations --------------------------------
    ;;
    ;; Each panel owns its own subs / events / fxs in
    ;; `panels/<panel>.cljs` under `(defn install! [] ...)`. Order is
    ;; alphabetised — re-frame resolves `:<-` chains lazily at
    ;; subscribe time so registration order is purely cosmetic.
    ;;
    ;; The open-in-editor install is cross-panel — its
    ;; `:rf.causa/open-in-editor` event-fx + `:rf.editor/open` fx are
    ;; dispatched from trace, issues-ribbon, mcp-server, and the
    ;; hydration debugger (rf2-g5q8d). Installed alongside the
    ;; per-panel installs so the registration order matches the
    ;; per-panel pattern.

    ;; Cross-cutting epoch primitives (rf2-qy0nu — extracted from the
    ;; deleted Time Travel panel). MUST install before app-db-diff +
    ;; views + machine-inspector — their composites :<- onto
    ;; `:rf.causa/epoch-history` / `:rf.causa/target-frame` /
    ;; `:rf.causa/selected-epoch-id`. Registration order is cosmetic
    ;; (re-frame resolves :<- lazily), but the top-down dependency read
    ;; is clearer.
    (epoch/install!)
    (open-in-editor/install!)
    (palette/install!)
    (settings-popup/install!)
    ;; Filters install AFTER `:rf.causa/active-filters` + the
    ;; add-filter / remove-filter events above are registered (the
    ;; filters facade adds `:rf.causa/filtered-cascades` + the edit-
    ;; popup events + the persistence fx + hydrates the slot from
    ;; localStorage). Hydration runs through the orchestrator so the
    ;; idempotency sentinel above prevents the hydrate-dispatch from
    ;; firing twice on shadow-cljs `:after-load`.
    (filters/install!)
    ;; Per-event-id mute filter (rf2-ikuwt). Installs the
    ;; `:rf.causa/muted-event-ids` slot + the mute / unmute / clear
    ;; events + the localStorage persistence fx + the unmute manager
    ;; modal open / close state. Installs AFTER `filters/install!` so
    ;; the `:rf.causa/filtered-cascades` sub it composes against is
    ;; already registered; the mute filter wraps the filtered-cascades
    ;; via the standalone `:rf.causa/spine-filtered-cascades` sub
    ;; below (the L2 event list + spine consumers swap their dependency
    ;; from `filtered-cascades` → `spine-filtered-cascades` so the
    ;; mute strip rides atop the existing IN/OUT pill filter).
    (spine-filters/install!)
    ;; Spine MUST install before event-detail / time-travel — their
    ;; legacy selection events shim writes through the spine slot,
    ;; and the slot's reducer helpers live in spine.cljs.
    (spine/install!)
    ;; Frame-switcher slot (rf2-iwwou) — hardened L1 frame-switcher
    ;; contract. MUST install AFTER `spine/install!` so the canonical
    ;; `:rf.causa/select-frame` event-fx's `[:dispatch [:rf.causa/set-
    ;; frame ...]]` resolves at hydration time. Registers the
    ;; `:rf.causa/current-frame` + `:rf.causa/available-frames` subs,
    ;; the `:rf.causa/select-frame` event-fx, the `:rf.causa.frame-
    ;; switcher/persist` localStorage write fx, and hydrates the
    ;; `[:focus :frame]` slot from localStorage at first install.
    (frame-switcher/install!)
    (app-db-diff/install!)
    ;; App-DB segment-inspector popup (rf2-e9tb0) — opens when any
    ;; path-segment in the App-DB Diff breadcrumb is clicked. Installs
    ;; after `app-db-diff` so the segment-inspector's
    ;; `:rf.causa/segment-inspector-value` sub can chain off
    ;; `:rf.causa/target-frame-db` (registered by app-db-diff's subs).
    ;; Registration order is cosmetic — re-frame resolves `:<-` lazily;
    ;; the top-down dependency reading is the rationale.
    (app-db-segment-inspector/install!)
    ;; Cancellation-cascade visualiser (rf2-59e7k) — installs the
    ;; subs + events for the Machines tab side-panel + the trace-row
    ;; popover. The view-side `reg-view`s are picked up at ns-load.
    ;; Order: registers AFTER spine + cascades (composes against
    ;; `:rf.causa/focus` + `:rf.causa/trace-buffer`); registry order is
    ;; cosmetic since re-frame resolves `:<-` chains lazily.
    (cancellation-cascade/install!)
    (event-detail/install!)
    (issues-ribbon/install!)
    (machine-inspector/install!)
    ;; Runtime Machines Canvas tab (rf2-mkpnb) — spine-INDEPENDENT canvas
    ;; browser. Sibling tab to the event-driven Machines Inspector
    ;; (above): picker on the left, interactive `machine-canvas/Chart`
    ;; on the right. Installs AFTER `machine-inspector/install!` so the
    ;; canvas adapter's subs / events / fxs (`:rf.causa.machine-
    ;; canvas/*`) are already registered. Registration order is purely
    ;; cosmetic — re-frame resolves `:<-` chains lazily.
    (machines-canvas-panel/install!)
    ;; Static Machines sub-tab (rf2-o5f5f.2) — browses every registered
    ;; machine + Topology + JUMP-to-Runtime + Cascade-dimmed surfaces.
    ;; Installs AFTER `machine-inspector/install!` so the static-machines
    ;; sub graph can :<- onto the existing `:rf.causa/registered-machines`,
    ;; `:rf.causa/machine-definitions`, and `:rf.causa/machine-snapshots`
    ;; subs without redeclaring them. Registration order is purely
    ;; cosmetic (re-frame resolves `:<-` chains lazily); the top-down
    ;; dependency read is clearer.
    (static-machines-panel/install!)
    ;; Managed-fx wire-boundary diff template (rf2-uyp86) — installs the
    ;; `:rf.causa/managed-fx-for-focused-event` sub + the
    ;; `:rf.causa/focus-event` cross-link event. The panel view itself
    ;; mounts inline in event_detail.cljs under the six-domino cascade,
    ;; so no L3 tab is added.
    (managed-fx-subs/install!)
    ;; Routing tab (rf2-nrbs9, narrowed per rf2-o5f5f.3) — Runtime L3
    ;; tab carrying the focused-event lens (FROM/TO chips when the
    ;; focused event triggered navigation). Installs the registered-
    ;; routes / current-route-slice / routing-tab-data subs +
    ;; test-only overrides. Composes against `:rf.causa/target-frame-db`
    ;; (registered by `app-db-diff/install!` above) so the install
    ;; order is intentional, though re-frame's `:<-` resolution is
    ;; lazy and the order is purely cosmetic.
    (routing/install!)
    ;; Static Routes panel (rf2-o5f5f.3) — Static-surface browse +
    ;; Simulate-URL + per-row inline expand + hermetic Simulate-
    ;; navigation preview. Installs the UI-state slots under
    ;; `:rf.causa.static.routes/*` + the `:rf.causa.static.routes/
    ;; tab-data` composite. Reads `:rf.causa/registered-routes` (just
    ;; registered above) — order is cosmetic since re-frame resolves
    ;; `:<-` chains lazily.
    (static-routes-panel/install!)
    ;; Static Schemas sub-tab (rf2-o5f5f.4) — browse every registered
    ;; Malli schema across app-db slots + events + subs. Reads
    ;; `re-frame.schemas.storage/schemas-by-frame` + the registrar's
    ;; `:event` / `:sub` `:spec` slots. Source-coord chip dispatches
    ;; `:rf.causa/open-in-editor` (open-in-editor installed above).
    (static-schemas-panel/install!)
    ;; Static Views sub-tab (rf2-o5f5f.5) — browse every registered
    ;; view via the registrar's `:view` slot. Pure-browse verb —
    ;; event-INDEPENDENT registry catalogue (no Fiber-walker live
    ;; mount tree at this surface; that's the Runtime Views panel's
    ;; concern).
    (static-views-panel/install!)
    ;; Static Flows sub-tab (rf2-uhsqb) — browse every flow registered
    ;; via `re-frame.flows/reg-flow`. Reads the per-frame flows atom
    ;; `re-frame.flows.registry/flows`. Flows are frame-scoped per
    ;; Spec 013 — the sub flattens the two-level shape into a single
    ;; row vector for the view.
    (static-flows-panel/install!)
    (reactive-panel/install!)
    (trace/install!)
    ;; Chrome A11y tab (rf2-5r2yj) — Runtime L4 tab that runs axe-core
    ;; scoped to `#rf-causa-root` (Causa's own chrome). Mirror of
    ;; Story's chrome-a11y panel (PR #1695). No app-db registrations —
    ;; the panel's state lives in module-level `r/atom`s because the
    ;; data has no other consumer; the install! only adds the L4 tab
    ;; entry. Sits at order 8 (after Issues at 7) — both tabs occupy
    ;; the diagnostics group at the right end of the tab strip.
    (chrome-a11y-panel/install!)
    ;; rf2-2moh1 — register the Static :events placeholder tab. The
    ;; other Static tabs (machines / routes / schemas / views / flows)
    ;; each register their entry from their own panel's `install!`
    ;; above; the `:events` tab is not yet panelled (sibling bead
    ;; rf2-o5f5f.6 fills it) so its placeholder registration lives
    ;; in `static.shell` alongside the placeholder-card helper.
    (static-shell/register-events-placeholder!))
  nil)

(defn reset-for-test!
  "Reset the registry's idempotency sentinel so test fixtures can drive
  multiple registration cycles. Test-only — never call from production
  code."
  []
  (reset! registered? false)
  nil)
