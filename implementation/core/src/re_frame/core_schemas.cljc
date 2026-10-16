(ns re-frame.core-schemas
  "Public-API wrappers for the optional schemas artefact (Spec 010).
  Implementation ships in `day8/re-frame2-schemas` (`re-frame.schemas`).
  See [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention).

  The schemas artefact pulls Malli (the default validator) onto the
  classpath — apps that want to drop the ~24 KB gzipped Malli surface
  omit the artefact and either substitute another validator or skip
  schema validation entirely."
  (:require [re-frame.core-artefact #?@(:clj  [:refer        [artefact-info defwrapper]]
                                        :cljs [:refer        [artefact-info]
                                               :refer-macros [defwrapper]])]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private schemas-artefact (artefact-info :schemas))

;; ---- validator install ---------------------------------------------------
;;
;; There is no core-side validator-install wrapper and no late-bind hook for
;; one: the validator port is a VALUE behind one door. Install it through
;; `re-frame.schemas/set-schema-fns!` on the OWNING namespace.

(defwrapper reg-app-schema
  "Fn-form delegate that performs the late-bind lookup for
  `reg-app-schema`. The `re-frame.core/reg-app-schema` macro (JVM) and
  the CLJS `def`-alias both route here, so the late-bind logic and the
  missing-artefact error message live in one place.

  `reg-app-schema` is an ordinary member of the
  `reg-*` family — the schema is the POSITIONAL value slot:
  `(reg-app-schema path schema)` (2-slot) / `(reg-app-schema path metadata
  schema)` (3-slot). It registers the registrar kind `:app-schema`, keyed by
  the canonical path, frame-agnostically; a `:frame` is an error.

  DEVELOPMENT-BUILD ASSERTION. Registration itself runs in every build,
  but the candidate validation it arms is dev-only: a production build
  never checks the schema, so a violating candidate installs silently.
  Per Spec 010 §Production builds."
  {:hook :schemas/reg-app-schema :artefact schemas-artefact :on-absent :throw
   :ex-data {:path path}}
  ([path schema]          :delegate)
  ([path metadata schema] :delegate))

(defwrapper reg-app-schemas
  "Bulk-register `{path -> schema}`, each entry its own `:app-schema`
  registration. The plural form of `reg-app-schema`, aimed at
  feature-modular apps (per Conventions §Feature-modularity prefix
  convention).

  Shape:

    (rf/reg-app-schemas {[:auth] AuthSlice
                         [:cart] CartSlice
                         ...})

  Returns the vector of paths registered. See `re-frame.schemas/reg-app-schemas`
  for full semantics and the singular-form fallback when deterministic
  ordering matters.

  DEVELOPMENT-BUILD ASSERTION, as for the singular form: every entry
  registers in a production build but is never checked there. Per Spec
  010 §Production builds."
  {:hook :schemas/reg-app-schemas :artefact schemas-artefact :on-absent :throw}
  ([path->schema]      [path->schema {}])
  ([path->schema opts] :delegate))
