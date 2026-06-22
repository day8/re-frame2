# Live retired `:replace` + `make-frame :capabilities` (MUST FIRE)

```clojure
(rf/image {:id :app/main
           :select-ns {:include ["app.checkout.**"]}
           :replace {[:fx :http] {:ns "app.story.http"}}})

(rf/make-frame {:id :app/main
                :images [app-image]
                :capabilities {:rf.capability/http http-client}})
```
