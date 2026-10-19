(ns fixture.positive.safe-redirect-fx
  "POSITIVE fixture: the retired SSR fx id and one of its error ids, as
  keyword tokens. `:rf.server/redirect` is relative-only by default, with
  `:allow` naming any other host.")

(defn bounce [next-url]
  ;; RETIRED: one redirect fx, safe by default.
  {:fx [[:rf.server/safe-redirect {:location next-url}]]})

(def refusals #{:rf.error/safe-redirect-host-disallowed})
