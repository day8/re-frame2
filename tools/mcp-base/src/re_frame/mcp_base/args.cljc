(ns re-frame.mcp-base.args
  "Argument-coercion helpers for MCP tools. The parsers in this ns
  take an ALREADY-RESOLVED raw value (extracted by the consumer from
  its platform-specific args object: a JS object for pair2-mcp, a
  Clojure map for story-mcp/causa-mcp) and normalise it into the
  Clojure-side type the tool body expects.

  ## Cross-server convention

  Argument names and default postures are a cross-MCP convention. An
  agent that learns `:dedup` defaults true on pair2-mcp must see the
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

(defn- parse-int*
  "Shared helper for `parse-positive-int` / `parse-non-negative-int`.
  `floor` is the lower clamp (1 for positive, 0 for non-negative); the
  three input arms (int / number / string + try/catch) are identical
  across both call sites."
  [raw default floor]
  (cond
    (nil? raw)
    default

    (integer? raw)
    (max floor (long raw))

    (number? raw)
    (max floor (long raw))

    (string? raw)
    #?(:clj (try
              (max floor (Long/parseLong (str/trim raw)))
              (catch NumberFormatException _ default))
       :cljs (let [n (js/parseInt raw 10)]
               (if (and (number? n) (not (js/isNaN n)))
                 (max floor (long n))
                 default)))

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
;; Keyword coercion — bounded-allowlist gate (rf2-ih7g4)
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
;;   - `parse-keyword` — the legacy permissive parser. Still INTERNS;
;;                       documented as a registry-backed-id helper.
;;                       Callers MUST gate by membership against a
;;                       bounded set at the lookup site (e.g. probe
;;                       the registry; absent ⇒ reject). Eventually a
;;                       follow-on bead retires this in favour of
;;                       `safe-keyword` everywhere.
;;
;; `parse-mode` is fixed here — every existing caller passes a finite
;; `recognised` set, so the gate lifts cleanly with no surface change.

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

  ## Why this exists (rf2-ih7g4)

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

(defn parse-keyword
  "Read a keyword from an agent-supplied argument. MCP arguments
  arrive as JSON, so keyword-typed ids come in as strings. Strips a
  leading `:` if present (some agents may serialise EDN-ish). Returns
  nil for nil / blank input.

  Namespaced keywords are supported (`\"ns/name\"` ⇒ `:ns/name`).

  ## Interning caveat (rf2-ih7g4)

  This fn INTERNS — `(keyword raw-string)` on the JVM permanently
  enlarges the global keyword table. Use ONLY where the value is
  destined for a registry lookup with a bounded known set (variant-id,
  substrate id, etc.) and the caller probes the registry immediately
  after parsing. For finite option sets (e.g. `:diff`/`:full` modes),
  prefer `safe-keyword` — which never interns outside the allowlist.

  Callers SHOULD treat the result as a candidate that MUST be checked
  against a bounded set before being used as a long-lived identifier."
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

(defn parse-mode
  "Normalise a possibly-string MCP arg into one of a small set of
  mode keywords. Accepts strings (`\"diff\"`/`\"full\"` etc),
  keywords (passthrough if recognised), or nil. Unrecognised values
  fall back to `default`.

  `recognised` is a set of accepted keywords (e.g. `#{:diff :full}`)
  — the bounded allowlist.

  ## Bounded-allowlist gate (rf2-ih7g4)

  Routes through `safe-keyword` so an unrecognised agent-supplied
  string never interns a fresh JVM keyword. The previous implementation
  called `parse-keyword` on every input (interning), then membership-
  checked. With this fix the membership check happens BEFORE the
  intern, eliminating the unbounded-growth DoS path for mode-shaped
  args (which are the vast majority of finite-set MCP args)."
  [raw default recognised]
  (cond
    (nil? raw)                       default
    (contains? recognised raw)       raw
    :else                            (or (safe-keyword raw recognised)
                                         default)))
