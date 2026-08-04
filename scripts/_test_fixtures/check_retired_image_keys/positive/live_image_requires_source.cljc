(ns fixture.live-image-requires-source)

;; ONE token per fixture: `:rf.image/requires` as live Clojure source code.
(def my-image
  (rf/image {:id :app/main
             :select-ns {:include ["app.core.**"]}
             :rf.image/requires #{:rf.capability/http}}))
