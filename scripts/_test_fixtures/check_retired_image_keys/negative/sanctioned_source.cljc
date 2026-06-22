(ns fixture.sanctioned-source)

;; The EP-0026 three-key image surface in real code — green.
(def my-image
  (rf/image {:id :app/main
             :select-ns {:include ["app.core.**"]}}))

;; str/replace is not the retired :replace image key.
(defn slugify [s]
  (clojure.string/replace s #"\s+" "-"))

;; A :rf.capability/* host-service map is ordinary data — never fires.
(def host {:rf.capability/http  ::http
           :rf.capability/clock ::clock})

;; A bare :capabilities key NOT inside a make-frame call is fine (an app's own
;; config field) — only `make-frame :capabilities` is the retired surface.
(def app-config {:capabilities {:feature/x true}})
