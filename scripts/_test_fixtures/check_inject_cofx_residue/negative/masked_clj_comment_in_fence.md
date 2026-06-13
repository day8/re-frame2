# Masked Clojure comment inside a fence (negative fixture)

A removed-context `;` comment that happens to sit inside a code fence is masked
length-preserving, so it must NOT fire. This replicates spec/Spec-Schemas.md's
schema-comment and the migration grep recipe shape.

```clojure
(def CofxErrorCategory
  [:enum
   :rf.error/unregistered-cofx       ;; the typo case
   ;; EP-0017: the v1 :rf.error/no-such-cofx is retired with inject-cofx, and
   ;; the :rf.world/inputs opt is renamed to :rf.cofx.
   :rf.error/missing-required-cofx])
```
