(ns fixture.negative.str-concat-human-text
  "NEGATIVE fixture: a (str …) message that CONCATENATES the keyword token
  with human text — the hand-rolled inline conformant shape (reagent-slim).
  This LEADS with a human sentence, so it is NOT a bare keyword. MUST stay
  green: the (str …) form opens with a string literal, not a bare keyword.")

(defn boom [x]
  (throw (ex-info (str "as-element fn is unregistered; install an adapter "
                       "before rendering. [" :rf.error/as-element-fn-unregistered "]")
                  {:rf.error/id :rf.error/as-element-fn-unregistered
                   :bad x})))
