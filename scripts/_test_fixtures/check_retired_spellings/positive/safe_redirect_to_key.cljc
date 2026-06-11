(ns fixture.positive.safe-redirect-to-key
  "POSITIVE fixture: the retired :to redirect-target key on a
  :rf.server/safe-redirect fx (rf2-vngir). Canonical key is :location.")

(defn login-handler [_ _]
  ;; RETIRED: :to is not a redirect-target key; rewrite as :location.
  {:fx [[:rf.server/safe-redirect {:to "/home" :relative-only? true}]]})
