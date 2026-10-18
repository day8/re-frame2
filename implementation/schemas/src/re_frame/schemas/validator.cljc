(ns re-frame.schemas.validator
  "Pluggable validator, explainer, and schema-printer functions.

  Per Spec 010 §Default validator and the validator-fn extension point.
  The framework never inspects the value stored in `:schema` directly;
  every validation site routes through the registered validator fn.
  This is the seam Malli plugs into by default. Applications may install a
  different validator bundle, or nil for no validation.

  The fns are registered separately so the validate hot path stays
  cheap (validate returns truthy/falsey; explain and humanize are only
  invoked on the failure branch to populate the trace's `:explain` and
  `:explain-humanized` keys; print is only invoked when the digest
  pipeline serialises a schema):

    :validate (fn [schema value] truthy?)
              — same shape as Malli's `validate`. nil disables; the
              call site treats nil as 'pass everything'.

    :explain  (fn [schema value] explanation)
              — same shape as Malli's `explain`. nil = no explanation
              attached to the failure trace.

    :humanize (fn [explanation] readable)
              — turns an `:explain` value into the operator-readable
              `:explain-humanized` payload (Spec 010 §Humanize-hook). nil =
              the failure trace carries `:explain` alone.

    :compile  (fn [schema] _)
              — called with each schema a development-build
              `reg-app-schema` / `reg-app-schemas` declares, before anything
              registers, so a malformed schema throws at the call that wrote
              it. The return is ignored. nil = registration does not check
              the schema, and a malformed one surfaces at its first
              validation instead.

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

  The default bundle is the Malli adapter's (`re-frame.schemas.malli`), so
  loading this namespace loads Malli."
  (:require [re-frame.schemas.malli :as rf.schemas.malli]))

#?(:clj (set! *warn-on-reflection* true))

(defn- compare-by-pr-str
  "Spec 010 §Digest algorithm step 1 ordering — total order on EDN
  values via `(compare (pr-str a) (pr-str b))`. Stable across runtimes
  because `pr-str` over EDN is a syntactic projection."
  [a b]
  (compare (pr-str a) (pr-str b)))

(defn- fn-token
  "Per Spec 010 §Digest algorithm step 1 — the canonical stand-in for a
  FUNCTION appearing inside a schema form.

  A bare predicate is the idiom the how-to recommends — `[:map [:n
  pos-int?]]` — and `pr-str` over a function is not deterministic at all.
  On the JVM a function prints as
  `#object[clojure.core$pos_int_QMARK_ 0x3aefae67 \"…@3aefae67\"]`, and
  that `0x3aefae67` is `System/identityHashCode` — a fresh value in every
  process. Printed raw, a fn-bearing schema would digest differently on
  every server restart, and the SSR hydrate handshake would report
  `:rf.ssr/schema-digest-mismatch` (\"Deploy drift\") against a client
  running byte-identical code.

  The token is derived from the host's own NAME for the function — the
  class name on the JVM, `.-name` on CLJS — which carries no address, so
  the digest is a pure function of the schema value and stable for the
  process lifetime. `\"#fn\"` is the fallback where the host offers no
  name (a JS anonymous function reports `\"\"`).

  Deriving from the name keeps the digest DISCRIMINATING — swapping
  `pos-int?` for `neg-int?` moves the digest, which a constant token
  would silently stop detecting, and drift detection is the whole point
  of the surface.

  What this deliberately does NOT do is make two HOSTS agree. The JVM
  answers `clojure.core$pos_int_QMARK_` where CLJS answers
  `cljs$core$pos_int_QMARK_`, and `:advanced` munges the CLJS name again
  to something build-specific. A fn-bearing schema therefore has a
  per-host stable digest, not a cross-runtime reproducible one — see
  Spec 010 §Digest algorithm. Normalising the two spellings into one would
  work in development and stop working under `:advanced` with nothing on
  screen to say so, which is the same lying-gate failure this token exists
  to prevent."
  [f]
  (let [nm #?(:clj  (.getName (class f))
              :cljs (.-name f))]
    (if (and (string? nm) (seq nm))
      (str "#fn[" nm "]")
      "#fn")))

(defn- whole-number-double?
  "True for a JVM floating-point value that denotes a whole number inside
  the ECMAScript safe-integer range.

  This is the second host-divergent printer case. `pr-str` of `1.0` is
  `\"1.0\"` on the JVM and `\"1\"` on CLJS, which has a single numeric type
  and cannot tell the two apart — so the perfectly ordinary schema prop
  `[:int {:min 1.0}]` would digest differently on the two hosts and fake
  the same \"Deploy drift\" warning. Emitting the integer form is the only
  direction that can agree: CLJS is physically unable to print a `.0`
  suffix for a value it does not distinguish from an integer, so
  normalising towards the JVM spelling would merely move the divergence.

  The safe-integer bound is what makes the conversion total. Outside it a
  double is not exactly representable as an integer on either host
  (`1e21` prints `\"1.0E21\"` on the JVM and `\"1e+21\"` on CLJS), and
  `(long 1e21)` overflows, so such a value is left alone and stays
  host-divergent — recorded in Spec 010 §Digest algorithm rather than
  papered over. NaN and the infinities fall out through the same range
  test, every comparison against them being false.

  CLJS answers false throughout: `1.0` already prints as `1` there, so
  there is nothing to normalise, and CLJS's `float?` is true of every
  number, which would send integers through a pointless conversion."
  [x]
  #?(:clj  (and (float? x)
                (let [d (double x)]
                  (and (<= -9007199254740991.0 d 9007199254740991.0)
                       (== d (Math/rint d)))))
     :cljs false))

(defn- canonicalise-schema-form
  "Per Spec 010 §Digest algorithm step 1 — normalise a schema EDN form for
  stable byte serialisation: metadata is stripped, and map keys are
  emitted in `(compare (pr-str a) (pr-str b))` order via sorted-maps /
  sorted-sets so insertion order does not bleed into the printed bytes.
  Sequences and vectors recurse element-wise.

  Two scalar kinds do NOT survive `pr-str` deterministically and are
  normalised here instead: a function becomes its `fn-token`
  (its host print carries a per-process identity hash), and a
  whole-number double becomes the integer it denotes (the JVM prints the
  `.0` suffix CLJS cannot). Every other value passes through unchanged.

  `fn?` is deliberate where `ifn?` would be wrong: CLJS keywords, maps
  and vectors are all `ifn?`, and tokenising those would destroy the
  schema form."
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
    (fn? form)             (fn-token form)
    (whole-number-double? form) (long form)
    :else          form))

(defn default-edn-print
  "The default schema-print companion. Per Spec 010 §Schema digest —
  serialise a schema value to the stable UTF-8 byte-source string the
  digest pipeline hashes. The default uses `pr-str` over a canonicalised
  EDN form: map-keys emitted in `compare-by-pr-str` order, metadata
  stripped, namespaced-map printing disabled. This is the cross-runtime
  contract every Malli-EDN-compatible port shares. Same `schema-value`
  always returns byte-identical bytes.

  `*print-length*` / `*print-level*` are reset too: an ambient REPL or tool
  binding would otherwise truncate both the canonicaliser's `pr-str` key
  comparator and the final bytes.

  Ports that ship a non-EDN schema language register their own printer via
  `set-schema-fns!`'s `:print` key; the digest then reflects the registered
  validator's serialisation contract rather than the framework's Malli-EDN
  default.

  Prints afresh on every call and retains nothing, so a schema replaced by
  a re-registration or dropped with its frame is not kept alive here."
  [schema-value]
  (binding [*print-meta*           false
            *print-readably*       true
            *print-dup*            false
            *print-namespace-maps* false
            *print-length*         nil
            *print-level*          nil]
    (pr-str (canonicalise-schema-form schema-value))))

(def default-schema-fns
  "The framework's own validator bundle, as a public VALUE — the state the
  atoms below hold before any app installs a port: the Malli bundle
  (`re-frame.schemas.malli/bundle`) completed with the EDN schema printer.

    {:validate …                    ;; Malli, compiled once per schema value
     :compile  …                    ;; compiles into the same cache; throws on a malformed schema
     :explain  malli.core/explain
     :humanize malli.error/humanize ;; development builds; nil in production
     :print    default-edn-print}   ;; the EDN canonicaliser the digest hashes

  Restore the framework defaults by installing it — `(set-schema-fns!
  default-schema-fns)`."
  (assoc rf.schemas.malli/bundle :print default-edn-print))

(defonce
  ^{:doc "The currently-registered validator fn — `(fn [schema value]
          truthy?)`. Default delegates to Malli; apps swap it via
          `set-schema-fns!`'s `:validate` key. An explicit `nil`
          disables validation everywhere (every `validate-*!` call
          returns true without inspecting the schema)."}
  validator-fn
  (atom (:validate default-schema-fns)))

(defonce
  ^{:doc "The currently-registered explainer fn — `(fn [schema value]
          explanation)`. Populates the failure trace's `:explain`
          key. Default delegates to Malli's `explain`; nil means no
          explanation is attached."}
  explainer-fn
  (atom (:explain default-schema-fns)))

(defonce
  ^{:doc "The currently-registered humanizer fn — `(fn [explanation]
          readable)`. Populates the failure trace's `:explain-humanized`
          key from the explainer's output. Default is Malli's `humanize`
          in development builds and nil in production; nil means the
          trace carries `:explain` alone."}
  humanizer-fn
  (atom (:humanize default-schema-fns)))

(defonce
  ^{:doc "The currently-registered schema compiler — `(fn [schema] _)`,
          called at development registration so a malformed schema throws
          at the call that wrote it. Default compiles through the Malli
          bundle's cache; nil skips the check."}
  compiler-fn
  (atom (:compile default-schema-fns)))

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
  "THE DOOR. Install any subset of the validator / explainer / humanizer /
  compiler / printer bundle from a single map.

    (set-schema-fns! {:validate validate-fn
                      :explain  explain-fn
                      :humanize humanize-fn
                      :compile  compile-fn
                      :print    print-fn})

  Each key is optional; a key that is absent leaves the existing
  registration in place. Per Spec 010 §How the surface works this is
  the one-call substitute-Malli boot pattern — a Zod / clojure.spec
  port installs its validator, explainer, and digest-printer together
  so the three never drift out of sync mid-boot.

    :validate  (fn [schema value] truthy?) | nil  — nil disables
               validation (every site soft-passes).
    :explain   (fn [schema value] explanation) | nil — nil leaves the
               failure trace's `:explain` slot nil.
    :humanize  (fn [explanation] readable) | nil — nil leaves the failure
               trace without `:explain-humanized`.
    :compile   (fn [schema] _) | nil — called at development registration
               so a malformed schema throws there; nil skips the check. A
               port that installs its own `:validate` installs its own
               `:compile` (or nil) beside it.
    :print     (fn [schema-value] canonical-string) | nil — the schema-print
               companion the digest pipeline hashes. nil coerces
               to the default EDN canonicaliser, so `printer-fn` is
               never nil after any write and the digest is never
               undefined for a present schema set.

  Per Spec 010 §Locked rules an installed validator must be pure
  (same `(schema, value)` returns the same result) and must be
  production-elidable alongside `re-frame.interop/debug-enabled?` —
  every call site is already gated on `debug-enabled?`, so the
  validator's body is unreachable in `:advanced` + `goog.DEBUG=false`
  builds.

  Writes are last-write-wins per key and are NOT transactional — the
  `reset!`s are separate, and configuration is expected to be
  quiescent at boot.

  Returns the installed bundle as a map — `{:validate @validator-fn
  :explain @explainer-fn :humanize @humanizer-fn :compile @compiler-fn
  :print @printer-fn}` —
  reflecting the live state of every function after this call. A bundle setter returns
  its bundle: the return mirrors this fn's own input shape, so a caller
  observes everything now installed, including keys it did NOT touch and
  the nil-printer coercion of `:print`. `:validate` / `:explain` /
  `:humanize` / `:compile` may be nil (each disables that fn); `:print` is
  never nil
  (always at least `default-edn-print`).

  Restoring is the same call: install a value captured by `schema-fns`,
  or `default-schema-fns` to return to the framework defaults."
  [{:keys [validate explain humanize compile print] :as m}]
  (when (contains? m :validate) (reset! validator-fn validate))
  (when (contains? m :explain)  (reset! explainer-fn explain))
  (when (contains? m :humanize) (reset! humanizer-fn humanize))
  (when (contains? m :compile)  (reset! compiler-fn compile))
  ;; Establish the printer-never-nil invariant at every write site.
  (when (contains? m :print)
    (reset! printer-fn (or print default-edn-print)))
  ;; Return the full installed bundle, including untouched keys and any
  ;; nil-printer coercion. This is a boot-time configuration path.
  {:validate @validator-fn
   :explain  @explainer-fn
   :humanize @humanizer-fn
   :compile  @compiler-fn
   :print    @printer-fn})

(defn schema-fns
  "THE READ. Return the installed validator / explainer / humanizer /
  compiler / printer as one value, in the SAME shape `set-schema-fns!`
  accepts and returns:

    {:validate @validator-fn   ;; may be nil (validation disabled)
     :explain  @explainer-fn   ;; may be nil (no explanation)
     :humanize @humanizer-fn   ;; may be nil (no humanized explanation)
     :compile  @compiler-fn    ;; may be nil (no registration-time check)
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
   :humanize @humanizer-fn
   :compile  @compiler-fn
   :print    @printer-fn})

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
  (nil); the failure trace's `:explain` slot then reads nil."
  [schema value]
  (when-let [f @explainer-fn]
    (f schema value)))

(defn run-humanizer
  "Invoke the registered humanizer fn against an explainer's output. Returns
  nil when no humanizer is registered (nil); the failure trace then carries
  `:explain` without `:explain-humanized`."
  [explanation]
  (when-let [f @humanizer-fn]
    (f explanation)))

(defn run-compiler
  "Invoke the registered schema compiler against `schema` at registration,
  so a malformed schema throws at the call that wrote it. A no-op when none
  is registered (nil). Returns nil.

  Callers MUST wrap invocations in `(when interop/debug-enabled? ...)`: the
  check is a development aid, and production registration stays a plain
  write."
  [schema]
  (when-let [f @compiler-fn]
    (f schema))
  nil)

(defn run-printer
  "Hot-path entry — invoke the registered schema-print companion against
  a single schema value. Per Spec 010 §Schema digest line 491 the digest
  pipeline (`re-frame.schemas.digest`) hashes this fn's UTF-8 bytes.
  `printer-fn` is never nil: its only write site (`set-schema-fns!`'s
  `:print` key) coerces a nil `:print` to `default-edn-print`, so the
  cross-runtime digest contract holds without a read-site guard."
  [schema-value]
  (@printer-fn schema-value))
