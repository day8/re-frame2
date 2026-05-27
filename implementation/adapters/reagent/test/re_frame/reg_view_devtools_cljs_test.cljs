(ns re-frame.reg-view-devtools-cljs-test
  "Per Spec 006 §React DevTools support (rf2-fa4ly): the reg-view
  wrapper sets a React `displayName` to the registered view-id, the
  source-coord injection point emits JSX-shaped source-coord props
  alongside the DOM attribute, and the React Context backing the
  frame-provider carries a recognisable `displayName` for the Context
  inspector.

  Coverage:

    - `displayName` on the wrapped fn matches `(str view-id)` for
      auto-derived and `:rf/id`-overridden registrations.
    - JSX source-coord props (`:_jsxFileName`, `:_jsxLineNumber`,
      `:_jsxColumnNumber`) land on the same root element as
      `:data-rf2-source-coord`.
    - JSX props are absent on programmatic `reg-view*` registrations
      without macro-captured coords (graceful degrade).
    - The frame-context's React `displayName` is set to `\"rf2-frame\"`.

  Production-elision is verified separately by the elision-probe
  build (sentinels `_jsxFileName` + `rf2-frame` in
  `scripts/check-elision.cjs`) and by
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

;; ---- JSX source-coord props -----------------------------------------------

(deftest jsx-source-props-injected-with-data-rf2-source-coord
  (testing "the reg-view wrapper merges JSX-shaped source-coord props
            (`:_jsxFileName`, `:_jsxLineNumber`, `:_jsxColumnNumber`)
            onto the same root element that carries
            `:data-rf2-source-coord` — React DevTools' \"View source\"
            gesture reads them to jump to the reg-view definition"
    (rf/reg-view ^{:rf/id :rf.devtools-test/jsx-root}
                 jsx-root-view []
      [:section "body"])
    (let [render (rf/view :rf.devtools-test/jsx-root)
          out    (render)
          attrs  (root-attrs out)]
      (is (map? attrs) "the root has an attrs map (spliced in by the wrapper)")
      (is (string? (:data-rf2-source-coord attrs))
          "data-rf2-source-coord present (parity)")
      (is (string? (:_jsxFileName attrs))
          "_jsxFileName present alongside data-rf2-source-coord")
      (is (integer? (:_jsxLineNumber attrs))
          "_jsxLineNumber is an integer")
      ;; :column may be absent under the macro-emitted prod-coords-form,
      ;; but in the dev build the macro emits it.
      (is (or (nil? (:_jsxColumnNumber attrs))
              (integer? (:_jsxColumnNumber attrs)))
          "_jsxColumnNumber is integer or omitted"))))

(deftest jsx-source-props-merge-into-existing-attrs
  (testing "an existing attrs map is preserved alongside the JSX props"
    (rf/reg-view ^{:rf/id :rf.devtools-test/jsx-with-attrs}
                 jsx-with-attrs-view []
      [:div {:class "card"} "body"])
    (let [render (rf/view :rf.devtools-test/jsx-with-attrs)
          out    (render)
          attrs  (root-attrs out)]
      (is (= "card" (:class attrs)) "user :class preserved")
      (is (string? (:_jsxFileName attrs))
          "_jsxFileName merged in alongside the user's attrs"))))

(deftest jsx-source-props-absent-on-programmatic-registration
  (testing "a programmatic `reg-view*` without macro source-coords
            carries NO JSX props (the contract degrades gracefully
            — React DevTools' \"View source\" needs a real file+line)"
    (rf/reg-view* :rf.devtools-test/jsx-programmatic
                  (fn [] [:em "p"]))
    (let [render (rf/view :rf.devtools-test/jsx-programmatic)
          out    (render)
          attrs  (root-attrs out)]
      (is (map? attrs)
          "attrs map still spliced in for data-rf2-source-coord / data-rf-view")
      (is (string? (:data-rf2-source-coord attrs))
          "data-rf2-source-coord still present (degrades to <ns>:<sym>:?:?)")
      (is (nil? (:_jsxFileName attrs))
          "_jsxFileName absent — no captured :file/:line to emit"))))

(deftest user-supplied-jsx-prop-wins
  (testing "a render-fn that already set _jsxFileName is not overwritten —
            composability with hand-stamped tools"
    (rf/reg-view ^{:rf/id :rf.devtools-test/user-jsx} user-jsx-view []
      [:p {:_jsxFileName "by-user.cljs" :_jsxLineNumber 99} "ok"])
    (let [render (rf/view :rf.devtools-test/user-jsx)
          out    (render)
          attrs  (root-attrs out)]
      (is (= "by-user.cljs" (:_jsxFileName attrs))
          "user-supplied :_jsxFileName wins")
      (is (= 99 (:_jsxLineNumber attrs))
          "user-supplied :_jsxLineNumber wins"))))

;; ---- React Context displayName --------------------------------------------

(deftest frame-context-display-name-is-rf2-frame
  (testing "the React Context backing the frame-provider carries a
            human-readable displayName so React DevTools' Context
            inspector renders `rf2-frame.Provider` rather than the
            opaque default"
    (is (= "rf2-frame"
           (.-displayName ^js adapter-context/frame-context))
        "frame-context.displayName is set to \"rf2-frame\"")))
