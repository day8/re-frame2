(ns fixtures.random-uuid-into-id
  "POSITIVE fixture: a mutation handler mints a durable `:id` from
  `(random-uuid)` at the write site. EP-0010 §Randomness governs this — a
  durable id must come from a supplied recordable coeffect, not an
  ambient generator. Must FLAG (1 finding).")

(defn new-instance
  [data]
  {:id   (random-uuid)                    ;; FLAGGED: ambient RNG into durable id
   :data data})
