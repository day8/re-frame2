(ns re-frame.security.gen-parity-security-cljs-test
  "Cross-runtime parity tests for the security tier's deterministic generator.

  A reported seed and index must reproduce the same draw on JVM and
  JavaScript. The LCG therefore uses exact low-32 multiplication on CLJS and
  carries an unsigned 32-bit state on both hosts."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])
            [re-frame.security.gen :as rf.security.gen]))

(deftest next-int-sequence-is-cross-runtime-deterministic
  ;; The JVM reference draw; a double-precision CLJS multiply diverges after
  ;; the first step.
  (is (= [54 24 56 95 79 28 42 38 60 29 94 96]
         (rf.security.gen/sample (rf.security.gen/gen-int 0 100) 12 1))))

(deftest for-all-counterexample-reproduces-from-seed
  ;; A for-all that never fails would turn every property in the tier vacuous.
  (let [g (rf.security.gen/gen-int 0 1000)
        {:keys [fail index]} (rf.security.gen/for-all g 50 99 #(< % 5))]
    (is (= fail (last (rf.security.gen/sample g (inc index) 99))))))
