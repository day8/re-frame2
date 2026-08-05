# App constructor (positive fixture)

A live `(rf/app ...)` facade-constructor call inside a fenced block on a
teaching page. `app` is a retired composition noun (EP-0023 §Naming); an image
is the public unit of composition. The `rf/`-facade call MUST fire.

```clojure
(def shop (rf/app {:frames [:cart :checkout]}))
```
