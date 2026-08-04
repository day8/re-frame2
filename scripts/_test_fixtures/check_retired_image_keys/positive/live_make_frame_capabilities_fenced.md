# One token per fixture: a live `make-frame :capabilities` key (MUST FIRE)

Hand the frame its host services:

```clojure
(rf/make-frame {:id :app/main
                :images [app-image]
                :capabilities {:rf.capability/http http-client}})
```
