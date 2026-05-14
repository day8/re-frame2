(ns re-frame.schemas
  "Schema attachment and dev-only validation. Per Spec 010.

  Schemas attach to registrations under :spec metadata; the validation
  fires at locked points (event vector before handler runs; sub return
  after compute; app-db after each handler completes). In dev builds
  only — production builds elide.

  Per Spec 010 §Per-frame schemas, `app-db` schemas are **frame-scoped**:
  registered against the active frame at registration time and looked
  up per frame at validation time. Stories, multi-instance widgets,
  per-test fixtures need shape-flexibility — a stripped-down schema
  registered against `:story.foo/empty` does NOT bleed into the
  `:rf/default` frame's contract. `(reg-app-schema path schema)` uses
  the active frame (`(frame/current-frame)`); `(reg-app-schema path
  schema {:frame frame-id})` registers explicitly.

  Per Spec 010 §Non-Malli validators the validator and explainer fns
  are pluggable via `set-schema-validator!` / `set-schema-explainer!`.
  The default delegates to Malli; apps that want to opt out — to drop
  the ~24 KB gzipped Malli surface — register a different fn (or `nil`
  for a hard no-op). The `:spec` value is opaque to the framework;
  only the registered validator interprets it.

  This namespace is the public façade over the sub-namespaces:

    - `re-frame.schemas.validator`      — pluggable validator / explainer
                                          atoms + setters / resetters.
    - `re-frame.schemas.storage`        — per-frame schema registry (the
                                          single source of truth; there
                                          is NO registrar `:app-schema`
                                          slot).
    - `re-frame.schemas.walker`         — unified per-slot flag walker
                                          plus path extractors.
    - `re-frame.schemas.elision-feeder` — `:large?` / `:sensitive?`
                                          aggregators + idempotent
                                          `[:rf/elision]` populators.
    - `re-frame.schemas.digest`         — `app-schemas-digest` (Spec 010
                                          §Digest algorithm).
    - `re-frame.schemas.validate`       — the five dev-time validation
                                          entry points + the production
                                          boundary-validation seam."
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.schemas.digest :as digest]
            [re-frame.schemas.elision-feeder :as elision-feeder]
            [re-frame.schemas.storage :as storage]
            [re-frame.schemas.validate :as validate]
            [re-frame.schemas.validator :as validator]
            [re-frame.schemas.walker :as walker]))

;; ---- public-surface re-exports --------------------------------------------
;;
;; Atoms re-exported as Vars so test fixtures can `(reset! schemas/
;; schemas-by-frame {})` and `@schemas/validator-fn` against the same
;; underlying atom value.

(def schemas-by-frame storage/schemas-by-frame)
(def validator-fn     validator/validator-fn)
(def explainer-fn     validator/explainer-fn)
(def printer-fn       validator/printer-fn)

;; Validator / explainer / printer.
(def set-schema-validator!   validator/set-schema-validator!)
(def set-schema-explainer!   validator/set-schema-explainer!)
(def set-schema-printer!     validator/set-schema-printer!)
(def reset-schema-validator! validator/reset-schema-validator!)

;; Registration + per-frame query (Spec 010 §Per-frame schemas).
(def reg-app-schema       storage/reg-app-schema)
(def reg-app-schemas      storage/reg-app-schemas)
(def app-schema-at        storage/app-schema-at)
(def app-schema-meta-at   storage/app-schema-meta-at)
(def app-schemas          storage/app-schemas)
(def frame-schema-entries storage/frame-schema-entries)

;; Test-support snapshot / restore / clear.
(def snapshot-schemas-by-frame storage/snapshot-schemas-by-frame)
(def restore-schemas-by-frame! storage/restore-schemas-by-frame!)
(def clear-schemas-by-frame!   storage/clear-schemas-by-frame!)

;; Per-slot flag walker.
(def walk-flagged-schema              walker/walk-flagged-schema)
(def extract-large-paths-from-schema  walker/extract-large-paths-from-schema)
(def extract-sensitive-paths-from-schema walker/extract-sensitive-paths-from-schema)
(def schema-has-sensitive?            walker/schema-has-sensitive?)

;; Per-flag registry feeders.
(def frame-elision-declarations    elision-feeder/frame-elision-declarations)
(def populate-elision-declarations elision-feeder/populate-elision-declarations)
(def frame-sensitive-declarations    elision-feeder/frame-sensitive-declarations)
(def populate-sensitive-declarations elision-feeder/populate-sensitive-declarations)

;; Schema digest (Spec 010 §Digest algorithm).
(def app-schemas-digest digest/app-schemas-digest)

;; Validation entry points (Spec 010 §Validation order).
(def validate-app-db!     validate/validate-app-db!)
(def validate-event!      validate/validate-event!)
(def validate-cofx!       validate/validate-cofx!)
(def validate-fx!         validate/validate-fx!)
(def validate-sub-return! validate/validate-sub-return!)

;; Production-side boundary-validation seam.
(def validate-with-registered-fn validate/validate-with-registered-fn)
(def explain-with-registered-fn  validate/explain-with-registered-fn)

;; ---- late-bind hook registration ------------------------------------------
;; Literal `set-fn!` invocations with literal keywords (one per line) —
;; the late-bind drift gate `re-frame.late-bind-drift-test` detects each
;; publication via regex.

;; Validation hot-path hooks (consumed by router / cofx / subs / epoch).
(late-bind/set-fn! :schemas/validate-app-db!     validate-app-db!)
(late-bind/set-fn! :schemas/validate-event!      validate-event!)
(late-bind/set-fn! :schemas/validate-sub-return! validate-sub-return!)
(late-bind/set-fn! :schemas/validate-cofx!       validate-cofx!)
(late-bind/set-fn! :schemas/validate-fx!         validate-fx!)
(late-bind/set-fn! :schemas/frame-schema-entries frame-schema-entries)

;; Boundary-validation seam.
(late-bind/set-fn! :schemas/validate-with-registered-fn validate-with-registered-fn)
(late-bind/set-fn! :schemas/explain-with-registered-fn  explain-with-registered-fn)

;; Public-API re-export hooks (consumed by re-frame.core-schemas).
(late-bind/set-fn! :schemas/reg-app-schema        reg-app-schema)
(late-bind/set-fn! :schemas/reg-app-schemas       reg-app-schemas)
(late-bind/set-fn! :schemas/app-schema-at         app-schema-at)
(late-bind/set-fn! :schemas/app-schemas           app-schemas)
(late-bind/set-fn! :schemas/app-schemas-digest    app-schemas-digest)
(late-bind/set-fn! :schemas/set-schema-validator! set-schema-validator!)
(late-bind/set-fn! :schemas/set-schema-explainer! set-schema-explainer!)
(late-bind/set-fn! :schemas/set-schema-printer!   set-schema-printer!)

;; Schema-walker hooks consumed by `re-frame.elision` to feed the
;; unified `[:rf/elision]` registry without statically depending on the
;; schemas artefact. Schemas owns the deep walker; the elision artefact
;; owns the app-db write.
(late-bind/set-fn! :schemas/extract-large-paths-from-schema     extract-large-paths-from-schema)
(late-bind/set-fn! :schemas/extract-sensitive-paths-from-schema extract-sensitive-paths-from-schema)

;; Test-support hooks (consumed by re-frame.test-support's
;; reset-runtime-fixture).
(late-bind/set-fn! :schemas/snapshot-by-frame    snapshot-schemas-by-frame)
(late-bind/set-fn! :schemas/restore-by-frame!    restore-schemas-by-frame!)
(late-bind/set-fn! :schemas/clear-by-frame!      clear-schemas-by-frame!)
