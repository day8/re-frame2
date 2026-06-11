(ns fixtures.rand-nth-into-temp-id
  "POSITIVE fixture: a durable `:temp-id` chosen via `(rand-nth ...)` at the
  write site. Must FLAG (1 finding).")

(def ^:private id-pool ["a" "b" "c"])

(defn assign-temp-id
  [row]
  (assoc row :temp-id (rand-nth id-pool)))   ;; FLAGGED
