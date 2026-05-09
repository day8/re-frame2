(ns realworld.articles-test
  "Headless tests for realworld.articles — global feed loading + failure
   paths. Retrofitted under rf2-o8t6 to use Spec 014's `:rf.http/managed`
   substrate via the framework-shipped canned-stub fxs."
  (:require [re-frame.core :as rf]
            [realworld.articles]
            [realworld.test-helpers :as th])
  (:require-macros [re-frame.views-macros :refer [with-frame]]))

(defn articles-load-test []
  (th/reg-canned-success! :rf.http/managed.canned-articles
                          {:articles [{:slug "hello-world"
                                       :title "Hello, world"
                                       :description "An intro"
                                       :body "..."
                                       :tagList ["intro"]
                                       :createdAt "2026-05-01T00:00:00Z"
                                       :updatedAt "2026-05-01T00:00:00Z"
                                       :favorited false
                                       :favoritesCount 0
                                       :author {:username "alice" :bio nil :image nil
                                                :following false}}]
                           :articlesCount 1})

  (with-frame [f (rf/make-frame {:on-create [:app/initialise]
                                 :fx-overrides {:rf.http/managed :rf.http/managed.canned-articles}})]
    (assert (= :idle (:status (rf/compute-sub [:articles] (rf/get-frame-db f)))))
    (rf/dispatch-sync [:articles/load] {:frame f})
    (let [slice (rf/compute-sub [:articles] (rf/get-frame-db f))]
      (assert (= :loaded (:status slice)))
      (assert (= 1 (count (:data slice))))
      (assert (= "hello-world" (-> slice :data first :slug))))
    (rf/dispatch-sync [:articles/load] {:frame f})
    (let [slice (rf/compute-sub [:articles] (rf/get-frame-db f))]
      (assert (= :loaded (:status slice)))
      (assert (= 2 (:attempt slice))))))

(defn articles-load-failure-test []
  (th/reg-canned-failure! :rf.http/managed.canned-articles-failure
                          :rf.http/http-5xx
                          {:status 500
                           :body   "server error"})

  (with-frame [f (rf/make-frame {:on-create [:app/initialise]
                                 :fx-overrides {:rf.http/managed :rf.http/managed.canned-articles-failure}})]
    (rf/dispatch-sync [:articles/load] {:frame f})
    (assert (= :error (:status (rf/compute-sub [:articles] (rf/get-frame-db f)))))
    (assert (some? (rf/compute-sub [:articles/error] (rf/get-frame-db f))))))
