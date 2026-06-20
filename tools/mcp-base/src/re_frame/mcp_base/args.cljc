(ns re-frame.mcp-base.args
  "Argument-coercion helpers for MCP tools. The parsers in this ns
  take an ALREADY-RESOLVED raw value (extracted by the consumer from
  its platform-specific args object: a JS object for re-frame2-pair-mcp, a
  Clojure map for story-mcp) and normalise it into the
  Clojure-side type the tool body expects.

  ## Cross-server convention

  Argument names and default postures are a cross-MCP convention. An
  agent that learns `:dedup` defaults true on re-frame2-pair-mcp must see the
  same default everywhere. These parsers encode the defaults so they
  can't drift across consumers."
  (:refer-clojure :exclude [parse-boolean])
  (:require [clojure.string :as str]))

(defn parse-boolean
  "Normalise a possibly-string MCP arg into a boolean. Accepts
  booleans (passthrough), strings (`\"true\"`/`\"false\"`/`\"1\"`/
  `\"0\"`/`\"yes\"`/`\"no\"`, case-insensitive), keywords
  (`:true`/`:false`), or nil. Unrecognised values fall back to
  `default`.

  Use a small wrapper at each call-site to bake the default and the
  rejection posture (default-suppress vs default-allow)."
  [raw default]
  (cond
    (nil? raw)       default
    (boolean? raw)   raw
    (= raw :true)    true
    (= raw :false)   false
    (string? raw)    (let [s (str/lower-case (str/trim raw))]
                       (cond
                         (contains? #{"true" "1" "yes" "y" "on"} s)   true
                         (contains? #{"false" "0" "no" "n" "off"} s)  false
                         :else default))
    :else            default))

(def ^:private int-string-re
  "Strict integer-string grammar: optional sign + one-or-more digits,
  nothing else. Trailing garbage (`\"12abc\"`) is rejected so the
  string arm parses byte-identically across hosts —
  `Long/parseLong` (JVM) already rejects trailing garbage; raw
  `js/parseInt` (CLJS) parses a numeric PREFIX (`\"12abc\"` ⇒ 12),
  which would diverge from the JVM default-fallback. Guarding both
  arms with this regex closes the divergence."
  #"[-+]?\d+")

;; ---------------------------------------------------------------------------
;; Cross-runtime finite/range guard.
;;
;; `(long raw)` is UNSAFE on out-of-domain numerics: on the JVM `##Inf`,
;; `##NaN`, and finite values past the long range (`1.0E20`) THROW
;; `IllegalArgumentException`, while `##NaN` quietly truncates to `0` — a
;; real, non-sentinel cap that locks the agent out. The string arm has
;; the same hazard: a digit string above the JS safe-integer ceiling
;; parses on the JVM (`Long/parseLong "9007199254740992"`) but defaults
;; on CLJS (`Number.isSafeInteger` rejects it), so without a shared guard
;; the SAME wire arg would behave differently per host.
;;
;; The cross-runtime-safe domain is the JS safe-integer range
;; (`±(2^53 − 1)`). It is the strictest of the two hosts (a JVM long is
;; wider), and the MCP arg surface only ever carries small ints
;; (pagination limits, token caps) — so clamping the admissible domain to
;; what BOTH hosts represent losslessly costs nothing real and makes the
;; coercion host-independent. `coerce-finite-long` is the single guard
;; both the numeric arm here and `cap/max-tokens` reuse: it returns a
;; `long` for an in-domain value and `nil` for anything non-finite,
;; non-integral-in-range, or out of the safe-integer window. Callers map
;; the `nil` to their own out-of-domain posture (default here, reject in
;; `cap/max-tokens`).

(def ^:private max-safe-integer
  "The JS safe-integer ceiling, `2^53 − 1` (9 007 199 254 740 991). The
  cross-runtime domain boundary: a value strictly inside `[-max, max]`
  round-trips losslessly through both a JS `number` and a JVM `long`."
  9007199254740991)

(defn coerce-finite-long
  "Coerce a number to a `long` IFF it is finite and within the JS
  safe-integer window `[-(2^53−1), 2^53−1]`; otherwise return `nil`.

  This is the cross-runtime arg-boundary guard. It must be
  called BEFORE `(long raw)` on any caller-supplied numeric, because
  `(long ##Inf)` / `(long 1.0E20)` THROW on the JVM and `(long ##NaN)`
  truncates to a real `0`. A fractional in-range value is floored toward
  zero via `long` (the documented benign floor — e.g. `2.9` ⇒ `2`); a
  fractional that floors below the domain is still in-range and floors
  normally. Returns `nil` for `##NaN`, `##Inf`, `##-Inf`, and any finite
  magnitude `>= 2^53`.

  Non-number input is a caller error — gate with `number?` first."
  [n]
  (when (and #?(:clj  (not (or (Double/isNaN (double n))
                               (Double/isInfinite (double n))))
               :cljs (and (not (js/isNaN n))
                          (js/isFinite n)))
             (<= (- max-safe-integer) n max-safe-integer))
    (long n)))

(defn- parse-int*
  "Shared helper for `parse-positive-int` / `parse-non-negative-int`.
  `floor` is the lower clamp (1 for positive, 0 for non-negative).
  Two input arms: a numeric `raw` is finite/range-guarded then floored
  (out-of-domain — `##Inf` / `##NaN` / past the safe-integer window —
  falls back to `default` rather than throwing or truncating to a real
  value); a string `raw` is strict-parsed (must match `int-string-re`
  end-to-end, then sit inside the same safe-integer window — trailing
  garbage and out-of-range magnitudes fall back to `default`, identically
  on JVM and CLJS). Every other shape falls
  back to `default`."
  [raw default floor]
  (cond
    (nil? raw)
    default

    (number? raw)
    (if-let [n (coerce-finite-long raw)]
      (max floor n)
      default)

    (string? raw)
    (let [s (str/trim raw)]
      (if-not (re-matches int-string-re s)
        default
        ;; Parse to a number, then route through the SAME finite/range
        ;; guard the numeric arm uses so the string and numeric arms agree
        ;; AND the two hosts agree. Without it, on the JVM a digit string
        ;; past the long range throws `NumberFormatException`; on CLJS a
        ;; digit string past the safe-integer ceiling silently becomes a
        ;; lossy float. Reading both as a BigInteger / BigInt and feeding
        ;; the safe-integer guard gives one cross-host rejection threshold
        ;; (the JS safe-integer window) instead of two divergent ones.
        #?(:clj  (let [n (try (bigint s) (catch NumberFormatException _ nil))]
                   (if (and n (<= (- max-safe-integer) n max-safe-integer))
                     (max floor (long n))
                     default))
           :cljs (let [n (js/parseInt s 10)]
                   (if (and (not (js/isNaN n))
                            (js/Number.isSafeInteger n))
                     (max floor (long n))
                     default)))))

    :else
    default))

(defn parse-positive-int
  "Normalise a possibly-string MCP arg into a positive integer. Accepts
  ints (passed through, clamped to ≥1), strings (parsed; non-numeric
  falls back to `default`), or nil (returns `default`). Non-positive
  numeric inputs clamp to 1.

  The `default` is the convention's documented cap (e.g. 50 for cursor
  pagination, 5000 for token caps)."
  [raw default]
  (parse-int* raw default 1))

(defn parse-non-negative-int
  "Like `parse-positive-int` but admits zero (useful when zero means
  \"disabled\" — e.g. max-tokens=0 disables the cap)."
  [raw default]
  (parse-int* raw default 0))

;; ---------------------------------------------------------------------------
;; Keyword coercion — bounded-allowlist gate
;; ---------------------------------------------------------------------------
;;
;; JVM keywords are interned in a global table that NEVER shrinks. A
;; long-running Clojure MCP server that interns one fresh keyword per
;; agent call grows that table without bound — eventually OOM. This is
;; a DoS surface even without a hostile actor: a careless agent that
;; uses random-uuid-shaped variant ids will sink the JVM over a long
;; session.
;;
;; The mitigation is: NEVER `(keyword raw-agent-string)` without first
;; checking the string against a bounded allowlist or set membership.
;;
;; Two primitives live here:
;;
;;   - `safe-keyword`  — the strict gate. Returns `nil` for strings
;;                       outside the allowed set; never interns a fresh
;;                       keyword. The right primitive for finite option
;;                       sets and registry-backed lookups.
;;
;;   - `fresh-keyword` — the policy-gated intern. INTERNS by design (the
;;                       call site is allocating a new identifier, not
;;                       resolving an existing one). Only callable when
;;                       the surrounding context has already bounded the
;;                       allocation cost — typically an operator-gated
;;                       write path (`--allow-writes`) whose registrar
;;                       enforces a grammar over the input. The
;;                       fresh-id posture is carried on the name itself
;;                       rather than in a warning-laden docstring.
;;
;; `parse-mode` routes through `safe-keyword` — every caller passes a
;; finite `recognised` set, so the bounded gate fits cleanly.

(defn- normalise-keyword-string
  "Internal: strip a leading `:` from a string and split a namespaced
  form (`\"ns/name\"` ⇒ `[\"ns\" \"name\"]`). Returns nil for blank
  input.

  Output shape:
    - blank input            ⇒ nil
    - bare `\"name\"`        ⇒ `[nil \"name\"]`
    - namespaced `\"ns/name\"` ⇒ `[\"ns\" \"name\"]`

  The two-string form is the input to either `find-keyword` (safe
  lookup, no intern) or `keyword` (interning constructor)."
  [^String s]
  (let [s' (if (and (> (count s) 0) (= \: (.charAt s 0)))
             (subs s 1)
             s)]
    (cond
      (str/blank? s')         nil
      (str/includes? s' "/")  (let [parts (str/split s' #"/" 2)]
                                [(first parts) (second parts)])
      :else                   [nil s'])))

(defn safe-keyword
  "Bounded-allowlist keyword resolver. Accepts a string or keyword and
  resolves it against `allowed` (a finite set of accepted keywords).
  Returns the matching keyword from `allowed`, or `nil` when the input
  is outside the set.

  ## Why this exists

  JVM keywords are interned in a global table that never shrinks.
  Calling `(keyword raw-agent-string)` on caller-supplied input lets an
  unbounded stream of unique strings permanently grow that table —
  a slow-burn DoS on long-lived MCP servers. `safe-keyword` checks set
  membership BEFORE constructing the keyword, so a caller-supplied
  string outside the set never interns.

  ## Implementation note — `find-keyword`

  On the JVM `find-keyword` returns an existing interned keyword or
  `nil` WITHOUT interning. We use it to coerce the string to a keyword
  for the membership check; the literal keywords in `allowed` were
  already interned when `allowed` was defined (at compile time, in
  source), so the lookup always finds them when the input matches.

  On CLJS keywords are not interned in the same JVM-table sense, so
  the DoS doesn't apply; the same `safe-keyword` shape works there via
  `keyword` (the membership check still rejects unknowns the same
  way).

  ## Inputs

    - keyword     — passed through iff `(contains? allowed v)`.
    - string      — leading `:` stripped, then resolved via
                    `find-keyword` (CLJ) / `keyword` (CLJS); accepted
                    iff in `allowed`.
    - everything else (nil, number, map, …) ⇒ nil."
  [v allowed]
  (cond
    (keyword? v)
    (when (contains? allowed v) v)

    (string? v)
    (when-let [[ns-part name-part] (normalise-keyword-string v)]
      (let [kw #?(:clj  (if ns-part
                          (find-keyword ns-part name-part)
                          (find-keyword name-part))
                  :cljs (if ns-part
                          (keyword ns-part name-part)
                          (keyword name-part)))]
        (when (and kw (contains? allowed kw))
          kw)))

    :else nil))

(defn fresh-keyword
  "Coerce an agent-supplied id into a keyword, INTERNING it. The
  primitive for paths whose allocation cost is **bounded by some
  other gate** — either an operator-gated write path that allocates a
  fresh identifier, or a read path whose universe of acceptable
  identifiers is constrained by a runtime registry. Strips a leading
  `:` if present (some agents may serialise EDN-ish). Returns nil for
  nil / blank input.

  Namespaced keywords are supported (`\"ns/name\"` ⇒ `:ns/name`).

  ## When to call

  JVM keywords are interned in a never-shrinking global table. Every
  `fresh-keyword` call permanently grows that table by one slot for a
  hitherto-unseen input. The policy is **bounded-cost allocation** —
  reserve this primitive for sites where some other gate caps the
  number of fresh keywords an attacker can mint:

    - **Operator-gated write paths** that allocate a NEW identifier —
      story-mcp's `--allow-writes` flag plus `:story.<path>/<name>`
      grammar bound; `register-variant`'s `:variant-id`,
      `record-as-variant`'s `:new-variant-id`.
    - **Runtime-bounded read paths** that resolve against a FINITE
      registry — re-frame2-pair-mcp's frame-id coercion. The registry's
      live size caps the allocation; an agent passing arbitrary
      strings can mint at most one new keyword per registered frame.
    - **Grammar-bounded registrars** (e.g. `:story.<path>/<name>` in
      `assert-id!`) — the grammar constrains the per-id allocation
      cost regardless of read/write direction.

  For finite option sets (mode keywords, slice keywords), use
  `safe-keyword` against a finite allowlist; `fresh-keyword` is the
  wrong primitive there — it would intern every typo the agent sent."
  [v]
  (cond
    (keyword? v) v
    (nil? v)     nil
    (string? v)
    (when-let [[ns-part name-part] (normalise-keyword-string v)]
      (if ns-part
        (keyword ns-part name-part)
        (keyword name-part)))
    :else nil))

(def ^:private default-fresh-keyword-max-len
  "Upper bound on the combined `ns` + `name` character count a
  `fresh-keyword-checked` id may carry before it is rejected WITHOUT
  interning. A keyword id is a short symbolic label; a multi-kilobyte
  string is not a legitimate id and minting it would grow the JVM
  keyword table by an arbitrarily large slot. 512 is generous for any
  real `:story.<path>/<name>` while still capping the per-attempt cost."
  512)

(defn fresh-keyword-checked
  "Like `fresh-keyword`, but validate the DECOMPOSED `[ns-part name-part]`
  string shape against `shape-ok?` (and a length cap) BEFORE interning —
  so an id that fails the caller's grammar leaves NO keyword in the JVM
  table. Returns the interned keyword on success, or `nil`
  when the input is nil / blank / over-long / fails `shape-ok?` (the
  caller maps `nil` to its own structured error).

  `fresh-keyword` interns first and lets a downstream registrar reject on
  grammar — but the reject happens AFTER the intern, so a hostile or
  malfunctioning client can permanently grow the keyword table through
  failed write attempts even though each correctly returns an error. The
  grammar check must run on the STRING shape, before the keyword is
  constructed. `shape-ok?` receives `[ns-part name-part]` (either may be
  `nil` for a bare name) and returns truthy iff the id is admissible.

  `:max-len` (default 512) caps the combined `ns`+`name` length — a
  defence against a single enormous-id intern even when the grammar
  itself is permissive.

  On CLJS keywords are not interned in the JVM-table sense, so the
  growth concern does not apply; the same shape gate runs for behavioural
  parity (a malformed id is rejected identically on both hosts)."
  ([v shape-ok?] (fresh-keyword-checked v shape-ok? default-fresh-keyword-max-len))
  ([v shape-ok? max-len]
   (cond
     (keyword? v)
     (when (shape-ok? [(namespace v) (name v)]) v)

     (nil? v) nil

     (string? v)
     (when-let [[ns-part name-part] (normalise-keyword-string v)]
       (let [len (+ (count (or ns-part "")) (count (or name-part "")))]
         (when (and (<= len max-len)
                    (shape-ok? [ns-part name-part]))
           (if ns-part
             (keyword ns-part name-part)
             (keyword name-part)))))

     :else nil)))

(defn parse-mode
  "Normalise a possibly-string MCP arg into one of a small set of
  mode keywords. Accepts strings (`\"diff\"`/`\"full\"` etc),
  keywords (passthrough if recognised), or nil. Unrecognised values
  fall back to `default`.

  `recognised` is a set of accepted keywords (e.g. `#{:diff :full}`)
  — the bounded allowlist.

  ## Bounded-allowlist gate

  Routes through `safe-keyword` so an unrecognised agent-supplied
  string never interns a fresh JVM keyword. The bounded membership
  check happens BEFORE any intern, so there is no unbounded-growth DoS
  path for mode-shaped args (which are the vast majority of finite-set
  MCP args)."
  [raw default recognised]
  (cond
    (nil? raw)                       default
    (contains? recognised raw)       raw
    :else                            (or (safe-keyword raw recognised)
                                         default)))
