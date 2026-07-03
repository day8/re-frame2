# schema-violation teaching (positive fixture)

Replicates the rf2-2oqj59 stale Button-C teaching: a live `:where :cofx`
value inside a fenced code block, teaching the RETIRED schema-validation
surface. The gate MUST fire on the fenced line.

```clojure
{:operation :rf.error/schema-validation-failure
 :op-type   :error
 :tags      {:where :cofx
             :recovery :no-recovery}}
```

That is the retired schema-validation surface and should be flagged.
