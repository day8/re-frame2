(ns re-frame.adapter.reagent-slim-flush-views-cljs-test
  "The canonical test-flush hook `flush-views!` is surfaced
  from the reagent-slim ADAPTER ns (`re-frame.adapter.reagent-slim`) with
  the canonical nil-return shape, the same test-flush surface every
  substrate publishes.

  The SUBSTRATE ns `reagent2.dom.client` carries its own `flush-views!`,
  which RETURNS A PROMISE (the
  goog.DEBUG-gated microtask→act→microtask Suspense-ordering primitive,
  IMPL-SPEC §4.6); on its own it would diverge from UIx in BOTH location
  (substrate ns vs adapter ns) AND return type (Promise vs nil). This file
  pins the canonical shape on the adapter ns: a fn whose 0-arity call
  returns nil, matching stock Reagent and UIx.

  The substrate-level `reagent2.dom.client/flush-views!` Promise primitive
  serves Suspense-deterministic callers; this is the
  cross-substrate CANONICAL surface, distinct from that lower-level one.

  Node-safe (no DOM, no component): act() is unreachable / gated under the
  :node-test runner, so the spine degrades to a plain synchronous flush —
  the SHAPE (fn + nil return) is the cross-substrate contract under test.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]))

(deftest flush-views-canonical-shape
  (testing "reagent-slim — flush-views! surfaced from the ADAPTER ns with
            the canonical nil-return shape, alongside the substrate-ns
            Promise-returning primitive"
    (is (fn? rf.adapter.reagent-slim/flush-views!)
        "the slim adapter ns exposes flush-views! as a fn")
    (is (nil? (rf.adapter.reagent-slim/flush-views!))
        "0-arity flush-views! returns nil — the canonical contract (NOT the substrate-ns Promise)")
    (is (nil? (rf.adapter.reagent-slim/flush-views! (fn [] nil)))
        "1-arity flush-views! also returns nil")))
