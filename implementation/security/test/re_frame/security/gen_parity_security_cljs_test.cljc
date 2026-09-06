(ns re-frame.security.gen-parity-security-cljs-test
  "Cross-runtime parity tests for the security tier's deterministic generator.

  A reported seed and index must reproduce the same draw on JVM and
  JavaScript. The LCG therefore uses exact low-32 multiplication on CLJS and
  carries an unsigned 32-bit state on both hosts."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.security.gen :as rf.security.gen]))

(defn- draws
  "Thread the PRNG from `seed`, drawing `n` ints in [0,bound)."
  [seed bound n]
  (loop [rng (rf.security.gen/make-rng seed), i 0, acc []]
    (if (< i n)
      (let [[v rng'] (rf.security.gen/next-int rng bound)]
        (recur rng' (inc i) (conj acc v)))
      acc)))

(deftest next-int-sequence-is-cross-runtime-deterministic
  (testing "the LCG reproduces the JVM reference draw sequence on this runtime
            and keeps CLJS bit-identical to JVM"
    (is (= [54 24 56 95 79 28 42 38 60 29 94 96]
           (draws 1 100 12))
        "seed 1, bound 100 must match the JVM reference")
    (is (= [60 245 28 134 20 3 216 219 7 245 13 25]
           (draws 23 256 12))
        "seed 23, bound 256 must match the JVM reference")))

(deftest sample-and-for-all-reproduce-from-seed
  (testing "the documented `(seed, g, n)` reproducibility holds on this runtime"
    (let [g (rf.security.gen/gen-int 0 1000)]
      ;; The second assertion ensures the seed participates in the draw.
      (is (= (rf.security.gen/sample g 20 7) (rf.security.gen/sample g 20 7)))
      (is (not= (rf.security.gen/sample g 20 7) (rf.security.gen/sample g 20 8))))
    ;; Re-drawing through the reported index must reproduce the counterexample.
    (let [g    (rf.security.gen/gen-int 0 1000)
          {:keys [fail index seed]} (rf.security.gen/for-all g 50 99 (fn [n] (< n 5)))]
      (is (some? fail) "a [0,1000) draw should exceed 5 within 50 draws")
      (is (= 99 seed))
      (is (= fail (last (rf.security.gen/sample g (inc index) seed)))
          "the reported seed/index must reproduce the failing draw exactly"))))
