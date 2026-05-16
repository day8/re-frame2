(ns re-frame.http-test-support-test
  "Smoke test for the `re-frame.http-test-support` leaf — required in
  the same PR as the new namespace per the per-leaf smoke-test
  convention (rf2-cdmle).

  The leaf's job is to register two fxs at load time:
   - `:rf.http/managed-canned-success`
   - `:rf.http/managed-canned-failure`

  Each delegates to the same handler body the prior
  `(when interop/debug-enabled? ...)` gate in `re-frame.http-managed`
  wired up — `re-frame.http-machine-wrapper/canned-success-handler` /
  `canned-failure-handler`. The smoke pins:

   1. Both fx ids ARE registered after requiring the namespace.
   2. The registered handlers are the wrapper's `canned-*-handler`
      vars (not some shim), so the existing args-map contract from
      Spec 014 §Testing carries through unchanged.

  The deeper end-to-end behaviour (canned reply → late-bind dispatch →
  reply lands in app-db) is exercised by `re-frame.http-managed-test`
  and the corresponding CLJS smoke under `implementation/adapters/*`.
  This file's contract is narrower: leaf-shape pin."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.registrar :as registrar]
            [re-frame.http-machine-wrapper :as machine-wrapper]
            [re-frame.http-test-support]))

;; Ensure each test starts from a known registrar — clear, then reload
;; the test-support ns so its load-time registration fires.
(use-fixtures :each
  (fn [t]
    (registrar/clear-all!)
    (require 're-frame.http-test-support :reload)
    (t)))

(deftest canned-stub-fxs-register-on-test-support-load
  (testing "loading re-frame.http-test-support registers both canned-stub fx ids"
    (is (some? (registrar/lookup :fx :rf.http/managed-canned-success))
        ":rf.http/managed-canned-success registered")
    (is (some? (registrar/lookup :fx :rf.http/managed-canned-failure))
        ":rf.http/managed-canned-failure registered")))

(deftest canned-stub-fxs-bind-the-machine-wrapper-handlers
  (testing "the registered handlers ARE the machine-wrapper canned-* vars
            (not a shim) — so Spec 014 §Testing's args-map contract
            carries through unchanged"
    (is (identical? machine-wrapper/canned-success-handler
                    (registrar/handler :fx :rf.http/managed-canned-success))
        ":rf.http/managed-canned-success → machine-wrapper/canned-success-handler")
    (is (identical? machine-wrapper/canned-failure-handler
                    (registrar/handler :fx :rf.http/managed-canned-failure))
        ":rf.http/managed-canned-failure → machine-wrapper/canned-failure-handler")))
