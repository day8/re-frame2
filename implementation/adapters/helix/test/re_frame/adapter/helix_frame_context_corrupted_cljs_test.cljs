(ns re-frame.adapter.helix-frame-context-corrupted-cljs-test
  "Parity test for `:rf.error/frame-context-corrupted` under the Helix
  adapter (rf2-n5obf).

  The corruption-detection + recovery path lives in
  `re-frame.adapter.context/function-component-current-frame` — the
  shared impl Helix wires into its `:adapter/current-frame` slot per
  rf2-d4sf. A non-keyword `_currentValue` on the shared frame-context
  emits `:rf.error/frame-context-corrupted` (rf2-8q66) and falls
  through to `:rf/default` (recovery `:replaced-with-default`).

  Pre-rf2-n5obf, the test for this path ran only under the Reagent
  adapter (see
  `adapters/reagent/test/re_frame/frame_provider_context_cljs_test.cljs`
  scenario-3). The Helix adapter wires through the *same* shared impl,
  so the path itself is already covered — what this file pins is that
  the Helix adapter's hook-routing keeps that path live. If the
  `:adapter/current-frame` Var ever drifts (a refactor of the late-bind
  hook table, the Helix adapter's `route-hook!` line, or the shared
  fn's emit path), this parity test breaks the build.

  Implementation note: the test pokes `.-_currentValue` directly. React
  itself mutates this field as Provider boundaries are entered and
  exited during render; outside a render frame we are free to set it
  to test values and restore the original in `finally`.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; rf2-qwm0a: listener / buffer surface lives in re-frame.trace.tooling.
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.adapter.helix :as helix-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter helix-adapter/adapter}))

;; ---- trace helpers --------------------------------------------------------

(defn- collect-traces [k]
  (let [traces (atom [])]
    (trace-tooling/register-trace-listener! k (fn [ev] (swap! traces conj ev)))
    traces))

(defn- corruption-traces [traces]
  (filter #(= :rf.error/frame-context-corrupted (:operation %)) @traces))

;; ---- direct-call parity ----------------------------------------------------
;;
;; The shared `function-component-current-frame` resolves `_currentValue`
;; off the frame-context. Helix's `:adapter/current-frame` hook (wired
;; in helix.cljs by `route-hook!`) points at this fn. Probe the fn
;; directly to assert the emit-and-recover contract under a Helix-
;; installed runtime.

(deftest direct-call-emits-error-and-recovers-under-helix
  (testing "function-component-current-frame: corrupted _currentValue under Helix adapter"
    (let [original (.-_currentValue ^js adapter-context/frame-context)
          traces   (collect-traces ::direct-helix)]
      (try
        (testing "nil _currentValue: error trace fires; resolves to :rf/default"
          (reset! traces [])
          (set! (.-_currentValue ^js adapter-context/frame-context) nil)
          (is (= :rf/default (adapter-context/function-component-current-frame))
              "falls through to :rf/default (recovery preserved under Helix)")
          (let [errs (corruption-traces traces)]
            (is (= 1 (count errs))
                "one :rf.error/frame-context-corrupted event fired")
            (is (= :error (:op-type (first errs)))
                ":op-type is :error per Spec 009 §Error contract")
            (is (= :replaced-with-default (:recovery (first errs)))
                ":recovery is :replaced-with-default — fall-through preserved")
            (is (= :nil (-> errs first :tags :type))
                ":tags :type names the corrupted shape")))
        (testing "number _currentValue: error trace fires; resolves to :rf/default"
          (reset! traces [])
          (set! (.-_currentValue ^js adapter-context/frame-context) 42)
          (is (= :rf/default (adapter-context/function-component-current-frame))
              "falls through to :rf/default")
          (let [errs (corruption-traces traces)]
            (is (= 1 (count errs))
                "one error trace per corrupted read")
            (is (= :number (-> errs first :tags :type)))
            (is (= 42 (-> errs first :tags :received))
                ":tags :received echoes the offending value")))
        (finally
          (trace-tooling/unregister-trace-listener! ::direct-helix)
          (set! (.-_currentValue ^js adapter-context/frame-context) original))))))

;; ---- adapter-routed parity -------------------------------------------------
;;
;; `rf/current-frame` (-> `frame/resolve-current-frame`) consults the
;; `:adapter/current-frame` late-bind hook (rf2-d4sf). Under a Helix-
;; installed runtime, this routes to `function-component-current-frame`.
;; Corrupting `_currentValue` and calling the public seam exercises the
;; rf2-d4sf wiring end-to-end: regression in the hook table, the Helix
;; `route-hook!` line, or the chain-bottom fallback breaks this test.

(deftest current-frame-recovers-under-helix
  (testing "rf/current-frame: corrupted _currentValue → :rf/default under Helix"
    (let [original (.-_currentValue ^js adapter-context/frame-context)
          traces   (collect-traces ::routed-helix)]
      (try
        (reset! traces [])
        (set! (.-_currentValue ^js adapter-context/frame-context) "")
        (is (= :rf/default (rf/current-frame))
            "adapter-routed read recovers to :rf/default under Helix")
        (let [errs (corruption-traces traces)]
          (is (= 1 (count errs))
              "one :rf.error/frame-context-corrupted event fired through the Helix-routed path")
          (is (= :empty-string (-> errs first :tags :type))
              ":tags :type distinguishes empty-string from a populated string"))
        (finally
          (trace-tooling/unregister-trace-listener! ::routed-helix)
          (set! (.-_currentValue ^js adapter-context/frame-context) original))))))
