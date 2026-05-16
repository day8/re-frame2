(ns re-frame.adapter.helix-make-derived-value-arity-spec-cljs-test
  "Helix adapter — per-arity pin for `make-derived-value` (rf2-eoy63).

  Pre-rf2-eoy63 the Helix adapter's `make-derived-value` (routed via
  `spine/make-derived-value-fn`) built a recompute closure with naive
  `(apply compute-fn (map deref source-containers))` — `apply` cost +
  lazy `map` on every recompute. The spine now factors out an
  arity-specialised `build-recompute-fn` so the Helix adapter (along
  with Reagent, reagent-slim, UIx) shares one implementation.

  Pins the observable per-arity contract so a regression on the
  spine helper would surface here.

  Pins:
    * 0-arity (sources empty)
    * 1-arity (layer-1 dominant)
    * 2-arity (layer-n dominant)
    * ≥3-arity (fallback)
    * source-vector order preserved"
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.adapter.helix :as adapter]))

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
  (testing "1 source — derefs source per recompute (layer-1 dominant)"
    (let [src     (make-source 7)
          derived (derive [src] (fn [a] (* a 10)))]
      (is (= 70 @derived))
      (write! src 8)
      (is (= 80 @derived)))))

(deftest derived-two-arity-cljs-test
  (testing "2 sources — derefs both per recompute (layer-n dominant)"
    (let [a       (make-source 3)
          b       (make-source 4)
          derived (derive [a b] +)]
      (is (= 7 @derived))
      (write! a 100)
      (is (= 104 @derived))
      (write! b 200)
      (is (= 300 @derived)))))

(deftest derived-three-arity-cljs-test
  (testing "3 sources — fallback (apply + mapv deref) path"
    (let [a (make-source 1) b (make-source 2) c (make-source 3)
          derived (derive [a b c] (fn [x y z] (+ x y z)))]
      (is (= 6 @derived))
      (write! a 10) (write! b 20) (write! c 30)
      (is (= 60 @derived)))))

(deftest derived-four-arity-cljs-test
  (testing "4 sources — fallback path"
    (let [a (make-source :a) b (make-source :b) c (make-source :c) d (make-source :d)
          derived (derive [a b c d] (fn [w x y z] [w x y z]))]
      (is (= [:a :b :c :d] @derived)))))

(deftest derived-source-vector-order-preserved-cljs-test
  (testing "argument order matches source-vector order"
    (let [s0 (make-source 100)
          s1 (make-source 1)
          derived (derive [s0 s1] -)]
      (is (= 99 @derived)))))
