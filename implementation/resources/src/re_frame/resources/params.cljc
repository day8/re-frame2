(ns re-frame.resources.params
  "The one params validate+canonicalize pipeline both registrars share. Each
  family passes only its error factory, so `registry` and `mutation-registry`
  keep their own thrown-error shapes. It requires only `state`,
  `classification` and `late-bind` (no static `schemas` dependency), which
  keeps the require graph acyclic. Spec 016 §Resource identity."
  (:require [re-frame.late-bind :as rf.late-bind]
            [re-frame.resources.classification :as rf.resources.classification]
            [re-frame.resources.state :as rf.resources.state]))

#?(:clj (set! *warn-on-reflection* true))

(defn validate+canonicalize
  "Returns the canonical `params` after, in order: defaulting an ABSENT slot
  to `{}` (an explicit nil passes through, so the schema decides whether nil
  conforms); rejecting non-EDN host values (`:rf.error/resource-non-edn-params`);
  validating against the `:params-schema` through the late-bound validator (a
  no-op with no schema or no validator). On a conformance failure it redacts
  the params and explainer output by their classification marks, then throws
  `(build-invalid-error redacted-params redacted-error)`. `where` names the
  calling public surface. Spec 016 §Resource identity."
  [id spec params where build-invalid-error]
  (let [params (rf.resources.state/default-omitted-params params)
        schema (:params-schema spec)]
    (rf.resources.state/reject-non-edn! params where :params id)
    (when schema
      (let [validate (rf.late-bind/get-fn :schemas/validate-with-registered-fn)]
        (when (and validate (not (validate schema params)))
          (let [explain  (rf.late-bind/get-fn :schemas/explain-with-registered-fn)
                ;; Redact before the error is built: a `:sensitive?` slot must
                ;; never ride raw on the thrown payload or downstream logs.
                redacted (rf.resources.classification/redact-invalid-params-error
                           params (when explain (explain schema params)) spec)]
            (throw (build-invalid-error (:params redacted) (:error redacted)))))))
    (rf.resources.state/canonicalize params)))
