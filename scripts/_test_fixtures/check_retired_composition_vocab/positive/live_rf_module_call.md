# Module constructor (positive fixture)

A live `(rf/module ...)` facade-constructor call inside a fenced block on a
teaching page. `module` is a retired composition noun (EP-0023 §Naming); use
image fragments via `rf/image` instead. The `rf/`-facade call MUST fire.

```clojure
(def cart (rf/module {:include-ns ["shop.cart.**"]}))
```
