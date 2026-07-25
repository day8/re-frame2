(ns re-frame.reg-view-devtools-elision-prod-test
  "Per Spec 006 §React DevTools support: under `:advanced` +
  `goog.DEBUG=false`, the reg-view* wrapper's `displayName`
  assignment elides — the closure compiler constant-folds the
  surrounding `(when interop/debug-enabled? ...)` gate and the entire
  branch DCEs.

  This file also asserts the rendered output of a registered view
  carries NO `_jsx*` props (rf2-rohdn — these never worked and were
  dropped; the absence under prod-mode is doubly-pinned by the
  fact that the entire reg-view* annotation site elides in prod).

  This file compiles under `:browser-test-prod-elision` (the dedicated
  shadow-cljs build with `goog.DEBUG=false` + `:advanced`); the
  matching CLJS-runtime test (`reg_view_devtools_cljs_test.cljs`)
  exercises the same surface under dev-mode.

  ## Fixtures carry an attrs map (rf2-wns8d)

  Every view whose assertions READ the rendered root's props declares a
  root with an EXPLICIT attrs map (`[:span {:id \"x\"} …]`), and that is
  load-bearing rather than incidental. Those tests assert the ABSENCE of
  a prop on the rendered root; a root with no attrs map has no props at
  all, so the absence reads true for a reason unrelated to the contract,
  and the assertion cannot go red no matter what the framework does to
  props it finds. (`reg-view-wrapped-fn-has-no-display-name-under-prod`
  reads `.-displayName` off the wrapped fn rather than the output, so it
  needs no attrs map and deliberately has none.)

  Measured: with the earlier attr-less `[:span \"hi\"]` fixture, a
  wrapper mutated to re-grow the pre-rf2-rohdn `_jsx*` props on an
  existing root attrs map left every assertion here GREEN. With the
  attrs map they go red, which is the whole point of an elision lane.

  So: do not simplify the fixtures back to a bare `[:tag \"text\"]`.
  Each assertion pins `(map? attrs)` first, so a fixture that drifts
  fails loudly instead of quietly certifying nothing.

  Naming convention: files ending in `-prod-test.cljs` are picked up
  ONLY by the `:browser-test-prod-elision` build."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(defn- root-attrs
  "The root element's attrs map, or nil when the root carries none.

  Every fixture that reads through this helper gives its root an
  EXPLICIT attrs map, so a nil return means the shape under test never
  materialised — which is why each caller pins `(map? …)` before reading
  a key out of it. See the §Fixtures carry an attrs map note above."
  [hiccup]
  (when (and (vector? hiccup) (map? (second hiccup)))
    (second hiccup)))

(deftest reg-view-output-has-no-jsx-source-props-under-prod
  (testing "Per rf2-rohdn: a registered view's rendered output carries
            NO `_jsxFileName`/`_jsxLineNumber`/`_jsxColumnNumber` props.
            Under :advanced + goog.DEBUG=false the entire reg-view*
            annotation branch DCEs anyway; rf2-rohdn also removed the
            JSX-prop emission so the same absence holds under dev-mode."
    (rf/reg-view* :rf.prod-elision-test/jsx-with-attrs
                  (fn [] [:span {:id "x"} "hi"]))
    (let [render (rf/view :rf.prod-elision-test/jsx-with-attrs)
          out    (render)
          attrs  (root-attrs out)]
      ;; Precondition, not decoration (rf2-wns8d): if this fails the three
      ;; key reads below are reading out of nil and certify nothing.
      (is (map? attrs)
          "the root carries an attrs map — the shape the assertions read")
      (is (= "x" (:id attrs)) "the author's own attrs pass through")
      (is (nil? (:_jsxFileName attrs))
          "NO :_jsxFileName on the rendered root")
      (is (nil? (:_jsxLineNumber attrs))
          "NO :_jsxLineNumber on the rendered root")
      (is (nil? (:_jsxColumnNumber attrs))
          "NO :_jsxColumnNumber on the rendered root"))))

(deftest reg-view-wrapped-fn-has-no-display-name-under-prod
  (testing "Per Spec 006 §React DevTools support production-elision:
            the reg-view wrapper's `displayName` assignment also rides
            the (when interop/debug-enabled? ...) gate, so the wrapped
            fn carries NO `displayName` under prod-mode."
    (rf/reg-view* :rf.prod-elision-test/dn-no-attr
                  (fn [] [:p "hi"]))
    (let [wrapped (rf/view :rf.prod-elision-test/dn-no-attr)]
      (is (some? wrapped) "the view is registered")
      (is (nil? (.-displayName ^js wrapped))
          "wrapped fn carries no .displayName under prod-mode — elision held"))))

(deftest jsx-literal-absent-from-rendered-output
  (testing "Defensive cross-check: scanning the rendered hiccup for the
            literal `_jsxFileName` keyword finds nothing under prod-mode.
            Doubly-pinned by rf2-rohdn (the framework no longer emits
            this prop at all) and by the elision gate (the whole branch
            DCEs in prod)."
    (rf/reg-view* :rf.prod-elision-test/jsx-literal-check
                  (fn [] [:p {:class "scan"} "scan me"]))
    (let [render (rf/view :rf.prod-elision-test/jsx-literal-check)
          out    (render)
          flat   (pr-str out)]
      ;; Same precondition as above: a root with no attrs map is a root a
      ;; prop-decorating regression never touches, so the scan below would
      ;; be reading a string that could not have carried the literal.
      (is (map? (root-attrs out))
          "the root carries an attrs map — the shape a prop regression lands on")
      (is (not (clojure.string/includes? flat "_jsxFileName"))
          "the literal _jsxFileName does not appear in the rendered output"))))
