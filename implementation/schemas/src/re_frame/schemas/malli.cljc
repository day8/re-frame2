(ns re-frame.schemas.malli
  "Malli adapter for the schemas artefact.

  Per Spec 010 §Default validator the CLJS reference's default validator
  delegates to Malli (`malli.core/validate` / `malli.core/explain`).
  This namespace publishes Malli's `validate` and `explain` functions
  into the framework's late-bind hook table in every build, and its
  `humanize` function in development builds only.
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

  `malli.error` is the one Malli namespace this adapter keeps off the
  production path. The framework's only reader of the humanizer is the
  `:explain-humanized` enrichment of `:rf.error/schema-validation-failure`
  traces, and those emit bodies already sit behind
  `interop/debug-enabled?`, so the humanizer is published under the same
  gate. Under `:advanced` + `goog.DEBUG=false` the publication folds
  away and Closure elides `malli.error` with it — the `:require` alone
  retains nothing. `scripts/check-schemas-bundle.cjs` asserts that
  absence on the matched probe build. An application that wants
  humanized messages in its own production UI requires `malli.error`
  itself and pays for it once, on purpose.

  See the `re-frame.schemas` namespace docstring for the bundle boundary."
  (:require [malli.core]
            [malli.error]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]))

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

(rf.late-bind/set-fn! :schemas/malli-validate  malli.core/validate)
(rf.late-bind/set-fn! :schemas/malli-explain   malli.core/explain)

;; Development only. `re-frame.schemas.validate` reads
;; `:schemas/humanize-explain!` solely inside its
;; `interop/debug-enabled?`-gated emit bodies, so a production build has
;; no reader for this hook, and publishing it unconditionally rooted
;; `malli.error` for nothing. Same gate here: `:advanced` +
;; `goog.DEBUG=false` folds the form away and `malli.error` leaves the
;; bundle. Consumers use the same hook regardless of validator adapter.
(when rf.interop/debug-enabled?
  (rf.late-bind/set-fn! :schemas/humanize-explain! malli.error/humanize))
