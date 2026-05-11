(ns re-frame.adapter.helix-source-coord-dom-elision-prod-test
  "Per Spec 006 §Source-coord annotation production-elision contract
  (beads rf2-uwg5 / rf2-00li): under `:advanced` + `goog.DEBUG=false`,
  the Helix adapter's `wrap-view` body — the React.cloneElement-based
  data-rf2-source-coord injection branch — elides. A registered view's
  rendered React element carries NO source-coord attribute under
  prod-mode.

  This file is the Helix-side counterpart to
  `re-frame.source-coord-dom-elision-prod-test`. Per rf2-00li the
  Helix adapter publishes its `wrap-view` through the
  `:adapter/wrap-view` late-bind hook; `views/reg-view*` routes
  registered render-fns through it. Both surfaces (the substrate's
  `wrap-view` body in `helix.cljs` and the inline path in
  `views.cljs`) ride the `interop/debug-enabled?` gate so the entire
  annotation branch DCEs under prod-mode.

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
            [re-frame.adapter.helix :as helix-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter helix-adapter/adapter}))

(defn- react-element-source-coord
  "Pull `data-rf2-source-coord` off a React element's `.-props`, or nil.
  Defensively returns nil on every branch so the test assertions can
  use `nil?`."
  [el]
  (when (and el (.-props el))
    (aget (.-props el) "data-rf2-source-coord")))

;; ---- annotation elides under :advanced + goog.DEBUG=false ----------------

(deftest reg-view-output-has-no-source-coord-under-prod-helix
  (testing "Per Spec 006 §Source-coord annotation production-elision
            (rf2-00li, Helix side): a registered view's rendered React
            element carries NO data-rf2-source-coord attribute under
            :advanced + goog.DEBUG=false. The Closure compiler DCEs
            the wrap-view body's cloneElement branch via the
            `interop/debug-enabled?` gate."
    (let [user-fn (fn []
                    (React/createElement "span" #js {} "hi"))]
      (rf/reg-view* :rf.helix-prod-elision-test/no-attr user-fn)
      (let [render (rf/view :rf.helix-prod-elision-test/no-attr)
            out    (render)]
        (is (some? out))
        (is (= "span" (.-type out))
            "root element's type preserved")
        (is (nil? (react-element-source-coord out))
            "NO data-rf2-source-coord on the rendered root — elision contract holds")))))

(deftest reg-view-with-attrs-has-no-source-coord-under-prod-helix
  (testing "Even with an existing props map on the root, the wrapper does
            NOT inject data-rf2-source-coord under prod-mode. User props
            pass through; no extra key is added."
    (let [user-fn (fn []
                    (React/createElement "div"
                                         #js {:className "card"
                                              :id        "x"}
                                         "body"))]
      (rf/reg-view* :rf.helix-prod-elision-test/with-attrs user-fn)
      (let [render (rf/view :rf.helix-prod-elision-test/with-attrs)
            out    (render)
            props  (.-props out)]
        (is (= "div" (.-type out)))
        (is (some? props))
        (is (= "card" (aget props "className")) "user className preserved")
        (is (= "x"    (aget props "id")) "user id preserved")
        (is (nil? (aget props "data-rf2-source-coord"))
            "NO data-rf2-source-coord merged into the props — elision contract holds")))))
