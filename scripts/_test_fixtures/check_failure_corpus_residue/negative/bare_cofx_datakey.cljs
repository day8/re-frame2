(ns fixture.bare-cofx-datakey
  "Negative fixture — a bare :cofx used as an ORDINARY data key (a click-
  counter slot), NOT as a schema-validation :where value. The gate requires
  the `:where` head, so a bare :cofx must stay GREEN.")

(rf/reg-event ::initialise
  (fn [_ _]
    ;; :cofx here is a counter slot name, not a :where surface.
    {:db {:click-count {:app-db 0 :event 0 :cofx 0 :fx 0}}}))

(rf/reg-sub :cofx-count (fn [db _] (get-in db [:click-count :cofx])))
