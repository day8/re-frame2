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
            [day8.re-frame2-causa.open-in-editor :as open-in-editor]
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
            [day8.re-frame2-causa.panels.trace :as trace]
            [day8.re-frame2-causa.panels.trace-helpers :as trace-helpers]))

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
    ;; contents (NOT the framework's `(rf/trace-buffer)`). Per rf2-in6l2
    ;; the slot lives in Causa's app-db at `:trace-buffer`; the trace
    ;; collector dispatches `:rf.causa/note-trace-event` into `:rf/causa`
    ;; on every push so the sub re-fires on the standard app-db-write
    ;; reactive path — panels re-render IMMEDIATELY on every trace
    ;; event with no dependency on the host's next dispatch (the prior
    ;; rf2-e9s81 shape thunked `trace-bus/buffer` directly so visible
    ;; delay was bounded by the host's next dispatch).
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

    ;; ---- trace-buffer mirror events (rf2-in6l2) -------------------
    ;;
    ;; `collect-trace!` dispatches `:rf.causa/note-trace-event` into
    ;; `:rf/causa` on every push so the layer-1 `:rf.causa/trace-buffer`
    ;; sub fires on the standard app-db-write reactive path. The event
    ;; mirrors `trace-bus/push`'s capped-vector eviction algebra so the
    ;; app-db slot stays bounded by the same depth contract as the
    ;; trace-bus atom — single source of truth on the cap, dual write
    ;; for the reactive surface.
    ;;
    ;; The mirror is *additive*: `trace-bus/buffer-state` remains as
    ;; the JVM-runnable data primitive (push algebra, sensitive-trace
    ;; tests, filter-vocab consumer tests) and as the seed source when
    ;; `mount.cljs/open!` first registers `:rf/causa`. The CLJS path
    ;; dual-writes atom + dispatch.
    ;;
    ;; Per rf2-qsjda + the matching `:rf.causa/note-sensitive-
    ;; suppressed` pattern: `:rf.trace/no-emit? true` opts the handler
    ;; out of framework trace emission. Without it the dispatch would
    ;; emit `:event/dispatched` etc. back through the trace-cb fan-out,
    ;; the collector would see its own self-emit, and the cascade would
    ;; loop until `drain-depth-default` terminated it.
    ;;
    ;; ## Seed-race dedup (rf2-z4fza follow-up)
    ;;
    ;; The mount-time seed path (`ensure-causa-frame!` in mount.cljs)
    ;; runs in this order:
    ;;
    ;;   1. `(rf/reg-frame :rf/causa {})` — registers the frame AND
    ;;      synchronously emits a `:frame/created` trace event for
    ;;      `:rf/causa`. `collect-trace!` runs for that event:
    ;;        a. atom push (synchronous);
    ;;        b. `mirror-into-causa!` checks `(frame/frame :rf/causa)`
    ;;           — the frame was just registered above, so it exists.
    ;;           A `:rf.causa/note-trace-event` dispatch is QUEUED.
    ;;   2. `(rf/dispatch-sync [:rf.causa/sync-trace-buffer (trace-bus/buffer)])`
    ;;      — runs synchronously; seeds the slot with the atom snapshot
    ;;      (which already contains the `:frame/created` event).
    ;;
    ;; When the queued note-trace-event from step 1b eventually drains,
    ;; the seeded slot already carries that event — re-pushing produces
    ;; a duplicate-`:id` pair inside the slot vector. The Trace panel
    ;; keys its `<li>`s on `t:<id>` (rf2-z4fza) so duplicate ids become
    ;; duplicate React keys: React warns AND leaves one extra stale
    ;; `<li>` in the DOM that survives subsequent renders even after
    ;; cap-eviction removes both copies from the data — pushing the
    ;; rendered viewport over the 200-row budget by one indefinitely.
    ;;
    ;; The dedup: the framework's `next-event-id` is a process-wide
    ;; monotonic counter, so `:id` is a true identity for the event.
    ;; If the incoming event's `:id` is already present anywhere in the
    ;; slot, the seed has already mirrored it — drop the push. The
    ;; scan is `O(slot)` but the slot is bounded by the cap (1000 by
    ;; default) and the seed window only fires once per session, so
    ;; the steady-state cost is one walk per emit. A tail-only check
    ;; would be cheaper but is incorrect: events arrive at the slot in
    ;; arrival order via `mirror-into-causa!`, so the dup from the
    ;; seed race lands SOMEWHERE in the seeded prefix, not always at
    ;; the tail.
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

    (open-in-editor/install!)
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
