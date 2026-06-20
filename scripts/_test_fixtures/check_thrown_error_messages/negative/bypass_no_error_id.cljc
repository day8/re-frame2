(ns fixture.negative.bypass-no-error-id
  "NEGATIVE fixture (rf2-krrv87 gate widen): a raw ex-info whose ex-data does
  NOT carry :rf.error/id. It is not a framework thrown-error at all (a plain
  application / internal ex-info), so the widened builder-bypass rule MUST
  NOT fire — the rule is scoped to framework errors (the :rf.error/id slot).")

(defn boom [x]
  (throw (ex-info "a plain non-framework ex-info"
                  {:some-app-key 1
                   :detail x})))
