(ns fixture.positive.str-error-id-var
  "POSITIVE fixture: (str error-id) as the ex-info message — the resources
  registration-error helper-clone shape (registry / mutation-registry /
  scope-registry) that bound the keyword as `error-id`.")

(defn registration-error [error-id where reason extra]
  (ex-info (str error-id)
           (merge {:rf.error/id error-id
                   :where       where
                   :reason      reason}
                  extra)))
