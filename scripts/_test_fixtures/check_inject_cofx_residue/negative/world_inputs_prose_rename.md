# `:rf.cofx` envelope field (negative fixture)

Prose describing the rename. The retired key is named only outside any fence,
so the gate must stay GREEN.

The recordable-coeffect map carried on every dispatch envelope (EP-0017;
renamed and flattened from the EP-0010 `:rf.world/inputs`). `:rf.world/inputs`
was a draft-only name, so supplying it in dispatch opts rides the generic
`:rf.warning/unknown-dispatch-opt` warning with a did-you-mean naming `:rf.cofx`.

```clojure
;; The canonical replacement opt — value-returning, flat.
(rf/dispatch [:order/place items] {:rf.cofx {:rf/time-ms 1781078400123}})
```
