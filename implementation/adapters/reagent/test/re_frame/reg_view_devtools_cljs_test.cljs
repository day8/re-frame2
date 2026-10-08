(ns re-frame.reg-view-devtools-cljs-test
  "Per Spec 006 §React DevTools support: the reg-view wrapper sets a
  React `displayName` to the
  registered view-id's performance/display projection, and the React
  Context backing the frame-provider carries a recognisable
  `displayName` for the Context inspector.

  Spec 009 §Naming convention makes the `rf:render:<id>` measure name and
  the displayName ONE identifier, so they are asserted equal rather than
  each well-formed. No JSX-shaped `_jsx*` props reach rendered hiccup:
  Reagent would pass them through as DOM attributes, and DevTools reads
  `__source` off `React.createElement` anyway. Production elision is the
  elision-probe build's and `reg_view_devtools_elision_prod_test.cljs`'s."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.context :as rf.adapter.context]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.performance :as rf.performance]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; ---- helpers ---------------------------------------------------------------

(defn- root-attrs
  "Return the root attrs map of a hiccup vector, or nil."
  [hiccup]
  (when (and (vector? hiccup) (map? (second hiccup)))
    (second hiccup)))

;; ---- displayName -----------------------------------------------------------

(deftest display-name-and-render-measure-are-one-identifier
  (testing "THE equality row. Spec 009 §Naming convention makes
            the `<id>` in `rf:render:<id>` and the id the substrate
            publishes to the developer ONE identifier. Asserting each half
            is separately well-formed would let them drift; this asserts
            they are the same string, so a change to either spelling that
            is not made to both fails here."
    (rf/reg-view ^{:rf/id :rf.devtools-test/one-identifier} one-id-view
                 [] [:p "x"])
    (let [id      :rf.devtools-test/one-identifier
          wrapped (rf/view id)]
      ;; Against the literal too, so two empty strings cannot satisfy it.
      (is (= ["rf:render:rf.devtools-test/one-identifier"
              "rf:render:rf.devtools-test/one-identifier"]
             [(rf.performance/build-name :render id)
              (str "rf:render:" (.-displayName ^js wrapped))])
          "the render measure name is exactly \"rf:render:\" + displayName, in the documented shape"))))

;; ---- JSX source-coord props (must NOT be injected) -----------------------

(deftest jsx-source-props-not-injected-by-macro-path
  (testing "a macro-registered view's rendered hiccup carries NO
            `_jsx*` props (they would only add dev-console noise)"
    (rf/reg-view ^{:rf/id :rf.devtools-test/no-jsx-macro}
                 no-jsx-macro-view []
      [:section "body"])
    (let [render (rf/view :rf.devtools-test/no-jsx-macro)
          out    (render)
          attrs  (root-attrs out)]
      (is (= [true true nil nil nil]
             [(string? (:data-rf2-source-coord attrs)) (string? (:data-rf-view attrs))
              (:_jsxFileName attrs) (:_jsxLineNumber attrs) (:_jsxColumnNumber attrs)])
          "the data-* attributes still ride the wrapper's attrs map, with no _jsx* props"))))

;; ---- React Context displayName --------------------------------------------

(deftest frame-context-display-name-is-rf2-frame
  (testing "the React Context backing the frame-provider carries a
            human-readable displayName so React DevTools' Context
            inspector renders `rf2-frame.Provider` rather than the
            opaque default"
    (is (= "rf2-frame"
           (.-displayName ^js rf.adapter.context/frame-context))
        "frame-context.displayName is set to \"rf2-frame\"")))
