(ns re-frame.ssr.wire
  "The EDN wire-crossing predicates the server shares between the payloads it
  sends to a browser.

  A value the server `pr-str`s must read back EQUAL on the browser, and some
  JVM values do not: a fn or host object prints a literal the safe reader has
  no constructor for, and a JVM-only number (a ratio, a bigdec, a bigint, a
  float, an integer past `2^53 - 1`) reads back as a DIFFERENT number without
  any error. `edn-carryable?` answers the whole question for one value, and
  `portable-number?` answers its numeric type / range half, which the
  hydration payload's crossing check reuses so the two cannot drift apart on
  which JVM numbers cross (Spec 011 §The numeric crossing rule).")

(def ^:const max-safe-integer
  "`2^53 - 1` — the largest integer a browser number (an IEEE-754 double)
  holds EXACTLY. Beyond it, consecutive integers share a representation:
  the CLJS reader turns `9007199254740993` into `9007199254740992` and
  says nothing."
  9007199254740991)

(defn portable-number?
  "Is the number `v` of a TYPE and RANGE the browser holds exactly? The type /
  range half of `wire-number?` below, shared with the hydration payload's
  crossing check (`re-frame.ssr.payload-policy`) so the two
  wires cannot drift apart on which JVM numbers cross.

  JVM: no Ratio, BigDecimal, BigInt / BigInteger or Float; integers only
  within ±`max-safe-integer`; every DOUBLE, `##NaN` and `##Inf` included —
  a double is already what the browser holds. CLJS: every number, since every
  CLJS number is already a double. What this leaves out is `wire-number?`'s
  NaN clause, which is about round-trip EQUALITY rather than about transport:
  NaN reads back as NaN, it just is not `=` to itself. See `wire-number?` for
  the table of what each refused type reads back as."
  [v]
  #?(:clj
     (cond
       (or (ratio? v) (decimal? v))    false
       (integer? v)                    (and (not (instance? clojure.lang.BigInt v))
                                            (not (instance? java.math.BigInteger v))
                                            (<= (- max-safe-integer) v max-safe-integer))
       (double? v)                     true
       :else                           false)
     :cljs
     (number? v)))

(defn- wire-number?
  "Can the number `v` cross the wire UNCHANGED? The whole predicate is
  the round-trip equality itself — `v` is admitted exactly when printing
  it here and reading it on the OTHER host yields an EQUAL value.

  The two hosts answer differently because they hold different numbers,
  not because there are two rules:

  **CLJS** — every number IS an IEEE-754 double, and the JVM reader
  reconstructs a double exactly, so every CLJS number crosses unchanged.
  The single exception is `##NaN`, which the property excludes on its
  own terms: NaN is not `=` to itself, so no NaN can satisfy a
  round-trip-EQUALITY test on any host.

  **JVM** — the server holds numeric types the browser has none of, and
  integers wider than a double can hold. Both cross badly, and both
  cross SILENTLY:

  | JVM value | prints | CLJS reads back |
  |---|---|---|
  | `9007199254740993N` (BigInt)  | `9007199254740993N` | `9007199254740992` |
  | `1.5M` (BigDecimal)           | `1.5M`   | `1.5` |
  | `1/3` (Ratio)                 | `1/3`    | `0.3333333333333333` |
  | `9007199254740993` (Long)     | same     | `9007199254740992` |
  | `(float 0.1)` (Float)         | `0.1`    | `0.1` ≠ the Float |

  So: no Ratio, BigDecimal, BigInt or Float; integers only within the
  safe range; no NaN. `Float` is rejected for failing the property on
  the JVM ALONE — `(= (float 0.1) (read \"0.1\"))` is already false,
  because the printed shortest-decimal names the *double* 0.1.

  A large DOUBLE (`1.0E308`) is fine and is not a contradiction: it is
  already an IEEE-754 double, so the far side reconstructs it bit for
  bit. The safe-integer bound is about REPRESENTABILITY, not magnitude —
  a Long past `2^53` carries precision no double can hold, while a
  double of any size carries only precision a double can hold.

  Rejecting rather than coercing follows the `record?` arm below: a
  BigInt silently arriving as a plain number, or a Ratio as an
  approximation, changes the value's TYPE between server and client,
  which is the very defect a fail-loud wire exists to prevent. The
  author narrows the value explicitly, so both hosts agree about what
  they are holding.

  The type / range rule is `portable-number?`, shared with the hydration
  payload; this adds only the NaN clause."
  [v]
  (and (portable-number? v)
       (not #?(:clj  (and (double? v) (Double/isNaN v))
               :cljs (js/Number.isNaN v)))))

(defn edn-carryable?
  "Can `v` ride an EDN wire to the browser? Scalars (nil / boolean / number
  / string / keyword / symbol) and collections of carryable values.
  A fn, a host object, or any other opaque value cannot, and a caller
  refuses it rather than silently dropping it.

  The question this answers is exactly `(= v (read (pr-str v)))` under
  the BUNDLED safe reader ON EITHER HOST — not \"is `v` map-shaped\", and
  not \"is `v` a number\". Those two distinctions are what the `record?`
  and `wire-number?` arms below exist for."
  [v]
  (cond
    (nil? v)     true
    (boolean? v) true
    ;; Not every number crosses. The JVM has numeric types and integer
    ;; widths the browser has none of, and each one arrives silently
    ;; WRONG rather than failing — see `wire-number?`.
    (number? v)  (wire-number? v)
    (string? v)  true
    (keyword? v) true
    (symbol? v)  true
    ;; A record satisfies `map?`, so without this arm it would take the map
    ;; branch, every entry would test carryable, and the record would be
    ;; declared wire-safe. But `pr-str` does not emit a record as a
    ;; map — it emits the TAGGED literal `#my.ns.R{:x 1}`, and the safe
    ;; reader has no constructor for that tag, so the body would fail to
    ;; read at the far end of the wire. The check must precede `map?`: a
    ;; record is map-LIKE but not map-PRINTING, and this predicate is about
    ;; the printed form.
    ;;
    ;; Rejecting rather than converting is deliberate. Reading the record
    ;; back as a plain map would silently change the value's TYPE between
    ;; server and client, which is the same class of defect one layer
    ;; down; the author converts explicitly (`(into {} r)`) so both hosts
    ;; agree about what they are holding.
    (record? v)  false
    (map? v)     (every? (fn [[k v']] (and (edn-carryable? k) (edn-carryable? v'))) v)
    (coll? v)    (every? edn-carryable? v)
    :else        false))
