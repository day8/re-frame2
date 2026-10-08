(ns re-frame.render-key-cljs-test
  "Per Spec-Schemas §`:rf/epoch-record`:
  `:rf.view/render-key` in the `:rf.view/render` trace and the
  `:rf/epoch-record`'s `:renders` projection is the tuple
  `[<view-id> <instance-token>]`.

  `*render-key*` is bound only during a render, and outside one
  `current-render-key` falls back to `[:rf.view/anonymous nil]`; two
  headless invocations through the wrapper get distinct instance-tokens."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views :as rf.views])
  (:require-macros [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; ---- helpers ---------------------------------------------------------------

(def ^:private view-render-pred
  #(= :rf.view/render (:operation %)))

;; ---- render-key tuple shape -----------------------------------------------

(deftest render-key-is-tuple-of-view-id-and-instance-token
  (testing "the wrapper bound by reg-view* binds *render-key* to
            [view-id instance-token] for the body of each render"
    (let [observed (atom nil)]
      (rf/reg-view* :rf.test/probe
        (fn []
          (reset! observed rf.views/*render-key*)
          [:p "ok"]))
      (let [wrapper (rf/view :rf.test/probe)]
        (wrapper)
        (let [k @observed]
          (is (= [true :rf.test/probe true]
                 [(and (vector? k) (= 2 (count k))) (first k) (int? (second k))])
              ":rf.view/render-key is a 2-tuple of the registered view-id and an integer instance-token"))))))

(deftest dynamic-var-unbound-outside-render
  (testing "*render-key* is nil outside an in-flight render"
    (is (nil? rf.views/*render-key*)
        "outside any render, *render-key* is unbound (nil)")
    (is (= [:rf.view/anonymous nil] (rf.views/current-render-key))
        "current-render-key returns the documented anonymous fallback
        when no render-key is bound (plain Reagent fn case)")))

;; ---- view/render trace event ----------------------------------------------

(deftest view-render-trace-carries-tuple-render-key
  (testing "the wrapper emits a :rf.view/render trace tagged with the tuple
            :rf.view/render-key"
    (with-trace-recorder! [traces {:pred view-render-pred}]
      (rf/reg-view* :rf.test/traced
        (fn [n] [:span "n-" n]))
      (let [wrapper (rf/view :rf.test/traced)]
        (wrapper 7)
        (wrapper 8)
        (let [ks (mapv #(get-in % [:tags :rf.view/render-key]) @traces)]
          (is (= [[:rf.view/render :rf.view/render] [:rf.test/traced :rf.test/traced] true]
                 [(mapv :operation @traces) (mapv first ks)
                  (and (every? vector? ks) (apply not= (map second ks)))])
              "one :rf.view/render trace per invocation, each tagged with a tuple render-key of the view-id and a distinct instance-token"))))))
