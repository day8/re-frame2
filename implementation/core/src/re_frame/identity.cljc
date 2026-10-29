(ns re-frame.identity
  "Internal canonical-EDN-identity algebra (EP-0012). The normative
  contract lives in [`spec/Conventions.md` §Canonical EDN identity];
  this namespace is the reference implementation of the `CEDN-1`
  encoding.

  ## Status — INTERNAL (EP-0012 disposition 1)

  The *semantics* are normative; the *names* are NOT public API. No
  `re-frame.core` facade export, no classification. Resources, routing,
  attempt identity, and schema digests are the consumers; a name graduates
  to public once two-plus of them use it through this namespace unchanged.

  ## Why canonical identity, not stringification

  `str`, `pr-str` over unordered host maps, `JSON.stringify`, and object
  identity are NOT valid framework identity contracts — they differ by
  host, leak insertion order, or depend on references. Resource caches,
  work ids, route params, and epoch/replay records need an identity that
  survives SSR, hydration, replay, Xray inspection, and multi-host
  conformance. Canonical EDN is the smallest Clojure-native answer.

  ## Identity vs digest (EP-0012 disposition 5)

  The canonical EDN value IS the identity everywhere — storage, attempt
  records, traces, epoch/replay. `canonical` returns that normalized
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
;; fixed-width 4-digit year down to `0000`.
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

;; Portable instant range: the four-digit-year
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

(defn portable-instant?
  "True iff host instant `x` (java.util.Date / java.time.Instant / js/Date)
  denotes a real moment in the portable range — exactly the host instants
  `canonical-bytes` encodes rather than rejects. `re-frame.path/segment?` keys
  off it, so a path segment is never an instant canonical identity refuses."
  [x]
  (let [millis #?(:clj  (let [^Instant inst (if (instance? Instant x) x (.toInstant ^Date x))]
                          (try (.toEpochMilli inst)
                               (catch ArithmeticException _ nil)))
                  :cljs (let [m (.getTime ^js x)]
                          (when-not (js/isNaN m) m)))]
    (boolean (and millis (portable-instant-millis? millis)))))

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

;; ---- well-formed text ----------------------------------------------------
;;
;; CEDN-1 is a UTF-8 token stream, and a UTF-16 surrogate that is not half of
;; a pair has no UTF-8 form: a host encoder substitutes it (`?` on the JVM,
;; U+FFFD in JavaScript), so a digest over the bytes would equal an ordinary
;; string's and differ across hosts. Text holding one is outside the domain
;; (Conventions §Canonical byte encoding).

(defn- code-unit-at [s i]
  #?(:clj  (int (.charAt ^String s (int i)))
     :cljs (.charCodeAt s i)))

(defn- unpaired-surrogate?
  "True iff `s` holds a high surrogate (U+D800-U+DBFF) not followed by a low
  one (U+DC00-U+DFFF), or a low surrogate not preceded by a high one."
  [s]
  (let [n #?(:clj (.length ^String s) :cljs (.-length s))]
    (loop [i 0]
      (if (< i n)
        (let [c (code-unit-at s i)]
          (cond
            (< c 0xD800) (recur (inc i))
            (< c 0xDC00) (if (and (< (inc i) n)
                                  (<= 0xDC00 (code-unit-at s (inc i)) 0xDFFF))
                           (recur (+ i 2))
                           true)
            (< c 0xE000) true
            :else        (recur (inc i))))
        false))))

(defn well-formed-text?
  "True iff `x` (a string, keyword or symbol) holds no unpaired surrogate —
  for a keyword or symbol, in neither its namespace nor its name. This is the
  SHARED text predicate the CEDN-1 encoder and the `:rf/path` concrete-segment
  domain both key off, so `re-frame.path/segment?` never admits text
  `canonical-bytes` rejects."
  [x]
  (if (string? x)
    (not (unpaired-surrogate? x))
    (not (or (unpaired-surrogate? (name x))
             (some-> (namespace x) unpaired-surrogate?)))))

(defn- well-formed-text
  "Return `x` (a string, keyword or symbol), or fail closed with
  `:unpaired-surrogate` when it is not `well-formed-text?`."
  [x]
  (if (well-formed-text? x)
    x
    (reject! x :unpaired-surrogate)))

;; ---- canonical token encoding (CEDN-1) -----------------------------------
;;
;; Encode into a UTF-8 token string. A string is its EDN literal via `pr-str`;
;; a keyword or symbol is its EDN token when plain and a quoted form otherwise
;; (below); the type tag preceding each value keeps distinct EDN kinds
;; distinct.

(declare encode)

(defn- encode-string [s]
  ;; `pr-str` writes the canonical EDN string literal identically on CLJ and
  ;; CLJS: seven characters escaped, every other character raw (Conventions
  ;; §Canonical byte encoding).
  (str "s:" (pr-str (well-formed-text s))))

;; A keyword or symbol token has no closing delimiter, and the element
;; separator is a space, so a name printed raw must never hold a character a
;; reader ends a token on: `(keyword "a b:1")` raw is `k::a b:1`, and a vector
;; of it would share its bytes with `[:a true]`. A PLAIN keyword or symbol is
;; written as its EDN token, which reads back as the same value on both hosts;
;; any other is QUOTED, its namespace and name each as a string literal, so
;; every keyword and symbol has one token and no two share one. The plain
;; grammar is ASCII because the two hosts' readers disagree about which
;; non-ASCII characters end a token (Conventions §Canonical byte encoding).

(def ^:private plain-part-re
  "One plain namespace or name: ASCII letters, digits and
  `* ! _ ? $ % & = < > + - . #`, beginning with neither a digit nor `#`, nor
  with `+`, `-` or `.` followed by a digit."
  #"(?:[A-Za-z*!_?$%&=<>]|[-+.](?![0-9]))[A-Za-z0-9*!_?$%&=<>+.#-]*")

(defn- plain-part? [s]
  (some? (re-matches plain-part-re s)))

(defn- plain-ident?
  "True iff keyword or symbol `x` is plain: its namespace, if any, and its name
  are plain parts, and it is not a bare symbol a reader takes for a literal
  (`nil`, `true`, `false`)."
  [x]
  (let [ns (namespace x)
        n  (name x)]
    (and (plain-part? n)
         (or (nil? ns) (plain-part? ns))
         (not (and (nil? ns) (symbol? x) (contains? #{"nil" "true" "false"} n))))))

(defn- ident-token
  "The token of keyword or symbol `x` after its type tag. Plain: the EDN token,
  which `pr-str` prints for a keyword in its single-colon fully-qualified
  form, never `::` shorthand. Otherwise quoted: the namespace's string literal
  and `/`, when there is a namespace, then the name's string literal."
  [x]
  (if (plain-ident? x)
    (pr-str x)
    (let [x (well-formed-text x)]
      (str (when-let [ns (namespace x)] (str (pr-str ns) "/"))
           (pr-str (name x))))))

(defn- encode-keyword [k]
  (str "k:" (ident-token k)))

(defn- encode-symbol [y]
  (str "y:" (ident-token y)))

(defn- encode-elements
  "Encode a sequence of already-canonical-ordered elements, space-joined.
  Composite encodings separate adjacent element tokens with a single
  ASCII space (Conventions §Canonical byte encoding)."
  [xs]
  (str/join " " (map encode xs)))

(defn- code-unit-rank
  "Rank a UTF-16 code unit so that, at the FIRST unit where two strings
  differ, comparing ranks gives their code-point order. A surrogate (half of
  a code point at or above U+10000) ranks above every unit in U+E000-U+FFFF;
  everything else keeps its relative order."
  [c]
  (cond
    (< c 0xD800) c
    (< c 0xE000) (+ c 0x2000)
    :else        (- c 0x800)))

(defn compare-canonical-bytes
  "Compare two `canonical-bytes` token strings by their UTF-8 bytes — the
  order CEDN-1 sorts map keys and set elements by (Conventions §Canonical
  byte encoding). Returns a negative number, zero, or a positive number,
  like `compare`.

  UTF-8 byte order is code-point order, so nothing is encoded. The host
  string comparators order UTF-16 code units instead, and the two orders
  disagree in exactly one place: a supplementary character (a surrogate
  pair) against a BMP character at or above U+E000. `compare` puts U+10000
  (D800 DC00) before U+E000; UTF-8 (F0 90 80 80 against EE 80 80) puts it
  after. So this scans to the first differing code unit — the work `compare`
  does anyway — and ranks that one pair by code point."
  [a b]
  (let [la #?(:clj (.length ^String a) :cljs (.-length a))
        lb #?(:clj (.length ^String b) :cljs (.-length b))
        n  (min la lb)]
    (loop [i 0]
      (if (< i n)
        (let [ca #?(:clj (int (.charAt ^String a i)) :cljs (.charCodeAt a i))
              cb #?(:clj (int (.charAt ^String b i)) :cljs (.charCodeAt b i))]
          (if (== ca cb)
            (recur (inc i))
            (- (code-unit-rank ca) (code-unit-rank cb))))
        (- la lb)))))

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
                     (sort-by first compare-canonical-bytes))
        ks      (map first entries)]
    (when-not (= (count ks) (count (distinct ks)))
      (reject! m :duplicate-canonical-map-key))
    (str "m{"
         (str/join " " (mapcat (fn [[ke ve]] [ke ve]) entries))
         "}")))

(defn- encode-set [s]
  ;; Sort elements by their canonical element encoding's bytes.
  ;;
  ;; Duplicate canonical elements FAIL CLOSED, under the map-key rule and for
  ;; its reason: two DISTINCT host elements can encode to the SAME bytes (a
  ;; `java.util.Date` and a `java.time.Instant` for one millisecond, or a host
  ;; instant and its tagged tuple), and `canonical` would collapse them into
  ;; one element, so emitting both tokens would give the two surfaces two
  ;; identities for one value.
  (let [encoded (map encode s)]
    (when-not (= (count s) (count (distinct encoded)))
      (reject! s :duplicate-canonical-set-element))
    (str "q#{" (str/join " " (sort compare-canonical-bytes encoded)) "}")))

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
    (integer? x)        (if (safe-integer? x)
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
  outside the safe range, and a string, keyword or symbol holding an
  unpaired surrogate)."
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

(defn- canonical-set
  "Canonicalize a set's elements, failing closed when two DISTINCT source
  elements canonicalize to ONE — the rule `canonical-map` applies to keys, and
  the one `canonical-bytes` enforces on sets, so the two surfaces never
  diverge."
  [s]
  (let [out (into #{} (map canonical) s)]
    (when (< (count out) (count s))
      (reject! s :duplicate-canonical-set-element))
    out))

(defn canonical
  "Return the canonical normalized EDN identity of `value` — the storage,
  attempt, trace, and replay identity (EP-0012 disposition 5: canonical
  EDN IS the identity).

  The normalization recurses through maps, sets, vectors and lists,
  preserving vector / list order and EDN kind. It does not reorder map
  entries or set elements — CEDN-1 ordering belongs to `canonical-bytes` —
  and two map spellings that differ only in insertion order return an `=`
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
  for a map carrying DUPLICATE canonical keys or a set carrying DUPLICATE
  canonical elements (two distinct host values whose CEDN-1 bytes collide,
  e.g. a `java.util.Date` and a `java.time.Instant`, or a
  host instant and its tagged tuple, for one millisecond).

  Present `nil` stays distinct from a missing key: `(canonical {:page nil})`
  is not `(canonical {})`. `nil` elision, if a surface wants it, is a
  surface-specific policy applied BEFORE canonicalization."
  [value]
  (cond
    (nil? value)       nil
    (boolean? value)   value
    (string? value)    (well-formed-text value)
    (keyword? value)   (well-formed-text value)
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
    (symbol? value)    (well-formed-text value)
    (integer? value)   (if (safe-integer? value)
                         value
                         (reject! value :integer-out-of-safe-range))
    (number? value)    (reject! value :non-integer-number)
    (vector? value)    (mapv canonical value)
    (set? value)       (canonical-set value)
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
