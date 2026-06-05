(ns re-frame.security.gen-parity-security-cljs-test
  "Cross-runtime parity pin for the security-tier deterministic generator
  (`re-frame.security.gen`) — rf2-h2yvs finding 2.

  ## Why this exists

  `gen.cljc` advertises a 32-bit LCG that is byte-deterministic AND identical
  across JVM + JS: `for-all` reports a failing draw as `{:seed :index}`, and
  the contract is that the SAME seed/index reproduces the SAME draw on either
  host. That only holds if the LCG arithmetic agrees bit-for-bit across
  runtimes.

  Before rf2-h2yvs, `next-state` used `unchecked-multiply`, which on CLJS
  compiles to JS `*` on a double. The LCG product `s * 1103515245` is ~2^62
  for a 32-bit state, exceeding the 53-bit double mantissa, so the low bits
  were lost and the JS sequence diverged from the JVM sequence after the first
  step (seed 1 / bound 100: JVM `54 24 56 …` vs the double path `54 25 12 …`).
  The fix routes the multiply through `js/Math.imul` (exact low-32 product) on
  CLJS, keeps the full-precision `long` multiply on CLJ, and carries an
  unsigned-32 state on both.

  ## What this pins

  The expected sequences below are the JVM reference (computed under
  `:clj`). The test runs in BOTH runtimes; on CLJS it now reproduces these
  exact values via `Math.imul`. A regression to the double-multiply path makes
  the very first multi-step draw RED on CLJS. The test is named
  `…-security-cljs-test` so it rides `npm run test:security` AND the always-on
  `npm run test:cljs` node gate; as `.cljc` it also pins the contract for any
  JVM run of the security tier."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.security.gen :as gen]))

(defn- draws
  "Thread the PRNG from `seed`, drawing `n` ints in [0,bound)."
  [seed bound n]
  (loop [rng (gen/make-rng seed), i 0, acc []]
    (if (< i n)
      (let [[v rng'] (gen/next-int rng bound)]
        (recur rng' (inc i) (conj acc v)))
      acc)))

(deftest next-int-sequence-is-cross-runtime-deterministic
  (testing "the LCG reproduces the JVM reference draw sequence on this runtime
            (rf2-h2yvs finding 2 — proves the Math.imul portable-multiply fix
            keeps CLJS bit-identical to JVM)"
    ;; Reference sequences computed under :clj with the portable next-state.
    (is (= [54 24 56 95 79 28 42 38 60 29 94 96]
           (draws 1 100 12))
        "seed 1, bound 100 must match the JVM reference (was 54 25 12 … on the
         pre-fix CLJS double-multiply path)")
    (is (= [60 245 28 134 20 3 216 219 7 245 13 25]
           (draws 23 256 12))
        "seed 23, bound 256 must match the JVM reference")))

(deftest sample-and-for-all-reproduce-from-seed
  (testing "the documented `(seed, g, n)` reproducibility holds on this runtime"
    (let [g (gen/gen-int 0 1000)]
      ;; Same seed ⇒ same sample, and a different seed ⇒ a different sample
      ;; (so the seed actually threads through the host-specific multiply).
      (is (= (gen/sample g 20 7) (gen/sample g 20 7)))
      (is (not= (gen/sample g 20 7) (gen/sample g 20 8))))
    ;; `for-all`'s counter-example seed/index must reproduce the failing draw:
    ;; assert "every drawn int < 5" over a [0,1000) generator; the first draw
    ;; that is >= 5 is the counter-example, and re-drawing at that seed/index
    ;; must yield the same value on either host.
    (let [g    (gen/gen-int 0 1000)
          {:keys [fail index seed]} (gen/for-all g 50 99 (fn [n] (< n 5)))]
      (is (some? fail) "a [0,1000) draw should exceed 5 within 50 draws")
      (is (= 99 seed))
      ;; Re-draw `index+1` values from the same seed; the last equals `fail`.
      (is (= fail (last (gen/sample g (inc index) seed)))
          "the reported seed/index must reproduce the failing draw exactly"))))
