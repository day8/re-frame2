(ns re-frame.resources.params
  "The one params validate+canonicalize pipeline both registrars share. Each
  family passes only its error factory, so `registry` and `mutation-registry`
  keep their own thrown-error shapes. It requires `state`, `classification`
  and core namespaces only (no static `schemas` dependency: the seam is
  late-bound), which keeps the require graph acyclic.
  Spec 016 §Resource identity."
  (:require [re-frame.late-bind :as rf.late-bind]
            [re-frame.privacy :as rf.privacy]
            [re-frame.resources.classification :as rf.resources.classification]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.trace :as rf.trace]))

#?(:clj (set! *warn-on-reflection* true))

(defn- invalid-params-slots
  "The ex-data slots an invalid-params error carries for the seam's `failure`
  on `params`. An ordinary failure carries the params, redacted slot by slot
  by the schema's marks, the seam's explanation as `:error` and, in
  development, its `:explain-humanized`, both already redacted by the seam. A
  validator that threw decided nothing about the params, so nothing proved
  them non-sensitive: they are withheld as `:rf/redacted`, flagged
  `:sensitive? true`, under `:reason :malformed-schema`.
  Spec 010 §Per-step recovery."
  [params spec {:keys [category tags]}]
  (if (= :rf.error/malformed-schema category)
    {:params     rf.privacy/redacted-sentinel
     :sensitive? true
     :reason     :malformed-schema}
    (merge {:params (rf.resources.classification/redact-invalid-params params spec)
            :error  (:explain tags)}
           (select-keys tags [:explain-humanized]))))

(defn validate+canonicalize
  "Returns the canonical `params` after, in order: defaulting an ABSENT slot
  to `{}` (an explicit nil passes through, so the schema decides whether nil
  conforms); rejecting non-EDN host values (`:rf.error/resource-non-edn-params`);
  checking against the `:params-schema` through the `:schemas/check!` seam (a
  no-op with no schema or no schemas artefact). On a failure it throws
  `(build-invalid-error slots)`, the family's error over
  `invalid-params-slots`; a malformed schema first emits the seam's
  `:rf.error/malformed-schema` trace, as every validating surface does.
  `where` names the calling public surface.
  Spec 016 §Resource identity; Spec 010 §The validation seam."
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
          (when (= :rf.error/malformed-schema (:category failure))
            (rf.trace/emit-error! :rf.error/malformed-schema (:tags failure)))
          ;; Redact before the error is built: a `:sensitive?` slot must
          ;; never ride raw on the thrown payload or downstream logs.
          (throw (build-invalid-error (invalid-params-slots params spec failure))))))
    (rf.resources.state/canonicalize params)))
