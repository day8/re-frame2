(ns realworld.auth-test
  "Headless tests for realworld.auth — the auth state machine.

   Each test stubs `:rf.http/managed` via `:fx-overrides` and synthesises
   the canonical reply shape (`{:kind :success :value v}` /
   `{:kind :failure :failure m}`) through the framework-shipped
   canned-stub fxs.

   Each test fn keeps its original signature (a plain zero-arg fn) so the
   integration runner can call them as before."
  (:require [re-frame.core :as rf]
            [realworld.auth]
            [realworld.test-helpers :as th])
  (:require-macros [re-frame.views-macros :refer [with-frame]]))

(defn login-happy-path-test []
  (th/reg-canned-success! :rf.http/managed.login-success
                          {:user {:email    "alice@example.com"
                                  :username "alice"
                                  :token    "jwt-abc"
                                  :bio      nil
                                  :image    nil}})

  (with-frame [f (rf/make-frame {:on-create    [:auth/initialise]
                                 :fx-overrides {:rf.http/managed     :rf.http/managed.login-success
                                                :auth.session/store  :rf/no-op
                                                :auth.session/clear  :rf/no-op}})]
    (assert (= :idle (rf/compute-sub [:auth/state] (rf/get-frame-db f))))

    (rf/dispatch-sync [:auth/flow [:auth/login {:email "alice@example.com"
                                                :password "correct-horse"}]]
                      {:frame f})
    (assert (= :authed (rf/compute-sub [:auth/state] (rf/get-frame-db f))))
    (assert (= "alice" (:username (rf/compute-sub [:auth/user] (rf/get-frame-db f)))))

    (rf/dispatch-sync [:auth/flow [:auth/logout]] {:frame f})
    (assert (= :idle (rf/compute-sub [:auth/state] (rf/get-frame-db f))))
    (assert (nil? (rf/compute-sub [:auth/user] (rf/get-frame-db f))))))

(defn login-failure-test []
  (th/reg-canned-failure! :rf.http/managed.login-failure
                          :rf.http/http-4xx
                          {:status 422
                           :body   {:errors {:body ["email or password is invalid"]}}})

  (with-frame [f (rf/make-frame {:on-create    [:auth/initialise]
                                 :fx-overrides {:rf.http/managed :rf.http/managed.login-failure}})]
    (rf/dispatch-sync [:auth/flow [:auth/login {:email "x@y.z" :password "wrong"}]]
                      {:frame f})
    (assert (= :error (rf/compute-sub [:auth/state] (rf/get-frame-db f))))
    (assert (some? (rf/compute-sub [:auth/error] (rf/get-frame-db f))))

    (rf/dispatch-sync [:auth/flow [:auth/dismiss]] {:frame f})
    (assert (= :idle (rf/compute-sub [:auth/state] (rf/get-frame-db f))))))
