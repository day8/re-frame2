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

(defwrapper set-schema-validator!
  "Register the validator fn that every dev-time schema-validation site
  routes through. Per Spec 010 §Non-Malli validators (rf2-froe) the
  seam is the substitute-Malli extension point — apps that want to
  drop the ~24 KB gzipped Malli surface (rf2-qnxf bundle audit) swap
  in their own validator at boot.

    (schemas/set-schema-validator! validate-fn)
      validate-fn :: (fn [schema value] truthy?)
                   | nil   ;; disables validation entirely

  Swaps ONLY the validator. The explainer / printer are left untouched
  — call `set-schema-explainer!` / `set-schema-printer!`, or use
  `set-schema-fns!` to install all three as one atomic bundle
  (rf2-13meg).

  Default behaviour ships Malli's validate / explain pair; calling
  this fn replaces the validator. Returns nil when the schemas
  artefact is not on the classpath (apps that don't want schemas don't
  need to pull `day8/re-frame2-schemas`)."
  {:hook :schemas/set-schema-validator! :artefact schemas-artefact :on-absent :nil}
  ([validate-fn] :delegate))

(defwrapper set-schema-fns!
  "Atomically install any subset of the validator / explainer / printer
  bundle from a single map (rf2-13meg). The honest bundle setter — its
  name says it sets all three schema-language fns, not just the
  validator.

    (schemas/set-schema-fns! {:validate validate-fn
                         :explain  explain-fn
                         :print    print-fn})

  Each key is optional; an absent key leaves the existing registration
  in place. A `nil` `:print` coerces to the default EDN canonicaliser
  (the digest is never undefined for a present schema set). This is the
  one-call substitute-Malli boot pattern — a Zod / clojure.spec port
  installs its validator, explainer, and digest-printer together so the
  three never drift mid-boot.

  Returns the installed bundle as a map `{:validate ... :explain ...
  :print ...}` reflecting the live state of all three fns after the call
  (rf2-qdtcx2 — a bundle setter returns its bundle), or nil when the
  schemas artefact is not on the classpath. See
  `re-frame.schemas/set-schema-fns!`."
  {:hook :schemas/set-schema-fns! :artefact schemas-artefact :on-absent :nil}
  ([fns-map] :delegate))

(defwrapper set-schema-explainer!
  "Register the explainer fn — `(fn [schema value] explanation)` — used
  to enrich schema-validation-failure traces' `:explain` key. Per
  Spec 010 §Non-Malli validators (rf2-froe). See
  `set-schema-validator!` for the validator companion. Returns nil
  when the schemas artefact is not on the classpath."
  {:hook :schemas/set-schema-explainer! :artefact schemas-artefact :on-absent :nil}
  ([explain-fn] :delegate))

(defwrapper set-schema-printer!
  "Register the schema-print companion — `(fn [schema-value]
  canonical-string)` — the digest pipeline hashes (Spec 010 §Schema
  digest line 491, rf2-wla45). Parallel to `set-schema-validator!`
  and `set-schema-explainer!`: ports that ship a non-Malli schema
  language register their own serialiser so the digest reflects the
  validator's own contract rather than the framework's Malli-EDN
  default.

  The fn MUST be pure and cross-runtime deterministic — a CLJS
  server and a CLJS client running the same schema set MUST produce
  the same bytes (Spec 010 §Digest algorithm). Setting `nil` falls
  back to the default EDN canonicaliser so the digest is never
  undefined.

  Returns nil when the schemas artefact is not on the classpath."
  {:hook :schemas/set-schema-printer! :artefact schemas-artefact :on-absent :nil}
  ([print-fn] :delegate))

(defwrapper reg-app-schema
  "Fn-form delegate that performs the late-bind lookup for
  `reg-app-schema`. The `re-frame.core/reg-app-schema` macro (JVM) and
  the CLJS `def`-alias both route here, so the late-bind logic and the
  missing-artefact error message live in one place.

  Per rf2-wvh95f F2 the grammar is `(reg-app-schema path metadata)` where the
  schema rides under `:schema` in the metadata map (:schema-in-metadata)."
  {:hook :schemas/reg-app-schema :artefact schemas-artefact :on-absent :throw
   :ex-data {:path path}}
  ([path metadata] :delegate))

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
  ordering matters."
  {:hook :schemas/reg-app-schemas :artefact schemas-artefact :on-absent :throw}
  ([path->schema]      [path->schema {}])
  ([path->schema opts] :delegate))
