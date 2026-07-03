# Rewritten teaching (negative fixture)

The corrected teaching uses the current `:rf.error/cofx-value-invalid` shape.
No `:where :cofx` value appears in the fence, so the gate must stay GREEN.

```clojure
{:operation :rf.error/cofx-value-invalid
 :op-type   :error
 :tags      {:rf.cofx/id ::bad-counter
             :recovery   :no-recovery}}
```
