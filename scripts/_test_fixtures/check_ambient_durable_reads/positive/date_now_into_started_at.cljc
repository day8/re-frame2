(ns fixtures.date-now-into-started-at
  "POSITIVE fixture: a work-ledger writer reads `(.now js/Date)` while writing
  the durable `:started-at` field. Must FLAG (1 finding).")

(defn open-work-row
  [row]
  (assoc row :started-at (.now js/Date)))   ;; FLAGGED
