(ns re-frame.adapter-make-derived-value-arity-spec-cljs-test
  "Reagent adapter — per-arity pin for `make-derived-value`.

  The body routes through `spine/build-recompute-fn`, so all four
  adapters share one implementation; these tests pin the observable
  contract for the Reagent adapter so an inadvertent spine-helper change
  cannot silently turn this surface into the naive
  `(apply compute-fn (map deref ...))` shape.

  The 0- and 1-arity branches are `build-recompute-fn`'s own, pinned by
  `re-frame.substrate.spine-build-recompute-fn-cljs-test`; the rows here pin
  the Reagent Reaction wiring around it.

  Pins:

    * 2-arity: layer-n sub shape — derefs two sources per recompute
    * ≥3-arity: fallback path — derefs all sources per recompute
    * mid-render mutation of a source produces a new derived value
      on next deref (no caching at the substrate layer)"
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.adapter.reagent :as rf.adapter.reagent]))

(defn- make-source [v]
  ((:make-state-container rf.adapter.reagent/adapter) v))

(defn- write! [c v]
  ((:replace-container! rf.adapter.reagent/adapter) c v))

(defn- derive [sources f]
  ((:make-derived-value rf.adapter.reagent/adapter) sources f))

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
