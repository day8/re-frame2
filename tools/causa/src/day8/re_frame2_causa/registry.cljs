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
            [day8.re-frame2-causa.defaults :as defaults]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.ai-co-pilot :as ai-co-pilot]
            [day8.re-frame2-causa.panels.app-db-diff :as app-db-diff]
            [day8.re-frame2-causa.panels.causality-graph :as causality-graph]
            [day8.re-frame2-causa.panels.effects :as effects]
            [day8.re-frame2-causa.panels.event-detail :as event-detail]
            [day8.re-frame2-causa.panels.flows :as flows]
            [day8.re-frame2-causa.panels.hydration-debugger :as hydration-debugger]
            [day8.re-frame2-causa.panels.issues-ribbon :as issues-ribbon]
            [day8.re-frame2-causa.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-causa.panels.mcp-server :as mcp-server]
            [day8.re-frame2-causa.panels.performance :as performance]
            [day8.re-frame2-causa.panels.routes :as routes]
            [day8.re-frame2-causa.panels.schema-violation-timeline :as schema-violation-timeline]
            [day8.re-frame2-causa.panels.subscriptions :as subscriptions]
            [day8.re-frame2-causa.panels.time-travel :as time-travel]
            [day8.re-frame2-causa.panels.trace :as trace]))

;; ---- defaults (re-exported for back-compat callers) ---------------------
;;
;; The Vars themselves live in `day8.re-frame2-causa.defaults` so the
;; per-panel `install!` fns can read them without forming a
;; registry→panel→registry cycle. Re-exported here so the shell + the
;; existing test surface keep reading `registry/default-panel-id` /
;; `registry/default-target-frame` — same source of truth.

(def default-panel-id defaults/default-panel-id)
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
    ;; contents (NOT the framework's `(rf/trace-buffer)`). Reading
    ;; directly from `trace-bus/buffer` is the right shape here
    ;; because the buffer is process-global, not per-frame — every
    ;; Causa shell mounted across any frame should see the same
    ;; trace stream. The sub thunks the pure-data accessor so
    ;; reactive contexts get a fresh read on every recompute;
    ;; layer-1 re-fires on the host's next dispatch and picks up
    ;; whatever the trace-cb has accumulated. See `trace_bus.cljc`
    ;; §Reactivity for the trade-off and the refactor path that
    ;; would re-introduce immediate-update reactivity without the
    ;; layering hazard.
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
        (get db :selected-panel defaults/default-panel-id)))

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

    ;; Cross-panel sidebar selection.
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

    ;; ---- per-panel installations --------------------------------
    ;;
    ;; Each panel owns its own subs / events / fxs in
    ;; `panels/<panel>.cljs` under `(defn install! [] ...)`. Order is
    ;; alphabetised — re-frame resolves `:<-` chains lazily at
    ;; subscribe time so registration order is purely cosmetic.

    (ai-co-pilot/install!)
    (app-db-diff/install!)
    (causality-graph/install!)
    (effects/install!)
    (event-detail/install!)
    (flows/install!)
    (hydration-debugger/install!)
    (issues-ribbon/install!)
    (machine-inspector/install!)
    (mcp-server/install!)
    (performance/install!)
    (routes/install!)
    (schema-violation-timeline/install!)
    (subscriptions/install!)
    (time-travel/install!)
    (trace/install!))
  nil)

(defn reset-for-test!
  "Reset the registry's idempotency sentinel so test fixtures can drive
  multiple registration cycles. Test-only — never call from production
  code."
  []
  (reset! registered? false)
  nil)
