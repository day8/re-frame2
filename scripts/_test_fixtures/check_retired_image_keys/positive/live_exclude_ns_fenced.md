# One token per fixture: a live `:exclude-ns` image key in a fence (MUST FIRE)

Trim the image:

```clojure
(rf/image {:id :app/main
           :exclude-ns ["app.todo.dev.**"]})
```
