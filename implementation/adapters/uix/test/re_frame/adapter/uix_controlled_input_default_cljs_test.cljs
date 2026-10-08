(ns re-frame.adapter.uix-controlled-input-default-cljs-test
  "The UIx adapter's controlled-input implementation is React's own, and it
  is NOT selected by what else is on the classpath.

  `uix.compiler.aot/create-uix-input` picks, per `:input` element and at
  element-creation time, between plain React and a port of Reagent's
  controlled-input workaround. Left unset,
  `uix.compiler.input/*use-reagent-input-enabled?*` makes that choice by
  asking whether `reagent.impl.util/*non-reactive*` happens to EXIST — so
  adding the Reagent adapter beside UIx would silently change how the UIx
  app's `<input>` elements behave. `re-frame.adapter.uix` `set!`s the var at
  load, so the answer is the adapter's, not the bundle's.

  The two implementations are materially different products, measured in
  real Chromium against react-dom 19.2.0 in
  `docs/design/fresco/studio/controlled-input-two-implementations.md`:
  React keeps the element controlled and converges inside the
  discrete event; the port makes it uncontrolled and converges one
  `requestAnimationFrame` later. Those DOM-level differences are witnessed
  there, in the browser lane. What THIS namespace pins is narrower and is
  the adapter's own contract: which of the two a consumer gets, and that
  the other one remains reachable on purpose.

  No DOM needed — the selector is a var read."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [uix.compiler.input]
            ;; Loaded for the load-time pin under test.
            [re-frame.adapter.uix]))

(def ^:private reagent-input-enabled-at-load?
  "The var as `re-frame.adapter.uix`'s load left it, snapshotted at THIS
  namespace's load — which CLJS runs after the adapter's, and before any
  test runs. Asserting the snapshot rather than only the live read is what
  makes the pin order-independent: sibling suites pin the var per row, and
  a live read alone would be masked by whichever of them the runner happens
  to schedule first."
  uix.compiler.input/*use-reagent-input-enabled?*)

(defn- reagent-input-selected? []
  (uix.compiler.input/should-use-reagent-input?))

(deftest react-controlled-input-is-the-adapters-default
  (testing "requiring the adapter is enough: a UIx `:input` is a plain React
           controlled input"
    (is (= [false false] [reagent-input-enabled-at-load? (reagent-input-selected?)])
        "the adapter's load left the var at false (read from the load-time
         snapshot, so no sibling suite's per-row pinning can mask its
         absence), and `should-use-reagent-input?` answers false")))

(deftest the-port-remains-reachable-explicitly
  (testing "the adapter makes React the DEFAULT, not the only option: the var
           stays public and dynamic, so a consumer who genuinely wants
           Reagent's port asks for it by name"
    (set! uix.compiler.input/*use-reagent-input-enabled?* true)
    (is (true? (reagent-input-selected?))
        "an explicit opt-in reaches the port")
    (set! uix.compiler.input/*use-reagent-input-enabled?* false)
    (is (false? (reagent-input-selected?))
        "and an explicit opt-out returns to React's own implementation")))
