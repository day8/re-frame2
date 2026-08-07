(ns re-frame.adapter.uix-source-coord-dom-elision-prod-test
  "Per Spec 006 §Source-coord annotation production-elision contract
  (beads rf2-uwg5 / rf2-00li): under `:advanced` + `goog.DEBUG=false`,
  the UIx adapter's `wrap-view` body — the React.cloneElement-based
  data-rf2-source-coord injection branch — elides. A registered view's
  rendered React element carries NO source-coord attribute under
  prod-mode.

  This file is the UIx-side counterpart to
  `re-frame.source-coord-dom-elision-prod-test`. Per rf2-00li the
  UIx adapter publishes its `wrap-view` through the
  `:adapter/wrap-view` late-bind hook; `views/reg-view*` routes
  registered render-fns through it. Both surfaces (the substrate's
  `wrap-view` body in `uix.cljs` and the inline path in `views.cljs`)
  ride the `interop/debug-enabled?` gate so the entire annotation
  branch DCEs under prod-mode.

  Naming convention: files ending in `-elision-prod-test.cljs` are
  picked up ONLY by the `:browser-test-prod-elision` build (per the
  build's `:ns-regexp` `-elision-prod-test$`). The default
  `:browser-test` and `:node-test` builds use different regexes and
  do NOT pick up this file. Running this file under
  `goog.DEBUG=true` would FAIL — the assertion is 'no annotation',
  which is only true under prod-mode."
  (:require ["react" :as React]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.uix :as uix-adapter]
            ;; rf2-5g21s — the react-element-attr accessor lives in the
            ;; dependency-free shared test home (hoisted there to dedupe
            ;; the since-removed Helix twin); reference it rather than
            ;; carry a copy. Kept separate from react-shared-suite so this
            ;; prod-elision build does not pull the suite's heavy deps.
            [re-frame.adapter.react-test-support :refer [react-element-attr]]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter}))

;; ---- annotation elides under :advanced + goog.DEBUG=false ----------------

(deftest reg-view-output-has-no-source-coord-under-prod-uix
  (testing "Per Spec 006 §Source-coord annotation production-elision
            (rf2-00li, UIx side): a registered view's rendered React
            element carries NO data-rf2-source-coord attribute under
            :advanced + goog.DEBUG=false. The Closure compiler DCEs
            the wrap-view body's cloneElement branch via the
            `interop/debug-enabled?` gate."
    (let [user-fn (fn []
                    (React/createElement "span" #js {} "hi"))]
      (rf/reg-view* :rf.uix-prod-elision-test/no-attr user-fn)
      (let [render (rf/view :rf.uix-prod-elision-test/no-attr)
            out    (render)]
        (is (some? out))
        (is (= "span" (.-type out))
            "root element's type preserved")
        (is (nil? (react-element-attr out "data-rf2-source-coord"))
            "NO data-rf2-source-coord on the rendered root — elision contract holds")
        (is (nil? (react-element-attr out "data-rf-view"))
            "NO data-rf-view on the rendered root — view-id tagging rides the
             same interop/debug-enabled? gate and elides too (rf2-ihcib)")))))

(deftest reg-view-with-attrs-has-no-source-coord-under-prod-uix
  (testing "Even with an existing props map on the root, the wrapper does
            NOT inject data-rf2-source-coord under prod-mode. User props
            pass through; no extra key is added."
    (let [user-fn (fn []
                    (React/createElement "div"
                                         #js {:className "card"
                                              :id        "x"}
                                         "body"))]
      (rf/reg-view* :rf.uix-prod-elision-test/with-attrs user-fn)
      (let [render (rf/view :rf.uix-prod-elision-test/with-attrs)
            out    (render)
            props  (.-props out)]
        (is (= "div" (.-type out)))
        (is (some? props))
        (is (= "card" (aget props "className")) "user className preserved")
        (is (= "x"    (aget props "id")) "user id preserved")
        (is (nil? (aget props "data-rf2-source-coord"))
            "NO data-rf2-source-coord merged into the props — elision contract holds")
        (is (nil? (aget props "data-rf-view"))
            "NO data-rf-view merged into the props — view-id tagging elides on the
             same gate (rf2-ihcib)")))))

(deftest wrap-view-head-has-no-display-name-under-prod-uix
  (testing "rf2-976bw: the spine's displayName stamp now calls
            `re-frame.performance/entry-id` to build the published name.
            That call sits inside the same `interop/debug-enabled?` arm as
            the rest of `wrap-view`'s body, so under :advanced +
            goog.DEBUG=false the arm DCEs, `wrap-view` returns the user-fn
            unchanged, and NO per-view name string — nor the label builder
            reached through it — survives into the bundle. The Reagent-side
            counterpart is
            `re-frame.reg-view-devtools-elision-prod-test/reg-view-wrapped-fn-has-no-display-name-under-prod`."
    (let [user-fn (fn [] (React/createElement "span" #js {:id "x"} "hi"))]
      (rf/reg-view* :rf.uix-prod-elision-test/dn-elided user-fn)
      (let [wrapped (rf/view :rf.uix-prod-elision-test/dn-elided)]
        (is (some? wrapped) "the view is registered")
        (is (nil? (.-displayName ^js wrapped))
            "no .displayName on the registered view under prod-mode")
        (is (nil? (.-displayName ^js (uix-adapter/wrap-view
                                       :rf.uix-prod-elision-test/dn-elided-head
                                       {} user-fn)))
            "no .displayName on a directly-built wrap-view head either —
             the whole arm elided")))))
