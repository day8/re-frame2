(ns re-frame.http-test-support-test
  "Smoke test for the `re-frame.http-test-support` leaf — required in
  the same PR as the new namespace per the per-leaf smoke-test
  convention (rf2-cdmle).

  Per rf2-lwmgw (audit-of-audits #15) the namespace is now the single
  home for every HTTP test surface:

   - load-time registration of the two canned-stub fxs:
      - `:rf.http/managed-canned-success`
      - `:rf.http/managed-canned-failure`
     Each delegates to `re-frame.http-machine-wrapper/canned-success-handler`
     / `canned-failure-handler`.
   - the stub macros / fns:
      - `with-managed-request-stubs`
      - `with-managed-request-stubs*`
      - `install-managed-request-stubs!`
      - `uninstall-managed-request-stubs!`
   - the late-bind hook publications under
     `:http/install-managed-request-stubs!`,
     `:http/uninstall-managed-request-stubs!`,
     `:http/with-managed-request-stubs*` (resolved by
     `re-frame.core-http`'s defwrapper surface).

  This smoke pins the load-time side effects: fx registrations land,
  fx ids bind to the wrapper's canned-* vars, and the stub-family
  late-bind hooks are non-nil after the require. The deeper end-to-end
  behaviour (canned reply → late-bind dispatch → reply lands in
  app-db) is exercised by `re-frame.http-managed-test` and the
  corresponding CLJS smoke under `implementation/adapters/*`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.http-machine-wrapper :as machine-wrapper]
            [re-frame.http-test-support :as http-test-support]))

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

(deftest stub-family-late-bind-hooks-publish-on-test-support-load
  (testing "rf2-lwmgw — loading re-frame.http-test-support publishes the
            stub-family late-bind hooks that re-frame.core-http resolves
            through. Without this ns required, rf/install-managed-request-stubs!
            and siblings raise :rf.error/http-artefact-missing — verified
            by re-frame.late-bind-missing-test."
    (is (identical? http-test-support/install-managed-request-stubs!
                    (late-bind/get-fn :http/install-managed-request-stubs!))
        ":http/install-managed-request-stubs! → http-test-support/install-managed-request-stubs!")
    (is (identical? http-test-support/uninstall-managed-request-stubs!
                    (late-bind/get-fn :http/uninstall-managed-request-stubs!))
        ":http/uninstall-managed-request-stubs! → http-test-support/uninstall-managed-request-stubs!")
    (is (identical? http-test-support/with-managed-request-stubs*
                    (late-bind/get-fn :http/with-managed-request-stubs*))
        ":http/with-managed-request-stubs* → http-test-support/with-managed-request-stubs*")))
