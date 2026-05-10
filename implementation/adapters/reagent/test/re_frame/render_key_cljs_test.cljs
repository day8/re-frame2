(ns re-frame.render-key-cljs-test
  "Per Spec 004 §Render-tree primitives (rf2-piag / rf2-t5tx Option C):
  `:render-key` in the `:view/render` trace and the
  `:rf/epoch-record`'s `:renders` projection is the tuple
  `[<view-id> <instance-token>]`.

  Coverage:

    - reg-view'd component, two direct invocations through the wrapper
      (no Reagent component context) → distinct instance-tokens, same
      view-id (mirrors per-mount-fresh semantics for headless tests).
    - The `:view/render` trace is emitted with the tuple-shaped
      `:render-key`.
    - The `*render-key*` dynamic var is bound during render-fn
      invocation and unbound outside.
    - Plain Reagent fns (no reg-view wrapper) — `current-render-key`
      returns the documented anonymous fallback `[:rf.view/anonymous nil]`.
    - The instance-counter monotonically increases."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace]
            [re-frame.views :as views]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---- helpers ---------------------------------------------------------------

(defn- record-render-traces! []
  (let [recorded (atom [])]
    (rf/register-trace-cb! ::recorder
      (fn [ev]
        (when (= :view/render (:operation ev))
          (swap! recorded conj ev))))
    recorded))

;; ---- render-key tuple shape -----------------------------------------------

(deftest render-key-is-tuple-of-view-id-and-instance-token
  (testing "the wrapper bound by reg-view* binds *render-key* to
            [view-id instance-token] for the body of each render"
    (let [observed (atom nil)]
      (rf/reg-view* :rf.test/probe
        (fn []
          (reset! observed views/*render-key*)
          [:p "ok"]))
      (let [wrapper (rf/view :rf.test/probe)]
        (wrapper)
        (let [k @observed]
          (is (vector? k) ":render-key is a vector")
          (is (= 2 (count k)) ":render-key is a 2-element tuple")
          (is (= :rf.test/probe (first k))
              "first slot is the registered view-id")
          (is (int? (second k))
              "second slot is the integer instance-token"))))))

(deftest two-instances-get-distinct-tokens-same-view-id
  (testing "two direct invocations of the wrapper outside a Reagent
            component context produce distinct instance-tokens (per-call
            mint mirrors per-mount-fresh for headless tests)"
    (let [observed (atom [])]
      (rf/reg-view* :rf.test/two-instances
        (fn []
          (swap! observed conj views/*render-key*)
          [:p "ok"]))
      (let [wrapper (rf/view :rf.test/two-instances)]
        (wrapper)                                ;; instance A
        (wrapper)                                ;; instance B
        (let [[k1 k2] @observed]
          (is (= :rf.test/two-instances (first k1) (first k2))
              "both renders share the view-id")
          (is (int? (second k1)))
          (is (int? (second k2)))
          (is (not= (second k1) (second k2))
              "the instance-tokens differ (per-call fresh mint outside
              a Reagent component)"))))))

(deftest dynamic-var-unbound-outside-render
  (testing "*render-key* is nil outside an in-flight render"
    (is (nil? views/*render-key*)
        "outside any render, *render-key* is unbound (nil)")
    (is (= [:rf.view/anonymous nil] (views/current-render-key))
        "current-render-key returns the documented anonymous fallback
        when no render-key is bound (plain Reagent fn case)")))

;; ---- view/render trace event ----------------------------------------------

(deftest view-render-trace-carries-tuple-render-key
  (testing "the wrapper emits a :view/render trace tagged with the tuple
            :render-key"
    (let [traces (record-render-traces!)]
      (rf/reg-view* :rf.test/traced
        (fn [n] [:span "n-" n]))
      (let [wrapper (rf/view :rf.test/traced)]
        (wrapper 7)
        (wrapper 8)
        (is (= 2 (count @traces)) "one trace per invocation")
        (let [[ev1 ev2] @traces
              k1        (get-in ev1 [:tags :render-key])
              k2        (get-in ev2 [:tags :render-key])]
          (is (= :view/render (:operation ev1)))
          (is (= :view/render (:operation ev2)))
          (is (vector? k1))
          (is (vector? k2))
          (is (= :rf.test/traced (first k1) (first k2)))
          (is (not= (second k1) (second k2))
              "tokens differ across instances")))
      (rf/remove-trace-cb! ::recorder))))

;; ---- monotonicity ---------------------------------------------------------

(deftest mint-instance-token-is-monotonic
  (testing "mint-instance-token! returns strictly increasing integers"
    (let [a (views/mint-instance-token!)
          b (views/mint-instance-token!)
          c (views/mint-instance-token!)]
      (is (int? a))
      (is (< a b c) "tokens monotonically increase"))))

;; ---- anonymous fallback for plain Reagent fns -----------------------------

(deftest plain-reagent-fn-falls-back-to-anonymous
  (testing "a plain Reagent fn (no reg-view wrapper) reads the anonymous
            fallback :render-key — current-render-key returns
            [:rf.view/anonymous nil] when *render-key* is unbound"
    (let [observed (atom nil)
          plain-fn (fn []
                     (reset! observed (views/current-render-key))
                     [:p "plain"])]
      (plain-fn)
      (is (= [:rf.view/anonymous nil] @observed)
          "plain fns surface the documented anonymous shape"))))

;; ---- conformance: render-key tuple shape ----------------------------------

(deftest render-key-tuple-conformance
  (testing "every emitted :view/render trace has a 2-tuple :render-key
            with a keyword view-id and (int OR nil) instance-token"
    (let [traces (record-render-traces!)]
      (rf/reg-view* :rf.test/conform-a (fn [] [:p "a"]))
      (rf/reg-view* :rf.test/conform-b (fn [] [:p "b"]))
      ((rf/view :rf.test/conform-a))
      ((rf/view :rf.test/conform-b))
      ((rf/view :rf.test/conform-a))
      (doseq [ev @traces]
        (let [k (get-in ev [:tags :render-key])]
          (is (vector? k))
          (is (= 2 (count k)))
          (is (keyword? (first k)) "view-id slot is a keyword")
          (is (or (int? (second k)) (nil? (second k)))
              "instance-token slot is an int (or nil for anonymous)")))
      (rf/remove-trace-cb! ::recorder))))
