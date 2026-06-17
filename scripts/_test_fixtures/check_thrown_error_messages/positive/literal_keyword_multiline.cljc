(ns fixture.positive.literal-keyword-multiline
  "POSITIVE fixture: the bare-keyword message on the line AFTER (ex-info —
  the common wrapped shape a line-local scan would miss.")

(defn boom [x]
  (throw
    (ex-info
      ":rf.error/resource-non-edn-params"
      {:rf.error/id :rf.error/resource-non-edn-params
       :reason "a human sentence"
       :bad x})))
