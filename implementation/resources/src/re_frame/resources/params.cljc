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
  checking against the `:params-schema` through the `:schemas/check!` seam (a
  no-op with no schema or no schemas artefact). On a failure it throws
  `(build-invalid-error redacted-params redacted-error)`: the params redacted
  slot by slot by the schema's marks, and the seam's explanation, which the
  seam has already redacted against the schema. `where` names the calling
  public surface. Spec 016 §Resource identity; Spec 010 §The validation seam."
  [id spec params where build-invalid-error]
  (let [params (rf.resources.state/default-omitted-params params)
        schema (:params-schema spec)]
    (rf.resources.state/reject-non-edn! params where :params id)
    (when schema
      (when-let [check! (rf.late-bind/get-fn :schemas/check!)]
        (when-let [failure (check! {:id         id
                                    :schema     schema
                                    :value      params
                                    :tags-only? true})]
          ;; Redact before the error is built: a `:sensitive?` slot must
          ;; never ride raw on the thrown payload or downstream logs.
          (throw (build-invalid-error
                   (rf.resources.classification/redact-invalid-params params spec)
                   (:explain (:tags failure)))))))
    (rf.resources.state/canonicalize params)))
