(ns fixture.negative.keyword-as-reason-value
  "NEGATIVE fixture: a :rf.error/… keyword appearing as an ex-data VALUE (here
  the :rf.error/id slot) while the MESSAGE is a human sentence. Only the
  ex-info FIRST argument is the message; ex-data keywords are sanctioned. MUST
  stay green.")

(defn boom [x]
  (throw (ex-info "a perfectly human sentence [:rf.error/foo]"
                  {:rf.error/id :rf.error/foo
                   :cause       :rf.error/inner
                   :bad         x})))
