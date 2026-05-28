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

  Naming convention: files ending in `-prod-test.cljs` are picked up
  ONLY by the `:browser-test-prod-elision` build."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(defn- root-attrs [hiccup]
  (when (and (vector? hiccup) (map? (second hiccup)))
    (second hiccup)))

(deftest reg-view-output-has-no-jsx-source-props-under-prod
  (testing "Per rf2-rohdn: a registered view's rendered output carries
            NO `_jsxFileName`/`_jsxLineNumber`/`_jsxColumnNumber` props.
            Under :advanced + goog.DEBUG=false the entire reg-view*
            annotation branch DCEs anyway; rf2-rohdn also removed the
            JSX-prop emission so the same absence holds under dev-mode."
    (rf/reg-view* :rf.prod-elision-test/jsx-no-attrs
                  (fn [] [:span "hi"]))
    (let [render (rf/view :rf.prod-elision-test/jsx-no-attrs)
          out    (render)
          attrs  (root-attrs out)]
      (is (or (nil? attrs)
              (nil? (:_jsxFileName attrs)))
          "NO :_jsxFileName on the rendered root")
      (is (or (nil? attrs)
              (nil? (:_jsxLineNumber attrs)))
          "NO :_jsxLineNumber on the rendered root")
      (is (or (nil? attrs)
              (nil? (:_jsxColumnNumber attrs)))
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
                  (fn [] [:p "scan me"]))
    (let [render (rf/view :rf.prod-elision-test/jsx-literal-check)
          out    (render)
          flat   (pr-str out)]
      (is (not (clojure.string/includes? flat "_jsxFileName"))
          "the literal _jsxFileName does not appear in the rendered output"))))
