(ns fixture.positive.redirect-url-multiline
  "POSITIVE fixture: the retired :url redirect-target key where the
  :rf.server/redirect tag and the :url key sit on different lines of the
  same inline form (within the sliding window). Proves the window-based
  (b) detector binds the key to the fx across a small wrap.")

(defn save-handler [_ _]
  {:fx [[:rf.server/redirect
         {:url    "/dashboard"
          :status 302}]]})
