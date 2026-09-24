(ns re-frame.adapter-flush-views-cljs-test
  "The canonical test-flush hook `flush-views!` is surfaced
  from the Reagent adapter ns with the canonical nil-return shape, the
  same test-flush surface the other substrates' adapter ns publish — so a
  test reaches for the adapter's `flush-views!` rather than for
  `reagent.core/flush` / `react/act` directly.

  This file pins that shape for stock Reagent: the adapter ns
  exposes `flush-views!` as a fn whose 0-arity call returns nil. Node-safe
  (no DOM, no component): act() is unreachable / gated under the :node-test
  runner, so the spine degrades to a plain synchronous flush — the SHAPE
  (fn + nil return) is the cross-substrate contract under test.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.adapter.reagent :as rf.adapter.reagent]))

(deftest flush-views-canonical-shape
  (testing "Reagent — flush-views! surfaced from the adapter ns with the
            canonical nil-return shape"
    (is (fn? rf.adapter.reagent/flush-views!)
        "the Reagent adapter ns exposes flush-views! as a fn")
    (is (nil? (rf.adapter.reagent/flush-views!))
        "0-arity flush-views! returns nil — the converged contract across all four substrates")
    (is (nil? (rf.adapter.reagent/flush-views! (fn [] nil)))
        "1-arity flush-views! also returns nil")))
