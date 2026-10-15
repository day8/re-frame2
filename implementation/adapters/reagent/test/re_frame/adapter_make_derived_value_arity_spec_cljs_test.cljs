(ns re-frame.adapter-make-derived-value-arity-spec-cljs-test
  "Reagent reactions recompute when either source changes; shared spine tests cover arity dispatch."
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
