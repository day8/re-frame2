(ns re-frame.ssr.wire-cljs-test
  "The EDN wire-crossing predicate (`re-frame.ssr.wire/edn-carryable?`, Spec
  011 §The numeric crossing rule): a value is carryable exactly when it
  reads back EQUAL on the other host. Runs on the JVM and Node."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.wire :as rf.ssr.wire]))

;; The smallest value whose `pr-str` carries a tag the safe reader cannot
;; construct, on both hosts.
(defrecord WireProbeRecord [x])

(deftest opaque-values-are-refused-however-deep
  (is (not (rf.ssr.wire/edn-carryable? {:a [1 {:b (fn [])}]}))
      "opaqueness is detected through nesting")
  (is (not (rf.ssr.wire/edn-carryable? {(->WireProbeRecord 1) :v}))
      "a record used as a map KEY is refused too"))

(deftest only-cross-host-numbers-ride-the-wire
  (testing "admitted — the integer bound is about representability, so a large
            double rides, and so does an infinity"
    (doseq [v [rf.ssr.wire/max-safe-integer
               (- rf.ssr.wire/max-safe-integer)
               1.5
               #?(:clj 1.0E308 :cljs 1e308)
               ##Inf]]
      (is (rf.ssr.wire/edn-carryable? v) (str "must still carry " (pr-str v))))
    (is (rf.ssr.wire/edn-carryable? {:a [1 {:b #{2 3.5}}]})))

  (testing "refused"
    (is (not (rf.ssr.wire/edn-carryable? #?(:clj Double/NaN :cljs js/NaN)))
        "NaN is not `=` to itself, so it cannot read back EQUAL on any host")
    #?(:clj
       (doseq [v [9007199254740993N
                  (inc rf.ssr.wire/max-safe-integer)
                  (- (inc rf.ssr.wire/max-safe-integer))
                  (float 0.1)]]
         (is (not (rf.ssr.wire/edn-carryable? v)) (pr-str v))))))
