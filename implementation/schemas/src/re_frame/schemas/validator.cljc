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

  The port is a VALUE and `set-schema-fns!` is the one door: it installs
  any subset of the bundle so a schema-language port keeps validation,
  explanations, and digest serialization aligned. `schema-fns` reads back
  what is installed, and `default-schema-fns` is the framework's own
  bundle as a public value. The three compose — capture with
  `schema-fns`, stub with `set-schema-fns!`, restore by installing the
  captured value (or `default-schema-fns`) — so no separate reset,
  snapshot, or restore verb is needed.

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

  Apps that want Malli-absent behaviour to be a hard fail install
  a stricter validator via `set-schema-fns!`."
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
            printer via `set-schema-fns!`'s `:print` key; the digest then
            reflects the registered validator's serialisation contract
            rather than the framework's Malli-EDN default.

            Memoised by immutable schema value and clearable for test
            isolation via `clear-edn-print-cache!`."
      :arglists '([schema-value])}
    default-edn-print memo)

  (def
    ^{:doc "Reset the `default-edn-print` memo cache. Intended for test
            fixtures that generate fresh schemas. Returns nil."
      :arglists '([])}
    clear-edn-print-cache! clear!))

(def default-schema-fns
  "The framework's own validator bundle, as a public VALUE — the state the
  three atoms below hold before any app installs a port.

    {:validate default-malli-validate   ;; late-binds to `:schemas/malli-validate`,
                                        ;;   soft-passes while that hook is unbound
     :explain  default-malli-explain    ;; late-binds to `:schemas/malli-explain`
     :print    default-edn-print}       ;; the EDN canonicaliser the digest hashes

  Restore the framework defaults by installing it — `(set-schema-fns!
  default-schema-fns)`. Because the map carries the same fn OBJECTS the
  atoms were seeded with, `using-default-validator?` answers true again
  afterwards.

  Note the bundle is the FRAMEWORK default, not \"the Malli bundle\":
  `:print` is the EDN canonicaliser rather than anything Malli supplies,
  and `:validate` / `:explain` soft-pass when the Malli adapter has not
  been loaded."
  {:validate default-malli-validate
   :explain  default-malli-explain
   :print    default-edn-print})

(defonce
  ^{:doc "The currently-registered validator fn — `(fn [schema value]
          truthy?)`. Default delegates to Malli; apps swap it via
          `set-schema-fns!`'s `:validate` key. An explicit `nil`
          disables validation everywhere (every `validate-*!` call
          returns true without inspecting the schema)."}
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
          serialiser via `set-schema-fns!`'s `:print` key. nil falls
          back to the default — the digest is never undefined for a
          present schema set."}
  printer-fn
  (atom default-edn-print))

(defn set-schema-fns!
  "THE DOOR. Install any subset of the validator / explainer / printer
  bundle from a single map.

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
               to the default EDN canonicaliser, so `printer-fn` is
               never nil after any write and the digest is never
               undefined for a present schema set.

  Per Spec 010 §Non-Malli validators an installed validator must be pure
  (same `(schema, value)` returns the same result) and must be
  production-elidable alongside `re-frame.interop/debug-enabled?` —
  every call site is already gated on `debug-enabled?`, so the
  validator's body is unreachable in `:advanced` + `goog.DEBUG=false`
  builds.

  Writes are last-write-wins per key and are NOT transactional — the
  three `reset!`s are separate, and configuration is expected to be
  quiescent at boot.

  Returns the installed bundle as a map — `{:validate @validator-fn
  :explain @explainer-fn :print @printer-fn}` — reflecting the live
  state of all three functions after this call. A bundle setter returns
  its bundle: the return mirrors this fn's own input shape, so a caller
  observes everything now installed, including keys it did NOT touch and
  the nil-printer coercion of `:print`. `:validate` / `:explain` may be
  nil (each disables that fn); `:print` is never nil (always at least
  `default-edn-print`).

  Restoring is the same call: install a value captured by `schema-fns`,
  or `default-schema-fns` to return to the framework defaults."
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

(defn schema-fns
  "THE READ. Return the installed validator / explainer / printer as one
  value, in the SAME shape `set-schema-fns!` accepts and returns:

    {:validate @validator-fn   ;; may be nil (validation disabled)
     :explain  @explainer-fn   ;; may be nil (no explanation)
     :print    @printer-fn}    ;; never nil (always at least default-edn-print)

  so it round-trips: `(set-schema-fns! (schema-fns))` is a no-op. That
  round-trip is what test isolation is built from — an ordinary `let` +
  `finally` over a value, with no dedicated snapshot or restore verb:

    (let [installed (schema-fns)]
      (try
        (set-schema-fns! {:validate stub-validate :explain stub-explain})
        ;; ... exercise the validation path against the stub ...
        (finally (set-schema-fns! installed))))

  Reads are not a transactional snapshot; configuration is expected to be
  quiescent here."
  []
  {:validate @validator-fn
   :explain  @explainer-fn
   :print    @printer-fn})

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
  `printer-fn` is never nil: its only write site (`set-schema-fns!`'s
  `:print` key) coerces a nil `:print` to `default-edn-print`, so the
  cross-runtime digest contract holds without a read-site guard."
  [schema-value]
  (@printer-fn schema-value))
