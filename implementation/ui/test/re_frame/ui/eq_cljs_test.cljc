(ns re-frame.ui.eq-cljs-test
  "The ruled rf= truth table (Object.is OR =, per slot) — both hosts."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.ui.eq :refer [rf= deps-rf=?]]))

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

(deftest deps-per-slot-doctrine
  ;; The effect-dependency doctrine walks the deps vector PER SLOT — the fix for
  ;; the whole-vector `rf=` regression (rf2-u53yy.6): a stable ##NaN slot and a
  ;; rebuilt-but-equal CLJS collection slot must NOT re-run the effect.
  (is (true? (deps-rf=? [1 :k "s"] [1 :k "s"]))
      "identical authored slots compare equal")
  (is (true? (deps-rf=? [{:a [1 2]}] [{:a [1 2]}]))
      "a rebuilt-but-equal CLJS collection slot is equal (= branch, per slot)")
  (is (true? (deps-rf=? [##NaN] [##NaN]))
      "a stable ##NaN slot is equal (Object.is branch, per slot) — whole-vector
       rf= would report changed because (= [##NaN] [##NaN]) is false")
  (is (true? (deps-rf=? [1 ##NaN {:a 1}] [1 ##NaN {:a 1}]))
      "NaN and rebuilt-collection slots mixed with scalars stay equal")
  (is (true? (deps-rf=? [] []))
      "empty deps are equal")
  (is (false? (deps-rf=? [1 2] [1 3]))
      "a changed scalar slot differs")
  (is (false? (deps-rf=? [{:a 1}] [{:a 2}]))
      "a changed collection slot differs")
  (is (false? (deps-rf=? [1] [1 2]))
      "different arity differs")
  (is (false? (deps-rf=? [1 2] [1]))
      "different arity differs (shorter)"))
