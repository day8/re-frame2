(ns realworld.auth-test
  "Headless tests for realworld.auth — the auth state machine.

   These were previously inline in realworld/auth.cljs; extracted under
   rf2-4v73 so production code stays test-free.

   Each test fn keeps its original signature (a plain zero-arg fn) so the
   integration runner can call them as before. The realworld.feature-test
   integration wrapper at implementation/test/re_frame/realworld_cljs_test.cljs
   exposes them to the shadow-cljs node-test runner."
  (:require [re-frame.core :as rf]
            [realworld.auth])
  (:require-macros [re-frame.views-macros :refer [with-frame]]))

(defn login-happy-path-test []
  (rf/reg-fx :http.canned-login-success
    {:platforms #{:client :server}}
    (fn [{:keys [frame]} {:keys [on-success]}]
      (when on-success
        (rf/dispatch (conj on-success {:user {:email    "alice@example.com"
                                              :username "alice"
                                              :token    "jwt-abc"
                                              :bio      nil
                                              :image    nil}})
                     {:frame frame}))))

  (with-frame [f (rf/make-frame {:on-create    [:auth/initialise]
                                 :fx-overrides {:http :http.canned-login-success
                                                :auth.session/store :rf/no-op
                                                :auth.session/clear :rf/no-op}})]
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
  (rf/reg-fx :http.canned-failure
    {:platforms #{:client :server}}
    (fn [{:keys [frame]} {:keys [on-error]}]
      (when on-error
        (rf/dispatch (conj on-error {:errors ["email or password is invalid"]})
                     {:frame frame}))))

  (with-frame [f (rf/make-frame {:on-create    [:auth/initialise]
                                 :fx-overrides {:http :http.canned-failure}})]
    (rf/dispatch-sync [:auth/flow [:auth/login {:email "x@y.z" :password "wrong"}]]
                      {:frame f})
    (assert (= :error (rf/compute-sub [:auth/state] (rf/get-frame-db f))))
    (assert (some? (rf/compute-sub [:auth/error] (rf/get-frame-db f))))

    (rf/dispatch-sync [:auth/flow [:auth/dismiss]] {:frame f})
    (assert (= :idle (rf/compute-sub [:auth/state] (rf/get-frame-db f))))))
