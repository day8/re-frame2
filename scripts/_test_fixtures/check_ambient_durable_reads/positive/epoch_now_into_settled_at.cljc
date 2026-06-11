(ns fixtures.epoch-now-into-settled-at
  "POSITIVE fixture: a mutation handler reads `interop/epoch-now-ms` while
  writing the durable `:settled-at` field. Even the wall-clock epoch reader is
  a violation AT the durable write site — the value must arrive via the reply
  token's `:rf.world/inputs`. Must FLAG (1 finding)."
  (:require [re-frame.interop :as interop]))

(defn settle-mutation
  [instance result]
  (assoc instance
         :result     result
         :settled-at (interop/epoch-now-ms)))   ;; FLAGGED
