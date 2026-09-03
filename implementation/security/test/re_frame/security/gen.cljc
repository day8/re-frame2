(ns re-frame.security.gen
  "Tiny deterministic generator substrate for the adversarial-property
  security tier (rf2-3cfvt).

  ## Why hand-rolled, not test.check

  test.check is not on the shadow `:node-test` classpath (it lives only
  on `tools/story`'s JVM `:test` alias), and the security tier MUST run
  under the always-on `npm run test:cljs` node gate AND a dedicated
  `npm run test:security` build. Pulling test.check onto the shadow
  classpath risks the bundle-isolation contract and is a hot-zone-adjacent
  dep change. So this ns supplies a small, dependency-free, seeded PRNG
  plus combinator generators - enough to draw HUNDREDS of inputs per
  property without a new dependency, fully cross-runtime (CLJC), and
  byte-deterministic (a failing draw reproduces from its seed).

  A generator here is a plain fn `(g rng) -> [value rng']` - it consumes
  the PRNG state and returns the drawn value plus the advanced state, so
  draws compose without shared mutable state. `for-all` runs a predicate
  over N draws and returns the first counter-example (or nil), so the
  property tests stay one-liners.

  This is NOT a test.check replacement - no shrinking, no rose trees. It
  is a deterministic fuzzer: a finite-but-combinatorially-large input
  space, swept reproducibly. For security boundaries that is the right
  shape - we want broad casing/shape coverage, and a hostile-input corpus
  (in each surface's test ns) pins the named regression cases exactly."
  (:refer-clojure :exclude [rand-nth])
  (:require [clojure.walk :as walk]))

;; ---------------------------------------------------------------------------
;; Seeded PRNG - a 32-bit LCG (numerical-recipes constants). Deterministic
;; and IDENTICAL across JVM + JS.
;;
;; ## Why the multiply needs host-specific care (rf2-h2yvs finding 2)
;;
;; The LCG step is `s' = (s * 1103515245 + 12345) mod 2^32`. The constant
;; `1103515245` is ~2^30, so for a 32-bit state `s` the product `s *
;; 1103515245` is ~2^62 BEFORE the mod-2^32 truncation. On the JVM `s` is a
;; `long` (64-bit) so `unchecked-multiply` holds the full product exactly and
;; the subsequent `bit-and … mask32` truncates correctly. On CLJS, though,
;; `unchecked-multiply` compiles to JS `*` on a double, which only has 53 bits
;; of mantissa - a ~2^62 product loses its low bits, so `(s * 1103515245) &
;; 0xFFFFFFFF` does NOT equal the true 32-bit product and the JS sequence
;; diverges from the JVM sequence after the first step (verified: seed 1 /
;; bound 100 gave JVM `54 24 56 95 …` vs the double path `54 25 12 28 …`).
;;
;; The portable primitive is `js/Math.imul`, which returns the low 32 bits of
;; the integer product (signed) exactly. We then coerce to an UNSIGNED 32-bit
;; state (`>>> 0` via `unsigned-bit-shift-right`) so both runtimes carry the
;; same `0 <= state < 2^32` convention - matching what the JVM `bit-and …
;; mask32` already yields (`mask32` is a long, so the JVM result is unsigned).
;; The `next-int` hi-bit extraction (`(bit-shift-right s' 8) & 0x7FFFFF`) is
;; sign-agnostic because the `& 0x7FFFFF` (23-bit) mask discards exactly the
;; bits a signed vs unsigned shift would differ in, so it needs no change.
;; State is an unsigned 32-bit integer on both hosts.
;; ---------------------------------------------------------------------------

(def ^:private mask32 0xFFFFFFFF)

(def ^:private lcg-mult 1103515245)
(def ^:private lcg-inc 12345)

(defn make-rng
  "Make a PRNG state (unsigned 32-bit) from an integer seed."
  [seed]
  #?(:clj  (bit-and (long seed) mask32)
     ;; `>>> 0` coerces to an unsigned 32-bit value, matching the JVM
     ;; `bit-and … mask32` convention (`mask32` is a long there).
     :cljs (unsigned-bit-shift-right (int seed) 0)))

(defn- next-state
  "Advance the LCG one step. Uses a portable 32-bit multiply: the JVM holds
  the full ~2^62 product in a `long` then truncates; CLJS uses `Math.imul`
  (exact low-32-bit product) then coerces to unsigned 32-bit. Both yield the
  identical unsigned-32 state sequence (rf2-h2yvs finding 2)."
  [current-state]
  #?(:clj  (bit-and (unchecked-add (unchecked-multiply (long current-state) lcg-mult) lcg-inc)
                    mask32)
     :cljs (unsigned-bit-shift-right (+ (js/Math.imul current-state lcg-mult) lcg-inc) 0)))

(defn next-int
  "Return `[n rng']` where `0 <= n < bound`. `bound` must be positive."
  [rng bound]
  (let [next-state-value (next-state rng)
        ;; take the high bits (low bits of an LCG are low-quality)
        high-bits (bit-and (bit-shift-right next-state-value 8) 0x7FFFFF)]
    [(mod high-bits bound) next-state-value]))

(defn rand-nth
  "Draw a uniformly-random element of `coll`. Returns `[elem rng']`."
  [rng coll]
  (let [collection-values (vec coll)
        [element-index next-rng] (next-int rng (count collection-values))]
    [(nth collection-values element-index) next-rng]))

;; ---------------------------------------------------------------------------
;; Generator combinators - `(g rng) -> [value rng']`.
;; ---------------------------------------------------------------------------

(defn gen-elem
  "Generator that draws a uniformly-random element of `coll`."
  [coll]
  (fn [rng] (rand-nth rng coll)))

(defn gen-int
  "Generator for an int in [lo, hi)."
  [lo hi]
  (fn [rng]
    (let [[drawn-offset next-rng] (next-int rng (- hi lo))]
      [(+ lo drawn-offset) next-rng])))

(defn gen-one-of
  "Generator that picks one of the supplied generators uniformly, then
  draws from it."
  [& gens]
  (fn [rng]
    (let [[selected-generator next-rng] (rand-nth rng (vec gens))]
      (selected-generator next-rng))))

(defn gen-fmap
  "Generator that maps `f` over `g`'s drawn value."
  [f g]
  (fn [rng]
    (let [[drawn-value next-rng] (g rng)]
      [(f drawn-value) next-rng])))

(defn gen-vec
  "Generator for a vector of `n` draws from `g` (n itself may be a draw -
  pass an int for fixed length, or a `[lo hi)` generator via `gen-int`)."
  [n-or-gen g]
  (fn [rng]
    (let [[draw-count initial-rng] (if (fn? n-or-gen) (n-or-gen rng) [n-or-gen rng])]
      (loop [draw-index 0, current-rng initial-rng, accumulated-values []]
        (if (< draw-index draw-count)
          (let [[drawn-value next-rng] (g current-rng)]
            (recur (inc draw-index) next-rng (conj accumulated-values drawn-value)))
          [accumulated-values current-rng])))))

(defn sample
  "Draw `n` values from generator `g` starting at `seed`. Returns a vector
  of the drawn values (state threaded internally). Deterministic in
  `(seed, g, n)`."
  ([g n] (sample g n 0))
  ([g n seed]
   (loop [draw-index 0, current-rng (make-rng seed), accumulated-values []]
     (if (< draw-index n)
       (let [[drawn-value next-rng] (g current-rng)]
         (recur (inc draw-index) next-rng (conj accumulated-values drawn-value)))
       accumulated-values))))

(defn for-all
  "Run `pred` over `n` draws of generator `g` (seeded at `seed`, default
  0). Returns `nil` when every draw satisfied `pred`, else a map
  `{:fail value :index i :seed seed}` describing the first counter-example
  - the seed + index reproduce it exactly. `pred` may throw; a throw is
  treated as a failure and the exception is captured under `:threw`."
  ([g n pred] (for-all g n 0 pred))
  ([g n seed pred]
   (loop [draw-index 0, current-rng (make-rng seed)]
     (if (< draw-index n)
       (let [[drawn-value next-rng] (g current-rng)
             [ok? thrown-exception] (try [(boolean (pred drawn-value)) nil]
                              (catch #?(:clj Throwable :cljs :default) e
                                [false e]))]
         (if ok?
           (recur (inc draw-index) next-rng)
           (cond-> {:fail drawn-value :index draw-index :seed seed}
             thrown-exception (assoc :threw thrown-exception))))
       nil))))

;; ---------------------------------------------------------------------------
;; Shared egress-scan helpers - lifted from the per-surface security test
;; namespaces, which each hand-copied a structurally identical deep
;; `contains-sentinel?` plus the `redacted?` / `large-marker?` one-liners
;; (rf2-n5bkm7). Consolidating them here removes the maintenance-only risk of
;; one copy gaining a leak-class fix the siblings silently miss.
;;
;; Each surface keeps its OWN per-file sentinel string local and calls
;; `(contains-string? x sentinel)`. Every surface matches the sentinel as a
;; SUBSTRING - both the per-leaf walker (`re-find`) and the `pr-str` fallback
;; scan for it - so a sentinel surviving inside a larger leaf, OR inside a
;; non-string leaf (a keyword/symbol form built from it, or a stringified
;; collection), is still caught.
;; ---------------------------------------------------------------------------

(defn contains-string?
  "Deep-walk `x`; true when `needle` appears anywhere - as a string leaf, inside
  a collection, or inside a stringified form. A walked STRING leaf matches when
  `needle` REGEX-matches it (`re-find` over `(re-pattern needle)`), and the
  `pr-str` fallback runs the same regex search over the rendered value, so
  `needle` surviving inside a non-string leaf (e.g. a keyword/symbol form built
  from it) is also caught. `needle` MUST therefore be a regex-safe literal (no
  unescaped regex metacharacters); the current sentinels are."
  [x needle]
  (let [hit (volatile! false)]
    (walk/postwalk
      (fn [node]
        (when (and (string? node)
                   (re-find (re-pattern needle) node))
          (vreset! hit true))
        node)
      x)
    (or @hit
        (boolean (re-find (re-pattern needle) (pr-str x))))))

(defn redacted?
  "True when `v` is the redaction sentinel keyword `:rf/redacted`."
  [v]
  (= :rf/redacted v))

(defn large-marker?
  "True when `v` is a structural large-elision marker (a map carrying
  `:rf.size/large-elided`)."
  [v]
  (and (map? v) (contains? v :rf.size/large-elided)))

;; ---------------------------------------------------------------------------
;; Nested-sensitive schema generator (rf2-iu6fqv) - lifted verbatim from the
;; two redaction-security suites (schema-redaction + validation-invariant),
;; which each hand-copied a structurally identical recursive generator: it
;; wraps a `:sensitive?` sentinel-bearing LEAF slot in a random tower of
;; collection/map combinators and plants a TYPE MISMATCH at the leaf, forcing a
;; validation failure that carries the sentinel. Every framework redaction
;; boundary must then scrub the sentinel from the emitted trace. Consolidating
;; here removes the maintenance-only risk of one copy gaining an arm (a new
;; leak class) its sibling silently misses.
;;
;; The two callers differed ONLY in (1) the sentinel string, (2) the maximum
;; nesting depth, and (3) the ORDER of two arms (`:tuple` / `:map-of-key`) in
;; the wrapper vector - a harmless historical drift: the SET of eleven arms,
;; each arm's built shape, and the leak-free property are identical; only the
;; seed->shape draw mapping differs. So the generator takes `sentinel`, the
;; `arms` vector, and `max-depth` as parameters and each caller passes its own,
;; keeping each suite's generated shapes byte-identical (a failing draw still
;; reproduces from its recorded seed).
;;
;; The eleven wrapper arms and WHY each exists (which egress leak class it pins):
;;   :map          - name the sensitive slot under a random key
;;   :vector       - element schema (value is a 1-element vector)
;;   :sequential   - as :vector, over a sequential coll
;;   :map-of       - string-keyed; the sensitive slot is the VALUE
;;   :map-of-key   - rf2-gocef0 / rf2-6ijdgh: the sentinel is planted AS the key
;;                   under a `:sensitive?` key schema. Malli reports the failing
;;                   key VALUE verbatim as the :in segment, so the walker's
;;                   :map-of key-scrub branch must scrub it from :path / :reason.
;;                   inner-val ALSO carries the sentinel at the leaf, so BOTH the
;;                   key AND the value must redact.
;;   :tuple        - sensitive slot at element 1 (an int filler sits at 0)
;;   :set          - rf2-ss06u.1: a :set wraps a navigable MAP carrying the
;;                   sensitive slot; Malli reports the element VALUE as the :in
;;                   segment, so the failing element would ride verbatim in :path
;;                   absent the sanitiser.
;;   :and :or :multi :orn - rf2-ss06u.2: transparent single-child wrappers
;;                   align-in-path cannot resolve. The sensitivity sits on a
;;                   CONSUMED ANCESTOR (the named :map slot); the failing leaf
;;                   lives under the wrapper. Absent the prefix-carry fix the
;;                   leftover subtree shows no sensitivity and the value leaks.
;; The leaf is a `:sensitive? :string` slot whose value is the sentinel wrapped
;; in a vector (WRONG type), so `:string` fails and the redaction path fires.
;; ---------------------------------------------------------------------------

(def ^:private gen-sensitive-key
  "Random slot key for the nested-sensitive generator's `:map`-family arms."
  (gen-elem [:auth :token :secret :pw :ssn :payload :inner :node :a :b]))

(defn- sensitive-shape
  "Generator returning `[schema db-value]` with a `sentinel`-bearing
  `:sensitive?` leaf wrapped in up to `depth` random collection/map layers
  drawn from `arms`. `depth` bounds recursion. See the block comment above for
  each arm."
  [sentinel arms depth]
  (if (<= depth 0)
    ;; Leaf: a sensitive :string slot whose value is the WRONG type (the
    ;; sentinel wrapped in a vector - fails :string, so the failure trace
    ;; would carry the sentinel verbatim absent redaction).
    (fn [rng]
      [[[:string {:sensitive? true}] [sentinel]] rng])
    (fn [rng]
      (let [[wrapper-arm next-rng] (rand-nth rng arms)
            [[inner-schema inner-value] after-inner-rng]
            ((sensitive-shape sentinel arms (dec depth)) next-rng)]
        (case wrapper-arm
          :map
          (let [[generated-key after-key-rng] (gen-sensitive-key after-inner-rng)]
            [[[:map [generated-key inner-schema]] {generated-key inner-value}] after-key-rng])

          :vector
          [[[:vector inner-schema] [inner-value]] after-inner-rng]

          :sequential
          [[[:sequential inner-schema] [inner-value]] after-inner-rng]

          :map-of
          [[[:map-of :string inner-schema] {"k" inner-value}] after-inner-rng]

          ;; The sensitive slot is the :map-of KEY (not the value); the sentinel
          ;; is planted AS the key. BOTH the key and the leaf value carry the
          ;; sentinel; both must redact.
          :map-of-key
          [[[:map-of [:string {:sensitive? true}] inner-schema] {sentinel inner-value}] after-inner-rng]

          :tuple
          ;; sensitive slot is element 1; element 0 is an int filler.
          [[[:tuple :int inner-schema] [0 inner-value]] after-inner-rng]

          ;; A :set wraps a MAP carrying the sensitive inner slot (so the set
          ;; element is a navigable map) under a random key.
          :set
          (let [[generated-key after-key-rng] (gen-sensitive-key after-inner-rng)]
            [[[:set [:map [generated-key inner-schema]]] #{{generated-key inner-value}}] after-key-rng])

          ;; Transparent single-child wrappers: the WRAPPER slot is the
          ;; sensitive container (props on the named :map slot) so the
          ;; sensitivity is on a CONSUMED ANCESTOR; the failing leaf lives
          ;; under the :and/:or/:multi/:orn.
          :and
          (let [[generated-key after-key-rng] (gen-sensitive-key after-inner-rng)]
            [[[:map [generated-key {:sensitive? true} [:and inner-schema]]] {generated-key inner-value}] after-key-rng])

          :or
          (let [[generated-key after-key-rng] (gen-sensitive-key after-inner-rng)]
            [[[:map [generated-key {:sensitive? true} [:or inner-schema]]] {generated-key inner-value}] after-key-rng])

          :multi
          (let [[generated-key after-key-rng] (gen-sensitive-key after-inner-rng)]
            ;; :multi needs a dispatch; wrap the inner under a single branch
            ;; keyed by a fixed dispatch value carried in the value map.
            [[[:map [generated-key {:sensitive? true}
                     [:multi {:dispatch :rf2/d}
                      [:x [:map [:rf2/d :keyword] [:v inner-schema]]]]]]
              {generated-key {:rf2/d :x :v inner-value}}] after-key-rng])

          :orn
          (let [[generated-key after-key-rng] (gen-sensitive-key after-inner-rng)]
            [[[:map [generated-key {:sensitive? true} [:orn [:x inner-schema]]]] {generated-key inner-value}] after-key-rng]))))))

(defn nested-sensitive-generator
  "Generator drawing `[schema db-value]` where a `sentinel`-bearing
  `:sensitive?` slot lives at a random `1..max-depth`-deep nesting built from
  the wrapper keywords in `arms`. A type mismatch at the leaf forces a
  validation failure carrying the sentinel; the redaction boundary under test
  must scrub it from every emitted trace slot.

  Parameterized (not hard-coded) so the two redaction-security suites share one
  implementation while each keeps its own sentinel, wrapper-arm order, and depth
  range - see the block comment above for the historical arm-order drift."
  [sentinel arms max-depth]
  (fn [rng]
    (let [[depth after-depth-rng] (next-int rng max-depth)]
      ((sensitive-shape sentinel arms (inc depth)) after-depth-rng))))
