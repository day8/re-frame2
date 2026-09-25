(ns fixture.source-comment-mention
  "Negative fixture — a `;` comment in testbed source naming the retired
  :where :cofx surface. Masked, so the gate must stay GREEN.")

;; There is no :where :cofx schema-validation surface (skip-handler, queue-
;; continues). A recordable cofx miss throws
;; :rf.error/cofx-value-invalid and halts the run.
(rf/reg-event ::violate-cofx
  {:rf.cofx/requires [::bad-counter]}
  (fn [{:keys [db]} _] {:db db}))
