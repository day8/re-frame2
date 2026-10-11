(ns re-frame.ssr.wire
  "The EDN wire-crossing predicates the server shares between the payloads it
  sends to a browser.

  A value the server `pr-str`s must read back EQUAL on the browser, and some
  JVM values do not: a fn or host object prints a literal the safe reader has
  no constructor for, and a JVM-only number (a ratio, a bigdec, a bigint, a
  float, an integer past `2^53 - 1`) reads back as a DIFFERENT number without
  any error. And a map or set can hold two JVM numbers the browser reads as
  ONE (`1` and `1.0`), so its reader rejects the duplicate. `edn-carryable?`
  answers the whole question for one value; `portable-number?` answers its
  numeric type / range half and `numeric-collision` its collection half, and
  the hydration payload's crossing check reuses both so the two wires cannot
  drift apart on what crosses (Spec 011 §The numeric crossing rule).")

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

(defn- browser-number
  "The one double the browser holds for the number `v`. The browser also has
  a single zero (`-0.0` is `=` to `0`), so every zero folds to `0.0`."
  [v]
  (let [d (double v)]
    (if (zero? d) 0.0 d)))

(defn- browser-value
  "`v` as the browser reads it: every number its `browser-number`, to any
  depth, with every map, set and sequential keeping its shape. Two values the
  JVM holds apart are `=` after this exactly when the browser reads them as
  one. Used only to compare siblings; never emitted."
  [v]
  (cond
    (number? v)     (browser-number v)
    (record? v)     v
    (map? v)        (into {} (map (fn [[k x]] [(browser-value k) (browser-value x)])) v)
    (set? v)        (into #{} (map browser-value) v)
    (sequential? v) (mapv browser-value v)
    :else           v))

(defn numeric-collision
  "-> data naming the first map whose distinct KEYS, or set whose distinct
  MEMBERS, the browser reads as ONE value, at any depth in `v`; else nil.

  Every number can cross on its own while the collection holding it cannot:
  the JVM keeps `1` and `1.0`, and `0` and `-0.0`, as distinct keys, but the
  browser holds one double per number and one zero, so its EDN reader meets
  a duplicate and refuses the whole read. A composite key collapses the same
  way (`[1]` beside `[1.0]`), so siblings are compared whole, by
  `browser-value`.

  The data: `:path` from `v`'s root to the colliding map or set — a map value
  adds its key and a sequential element its index, while a map key or set
  member adds nothing, so a collision inside one names the collection holding
  it; `:collision` (`:map-keys` / `:set-members`); `:collapses`, the distinct
  values; and `:browser-value`, the one value they become. A record is not
  walked: it prints as a tagged literal the reader refuses on its own. On
  CLJS every number is already a double, so this always answers nil there."
  ([v] (numeric-collision v []))
  ([v path]
   (letfn [(collapse [kind xs]
             (some (fn [[shared members]]
                     (when (next members)
                       {:path          path
                        :collision     kind
                        :collapses     (vec members)
                        :browser-value shared}))
                   (group-by browser-value xs)))]
     (cond
       (record? v)     nil
       (map? v)        (or (collapse :map-keys (keys v))
                           (some (fn [[k x]]
                                   (or (numeric-collision k path)
                                       (numeric-collision x (conj path k))))
                                 v))
       (set? v)        (or (collapse :set-members v)
                           (some #(numeric-collision % path) v))
       (sequential? v) (first (keep-indexed (fn [i x] (numeric-collision x (conj path i))) v))
       :else           nil))))

(defn- carryable-alone?
  "`edn-carryable?` for each value on its own: scalars and collections of
  carryable values, with no question asked of a collection as a whole."
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
    (map? v)     (every? (fn [[k v']] (and (carryable-alone? k) (carryable-alone? v'))) v)
    (coll? v)    (every? carryable-alone? v)
    :else        false))

(defn edn-carryable?
  "Can `v` ride an EDN wire to the browser? Scalars (nil / boolean / number
  / string / keyword / symbol) and collections of carryable values, where no
  map or set holds two numbers the browser reads as one. A fn, a host object,
  or any other opaque value cannot, and a caller refuses it rather than
  silently dropping it.

  The question this answers is exactly `(= v (read (pr-str v)))` under
  the BUNDLED safe reader ON EITHER HOST — not \"is `v` map-shaped\", not
  \"is `v` a number\", and not \"is each value carryable\". Those three
  distinctions are what the `record?` and `wire-number?` arms of
  `carryable-alone?` and the `numeric-collision` clause exist for."
  [v]
  (and (carryable-alone? v)
       ;; Only a JVM collection can hold two numbers the browser reads as one.
       #?(:clj  (nil? (numeric-collision v))
          :cljs true)))
