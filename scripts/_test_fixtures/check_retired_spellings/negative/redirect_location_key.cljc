(ns fixture.negative.redirect-location-key
  "NEGATIVE fixture: the CANONICAL :location redirect-target key on a
  :rf.server/redirect / :rf.server/safe-redirect fx (rf2-vngir). This is
  the correct spelling — it must NEVER fire the gate.")

(defn save-handler [_ _]
  {:fx [[:rf.server/redirect {:location "/dashboard" :status 302}]]})

(defn login-handler [_ _]
  {:fx [[:rf.server/safe-redirect {:location "/home" :relative-only? true}]]})
