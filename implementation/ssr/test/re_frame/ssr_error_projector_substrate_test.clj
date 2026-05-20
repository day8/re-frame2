(ns re-frame.ssr-error-projector-substrate-test
  "Per rf2-fb598: the SSR error-projection pipeline rides the always-on
  `register-error-listener!` substrate (per Spec 009 §What IS
  available in production §Error-emit listener) — NOT the dev-only
  `register-listener!` surface.

  Production-hardening (rf2-vnjfg — `-Dre-frame.debug=false` on the JVM)
  silences `trace/emit-error!` so any listener installed on
  `register-listener!` stops firing under that posture. The SSR
  error-projector is a production-required surface — Spec 011 §Server
  error projection commits the runtime to stamping the public-error
  `:status` onto `:rf/response` whenever an exception escapes a server-
  frame drain — so the listener install MUST survive the JVM dev gate.

  This suite pins that contract: under `with-redefs [interop/debug-
  enabled? false]` (the same vocabulary jvm_prod_gate_integration_test
  uses for the same posture), a server-frame handler that throws still
  results in the projector stamping :status 500 onto :rf/response.

  Companion suites:
    - `re-frame.ssr-end-to-end-test` — the dev-mode end-to-end coverage
      (debug-enabled? on; trace surface live).
    - `re-frame.jvm-prod-gate-integration-test` — the JVM dev-gate
      contract for the core trace / event-emit / error-emit surfaces.
    - `re-frame.epoch.jvm-prod-gate-test` — the same posture for the
      epoch artefact.

  The shape mirrors `jvm_prod_gate_integration_test`'s `always-on-
  error-emit-still-fires-when-debug-disabled` test: flip the gate,
  drive a known-throwing handler through the cascade, observe the
  always-on surface still works. This suite extends that contract one
  step further — into the SSR-artefact's framework-internal use of the
  same substrate."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

(deftest ssr-error-projector-fires-under-production-hardening-rf2-fb598
  (testing "Spec 011 §Server error projection holds when
            `interop/debug-enabled? = false` — the always-on error-emit
            substrate carries the projector install, not the dev-only
            trace surface. A server-frame handler that throws still
            stamps :status 500 onto :rf/response."
    (rf/reg-event-fx :load/article
      (fn [_ _]
        (throw (ex-info "Database connection failed" {}))))
    (rf/reg-event-fx :rf/server-init
      (fn [_ _]
        {:fx [[:dispatch [:load/article]]]}))

    (with-redefs [interop/debug-enabled? false]
      (let [f (rf/make-frame
                {:platform  :server
                 :on-create [:rf/server-init]
                 :ssr       {:public-error-id   :rf.ssr/default-error-projector
                             :dev-error-detail? false}})
            response (ssr/get-response f)]
        (is (= 500 (:status response))
            "Spec 011 §Server error projection — the default projector
             stamps :status 500 on :rf/response even with the dev trace
             gate disabled (production-hardening posture per rf2-vnjfg).
             Pre-rf2-fb598 the install was on register-listener!, which
             elides under this gate; rf2-fb598 moves the install onto
             the always-on register-error-listener! substrate.")))))

(deftest ssr-error-projector-direct-substrate-install-rf2-fb598
  (testing "The error-emit listener is registered under
            `::re-frame.ssr.error-listener/error-projection` (the
            framework-private id used by both substrate installs). A
            tight error-record delivered through the substrate routes
            to the SSR projector buffer and stamps the response."
    (let [f (rf/make-frame
              {:platform :server
               :ssr      {:public-error-id   :rf.ssr/default-error-projector
                          :dev-error-detail? false}})]
      ;; Drive the substrate directly via `re-frame.error-emit/dispatch-
      ;; on-error!` (the same surface `router.cljc` and `fx.cljc` use).
      ;; This bypasses the cascade so the test isolates the listener-
      ;; install plumbing — the round-trip through the dispatch loop is
      ;; covered by the end-to-end suite above and ssr_end_to_end_test.
      (let [dispatch-on-error!
            (requiring-resolve 're-frame.error-emit/dispatch-on-error!)
            ex (ex-info "boom" {})]
        (with-redefs [interop/debug-enabled? false]
          (dispatch-on-error!
            :rf.error/handler-exception
            [:boom]                            ;; event
            :boom                              ;; event-id
            f                                  ;; frame
            ex                                 ;; exception
            0                                  ;; elapsed-ms
            (System/currentTimeMillis)         ;; time
            {:operation :rf.error/handler-exception
             :op-type   :error
             :tags      {:frame f
                         :event-id :boom
                         :exception ex
                         :recovery :no-recovery}})
          (is (= 500 (:status (ssr/get-response f)))
              "The framework's own error-emit listener
               (`::error-projection`) routed the record to the buffer,
               flush-response! drained it, and the default projector
               stamped :status 500 on the response accumulator — all
               under the disabled dev gate."))))))

(deftest ssr-error-projector-listener-installed-on-error-emit-substrate-rf2-fb598
  (testing "Direct registry-level smoke: the SSR façade installs
            `::error-projection` on the error-emit substrate at ns-load.
            Without this install the production-hardening case
            regresses."
    ;; Reach into the framework-private listeners atom; an idiomatic
    ;; check of \"the substrate has the listener\" without depending on
    ;; the public unregister surface.
    (let [listeners-var (requiring-resolve 're-frame.error-emit/listeners)
          registered    (some-> listeners-var deref deref keys set)]
      (is (contains? registered :re-frame.ssr/error-projection)
          "The SSR façade registers ::error-projection on the always-on
           register-error-listener! substrate at ns-load — Spec 011
           §Server error projection (rf2-fb598). The id matches the one
           the dev-only register-listener! install also uses, so the
           two surfaces are addressable as one logical projector."))))
