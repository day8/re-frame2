# Masked comment in a fence (negative fixture)

A `;` Clojure comment INSIDE a code fence describing the retirement is
removed-context prose that happens to sit in a code block. The gate masks `;`
comments inside fences, so this must stay GREEN.

```clojure
(rf/reg-cofx ::bad-counter
  {:schema pos-int?}
  ;; A miss here is :where :cofx? No — it throws :rf.error/cofx-value-invalid.
  (fn [] -1))
```
