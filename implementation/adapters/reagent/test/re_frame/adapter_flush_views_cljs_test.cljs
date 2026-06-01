(ns re-frame.adapter-flush-views-cljs-test
  "rf2-b6nm5 — the canonical test-flush hook `flush-views!` is now surfaced
  from the Reagent adapter ns with the canonical nil-return shape
  (rf2-3yij Decision 6), converging the test-flush surface across all four
  substrates.

  Before this convergence the test-flush surface diverged THREE ways:
    - UIx / Helix surfaced `flush-views!` in the ADAPTER ns, (act f),
      returns nil (canonical);
    - reagent-slim surfaced `flush-views!` only in the SUBSTRATE ns
      `reagent2.dom.client`, returning a PROMISE;
    - stock Reagent surfaced NO adapter-level flush-views! at all — tests
      reached for `reagent.core/flush` / `react/act` directly.

  This file pins the converged shape for stock Reagent: the adapter ns now
  exposes `flush-views!` as a fn whose 0-arity call returns nil. Node-safe
  (no DOM, no component): act() is unreachable / gated under the :node-test
  runner, so the spine degrades to a plain synchronous flush — the SHAPE
  (fn + nil return) is the cross-substrate contract under test.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.adapter.reagent :as reagent-adapter]))

(deftest flush-views-canonical-shape
  (testing "Reagent — flush-views! surfaced from the adapter ns with the
            canonical nil-return shape (rf2-b6nm5 / Decision 6)"
    (is (fn? reagent-adapter/flush-views!)
        "the Reagent adapter ns exposes flush-views! as a fn (previously absent)")
    (is (nil? (reagent-adapter/flush-views!))
        "0-arity flush-views! returns nil — the converged contract across all four substrates")
    (is (nil? (reagent-adapter/flush-views! (fn [] nil)))
        "1-arity flush-views! also returns nil")))
