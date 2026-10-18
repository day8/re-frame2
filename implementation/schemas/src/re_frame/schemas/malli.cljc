(ns re-frame.schemas.malli
  "Malli adapter for the schemas artefact: the framework's default validator
  bundle.

  Per Spec 010 §Default validator the CLJS reference's default validator is
  Malli. [[bundle]] is that default as a value — `:validate`, `:explain` and,
  in development builds, `:humanize` — and `re-frame.schemas.validator` seeds
  the installed port from it, so a registered schema validates against Malli
  with nothing further to wire.

  Applications do not require this namespace explicitly: the
  `re-frame.schemas` facade loads it, on both CLJS and the JVM:

      (ns my-app.core
        (:require [re-frame.core :as rf]
                  [re-frame.schemas])) ;; transitively loads this adapter

  Because the facade pulls Malli in, the only Malli-FREE posture is to
  not require `re-frame.schemas` at all. An app that uses schemas but
  wants to drop Malli's validation *behaviour* (it still pays the bundle
  cost under static CLJS compilation, since requiring the facade loads
  this ns's body) either:

    - Calls `(schemas/set-schema-fns! {:validate my-validator-fn})` at
      boot to install a custom validator, or
    - Calls `(schemas/set-schema-fns! {:validate nil})` for a hard no-op.

  `:validate` compiles each schema once. `malli.core/validate` builds a
  fresh validator on every call, which costs more than the check itself, so
  the bundle keeps each compiled validator in a cache keyed WEAKLY on the
  schema value: an entry lives exactly as long as something else holds its
  schema — in practice the registration that declared it — and installing a
  different `:validate` leaves the cache to the collector with the bundle
  that owns it. Nothing needs configuring or clearing.

  `malli.error` is the one Malli namespace this adapter keeps off the
  production path. The framework's only reader of the humanizer is the
  `:explain-humanized` enrichment of `:rf.error/schema-validation-failure`
  traces, and those emit bodies already sit behind
  `interop/debug-enabled?`, so `:humanize` carries the humanizer under the
  same gate. Under `:advanced` + `goog.DEBUG=false` the reference folds
  away and Closure elides `malli.error` with it — the `:require` alone
  retains nothing. `scripts/check-schemas-bundle.cjs` asserts that
  absence on the matched probe build. An application that wants
  humanized messages in its own production UI requires `malli.error`
  itself and pays for it once, on purpose.

  `malli.transform` is required on CLJS for managed HTTP's schema
  `:decode`, which coerces the parsed JSON with
  `malli.transform/json-transformer` (Spec 014 §Schema-driven). The CLJS
  decoder looks that function up with `resolve`, which loads nothing, so the
  namespace must already be in the build; requiring it here puts it there
  for every app that uses schemas. Nothing in this namespace calls it, so a
  build without the HTTP decoder keeps none of it: Closure drops a
  namespace nothing reaches. The JVM decoder loads it on demand with
  `requiring-resolve`.

  See the `re-frame.schemas` namespace docstring for the bundle boundary."
  (:require [malli.core]
            [malli.error]
            #?(:cljs [malli.transform])
            [re-frame.interop :as rf.interop]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the compiled-validator cache ------------------------------------------

(defn- weak-cache
  "A map from schema value to compiled validator that holds its keys weakly.
  The JVM map compares keys by value and is synchronised for the concurrent
  validation a server runs; a CLJS `WeakMap` compares by identity."
  []
  #?(:clj  (java.util.Collections/synchronizedMap (java.util.WeakHashMap.))
     :cljs (js/WeakMap.)))

(defn- weak-key?
  "True when `schema` can key the cache. A `WeakMap` refuses primitives, so on
  CLJS a string, number or boolean schema compiles uncached."
  [schema]
  #?(:clj  true
     :cljs (let [t (goog/typeOf schema)]
             (or (= "object" t) (= "function" t)))))

(defn- cached-validator
  "The compiled Malli validator for `schema`, from `cache` or compiled into it.
  A malformed schema throws here, before anything is cached."
  [cache schema]
  (if-not (weak-key? schema)
    (malli.core/validator schema)
    (or #?(:clj  (.get ^java.util.Map cache schema)
           :cljs (.get cache schema))
        (let [v (malli.core/validator schema)]
          #?(:clj  (.put ^java.util.Map cache schema v)
             :cljs (.set cache schema v))
          v))))

(defn- bundle-over
  "The Malli bundle whose `:validate` compiles through `cache`."
  [cache]
  {:validate (fn validate [schema value]
               ((cached-validator cache schema) value))
   :explain  malli.core/explain
   :humanize (when rf.interop/debug-enabled? malli.error/humanize)})

(def bundle
  "The Malli validator bundle — the framework's default `:validate`,
  `:explain` and `:humanize`, which `re-frame.schemas.validator` completes
  with its EDN schema printer as `default-schema-fns`.

    {:validate (fn [schema value] truthy?)   ;; cached compiled validators
     :explain  malli.core/explain
     :humanize malli.error/humanize}         ;; development builds; nil in production"
  (bundle-over (weak-cache)))
