(ns day8.re-frame2-causa.registry
  "Causa's `:rf.causa/*` event / sub / fx registrations.

  Two collision contracts: the `:rf.causa/*` keyword prefix prevents
  id collisions against the host's registrations (the registrar is
  process-global); the `:rf/causa` frame prevents Causa's db reads /
  writes from leaking into the host's app-db.

  This ns owns only the cross-panel primitives (trace-buffer sub,
  panel-selection slot, shared cascades projection, suppression-
  counter handlers) plus orchestration calls into each per-panel
  `install!`. Per-panel registrations live in their panel's own ns."
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

;; ---- defaults (re-exports) ----------------------------------------------
;; The Vars live in `defaults` so panel `install!` fns can read them
;; without forming a registry→panel→registry cycle.

(def default-panel-id defaults/default-panel-id)
(def default-target-frame defaults/default-target-frame)

;; ---- idempotency sentinel ------------------------------------------------

(defonce ^:private registered?
  ;; Re-registration on `:after-load` would emit a
  ;; `:rf.warning/handler-replaced` trace per registration and
  ;; flood the dev console on every reload.
  (atom false))

(defn register-causa-handlers!
  "Idempotent registration of Causa's :rf.causa/* events, subs, fxs.
  Called from `day8.re-frame2-causa.preload` at load time. Safe to
  call multiple times — second + subsequent calls are no-ops."
  []
  (when (compare-and-set! registered? false true)
    ;; ---- cross-panel primitives ---------------------------------

    ;; Causa's own ring buffer (NOT the framework's `rf/trace-buffer`).
    ;; The buffer is process-global, not per-frame — every Causa shell
    ;; mounted across any frame sees the same trace stream. Layer-1
    ;; sub re-fires on the host's next dispatch and picks up whatever
    ;; the trace-cb has accumulated; see `trace_bus.cljc` §Reactivity.
    (rf/reg-sub :rf.causa/trace-buffer
      (fn [_db _query]
        (trace-bus/buffer)))

    ;; Total count of `:sensitive?` trace events suppressed under the
    ;; current `:trace/show-sensitive?` setting. Drives the shell's
    ;; bottom-rail `[● REDACTED N]` indicator. The CLJS path
    ;; dual-writes via dispatch (this counter) and `config/suppressed-
    ;; counters` (the JVM-runnable data primitive); the dispatch keeps
    ;; the reactive surface in sync without depending on sibling-sub
    ;; recompute.
    (rf/reg-sub :rf.causa/suppressed-sensitive-count
      (fn [db _query]
        (reduce + 0 (vals (get db :suppressed-counters {})))))

    ;; Currently-active panel — drives the canvas's switch in shell.cljs.
    (rf/reg-sub :rf.causa/selected-panel
      (fn [db _query]
        (get db :selected-panel defaults/default-panel-id)))

    ;; Shared cascade projection: routing event-detail, causality-graph,
    ;; and performance through one intermediate sub collapses three
    ;; O(buffer) passes per push to one.
    (rf/reg-sub :rf.causa/cascades
      :<- [:rf.causa/trace-buffer]
      (fn [buffer _query]
        (projection/group-cascades buffer)))

    ;; Cross-panel sidebar selection.
    (rf/reg-event-db :rf.causa/select-panel
      (fn [db [_ panel-id]]
        (assoc db :selected-panel panel-id)))

    ;; Bump the per-frame suppressed-events counter. `frame-id` is the
    ;; event's `:tags :frame`; `nil` falls under `:global`.
    ;;
    ;; `:rf.trace/no-emit? true` is load-bearing: without it the
    ;; dispatch fired by `trace-bus/collect-trace!` would re-emit
    ;; `:event/dispatched` back through the trace-cb fan-out and the
    ;; cascade would loop until `drain-depth-default` terminated it.
    (rf/reg-event-db :rf.causa/note-sensitive-suppressed
      {:rf.trace/no-emit? true}
      (fn [db [_ frame-id]]
        (update-in db [:suppressed-counters (or frame-id :global)]
                   (fnil inc 0))))

    ;; Reset counters; with a `frame-id` drops just that bucket.
    ;; `:rf.trace/no-emit? true` rationale: see above.
    (rf/reg-event-db :rf.causa/reset-suppressed-counters
      {:rf.trace/no-emit? true}
      (fn [db [_ frame-id]]
        (if frame-id
          (update db :suppressed-counters dissoc (or frame-id :global))
          (dissoc db :suppressed-counters))))

    ;; ---- per-panel installations (alphabetised) -----------------

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
