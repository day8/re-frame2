(ns re-frame.http-test-support-test
  "Smoke test for the `re-frame.http-test-support` leaf — required in
  the same PR as the new namespace per the per-leaf smoke-test
  convention (rf2-cdmle).

  Per rf2-lwmgw (audit-of-audits #15) the namespace is now the single
  home for every HTTP test surface:

   - load-time registration of the two canned-stub fxs:
      - `:rf.http/managed-canned-success`
      - `:rf.http/managed-canned-failure`
     Each wraps `re-frame.http-machine-wrapper/canned-success-handler`
     / `canned-failure-handler` in the rf2-j1mo4 `:after-ms` delay
     decorator: absent / 0 `:after-ms` delegates straight through
     (immediate reply); a positive `:after-ms` defers via the framework
     `:dispatch-later`.
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
  the fx ids delegate (on the immediate path) to the machine-wrapper
  canned-* bodies, and the stub-family late-bind hooks are non-nil after
  the require. The deeper end-to-end
  behaviour (canned reply → late-bind dispatch → reply lands in
  app-db) is exercised by `re-frame.http-managed-test` and the
  corresponding CLJS smoke under `implementation/adapters/*`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
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

(deftest canned-stub-fxs-delegate-to-machine-wrapper-handlers
  (testing "rf2-j1mo4 — the registered handlers wrap the machine-wrapper
            canned-* vars in the `with-after-ms` delay decorator. On the
            immediate path (no `:after-ms`) the wrapper delegates straight
            through to the machine-wrapper body, so Spec 014 §Testing's
            args-map contract carries through unchanged; a positive
            `:after-ms` defers via the framework `:dispatch-later`.

            The handlers are deliberately NOT `identical?` to the
            machine-wrapper vars anymore — the delay is a parameter of the
            same effect, threaded by wrapping the reg-fx body. We pin the
            delegation by driving the immediate path through a stub
            late-bind router and asserting the wrapper body fired the
            machine-wrapper reply walk (a dispatch through `:router/dispatch!`)."
    (let [dispatched (atom [])
          ;; Save the live router hook and RESTORE it in finally — never
          ;; null it. Nulling `:router/dispatch!` is global state that
          ;; would silently break every subsequent test's `:dispatch`
          ;; cascade (it is published once at router load, not per-test).
          original   (late-bind/get-fn :router/dispatch!)]
      (late-bind/set-fn! :router/dispatch!
                         (fn [ev opts] (swap! dispatched conj [ev opts])))
      (try
        ;; Immediate path — no :after-ms. The wrapper must delegate to the
        ;; machine-wrapper body, which calls dispatch-reply-via-late-bind!.
        ((registrar/handler :fx :rf.http/managed-canned-success)
         {:frame :rf/default :event [:t/load]}
         {:value {:ok true}})
        (is (= 1 (count @dispatched))
            "immediate canned-success delegated to the machine-wrapper body (one reply dispatch)")
        (is (= :success (-> @dispatched first first (nth 1) :rf/reply :kind))
            "the synthesised reply is the machine-wrapper's success envelope")
        (finally
          (late-bind/set-fn! :router/dispatch! original))))))

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
