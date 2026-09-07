(ns re-frame.schemas
  "Schema registration, validation, classification, and digest façade.

  App-db schemas are frame-scoped. A registration without an explicit
  `:frame` uses the carried frame scope and fails when no scope exists.
  Validation runs at the event, effect, subscription, and post-commit
  boundaries in development builds; the boundary interceptor can also
  invoke the registered validator in production.

  Loading this artefact also loads the Malli adapter, so registering a
  schema enables validation by default. Applications can install a
  different validator bundle, or set the validator to nil to make schemas
  inert data. The latter disables validation but does not remove Malli from
  a statically compiled bundle; applications that do not require this
  optional artefact remain Malli-free.

  This namespace re-exports the supported entry points and publishes the
  hooks used by core and other optional artefacts."
  (:require [re-frame.late-bind :as rf.late-bind]
            ;; Publishes the default validator bundle at namespace load.
            [re-frame.schemas.malli]
            [re-frame.schemas.digest :as rf.schemas.digest]
            [re-frame.schemas.storage :as rf.schemas.storage]
            [re-frame.schemas.validate :as rf.schemas.validate]
            [re-frame.schemas.validator :as rf.schemas.validator]
            [re-frame.schemas.walker :as rf.schemas.walker]))

;; Raw registry and validator atoms stay private to their owning namespaces.
;; Snapshot, restore, clear, and setter functions preserve representation and
;; never-nil printer invariants across the public boundary.

;; The validator port is a VALUE: one installer, one read, and the framework
;; default as a value. Capture / stub / restore is a `let` + `finally` over
;; `schema-fns`, and restoring the defaults is `(set-schema-fns!
;; default-schema-fns)` — no dedicated reset, snapshot or restore verb.
(def set-schema-fns!     rf.schemas.validator/set-schema-fns!)
(def schema-fns          rf.schemas.validator/schema-fns)
(def default-schema-fns  rf.schemas.validator/default-schema-fns)

;; Production caches are bounded by boot-time schema cardinality. Fixtures
;; that generate fresh schemas clear them between tests.
(def clear-edn-print-cache!       rf.schemas.validator/clear-edn-print-cache!)
(def clear-sensitive-paths-cache! rf.schemas.walker/clear-sensitive-paths-cache!)

;; Registration + per-frame query (Spec 010 §Per-frame schemas).
(def reg-app-schema       rf.schemas.storage/reg-app-schema)
(def reg-app-schemas      rf.schemas.storage/reg-app-schemas)
(def app-schema-at        rf.schemas.storage/app-schema-at)
(def app-schema-meta-at   rf.schemas.storage/app-schema-meta-at)
(def app-schemas          rf.schemas.storage/app-schemas)
(def frame-schema-entries rf.schemas.storage/frame-schema-entries)

;; Test-support snapshot / restore / clear.
(def snapshot-schemas-by-frame rf.schemas.storage/snapshot-schemas-by-frame)
(def restore-schemas-by-frame! rf.schemas.storage/restore-schemas-by-frame!)
(def clear-schemas-by-frame!   rf.schemas.storage/clear-schemas-by-frame!)
(def on-frame-destroyed!       rf.schemas.storage/on-frame-destroyed!)
(def clear-validator-unavailable-warned!
  rf.schemas.storage/clear-validator-unavailable-warned!)
(def clear-walker-opaque-warned!
  rf.schemas.storage/clear-walker-opaque-warned!)

;; Per-slot flag extractors support schema-local validation and tool egress.
;; Durable app-db classification is owned by frame commit effects, not schemas.
(def extract-large-paths-from-schema  rf.schemas.walker/extract-large-paths-from-schema)
(def extract-sensitive-paths-from-schema rf.schemas.walker/extract-sensitive-paths-from-schema)
(def schema-has-sensitive?            rf.schemas.walker/schema-has-sensitive?)
(def schema-sensitive-at?             rf.schemas.walker/schema-sensitive-at?)
;; Opaque schemas cannot be inspected for flags and therefore take the
;; conservative failure-redaction path.
(def schema-has-large?                rf.schemas.walker/schema-has-large?)
(def schema-opaque?                   rf.schemas.walker/schema-opaque?)
;; Redaction and registration warnings use the recursive opaque check because
;; a walkable vector can contain an opaque descendant.
(def schema-has-opaque-child?         rf.schemas.walker/schema-has-opaque-child?)

;; Schema digest (Spec 010 §Digest algorithm).
(def app-schemas-digest rf.schemas.digest/app-schemas-digest)

;; Validation entry points in the order defined by Spec 010. Recordable cofx
;; values are validated by `re-frame.cofx`, not by this dev-time family.
(def validate-app-schema! rf.schemas.validate/validate-app-schema!)
(def validate-event!      rf.schemas.validate/validate-event!)
(def validate-fx!         rf.schemas.validate/validate-fx!)
(def validate-sub!        rf.schemas.validate/validate-sub!)

;; Production-side boundary-validation seam.
(def validate-with-registered-fn rf.schemas.validate/validate-with-registered-fn)
(def explain-with-registered-fn  rf.schemas.validate/explain-with-registered-fn)
;; Shared schema-aware redaction for validation failures emitted by callers
;; outside this artefact.
(def redact-validation-tags      rf.schemas.validate/redact-validation-tags)

;; ---- late-bind hook registration ------------------------------------------
;;
;; Core and sibling artefacts use late-bound hooks so schemas remains optional.
;;
;; Calls are written as literal `set-fn!` invocations with a literal
;; keyword (one per line) rather than collapsed into a data-driven
;; doseq, so the late-bind drift gate
;; (`re-frame.late-bind-drift-test`) can detect each publication via
;; regex — matching the convention of every other artefact's
;; publication block (flows / machines / routing / http / ssr).

;; Validation hot-path hooks (consumed by router / subs / epoch).
;; Recordable cofx validation owns its own production error contract.
(rf.late-bind/set-fn! :schemas/validate-app-schema! validate-app-schema!)
(rf.late-bind/set-fn! :schemas/validate-event!      validate-event!)
(rf.late-bind/set-fn! :schemas/validate-sub!        validate-sub!)
(rf.late-bind/set-fn! :schemas/validate-fx!         validate-fx!)
(rf.late-bind/set-fn! :schemas/frame-schema-entries frame-schema-entries)
;; Frame-destroy cleanup hook (consumed by frame/destroy-frame!,
;; mirrors :machines/on-frame-destroyed! and :ssr/on-frame-destroyed).
(rf.late-bind/set-fn! :schemas/on-frame-destroyed!  on-frame-destroyed!)

;; Boundary-validation seam.
(rf.late-bind/set-fn! :schemas/validate-with-registered-fn validate-with-registered-fn)
(rf.late-bind/set-fn! :schemas/explain-with-registered-fn  explain-with-registered-fn)
;; Shared validation-failure redaction hook.
(rf.late-bind/set-fn! :schemas/redact-validation-tags      redact-validation-tags)

;; Public-API re-export hooks (consumed by re-frame.core-schemas).
(rf.late-bind/set-fn! :schemas/reg-app-schema        reg-app-schema)
(rf.late-bind/set-fn! :schemas/reg-app-schemas       reg-app-schemas)
(rf.late-bind/set-fn! :schemas/app-schema-at         app-schema-at)
(rf.late-bind/set-fn! :schemas/app-schema-meta-at    app-schema-meta-at)
(rf.late-bind/set-fn! :schemas/app-schemas           app-schemas)
(rf.late-bind/set-fn! :schemas/app-schemas-digest    app-schemas-digest)

;; Pure-data per-slot extractors. These do not populate durable app-db
;; classification; frame commit effects own that policy.
(rf.late-bind/set-fn! :schemas/extract-large-paths-from-schema     extract-large-paths-from-schema)
(rf.late-bind/set-fn! :schemas/extract-sensitive-paths-from-schema extract-sensitive-paths-from-schema)

;; Test-support hooks (consumed by re-frame.test-support's
;; make-reset-runtime-fixture).
(rf.late-bind/set-fn! :schemas/snapshot-by-frame    snapshot-schemas-by-frame)
(rf.late-bind/set-fn! :schemas/restore-by-frame!    restore-schemas-by-frame!)
(rf.late-bind/set-fn! :schemas/clear-by-frame!      clear-schemas-by-frame!)
