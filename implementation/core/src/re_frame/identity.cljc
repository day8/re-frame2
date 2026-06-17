(ns re-frame.identity
  "Internal canonical-EDN-identity algebra (EP-0012, ACCEPTED ruling
  rf2-s7xshi). The normative contract lives in [`spec/Conventions.md`
  §Canonical EDN identity]; this namespace is the reference
  implementation of the `CEDN-1` encoding.

  ## Status — INTERNAL (EP-0012 disposition 1)

  The *semantics* are normative immediately; the *names* are NOT public
  API at this slice. No `re-frame.core` facade export, no classification.
  Resources, routing, the work ledger, and schema digests are the
  consumers that will graduate a public name once two-plus use it through
  this namespace unchanged.

  ## Why canonical identity, not stringification

  `str`, `pr-str` over unordered host maps, `JSON.stringify`, and object
  identity are NOT valid framework identity contracts — they differ by
  host, leak insertion order, or depend on references. Resource caches,
  work ids, route params, and epoch/replay records need an identity that
  survives SSR, hydration, replay, Xray inspection, and multi-host
  conformance. Canonical EDN is the smallest Clojure-native answer.

  ## Identity vs digest (EP-0012 disposition 5)

  The canonical EDN value IS the identity everywhere — storage, work
  ledger, traces, epoch/replay. `canonical` returns that normalized
  value. A *digest* is an OPTIONAL, versioned, always-recomputable
  projection for size-constrained surfaces; it is never an independent
  identity fact, never required for correctness, never the authoritative
  stored key. This namespace ships `canonical`, `canonical-bytes` (the
  CEDN-1 byte string the comparison/digest is defined over), and
  `identical-identity?` (CEDN-1 byte equality) — and no
  authoritative-digest store.

  ## The CEDN-1 byte encoding

  A UTF-8 token stream with a type tag before every value (Conventions
  §Canonical byte encoding). The type tag keeps distinct EDN values
  distinct — `\"42\"`, `42`, `:42`, `[1 2]`, and `(1 2)` cannot collide.
  Maps order entries by their keys' CEDN-1 bytes; sets order elements by
  their CEDN-1 bytes; vectors and lists preserve order and remain
  distinct from each other. Out-of-domain values fail closed with
  `:rf.error/non-edn-identity` rather than falling back to host
  comparison.

  Equality-sensitive comparison MUST be equivalent to comparing CEDN-1
  bytes; `canonical` returns the normalized EDN value, and two values are
  equal-as-identity iff their `canonical-bytes` are `=`.

  Pure namespace — no runtime state, no trace. `.cljc` so the JVM test
  sweep exercises the encoder cross-host."
  (:require [clojure.string :as str]
            [re-frame.error :as error])
  #?(:clj (:import [java.util UUID]
                   [java.util Date]
                   [java.time Instant ZoneOffset]
                   [java.time.format DateTimeFormatter])))

#?(:clj (set! *warn-on-reflection* true))

;; A fixed UTC RFC 3339 formatter pinned to millisecond precision so an
;; instant at a whole second renders `...T00:00:00.000Z`, not `...T00:00:00Z`
;; — the encoding is deterministic either way, but the spec pins
;; millisecond text, so we pin it.
#?(:clj
   (def ^:private ^DateTimeFormatter rfc3339-millis-utc
     (-> (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
         (.withZone ZoneOffset/UTC))))

;; ---- the fail-closed rejection -------------------------------------------

(defn- reject!
  "Throw the canonical `:rf.error/non-edn-identity` error. A value outside
  the CEDN-1 domain fails the WHOLE identity closed — never a host-string
  fallback (Conventions §Canonical EDN identity)."
  [value reason]
  (error/throw-error!
    :rf.error/non-edn-identity
    'rf.identity/canonical
    reason
    {:recovery :encode-as-portable-edn
     :extra    {:bad-value value
                :bad-type  (str (type value))}}))

;; ---- safe-integer range --------------------------------------------------
;;
;; Portable integers live in the ECMAScript safe-integer range so identity
;; survives the CLJS host. Outside it, fail closed.

(def ^:private max-safe-integer 9007199254740991)
(def ^:private min-safe-integer -9007199254740991)

(defn safe-segment-integer?
  "True iff `n` is an integer inside the CEDN-1 portable safe-integer range
  `[-9007199254740991, 9007199254740991]` (the ECMAScript safe-integer
  range). This is the SHARED predicate the CEDN-1 encoder and the `:rf/path`
  concrete-segment domain both key off (rf2-ujmc3u): the shared path
  vocabulary MUST NOT be wider than canonical EDN identity, so an integer
  that cannot be portably compared / printed / routed / digested is not a
  valid concrete path segment either. `re-frame.path/segment?` composes this
  on top of its `integer?` check so the two surfaces share ONE definition of
  \"portable integer\" rather than `path` re-admitting unsafe integers the
  canonicalizer rejects. Non-integers return false."
  [n]
  (and (integer? n)
       (<= min-safe-integer n)
       (<= n max-safe-integer)))

(defn- safe-integer?
  [n]
  (and (<= min-safe-integer n) (<= n max-safe-integer)))

;; ---- host-type discrimination --------------------------------------------

(defn- uuid-value?
  [x]
  #?(:clj  (instance? UUID x)
     :cljs (uuid? x)))

(defn- instant->utc-millis-string
  "Render an instant as RFC 3339 UTC text with millisecond precision.
  Equivalent instants in different source timezones normalize to the same
  UTC text — timezone text from the source literal is not identity."
  [x]
  #?(:clj
     (let [^Instant inst (cond
                           (instance? Instant x) x
                           (instance? Date x)    (.toInstant ^Date x)
                           :else                 nil)]
       (when inst
         ;; Truncate to milliseconds, render with a fixed millisecond-
         ;; precision UTC formatter.
         (let [millis (.toEpochMilli inst)]
           (.format rfc3339-millis-utc (Instant/ofEpochMilli millis)))))
     :cljs
     (when (instance? js/Date x)
       ;; `toISOString` already emits `...T..:..:..\.SSSZ` (millisecond
       ;; precision, UTC) — the JS counterpart of the JVM formatter above.
       (.toISOString ^js x))))

(defn- instant-value?
  [x]
  #?(:clj  (or (instance? Instant x) (instance? Date x))
     :cljs (instance? js/Date x)))

(defn- bad-number?
  "A number outside the CEDN-1 numeric domain: floats, ratios, arbitrary-
  precision decimals, NaN, and infinities. CEDN-1 admits only portable
  integers in the safe range unless a future spec encodes a numeric class.
  On CLJS a `js/Number` integer-valued double (`2.0`) reads as an integer
  via `int?`/`integer?` so it is admitted iff it is integer-valued and in
  range; a fractional or non-finite number is rejected."
  [x]
  #?(:clj
     (or (float? x)        ; Double / Float
         (ratio? x)
         (decimal? x))
     :cljs
     ;; CLJS has one numeric type. `int?` is true for integer-valued
     ;; doubles; reject non-integers and non-finite values.
     (or (not (cljs.core/integer? x))
         (not (js/isFinite x)))))

;; ---- canonical token encoding (CEDN-1) -----------------------------------
;;
;; Encode into a UTF-8 token string. Strings/keywords/symbols use the EDN
;; literal via `pr-str` (portable across CLJ/CLJS readers); the type tag
;; preceding each value keeps distinct EDN kinds distinct.

(declare encode)

(defn- encode-string [s]
  ;; `pr-str` produces a canonical EDN string literal over Unicode scalar
  ;; values, identical across CLJ/CLJS.
  (str "s:" (pr-str s)))

(defn- encode-keyword [k]
  ;; The canonical EDN keyword token, never auto-resolved `::` shorthand.
  ;; `pr-str` on a keyword always prints the single-colon fully-qualified
  ;; form.
  (str "k:" (pr-str k)))

(defn- encode-symbol [y]
  (str "y:" (pr-str y)))

(defn- encode-elements
  "Encode a sequence of already-canonical-ordered elements, space-joined.
  Composite encodings separate adjacent element tokens with a single
  ASCII space (Conventions §Canonical byte encoding)."
  [xs]
  (str/join " " (map encode xs)))

(defn- encode-map [m]
  ;; Order entries by their keys' CEDN-1 bytes; each key token is separated
  ;; from its value token, and entries from each other, by a single space.
  ;;
  ;; Duplicate canonical keys FAIL CLOSED (Conventions §Map key
  ;; canonicalization: "Duplicate canonical keys are invalid and MUST be
  ;; rejected before the value becomes a cache key, route identity, or work
  ;; id."). Two DISTINCT host map keys can encode to the SAME CEDN-1 key
  ;; bytes — e.g. a `java.util.Date` and a `java.time.Instant` for one
  ;; instant are distinct JVM map keys (`=` does not equate them) yet render
  ;; the identical `t:<utc>` token. Emitting both would produce a structurally
  ;; ambiguous identity; instead we reject the whole identity closed
  ;; (rf2-w9x5fv item 3) rather than silently serialize colliding key tokens.
  (let [entries (->> m
                     (map (fn [[k v]] [(encode k) (encode v)]))
                     (sort-by first))
        ks      (map first entries)]
    (when-not (= (count ks) (count (distinct ks)))
      (reject! m :duplicate-canonical-map-key))
    (str "m{"
         (str/join " " (mapcat (fn [[ke ve]] [ke ve]) entries))
         "}")))

(defn- encode-set [s]
  ;; Sort elements by their canonical element encoding.
  (let [sorted (sort (map encode s))]
    (str "q#{" (str/join " " sorted) "}")))

(defn- encode
  "Encode one EDN value to its CEDN-1 token string, or fail closed."
  [x]
  (cond
    (nil? x)            "n"
    (boolean? x)        (if x "b:1" "b:0")
    (string? x)         (encode-string x)
    (keyword? x)        (encode-keyword x)
    ;; UUID / instant BEFORE the symbol/number branches (a host date is
    ;; not a number; a UUID is not a symbol).
    (uuid-value? x)     (str "u:" (str/lower-case (str x)))
    (instant-value? x)  (str "t:" (instant->utc-millis-string x))
    (symbol? x)         (encode-symbol x)
    (integer? x)        (if (and (not (bad-number? x)) (safe-integer? x))
                          (str "i:" x)
                          (reject! x :integer-out-of-safe-range))
    (number? x)         (reject! x :non-integer-number)
    ;; Vectors before the generic sequential branch so vector-kind is
    ;; preserved distinctly from list-kind.
    (vector? x)         (str "v[" (encode-elements x) "]")
    (set? x)            (encode-set x)
    (map? x)            (encode-map x)
    ;; `seq?` subsumes `list?` (every list is a seq; lazy seqs are seq? but
    ;; not list?) and both encode identically, so one `seq?` branch covers
    ;; the whole list-kind (fe3a9q: the prior `list?` branch was dead).
    (seq? x)            (str "l(" (encode-elements x) ")")
    :else               (reject! x :unsupported-host-value)))

;; ---- public-internal surface ---------------------------------------------

(defn canonical-bytes
  "Return the CEDN-1 token string for `value` — the byte-level identity
  the equality contract is defined over. Two values are equal-as-identity
  iff their `canonical-bytes` are `=`. Throws `:rf.error/non-edn-identity`
  for any value outside the CEDN-1 domain (functions, atoms, promises,
  DOM nodes, host objects, floats / ratios / NaN / infinities, integers
  outside the safe range)."
  [value]
  (encode value))

(declare canonical)

(defn- canonical-map
  "Canonicalize a map's keys and values, failing closed on DUPLICATE
  canonical keys. Two DISTINCT source keys can canonicalize to the SAME key
  (e.g. a java.util.Date and a java.time.Instant for one instant both
  normalize to the same UTC text), which would silently collapse two
  identity-distinct entries into one. Per Conventions §Map key
  canonicalization (\"Duplicate canonical keys are invalid and MUST be
  rejected before the value becomes a cache key, route identity, or work
  id\") this rejects the whole identity closed — the same fail-closed rule
  `canonical-bytes` enforces, so the two surfaces never diverge (rf2-w9x5fv
  item 3)."
  [m]
  (let [out (reduce-kv (fn [acc k v]
                         (let [ck (canonical k)]
                           (when (contains? acc ck)
                             (reject! m :duplicate-canonical-map-key))
                           (assoc acc ck (canonical v))))
                       {} m)]
    out))

(defn canonical
  "Return the canonical normalized EDN identity of `value` — the storage,
  work-ledger, trace, and replay identity (EP-0012 disposition 5: canonical
  EDN IS the identity).

  The normalization recursively reorders map entries and set elements into
  CEDN-1 canonical order while preserving vector / list order and EDN kind,
  so two map spellings that differ only in insertion order return an `=`
  canonical value. Instants normalize to a single representation
  (millisecond-precision UTC), so `canonical` and `canonical-bytes` share ONE
  canonical form — a value stored via `canonical` and a value compared via
  `canonical-bytes` can never disagree (rf2-w9x5fv item 3). Fails closed with
  `:rf.error/non-edn-identity` for any out-of-domain value — the validation
  walk runs eagerly so a host handle buried anywhere in the structure is
  rejected, never host-stringified — and for a map carrying DUPLICATE
  canonical keys (two distinct host keys whose CEDN-1 bytes collide).

  Present `nil` stays distinct from a missing key: `(canonical {:page nil})`
  is not `(canonical {})`. `nil` elision, if a surface wants it, is a
  surface-specific policy applied BEFORE canonicalization."
  [value]
  (cond
    (nil? value)       nil
    (boolean? value)   value
    (string? value)    value
    (keyword? value)   value
    ;; A UUID is `=` across both surfaces already (UUID equality is over the
    ;; canonical 4122 form), so it has no two-surface divergence — preserve it.
    (uuid-value? value) value
    ;; Instants DO diverge: a java.util.Date and a java.time.Instant for one
    ;; moment are NOT `=`, so preserving the host object would give two
    ;; unequal canonical values for one identity fact. Normalize both to the
    ;; single CEDN-1 UTC text so `canonical` and `canonical-bytes` agree on a
    ;; single canonical form (rf2-w9x5fv item 3).
    (instant-value? value) (instant->utc-millis-string value)
    (symbol? value)    value
    (integer? value)   (if (and (not (bad-number? value)) (safe-integer? value))
                         value
                         (reject! value :integer-out-of-safe-range))
    (number? value)    (reject! value :non-integer-number)
    (vector? value)    (mapv canonical value)
    (set? value)       (into #{} (map canonical) value)
    (map? value)       (canonical-map value)
    ;; `seq?` subsumes `list?` (fe3a9q) — one branch covers list-kind.
    (seq? value)       (apply list (map canonical value))
    :else              (reject! value :unsupported-host-value)))

(defn identical-identity?
  "True iff `a` and `b` denote the same canonical EDN fact — i.e. their
  CEDN-1 bytes are `=`. Both are encoded (and so fail closed if either is
  out of domain)."
  [a b]
  (= (canonical-bytes a) (canonical-bytes b)))
