# Pinning recordable coeffects on dispatch (positive fixture)

A live `:rf.world/inputs` dispatch opt inside a fenced code block on a
non-migration page. The opt was renamed to `:rf.cofx`; the gate MUST fire.

```clojure
(rf/dispatch [:order/place items]
             {:rf.world/inputs {:rf/time-ms 1781078400123}})
```
