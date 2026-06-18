# Realm constructor (positive fixture)

A live `(rf/realm ...)` facade-constructor call inside a fenced block on a
teaching page. `realm` is a retired composition noun (EP-0023 §Naming); the
`rf/`-facade call MUST fire.

```clojure
(def shop (rf/realm {:id :shop}))
```
