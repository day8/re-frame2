(ns day8.re-frame2-xray.panels.managed-fx-subs
  "Composite subs for the managed-fx wire-boundary diff template
  (rf2-uyp86, parent rf2-5aw5v).

  The panel template (`panels/managed_fx_template`) is the renderer;
  this ns produces the data the renderer consumes. One read produces
  every slot the view needs:

      {:records [<record> ...]}

  where each record satisfies the eight-property contract from
  [`panels/managed-fx-helpers/cascade->managed-fx-records`](managed_fx_helpers.cljc).

  The composite is keyed to the spine's `:rf.xray/focus` — it
  recomputes whenever the user clicks a different cascade in the L2
  event list, scrubs through history, or a new cascade lands at head
  in LIVE mode.

  ## Cross-link

  Records carry `:origin-event-id` so the panel's HANDLER DISPATCHED
  row can wire `:on-click` to `:rf.xray/focus-event` — clicking pivots
  the spine to the child cascade the response handler kicks off. The
  cross-link event lives here so panel views stay thin."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.panels.managed-fx-helpers :as h]
            [day8.re-frame2-xray.spine :as spine]))

;; ---- public install -----------------------------------------------------

(defn install!
  "Idempotent install — register `:rf.xray/managed-fx-for-focused-event`
  + the `:rf.xray/focus-event` cross-link event. Called from
  `registry.cljs`'s `register-xray-handlers!` fan-out."
  []

  ;; Composite sub — produces the records vector for the focused
  ;; cascade. Re-derives on every spine flip / cascade-list change /
  ;; focus move.
  (rf/reg-sub :rf.xray/managed-fx-for-focused-event
    :<- [:rf.xray/cascades]
    :<- [:rf.xray/focus]
    (fn [[cascades focus] _query]
      ;; rf2-bz7flo — resolve the focused cascade frame-strictly. Dispatch
      ;; ids are unique only within a frame, so keying by dispatch-id alone
      ;; could surface managed-fx rows from a foreign frame's same-id cascade
      ;; while the returned `:frame` is the focused frame. `cascade-by-focus`
      ;; keys by both `:frame` + `:dispatch-id` when focus carries a frame.
      (let [dispatch-id (:dispatch-id focus)
            cascade     (spine/cascade-by-focus cascades focus)]
        {:dispatch-id dispatch-id
         :frame       (:frame focus)
         :records     (if cascade
                        (h/cascade->managed-fx-records cascade)
                        [])})))

  ;; Cross-link event — the HANDLER DISPATCHED row dispatches this to
  ;; pivot the spine to a different cascade. Thin wrapper over the
  ;; existing `:rf.xray/focus-cascade` event; named to read as the
  ;; panel-side concept ('focus this event'), since the panel surfaces
  ;; an event vector and the user thinks 'jump to that event' rather
  ;; than 'jump to that cascade'. Internally same write.
  (rf/reg-event :rf.xray/focus-event
    (fn [{:keys [db]} [_ dispatch-id frame-id]]
      {:db (spine/focus-cascade-reducer db dispatch-id frame-id)})))
