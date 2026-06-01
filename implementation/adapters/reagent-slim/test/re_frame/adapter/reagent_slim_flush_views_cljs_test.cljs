(ns re-frame.adapter.reagent-slim-flush-views-cljs-test
  "rf2-b6nm5 — the canonical test-flush hook `flush-views!` is now surfaced
  from the reagent-slim ADAPTER ns (`re-frame.adapter.reagent-slim`) with
  the canonical nil-return shape (rf2-3yij Decision 6), converging the
  test-flush surface across all four substrates.

  Before this convergence the ONLY slim `flush-views!` lived in the
  SUBSTRATE ns `reagent2.dom.client` and RETURNED A PROMISE (the
  goog.DEBUG-gated microtask→act→microtask Suspense-ordering primitive,
  IMPL-SPEC §4.6) — diverging from UIx/Helix in BOTH location (substrate
  ns vs adapter ns) AND return type (Promise vs nil). This file pins the
  converged shape on the adapter ns: a fn whose 0-arity call returns nil,
  matching stock Reagent, UIx, and Helix.

  The substrate-level `reagent2.dom.client/flush-views!` Promise primitive
  is unchanged (Suspense-deterministic callers keep it); this is the
  cross-substrate CANONICAL surface, distinct from that lower-level one.

  Node-safe (no DOM, no component): act() is unreachable / gated under the
  :node-test runner, so the spine degrades to a plain synchronous flush —
  the SHAPE (fn + nil return) is the cross-substrate contract under test.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.adapter.reagent-slim :as reagent-slim]))

(deftest flush-views-canonical-shape
  (testing "reagent-slim — flush-views! surfaced from the ADAPTER ns with
            the canonical nil-return shape (rf2-b6nm5 / Decision 6),
            converged with the substrate-ns Promise-returning primitive"
    (is (fn? reagent-slim/flush-views!)
        "the slim adapter ns exposes flush-views! as a fn (previously only the substrate ns did)")
    (is (nil? (reagent-slim/flush-views!))
        "0-arity flush-views! returns nil — the converged contract (NOT the substrate-ns Promise)")
    (is (nil? (reagent-slim/flush-views! (fn [] nil)))
        "1-arity flush-views! also returns nil")))
