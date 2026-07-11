(ns re-frame.ui.eq-cljs-test
  "The ruled rf= truth table (Object.is OR =, per slot) — both hosts."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.ui.eq :refer [rf=]]))

(deftest value-branch
  (is (true? (rf= {:a [1 2]} {:a [1 2]}))
      "fresh-but-equal CLJS data compares by value — no repaint")
  (is (true? (rf= [:cart/add 1] [:cart/add 1])))
  (is (false? (rf= {:a 1} {:a 2})))
  (is (true? (rf= "s" "s")))
  (is (true? (rf= :k :k))))

(deftest identity-branch
  (let [o #?(:clj (Object.) :cljs (js-obj))]
    (is (true? (rf= o o)) "host values: identity")
    (is (false? (rf= #?(:clj (Object.) :cljs (js-obj))
                     #?(:clj (Object.) :cljs (js-obj))))
        "distinct host objects are never equal — mutable foreign values
         belong at an explicit boundary"))
  (let [f (fn [x] x)]
    (is (true? (rf= f f)))
    (is (false? (rf= (fn [x] x) (fn [x] x))))))

(deftest numeric-edges
  (is (true? (rf= ##NaN ##NaN)) "NaN props are repaint-stable (Object.is branch)")
  (is (true? (rf= -0.0 0.0))
      "-0/+0 compare EQUAL via the = branch (deliberate divergence from raw Object.is)")
  (is (true? (rf= 1 1.0))
      "one number line — JS has a single number type; the JVM twin matches")
  (is (false? (rf= 1 2)))
  (is (true? (rf= nil nil))))

(deftest date-and-records
  (let [t 1234567890]
    (is (true? (rf= #?(:clj (java.util.Date. (long t)) :cljs (js/Date. t))
                    #?(:clj (java.util.Date. (long t)) :cljs (js/Date. t))))
        "dates carry value equality (IEquiv / .equals)")))
