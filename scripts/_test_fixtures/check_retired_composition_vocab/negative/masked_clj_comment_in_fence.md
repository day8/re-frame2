# Masked Clojure comment in a fence (negative fixture)

A `;`-to-EOL Clojure comment INSIDE a code fence is removed-context prose that
happens to sit in a code block. It is masked length-preserving before scanning,
so a retired symbol mentioned only in a comment must stay GREEN.

```clojure
;; rf/install! and (rf/realm ...) were retired by EP-0023; rf/app-owns too.
(rf/reg-frame :app/main {:images [app-image]})
```
