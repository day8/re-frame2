(ns re-frame.reg-view-devtools-cljs-test
  "Per Spec 006 §React DevTools support (rf2-fa4ly): the reg-view
  wrapper sets a React `displayName` to the registered view-id and
  the React Context backing the frame-provider carries a recognisable
  `displayName` for the Context inspector.

  Coverage:

    - `displayName` on the wrapped fn matches `(str view-id)` for
      auto-derived and `:rf/id`-overridden registrations.
    - The frame-context's React `displayName` is set to `\"rf2-frame\"`.
    - Regression (rf2-rohdn): NO `:_jsxFileName` / `:_jsxLineNumber`
      / `:_jsxColumnNumber` JSX-shaped source-coord props are injected
      into rendered hiccup. The earlier rf2-fa4ly injection never
      worked (Reagent passed them through as DOM attributes; DevTools
      reads `__source` from React.createElement, not element props)
      and rf2-rohdn dropped it.

  Production-elision is verified separately by the elision-probe
  build (sentinel `rf2-frame` in `scripts/check-elision.cjs`) and by
  `reg_view_devtools_elision_prod_test.cljs`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            [re-frame.views])
  (:require-macros [re-frame.core :refer [reg-view]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---- helpers ---------------------------------------------------------------

(defn- root-attrs
  "Return the root attrs map of a hiccup vector, or nil."
  [hiccup]
  (when (and (vector? hiccup) (map? (second hiccup)))
    (second hiccup)))

;; ---- displayName -----------------------------------------------------------

(deftest display-name-on-wrapped-fn-matches-view-id
  (testing "the reg-view wrapper's React displayName is (str view-id)
            so React DevTools shows `<:rf.devtools-test/dn-auto>` in
            the component tree"
    (rf/reg-view dn-auto [] [:p "hi"])
    (let [wrapped (rf/view :re-frame.reg-view-devtools-cljs-test/dn-auto)]
      (is (some? wrapped) "the view is registered")
      (is (= ":re-frame.reg-view-devtools-cljs-test/dn-auto"
             (.-displayName ^js wrapped))
          "displayName matches (str id) on the wrapped fn"))))

(deftest display-name-honours-rf-id-override
  (testing "an `:rf/id`-overridden registration carries the override id in
            displayName"
    (rf/reg-view ^{:rf/id :rf.devtools-test/dn-explicit} dn-meta-view
                 [] [:span "ok"])
    (let [wrapped (rf/view :rf.devtools-test/dn-explicit)]
      (is (some? wrapped))
      (is (= ":rf.devtools-test/dn-explicit"
             (.-displayName ^js wrapped))
          "the override id drives displayName"))))

;; ---- JSX source-coord props (regression: must NOT be injected) -----------
;;
;; rf2-rohdn removed the JSX-prop injection (rf2-fa4ly): the props leaked
;; to the DOM as attributes, triggering React's "unrecognised prop on a
;; DOM element" console warnings, AND the feature didn't deliver its
;; intended benefit (React DevTools' "View source" reads `__source`
;; off `React.createElement`'s third arg — set by
;; `@babel/plugin-transform-react-jsx-source` at JSX-compile time, NOT
;; from element props). These regression tests pin the absence so a
;; well-intentioned re-introduction doesn't bring the noise back.

(deftest jsx-source-props-not-injected-by-macro-path
  (testing "a macro-registered view's rendered hiccup carries NO
            `_jsx*` props (rf2-rohdn regression — feature never
            worked, dropped to remove dev-console noise)"
    (rf/reg-view ^{:rf/id :rf.devtools-test/no-jsx-macro}
                 no-jsx-macro-view []
      [:section "body"])
    (let [render (rf/view :rf.devtools-test/no-jsx-macro)
          out    (render)
          attrs  (root-attrs out)]
      (is (map? attrs) "attrs map still spliced in for data-* attributes")
      (is (string? (:data-rf2-source-coord attrs))
          "data-rf2-source-coord still present (rides the same wrapper)")
      (is (string? (:data-rf-view attrs))
          "data-rf-view still present (rides the same wrapper)")
      (is (nil? (:_jsxFileName attrs))     "_jsxFileName not injected")
      (is (nil? (:_jsxLineNumber attrs))   "_jsxLineNumber not injected")
      (is (nil? (:_jsxColumnNumber attrs)) "_jsxColumnNumber not injected"))))

(deftest jsx-source-props-not-injected-by-programmatic-path
  (testing "a programmatic `reg-view*` registration also carries NO
            `_jsx*` props (rf2-rohdn — covers both macro + non-macro
            entry points)"
    (rf/reg-view* :rf.devtools-test/no-jsx-prog
                  (fn [] [:em "p"]))
    (let [render (rf/view :rf.devtools-test/no-jsx-prog)
          out    (render)
          attrs  (root-attrs out)]
      (is (map? attrs))
      (is (string? (:data-rf2-source-coord attrs))
          "data-rf2-source-coord still present (degrades to <ns>:<sym>:?:?)")
      (is (nil? (:_jsxFileName attrs))     "_jsxFileName not injected")
      (is (nil? (:_jsxLineNumber attrs))   "_jsxLineNumber not injected")
      (is (nil? (:_jsxColumnNumber attrs)) "_jsxColumnNumber not injected"))))

(deftest jsx-source-props-preserve-user-supplied
  (testing "if user code stamps `_jsx*` props themselves (e.g. a hand-
            crafted React-DevTools shim), the framework does NOT
            overwrite or strip them — passthrough is opaque (rf2-rohdn)"
    (rf/reg-view ^{:rf/id :rf.devtools-test/user-jsx} user-jsx-view []
      [:p {:_jsxFileName "by-user.cljs" :_jsxLineNumber 99} "ok"])
    (let [render (rf/view :rf.devtools-test/user-jsx)
          out    (render)
          attrs  (root-attrs out)]
      (is (= "by-user.cljs" (:_jsxFileName attrs))
          "user-supplied :_jsxFileName preserved")
      (is (= 99 (:_jsxLineNumber attrs))
          "user-supplied :_jsxLineNumber preserved"))))

;; ---- React Context displayName --------------------------------------------

(deftest frame-context-display-name-is-rf2-frame
  (testing "the React Context backing the frame-provider carries a
            human-readable displayName so React DevTools' Context
            inspector renders `rf2-frame.Provider` rather than the
            opaque default"
    (is (= "rf2-frame"
           (.-displayName ^js adapter-context/frame-context))
        "frame-context.displayName is set to \"rf2-frame\"")))
