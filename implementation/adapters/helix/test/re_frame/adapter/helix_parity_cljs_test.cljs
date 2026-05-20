(ns re-frame.adapter.helix-parity-cljs-test
  "Per Spec 006 §Adapter shipping convention + rf2-2qit Decision 5: the
  Helix adapter must match the Reagent adapter's render-time contracts
  for hot-reload, render-key, and source-coord DOM annotation.

  This file is the parity counterpart to:
    - implementation/adapters/reagent/test/re_frame/hot_reload_cljs_test.cljs
    - implementation/adapters/reagent/test/re_frame/render_key_cljs_test.cljs
    - implementation/adapters/reagent/test/re_frame/source_coord_dom_cljs_test.cljs

  Per bead rf2-v1y7. The shape mirrors the Reagent tests; each block
  asserts the Helix adapter respects the same contract.

  ---------------------------------------------------------------------------
  rf2-00li: the source-coord-DOM-annotation test is now active. The
  Helix adapter publishes its `wrap-view` (helix.cljs) through the
  `:adapter/wrap-view` late-bind hook; `views/reg-view*` consults the
  hook and routes registered render-fns through the substrate-side
  wrapper, which uses `React.cloneElement` to inject
  `data-rf2-source-coord` on the rendered root React element.
  ---------------------------------------------------------------------------"
  (:require ["react" :as React]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.helix :as helix-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views :as views]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
    {:adapter helix-adapter/adapter}))

;; ---- (1) Hot-reload contract ----------------------------------------------

(deftest view-re-register-causes-rerender-helix
  (testing "after re-registering a view, the next registry lookup returns
            the new body — the contract underlying hot-reload"
    (let [observed (atom nil)]
      (rf/reg-view* :rf.helix-parity-test/probe
        (fn []
          (reset! observed :body-v1)
          :v1-output))
      ((rf/view :rf.helix-parity-test/probe))
      (is (= :body-v1 @observed)
          "v1 body ran on first render")

      ;; Re-register with a DIFFERENT body.
      (rf/reg-view* :rf.helix-parity-test/probe
        (fn []
          (reset! observed :body-v2)
          :v2-output))
      ((rf/view :rf.helix-parity-test/probe))
      (is (= :body-v2 @observed)
          "after re-registration, the next render mutates observed to v2"))))

;; ---- (2) Render-key contract ----------------------------------------------

(deftest current-render-key-anonymous-fallback-helix
  (testing "outside a render, current-render-key returns the documented
            anonymous fallback [:rf.view/anonymous nil]"
    (is (= [:rf.view/anonymous nil] (views/current-render-key))
        "current-render-key reads the anonymous fallback outside any render")
    (is (nil? views/*render-key*)
        "*render-key* is nil outside any render cycle")))

;; ---- (3) Source-coord DOM annotation helper -------------------------------

(deftest format-source-coord-helper-shape-helix
  (testing "the Helix adapter's wrap-view fn is callable and dispatches
            to the user fn"
    (let [out-from-fn (atom nil)
          wrapped     (helix-adapter/wrap-view
                        :rf.helix-parity-test/sample
                        {:line 42 :column 7}
                        (fn []
                          (reset! out-from-fn :ran)
                          nil))]
      (is (fn? wrapped) "wrap-view returns a fn")
      (wrapped)
      (is (= :ran @out-from-fn)
          "the wrapped fn invokes the user fn"))))

;; --- (rf2-00li) — DOM-annotation through reg-view ------------------------
;;
;; The full contract — `(rf/reg-view* id render-fn)` produces, when called,
;; a React element whose root carries `data-rf2-source-coord` — depends on
;; reg-view* dispatching through the installed adapter's `wrap-view`. With
;; the rf2-00li wiring in place, the Helix adapter publishes its `wrap-view`
;; through the `:adapter/wrap-view` late-bind hook and `views/reg-view*`
;; routes registered render-fns through it (instead of through the
;; hiccup-shape inline walk, which would mis-classify a React element
;; as a non-DOM root and skip annotation).

(defn- react-element-source-coord
  "Pull `data-rf2-source-coord` off a React element's `.-props`, or nil."
  [el]
  (when (and el (.-props el))
    (aget (.-props el) "data-rf2-source-coord")))

(deftest reg-view-produces-source-coord-annotation-helix
  (testing "Per rf2-00li: reg-view* under the Helix adapter routes the
            render-fn through (adapter/wrap-view id metadata user-fn) so
            the rendered React element carries data-rf2-source-coord on
            its root. The user-fn returns a real React element (the
            shape a Helix component head produces); the wrapper's
            React.cloneElement injects the attr."
    (let [user-fn (fn []
                    (React/createElement "span" #js {} "hi"))]
      (rf/reg-view* :rf.helix-parity-test/annotated user-fn)
      (let [render (rf/view :rf.helix-parity-test/annotated)
            out    (render)]
        (is (some? out)
            "the registered fn returned a non-nil React element")
        (is (= "span" (.-type out))
            "root element's type is preserved (still a span)")
        (let [attr (react-element-source-coord out)]
          (is (string? attr)
              "data-rf2-source-coord is present on the root element")
          ;; <ns>:<sym>:<line>:<col> — programmatic reg-view* has no
          ;; macro-captured coords so line/col are `?`.
          (is (= "rf.helix-parity-test:annotated:?:?" attr)
              (str "attribute value matches "
                   "<ns>:<sym>:?:? for programmatic reg-view*; got "
                   (pr-str attr))))))))

(deftest reg-view-non-dom-root-is-exempt-helix
  (testing "Per Spec 006 §Source-coord annotation (Fragment exemption):
            a React element whose `type` is not a DOM-tag string (e.g.
            a function component, or React.Fragment) passes through
            unchanged — the cloneElement injection is skipped and the
            output's props are untouched."
    (let [Frag    (.-Fragment React)
          user-fn (fn []
                    (React/createElement Frag nil
                                         (React/createElement "p" nil "a")
                                         (React/createElement "p" nil "b")))]
      (rf/reg-view* :rf.helix-parity-test/fragment-root user-fn)
      (let [render (rf/view :rf.helix-parity-test/fragment-root)
            out    (render)]
        (is (some? out))
        (is (identical? Frag (.-type out))
            "Fragment root preserved as the element type")
        (is (nil? (react-element-source-coord out))
            "no data-rf2-source-coord on the Fragment root (exempt)")))))

(deftest reg-view-preserves-user-supplied-source-coord-helix
  (testing "Per rf2-owioi: when the user component's root element already
            carries `data-rf2-source-coord`, the wrapper passes it through
            unchanged — no cloneElement, no attribute clobber. Guards the
            existing-attr short-circuit in `inject-source-coord-attr`."
    (let [user-attr "users.namespace:my-component:1:1"
          user-fn   (fn []
                      (React/createElement "div"
                        #js {"data-rf2-source-coord" user-attr}
                        "hi"))]
      (rf/reg-view* :rf.helix-parity-test/user-attr-root user-fn)
      (let [render (rf/view :rf.helix-parity-test/user-attr-root)
            out    (render)]
        (is (some? out))
        (is (= "div" (.-type out))
            "root element's type is preserved")
        (is (= user-attr (react-element-source-coord out))
            "the user-supplied data-rf2-source-coord survives the wrap-view pass")))))
