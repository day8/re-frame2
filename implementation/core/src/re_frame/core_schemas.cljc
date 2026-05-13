(ns re-frame.core-schemas
  "Public-API wrappers for the optional schemas artefact (Spec 010).
  Implementation ships in `day8/re-frame2-schemas`
  (`re-frame.schemas` ns) per rf2-p7va.

  Per [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention) — wrappers
  look the producing fns up via the late-bind hook table at call time;
  consumers reach the surfaces through `re-frame.core` re-exports.

  Per-feature carve-out: the schemas artefact pulls Malli (the default
  validator) onto the classpath — apps that want to drop the ~24 KB
  gzipped Malli surface omit the artefact and either use a substitute
  validator (per rf2-froe) or skip schema validation entirely.

  Per rf2-h824v the wrappers below are emitted by the
  `re-frame.core-artefact/defwrapper` factory from a declarative table —
  one row per public surface."
  (:require [re-frame.core-artefact #?@(:clj  [:refer        [defwrapper]]
                                        :cljs [:refer-macros [defwrapper]])]))

(def ^:private schemas-artefact
  {:error-keyword :rf.error/schemas-artefact-missing
   :maven         "day8/re-frame2-schemas"
   :require-ns    "re-frame.schemas"})

(defwrapper app-schema-at
  "Return the registered schema for a path in a frame, or nil. Per Spec
  010 §Schemas as a tooling and agent surface. Returns nil when the
  schemas artefact is not on the classpath."
  {:hook :schemas/app-schema-at :artefact schemas-artefact :on-absent :nil}
  ([path]      [path {}])
  ([path opts] :delegate))

(defwrapper app-schemas
  "Return every registered `app-schema-at` declaration for a frame as a
  `{path → schema}` map. Per Spec 010 §Per-frame schemas. Returns `{}`
  when the schemas artefact is not on the classpath."
  {:hook :schemas/app-schemas :artefact schemas-artefact :on-absent :empty-map}
  ([]                 [{}])
  ([opts-or-frame-id] :delegate))

(defwrapper app-schemas-digest
  "Return a stable digest of the registered schemas for a frame. Per
  Spec 010 §Digest algorithm. Returns `nil` when the schemas artefact
  is not on the classpath."
  {:hook :schemas/app-schemas-digest :artefact schemas-artefact :on-absent :nil}
  ([]                 [{}])
  ([opts-or-frame-id] :delegate))

(defwrapper set-schema-validator!
  "Register the validator fn that every dev-time schema-validation site
  routes through. Per Spec 010 §Non-Malli validators (rf2-froe) the
  seam is the substitute-Malli extension point — apps that want to
  drop the ~24 KB gzipped Malli surface (rf2-qnxf bundle audit) swap
  in their own validator at boot.

  Argument shapes:

    (rf/set-schema-validator! validate-fn)
      validate-fn :: (fn [schema value] truthy?)
                   | nil   ;; disables validation entirely

    (rf/set-schema-validator! {:validate validate-fn :explain explain-fn})
      Atomic swap of both fns at once.

  Default behaviour ships Malli's validate / explain pair; calling
  this fn replaces them. Returns nil when the schemas artefact is
  not on the classpath (apps that don't want schemas don't need to
  pull `day8/re-frame2-schemas`)."
  {:hook :schemas/set-schema-validator! :artefact schemas-artefact :on-absent :nil}
  ([validate-fn-or-map] :delegate))

(defwrapper set-schema-explainer!
  "Register the explainer fn — `(fn [schema value] explanation)` — used
  to enrich schema-validation-failure traces' `:explain` key. Per
  Spec 010 §Non-Malli validators (rf2-froe). See
  `set-schema-validator!` for the validator companion. Returns nil
  when the schemas artefact is not on the classpath."
  {:hook :schemas/set-schema-explainer! :artefact schemas-artefact :on-absent :nil}
  ([explain-fn] :delegate))

(defwrapper reg-app-schema
  "Fn-form delegate that performs the late-bind lookup for
  `reg-app-schema`. The `re-frame.core/reg-app-schema` macro (JVM) and
  the CLJS `def`-alias both route here, so the late-bind logic and the
  missing-artefact error message live in one place."
  {:hook :schemas/reg-app-schema :artefact schemas-artefact :on-absent :throw
   :ex-data {:path path}}
  ([path schema]      [path schema {}])
  ([path schema opts] :delegate))

(defwrapper reg-app-schemas
  "Bulk-register `{path -> schema}` against the active frame (or the
  `:frame` opt). Per rf2-jzs9 — the plural form of `reg-app-schema`,
  aimed at feature-modular apps (per Conventions §Feature-modularity
  prefix convention).

  Shape:

    (rf/reg-app-schemas {[:auth] AuthSlice
                         [:cart] CartSlice
                         ...})
    (rf/reg-app-schemas {...} {:frame :tenant/a})

  Returns the vector of paths registered. See `re-frame.schemas/reg-app-schemas`
  for full semantics and the singular-form fallback when deterministic
  ordering matters."
  {:hook :schemas/reg-app-schemas :artefact schemas-artefact :on-absent :throw}
  ([path->schema]      [path->schema {}])
  ([path->schema opts] :delegate))
