(ns re-frame.core-schemas
  "Public-API wrappers for the optional schemas artefact (Spec 010).
  Implementation ships in `day8/re-frame2-schemas` (`re-frame.schemas`).
  See [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention).

  The schemas artefact pulls Malli (the default validator) onto the
  classpath — apps that want to drop the ~24 KB gzipped Malli surface
  omit the artefact and either substitute another validator or skip
  schema validation entirely."
  (:require [re-frame.core-artefact #?@(:clj  [:refer        [defwrapper]]
                                        :cljs [:refer-macros [defwrapper]])]))

#?(:clj (set! *warn-on-reflection* true))

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

(defwrapper app-schema-meta-at
  "Return the full registration-metadata map for a path in a frame, or
  nil. Unlike `app-schema-at` (which returns just the `:schema` value),
  this returns the meta stamped at `reg-app-schema` — `:path`,
  `:schema`, `:frame`, and the source-coords `:ns` / `:line` / `:file`.
  The source-coord introspection surface pair-tools and 10x read when
  they need the registration anchor (e.g. click-back-to-code). Per Spec
  010 §Schemas as a tooling and agent surface. Returns nil when the
  schemas artefact is not on the classpath."
  {:hook :schemas/app-schema-meta-at :artefact schemas-artefact :on-absent :nil}
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

;; ---- validator-install wrappers — RETIRED (rf2-kuky.39) -------------------
;;
;; Four `defwrapper`s lived here — a bundle installer plus one per-fn sibling
;; each for validate, explain and print — served by four late-bind hooks.
;; rf2-wad2fl had already removed their `re-frame.core` re-exports (the
;; front-porch shrink), which left both the wrappers and their hooks with zero
;; consumers; rf2-kuky.39 deleted the three per-fn siblings outright and made
;; the validator port a VALUE behind one door. Install it through
;; `re-frame.schemas/set-schema-fns!` on the OWNING namespace — which is where
;; the front-porch boundary had already sent it.

(defwrapper reg-app-schema
  "Fn-form delegate that performs the late-bind lookup for
  `reg-app-schema`. The `re-frame.core/reg-app-schema` macro (JVM) and
  the CLJS `def`-alias both route here, so the late-bind logic and the
  missing-artefact error message live in one place.

  Per rf2-qm7k83 Part A `reg-app-schema` is an ordinary member of the
  `reg-*` family — the schema is the POSITIONAL value slot:
  `(reg-app-schema path schema)` (2-slot) / `(reg-app-schema path metadata
  schema)` (3-slot, `metadata` carries the optional `:frame` target).

  DEVELOPMENT-BUILD ASSERTION. Registration itself runs in every build,
  but the candidate validation it arms is dev-only: a production build
  never checks the schema, so a violating candidate installs silently.
  Per Spec 010 §Production builds."
  {:hook :schemas/reg-app-schema :artefact schemas-artefact :on-absent :throw
   :ex-data {:path path}}
  ([path schema]          :delegate)
  ([path metadata schema] :delegate))

(defwrapper reg-app-schemas
  "Bulk-register `{path -> schema}` against the active frame (or the
  `:frame` opt). The plural form of `reg-app-schema`, aimed at
  feature-modular apps (per Conventions §Feature-modularity prefix
  convention).

  Shape:

    (rf/reg-app-schemas {[:auth] AuthSlice
                         [:cart] CartSlice
                         ...})
    (rf/reg-app-schemas {...} {:frame :tenant/a})

  Returns the vector of paths registered. See `re-frame.schemas/reg-app-schemas`
  for full semantics and the singular-form fallback when deterministic
  ordering matters.

  DEVELOPMENT-BUILD ASSERTION, as for the singular form: every entry
  registers in a production build but is never checked there. Per Spec
  010 §Production builds."
  {:hook :schemas/reg-app-schemas :artefact schemas-artefact :on-absent :throw}
  ([path->schema]      [path->schema {}])
  ([path->schema opts] :delegate))
