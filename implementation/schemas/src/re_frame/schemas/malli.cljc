(ns re-frame.schemas.malli
  "Malli adapter for the schemas artefact.

  Per Spec 010 §Default validator the CLJS reference's default validator
  delegates to Malli (`malli.core/validate` / `malli.core/explain`).
  This namespace publishes Malli's `validate`, `explain`, and `humanize`
  functions into the framework's late-bind hook table.
  `re-frame.schemas.validator/default-malli-validate` and
  `re-frame.schemas.validator/default-malli-explain` consult the table
  at call time. An absent validator hook soft-passes; an absent explainer
  or humanizer produces no diagnostic value.

  Applications do not require this namespace explicitly: the
  `re-frame.schemas` facade requires it for
  its ns-load side effect, so loading the schemas artefact publishes the
  Malli hooks automatically on both CLJS and the JVM:

      (ns my-app.core
        (:require [re-frame.core :as rf]
                  [re-frame.schemas])) ;; transitively loads this adapter

  Because the facade pulls Malli in, the only Malli-FREE posture is to
  not require `re-frame.schemas` at all. An app that uses schemas but
  wants to drop Malli's validation *behaviour* (it still pays the bundle
  cost under static CLJS compilation, since requiring the facade loads
  this ns's body) either:

    - Calls `(schemas/set-schema-validator! my-validator-fn)` at boot to
      install a custom validator, or
    - Calls `(schemas/set-schema-validator! nil)` for a hard no-op.

  See the `re-frame.schemas` namespace docstring for the bundle boundary."
  (:require [malli.core]
            [malli.error]
            [re-frame.late-bind :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- publish Malli into the late-bind hook table -------------------------
;;
;; The hooks `:schemas/malli-validate` and
;; `:schemas/malli-explain` are read by
;; `re-frame.schemas.validator/default-malli-validate` /
;; `default-malli-explain` on every validate call. Both
;; sides of the seam are in this artefact so the contract is local;
;; the seam is published as a hook key so future ports
;; can register their own default validator pair without changing this
;; namespace.

(late-bind/set-fn! :schemas/malli-validate  malli.core/validate)
(late-bind/set-fn! :schemas/malli-explain   malli.core/explain)

;; Consumers use the same humanize hook regardless of validator adapter.
(late-bind/set-fn! :schemas/humanize-explain! malli.error/humanize)
