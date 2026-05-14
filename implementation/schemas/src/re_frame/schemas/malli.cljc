(ns re-frame.schemas.malli
  "Malli adapter for the schemas artefact (rf2-t0hq).

  Per Spec 010 §Default validator the CLJS reference's default validator
  delegates to Malli (`malli.core/validate` / `malli.core/explain`).
  Historically `re-frame.schemas/default-malli-validate` reached Malli
  via runtime `resolve` on CLJS:

      :cljs
      (try
        (if-let [v (resolve 'malli.core/validate)]
          (v schema value)
          true)
        (catch :default _ true))

  CLJS has no runtime `resolve` (the symbol is a compile-time analyzer
  affordance only); the `if-let`'s `nil` branch always fired and the
  default validator returned `true` for every value. Malli was never
  consulted on CLJS — `(rf/reg-app-schema [:user :age] :int)` then a
  commit of `\"twenty-three\"` produced no
  `:rf.error/schema-validation-failure` trace.

  The fix follows the rf2-froe / rf2-p7va substitute-validator
  precedent: this namespace exists only to publish Malli's `validate`
  and `explain` fns into the framework's late-bind hook table.
  `re-frame.schemas/default-malli-validate` and
  `default-malli-explain` consult the table at call time and delegate
  to whatever's published; absent any publisher they soft-pass per the
  Spec 010 §Recommended soft-pass rule.

  Users opt in by requiring this namespace at app boot — same pattern
  on both CLJS and the JVM (rf2-qyfie removed the JVM `requiring-resolve`
  fallback so the contract is symmetrical):

      (ns my-app.core
        (:require [re-frame.core :as rf]
                  [re-frame.schemas]         ;; load the schemas artefact
                  [re-frame.schemas.malli])) ;; publish Malli into the hook table

  Apps that want to drop the Malli surface entirely (per rf2-qnxf
  bundle audit) do NOT require this namespace and either:

    - Leave the default in place (soft-pass; no failure traces fire).
    - Call `(rf/set-schema-validator! my-validator-fn)` at boot to
      install a custom validator.
    - Call `(rf/set-schema-validator! nil)` for a hard no-op."
  (:require [malli.core]
            [re-frame.late-bind :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- publish Malli into the late-bind hook table -------------------------
;;
;; Per rf2-t0hq the two hooks `:schemas/malli-validate` and
;; `:schemas/malli-explain` are read by `re-frame.schemas/default-malli-
;; validate` / `default-malli-explain` on every validate call. Both
;; sides of the seam are in this artefact so the contract is local;
;; the seam is published as a hook key so future ports
;; (`re-frame.schemas.zod` / `.pydantic` / ...) can register their own
;; default validator pair without a code change to the schemas
;; namespace.

(late-bind/set-fn! :schemas/malli-validate malli.core/validate)
(late-bind/set-fn! :schemas/malli-explain  malli.core/explain)
