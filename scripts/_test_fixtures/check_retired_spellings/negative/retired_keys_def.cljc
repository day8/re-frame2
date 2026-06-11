(ns fixture.negative.retired-keys-def
  "NEGATIVE fixture: the actual runtime GUARD that names the retired keys.
  Mirrors re-frame.ssr.response — the `retired-redirect-target-keys` def is
  a VECTOR of bare keywords [:url :to] (not map-entry keys), used by the
  reject! guard. The mention of :rf.server/redirect is in prose/strings.
  Must stay GREEN: the gate must not flag the very guard that enforces the
  rename. The keywords are not in `:key value` map-entry position, and the
  fx-id references sit inside masked strings/comments.")

;; The redirect fx (:rf.server/redirect / :rf.server/safe-redirect) writes a
;; Location header; the retired :url / :to keys must be rejected.
(def ^:private retired-redirect-target-keys
  "Redirect-target spellings retired in favour of :location (rf2-vngir).
  Detected on a :rf.server/redirect / :rf.server/safe-redirect args map."
  [:url :to])

(defn reject-retired-redirect-keys! [redirect-map]
  (let [retired (filterv #(contains? redirect-map %) retired-redirect-target-keys)]
    (when (seq retired)
      (throw (ex-info "redirect-retired-target-key"
                      {:retired-keys  retired
                       :canonical-key :location})))))
