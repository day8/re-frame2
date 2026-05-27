(ns realworld.routing-test
  "Headless tests for realworld.routing — route table coverage including
   path params, query, not-found fallback, and the auth-guard interceptor
   (Spec 012 §Redirects and guards)."
  (:require [re-frame.core :as rf]
            [realworld.routing :as routing])
  (:require-macros [re-frame.core :refer [with-frame]]))

(defn routing-tests []
  (with-frame [f (rf/make-frame {:on-create [:app/initialise]})]
    (rf/dispatch-sync [:rf.route/navigate :conduit.article/show {:slug "hello"}] {:frame f})
    (assert (= :conduit.article/show (rf/compute-sub [:rf.route/id] (rf/get-frame-db f))))
    (assert (= "hello" (:slug (rf/compute-sub [:rf.route/params] (rf/get-frame-db f)))))

    (rf/dispatch-sync [:rf.route/handle-url-change "/profile/eve"] {:frame f})
    (assert (= :conduit.profile/show (rf/compute-sub [:rf.route/id] (rf/get-frame-db f))))

    (rf/dispatch-sync [:rf.route/handle-url-change "/settings"] {:frame f})
    (assert (= :conduit.user/settings (rf/compute-sub [:rf.route/id] (rf/get-frame-db f))))

    (rf/dispatch-sync [:rf.route/handle-url-change "/?tag=clojure"] {:frame f})
    (assert (= "clojure" (:tag (rf/compute-sub [:rf.route/query] (rf/get-frame-db f)))))

    (rf/dispatch-sync [:rf.route/handle-url-change "/garbage/path"] {:frame f})
    (assert (= :rf.route/not-found (rf/compute-sub [:rf.route/id] (rf/get-frame-db f))))))

(defn auth-guard-test []
  ;; The auth-guard is a plain interceptor (Spec 012 §Redirects and
  ;; guards) wired into the demo frame via `reg-frame :interceptors`
  ;; (core.cljs). Configure the test frame with the same interceptor so
  ;; the guard is exercised end-to-end.
  (with-frame [f (rf/make-frame {:on-create    [:app/initialise]
                                 :interceptors [routing/auth-guard]})]
    ;; Unauthenticated: navigating to a :requires-auth route
    ;; (:conduit.user/settings) is redirected to :conduit.auth/login.
    (rf/dispatch-sync [:rf.route/navigate :conduit.user/settings {}] {:frame f})
    (assert (= :conduit.auth/login (rf/compute-sub [:rf.route/id] (rf/get-frame-db f)))
            "unauthenticated nav to a :requires-auth route redirects to login")

    ;; A non-guarded route is unaffected by the guard.
    (rf/dispatch-sync [:rf.route/navigate :conduit/home {}] {:frame f})
    (assert (= :conduit/home (rf/compute-sub [:rf.route/id] (rf/get-frame-db f)))
            "unguarded route navigates normally with the guard installed")

    ;; Authenticated: the same guarded nav now proceeds.
    (rf/dispatch-sync [:auth/store-session {:username "eve" :token "t"}] {:frame f})
    (rf/dispatch-sync [:rf.route/navigate :conduit.user/settings {}] {:frame f})
    (assert (= :conduit.user/settings (rf/compute-sub [:rf.route/id] (rf/get-frame-db f)))
            "authenticated nav to a :requires-auth route proceeds")))
