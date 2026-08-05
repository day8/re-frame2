(ns fixture.positive.str-remaining-err-var-names
  "POSITIVE fixture (rf2-n6ijg): the three `_STR_ERR_VAR_RE` variable names that
  had no fixture — `error-keyword`, `err-kw`, `err-id`. `error-kw` and
  `error-id` are covered by their own files beside this one.

  The var NAME is the load-bearing signal: it is what tells the gate the value
  is a discriminator keyword rather than human text, so each accepted spelling
  needs a case of its own. Delete any one of them from the alternation and
  nothing here can be answered by another. Three findings expected, and each
  site names itself in its ex-data so the self-test can say WHICH one died.")

(defn validation-error-long-name [error-keyword reason]
  (ex-info (str error-keyword) {:rf.error/id :rf.error/str-error-keyword-var
                                :reason      reason}))

(defn validation-error-short-kw [err-kw reason]
  (ex-info (str err-kw) {:rf.error/id :rf.error/str-err-kw-var
                         :reason      reason}))

(defn validation-error-short-id [err-id reason]
  (ex-info (str err-id) {:rf.error/id :rf.error/str-err-id-var
                         :reason      reason}))
