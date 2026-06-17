(ns fixture.negative.inline-human-token-slim
  "NEGATIVE fixture: the bundle-isolated reagent-slim shape — it CANNOT
  :require re-frame.error, so it hand-rolls a human sentence + the trailing
  [:rf.error/<id>] token inline. MUST stay green (it LEADS with a string
  literal of human text, not a bare keyword).")

(defn boom [x]
  (throw (ex-info "reagent2 create-class requires a :render fn (or :reagent-render); add one to the spec map. [:rf.error/create-class-missing-render]"
                  {:rf.error/id :rf.error/create-class-missing-render
                   :reason      "create-class requires a :render fn"
                   :bad         x})))
