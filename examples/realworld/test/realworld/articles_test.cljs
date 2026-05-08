(ns realworld.articles-test
  "Headless tests for realworld.articles — global feed loading + failure
   paths. Extracted from realworld/articles.cljs under rf2-4v73."
  (:require [re-frame.core :as rf]
            [realworld.articles])
  (:require-macros [re-frame.views-macros :refer [with-frame]]))

(defn articles-load-test []
  (rf/reg-fx :http.canned-articles
    {:platforms #{:client :server}}
    (fn [{:keys [frame]} {:keys [on-success]}]
      (when on-success
        (rf/dispatch
          (conj on-success
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
          {:frame frame}))))

  (with-frame [f (rf/make-frame {:on-create [:app/initialise]
                                 :fx-overrides {:http :http.canned-articles}})]
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
  (rf/reg-fx :http.canned-failure
    {:platforms #{:client :server}}
    (fn [{:keys [frame]} {:keys [on-error]}]
      (when on-error
        (rf/dispatch (conj on-error {:errors {:body ["server error"]}})
                     {:frame frame}))))

  (with-frame [f (rf/make-frame {:on-create [:app/initialise]
                                 :fx-overrides {:http :http.canned-failure}})]
    (rf/dispatch-sync [:articles/load] {:frame f})
    (assert (= :error (:status (rf/compute-sub [:articles] (rf/get-frame-db f)))))
    (assert (some? (rf/compute-sub [:articles/error] (rf/get-frame-db f))))))
