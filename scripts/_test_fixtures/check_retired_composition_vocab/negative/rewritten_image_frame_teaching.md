# Rewritten image -> frame teaching (negative fixture)

The CORRECT EP-0023 public composition model. None of these symbols are
retired, so this fenced example must stay GREEN.

```clojure
(def app-image (rf/image {:include-ns ["my.app.**"]}))
(def frame (rf/make-frame {:id :app/main :images [app-image] :initial-events []}))
(rf/make-frame {:id :app/main :images [app-image]})
(rf/destroy-frame! frame)
```
