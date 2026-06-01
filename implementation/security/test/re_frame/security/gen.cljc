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
  (:refer-clojure :exclude [rand-nth]))

;; ---------------------------------------------------------------------------
;; Seeded PRNG - a 32-bit LCG (numerical-recipes constants). Deterministic
;; and identical across JVM + JS (we mask to 32 bits with bit-and so the
;; arithmetic never depends on host integer width). State is an int.
;; ---------------------------------------------------------------------------

(def ^:private mask32 0xFFFFFFFF)

(defn make-rng
  "Make a PRNG state from an integer seed."
  [seed]
  (bit-and (int seed) mask32))

(defn- next-state
  [s]
  (bit-and (unchecked-add (unchecked-multiply s 1103515245) 12345) mask32))

(defn next-int
  "Return `[n rng']` where `0 <= n < bound`. `bound` must be positive."
  [rng bound]
  (let [s' (next-state rng)
        ;; take the high bits (low bits of an LCG are low-quality)
        hi (bit-and (bit-shift-right s' 8) 0x7FFFFF)]
    [(mod hi bound) s']))

(defn rand-nth
  "Draw a uniformly-random element of `coll`. Returns `[elem rng']`."
  [rng coll]
  (let [v (vec coll)
        [i rng'] (next-int rng (count v))]
    [(nth v i) rng']))

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
    (let [[n rng'] (next-int rng (- hi lo))]
      [(+ lo n) rng'])))

(defn gen-one-of
  "Generator that picks one of the supplied generators uniformly, then
  draws from it."
  [& gens]
  (fn [rng]
    (let [[g rng'] (rand-nth rng (vec gens))]
      (g rng'))))

(defn gen-fmap
  "Generator that maps `f` over `g`'s drawn value."
  [f g]
  (fn [rng]
    (let [[v rng'] (g rng)]
      [(f v) rng'])))

(defn gen-vec
  "Generator for a vector of `n` draws from `g` (n itself may be a draw -
  pass an int for fixed length, or a `[lo hi)` generator via `gen-int`)."
  [n-or-gen g]
  (fn [rng]
    (let [[n rng0] (if (fn? n-or-gen) (n-or-gen rng) [n-or-gen rng])]
      (loop [i 0, rng rng0, acc []]
        (if (< i n)
          (let [[v rng'] (g rng)]
            (recur (inc i) rng' (conj acc v)))
          [acc rng])))))

(defn sample
  "Draw `n` values from generator `g` starting at `seed`. Returns a vector
  of the drawn values (state threaded internally). Deterministic in
  `(seed, g, n)`."
  ([g n] (sample g n 0))
  ([g n seed]
   (loop [i 0, rng (make-rng seed), acc []]
     (if (< i n)
       (let [[v rng'] (g rng)]
         (recur (inc i) rng' (conj acc v)))
       acc))))

(defn for-all
  "Run `pred` over `n` draws of generator `g` (seeded at `seed`, default
  0). Returns `nil` when every draw satisfied `pred`, else a map
  `{:fail value :index i :seed seed}` describing the first counter-example
  - the seed + index reproduce it exactly. `pred` may throw; a throw is
  treated as a failure and the exception is captured under `:threw`."
  ([g n pred] (for-all g n 0 pred))
  ([g n seed pred]
   (loop [i 0, rng (make-rng seed)]
     (if (< i n)
       (let [[v rng'] (g rng)
             [ok? threw] (try [(boolean (pred v)) nil]
                              (catch #?(:clj Throwable :cljs :default) e
                                [false e]))]
         (if ok?
           (recur (inc i) rng')
           (cond-> {:fail v :index i :seed seed}
             threw (assoc :threw threw))))
       nil))))
