(ns re-frame.http-test-support-test
  "Smoke test for the `re-frame.http.test-support` leaf — required in
  the same PR as the new namespace per the per-leaf smoke-test
  convention (rf2-cdmle).

  Per rf2-lwmgw (audit-of-audits #15) the namespace is now the single
  home for every HTTP test surface:

   - load-time registration of the two canned-stub fxs:
      - `:rf.http/managed-canned-success`
      - `:rf.http/managed-canned-failure`
     Each wraps this namespace's own `canned-success-handler` /
     `canned-failure-handler` (rf2-w59es5 — the canned-stub bodies live
     here in test-support, not the production machine-wrapper) in the
     rf2-j1mo4 `:after-ms` delay decorator: absent / 0 `:after-ms`
     delegates straight through (immediate reply); a positive `:after-ms`
     defers via the framework `:dispatch-later`.
   - the stub fns:
      - `with-request-stubs`
      - `install-managed-request-stubs!`
      - `uninstall-managed-request-stubs!`
     None of the three is a `re-frame.core` re-export (rf2-ntwwyt,
     rf2-kuky.13) and none publishes a late-bind hook — tests call them
     directly from this namespace.

  This smoke pins the load-time side effects: fx registrations land, the
  fx ids delegate (on the immediate path) to this namespace's canned-*
  bodies, and `with-request-stubs` binds the scope override and route map
  for its thunk's dynamic extent. The deeper end-to-end
  behaviour (canned reply → late-bind dispatch → reply lands in
  app-db) is exercised by `re-frame.http-managed-test` and the
  corresponding CLJS smoke under `implementation/adapters/*`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.router :as rf.router]
            [re-frame.http.test-support :as rf.http.test-support]))

;; Ensure each test starts from a known registrar — clear, then reload
;; the test-support ns so its load-time registration fires.
(use-fixtures :each
  (fn [t]
    (rf.registrar/clear-all!)
    (require 're-frame.http.test-support :reload)
    (t)))

(deftest canned-stub-fxs-register-on-test-support-load
  (testing "loading re-frame.http.test-support registers both canned-stub fx ids"
    (is (some? (rf.registrar/lookup :fx :rf.http/managed-canned-success))
        ":rf.http/managed-canned-success registered")
    (is (some? (rf.registrar/lookup :fx :rf.http/managed-canned-failure))
        ":rf.http/managed-canned-failure registered")))

(deftest canned-stub-fxs-delegate-to-canned-handlers
  (testing "rf2-j1mo4 — the registered handlers wrap the canned-* handler
            vars in the `with-after-ms` delay decorator. On the immediate
            path (no `:after-ms`) the wrapper delegates straight through to
            the canned handler body, so Spec 014 §Testing's args-map contract
            carries through unchanged; a positive `:after-ms` defers via the
            framework `:dispatch-later`.

            The handlers are deliberately NOT `identical?` to the canned-*
            vars anymore — the delay is a parameter of the same effect,
            threaded by wrapping the reg-fx body. We pin the delegation by
            driving the immediate path through a stub late-bind router and
            asserting the canned body fired the reply walk (a dispatch
            through `:router/dispatch!`)."
    (let [dispatched (atom [])
          ;; Save the live router hook and RESTORE it in finally — never
          ;; null it. Nulling `:router/dispatch!` is global state that
          ;; would silently break every subsequent test's `:dispatch`
          ;; cascade (it is published once at router load, not per-test).
          original   (rf.late-bind/get-fn :router/dispatch!)]
      (rf.late-bind/set-fn! :router/dispatch!
                         (fn [ev opts] (swap! dispatched conj [ev opts])))
      (try
        ;; Immediate path — no :after-ms. The wrapper must delegate to the
        ;; canned handler body, which calls dispatch-reply-via-late-bind!.
        ((rf.registrar/handler :fx :rf.http/managed-canned-success)
         {:frame :rf/default :event [:t/load]}
         {:value {:ok true} :reply-to [:t/load]})
        (is (= 1 (count @dispatched))
            "immediate canned-success delegated to the canned handler body (one reply dispatch)")
        (is (= :ok (-> @dispatched first first (nth 1) :status))
            "the synthesised reply is the canned handler's success envelope (appended last arg)")
        (finally
          (rf.late-bind/set-fn! :router/dispatch! original))))))

(deftest with-request-stubs-binds-scope-override-and-route-map
  (testing "rf2-bxc8kf / rf2-kuky.13 — with-request-stubs binds BOTH the
            :rf.http/managed → :rf.test/managed-http-scope-stub fx-override and
            the scope's route map for the thunk's dynamic extent, and nesting
            SHADOWS the route map and restores it on exit. The end-to-end
            routing / nesting behaviour (dispatch-sync → synthesised reply in
            app-db, including inside a pre-created sealed frame) is exercised by
            re-frame.http-managed-test; what this leaf pins is the binding
            contract of the fn itself."
    (let [scope-stubs #(deref #'rf.http.test-support/*scope-stubs*)
          outer       {[:get "/a"] {:reply {:ok :from-outer}}}
          inner       {[:get "/b"] {:reply {:ok :from-inner}}}]
      (is (not (contains? rf.router/*fx-overrides* :rf.http/managed))
          "no :rf.http/managed override outside any scope")
      (rf.http.test-support/with-request-stubs outer
        (fn []
          (is (= :rf.test/managed-http-scope-stub
                 (:rf.http/managed rf.router/*fx-overrides*))
              "the scope binds the stable load-time override target")
          (is (= outer (scope-stubs))
              "the outer scope's route map is the live one")
          (rf.http.test-support/with-request-stubs inner
            (fn []
              (is (= inner (scope-stubs))
                  "an inner scope SHADOWS the outer route map")))
          (is (= outer (scope-stubs))
              "the outer route map is restored when the inner scope exits")))
      (is (not (contains? rf.router/*fx-overrides* :rf.http/managed))
          "both bindings unwind on exit — no registrar mutation to tear down"))))

(deftest scope-stub-fx-fires-loud-outside-any-scope
  (testing "the stable override target is the wrapper's private seam: firing it
            as an :fx-overrides value directly, with no with-request-stubs scope
            in effect, fails loud rather than synthesising a reply against
            ::no-scope"
    (let [handler (rf.registrar/handler :fx :rf.test/managed-http-scope-stub)
          thrown  (try (handler {:frame :rf/default :event [:t/load]}
                                {:request {:method :get :url "/a"}})
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown)
          "the scope-stub fx throws when fired outside a with-request-stubs scope")
      (is (= :rf.test/managed-http-scope-stub (:rf.fx/id (ex-data thrown)))
          "ex-data names the offending fx id"))))
