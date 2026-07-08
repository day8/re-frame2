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
  [s]
  #?(:clj  (bit-and (unchecked-add (unchecked-multiply (long s) lcg-mult) lcg-inc)
                    mask32)
     :cljs (unsigned-bit-shift-right (+ (js/Math.imul s lcg-mult) lcg-inc) 0)))

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

;; ---------------------------------------------------------------------------
;; Shared egress-scan helpers - lifted from the per-surface security test
;; namespaces, which each hand-copied a structurally identical deep
;; `contains-sentinel?` plus the `redacted?` / `large-marker?` one-liners
;; (rf2-n5bkm7). Consolidating them here removes the maintenance-only risk of
;; one copy gaining a leak-class fix the siblings silently miss.
;;
;; Each surface keeps its OWN per-file sentinel string local and calls
;; `(contains-string? x sentinel exact?)`, passing `exact?` to preserve that
;; surface's per-node matching: some surfaces match the sentinel as an EXACT
;; string equality on each walked leaf (`exact? true`), others as a SUBSTRING
;; (`exact? false`, the `re-find` form - catches a sentinel embedded inside a
;; larger leaf). Either way the `pr-str` fallback always uses a SUBSTRING scan,
;; so a sentinel surviving inside a non-string leaf (a keyword/symbol form
;; built from it, or a stringified collection) is still caught.
;; ---------------------------------------------------------------------------

(defn contains-string?
  "Deep-walk `x`; true when `needle` appears anywhere - as a value, inside a
  collection, or inside a stringified form. When `exact?` is truthy, a walked
  STRING leaf matches only on exact `=` equality with `needle`; otherwise a
  leaf matches when `needle` REGEX-matches it (`re-find` over `(re-pattern
  needle)`). The `pr-str` fallback always uses the same regex search, so
  `needle` surviving inside a non-string leaf (e.g. a keyword/symbol form
  built from it) is also caught. `needle` MUST therefore be a regex-safe
  literal (no unescaped regex metacharacters); the current sentinels are."
  [x needle exact?]
  (let [hit (volatile! false)]
    (walk/postwalk
      (fn [node]
        (when (and (string? node)
                   (if exact?
                     (= needle node)
                     (re-find (re-pattern needle) node)))
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
