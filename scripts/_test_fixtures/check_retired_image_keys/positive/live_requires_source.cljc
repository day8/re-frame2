(ns fixture.live-requires-source)

;; A live retired key in real Clojure source code (NOT a comment) MUST fire.
(def my-image
  (rf/image {:id :app/main
             :select-ns {:include ["app.core.**"]}
             :rf.image/requires #{:rf.capability/http}}))

;; A live :rf.gen/requires read in code also fires.
(defn read-requires [gen]
  (:rf.gen/requires gen))
