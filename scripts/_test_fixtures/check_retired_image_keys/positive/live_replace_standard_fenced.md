# One token per fixture: a live `:replace-standard` key in a fence (MUST FIRE)

Shadow a framework standard:

```clojure
(rf/image {:id :app/main
           :replace-standard {[:fx :http] {:ns "app.http"}}})
```
