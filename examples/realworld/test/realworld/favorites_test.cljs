(ns realworld.favorites-test
  "Headless test for realworld.favorites — optimistic-update rollback.
   Extracted from realworld/favorites.cljs under rf2-4v73."
  (:require [re-frame.core :as rf]
            [realworld.favorites])
  (:require-macros [re-frame.views-macros :refer [with-frame]]))

(defn favorite-toggle-test []
  (rf/reg-fx :http.canned-favorite-rollback
    {:platforms #{:client :server}}
    (fn [{:keys [frame]} {:keys [on-error]}]
      (when on-error
        (rf/dispatch (conj on-error {:errors {:body ["rollback"]}}) {:frame frame}))))

  (with-frame [f (rf/make-frame {:on-create [:app/initialise]
                                 :fx-overrides {:http :http.canned-favorite-rollback}})]
    (rf/dispatch-sync [:articles/initialise] {:frame f})
    (rf/dispatch-sync [:articles/loaded
                       {:articles [{:slug "hello"
                                    :title "Hello"
                                    :description "Short"
                                    :body "Body"
                                    :tagList []
                                    :createdAt "2026-05-01"
                                    :updatedAt "2026-05-01"
                                    :favorited false
                                    :favoritesCount 0
                                    :author {:username "alice" :bio nil :image nil :following false}}]}]
                      {:frame f})
    (rf/dispatch-sync [:article/toggle-favorite "hello"] {:frame f})
    (assert (false? (-> (rf/compute-sub [:articles/data] (rf/get-frame-db f))
                        first
                        :favorited)))
    (assert (= 0 (-> (rf/compute-sub [:articles/data] (rf/get-frame-db f))
                     first
                     :favoritesCount)))))
