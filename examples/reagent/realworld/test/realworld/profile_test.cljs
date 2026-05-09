(ns realworld.profile-test
  "Headless test for realworld.profile — profile + authored-articles
   load. Retrofitted under rf2-o8t6 to use Spec 014's `:rf.http/managed`
   substrate via the framework-shipped canned-stub fxs."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [realworld.profile]
            [realworld.test-helpers :as th])
  (:require-macros [re-frame.views-macros :refer [with-frame]]))

(defn profile-load-test []
  (th/reg-canned-success-by-url! :rf.http/managed.canned-profile
                                 (fn [url]
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
                                                  :author {:username "eve" :bio nil :image nil :following false}}]})))

  (with-frame [f (rf/make-frame {:on-create [:app/initialise]
                                 :fx-overrides {:rf.http/managed :rf.http/managed.canned-profile}})]
    (rf/dispatch-sync [:profile/initialise] {:frame f})
    (rf/dispatch-sync [:rf.route/handle-url-change "/profile/eve"] {:frame f})
    (assert (= "eve" (:username (rf/compute-sub [:profile/data] (rf/get-frame-db f)))))
    (assert (= 1 (count (rf/compute-sub [:profile.articles/data] (rf/get-frame-db f)))))))
