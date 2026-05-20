(ns re-frame.frame-provider-render-key-elision-prod-test
  "Per Spec 009 §Production builds (bead rf2-l7hlm) — `:advanced` +
  `goog.DEBUG=false` runtime contract for the dev-only frame-provider /
  render-tree machinery owned by `re-frame.views`. Companion to:

   - `re-frame.source-coord-dom-elision-prod-test` (rf2-uwg5) — pins
     the `data-rf2-source-coord` DOM-injection elision specifically.
   - `re-frame.trace-listener-elision-prod-test` (rf2-2zdu) — pins the
     trace surface elision.

  This file pins the *render-key* binding + `:view/render` trace
  surface that the `reg-view*` wrapper installs on every rendered view.
  Under prod-mode the `emit-render-trace!` body (gated on
  `interop/debug-enabled?`) DCEs, and the `view-coord-attr` capture
  also DCEs — but the rest of the wrapper (the `:contextType`
  attachment, the `*render-key*` binding, the `apply render-fn args`)
  stays intact. The view renders correctly; the dev-only trace
  metadata simply does not emit.

  Surfaces exercised:

  - The wrapper's `:view/render` trace — NO trace event observed under
    prod.
  - `*render-key*` — still BOUND under prod (the wrapper's `binding`
    is not gated; the binding is read by `current-render-key` which
    consumers may call defensively).
  - `current-render-key` — returns the `[view-id token]` shape inside
    the wrapper render even under prod (no anonymous fallback).
  - Source-coord injection — already pinned in
    `re-frame.source-coord-dom-elision-prod-test`; we touch it once
    here to confirm the cross-cutting elision still holds when the
    test exercises the render-key surface.

  Naming convention: files ending in `-elision-prod-test.cljs` are
  picked up ONLY by the `:browser-test-prod-elision` build. Running
  under `goog.DEBUG=true` would FAIL — under dev the `:view/render`
  trace fires for every render."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views :as views]
            ;; rf2-qwm0a — listener surface lives in `re-frame.trace.tooling`.
            [re-frame.trace.tooling :as trace-tooling]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
    {:adapter reagent-adapter/adapter}))

;; ---- :view/render trace does not fire under prod -------------------------

(deftest reg-view-render-emits-no-view-render-trace-under-prod
  (testing "Per Spec 009 §Production builds (rf2-l7hlm): every render
            of a `reg-view*`-registered view fires a `:view/render`
            trace under dev. Under prod the entire emit body (gated on
            `interop/debug-enabled?`) DCEs — a registered trace
            listener observes NO `:view/render` event when the wrapper
            renders. The wrapper still calls the user fn — only the
            trace metadata elides."
    (let [seen (atom [])]
      (trace-tooling/register-trace-listener!
        ::prod-view-listener
        (fn [ev] (swap! seen conj ev)))
      (rf/reg-view* :prod-frame-provider/sample
                    (fn [] [:span "rendered"]))
      (let [wrapper (rf/view :prod-frame-provider/sample)
            out     (wrapper)]
        (is (vector? out) "wrapper invoked the user fn — render still produced hiccup")
        (is (= :span (first out)) "root tag preserved"))
      (is (empty? @seen)
          "no trace events delivered — :view/render elides under
           :advanced + goog.DEBUG=false")
      (trace-tooling/unregister-trace-listener! ::prod-view-listener))))

;; ---- current-render-key still binds under prod ---------------------------

(deftest current-render-key-binding-survives-elision-under-prod
  (testing "Per Spec 004 §Render-tree primitives: the
            `binding [*render-key* ...]` form sits in the wrapper body
            BUT is not itself gated on `interop/debug-enabled?` — only
            the `emit-render-trace!` body inside the binding is gated.
            Under prod the binding still takes effect; consumers that
            call `current-render-key` defensively (e.g. error-emit
            source-coord stamping) get the proper shape, not the
            anonymous fallback. This guards against a future change
            that mistakenly tucks the binding under the gate."
    (let [observed-key (atom nil)]
      (rf/reg-view* :prod-frame-provider/key-reader
        (fn []
          (reset! observed-key (views/current-render-key))
          [:span "key-reader"]))
      (let [wrapper (rf/view :prod-frame-provider/key-reader)
            _out    (wrapper)]
        (is (vector? @observed-key)
            "*render-key* is bound to a vector inside the render under prod")
        (is (= :prod-frame-provider/key-reader (first @observed-key))
            "the view-id slot of *render-key* survives under prod")
        (is (some? (second @observed-key))
            "the instance-token slot of *render-key* survives under prod")))))

;; ---- mint-instance-token! survives (value-layer machinery) ---------------

(deftest mint-instance-token-survives-under-prod
  (testing "Per Spec 004 §Render-tree primitives: `mint-instance-token!`
            is value-layer machinery (a swap! on the process-wide
            `instance-counter` atom). It is NOT gated — calling it
            under prod returns a fresh integer. The token's only
            consumer (the trace emit and the dev source-coord stamp)
            elides, but the mint surface itself is safe to call."
    (let [t1 (views/mint-instance-token!)
          t2 (views/mint-instance-token!)]
      (is (integer? t1) "mint returns an integer under prod")
      (is (integer? t2) "second call also returns an integer")
      (is (not= t1 t2)
          "the counter still advances under prod — the value-layer
           machinery survives elision; only the dev-only consumer
           (the trace surface) elides"))))

;; ---- current-render-key outside a render-key binding falls through -------

(deftest current-render-key-anonymous-fallback-under-prod
  (testing "Per Spec 004 §Render-tree primitives: outside a registered
            view's render, `current-render-key` returns the documented
            anonymous fallback `[:rf.view/anonymous nil]`. The
            fallback shape itself survives prod because the
            `or *render-key* [:rf.view/anonymous nil]` form is not
            gated — anonymous-render context is a documented prod-mode
            shape too (e.g. headless tests calling `current-render-key`
            outside any reg-view* wrapper)."
    (is (= [:rf.view/anonymous nil]
           (views/current-render-key))
        "current-render-key returns the anonymous fallback outside a
         render under prod")))
