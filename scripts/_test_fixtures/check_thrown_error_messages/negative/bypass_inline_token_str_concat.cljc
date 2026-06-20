(ns fixture.negative.bypass-inline-token-str-concat
  "NEGATIVE fixture (rf2-krrv87 gate widen): the sanctioned reagent-slim
  shape that hand-rolls the human sentence + the [:rf.<ns>/…] token inline
  via a (str …) concatenation. The message ALREADY embeds the greppability
  token, so it is conformant to rule 4 and MUST stay green even though it is
  a raw ex-info with :rf.error/id in ex-data.")

(defn boom [reason x]
  (throw (ex-info (str reason " [:rf.error/as-element-fn-unregistered]")
                  {:rf.error/id :rf.error/as-element-fn-unregistered
                   :reason      reason
                   :bad         x})))
