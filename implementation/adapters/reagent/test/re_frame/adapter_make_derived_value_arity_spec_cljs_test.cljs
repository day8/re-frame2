(ns re-frame.adapter-make-derived-value-arity-spec-cljs-test
  "Reagent adapter — per-arity pin for `make-derived-value` (rf2-eoy63).

  Pre-rf2-eoy63 the arity-spec lived inline in this adapter as a
  4-branch `case`. The body now routes through `spine/build-recompute-fn`
  so all four adapters share one implementation; these tests pin the
  observable contract for the Reagent adapter so an inadvertent
  spine-helper regression cannot silently revert this surface to the
  naive `(apply compute-fn (map deref ...))` shape.

  Pins:

    * 0-arity: derived value tracks a 0-input compute-fn
    * 1-arity: layer-1 sub shape — derefs one source per recompute
    * 2-arity: layer-n sub shape — derefs two sources per recompute
    * ≥3-arity: fallback path — derefs all sources per recompute
    * mid-render mutation of a source produces a new derived value
      on next deref (no caching at the substrate layer)"
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.adapter.reagent :as adapter]))

(defn- make-source [v]
  ((:make-state-container adapter/adapter) v))

(defn- write! [c v]
  ((:replace-container! adapter/adapter) c v))

(defn- derive [sources f]
  ((:make-derived-value adapter/adapter) sources f))

(deftest derived-zero-arity-cljs-test
  (testing "0 sources — compute-fn called with no args"
    (let [calls   (atom 0)
          derived (derive []
                    (fn [] (swap! calls inc) ::seed))]
      (is (= ::seed @derived) "derived returns 0-arg compute-fn result")
      (is (pos? @calls) "compute-fn fired")
      (is (= ::seed @derived) "subsequent deref still ::seed"))))

(deftest derived-one-arity-cljs-test
  (testing "1 source — derefs source per recompute (layer-1 dominant path)"
    (let [src     (make-source 7)
          derived (derive [src] (fn [a] (* a 10)))]
      (is (= 70 @derived))
      (write! src 8)
      (is (= 80 @derived) "1-arity recompute picks up source mutation"))))

(deftest derived-two-arity-cljs-test
  (testing "2 sources — derefs both per recompute (layer-n dominant path)"
    (let [a       (make-source 3)
          b       (make-source 4)
          derived (derive [a b] +)]
      (is (= 7 @derived))
      (write! a 100)
      (is (= 104 @derived) "source-0 mutation flows through")
      (write! b 200)
      (is (= 300 @derived) "source-1 mutation flows through"))))

(deftest derived-three-arity-cljs-test
  (testing "3 sources — fallback (apply + mapv deref) path"
    (let [a (make-source 1) b (make-source 2) c (make-source 3)
          derived (derive [a b c] (fn [x y z] (+ x y z)))]
      (is (= 6 @derived))
      (write! a 10) (write! b 20) (write! c 30)
      (is (= 60 @derived) "all 3 sources flow through after mutations"))))

(deftest derived-four-arity-cljs-test
  (testing "4 sources — fallback path with apply"
    (let [a (make-source :a) b (make-source :b) c (make-source :c) d (make-source :d)
          derived (derive [a b c d] (fn [w x y z] [w x y z]))]
      (is (= [:a :b :c :d] @derived) "args in source-vector order"))))

(deftest derived-source-vector-order-preserved-cljs-test
  (testing "argument order matches source-vector order (non-commutative compute-fn pins it)"
    (let [s0 (make-source 100)
          s1 (make-source 1)
          derived (derive [s0 s1] -)]
      (is (= 99 @derived) "subtraction in source-vector order"))))
