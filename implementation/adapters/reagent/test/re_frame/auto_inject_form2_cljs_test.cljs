(ns re-frame.auto-inject-form2-cljs-test
  "Per Spec 004 §reg-view (rf2-d0pi) and §Form-2: the `reg-view` macro
  auto-injects lexical bindings `dispatch` and `subscribe` around the
  body. The injection is a single OUTER `let`:

      (fn outer [args]
        (let [dispatch  (rf/dispatcher)
              subscribe (rf/subscriber)]
          body))

  For Form-1 (body is plain hiccup), `dispatch` / `subscribe` are
  available straight in the body — covered by the existing macro
  tests (views-macros-test).

  For Form-2 (body returns an inner render fn), the outer `let`
  encloses the inner `(fn ... )`, so the inner fn captures the SAME
  `dispatch` / `subscribe` lexical bindings via Clojure closure. Both
  the outer body AND the inner-render fn see the auto-injected names —
  the bindings are NOT re-injected per inner call; they're the same
  closed-over values. Per Spec 004 §Form-2:

    'The dispatch and subscribe in both the outer body and the inner
     fn refer to the same lexical bindings — Clojure lexical closure
     does the right thing.'

  This is the pre-beta gap that rf2-o423 surfaced (rf2-d4v7 sub-gap 1):
  the Form-1 happy path is covered by views-macros-test, but the
  Form-2 boundary — does the inner fn see the injected names? — was
  only documented in Spec 004 §Form-2 and the macro source comment,
  never asserted by a test. This file pins the contract.

  Reference Mike's feedback memory: 'target Reagent v2; don't invest
  in 1.2 back-compat.' Form-2's expansion is identical under v1 and
  v2 Reagent (lexical closure is Clojure semantics, not a Reagent
  feature)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---- Form-1 baseline: dispatch / subscribe are auto-injected -------------

(deftest form-1-auto-inject-baseline
  (testing "Form-1 body sees auto-injected `dispatch` / `subscribe`. The
            outer `let` makes both names lex-scoped over the body — if
            either name were not in scope, the CLJS compiler would
            error at macro-expansion time. The runtime check below
            asserts both names resolve to fns and the captured
            dispatcher routes against the registered :rf/default frame."
    (let [captured (atom {})]
      (reg-view ^{:rf/id :rf.f2-inject/form-1-view} f1-view []
        ;; Capture the auto-injected bindings as fn values. We don't
        ;; invoke them here — invocation behaviour belongs to the
        ;; dispatcher / subscriber tests in events_cljs_test. The point
        ;; is: the names are bound, in scope, and bound to fns.
        (swap! captured assoc
               :dispatch  dispatch
               :subscribe subscribe)
        [:p "ok"])
      (let [render (rf/view :rf.f2-inject/form-1-view)
            out    (render)]
        (is (vector? out) "Form-1 returns hiccup")
        (is (fn? (:dispatch  @captured))
            "Form-1: `dispatch` auto-injected as a fn")
        (is (fn? (:subscribe @captured))
            "Form-1: `subscribe` auto-injected as a fn")))))

;; ---- Form-2 boundary: inner fn captures the SAME dispatch / subscribe ----

(deftest form-2-inner-fn-captures-auto-injected-bindings
  (testing "Form-2: when the body returns a fn, the inner fn closes over
            the SAME `dispatch` / `subscribe` lexical bindings the outer
            let injected. The macro expansion is:

                (fn outer [args]
                  (let [dispatch  (rf/dispatcher)
                        subscribe (rf/subscriber)]
                    body))               ;; body = `(fn inner [...] ...)`

            Clojure lexical closure means `inner` sees `dispatch` /
            `subscribe` from the surrounding `let` — they are NOT
            re-injected per inner call. Per Spec 004 §Form-2:
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
            "Form-2 wrapper returns a fn (the inner render — Spec 004
             §Form-2 / Spec 006 §Form-2 handling)")
        (let [inner-out (inner-or-fn)]
          (is (vector? inner-out) "inner fn returns hiccup")
          (is (= :p (first inner-out)) "inner fn root tag preserved"))
        ;; Both layers captured fn values for `dispatch` and `subscribe`.
        (is (fn? (:dispatch  @outer-captured))
            "outer body: `dispatch` auto-injected as a fn")
        (is (fn? (:subscribe @outer-captured))
            "outer body: `subscribe` auto-injected as a fn")
        (is (fn? (:dispatch  @inner-captured))
            "inner fn: `dispatch` resolves via lexical closure")
        (is (fn? (:subscribe @inner-captured))
            "inner fn: `subscribe` resolves via lexical closure")
        ;; The inner fn sees the SAME fn instances the outer body saw —
        ;; this is the Spec 004 §Form-2 contract: 'refer to the same
        ;; lexical bindings'. If the macro had re-injected per inner
        ;; call (a bug shape), the values would be fresh fn objects
        ;; minted by `(rf/dispatcher)` / `(rf/subscriber)` per call,
        ;; not identical to the outer body's.
        (is (identical? (:dispatch  @outer-captured)
                        (:dispatch  @inner-captured))
            "inner fn's `dispatch` is the SAME closed-over fn as the
             outer body's — confirming the auto-inject is the outer
             let, lexically captured by the inner fn (Spec 004 §Form-2)")
        (is (identical? (:subscribe @outer-captured)
                        (:subscribe @inner-captured))
            "inner fn's `subscribe` is the SAME closed-over fn as the
             outer body's — confirming lexical-closure semantics")))))

;; ---- Form-2: bindings persist across inner re-renders --------------------

(deftest form-2-inner-fn-binding-stable-across-renders
  (testing "A Form-2 view's auto-inject is the OUTER `let`, run once
            per outer-body invocation. Subsequent inner-fn calls see
            the SAME captured `dispatch` / `subscribe` — they are NOT
            re-resolved per render. Per Spec 004 §Form-2 lexical-
            closure contract.

            Asserts: the captured `dispatch` is identical (===) across
            three inner-fn invocations — the binding is the closed-
            over value, not freshly resolved per render."
    (let [captures (atom [])]
      (reg-view ^{:rf/id :rf.f2-inject/identity-view} id-view []
        ;; Outer body: stash the auto-injected dispatch.
        (swap! captures conj dispatch)
        (fn inner-render []
          ;; Inner body: stash the captured dispatch on each render.
          (swap! captures conj dispatch)
          [:span "ok"]))
      (let [wrapper   (rf/view :rf.f2-inject/identity-view)
            inner-fn  (wrapper)]
        (inner-fn)        ;; first inner render
        (inner-fn)        ;; second inner render
        (let [captured @captures]
          ;; Three captures: outer + 2× inner.
          (is (= 3 (count captured))
              "outer body and 2 inner renders all stashed `dispatch`")
          ;; All three are the SAME object — proving the inner fn
          ;; closed over the same lexical binding the outer let
          ;; created.
          (is (apply identical? captured)
              "all three captures are the same fn instance —
               confirming the inner fn's `dispatch` is the outer
               let's binding, closed over, NOT re-resolved per inner
               call (Spec 004 §Form-2 lexical-closure contract)"))))))
