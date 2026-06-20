(ns re-frame.schemas.malli
  "Malli adapter for the schemas artefact (rf2-t0hq).

  Per Spec 010 §Default validator the CLJS reference's default validator
  delegates to Malli (`malli.core/validate` / `malli.core/explain`).
  Historically `re-frame.schemas.validator/default-malli-validate` reached
  Malli via runtime `resolve` on CLJS:

      :cljs
      (try
        (if-let [v (resolve 'malli.core/validate)]
          (v schema value)
          true)
        (catch :default _ true))

  CLJS has no runtime `resolve` (the symbol is a compile-time analyzer
  affordance only); the `if-let`'s `nil` branch always fired and the
  default validator returned `true` for every value. Malli was never
  consulted on CLJS — `(rf/reg-app-schema [:user :age] {:schema :int})` then a
  commit of `\"twenty-three\"` produced no
  `:rf.error/schema-validation-failure` trace.

  The fix follows the rf2-froe / rf2-p7va substitute-validator
  precedent: this namespace exists only to publish Malli's `validate`
  and `explain` fns into the framework's late-bind hook table.
  `re-frame.schemas.validator/default-malli-validate` and
  `re-frame.schemas.validator/default-malli-explain` consult the table
  at call time and delegate to whatever's published; absent any
  publisher they soft-pass per the Spec 010 §Recommended soft-pass rule.

  Post-rf2-v96fh (schema implies validation) apps do NOT require this
  namespace explicitly: the `re-frame.schemas` facade `:require`s it for
  its ns-load side effect, so loading the schemas artefact publishes the
  Malli hooks automatically and the default validator is LIVE the moment
  a schema is registered — same on both CLJS and the JVM (rf2-qyfie
  removed the JVM `requiring-resolve` fallback so the contract is
  symmetrical):

      (ns my-app.core
        (:require [re-frame.core :as rf]
                  [re-frame.schemas])) ;; transitively loads this adapter

  (A standalone `(:require [re-frame.schemas.malli])` still works and is
  harmless — the hook publication is idempotent — but is no longer
  required. Pre-v96fh it WAS the opt-in: the adapter had to be required
  separately or schemas soft-passed silently.)

  Because the facade pulls Malli in, the only Malli-FREE posture is to
  not require `re-frame.schemas` at all (per rf2-qnxf bundle audit — the
  no-feature counter reference app). An app that DOES use schemas but
  wants to drop Malli's validation *behaviour* (it still pays the bundle
  cost under static CLJS compilation, since requiring the facade loads
  this ns's body) either:

    - Calls `(schemas/set-schema-validator! my-validator-fn)` at boot to
      install a custom validator, or
    - Calls `(schemas/set-schema-validator! nil)` for a hard no-op.

  See the `re-frame.schemas` ns docstring (rf2-v96fh) for the full
  bundle-isolation tradeoff."
  (:require [malli.core]
            [malli.error]
            [re-frame.late-bind :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- publish Malli into the late-bind hook table -------------------------
;;
;; Per rf2-t0hq the two hooks `:schemas/malli-validate` and
;; `:schemas/malli-explain` are read by
;; `re-frame.schemas.validator/default-malli-validate` /
;; `default-malli-explain` on every validate call. Both
;; sides of the seam are in this artefact so the contract is local;
;; the seam is published as a hook key so future ports
;; (`re-frame.schemas.zod` / `.pydantic` / ...) can register their own
;; default validator pair without a code change to the schemas
;; namespace.

(late-bind/set-fn! :schemas/malli-validate  malli.core/validate)
(late-bind/set-fn! :schemas/malli-explain   malli.core/explain)

;; rf2-2ek7t — also publish the canonical :schemas/humanize-explain!
;; hook so consumers (Xray's violation block, future tools) read
;; through ONE seam regardless of which validator's adapter is
;; loaded. When malli is the registered validator + this adapter ns
;; is loaded, the canonical hook routes to malli.error/humanize.
;; Non-Malli adapters (e.g. .zod / .pydantic) register their own
;; humanizer under the same canonical hook key.
(late-bind/set-fn! :schemas/humanize-explain! malli.error/humanize)
