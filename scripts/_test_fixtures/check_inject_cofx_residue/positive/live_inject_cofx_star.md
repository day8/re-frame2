# Higher-order coeffect injection (positive fixture)

A live `inject-cofx*` fn-form call inside a fenced code block on a non-migration
page. The gate MUST fire on the fenced line (the `*` form is also retired).

```clojure
(def my-chain
  [(rf/inject-cofx* :now)])
```
