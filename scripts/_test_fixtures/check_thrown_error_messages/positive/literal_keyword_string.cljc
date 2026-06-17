(ns fixture.positive.literal-keyword-string
  "POSITIVE fixture: a bare :rf.error/… keyword STRING LITERAL as the
  ex-info message — the retired keyword-only shape (rf2-vvixub).")

(defn boom [x]
  (throw (ex-info ":rf.error/no-frame-context"
                  {:rf.error/id :rf.error/no-frame-context
                   :reason "a human sentence"
                   :bad x})))
