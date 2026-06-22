# Live retired `:include-ns` in a fenced example (MUST FIRE)

Build an image:

```clojure
(rf/image {:id :app/main
           :include-ns ["app.todo.**"]})
```
