(ns realworld.favorites-test
  "Headless test for realworld.favorites — optimistic-update rollback.
   Uses Spec 014's `:rf.http/managed` substrate via the framework-shipped
   canned-stub fxs."
  (:require [re-frame.core :as rf]
            [realworld.favorites]
            [realworld.test-helpers :as th])
  (:require-macros [re-frame.core :refer [with-frame]]))

(defn favorite-toggle-test []
  (th/reg-canned-failure! :rf.http/managed.favorite-rollback
                          :rf.http/http-4xx
                          {:status 400
                           :body   {:errors {:body ["rollback"]}}})

  (with-frame [f (rf/make-frame {:on-create [:app/initialise]
                                 :fx-overrides {:rf.http/managed :rf.http/managed.favorite-rollback}})]
    (rf/dispatch-sync [:articles/initialise] {:frame f})
    (rf/dispatch-sync [:articles/loaded
                       {:kind :success
                        :value {:articles [{:slug "hello"
                                            :title "Hello"
                                            :description "Short"
                                            :body "Body"
                                            :tagList []
                                            :createdAt "2026-05-01"
                                            :updatedAt "2026-05-01"
                                            :favorited false
                                            :favoritesCount 0
                                            :author {:username "alice" :bio nil :image nil :following false}}]}}]
                      {:frame f})
    (rf/dispatch-sync [:article/toggle-favorite "hello"] {:frame f})
    ;; Optimistic flip + canned 4xx → rollback to original state.
    (assert (false? (-> (rf/compute-sub [:articles/data] (rf/get-frame-db f))
                        first
                        :favorited)))
    (assert (= 0 (-> (rf/compute-sub [:articles/data] (rf/get-frame-db f))
                     first
                     :favoritesCount)))))
