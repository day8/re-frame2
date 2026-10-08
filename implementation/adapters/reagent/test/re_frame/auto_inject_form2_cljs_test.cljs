(ns re-frame.auto-inject-form2-cljs-test
  "Per Spec 002 §What `reg-view` injects: the `reg-view` macro
  auto-injects lexical bindings `dispatch` and `subscribe` around the
  body. The injection is a single OUTER `let`:

      (fn outer [args]
        (let [dispatch  (:dispatch (rf/capture-frame))
              subscribe (:subscribe (rf/capture-frame))]
          body))

  For Form-2 the body returns an inner render fn, which that outer `let`
  encloses, so the inner fn closes over the SAME bindings rather than
  receiving fresh ones per call. This pins that boundary."
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
  (testing "Form-2: the inner fn sees `dispatch` / `subscribe` (it compiles)
            and they are the SAME fn values the outer body saw"
    (let [outer-captured (atom {})
          inner-captured (atom {})]
      (reg-view ^{:rf/id :rf.f2-inject/form-2-view} f2-view []
        (swap! outer-captured assoc
               :dispatch  dispatch
               :subscribe subscribe)
        ;; Without the closure, these symbols would not resolve here.
        (fn inner-render []
          (swap! inner-captured assoc
                 :dispatch  dispatch
                 :subscribe subscribe)
          [:p "ok"]))
      ;; Invoking the wrapper runs the outer body and returns the inner fn,
      ;; wrapped per Spec 006 §Form-2 handling.
      (let [inner     ((rf/view :rf.f2-inject/form-2-view))
            inner-out (inner)]
        (is (= [true :p] [(vector? inner-out) (first inner-out)])
            "the inner fn returns its hiccup, root tag preserved"))
      ;; A per-call re-injection would mint fresh fns from `capture-frame`.
      (let [{outer-d :dispatch outer-s :subscribe} @outer-captured
            {inner-d :dispatch inner-s :subscribe} @inner-captured]
        (is (= [true true true true]
               [(fn? outer-d) (fn? outer-s) (identical? outer-d inner-d) (identical? outer-s inner-s)])
            "the outer body got `dispatch` / `subscribe` as fns, and the inner fn closed over the SAME ones")))))
