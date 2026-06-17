(ns fixture.positive.str-error-kw-var
  "POSITIVE fixture: (str error-kw) as the ex-info message — the machines /
  events helper-clone shape that centralised the regression into a builder
  parameter named `error-kw`.")

(defn validation-error [error-kw reason extras]
  (ex-info (str error-kw)
           (merge {:rf.error/id error-kw
                   :reason      reason}
                  extras)))
