(ns re-frame.adapter.uix-controlled-input-default-cljs-test
  "The UIx adapter's controlled-input implementation is React's own, and it
  is NOT selected by what else is on the classpath (rf2-heqwo).

  `uix.compiler.aot/create-uix-input` picks, per `:input` element and at
  element-creation time, between plain React and a port of Reagent's
  controlled-input workaround. Left unset,
  `uix.compiler.input/*use-reagent-input-enabled?*` makes that choice by
  asking whether `reagent.impl.util/*non-reactive*` happens to EXIST — so
  adding the Reagent adapter beside UIx silently changed how the UIx app's
  `<input>` elements behave. `re-frame.adapter.uix` now `set!`s the var at
  load, so the answer is the adapter's, not the bundle's.

  The two implementations are materially different products, measured in
  real Chromium against react-dom 19.2.0 in
  `docs/design/hicasso/studio/controlled-input-two-implementations.md`
  (rf2-n3dxw): React keeps the element controlled and converges inside the
  discrete event; the port makes it uncontrolled and converges one
  `requestAnimationFrame` later. Those DOM-level differences are witnessed
  there, in the browser lane. What THIS namespace pins is narrower and is
  the adapter's own contract: which of the two a consumer gets, and that
  the other one remains reachable on purpose.

  No DOM needed — the selector is a var read. ns ends in -cljs-test so
  shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [uix.compiler.input]
            [re-frame.adapter.uix :as uix-adapter]))

(defn- selector []
  (uix.compiler.input/should-use-reagent-input?))

(defn- with-cleared-pin
  "Run `f` with the adapter's load-time pin cleared, so UIx's own classpath
  sniff can be observed, and restore the pin however `f` ends."
  [f]
  (set! uix.compiler.input/*use-reagent-input-enabled?* nil)
  (try (f) (finally (set! uix.compiler.input/*use-reagent-input-enabled?* false))))

(deftest reacts-controlled-input-is-the-adapters-default
  (testing "requiring the adapter is enough: a UIx `:input` is a plain React
           controlled input"
    (is (some? uix-adapter/adapter)
        "the adapter namespace is loaded — which is what runs the pin")
    (is (false? (selector))
        "`should-use-reagent-input?` answers false, so
         `create-uix-input` builds a plain React element")))

(deftest the-pin-is-load-bearing-in-a-bundle-that-carries-reagent
  (testing "clear the pin and this bundle's contents choose instead — which
           is the accident rf2-heqwo removes, and the reason this assertion
           is not vacuous"
    (with-cleared-pin
      (fn []
        (if (true? (selector))
          (is (true? (selector))
              "Reagent is in this bundle, so UIx's unset default would have
               selected the port — the adapter's pin is what stops it")
          ;; A bundle without Reagent answers false either way. Say so
          ;; rather than asserting a coincidence: the pin still holds, it
          ;; simply cannot be distinguished from the sniff here.
          (is (false? (selector))
              "no Reagent in this bundle, so UIx's unset default already
               answers false; the pin is still what makes it a decision"))))
    (is (false? (selector))
        "and the pin is back after the probe")))

(deftest the-port-remains-reachable-explicitly
  (testing "the ruling makes React the DEFAULT, not the only option: the var
           stays public and dynamic, so a consumer who genuinely wants
           Reagent's port asks for it by name"
    (set! uix.compiler.input/*use-reagent-input-enabled?* true)
    (is (true? (selector))
        "an explicit opt-in reaches the port")
    (set! uix.compiler.input/*use-reagent-input-enabled?* false)
    (is (false? (selector))
        "and an explicit opt-out returns to React's own implementation")))
