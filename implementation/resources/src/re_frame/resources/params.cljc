(ns re-frame.resources.params
  "The single owner of the resource/mutation params validate+canonicalize
  pipeline (rf2-7rbb7t). Both `re-frame.resources.registry` and
  `re-frame.resources.mutation-registry` register a REQUIRED `:params-schema`
  that validates + canonicalizes their family's params, and each family had
  an independent, near-identical `validate+canonicalize-params`: default an
  omitted slot, reject a non-EDN / host value at the cache-key boundary,
  resolve the pluggable late-bound Malli validator/explainer, REDACT the
  invalid params before they can ride an error payload, throw the
  family-specific `:rf.error/*-invalid-params`, and canonicalize. The
  nil/missing and invalid-param-redaction correctness/privacy fixes had to
  land TWICE (a correctness + privacy-boundary duplication).

  This leaf owns that pipeline ONCE, parameterized only by the family's error
  descriptor — a `build-invalid-error` callback that shapes the family's
  `:rf.error/resource-invalid-params` / `:rf.error/mutation-invalid-params`
  throw. `registry` and `mutation-registry` keep THIN
  `validate+canonicalize-params` wrappers so their call sites AND thrown error
  shapes are unchanged.

  LEAF, no new edge: it requires only `state` (the omitted-default /
  EDN-rejection / canonicalization the two registrars already delegate to),
  `classification` (the invalid-param redaction seam), and `late-bind` (the
  pluggable Malli validator/explainer — NO static `schemas` dependency).
  `registry` and `mutation-registry` already require all three, so hosting the
  pipeline here introduces no require cycle; `mutation-registry` does NOT gain
  a dependency on `registry`.

  PRIVACY: the `classification/redact-invalid-params-error` step is a privacy
  boundary — a `:sensitive?` params slot must never ride raw on the thrown
  error payload / logs when validation fails on a non-sensitive sibling (and a
  `:large?` slot elides). It is applied here ONCE, identically for both
  families, before the family error is built."
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.resources.classification :as classification]
            [re-frame.resources.state :as state]))

#?(:clj (set! *warn-on-reflection* true))

(defn validate+canonicalize
  "The shared validate+canonicalize params pipeline both the resource and the
  mutation registrar delegate to (rf2-7rbb7t). Applies, in order:

    1. the documented omitted-`:params` default (`state/default-omitted-params`)
       — an ABSENT slot (`state/missing-params`) becomes `{}`; a PRESENT value
       (INCLUDING an explicit `nil`) passes through unchanged so the
       `:params-schema`, not a blanket `(or params {})`, decides whether nil
       conforms (nil vs missing is schema-defined — Spec 016 §Resource
       identity / EP-0012 §canonical-forms);
    2. host / opaque value rejection at the cache-key boundary
       (`state/reject-non-edn!`, throwing `:rf.error/resource-non-edn-params`);
    3. schema conformance against the family `:params-schema` via the pluggable
       late-bound Malli validator (`:schemas/validate-with-registered-fn`) — a
       no-op when no validator is registered; NO static `schemas` dependency;
    4. on a conformance failure, REDACT the failing params + explainer output
       through the shared `classification/redact-invalid-params-error` privacy
       seam (a `:sensitive?` slot never rides raw on the error payload; a
       `:large?` slot elides), then throw the FAMILY error the
       `build-invalid-error` callback shapes from the ALREADY-redacted values;
    5. return the canonical params (`state/canonicalize`).

  `id` is the resource / mutation id (named in the non-EDN boundary error).
  `spec` carries the `:params-schema` + its per-slot classification marks.
  `where` names the call-site public surface (threaded into the non-EDN error).
  `build-invalid-error` is the family's error factory
  `(fn [redacted-params redacted-error] -> ex-info-to-throw)` — the ONLY
  difference between the two families (the
  `:rf.error/resource-invalid-params` vs `:rf.error/mutation-invalid-params`
  error id, the family message, and the `:resource-id` / `:mutation-id`
  slot). It is invoked ONLY on a conformance failure, with the redacted params
  + redacted explainer error, and its return value is thrown — so each family
  keeps its exact thrown-error shape."
  [id spec params where build-invalid-error]
  (let [params (state/default-omitted-params params)
        schema (:params-schema spec)]
    ;; host / opaque values are rejected at the cache-key boundary
    (state/reject-non-edn! params where :params id)
    ;; schema conformance (pluggable; no-op when no validator is registered)
    (when schema
      (let [validate (late-bind/get-fn-cached :schemas/validate-with-registered-fn)]
        (when (and validate (not (validate schema params)))
          (let [explain  (late-bind/get-fn-cached :schemas/explain-with-registered-fn)
                ;; rf2-99j4e4 — the thrown error data (and any downstream egress)
                ;; must not leak a `:sensitive?` params slot (nor ride a `:large?`
                ;; slot raw). Route `:params` + the explainer `:error` through the
                ;; resources-family classification projection (the SAME per-slot
                ;; owner surface SSR key egress uses + the shared schemas redaction
                ;; seam), so the owner's `:params-schema` marks govern the error
                ;; payload as they govern wire egress.
                redacted (classification/redact-invalid-params-error
                           params (when explain (explain schema params)) spec)]
            (throw (build-invalid-error (:params redacted) (:error redacted)))))))
    (state/canonicalize params)))
