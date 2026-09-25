(ns day8.re-frame2-xray.panels.cancellation-cascade-events
  "Events for the Cancellation-cascade visualiser.

  The visualiser's event surfaces:

    `:rf.xray/cancellation-cascade-open`    ; popover open (anchor focus)
    `:rf.xray/cancellation-cascade-close`   ; popover close
    `:rf.xray/cancellation-cascade-toggle-expand` ; show-all-N toggle
    `:rf.xray/focus-trace-entry`            ; jump-to-trace from a row
    `:rf.xray/cancellation-cascade-set-expanded`
       ; set the show-all-N flag explicitly

  All events run against Xray's `:rf/xray` frame; the storage slots
  live at `:cancellation-cascade-popover-open?`,
  `:cancellation-cascade-popover-focus` (the `{:kind :dispatch-id | :machine-id
  :id <id>}` map the visualiser uses to pick its anchor), and
  `:cancellation-cascade-expanded?`.

  `:rf.xray/focus-trace-entry` is a small jump-event the visualiser
  rows dispatch when clicked — it delegates into the
  `:rf.xray/select-dispatch-id` spine shim when a dispatch-id is
  present, otherwise no-ops. It is the 'click a row → jump to the
  trace entry' affordance."
  (:require [re-frame.core :as rf]))

(defn install!
  "Install the cancellation-cascade visualiser's events. Idempotent
  under re-frame's replace-in-place registrar — second + subsequent
  calls are harmless beyond the `:rf.warning/handler-replaced` trace
  which the orchestrator's `registered?` sentinel already protects
  against."
  []

  ;; ---- popover open / close --------------------------------------------

  (rf/reg-event :rf.xray/cancellation-cascade-open
    (fn [{:keys [db]} [_ focus]]
      {:db (-> db
          (assoc :cancellation-cascade-popover-open? true)
          (assoc :cancellation-cascade-popover-focus focus))}))

  (rf/reg-event :rf.xray/cancellation-cascade-close
    (fn [{:keys [db]} _event]
      {:db (assoc db :cancellation-cascade-popover-open? false)}))

  ;; ---- show-all / collapse toggle --------------------------------------

  (rf/reg-event :rf.xray/cancellation-cascade-toggle-expand
    (fn [{:keys [db]} _event]
      {:db (update db :cancellation-cascade-expanded? not)}))

  (rf/reg-event :rf.xray/cancellation-cascade-set-expanded
    (fn [{:keys [db]} [_ expanded?]]
      {:db (assoc db :cancellation-cascade-expanded? (boolean expanded?))}))

  ;; ---- focus-trace-entry: row-click jump --------------------------------
  ;;
  ;; Clicking any row in the visualiser
  ;; surfaces the underlying trace entry. The row carries the dispatch-id
  ;; (when the trace event was emitted during a drain); we delegate into
  ;; the spine shim `:rf.xray/select-dispatch-id` so the
  ;; Epoch panel takes the focus pivot.
  (rf/reg-event :rf.xray/focus-trace-entry
    (fn [_ctx [_ {:keys [dispatch-id frame trace-id]}]]
      ;; trace-id rides through unused: the dispatch-id is
      ;; the only addressable axis the spine accepts. When no dispatch-id
      ;; is available (e.g. an actor-destroy abort outside a drain), the
      ;; event becomes a no-op — the visualiser still has the trace-id
      ;; pinned via the row's data-testid so the user can grep on it.
      ;;
      ;; The re-dispatched events target the SURROUNDING
      ;; instance frame captured at handler entry (the handler runs in
      ;; the dispatching frame's context), not a `{:frame :rf/xray}`
      ;; literal that pins the singleton.
      (let [here (rf/current-frame-id)]
        {:fx (cond-> []
               dispatch-id
               (conj [:dispatch [[:rf.xray/select-dispatch-id
                                  dispatch-id frame]
                                 {:frame here}]])
               ;; Flip the visible tab to Epoch so the row jump lands on
               ;; the focused cascade's pipeline detail. Epoch is the
               ;; cascade-pipeline master view
               ;; the `:rf.xray/select-dispatch-id` spine pin drives
               ;; (epoch_panel.cljs :order -1), and `:epoch` is a LIVE
               ;; Dynamic L4 tab (focus.cljc `valid-panels`). There is no
               ;; `:event` tab, so selecting one would land the shell's
               ;; unknown-tab stub, and the 4-layer shell reads no
               ;; `:rf.xray/select-panel` slot.
               dispatch-id
               (conj [:dispatch [[:rf.xray/select-tab :epoch]
                                 {:frame here}]])
               ;; Also close the popover when a row jump fires — the
               ;; user has navigated away.
               true
               (conj [:dispatch [[:rf.xray/cancellation-cascade-close]
                                 {:frame here}]]))})))

  nil)
