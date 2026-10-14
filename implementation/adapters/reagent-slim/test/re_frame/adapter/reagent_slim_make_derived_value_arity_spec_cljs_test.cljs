(ns re-frame.adapter.reagent-slim-make-derived-value-arity-spec-cljs-test
  "reagent-slim `make-derived-value` over 2 sources: every source's change
  flows through. The recompute fn every arity shares is the spine's, pinned
  row by row in `re-frame.substrate.spine-build-recompute-fn-cljs-test`."
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
