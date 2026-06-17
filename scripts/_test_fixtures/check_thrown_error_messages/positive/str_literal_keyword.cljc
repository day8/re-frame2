(ns fixture.positive.str-literal-keyword
  "POSITIVE fixture: (str :rf.error/…) as the ex-info message — the per-surface
  helper-clone shape (marks.cljc / identity.cljc / path.cljc / etc.).")

(defn boom [x]
  (throw (ex-info (str :rf.error/bad-marks)
                  {:rf.error/id :rf.error/bad-marks
                   :reason "a human sentence"
                   :bad x})))
