(ns re-frame.schemas
  "Schema attachment and dev-only validation. Per Spec 010.

  Schemas attach to registrations under :schema metadata; the validation
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

  Per Spec 010 §Non-Malli validators the validator/explainer fns are
  pluggable via `set-schema-validator!` / `set-schema-explainer!`. The
  default validator delegates to Malli; apps drop Malli by registering
  a different fn (or `nil` for a hard no-op).

  Public façade over `re-frame.schemas.{validator,storage,walker,
  digest,validate}` — re-exports the symbols external consumers reach
  through (tests, `re-frame.core-schemas`, the late-bind hook table)
  and owns the late-bind hook publication."
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.schemas.digest :as digest]
            [re-frame.schemas.storage :as storage]
            [re-frame.schemas.validate :as validate]
            [re-frame.schemas.validator :as validator]
            [re-frame.schemas.walker :as walker]))

;; ---- public-surface re-exports --------------------------------------------
;;
;; Atoms re-exported as Vars so test fixtures can `(reset! schemas/
;; schemas-by-frame {})` and `@schemas/validator-fn` against the same
;; underlying atom value.
;;
;; Posture (rf2-1gm0o — public-by-design): the four atom Vars are an
;; **intentional fixture-composition primitive**, not a backdoor. Test
;; fixtures across the implementation tree (~70+ test namespaces under
;; `implementation/{core,epoch,flows,http,routing,schemas,ssr}/test/`)
;; compose `(reset! schemas/schemas-by-frame {})` directly alongside
;; `(reset! re-frame.flows/flows {})` and the other per-feature atom
;; resets to express "wipe per-feature state to a known shape, atomically,
;; before this test." The dedicated `snapshot-schemas-by-frame` /
;; `restore-schemas-by-frame!` / `clear-schemas-by-frame!` /
;; `reset-schema-validator!` fns serve the **registered-test-fixture
;; pathway** (consumed by `re-frame.test-support`'s `reset-runtime-
;; fixture` via the late-bind hook table); the raw atom Vars serve the
;; **ad-hoc-test-fixture pathway** where the test author composes the
;; setup without going through the registered fixture. Both surfaces are
;; documented as supported. The atoms are NOT marked `^:private` because
;; the dual pathway is by design — see rf2-ycqtv audit Finding #5.

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
(def clear-validator-unavailable-warned!
  storage/clear-validator-unavailable-warned!)
(def clear-walker-opaque-warned!
  storage/clear-walker-opaque-warned!)

;; Per-slot flag walker (rf2-nwv63 / rf2-kj51z / rf2-oghml).
(def walk-flagged-schema              walker/walk-flagged-schema)
(def extract-large-paths-from-schema  walker/extract-large-paths-from-schema)
(def extract-sensitive-paths-from-schema walker/extract-sensitive-paths-from-schema)
(def schema-has-sensitive?            walker/schema-has-sensitive?)
(def schema-sensitive-at?             walker/schema-sensitive-at?)

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
;; to consume these two hooks; the per-slot extract-* hooks are the
;; single surface elision needs.
(late-bind/set-fn! :schemas/extract-large-paths-from-schema     extract-large-paths-from-schema)
(late-bind/set-fn! :schemas/extract-sensitive-paths-from-schema extract-sensitive-paths-from-schema)

;; Test-support hooks (consumed by re-frame.test-support's
;; make-reset-runtime-fixture).
(late-bind/set-fn! :schemas/snapshot-by-frame    snapshot-schemas-by-frame)
(late-bind/set-fn! :schemas/restore-by-frame!    restore-schemas-by-frame!)
(late-bind/set-fn! :schemas/clear-by-frame!      clear-schemas-by-frame!)
