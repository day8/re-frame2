(ns fixtures.every-durable-id-key
  "POSITIVE fixture: every key in `_DURABLE_ID_KEYS`, one per line, each minted
  from an ambient generator. Eight findings, and the self-test asserts the eight
  KEYS by name.

  EP-0010 §Randomness is the rule these witness: a durable id must arrive as a
  supplied recordable coeffect, never from `(random-uuid)` at the write site.
  The read form is held constant so the only thing that varies is the key.")

(defn mint-every-durable-id
  [row]
  (assoc row
         :id             (random-uuid)
         :entry-id       (random-uuid)
         :request-id     (random-uuid)
         :instance-id    (random-uuid)
         :mutation-id    (random-uuid)
         :temp-id        (random-uuid)
         :correlation-id (random-uuid)
         :resource-id    (random-uuid)))
