(ns fixture.negative.keyword-in-exdata
  "NEGATIVE fixture: a (str :rf.error/…) appearing NOT in ex-info message
  position — here building an ex-data diagnostic string. The patterns anchor
  on `(ex-info (str :rf.error/…`, so a bare `(str :rf.error/…)` elsewhere is
  sanctioned. MUST stay green.")

(defn diagnostic [x]
  (let [label (str :rf.error/some-category)]
    {:rf.error/id :rf.error/some-category
     :label       label
     :bad         x}))
