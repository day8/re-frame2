(ns re-frame.adapter.helix-frame-provider-children-cljs-test
  "Unit coverage for the three branches of the Helix adapter's
  `frame-provider` :children handling (rf2-uze2w).

  `frame-provider` (shared spine — `re-frame.substrate.spine`, exposed
  through `re-frame.adapter.helix`) accepts a props map with `:frame`
  and `:children` keys and returns a React element wrapping the children
  in the shared frame-context Provider. Three defensive branches:

    1. `:children` sequential → splat directly into provider-element
    2. `:children` non-sequential → wrap-in-vec then splat (defensive
       cover for callers that pass a single element rather than a vec)
    3. `:frame` nil / missing → fall through to `:rf/default` (per
       rf2-sixo — tooling-generated trees that elide the prop)

  Pre-rf2-uze2w none of these branches had explicit coverage. The
  nil-fallback in particular matters because rf2-sixo's rationale is
  precisely \"tooling broke and stopped passing the prop\" — a
  regression here is exactly that failure mode.

  Implementation note: `frame-provider` is a plain CLJS fn returning a
  raw React element (NOT a defnc component head). The test calls it
  directly and inspects the returned element's `.props.value` and
  `.props.children` — no DOM needed, so this runs under `:node-test`.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react" :as React]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.adapter.helix :as helix-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter helix-adapter/adapter}))

;; ---- helpers ---------------------------------------------------------------

(defn- react-element?
  "Cheap React-element shape probe — every React.createElement result
  has the `$$typeof` Symbol marker and a `type` field."
  [el]
  (and (some? el)
       (some? (.-$$typeof ^js el))
       (some? (.-type ^js el))))

(defn- provider-type?
  "True iff `el` is a frame-context Provider element (i.e. its `type`
  is the Provider object hanging off the shared frame-context)."
  [el]
  (identical? (.-type ^js el)
              (.-Provider ^js adapter-context/frame-context)))

(defn- props-value [el] (.-value (.-props ^js el)))
(defn- props-children [el] (.-children (.-props ^js el)))

(defn- leaf-element
  "Build a trivial React element to act as a child in the assertions
  below. `React.createElement(\"span\", null, label)`."
  [label]
  (React/createElement "span" nil label))

;; ---- Branch 1: :children sequential ----------------------------------------

(deftest frame-provider-children-sequential
  (testing "sequential :children — vec of two elements — splats into Provider children"
    (let [c1 (leaf-element "a")
          c2 (leaf-element "b")
          el (helix-adapter/frame-provider
               {:frame :rf.helix-fp-test/seq
                :children [c1 c2]})]
      (is (react-element? el)
          "frame-provider returns a React element")
      (is (provider-type? el)
          "the element's `type` is the shared frame-context Provider")
      (is (= :rf.helix-fp-test/seq (props-value el))
          ":value prop carries the requested frame keyword")
      ;; React.createElement collapses 2+ children into a JS array on
      ;; `.props.children`.
      (let [kids (props-children el)]
        (is (array? kids)
            "two children land as a JS array on .props.children")
        (is (= 2 (.-length kids))
            "both children present")
        (is (identical? c1 (aget kids 0)))
        (is (identical? c2 (aget kids 1)))))))

;; ---- Branch 2: :children non-sequential ------------------------------------

(deftest frame-provider-children-single-non-sequential
  (testing "non-sequential :children — single React element — wrap-in-vec then splat"
    (let [only (leaf-element "solo")
          el (helix-adapter/frame-provider
               {:frame :rf.helix-fp-test/single
                :children only})]
      (is (react-element? el))
      (is (provider-type? el))
      (is (= :rf.helix-fp-test/single (props-value el)))
      ;; The wrap-in-vec path passes `[only]` through `apply`, so a
      ;; single child lands directly on `.props.children` (React's
      ;; single-child shortcut — no array wrapper).
      (let [kids (props-children el)]
        (is (identical? only kids)
            "single child lands directly on .props.children (no array wrapper)")))))

;; ---- Branch 3: :frame nil / missing → :rf/default --------------------------

(deftest frame-provider-frame-nil-falls-through-to-default
  (testing "nil :frame — falls through to :rf/default (rf2-sixo)"
    (let [c1 (leaf-element "x")
          el (helix-adapter/frame-provider
               {:frame nil
                :children [c1]})]
      (is (provider-type? el))
      (is (= :rf/default (props-value el))
          "nil :frame is replaced with :rf/default — the tooling-elided-prop fallback"))))

(deftest frame-provider-frame-missing-key-falls-through-to-default
  (testing "missing :frame key — destructured nil — falls through to :rf/default"
    (let [c1 (leaf-element "y")
          el (helix-adapter/frame-provider
               {:children [c1]})]
      (is (provider-type? el))
      (is (= :rf/default (props-value el))
          "no :frame key destructures to nil; same fall-through to :rf/default"))))
