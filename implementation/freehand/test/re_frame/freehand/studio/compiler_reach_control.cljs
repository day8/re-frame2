;; ---------------------------------------------------------------------------
;; SCAFFOLDING for rf2-lnecd — deleted before the PR.
;;
;; The THIRD bundle: the positive control that gives the "the compiler does
;; not ship" claim teeth.
;;
;; `freehand-release-compiled` contains no string from
;; `re-frame.freehand.compiler.analyze`, and on its own that proves nothing —
;; a grep that has never been observed to fire cannot tell `absent` from
;; `misspelled`, `renamed`, `inlined`, or `never in this bundle to begin
;; with`. This entry is a strict superset of the same app that additionally
;; REACHES the analyzer at runtime, so the same sentinel must be PRESENT here.
;;
;; Absent there + present here = the compiler is macro-time only.
;; Absent in both = the sentinel is broken and the claim is unproven.
;; ---------------------------------------------------------------------------

(ns re-frame.freehand.studio.compiler-reach-control
  (:require [re-frame.freehand.compiler.analyze :as analyze]
            [re-frame.freehand.release-app-lowered-full :as app]))

(defn ^:export -main []
  (app/-main)
  ;; A live reference the optimiser cannot fold away: the analyzer's own entry
  ;; point reaches the document, so its namespace is genuinely retained and
  ;; its refusal strings are genuinely in the artefact.
  (set! (.-title js/document)
        (str "analyze reachable: " (some? analyze/analyze) (some? analyze/analyze-view-body))))
