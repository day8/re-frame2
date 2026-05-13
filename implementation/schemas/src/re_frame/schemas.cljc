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

  Per Spec 010 §Non-Malli validators (rf2-froe) the validator and
  explainer fns are pluggable via `set-schema-validator!` /
  `set-schema-explainer!`. The default validator delegates to Malli
  (`(resolve 'malli.core/validate)`); apps that want to opt out — to
  drop the ~24 KB gzipped Malli surface measured in
  findings/malli-bundle-cost-audit.md — register a different fn (or
  `nil` for a hard no-op). The `:spec` value remains opaque to the
  framework; only the registered validator interprets it.

  Per rf2-dgug5 the original 1247-LoC monolith was split along the
  banner-line concerns the file's own opening docstring described:

    - `re-frame.schemas.validator`      — pluggable validator/explainer
      atoms + `set-schema-validator!` / `set-schema-explainer!` /
      `reset-schema-validator!` / `run-validator` / `run-explainer`
      (rf2-froe / rf2-t0hq).
    - `re-frame.schemas.storage`        — per-frame schema registry
      (`schemas-by-frame`, `reg-app-schema` / `reg-app-schemas`,
      `app-schema-at` / `app-schemas`, `frame-schema-entries`,
      snapshot/restore/clear) + the hot-reload replacement-hook seam.
    - `re-frame.schemas.walker`         — unified per-slot flag walker
      (rf2-nwv63 / rf2-kj51z / rf2-oghml) parameterised on the flag
      key, plus `extract-large-paths-from-schema`,
      `extract-sensitive-paths-from-schema`, `schema-has-sensitive?`.
    - `re-frame.schemas.elision-feeder` — per-frame `:large?` /
      `:sensitive?` declaration aggregators + idempotent
      `[:rf/elision]` populators (rf2-v9tw2 / rf2-c1l4d).
    - `re-frame.schemas.digest`         — Spec 010 §Digest algorithm
      and `app-schemas-digest`.
    - `re-frame.schemas.validate`       — the five dev-time validation
      entry points (`validate-event!` / `validate-cofx!` /
      `validate-fx!` / `validate-app-db!` / `validate-sub-return!`)
      and the production-side boundary-validation seam
      (`validate-with-registered-fn` / `explain-with-registered-fn`).

  This namespace is the public façade — it re-exports the symbols
  every external consumer reaches through (tests, `re-frame.core-
  schemas`, the late-bind hook table) and owns the late-bind hook
  registration so consumer artefacts pick up the schemas surface
  without statically requiring it."
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

;; Validator / explainer (rf2-froe).
(def set-schema-validator!   validator/set-schema-validator!)
(def set-schema-explainer!   validator/set-schema-explainer!)
(def reset-schema-validator! validator/reset-schema-validator!)

;; Registration + per-frame query (Spec 010 §Per-frame schemas).
(def reg-app-schema       storage/reg-app-schema)
(def reg-app-schemas      storage/reg-app-schemas)
(def app-schema-at        storage/app-schema-at)
(def app-schemas          storage/app-schemas)
(def frame-schema-entries storage/frame-schema-entries)

;; Test-support snapshot / restore / clear.
(def snapshot-schemas-by-frame storage/snapshot-schemas-by-frame)
(def restore-schemas-by-frame! storage/restore-schemas-by-frame!)
(def clear-schemas-by-frame!   storage/clear-schemas-by-frame!)

;; Per-slot flag walker (rf2-nwv63 / rf2-kj51z / rf2-oghml).
(def walk-flagged-schema              walker/walk-flagged-schema)
(def extract-large-paths-from-schema  walker/extract-large-paths-from-schema)
(def extract-sensitive-paths-from-schema walker/extract-sensitive-paths-from-schema)
(def schema-has-sensitive?            walker/schema-has-sensitive?)

;; Per-flag registry feeders (rf2-v9tw2 / rf2-c1l4d).
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

;; Production-side boundary-validation seam (rf2-r2uh).
(def validate-with-registered-fn validate/validate-with-registered-fn)
(def explain-with-registered-fn  validate/explain-with-registered-fn)

;; ---- late-bind hook registration ------------------------------------------
;;
;; re-frame.router, re-frame.cofx, re-frame.subs, re-frame.elision,
;; re-frame.epoch, re-frame.test-support, and re-frame.core-schemas
;; need to call into schema validation but cannot `:require` this
;; namespace without forcing the schemas artefact onto every consumer's
;; classpath (per rf2-p7va — schemas is optional). Publish entry points
;; through the late-bind hook registry. See re-frame.late-bind.

(doseq [[k f]
        {;; Validation hot-path hooks (consumed by router / cofx / subs / epoch).
         :schemas/validate-app-db!     validate-app-db!
         :schemas/validate-event!      validate-event!
         :schemas/validate-sub-return! validate-sub-return!
         :schemas/validate-cofx!       validate-cofx!
         :schemas/validate-fx!         validate-fx!
         :schemas/frame-schema-entries frame-schema-entries

         ;; Boundary-validation seam (rf2-r2uh integration).
         :schemas/validate-with-registered-fn validate-with-registered-fn
         :schemas/explain-with-registered-fn  explain-with-registered-fn

         ;; Public-API re-export hooks (consumed by re-frame.core-schemas).
         :schemas/reg-app-schema        reg-app-schema
         :schemas/reg-app-schemas       reg-app-schemas
         :schemas/app-schema-at         app-schema-at
         :schemas/app-schemas           app-schemas
         :schemas/app-schemas-digest    app-schemas-digest
         :schemas/set-schema-validator! set-schema-validator!
         :schemas/set-schema-explainer! set-schema-explainer!

         ;; Elision-walker hooks (rf2-nwv63 / rf2-v9tw2) — published so
         ;; re-frame.core's downstream registry-population code can
         ;; hydrate `[:rf/elision :declarations]` at boot / on
         ;; reg-app-schema without statically depending on the schemas
         ;; artefact.
         :schemas/extract-large-paths-from-schema extract-large-paths-from-schema
         :schemas/frame-elision-declarations      frame-elision-declarations
         :schemas/populate-elision-declarations   populate-elision-declarations

         ;; Sensitive-paths walker (rf2-kj51z / rf2-c1l4d).
         :schemas/extract-sensitive-paths-from-schema extract-sensitive-paths-from-schema
         :schemas/schema-has-sensitive?               schema-has-sensitive?
         :schemas/frame-sensitive-declarations        frame-sensitive-declarations
         :schemas/populate-sensitive-declarations     populate-sensitive-declarations

         ;; Test-support hooks (consumed by re-frame.test-support's
         ;; reset-runtime-fixture).
         :schemas/snapshot-by-frame    snapshot-schemas-by-frame
         :schemas/restore-by-frame!    restore-schemas-by-frame!
         :schemas/clear-by-frame!      clear-schemas-by-frame!}]
  (late-bind/set-fn! k f))
