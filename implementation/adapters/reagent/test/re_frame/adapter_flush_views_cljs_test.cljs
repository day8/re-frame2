(ns re-frame.adapter-flush-views-cljs-test
  "The Reagent adapter ns publishes the cross-substrate `flush-views!`, which
  returns nil like every other substrate's."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.adapter.reagent :as rf.adapter.reagent]))

(deftest flush-views-canonical-shape
  (testing "Reagent — flush-views! surfaced from the adapter ns with the
            canonical nil-return shape"
    (is (nil? (rf.adapter.reagent/flush-views!))
        "0-arity flush-views! returns nil — the converged contract across all four substrates")
    (is (nil? (rf.adapter.reagent/flush-views! (fn [] nil)))
        "1-arity flush-views! also returns nil")))
