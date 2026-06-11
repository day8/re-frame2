(ns fixtures.threaded-completed-at
  "NEGATIVE fixture: the CORRECT pattern. The durable `:completed-at` /
  `:loaded-at` values are THREADED from the reply token's `:rf.world/inputs`
  `:time-ms` (a supplied causal time), never read ambiently here. The function
  takes `completed-at` as a parameter and binds `time-ms` from the token. Must
  stay GREEN (0 findings)."
  (:require [re-frame.interop :as interop]))   ;; required but not read at a durable site

(defn build-reply
  [token outcome]
  (let [time-ms (get-in token [:rf.world/inputs :time-ms])]
    {:outcome      outcome
     :completed-at time-ms
     :loaded-at    time-ms}))

;; A bare `interop/now-ms` appears in code but NOT adjacent to a durable key —
;; e.g. an unrelated perf t0 binding the gate's shape does not match.
(defn elapsed-since
  [start]
  (- (interop/now-ms) start))
