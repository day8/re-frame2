(ns re-frame.adapter.reagent-slim-flush-views-cljs-test
  "The reagent-slim ADAPTER ns publishes the cross-substrate `flush-views!`,
  which returns nil like stock Reagent's and UIx's; the substrate ns
  `reagent2.dom.client` keeps its own Promise-returning primitive."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]))

(deftest flush-views-canonical-shape
  (testing "reagent-slim — flush-views! surfaced from the ADAPTER ns with
            the canonical nil-return shape, alongside the substrate-ns
            Promise-returning primitive"
    (is (nil? (rf.adapter.reagent-slim/flush-views!))
        "0-arity flush-views! returns nil — the canonical contract (NOT the substrate-ns Promise)")
    (is (nil? (rf.adapter.reagent-slim/flush-views! (fn [] nil)))
        "1-arity flush-views! also returns nil")))
