(ns fixture.negative.bypass-marker-exempt
  "NEGATIVE fixture (rf2-krrv87 gate widen): a DELIBERATE, documented
  builder-bypass that opts out via the `rf2:builder-bypass-ok` marker comment
  (the conformance-DSL :throw / :count ops pin their message downstream as
  :exception-message, so it stays bare prose without the token by design).
  MUST stay green BECAUSE of the marker — without it, this would fire."
  (:require [clojure.string]))

(defn throw-step [step]
  ;; rf2:builder-bypass-ok — the message IS the fixture-supplied text,
  ;; re-emitted downstream as :exception-message; it stays the prose by design.
  (throw (ex-info (str (second step))
                  {:rf.error/id   :rf.error/conformance-throw-step
                   :where         'rf/conformance-eval
                   :from-fixture? true})))
