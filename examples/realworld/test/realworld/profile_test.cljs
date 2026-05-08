(ns realworld.profile-test
  "Headless test for realworld.profile — profile + authored-articles
   load. Extracted from realworld/profile.cljs under rf2-4v73."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [realworld.profile])
  (:require-macros [re-frame.views-macros :refer [with-frame]]))

(defn profile-load-test []
  (rf/reg-fx :http.canned-profile
    {:platforms #{:client :server}}
    (fn [{:keys [frame]} {:keys [url on-success]}]
      (when on-success
        (rf/dispatch
          (conj on-success
                (if (str/includes? url "/profiles/")
                  {:profile {:username "eve" :bio "Writes things" :image nil :following false}}
                  {:articles [{:slug "one"
                               :title "One"
                               :description "Short"
                               :body "Body"
                               :tagList []
                               :createdAt "2026-05-01"
                               :updatedAt "2026-05-01"
                               :favorited false
                               :favoritesCount 0
                               :author {:username "eve" :bio nil :image nil :following false}}]}))
          {:frame frame}))))

  (with-frame [f (rf/make-frame {:on-create [:app/initialise]
                                 :fx-overrides {:http :http.canned-profile}})]
    (rf/dispatch-sync [:profile/initialise] {:frame f})
    (rf/dispatch-sync [:rf.route/handle-url-change "/profile/eve"] {:frame f})
    (assert (= "eve" (:username (rf/compute-sub [:profile/data] (rf/get-frame-db f)))))
    (assert (= 1 (count (rf/compute-sub [:profile.articles/data] (rf/get-frame-db f)))))))
