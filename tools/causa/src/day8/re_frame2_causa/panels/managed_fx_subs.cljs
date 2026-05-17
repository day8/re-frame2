(ns day8.re-frame2-causa.panels.managed-fx-subs
  "Composite subs for the managed-fx wire-boundary diff template
  (rf2-uyp86, parent rf2-5aw5v).

  The panel template (`panels/managed_fx_template`) is the renderer;
  this ns produces the data the renderer consumes. One read produces
  every slot the view needs:

      {:records [<record> ...]}

  where each record satisfies the eight-property contract from
  [`panels/managed-fx-helpers/cascade->managed-fx-records`](managed_fx_helpers.cljc).

  The composite is keyed to the spine's `:rf.causa/focus` — it
  recomputes whenever the user clicks a different cascade in the L2
  event list, scrubs through history, or a new cascade lands at head
  in LIVE mode.

  ## Cross-link

  Records carry `:origin-event-id` so the panel's HANDLER DISPATCHED
  row can wire `:on-click` to `:rf.causa/focus-event` — clicking pivots
  the spine to the child cascade the response handler kicks off. The
  cross-link event lives here so panel views stay thin."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.managed-fx-helpers :as h]
            [day8.re-frame2-causa.spine :as spine]))

;; ---- public install -----------------------------------------------------

(defn install!
  "Idempotent install — register `:rf.causa/managed-fx-for-focused-event`
  + the `:rf.causa/focus-event` cross-link event. Called from
  `registry.cljs`'s `register-causa-handlers!` fan-out."
  []

  ;; Composite sub — produces the records vector for the focused
  ;; cascade. Re-derives on every spine flip / cascade-list change /
  ;; focus move.
  (rf/reg-sub :rf.causa/managed-fx-for-focused-event
    :<- [:rf.causa/cascades]
    :<- [:rf.causa/focus]
    (fn [[cascades focus] _query]
      (let [dispatch-id (:dispatch-id focus)
            cascade     (when dispatch-id
                          (spine/cascade-by-id cascades dispatch-id))]
        {:dispatch-id dispatch-id
         :frame       (:frame focus)
         :records     (if cascade
                        (h/cascade->managed-fx-records cascade)
                        [])})))

  ;; Cross-link event — the HANDLER DISPATCHED row dispatches this to
  ;; pivot the spine to a different cascade. Thin wrapper over the
  ;; existing `:rf.causa/focus-cascade` event; named to read as the
  ;; panel-side concept ('focus this event'), since the panel surfaces
  ;; an event vector and the user thinks 'jump to that event' rather
  ;; than 'jump to that cascade'. Internally same write.
  (rf/reg-event-db :rf.causa/focus-event
    (fn [db [_ dispatch-id frame-id]]
      (spine/focus-cascade-reducer db dispatch-id frame-id))))
