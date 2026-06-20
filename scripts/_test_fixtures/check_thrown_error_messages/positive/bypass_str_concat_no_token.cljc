(ns fixture.positive.bypass-str-concat-no-token
  "POSITIVE fixture (rf2-krrv87 gate widen): a raw ex-info whose ex-data
  carries :rf.error/id but whose message is a (str …) WITHOUT the
  [:rf.<ns>/…] token and is NOT a human-message call — a builder bypass
  that skips the canonical derivation + the greppability token (rule 4).")

(defn boom [x]
  (throw (ex-info (str "could not frobnicate " x)
                  {:rf.error/id :rf.error/frobnicate-failed
                   :where    'rf/frobnicate
                   :recovery :no-recovery})))
