(ns fixture.positive.redirect-url-key
  "POSITIVE fixture: the retired :url redirect-target key on a
  :rf.server/redirect fx (rf2-vngir). Canonical key is :location.")

(defn save-handler [_ _]
  ;; RETIRED: :url is a client-nav key, never a redirect-target key.
  {:fx [[:rf.server/redirect {:url "/dashboard" :status 302}]]})
