(ns re-frame.adapter.uix-parity-cljs-test
  "Per Spec 006 §Adapter shipping convention + rf2-3yij Decision 5: the
  UIx adapter must match the Reagent adapter's render-time contracts for
  hot-reload, render-key, and source-coord DOM annotation.

  This file is the parity counterpart to:
    - implementation/adapters/reagent/test/re_frame/hot_reload_cljs_test.cljs
    - implementation/adapters/reagent/test/re_frame/render_key_cljs_test.cljs
    - implementation/adapters/reagent/test/re_frame/source_coord_dom_cljs_test.cljs

  Per bead rf2-v1y7. The shape mirrors the Reagent tests; each block
  asserts the UIx adapter respects the same contract.

  ---------------------------------------------------------------------------
  rf2-00li: the source-coord-DOM-annotation test is now active. The
  UIx adapter publishes its `wrap-view` (uix.cljs) through the
  `:adapter/wrap-view` late-bind hook; `views/reg-view*` consults the
  hook and routes registered render-fns through the substrate-side
  wrapper, which uses `React.cloneElement` to inject
  `data-rf2-source-coord` on the rendered root React element.
  ---------------------------------------------------------------------------"
  (:require ["react" :as React]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views :as views]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter uix-adapter/adapter}))

;; ---- (1) Hot-reload contract ----------------------------------------------
;;
;; Per Spec 001 §Hot-reload semantics rule 4: re-registering a view at
;; runtime causes a subsequent registry-driven render to invoke the new
;; render fn. The Reagent test exercises this through registry lookup
;; (`rf/view`); the UIx-side contract is identical at the registry
;; layer — UIx components that call into the re-frame registry to
;; resolve a view-id pick up the new body on the next render cycle.

(deftest view-re-register-causes-rerender-uix
  (testing "after re-registering a view, the next registry lookup returns
            the new body — the contract underlying hot-reload"
    (let [observed (atom nil)]
      (rf/reg-view* :rf.uix-parity-test/probe
        (fn []
          (reset! observed :body-v1)
          :v1-output))
      ;; Resolve via the registry — same lookup the substrate's render
      ;; cycle would do — and invoke.
      ((rf/view :rf.uix-parity-test/probe))
      (is (= :body-v1 @observed)
          "v1 body ran on first render")

      ;; Re-register with a DIFFERENT body.
      (rf/reg-view* :rf.uix-parity-test/probe
        (fn []
          (reset! observed :body-v2)
          :v2-output))
      ;; Next render — fresh registry resolution — runs v2.
      ((rf/view :rf.uix-parity-test/probe))
      (is (= :body-v2 @observed)
          "after re-registration, the next render mutates observed to v2"))))

;; ---- (2) Render-key contract ----------------------------------------------
;;
;; Per Spec 004 §Render-tree primitives: `current-render-key` returns
;; `[<view-id> <instance-token>]` for registered views, and the
;; documented anonymous fallback `[:rf.view/anonymous nil]` for plain
;; fns (no reg-view wrapper).
;;
;; Outside a render, `*render-key*` is unbound; `current-render-key`
;; surfaces the anonymous fallback. This contract is independent of
;; substrate — it lives in `re-frame.views` which both adapters
;; consume.

(deftest current-render-key-anonymous-fallback-uix
  (testing "outside a render, current-render-key returns the documented
            anonymous fallback [:rf.view/anonymous nil]"
    ;; This is the substrate-agnostic baseline. Both Reagent and UIx
    ;; rely on views/current-render-key to surface the anonymous
    ;; tuple when `*render-key*` is unbound; the lookup does not
    ;; consult the substrate.
    (is (= [:rf.view/anonymous nil] (views/current-render-key))
        "current-render-key reads the anonymous fallback outside any render")
    (is (nil? views/*render-key*)
        "*render-key* is nil outside any render cycle")))

;; ---- (3) Source-coord DOM annotation contract -----------------------------
;;
;; Per Spec 006 §Source-coord annotation: a registered view under
;; goog.DEBUG=true must produce `data-rf2-source-coord` on its rendered
;; root DOM element. The Reagent adapter wires this inline in
;; views/reg-view*; rf2-00li covers the UIx wiring gap.
;;
;; The standalone format-source-coord helper IS available — let's pin
;; the helper's behaviour even though full integration isn't yet wired.

(deftest format-source-coord-helper-shape-uix
  (testing "the UIx adapter's source-coord format helper produces the
            canonical <ns>:<sym>:<line>:<col> shape"
    ;; The helper is private; exercise it indirectly through
    ;; wrap-view's coord-attr computation. We can't trivially call
    ;; a private fn, but we can call wrap-view with a known id /
    ;; metadata and inspect the output. (wrap-view is public per
    ;; the spec — line 375.)
    (let [out-from-fn (atom nil)
          wrapped     (uix-adapter/wrap-view :rf.uix-parity-test/sample
                                             {:line 42 :column 7}
                                             (fn []
                                               (reset! out-from-fn :ran)
                                               nil))]
      ;; wrap-view returns a callable; invoke it to confirm it doesn't
      ;; throw. We can't easily inspect the React.cloneElement output
      ;; without a real React render context, so the assertion is
      ;; "wrap-view is a real fn, it accepts the id+metadata+user-fn
      ;; tuple, and the inner fn ran".
      (is (fn? wrapped) "wrap-view returns a fn")
      (wrapped)
      (is (= :ran @out-from-fn)
          "the wrapped fn invokes the user fn"))))

;; --- (rf2-00li) — DOM-annotation through reg-view ------------------------
;;
;; The full contract — `(rf/reg-view* id render-fn)` produces, when called,
;; a React element whose root carries `data-rf2-source-coord` — depends on
;; reg-view* dispatching through the installed adapter's `wrap-view`. With
;; the rf2-00li wiring in place, the UIx adapter publishes its `wrap-view`
;; through the `:adapter/wrap-view` late-bind hook and `views/reg-view*`
;; routes registered render-fns through it (instead of through the
;; hiccup-shape inline walk, which would mis-classify a React element
;; as a non-DOM root and skip annotation).

(defn- react-element-source-coord
  "Pull `data-rf2-source-coord` off a React element's `.-props`, or nil."
  [el]
  (when (and el (.-props el))
    (aget (.-props el) "data-rf2-source-coord")))

(deftest reg-view-produces-source-coord-annotation-uix
  (testing "Per rf2-00li: reg-view* under the UIx adapter routes the
            render-fn through (adapter/wrap-view id metadata user-fn) so
            the rendered React element carries data-rf2-source-coord on
            its root. The user-fn returns a real React element (the
            shape an UIx component head produces); the wrapper's
            React.cloneElement injects the attr."
    (let [user-fn (fn []
                    (React/createElement "span" #js {} "hi"))]
      (rf/reg-view* :rf.uix-parity-test/annotated user-fn)
      (let [render (rf/view :rf.uix-parity-test/annotated)
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
          (is (= "rf.uix-parity-test:annotated:?:?" attr)
              (str "attribute value matches "
                   "<ns>:<sym>:?:? for programmatic reg-view*; got "
                   (pr-str attr))))))))

(deftest reg-view-non-dom-root-is-exempt-uix
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
      (rf/reg-view* :rf.uix-parity-test/fragment-root user-fn)
      (let [render (rf/view :rf.uix-parity-test/fragment-root)
            out    (render)]
        (is (some? out))
        (is (identical? Frag (.-type out))
            "Fragment root preserved as the element type")
        (is (nil? (react-element-source-coord out))
            "no data-rf2-source-coord on the Fragment root (exempt)")))))

(deftest reg-view-preserves-user-supplied-source-coord-uix
  (testing "Per rf2-owioi: when the user component's root element already
            carries `data-rf2-source-coord`, the wrapper passes it through
            unchanged — no cloneElement, no attribute clobber. Guards the
            existing-attr short-circuit in `inject-source-coord-attr`."
    (let [user-attr "users.namespace:my-component:1:1"
          user-fn   (fn []
                      (React/createElement "div"
                        #js {"data-rf2-source-coord" user-attr}
                        "hi"))]
      (rf/reg-view* :rf.uix-parity-test/user-attr-root user-fn)
      (let [render (rf/view :rf.uix-parity-test/user-attr-root)
            out    (render)]
        (is (some? out))
        (is (= "div" (.-type out))
            "root element's type is preserved")
        (is (= user-attr (react-element-source-coord out))
            "the user-supplied data-rf2-source-coord survives the wrap-view pass")))))
