(ns re-frame.auto-inject-form2-cljs-test
  "Per Spec 002 §What `reg-view` injects: the `reg-view` macro
  auto-injects lexical bindings `dispatch` and `subscribe` around the
  body. The injection is a single OUTER `let`:

      (fn outer [args]
        (let [dispatch  (:dispatch (rf/capture-frame))
              subscribe (:subscribe (rf/capture-frame))]
          body))

  For Form-1 (body is plain hiccup), `dispatch` / `subscribe` are
  available straight in the body — the same outer `let` whose body is
  the Form-2 outer body the tests below capture from.

  For Form-2 (body returns an inner render fn), the outer `let`
  encloses the inner `(fn ... )`, so the inner fn captures the SAME
  `dispatch` / `subscribe` lexical bindings via Clojure closure. Both
  the outer body AND the inner-render fn see the auto-injected names —
  the bindings are NOT re-injected per inner call; they're the same
  closed-over values. Put plainly:

    'The dispatch and subscribe in both the outer body and the inner
     fn refer to the same lexical bindings — Clojure lexical closure
     does the right thing.'

  This file pins the Form-2 boundary — does the inner fn see the
  injected names?

  Form-2's expansion is identical under v1 and v2 Reagent (lexical
  closure is Clojure semantics, not a Reagent feature)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views])
  (:require-macros [re-frame.core :refer [reg-view]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; ---- Form-2 boundary: inner fn captures the SAME dispatch / subscribe ----

(deftest form-2-inner-fn-captures-auto-injected-bindings
  (testing "Form-2: when the body returns a fn, the inner fn closes over
            the SAME `dispatch` / `subscribe` lexical bindings the outer
            let injected. The macro expansion is:

                (fn outer [args]
                  (let [dispatch  (:dispatch (rf/capture-frame))
                        subscribe (:subscribe (rf/capture-frame))]
                    body))               ;; body = `(fn inner [...] ...)`

            Clojure lexical closure means `inner` sees `dispatch` /
            `subscribe` from the surrounding `let` — they are NOT
            re-injected per inner call. Put plainly:
            'The dispatch and subscribe in both the outer body and
            the inner fn refer to the same lexical bindings — Clojure
            lexical closure does the right thing.'

            Asserts: from the inner fn's body, both `dispatch` and
            `subscribe` resolve (no compile error), and they are the
            SAME (===) fn values the outer body saw."
    (let [outer-captured (atom {})
          inner-captured (atom {})]
      (reg-view ^{:rf/id :rf.f2-inject/form-2-view} f2-view []
        ;; Outer body — the auto-inject `let`'s body. Stashes the
        ;; injected names from the OUTER scope.
        (swap! outer-captured assoc
               :dispatch  dispatch
               :subscribe subscribe)
        ;; Inner render fn — the body of `(fn inner ...)`. References
        ;; `dispatch` / `subscribe` from the SURROUNDING `let`. If
        ;; lexical closure didn't carry them through, the CLJS
        ;; compiler would fail to resolve the symbols here.
        (fn inner-render []
          (swap! inner-captured assoc
                 :dispatch  dispatch
                 :subscribe subscribe)
          [:p "ok"]))
      (let [wrapper       (rf/view :rf.f2-inject/form-2-view)
            ;; First wrapper invocation: runs the outer body (records
            ;; outer-captured) and returns the inner fn (which the
            ;; substrate's source-coord wrapper has wrapped per Spec
            ;; 006 §Form-2 handling — the wrapped fn delegates to
            ;; the inner render).
            inner-or-fn   (wrapper)]
        (is (fn? inner-or-fn)
            "Form-2 wrapper returns a fn (the inner render — Spec 006
             §Form-2 handling)")
        (let [inner-out (inner-or-fn)]
          (is (vector? inner-out) "inner fn returns hiccup")
          (is (= :p (first inner-out)) "inner fn root tag preserved"))
        ;; Both layers captured fn values for `dispatch` and `subscribe`.
        (is (fn? (:dispatch  @outer-captured))
            "outer body: `dispatch` auto-injected as a fn")
        (is (fn? (:subscribe @outer-captured))
            "outer body: `subscribe` auto-injected as a fn")
        ;; The inner fn sees the SAME fn instances the outer body saw —
        ;; this is the Spec 002 §What `reg-view` injects contract: the
        ;; outer `let` binds once and the inner fn closes over it —
        ;; 'refer to the same
        ;; lexical bindings'. If the macro had re-injected per inner
        ;; call (a bug shape), the values would be fresh fn objects
        ;; minted by `(:dispatch (rf/capture-frame))` / `(:subscribe (rf/capture-frame))` per call,
        ;; not identical to the outer body's.
        (is (identical? (:dispatch  @outer-captured)
                        (:dispatch  @inner-captured))
            "inner fn's `dispatch` is the SAME closed-over fn as the
             outer body's — confirming the auto-inject is the outer
             let, lexically captured by the inner fn (Spec 002 §What
             `reg-view` injects)")
        (is (identical? (:subscribe @outer-captured)
                        (:subscribe @inner-captured))
            "inner fn's `subscribe` is the SAME closed-over fn as the
             outer body's — confirming lexical-closure semantics")))))
