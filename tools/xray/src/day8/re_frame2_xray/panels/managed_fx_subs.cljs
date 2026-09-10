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

  ## Section disclosure

  This ns also owns the record panel's per-section open/closed state —
  the `:rf.xray/managed-fx-expanded-sections` read and the
  `:rf.xray/managed-fx-toggle-section` write. `theme/section/section-row`
  draws the disclosure glyph but deliberately wires no click, so the
  state is the caller's; it is held per `[record-key section-id]` pair so
  one record's REQUEST opens independently of its siblings'.

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
    {:inputs [[:rf.xray/event-bundles] [:rf.xray/focus]]}
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
                        [])})))

  ;; ---- section disclosure ----------------------------------------------
  ;;
  ;; `theme/section/section-row` renders the disclosure glyph and nothing
  ;; else — "Click-to-toggle wiring is the caller's responsibility" — so the
  ;; open/closed state is the panel's, held here. The vocabulary
  ;; (`expansion-slot`, `expansion-key`, `resolve-expanded?`,
  ;; `section-defaults`) is pure data in `managed_fx_helpers`; this ns owns
  ;; only the registration.
  ;;
  ;; The slot is read at the panel's reactive boundary (`panels/ManagedFxList`)
  ;; and threaded down as plain data — `record-panel` and `records-list` are
  ;; plain fns, and per Spec 006 §Plain-fn footgun an ambient `subscribe`
  ;; inside one raises `:rf.error/no-frame-context` rather than resolving the
  ;; surrounding frame. Same shape the edn-inspector widget uses: its
  ;; `reg-view` reads `@(subscribe [expansion-slot])` once and hands the map
  ;; to the pure renderers.

  (rf/reg-sub :rf.xray/managed-fx-expanded-sections
    (fn [db _query]
      (get db h/expansion-slot)))

  ;; Toggle ONE section of ONE record.
  ;;
  ;; The next value is computed through `resolve-expanded?` rather than by
  ;; flipping the stored entry, so the FIRST click inverts what the operator
  ;; can actually SEE. Flipping a nil override from an assumed `false` would
  ;; be a silent no-op on the two sections that default OPEN.
  (rf/reg-event :rf.xray/managed-fx-toggle-section
    (fn [{:keys [db]} [_ rec-key section-id]]
      {:db (assoc-in db [h/expansion-slot (h/expansion-key rec-key section-id)]
                     (not (h/resolve-expanded? (get db h/expansion-slot)
                                               rec-key section-id)))}))

  nil)

;; The HANDLER DISPATCHED row's `:on-click` dispatches the spine's
;; canonical `:rf.xray/focus-event` directly (managed_fx_template.cljs)
;; — 'focus this event' reads as the panel-side concept and IS the
;; spine focus write. No panel-local duplicate is registered: one id,
;; one write path (rf2-fsqlgz collapsed the former thin wrapper onto
;; the spine event when the pipeline-vocab rename made both ids
;; `:rf.xray/focus-event`).
