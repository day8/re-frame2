(ns reagent2.core-cljs-test
  "Unit tests for `reagent2.core` user-facing surface (Stage 4-D, rf2-6hyy).

  Covers the surfaces that don't have dedicated test files:

    - force-update — routes .forceUpdate on `this`; 1-arity only
      (the stock-Reagent 2-arity `[this deep?]` was dropped per
      rf2-okpsr — :deep? has no React 19 analogue and no audited
      caller relied on it).

  Other reagent2.core surfaces are exercised through their dedicated
  per-impl tests (component / template / ratom / batching / dom-throw).

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.core :as r]))

;; ---------------------------------------------------------------------------
;; force-update — 1-arity routes .forceUpdate on `this`
;; ---------------------------------------------------------------------------

(defn- fake-react-instance
  "Build a minimal stand-in for a mounted React class instance. Tracks
  .forceUpdate invocations on the `calls` atom."
  [calls]
  (let [c #js {}]
    (set! (.-forceUpdate c)
          (fn [] (swap! calls conj :force-update)))
    c))

(deftest force-update-1-arity-calls-forceupdate
  (testing "(force-update this) invokes .forceUpdate on the instance"
    (let [calls (atom [])
          this  (fake-react-instance calls)]
      (r/force-update this)
      (is (= [:force-update] @calls)
          ".forceUpdate fired exactly once"))))

(deftest force-update-no-throw-when-forceupdate-missing
  (testing "missing .forceUpdate is a silent no-op (defensive against
            stand-in instances that don't carry the React method)"
    (let [bare #js {}]
      ;; No throw expected — the when-some guard skips the call.
      (r/force-update bare)
      (is true "did not throw"))))

(deftest force-update-is-strictly-1-arity
  (testing "force-update declares a single 1-arity body (rf2-okpsr —
            the stock-Reagent 2-arity `[this deep?]` was dropped). The
            metadata `:arglists` is the canonical contract surface."
    (let [arglists (-> #'r/force-update meta :arglists)]
      (is (= 1 (count arglists))
          ":arglists declares exactly one signature")
      (is (= '[^js this] (first arglists))
          ":arglists is [this] only"))))
