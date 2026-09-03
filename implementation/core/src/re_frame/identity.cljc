(ns re-frame.identity
  "Internal canonical-EDN-identity algebra (EP-0012). The normative
  contract lives in [`spec/Conventions.md` §Canonical EDN identity];
  this namespace is the reference implementation of the `CEDN-1`
  encoding.

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
            [re-frame.error :as rf.error])
  #?(:clj (:import [java.util UUID]
                   [java.util Date]
                   [java.time Instant ZoneOffset]
                   [java.time.format DateTimeFormatter])))

#?(:clj (set! *warn-on-reflection* true))

;; A fixed UTC RFC 3339 formatter pinned to millisecond precision so an
;; instant at a whole second renders `...T00:00:00.000Z`, not `...T00:00:00Z`
;; — the encoding is deterministic either way, but the spec pins
;; millisecond text, so we pin it. Uses `uuuu` (the signed proleptic year),
;; not `yyyy` (year-of-era), so the full portable window renders a correct
;; fixed-width 4-digit year down to `0000` — the two spellings are identical
;; for every year in that window, so the frozen CEDN-1 fixture token is
;; unchanged.
#?(:clj
   (def ^:private ^DateTimeFormatter rfc3339-millis-utc
     (-> (DateTimeFormatter/ofPattern "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
         (.withZone ZoneOffset/UTC))))

;; ---- the fail-closed rejection -------------------------------------------

(defn- reject!
  "Throw the canonical `:rf.error/non-edn-identity` error. A value outside
  the CEDN-1 domain fails the WHOLE identity closed — never a host-string
  fallback (Conventions §Canonical EDN identity)."
  [value reason]
  (rf.error/throw-error!
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
  concrete-segment domain both key off: the shared path
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

(defn- instant-value?
  [x]
  #?(:clj  (or (instance? Instant x) (instance? Date x))
     :cljs (instance? js/Date x)))

;; ---- the reserved tagged-instant tuple -----------------------------------
;;
;; The canonical form of an instant (java.util.Date / java.time.Instant /
;; js/Date) is the reserved tagged tuple
;;
;;     [:rf.identity/instant "<RFC-3339 UTC millisecond text>"]
;;
;; e.g. [:rf.identity/instant "2026-06-10T00:00:00.000Z"]. The tuple keeps the
;; instant KIND distinct from a look-alike plain string on BOTH identity
;; surfaces: `canonical` returns the tuple, and `canonical-bytes` emits the
;; SAME `t:<text>` token for a host instant AND for the tagged tuple (a plain
;; string stays `s:`). So `{#inst "…" :a, "…" :b}` is a legal two-entry map on
;; both surfaces, and an instant param never aliases a same-looking string
;; param. A BARE-string canonical form would recreate the instant-vs-string
;; collision one level down; the tagged tuple is collision-resistant vs user
;; data and mirrors the `[:rf.path/param …]` reserved-tuple precedent
;; (Conventions §Canonical EDN identity).

(def instant-marker
  "The reserved head keyword of the canonical tagged-instant tuple. The ENTIRE
  `[:rf.identity/instant <text>]` grammar is reserved: a vector headed by this
  marker is validated as a tagged instant and NEVER falls through to the
  generic vector branch — a wrong-arity / non-string / non-canonical /
  impossible-date payload fails closed with `:invalid-canonical-instant`."
  :rf.identity/instant)

;; Portable instant range (RULED — do not re-litigate): the four-digit-year
;; RFC-3339 window, inclusive
;; [0000-01-01T00:00:00.000Z .. 9999-12-31T23:59:59.999Z]. Fixed-width `uuuu`
;; year, inside JS Date's ±8.64e15 ms envelope on both hosts. Both boundaries
;; are the SAME epoch-millisecond on the JVM (java.time) and CLJS (js/Date)
;; hosts — proleptic-Gregorian UTC agrees across hosts. Outside the window an
;; instant fails closed with `:instant-out-of-portable-range`.
(def ^:private min-portable-instant-millis -62167219200000) ;; 0000-01-01T00:00:00.000Z
(def ^:private max-portable-instant-millis 253402300799999) ;; 9999-12-31T23:59:59.999Z

(def ^:private instant-text-re
  "The canonical millisecond-precision UTC spelling: fixed-width 4-digit year,
  zero-padded fields, literal `T` and `Z`, and exactly three fractional
  digits."
  #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z")

(defn- portable-instant-millis?
  [millis]
  (and (<= min-portable-instant-millis millis)
       (<= millis max-portable-instant-millis)))

(defn- millis->utc-text
  "Epoch-milliseconds → the canonical RFC-3339 millisecond-UTC text. `bad` is
  the original host / tuple value carried in the fail-closed error when the
  moment is outside the portable range."
  [bad millis]
  (when-not (portable-instant-millis? millis)
    (reject! bad :instant-out-of-portable-range))
  #?(:clj  (.format rfc3339-millis-utc (Instant/ofEpochMilli millis))
     :cljs (.toISOString (js/Date. millis))))

(defn- host-instant->utc-text
  "Project a host instant (java.util.Date / java.time.Instant / js/Date) to
  canonical millisecond-UTC text at epoch-millisecond precision. Equivalent
  instants in different source timezones normalize to the same UTC text —
  source-literal timezone is never identity. Converts the host leaks (JVM
  `toEpochMilli` overflow; a NaN `js/Date` from an invalid time value) into the
  fail-closed identity error rather than letting a host exception escape."
  [x]
  #?(:clj
     (let [^Instant inst (if (instance? Instant x) x (.toInstant ^Date x))
           millis        (try (.toEpochMilli inst)
                              (catch ArithmeticException _
                                (reject! x :instant-out-of-portable-range)))]
       (millis->utc-text x millis))
     :cljs
     (let [millis (.getTime ^js x)]
       (if (js/isNaN millis)
         (reject! x :invalid-instant)
         (millis->utc-text x millis)))))

(defn- canonical-instant-text?
  "True iff `s` is EXACTLY the canonical millisecond-UTC spelling of a real
  instant in the portable range — the fixed-shape regex PLUS a host round-trip
  (parse → reformat → compare). The round-trip rejects a shape-valid but
  IMPOSSIBLE spelling that a host clock silently folds (hour 24, a leap
  second) or that denotes a different day (Feb 30), and an out-of-range year."
  [s]
  (boolean
    (and (string? s)
         (re-matches instant-text-re s)
         (let [millis #?(:clj  (try (.toEpochMilli (Instant/parse s))
                                    (catch Exception _ nil))
                         :cljs (let [t (.getTime (js/Date. s))]
                                 (when-not (js/isNaN t) t)))]
           (and millis
                (portable-instant-millis? millis)
                (= s #?(:clj  (.format rfc3339-millis-utc (Instant/ofEpochMilli millis))
                        :cljs (.toISOString (js/Date. millis)))))))))

(defn- tagged-instant-head?
  "True iff `x` is a vector headed by the reserved `instant-marker` — a
  tagged-instant CANDIDATE. A candidate is validated strictly
  (`tagged-instant-text`) and never treated as a generic vector."
  [x]
  (and (vector? x)
       (= instant-marker (nth x 0 nil))))

(defn- tagged-instant-text
  "Validate a tagged-instant candidate and return its canonical UTC text, or
  fail closed with `:invalid-canonical-instant` (wrong arity, or a
  non-string / non-canonical / impossible-date payload)."
  [x]
  (if (and (= 2 (count x))
           (canonical-instant-text? (nth x 1)))
    (nth x 1)
    (reject! x :invalid-canonical-instant)))

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
  ;; rather than silently serialize colliding key tokens.
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
    (instant-value? x)  (str "t:" (host-instant->utc-text x))
    ;; The reserved tagged-instant tuple encodes to the SAME `t:<text>` token
    ;; as the host instant it denotes — so `canonical-bytes (canonical x)` =
    ;; `canonical-bytes x` — and is checked BEFORE the generic vector branch so
    ;; a malformed tuple fails closed rather than encoding as a plain vector.
    (tagged-instant-head? x) (str "t:" (tagged-instant-text x))
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
    ;; the whole list-kind.
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
  normalize to the same `[:rf.identity/instant <text>]` tuple, as does a host
  instant and its already-tagged tuple), which would silently collapse two
  identity-distinct entries into one. Per Conventions §Map key
  canonicalization (\"Duplicate canonical keys are invalid and MUST be
  rejected before the value becomes a cache key, route identity, or work
  id\") this rejects the whole identity closed — the same fail-closed rule
  `canonical-bytes` enforces, so the two surfaces never diverge. An instant key
  and a look-alike STRING key are DISTINCT canonical keys (the tuple carries
  the instant kind), so a heterogeneous instant+string-keyed map is a legal
  multi-entry map, not a duplicate."
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
  canonical value. An instant normalizes to the reserved tagged tuple
  `[:rf.identity/instant \"<millisecond-precision UTC text>\"]`, which
  `canonical-bytes` encodes to the SAME `t:<text>` token as the host instant it
  denotes — so `canonical` and `canonical-bytes` share ONE canonical form and a
  value stored via `canonical` can never disagree with the same value compared
  via `canonical-bytes`. Because the tuple carries the instant KIND, an instant
  key and a look-alike STRING key stay DISTINCT on both surfaces:
  `(canonical {#inst \"…\" :a, \"…\" :b})` is a legal two-entry map, not a
  collision. Fails closed with `:rf.error/non-edn-identity` for any
  out-of-domain value — the validation walk runs eagerly so a host handle
  buried anywhere in the structure is rejected, never host-stringified — and
  for a map carrying DUPLICATE canonical keys (two distinct host keys whose
  CEDN-1 bytes collide, e.g. a `java.util.Date` and a `java.time.Instant`, or a
  host instant and its tagged tuple, for one millisecond).

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
    ;; reserved tagged tuple so `canonical` and `canonical-bytes` agree on a
    ;; single kind-preserving canonical form.
    (instant-value? value) [instant-marker (host-instant->utc-text value)]
    ;; An already-tagged tuple is validated and returned unchanged (idempotent)
    ;; — never reinterpreted as a generic vector. Checked BEFORE the vector
    ;; branch so a malformed tuple fails closed.
    (tagged-instant-head? value) [instant-marker (tagged-instant-text value)]
    (symbol? value)    value
    (integer? value)   (if (and (not (bad-number? value)) (safe-integer? value))
                         value
                         (reject! value :integer-out-of-safe-range))
    (number? value)    (reject! value :non-integer-number)
    (vector? value)    (mapv canonical value)
    (set? value)       (into #{} (map canonical) value)
    (map? value)       (canonical-map value)
    ;; `seq?` subsumes `list?` — one branch covers list-kind.
    (seq? value)       (apply list (map canonical value))
    :else              (reject! value :unsupported-host-value)))

(defn identical-identity?
  "True iff `a` and `b` denote the same canonical EDN fact — i.e. their
  CEDN-1 bytes are `=`. Both are encoded (and so fail closed if either is
  out of domain)."
  [a b]
  (= (canonical-bytes a) (canonical-bytes b)))
