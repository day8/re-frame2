(ns realworld.auth-test
  "Headless tests for realworld.auth — the auth state machine.

   Each test stubs `:rf.http/managed` via `:fx-overrides` and synthesises
   the canonical reply shape (`{:kind :success :value v}` /
   `{:kind :failure :failure m}`) through the framework-shipped
   canned-stub fxs.

   Each test fn keeps its original signature (a plain zero-arg fn) so the
   integration runner can call them as before."
  (:require [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [realworld.auth]
            [realworld.test-helpers :as th])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(defn login-happy-path-test []
  (th/reg-canned-success! :realworld.test/login-success
                          {:user {:email    "alice@example.com"
                                  :username "alice"
                                  :token    "jwt-abc"
                                  :bio      nil
                                  :image    nil}})

  (with-new-frame [f (rf/make-frame {:on-create    [:auth/initialise]
                                 :fx-overrides {:rf.http/managed      :realworld.test/login-success
                                                :auth.session/persist :rf/no-op}})]
    (assert (= :idle (rf/compute-sub [:auth/state] (rf/get-frame-db f))))

    (rf/dispatch-sync [:auth/flow [:auth/login {:email "alice@example.com"
                                                :password "correct-horse"}]]
                      {:frame f})
    (assert (= :authed (rf/compute-sub [:auth/state] (rf/get-frame-db f))))
    (assert (= "alice" (:username (rf/compute-sub [:auth/user] (rf/get-frame-db f)))))

    (rf/dispatch-sync [:auth/flow [:auth/logout]] {:frame f})
    (assert (= :idle (rf/compute-sub [:auth/state] (rf/get-frame-db f))))
    (assert (nil? (rf/compute-sub [:auth/user] (rf/get-frame-db f))))))

(defn session-token-cofx-shape-test []
  ;; Cofx-shape contract — the :auth.session/token cofx must assoc its
  ;; injected value into `(:coeffects ctx)` (not the top of ctx). If
  ;; the shape regresses, the value lands at ctx[<cofx-id>] and the
  ;; handler's destructure binds nil — silently.
  ;;
  ;; Call the registered cofx handler directly with a known empty input
  ;; ctx and assert the value lands at the conventional path. The cofx
  ;; reads from `js/globalThis.localStorage`; node-side that is absent
  ;; so the value is nil — but the SHAPE is what matters here:
  ;; `:coeffects` must be present and contain the key.
  (let [cofx-meta (registrar/handler-meta :cofx :auth.session/token)
        handler   (:handler-fn cofx-meta)
        result    (handler {:coeffects {}} nil)]
    (assert (contains? (:coeffects result) :auth.session/token)
            "cofx body must assoc its injected value under [:coeffects :auth.session/token]")
    (assert (nil? (get result :auth.session/token))
            "the misshapen-cofx witness — the value must NOT land at top of ctx")))

(defn login-failure-test []
  (th/reg-canned-failure! :realworld.test/login-failure
                          :rf.http/http-4xx
                          {:status 422
                           :body   {:errors {:body ["email or password is invalid"]}}})

  (with-new-frame [f (rf/make-frame {:on-create    [:auth/initialise]
                                 :fx-overrides {:rf.http/managed :realworld.test/login-failure}})]
    (rf/dispatch-sync [:auth/flow [:auth/login {:email "x@y.z" :password "wrong"}]]
                      {:frame f})
    (assert (= :error (rf/compute-sub [:auth/state] (rf/get-frame-db f))))
    (assert (some? (rf/compute-sub [:auth/error] (rf/get-frame-db f))))

    (rf/dispatch-sync [:auth/flow [:auth/dismiss]] {:frame f})
    (assert (= :idle (rf/compute-sub [:auth/state] (rf/get-frame-db f))))))
