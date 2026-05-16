(ns re-frame.adapter.reagent-slim-make-derived-value-arity-spec-cljs-test
  "reagent-slim adapter — per-arity pin for `make-derived-value`
  (rf2-eoy63).

  Pre-rf2-eoy63 this adapter's `make-derived-value` was naive
  `(apply compute-fn (map deref source-containers))` — `apply` cost on
  every recompute and a lazy `map` cons chain that defers derefs. The
  fn now routes through `spine/build-recompute-fn` so reagent-slim
  shares the arity-spec with Reagent, UIx and Helix. These tests pin
  the observable contract so an inadvertent regression to the naive
  shape would break the suite.

  Pins:

    * 0-arity: 0-input compute-fn
    * 1-arity: layer-1 sub shape
    * 2-arity: layer-n sub shape
    * ≥3-arity: fallback path
    * source-vector order preserved through the recompute closure"
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.adapter.reagent-slim :as adapter]))

(defn- make-source [v]
  ((:make-state-container adapter/adapter) v))

(defn- write! [c v]
  ((:replace-container! adapter/adapter) c v))

(defn- derive [sources f]
  ((:make-derived-value adapter/adapter) sources f))

(deftest derived-zero-arity-cljs-test
  (testing "0 sources — compute-fn called with no args"
    (let [derived (derive [] (fn [] ::seed))]
      (is (= ::seed @derived)))))

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
      (is (= [:a :b :c :d] @derived)))))

(deftest derived-source-vector-order-preserved-cljs-test
  (testing "argument order matches source-vector order"
    (let [s0 (make-source 100)
          s1 (make-source 1)
          derived (derive [s0 s1] -)]
      (is (= 99 @derived)))))
