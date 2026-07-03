(ns fixture.source-docstring-mention
  "Negative fixture — a docstring naming the retired surface. Button C used
  to teach :where :cofx (skip-handler); that surface was retired in EP-0017
  and a recordable cofx miss now throws :rf.error/cofx-value-invalid. The
  string-literal mask blanks this, so the gate must stay GREEN.")

(defn note []
  "The retired :where :cofx surface is only named here in a docstring.")
