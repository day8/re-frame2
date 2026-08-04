# One token per fixture: a live `:replace` image key in a fence (MUST FIRE)

Declare a winner:

```clojure
(rf/image {:id :app/main
           :select-ns {:include ["app.checkout.**"]}
           :replace {[:fx :http] {:ns "app.story.http"}}})
```
