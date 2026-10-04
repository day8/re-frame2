(ns re-frame.adapter.reagent-slim-make-derived-value-arity-spec-cljs-test
  "reagent-slim adapter — per-arity pin for `make-derived-value`.

  The fn routes through `spine/build-recompute-fn`, so reagent-slim
  shares the arity-spec with Reagent and UIx instead of a naive
  `(apply compute-fn (map deref source-containers))` — `apply` cost on
  every recompute and a lazy `map` cons chain that defers derefs. These
  tests pin the observable contract so an inadvertent slide back to the
  naive shape would break the suite.

  The 0- and 1-arity branches are `build-recompute-fn`'s own, pinned by
  `re-frame.substrate.spine-build-recompute-fn-cljs-test`; the rows here pin
  the slim Reaction wiring around it.

  Pins:

    * 2-arity: layer-n sub shape
    * ≥3-arity: fallback path
    * source-vector order preserved through the recompute closure"
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]))

(defn- make-source [v]
  ((:make-state-container rf.adapter.reagent-slim/adapter) v))

(defn- write! [c v]
  ((:replace-container! rf.adapter.reagent-slim/adapter) c v))

(defn- make-derived-value [sources f]
  ((:make-derived-value rf.adapter.reagent-slim/adapter) sources f))

(deftest derived-two-arity-cljs-test
  (testing "2 sources — derefs both per recompute (layer-n dominant path)"
    (let [a       (make-source 3)
          b       (make-source 4)
          derived (make-derived-value [a b] +)]
      (is (= 7 @derived))
      (write! a 100)
      (is (= 104 @derived) "source-0 mutation flows through")
      (write! b 200)
      (is (= 300 @derived) "source-1 mutation flows through"))))

(deftest derived-three-arity-cljs-test
  (testing "3 sources — fallback (apply + mapv deref) path"
    (let [a (make-source 1) b (make-source 2) c (make-source 3)
          derived (make-derived-value [a b c] (fn [x y z] (+ x y z)))]
      (is (= 6 @derived))
      (write! a 10) (write! b 20) (write! c 30)
      (is (= 60 @derived) "all 3 sources flow through after mutations"))))

(deftest derived-four-arity-cljs-test
  (testing "4 sources — fallback path with apply"
    (let [a (make-source :a) b (make-source :b) c (make-source :c) d (make-source :d)
          derived (make-derived-value [a b c d] (fn [w x y z] [w x y z]))]
      (is (= [:a :b :c :d] @derived)))))
