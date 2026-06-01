(ns re-frame.schemas.validator
  "Pluggable validator / explainer / printer fns (rf2-froe + rf2-wla45).

  Per Spec 010 §Non-Malli validators — the validator-fn extension point.
  The framework never inspects the value stored in `:schema` directly;
  every validation site routes through the registered validator fn.
  This is the seam Malli plugs into by default; apps that want to drop
  the ~24 KB gzipped Malli surface (rf2-qnxf) call
  `(rf/set-schema-validator! some-other-fn)` (or `nil` for no-op) at
  boot before any reg-app-schema / :schema metadata lands.

  Three fns are registered separately so the validate hot path stays
  cheap (validate returns truthy/falsey; explain is only invoked on
  the failure branch to populate the trace's `:explain` key; print is
  only invoked when the digest pipeline serialises a schema):

    :validate (fn [schema value] truthy?)
              — same shape as Malli's `validate`. nil disables; the
              call site treats nil as 'pass everything'.

    :explain  (fn [schema value] explanation)
              — same shape as Malli's `explain`. nil = no explanation
              attached to the failure trace.

    :print    (fn [schema-value] canonical-string)
              — per Spec 010 §Schema digest line 491, the registered
              validator's `schema-print` companion. Serialises a
              schema value to the byte-stable UTF-8 string the digest
              pipeline hashes. The default mirrors the Malli-EDN
              canonicalisation (sort-by-pr-str map keys, metadata
              stripped, `pr-str`); ports that ship a non-EDN schema
              language (a Zod / clojure.spec port) install their own
              printer here so digests match the validator's own
              serialisation contract. nil falls back to the default
              EDN canonicaliser, which is the cross-runtime contract
              every Malli-EDN-compatible port shares.

  Each fn has a dedicated single-purpose setter — `set-schema-validator!`
  / `set-schema-explainer!` / `set-schema-printer!`. `set-schema-fns!`
  installs any subset of the three as one atomic bundle for callers
  (a port's boot, a substitute-Malli swap) who want all three to land
  together. The bundle setter is named for what it does — it does NOT
  pretend to set only the validator (rf2-13meg).

  Per rf2-t0hq the CLJS default validator used to reach Malli through
  runtime `resolve` — but CLJS has no runtime resolve (the symbol is
  a compile-time analyzer affordance only). The fix is the rf2-froe /
  rf2-p7va substitute-validator pattern: the `re-frame.schemas.malli`
  adapter namespace publishes Malli's `validate` and `explain` into the
  late-bind hook table on ns-load. The default fns below consult the
  table on every call.

  Per rf2-v96fh (schema implies validation) the `re-frame.schemas`
  facade now `:require`s `re-frame.schemas.malli` itself, so loading the
  schemas artefact wires the Malli hooks automatically — the default
  validator is LIVE the moment a schema is registered. The soft-pass
  branch below (return `true` when `:schemas/malli-validate` is unbound)
  is therefore the defensive fallback for two residual cases, NOT the
  default state: (1) a non-Malli port / app that installed its own
  validator via `set-schema-fns!` and never bound the Malli hook, and
  (2) test harnesses that deliberately unbind the hook to exercise the
  absent path. Per Spec 010 §Recommended soft-pass an unbound default
  validator passes rather than failing, so a substitute-validator
  app is never blocked by Malli's absence."
  (:require [re-frame.late-bind :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

(defn- default-malli-validate
  "The default validator — delegates to `malli.core/validate` via the
  late-bind hook `:schemas/malli-validate` published by
  `re-frame.schemas.malli` (rf2-t0hq). Soft-passes (returns true) when
  the adapter ns is not loaded, per Spec 010 §Recommended soft-pass.

  Apps that want Malli-absent behaviour to be a hard fail register
  a stricter validator via `set-schema-validator!`."
  [schema value]
  (if-let [v (late-bind/get-fn :schemas/malli-validate)]
    (v schema value)
    true))

(defn- default-malli-explain
  "The default explainer — delegates to `malli.core/explain` via the
  late-bind hook `:schemas/malli-explain` published by
  `re-frame.schemas.malli` (rf2-t0hq). Returns nil when the adapter
  ns is not loaded — the failure trace then omits the `:explain` key."
  [schema value]
  (when-let [e (late-bind/get-fn :schemas/malli-explain)]
    (e schema value)))

(defn- compare-by-pr-str
  "Spec 010 §Digest algorithm step 1 ordering — total order on EDN
  values via `(compare (pr-str a) (pr-str b))`. Stable across runtimes
  because `pr-str` over EDN is a syntactic projection."
  [a b]
  (compare (pr-str a) (pr-str b)))

(defn- canonicalise-schema-form
  "Per Spec 010 §Digest algorithm step 1 — normalise a schema EDN form for
  stable byte serialisation: metadata is stripped, and map keys are
  emitted in `(compare (pr-str a) (pr-str b))` order via sorted-maps /
  sorted-sets so insertion order does not bleed into the printed bytes.
  Sequences and vectors recurse element-wise. Non-collection values
  pass through unchanged."
  [form]
  (cond
    (map? form)    (into (sorted-map-by compare-by-pr-str)
                         (map (fn [[k v]]
                                [(canonicalise-schema-form k)
                                 (canonicalise-schema-form v)]))
                         form)
    (vector? form) (mapv canonicalise-schema-form form)
    (set? form)    (into (sorted-set-by compare-by-pr-str)
                         (map canonicalise-schema-form)
                         form)
    (seq? form)    (doall (map canonicalise-schema-form form))
    :else          form))

(defn- compute-edn-print
  "The pure serialisation step behind `default-edn-print` — `pr-str`
  over a canonicalised EDN form with the digest pipeline's print-flag
  bindings. Extracted so the memo wrapper can be swapped for an
  atom-backed cache (rf2-17sqc) without disturbing the serialisation
  contract. Same `schema-value` always returns byte-identical bytes."
  [schema-value]
  (binding [*print-meta*           false
            *print-readably*       true
            *print-dup*            false
            *print-namespace-maps* false]
    (pr-str (canonicalise-schema-form schema-value))))

;; Atom-backed memo cache for `default-edn-print` (rf2-17sqc). Replaces
;; the opaque `(memoize compute-edn-print)` so the cache is *clearable*
;; for test isolation — `clojure.core/memoize` exposes no clear hook, so
;; a test that registers thousands of distinct fresh schemas (the
;; concurrency stress test) could never reset the process-lifetime cache.
;; The cache key is the schema value (immutable); behaviour for callers
;; is byte-identical to the prior `memoize` form.
;;
;; ## Boot-once invariant (Mike ruled rf2-17sqc, option B)
;;
;; App schemas are registered ONCE at boot. The printer memo is
;; therefore bounded by the registered-schema cardinality, and the
;; cache is process-lifetime and intentionally NOT evicted — no bounded
;; LRU. The only scenario that violates boot-once is a test that
;; deliberately registers many distinct fresh schemas; such tests call
;; `clear-edn-print-cache!` in fixture teardown to keep the cache from
;; growing across the suite. See [010 §Schema digest].
(def ^:private edn-print-cache (atom {}))

(defn clear-edn-print-cache!
  "Reset the `default-edn-print` memo cache (rf2-17sqc). Test-support
  hook: the printer memo is process-lifetime and bounded by the
  registered-schema cardinality in real apps (schemas register once at
  boot), so production never needs this — but a test that registers
  many distinct fresh schemas (`schemas_concurrency_stress_test`) calls
  it in fixture teardown so the cache doesn't grow unbounded across the
  suite. Returns nil."
  []
  (reset! edn-print-cache {})
  nil)

(defn default-edn-print
  "The default schema-print companion (rf2-wla45). Per Spec 010 §Schema
  digest line 491 — serialise a schema value to the stable UTF-8
  byte-source string the digest pipeline hashes. The default uses
  `pr-str` over a canonicalised EDN form: map-keys emitted in
  `compare-by-pr-str` order, metadata stripped, namespaced-map
  printing disabled. This is the cross-runtime contract every
  Malli-EDN-compatible port shares.

  Ports that ship a non-EDN schema language register their own printer
  via `set-schema-printer!`; the digest then reflects the registered
  validator's serialisation contract rather than the framework's
  Malli-EDN default.

  Memoised by `schema-value` (rf2-y29nf): the digest pipeline
  (`re-frame.schemas.digest/compute-digest`) calls this once per
  registered schema per digest call, and the digest is invoked on the
  SSR hydrate handshake, the epoch-restore schema-mismatch trace, and
  pair-tool drift detection — repeat calls against the same registered
  schema values dominate. Pure over immutable schema values, so the
  cache is bounded by the registered-schema cardinality.

  The memo is **clearable** for test isolation via
  `clear-edn-print-cache!` (rf2-17sqc): schemas register once at boot,
  so the cache is process-lifetime and intentionally not evicted in
  production — but tests that register many distinct fresh schemas reset
  it in fixture teardown. Behaviour for callers is byte-identical to the
  prior `clojure.core/memoize` form."
  [schema-value]
  (if-let [e (find @edn-print-cache schema-value)]
    (val e)
    (let [v (compute-edn-print schema-value)]
      (swap! edn-print-cache assoc schema-value v)
      v)))

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

(defonce
  ^{:doc "The currently-registered schema-print fn — `(fn [schema-value]
          canonical-string)`. Per Spec 010 §Schema digest line 491,
          the registered validator's `schema-print` companion. The
          digest pipeline (`re-frame.schemas.digest`) hashes the
          UTF-8 bytes of this fn's return value. Default is
          `default-edn-print` (Malli-EDN canonicalisation); ports
          that ship a non-EDN schema language swap in their own
          serialiser via `set-schema-printer!`. nil falls back to
          the default — the digest is never undefined for a present
          schema set."}
  printer-fn
  (atom default-edn-print))

(defn set-schema-validator!
  "Register the validator fn that every dev-time validation site routes
  through. Per Spec 010 §Non-Malli validators the seam is
  the substitute-Malli extension point — apps that want to drop Malli
  (the ~24 KB gzipped surface measured in the rf2-qnxf bundle audit)
  swap in their own validator at boot, before the first `reg-app-schema`
  or `:schema`-bearing `reg-*` lands.

    (set-schema-validator! validate-fn)
      validate-fn :: (fn [schema value] truthy?)
                   | nil   ;; disables validation entirely
      Same signature as `malli.core/validate` — truthy on conform,
      falsey on fail.

  This setter swaps ONLY the validator. The explainer and printer are
  left untouched — apps that also want to swap those call
  `set-schema-explainer!` / `set-schema-printer!`, or use
  `set-schema-fns!` to install all three as one atomic bundle
  (rf2-13meg).

  Per Spec 010 §Non-Malli validators the validator-fn must be pure
  (same `(schema, value)` returns the same result) and must be
  production-elidable alongside `re-frame.interop/debug-enabled?` —
  every call site is already gated on `debug-enabled?`, so the
  validator's body is unreachable in `:advanced` + `goog.DEBUG=false`
  builds.

  Last-write-wins on re-registration. Returns the validator that was
  installed (may be nil)."
  [validate-fn]
  (reset! validator-fn validate-fn)
  @validator-fn)

(defn set-schema-fns!
  "Atomically install any subset of the validator / explainer / printer
  bundle from a single map (rf2-13meg). The honest bundle setter — its
  name says it sets all three schema-language fns, not just the
  validator.

    (set-schema-fns! {:validate validate-fn
                      :explain  explain-fn
                      :print    print-fn})

  Each key is optional; a key that is absent leaves the existing
  registration in place. Per Spec 010 §Non-Malli validators this is
  the one-call substitute-Malli boot pattern — a Zod / clojure.spec
  port installs its validator, explainer, and digest-printer together
  so the three never drift out of sync mid-boot.

    :validate  (fn [schema value] truthy?) | nil  — nil disables
               validation (every site soft-passes).
    :explain   (fn [schema value] explanation) | nil — nil omits the
               failure trace's `:explain` key.
    :print     (fn [schema-value] canonical-string) | nil — per
               rf2-wla45 the schema-print companion the digest pipeline
               hashes (Spec 010 §Schema digest line 491). nil coerces
               to the default EDN canonicaliser (the digest is never
               undefined for a present schema set) — identical to
               `set-schema-printer!`'s nil fallback, so `printer-fn` is
               never nil after any write (rf2-ee38b.6).

  Last-write-wins per key. Returns the validator that was installed
  (may be nil)."
  [{:keys [validate explain print] :as m}]
  (when (contains? m :validate) (reset! validator-fn validate))
  (when (contains? m :explain)  (reset! explainer-fn explain))
  ;; Per rf2-ee38b.6 (clarity P2): coerce `nil` to the default
  ;; identically to the dedicated `set-schema-printer!` setter, so
  ;; "printer-fn is never nil" is a true invariant established at the
  ;; write site — not re-asserted defensively in `run-printer`.
  (when (contains? m :print)
    (reset! printer-fn (or print default-edn-print)))
  @validator-fn)

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

(defn set-schema-printer!
  "Register the schema-print companion — `(fn [schema-value]
  canonical-string)` — the digest pipeline hashes per Spec 010
  §Schema digest line 491 (rf2-wla45). Parallel to
  `set-schema-validator!` / `set-schema-explainer!`: the validator
  surface is fully pluggable, and the digest contract is too —
  non-Malli ports register their own serialiser so the digest
  reflects the port's own validation contract rather than the
  framework's Malli-EDN default.

  The fn MUST be:
    - Pure — same `schema-value` returns the same byte sequence.
    - Deterministic across runtimes — a CLJS server and a CLJS
      client running the same schema set MUST produce the same
      bytes (Spec 010 §Digest algorithm cross-runtime guarantee).
    - Defined for every schema value the registered validator
      accepts.

  Setting `nil` falls back to the default EDN canonicaliser
  (`default-edn-print`) so the digest is never undefined for a
  present schema set.

  Last-write-wins. Returns the printer that was installed (the
  default fn when nil was passed)."
  [print-fn]
  (reset! printer-fn (or print-fn default-edn-print))
  @printer-fn)

(defn reset-schema-validator!
  "Reset the validator, explainer, and printer atoms back to the
  framework defaults. Test-support helper — restores the defaults
  after a test that called `set-schema-validator!` / `set-schema-
  explainer!` / `set-schema-printer!`."
  []
  (reset! validator-fn default-malli-validate)
  (reset! explainer-fn default-malli-explain)
  (reset! printer-fn   default-edn-print))

(defn using-default-validator?
  "True when `validator-fn` is still the framework-default
  `default-malli-validate`. Used by the
  `:rf.warning/schema-validator-unavailable` registration-time check
  (rf2-fq7d2) to distinguish 'app hasn't opted out of Malli' from
  'app installed its own validator and knows what it's doing'."
  []
  (identical? @validator-fn default-malli-validate))

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

(defn run-printer
  "Hot-path entry — invoke the registered schema-print companion against
  a single schema value. Per Spec 010 §Schema digest line 491 the digest
  pipeline (`re-frame.schemas.digest`) hashes this fn's UTF-8 bytes
  (rf2-wla45). `printer-fn` is never nil: both write sites
  (`set-schema-printer!` and `set-schema-fns!`'s `:print` key) coerce
  a nil `:print` to `default-edn-print` (rf2-ee38b.6), so the
  cross-runtime digest contract holds without a read-site guard."
  [schema-value]
  (@printer-fn schema-value))
