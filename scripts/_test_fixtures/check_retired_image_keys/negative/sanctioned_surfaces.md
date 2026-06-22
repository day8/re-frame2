# Sanctioned, untouched surfaces (MUST STAY GREEN)

The EP-0026 model — three keys, image order:

```clojure
(rf/image {:id :app/main
           :select-ns {:include ["app.todo.**"] :exclude ["app.todo.dev.**"]}
           :registrations {:reg-event [[:counter/inc handler]]}})

(rf/make-frame {:id :app/main :images [a-image b-image]})
```

`clojure.string/replace` and friends are NOT the retired image key:

```clojure
(str/replace s #"\." "/")
(clojure.string/replace x #"a" "b")
(string/replace-first y "a" "b")
```

The `:rf.capability/*` host-service vocabulary is UNRELATED and untouched — a
capability map is ordinary data here, and the bare word "capabilities" must
never fire:

```clojure
(def host-services
  {:rf.capability/http   http-client
   :rf.capability/clock  system-clock
   :rf.capability/random secure-random})
```
