(ns re-frame.ssr-error-projector-substrate-test
  "The SSR error-projection pipeline rides the always-on
  `register-error-listener!` substrate (per Spec 009 §What IS
  available in production §Error-emit listener) — NOT the dev-only
  `register-listener!` surface.

  Production-hardening (`-Dre-frame.debug=false` on the JVM)
  silences `trace/emit-error!` so any listener installed on
  `register-listener!` stops firing under that posture. The SSR
  error-projector is a production-required surface — Spec 011 §Server
  error projection commits the runtime to stamping the public-error
  `:status` onto `:rf/response` whenever an exception escapes a server-
  frame drain — so the listener install MUST survive the JVM dev gate.

  This suite pins the install itself: the `::error-projection` listener
  sits on the always-on registry, and a record delivered through
  `dispatch-on-error!` reaches the projector buffer and stamps :status 500
  onto :rf/response without the dev trace bus. The full cascade — a
  server-frame handler that throws, projected to 500 — is
  `re-frame.ssr-end-to-end-test/ssr-default-error-projector-handler-exception`,
  which runs under the real `-Dre-frame.debug=false` gate in
  `scripts/test-ssr-prod-gate.sh`.

  Companion suites:
    - `re-frame.ssr-end-to-end-test` — the end-to-end coverage, run in both
      postures; its dev-trace assertions sit in debug-gated arms.
    - `re-frame.jvm-prod-gate-integration-test` — the JVM dev-gate
      contract for the core trace / event-emit / error-emit surfaces.
    - `re-frame.epoch.jvm-prod-gate-test` — the same posture for the
      epoch artefact.

  It is the SSR-artefact counterpart of `jvm_prod_gate_integration_test`'s
  `always-on-error-emit-still-fires-when-debug-disabled` test: the same
  always-on substrate, reached by the SSR artefact's framework-internal
  listener."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(deftest ssr-error-projector-direct-substrate-install-rf2-fb598
  (testing "The error-emit listener is registered under
            `::re-frame.ssr.error-listener/error-projection` (the
            framework-private id used by both substrate installs). A
            tight error-record delivered through the substrate routes
            to the SSR projector buffer and stamps the response."
    (let [f (rf.frame/make-anon-frame-record!
              {:platform :server
               :ssr      {:public-error-id   :rf.ssr/default-error-projector
                          :dev-error-detail? false}})]
      ;; Drive the substrate directly via `re-frame.error-emit/dispatch-
      ;; on-error!` (the same surface `router.cljc` and `fx.cljc` use).
      ;; This bypasses the cascade so the test isolates the listener-
      ;; install plumbing — the round-trip through the dispatch loop is
      ;; pinned by `re-frame.ssr-end-to-end-test/ssr-default-error-projector-handler-exception`.
      (let [dispatch-on-error!
            (requiring-resolve 're-frame.error-emit/dispatch-on-error!)
            ex (ex-info "boom" {})]
        (with-redefs [rf.interop/debug-enabled? false]
          (dispatch-on-error!
            :rf.error/handler-exception
            [:boom]                            ;; event
            :boom                              ;; event-id
            f                                  ;; frame
            ex                                 ;; exception
            0                                  ;; elapsed-ms
            (System/currentTimeMillis))        ;; time
          (is (= 500 (:status (rf.ssr/get-response f)))
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
           §Server error projection. The id matches the one
           the dev-only register-listener! install also uses, so the
           two surfaces are addressable as one logical projector."))))
