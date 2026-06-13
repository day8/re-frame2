# Rewritten coeffect teaching (negative fixture)

The CORRECT current-surface API — value-returning `reg-cofx` plus the
`:rf.cofx/requires` declaration — inside a code fence. This is exactly what the
rf2-d8mvke.3 stale examples were rewritten to. The gate must stay GREEN.

```clojure
(rf/reg-cofx :rf.server/request
  {:doc "Per-request context bound by the host adapter."}
  (fn [] *current-request*))

(rf/reg-event-fx :rf/server-init
  {:rf.cofx/requires [:rf.server/request]}
  (fn handler-server-init [{:keys [rf.server/request]} _]
    {:fx [[:rf.machine/spawn {:machine-id :pdp/load}]]}))
```
