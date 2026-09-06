(ns re-frame.schemas.validator
  "Pluggable validator, explainer, and schema-printer functions.

  Per Spec 010 §Non-Malli validators — the validator-fn extension point.
  The framework never inspects the value stored in `:schema` directly;
  every validation site routes through the registered validator fn.
  This is the seam Malli plugs into by default. Applications may install a
  different validator bundle, or nil for no validation.

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

  Dedicated setters change one function. `set-schema-fns!` installs any
  subset as a bundle so a schema-language port can keep validation,
  explanations, and digest serialization aligned.

  The Malli adapter publishes its functions through late binding. The façade
  loads that adapter automatically; the absent-hook soft-pass remains a
  defensive contract for substitute ports and isolated tests."
  (:require [re-frame.late-bind :as rf.late-bind]
            [re-frame.schemas.cache :as rf.schemas.cache]))

#?(:clj (set! *warn-on-reflection* true))

(defn- default-malli-validate
  "The default validator — delegates to `malli.core/validate` via the
  late-bind hook `:schemas/malli-validate` published by
  `re-frame.schemas.malli`. Soft-passes (returns true) when
  the adapter ns is not loaded, per Spec 010 §Recommended soft-pass.

  Apps that want Malli-absent behaviour to be a hard fail register
  a stricter validator via `set-schema-validator!`."
  [schema value]
  (if-let [v (rf.late-bind/get-fn :schemas/malli-validate)]
    (v schema value)
    true))

(defn- default-malli-explain
  "The default explainer — delegates to `malli.core/explain` via the
  late-bind hook `:schemas/malli-explain` published by
  `re-frame.schemas.malli`. Returns nil when the adapter
  ns is not loaded — the failure trace then omits the `:explain` key."
  [schema value]
  (when-let [e (rf.late-bind/get-fn :schemas/malli-explain)]
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
  bindings. Same `schema-value` always returns byte-identical bytes."
  [schema-value]
  (binding [*print-meta*           false
            *print-readably*       true
            *print-dup*            false
            *print-namespace-maps* false]
    (pr-str (canonicalise-schema-form schema-value))))

;; The process-lifetime print cache is bounded by boot-time schema
;; cardinality and can be cleared by test fixtures that generate schemas.
(let [[memo clear!] (rf.schemas.cache/clearable-memo compute-edn-print)]

  (def
    ^{:doc "The default schema-print companion. Per Spec 010
            §Schema digest line 491 — serialise a schema value to the
            stable UTF-8 byte-source string the digest pipeline hashes.
            The default uses `pr-str` over a canonicalised EDN form:
            map-keys emitted in `compare-by-pr-str` order, metadata
            stripped, namespaced-map printing disabled. This is the
            cross-runtime contract every Malli-EDN-compatible port shares.

            Ports that ship a non-EDN schema language register their own
            printer via `set-schema-printer!`; the digest then reflects
            the registered validator's serialisation contract rather than
            the framework's Malli-EDN default.

            Memoised by immutable schema value and clearable for test
            isolation via `clear-edn-print-cache!`."
      :arglists '([schema-value])}
    default-edn-print memo)

  (def
    ^{:doc "Reset the `default-edn-print` memo cache. Intended for test
            fixtures that generate fresh schemas. Returns nil."
      :arglists '([])}
    clear-edn-print-cache! clear!))

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
  through. Install substitute validators at boot, before schema-bearing
  registrations land.

    (set-schema-validator! validate-fn)
      validate-fn :: (fn [schema value] truthy?)
                   | nil   ;; disables validation entirely
      Same signature as `malli.core/validate` — truthy on conform,
      falsey on fail.

  This setter swaps ONLY the validator. The explainer and printer are
  left untouched — apps that also want to swap those call
  `set-schema-explainer!` / `set-schema-printer!`, or use
  `set-schema-fns!` to install all three as one bundle.

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
  "Install any subset of the validator / explainer / printer bundle from
  a single map.

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
    :print     (fn [schema-value] canonical-string) | nil — the schema-print
               companion the digest pipeline hashes. nil coerces
               to the default EDN canonicaliser (the digest is never
               undefined for a present schema set) — identical to
               `set-schema-printer!`'s nil fallback, so `printer-fn` is
               never nil after any write.

  Last-write-wins per key. Returns the installed bundle as a map —
  `{:validate @validator-fn :explain @explainer-fn :print @printer-fn}`
  — reflecting the live state of all three functions after this call.
  A bundle setter returns its bundle: the return mirrors `set-schema-fns!`'s
  own input shape, so a caller can atomically observe everything that is now
  installed — including keys it did NOT touch and the nil-printer coercion of
  `:print`. The single-purpose setters keep their own single-value returns
  (the fn each one installs); only this bundle setter returns the bundle map.
  `:validate` / `:explain` may be nil (each disables that fn); `:print` is
  never nil (always at least `default-edn-print`)."
  [{:keys [validate explain print] :as m}]
  (when (contains? m :validate) (reset! validator-fn validate))
  (when (contains? m :explain)  (reset! explainer-fn explain))
  ;; Establish the printer-never-nil invariant at every write site.
  (when (contains? m :print)
    (reset! printer-fn (or print default-edn-print)))
  ;; Return the full installed bundle, including untouched keys and any
  ;; nil-printer coercion. This is a boot-time configuration path.
  {:validate @validator-fn
   :explain  @explainer-fn
   :print    @printer-fn})

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
  §Schema digest line 491. Parallel to
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

(defn snapshot-schema-fns
  "Capture the installed validator, explainer, and printer as one opaque
  bundle. Together with `snapshot-schemas-by-frame`, this lets fixtures
  preserve the complete schema runtime through the encapsulated API.

  Returns a map in the SAME shape `set-schema-fns!` accepts and returns
  accepted by `set-schema-fns!`:

    {:validate @validator-fn   ;; may be nil (validation disabled)
     :explain  @explainer-fn   ;; may be nil (no explanation)
     :print    @printer-fn}    ;; never nil (always at least default-edn-print)

  so the value round-trips through `restore-schema-fns!`. Reads are not a
  transactional snapshot; configuration is expected to be quiescent here."
  []
  {:validate @validator-fn
   :explain  @explainer-fn
   :print    @printer-fn})

(defn restore-schema-fns!
  "Reinstall a validator / explainer / printer bundle captured by
  `snapshot-schema-fns`. The bundle-level companion to the
  registry's `restore-schemas-by-frame!` (storage.cljc).

  Restoring is a FULL bundle install — all three atoms are reset from the
  snapshot map's `:validate` / `:explain` / `:print` keys. Delegates to
  `set-schema-fns!` so a snapshot's keys land through the SAME write path
  the public setter uses: in particular a `nil` `:print` coerces to
  `default-edn-print` exactly like `set-schema-fns!` / `set-schema-printer!`,
  so the printer-never-nil invariant `run-printer` relies on
  holds after a restore without a read-site guard — a `snapshot-schema-fns`
  value always carries a non-nil `:print`, but routing the restore through
  the coercing setter keeps the invariant true even for a hand-built bundle
  map. Returns the installed bundle map (the `set-schema-fns!` return)."
  [bundle]
  (set-schema-fns! bundle))

(defn using-default-validator?
  "True when `validator-fn` is still the framework-default
  `default-malli-validate`. Used by the
  `:rf.warning/schema-validator-unavailable` registration-time check to
  distinguish 'app hasn't opted out of Malli' from
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
  pipeline (`re-frame.schemas.digest`) hashes this fn's UTF-8 bytes.
  `printer-fn` is never nil: both write sites
  (`set-schema-printer!` and `set-schema-fns!`'s `:print` key) coerce
  a nil `:print` to `default-edn-print`, so the
  cross-runtime digest contract holds without a read-site guard."
  [schema-value]
  (@printer-fn schema-value))
