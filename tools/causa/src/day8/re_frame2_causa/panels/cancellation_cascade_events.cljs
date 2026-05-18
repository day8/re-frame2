(ns day8.re-frame2-causa.panels.cancellation-cascade-events
  "Events for the Cancellation-cascade visualiser (rf2-59e7k).

  Per the bead's contract the visualiser has three event surfaces:

    `:rf.causa/cancellation-cascade-open`    ; popover open (anchor focus)
    `:rf.causa/cancellation-cascade-close`   ; popover close
    `:rf.causa/cancellation-cascade-toggle-expand` ; show-all-N toggle
    `:rf.causa/focus-trace-entry`            ; jump-to-trace from a row
    `:rf.causa/cancellation-cascade-set-collapse-default`
       ; per-panel collapse default override (test hook)

  All events run against Causa's `:rf/causa` frame; the storage slots
  live at `:cancellation-cascade-popover-open?`,
  `:cancellation-cascade-popover-focus` (the `{:kind :dispatch-id | :machine-id
  :id <id>}` map the visualiser uses to pick its anchor), and
  `:cancellation-cascade-expanded?`.

  `:rf.causa/focus-trace-entry` is a small jump-event the visualiser
  rows dispatch when clicked — it delegates into the existing
  `:rf.causa/select-dispatch-id` spine shim when a dispatch-id is
  present, otherwise no-ops. The slot is the bead's contract for
  'click a row → jump to the trace entry'."
  (:require [re-frame.core :as rf]))

(defn install!
  "Install the cancellation-cascade visualiser's events. Idempotent
  under re-frame's replace-in-place registrar — second + subsequent
  calls are harmless beyond the `:rf.warning/handler-replaced` trace
  which the orchestrator's `registered?` sentinel already protects
  against."
  []

  ;; ---- popover open / close --------------------------------------------

  (rf/reg-event-db :rf.causa/cancellation-cascade-open
    (fn [db [_ focus]]
      (-> db
          (assoc :cancellation-cascade-popover-open? true)
          (assoc :cancellation-cascade-popover-focus focus))))

  (rf/reg-event-db :rf.causa/cancellation-cascade-close
    (fn [db _event]
      (assoc db :cancellation-cascade-popover-open? false)))

  ;; ---- show-all / collapse toggle --------------------------------------

  (rf/reg-event-db :rf.causa/cancellation-cascade-toggle-expand
    (fn [db _event]
      (update db :cancellation-cascade-expanded? not)))

  (rf/reg-event-db :rf.causa/cancellation-cascade-set-expanded
    (fn [db [_ expanded?]]
      (assoc db :cancellation-cascade-expanded? (boolean expanded?))))

  ;; ---- focus-trace-entry: row-click jump --------------------------------
  ;;
  ;; Per the bead's contract: clicking any row in the visualiser
  ;; surfaces the underlying trace entry. The row carries the dispatch-id
  ;; (when the trace event was emitted during a drain); we delegate into
  ;; the legacy spine shim `:rf.causa/select-dispatch-id` so the
  ;; existing event-detail panel takes the focus pivot.
  (rf/reg-event-fx :rf.causa/focus-trace-entry
    (fn [_ctx [_ {:keys [dispatch-id frame trace-id]}]]
      ;; trace-id rides through for the future per-event focus surface
      ;; (rf2-pending event-row direct focus); today the dispatch-id is
      ;; the only addressable axis the spine accepts. When no dispatch-id
      ;; is available (e.g. an actor-destroy abort outside a drain), the
      ;; event becomes a no-op — the visualiser still has the trace-id
      ;; pinned via the row's data-testid so the user can grep on it.
      {:fx (cond-> []
             dispatch-id
             (conj [:dispatch [[:rf.causa/select-dispatch-id
                                dispatch-id frame]
                               {:frame :rf/causa}]])
             ;; Flip the visible tab to Event so the row jump lands
             ;; in event-detail. The legacy `:rf.causa/select-panel`
             ;; slot is no longer read by the 4-layer shell (rf2-qy0nu).
             dispatch-id
             (conj [:dispatch [[:rf.causa/select-tab :event]
                               {:frame :rf/causa}]])
             ;; Also close the popover when a row jump fires — the
             ;; user has navigated away.
             true
             (conj [:dispatch [[:rf.causa/cancellation-cascade-close]
                               {:frame :rf/causa}]]))}))

  nil)
