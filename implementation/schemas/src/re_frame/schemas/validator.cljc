(ns re-frame.schemas.validator
  "Pluggable validator / explainer fns (rf2-froe).

  Per Spec 010 §Non-Malli validators — the validator-fn extension point.
  The framework never inspects the value stored in `:spec` directly;
  every validation site routes through the registered validator fn.
  This is the seam Malli plugs into by default; apps that want to drop
  the ~24 KB gzipped Malli surface (rf2-qnxf) call
  `(rf/set-schema-validator! some-other-fn)` (or `nil` for no-op) at
  boot before any reg-app-schema / :spec metadata lands.

  Two fns are registered separately so the validate hot path stays
  cheap (validate returns truthy/falsey; explain is only invoked on
  the failure branch to populate the trace's `:explain` key):

    :validate (fn [schema value] truthy?)
              — same shape as Malli's `validate`. nil disables; the
              call site treats nil as 'pass everything'.

    :explain  (fn [schema value] explanation)
              — same shape as Malli's `explain`. nil = no explanation
              attached to the failure trace.

  A combined `(set-schema-validator! {:validate ... :explain ...})`
  arity exists for callers who want to install both atomically.

  Per rf2-t0hq the CLJS default validator used to reach Malli through
  runtime `resolve` — but CLJS has no runtime resolve (the symbol is
  a compile-time analyzer affordance only). The fix is the rf2-froe /
  rf2-p7va substitute-validator pattern: the `re-frame.schemas.malli`
  adapter namespace publishes Malli's `validate` and `explain` into the
  late-bind hook table on ns-load. The default fns below consult the
  table on every call. Apps opt in to Malli validation on CLJS by
  requiring `re-frame.schemas.malli` at app boot.

  On the JVM `requiring-resolve` still works as a no-require fallback
  so existing JVM tests / apps keep working without an explicit
  require of the adapter ns; the late-bind hook takes precedence when
  the adapter ns IS loaded."
  (:require [re-frame.late-bind :as late-bind]))

(defn- default-malli-validate
  "The default validator — delegates to malli.core/validate via the
  late-bind hook published by `re-frame.schemas.malli` (rf2-t0hq).

  Lookup order:
    1. Late-bind hook `:schemas/malli-validate` (published by
       `re-frame.schemas.malli` when that namespace is loaded).
    2. On the JVM only, fall back to `(requiring-resolve
       'malli.core/validate)` so the JVM artefact tests / apps that
       have Malli on the classpath but don't `:require
       [re-frame.schemas.malli]` keep working.
    3. Soft-pass — return true (per Spec 010 §Recommended soft-pass).

  Apps that want Malli-absent behaviour to be a hard fail register
  a stricter validator via `set-schema-validator!`."
  [schema value]
  (if-let [v (late-bind/get-fn :schemas/malli-validate)]
    (v schema value)
    #?(:clj  (try
               (if-let [v (requiring-resolve 'malli.core/validate)]
                 (v schema value)
                 true)
               (catch Throwable _ true))
       :cljs true)))

(defn- default-malli-explain
  "The default explainer — delegates to malli.core/explain via the
  late-bind hook published by `re-frame.schemas.malli` (rf2-t0hq).
  Same lookup order as `default-malli-validate`. Returns the Malli
  explanation map on fail; nil on conform / when Malli is not on the
  classpath / when the adapter ns is not loaded."
  [schema value]
  (if-let [e (late-bind/get-fn :schemas/malli-explain)]
    (e schema value)
    #?(:clj  (try
               (when-let [e (requiring-resolve 'malli.core/explain)]
                 (e schema value))
               (catch Throwable _ nil))
       :cljs nil)))

(defonce
  ^{:doc "The currently-registered validator fn — `(fn [schema value]
          truthy?)`. Default delegates to Malli; apps swap via
          `set-schema-validator!`. Setting the atom to `nil` disables
          validation everywhere (every `validate-*!` call returns
          true without inspecting the schema)."}
  validator-fn
  (atom default-malli-validate))

(defonce
  ^{:doc "The currently-registered explainer fn — `(fn [schema value]
          explanation)`. Populates the failure trace's `:explain`
          key. Default delegates to Malli's `explain`; nil means no
          explanation is attached."}
  explainer-fn
  (atom default-malli-explain))

(defn set-schema-validator!
  "Register the validator fn that every dev-time validation site routes
  through. Per Spec 010 §Non-Malli validators (rf2-froe) the seam is
  the substitute-Malli extension point — apps that want to drop Malli
  (the ~24 KB gzipped surface measured in the rf2-qnxf bundle audit)
  swap in their own validator at boot, before the first `reg-app-schema`
  or `:spec`-bearing `reg-*` lands.

  Argument shapes:

    (set-schema-validator! validate-fn)
      validate-fn :: (fn [schema value] truthy?)
                   | nil   ;; disables validation entirely
      Same signature as `malli.core/validate` — truthy on conform,
      falsey on fail. The explainer is left untouched (apps that want
      to also swap explanations call `set-schema-explainer!`).

    (set-schema-validator! {:validate validate-fn :explain explain-fn})
      Atomic swap of both fns at once. Either key may be `nil` to
      disable the corresponding hot path. Keys not supplied leave the
      existing registration in place.

  Per Spec 010 §Non-Malli validators the validator-fn must be pure
  (same `(schema, value)` returns the same result) and must be
  production-elidable alongside `re-frame.interop/debug-enabled?` —
  every call site is already gated on `debug-enabled?`, so the
  validator's body is unreachable in `:advanced` + `goog.DEBUG=false`
  builds.

  Last-write-wins on re-registration. Returns the validator that was
  installed (may be nil)."
  [validate-fn-or-map]
  (cond
    (map? validate-fn-or-map)
    (let [{:keys [validate explain]
           :or   {validate ::unset
                  explain  ::unset}} validate-fn-or-map]
      (when-not (= ::unset validate) (reset! validator-fn validate))
      (when-not (= ::unset explain)  (reset! explainer-fn  explain))
      @validator-fn)

    :else
    (do (reset! validator-fn validate-fn-or-map)
        @validator-fn)))

(defn set-schema-explainer!
  "Register the explainer fn — `(fn [schema value] explanation)` — that
  every failure-trace site calls to enrich the trace's `:explain` key.
  See `set-schema-validator!`. Setting `nil` disables explanations
  (the failure trace simply omits the `:explain` data).

  Last-write-wins. Returns the explainer that was installed (may be
  nil)."
  [explain-fn]
  (reset! explainer-fn explain-fn)
  @explainer-fn)

(defn reset-schema-validator!
  "Reset the validator and explainer atoms back to the default Malli-
  delegating fns. Test-support helper — restores the framework default
  after a test that called `set-schema-validator!` / `set-schema-
  explainer!`."
  []
  (reset! validator-fn default-malli-validate)
  (reset! explainer-fn default-malli-explain))

(defn run-validator
  "Hot-path entry — invoke the registered validator fn against a
  `(schema, value)` pair. Returns true (pass) when no validator is
  registered (nil) so the call sites treat 'no validator' as 'no
  validation' rather than 'every value fails'."
  [schema value]
  (if-let [f @validator-fn]
    (f schema value)
    true))

(defn run-explainer
  "Hot-path entry — invoke the registered explainer fn against a
  `(schema, value)` pair. Returns nil when no explainer is registered
  (nil); call sites then omit the `:explain` key from the failure
  trace."
  [schema value]
  (when-let [f @explainer-fn]
    (f schema value)))
