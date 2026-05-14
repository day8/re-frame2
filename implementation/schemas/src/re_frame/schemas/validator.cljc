(ns re-frame.schemas.validator
  "Pluggable validator / explainer / printer fns (rf2-froe + rf2-wla45).

  Per Spec 010 §Non-Malli validators — the validator-fn extension point.
  The framework never inspects the value stored in `:spec` directly;
  every validation site routes through the registered validator fn.
  This is the seam Malli plugs into by default; apps that want to drop
  the ~24 KB gzipped Malli surface (rf2-qnxf) call
  `(rf/set-schema-validator! some-other-fn)` (or `nil` for no-op) at
  boot before any reg-app-schema / :spec metadata lands.

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

  A combined `(set-schema-validator! {:validate ... :explain ... :print ...})`
  arity exists for callers who want to install all three atomically.

  Per rf2-t0hq the CLJS default validator used to reach Malli through
  runtime `resolve` — but CLJS has no runtime resolve (the symbol is
  a compile-time analyzer affordance only). The fix is the rf2-froe /
  rf2-p7va substitute-validator pattern: the `re-frame.schemas.malli`
  adapter namespace publishes Malli's `validate` and `explain` into the
  late-bind hook table on ns-load. The default fns below consult the
  table on every call. Apps opt in to Malli validation by requiring
  `re-frame.schemas.malli` at app boot — the same pattern on both
  runtimes (no JVM-vs-CLJS asymmetry). When the adapter ns is not
  loaded the default fns soft-pass per Spec 010 §Recommended soft-pass."
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

(defn- canonicalise-schema-form
  "Per Spec 010 §Digest algorithm step 1 — normalise a schema EDN form for
  stable byte serialisation: metadata is stripped, and map keys are
  emitted in `(compare (pr-str a) (pr-str b))` order via sorted-maps /
  sorted-sets so insertion order does not bleed into the printed bytes.
  Sequences and vectors recurse element-wise. Non-collection values
  pass through unchanged."
  [form]
  (letfn [(canon [x]
            (cond
              (map? x)
              (into (sorted-map-by (fn [a b]
                                     (compare (pr-str a) (pr-str b))))
                    (map (fn [[k v]] [(canon k) (canon v)]))
                    x)
              (vector? x) (mapv canon x)
              (set? x)    (into (sorted-set-by (fn [a b]
                                                 (compare (pr-str a) (pr-str b))))
                                (map canon)
                                x)
              (seq? x)    (doall (map canon x))
              :else       x))]
    (canon form)))

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
  Malli-EDN default."
  [schema-value]
  (binding [*print-meta*           false
            *print-readably*       true
            *print-dup*            false
            *print-namespace-maps* false]
    (pr-str (canonicalise-schema-form schema-value))))

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

    (set-schema-validator! {:validate validate-fn
                            :explain  explain-fn
                            :print    print-fn})
      Atomic swap of any subset at once. Per rf2-wla45 the `:print`
      key registers the schema-print companion the digest pipeline
      hashes (Spec 010 §Schema digest line 491). Any key may be `nil`
      to disable the corresponding hot path (the printer falls back
      to the default EDN canonicaliser; the digest is never
      undefined for a present schema set). Keys not supplied leave
      the existing registration in place.

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
    (let [{:keys [validate explain print]
           :or   {validate ::unset
                  explain  ::unset
                  print    ::unset}} validate-fn-or-map]
      (when-not (= ::unset validate) (reset! validator-fn validate))
      (when-not (= ::unset explain)  (reset! explainer-fn  explain))
      (when-not (= ::unset print)    (reset! printer-fn    print))
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
  "Hot-path entry — invoke the registered schema-print companion (or
  the default EDN canonicaliser when none is registered) against a
  single schema value. Per Spec 010 §Schema digest line 491 — the
  digest pipeline (`re-frame.schemas.digest`) hashes this fn's UTF-8
  bytes (rf2-wla45). Never returns nil for a non-nil schema value:
  a nil registration falls back to `default-edn-print` so the
  cross-runtime digest contract holds even when the validator
  surface is fully nilled out."
  [schema-value]
  (let [f (or @printer-fn default-edn-print)]
    (f schema-value)))
