(ns re-frame.ui.proof-pack.all-views
  "G-18 positive control: a consumer that imports EVERY view of the proof-pack
  library. Every sentinel MUST be present in this advanced build — that proves
  the sentinels are real and DCE-able, so the single-view build's absences are
  genuine elision, not a renamed/missing string (non-vacuity)."
  (:require [re-frame.ui.proof-pack.library :as lib]))

(defn -main []
  (unchecked-set js/globalThis "__RF2_PP_ALL__"
                 #js [lib/controlled-input
                      lib/selection-controller
                      lib/list-cell
                      lib/safe-form-control
                      lib/schema-described
                      lib/inline-popover]))
