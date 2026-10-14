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
    ;; The 0-arity delegates to the 1-arity, so one read covers both.
    (is (nil? (rf.adapter.reagent-slim/flush-views!))
        "flush-views! returns nil — the canonical contract (NOT the substrate-ns Promise)")))
