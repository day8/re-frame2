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
      `app-schema-at` / `app-schema-meta-at` / `app-schemas`,
      `frame-schema-entries`, snapshot/restore/clear). Per rf2-0frdi
      this is the single source of truth — there is no registrar
      `:app-schema` slot.
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
(def printer-fn       validator/printer-fn)

;; Validator / explainer / printer (rf2-froe + rf2-wla45).
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
(def on-frame-destroyed!       storage/on-frame-destroyed!)

;; Per-slot flag walker (rf2-nwv63 / rf2-kj51z / rf2-oghml).
(def walk-flagged-schema              walker/walk-flagged-schema)
(def extract-large-paths-from-schema  walker/extract-large-paths-from-schema)
(def extract-sensitive-paths-from-schema walker/extract-sensitive-paths-from-schema)
(def schema-has-sensitive?            walker/schema-has-sensitive?)
(def schema-sensitive-at?             walker/schema-sensitive-at?)

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
;;
;; Calls are written as literal `set-fn!` invocations with a literal
;; keyword (one per line) rather than collapsed into a data-driven
;; doseq, so the late-bind drift gate
;; (`re-frame.late-bind-drift-test`) can detect each publication via
;; regex — matching the convention of every other artefact's
;; publication block (flows / machines / routing / http / ssr).

;; Validation hot-path hooks (consumed by router / cofx / subs / epoch).
(late-bind/set-fn! :schemas/validate-app-db!     validate-app-db!)
(late-bind/set-fn! :schemas/validate-event!      validate-event!)
(late-bind/set-fn! :schemas/validate-sub-return! validate-sub-return!)
(late-bind/set-fn! :schemas/validate-cofx!       validate-cofx!)
(late-bind/set-fn! :schemas/validate-fx!         validate-fx!)
(late-bind/set-fn! :schemas/frame-schema-entries frame-schema-entries)
;; Frame-destroy cleanup hook (consumed by frame/destroy-frame!,
;; mirrors :machines/on-frame-destroyed! and :ssr/on-frame-destroyed).
(late-bind/set-fn! :schemas/on-frame-destroyed!  on-frame-destroyed!)

;; Boundary-validation seam (rf2-r2uh integration).
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
;; schemas artefact. Per rf2-ynnq0 Option A — schemas owns the deep
;; walker; the elision artefact owns the app-db write. The Path D impl
;; (rf2-w3n5u) wires `re-frame.elision/populate-elision-from-schemas!`
;; to consume these two hooks. The five sibling feeders that previously
;; sat alongside (`:schemas/frame-elision-declarations`,
;; `:schemas/populate-elision-declarations`, `:schemas/schema-has-
;; sensitive?`, `:schemas/frame-sensitive-declarations`,
;; `:schemas/populate-sensitive-declarations`) were deleted per
;; rf2-5q7r0 — they had zero consumers in tree, and Option A makes the
;; per-slot extract-* hooks the single surface elision needs.
(late-bind/set-fn! :schemas/extract-large-paths-from-schema     extract-large-paths-from-schema)
(late-bind/set-fn! :schemas/extract-sensitive-paths-from-schema extract-sensitive-paths-from-schema)

;; Test-support hooks (consumed by re-frame.test-support's
;; reset-runtime-fixture).
(late-bind/set-fn! :schemas/snapshot-by-frame    snapshot-schemas-by-frame)
(late-bind/set-fn! :schemas/restore-by-frame!    restore-schemas-by-frame!)
(late-bind/set-fn! :schemas/clear-by-frame!      clear-schemas-by-frame!)
