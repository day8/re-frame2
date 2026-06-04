(ns realworld.routing-test
  "Headless tests for realworld.routing — route table coverage including
   path params, query, not-found fallback, and the auth-guard interceptor
   (Spec 012 §Redirects and guards)."
  (:require [re-frame.core :as rf]
            [realworld.routing :as routing])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(defn routing-tests []
  (with-new-frame [f (rf/make-frame {:on-create [:app/initialise]})]
    (rf/dispatch-sync [:rf.route/navigate :realworld.article/show {:slug "hello"}] {:frame f})
    (assert (= :realworld.article/show (rf/compute-sub [:rf.route/id] (rf/app-db-value f))))
    (assert (= "hello" (:slug (rf/compute-sub [:rf.route/params] (rf/app-db-value f)))))

    (rf/dispatch-sync [:rf.route/handle-url-change "/profile/eve"] {:frame f})
    (assert (= :realworld.profile/show (rf/compute-sub [:rf.route/id] (rf/app-db-value f))))

    (rf/dispatch-sync [:rf.route/handle-url-change "/settings"] {:frame f})
    (assert (= :realworld.user/settings (rf/compute-sub [:rf.route/id] (rf/app-db-value f))))

    (rf/dispatch-sync [:rf.route/handle-url-change "/?tag=clojure"] {:frame f})
    (assert (= "clojure" (:tag (rf/compute-sub [:rf.route/query] (rf/app-db-value f)))))

    (rf/dispatch-sync [:rf.route/handle-url-change "/garbage/path"] {:frame f})
    (assert (= :rf.route/not-found (rf/compute-sub [:rf.route/id] (rf/app-db-value f))))))

(defn auth-guard-test []
  ;; The auth-guard is a plain interceptor (Spec 012 §Redirects and
  ;; guards) wired into the demo frame via `reg-frame :interceptors`
  ;; (core.cljs). Configure the test frame with the same interceptor so
  ;; the guard is exercised end-to-end.
  (with-new-frame [f (rf/make-frame {:on-create    [:app/initialise]
                                 :interceptors [routing/auth-guard]})]
    ;; Unauthenticated: navigating to a :requires-auth route
    ;; (:realworld.user/settings) is redirected to :realworld.auth/login.
    (rf/dispatch-sync [:rf.route/navigate :realworld.user/settings {}] {:frame f})
    (assert (= :realworld.auth/login (rf/compute-sub [:rf.route/id] (rf/app-db-value f)))
            "unauthenticated nav to a :requires-auth route redirects to login")

    ;; A non-guarded route is unaffected by the guard.
    (rf/dispatch-sync [:rf.route/navigate :realworld/home {}] {:frame f})
    (assert (= :realworld/home (rf/compute-sub [:rf.route/id] (rf/app-db-value f)))
            "unguarded route navigates normally with the guard installed")

    ;; Bounce-back stash (rf2-ygh4m ITEM 1): the redirect to login also
    ;; records the original target at [:auth :return-to] so a post-login
    ;; handler can return the user there. (We are still on /login from the
    ;; first redirect above; navigate back to a guarded route to re-stash.)
    (rf/dispatch-sync [:rf.route/navigate :realworld.user/settings {}] {:frame f})
    (assert (= {:id :realworld.user/settings :params {}}
               (get-in (rf/app-db-value f) [:auth :return-to]))
            "the redirect stashes the original target for post-login bounce-back")

    ;; Authenticated: the same guarded nav now proceeds.
    (rf/dispatch-sync [:auth/store-session {:username "eve" :token "t"}] {:frame f})
    (rf/dispatch-sync [:rf.route/navigate :realworld.user/settings {}] {:frame f})
    (assert (= :realworld.user/settings (rf/compute-sub [:rf.route/id] (rf/app-db-value f)))
            "authenticated nav to a :requires-auth route proceeds")

    ;; The post-login bounce-back event consumes the stashed target and
    ;; clears the slot. With a stash present it navigates THERE; we assert
    ;; the slot is cleared after one consumption.
    (rf/dispatch-sync [:auth/post-login-redirect] {:frame f})
    (assert (nil? (get-in (rf/app-db-value f) [:auth :return-to]))
            ":auth/post-login-redirect clears the :return-to slot")))
