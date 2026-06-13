# SSR loader wiring (positive fixture)

Replicates the rf2-d8mvke.3 stale current-surface example: a live,
copy-pasteable `inject-cofx` interceptor entry inside a fenced code block on a
non-migration page. The gate MUST fire on the fenced line.

The per-request frame reads the request URL via the request cofx and spawns the
loader:

```clojure
(rf/reg-event-fx :rf/server-init
  [(rf/inject-cofx :rf.server/request)]
  (fn handler-server-init [{:keys [rf.server/request]} _]
    {:fx [[:rf.machine/spawn {:machine-id :pdp/load}]]}))
```

That is the retired interceptor shape and should be flagged.
