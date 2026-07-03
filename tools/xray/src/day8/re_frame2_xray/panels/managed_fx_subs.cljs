(ns day8.re-frame2-xray.panels.managed-fx-subs
  "Composite subs for the managed-fx wire-boundary diff template
  (rf2-uyp86, parent rf2-5aw5v).

  The panel template (`panels/managed_fx_template`) is the renderer;
  this ns produces the data the renderer consumes. One read produces
  every slot the view needs:

      {:records [<record> ...]}

  where each record satisfies the eight-property contract from
  [`panels/managed-fx-helpers/event-bundle->managed-fx-records`](managed_fx_helpers.cljc).

  The composite is keyed to the spine's `:rf.xray/focus` — it
  recomputes whenever the user clicks a different event-bundle in the L2
  event list, scrubs through history, or a new event-bundle lands at head
  in LIVE mode.

  ## Cross-link

  Records carry `:origin-event-id` so the panel's HANDLER DISPATCHED
  row can wire `:on-click` to the spine's canonical `:rf.xray/focus-event`
  — clicking pivots the spine to the child event the response handler
  kicks off. The panel reuses the spine event directly, so views stay
  thin and there is one focus write path."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.panels.managed-fx-helpers :as h]
            [day8.re-frame2-xray.spine :as spine]))

;; ---- public install -----------------------------------------------------

(defn install!
  "Idempotent install — register `:rf.xray/managed-fx-for-focused-event`.
  The panel's HANDLER DISPATCHED row cross-links via the spine's
  canonical `:rf.xray/focus-event`, so no panel-local focus event is
  registered here. Called from `registry.cljs`'s
  `register-xray-handlers!` fan-out."
  []

  ;; Composite sub — produces the records vector for the focused
  ;; event-bundle. Re-derives on every spine flip / event-bundle-list change /
  ;; focus move.
  (rf/reg-sub :rf.xray/managed-fx-for-focused-event
    :<- [:rf.xray/event-bundles]
    :<- [:rf.xray/focus]
    (fn [[event-bundles focus] _query]
      ;; rf2-bz7flo — resolve the focused event-bundle frame-strictly. Dispatch
      ;; ids are unique only within a frame, so keying by dispatch-id alone
      ;; could surface managed-fx rows from a foreign frame's same-id event-bundle
      ;; while the returned `:frame` is the focused frame. `event-bundle-by-focus`
      ;; keys by both `:frame` + `:dispatch-id` when focus carries a frame.
      (let [dispatch-id (:dispatch-id focus)
            event-bundle     (spine/event-bundle-by-focus event-bundles focus)]
        {:dispatch-id dispatch-id
         :frame       (:frame focus)
         :records     (if event-bundle
                        (h/event-bundle->managed-fx-records event-bundle)
                        [])}))))

;; The HANDLER DISPATCHED row's `:on-click` dispatches the spine's
;; canonical `:rf.xray/focus-event` directly (managed_fx_template.cljs)
;; — 'focus this event' reads as the panel-side concept and IS the
;; spine focus write. No panel-local duplicate is registered: one id,
;; one write path (rf2-fsqlgz collapsed the former thin wrapper onto
;; the spine event when the pipeline-vocab rename made both ids
;; `:rf.xray/focus-event`).
