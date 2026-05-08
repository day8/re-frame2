(ns realworld.comments-test
  "Headless tests for realworld.comments — article-detail load and
   comment-post happy path. Extracted from realworld/comments.cljs under
   rf2-4v73."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [realworld.comments])
  (:require-macros [re-frame.views-macros :refer [with-frame]]))

(defn comments-load-test []
  (rf/reg-fx :http.canned-article-and-comments
    {:platforms #{:client :server}}
    (fn [{:keys [frame]} {:keys [url on-success]}]
      (when on-success
        (rf/dispatch
          (conj on-success
                (cond
                  (str/ends-with? url "/comments")
                  {:comments [{:id 1
                               :createdAt "2026-05-01"
                               :updatedAt "2026-05-01"
                               :body "First!"
                               :author {:username "eve" :bio nil :image nil :following false}}]}

                  :else
                  {:article {:slug "hello"
                             :title "Hello"
                             :description "Short"
                             :body "Body"
                             :tagList ["demo"]
                             :createdAt "2026-05-01"
                             :updatedAt "2026-05-01"
                             :favorited false
                             :favoritesCount 0
                             :author {:username "alice" :bio nil :image nil :following false}}}))
          {:frame frame}))))

  (with-frame [f (rf/make-frame {:on-create [:app/initialise]
                                 :fx-overrides {:http :http.canned-article-and-comments}})]
    (rf/dispatch-sync [:article/initialise] {:frame f})
    (rf/dispatch-sync [:comments/initialise] {:frame f})
    (rf/dispatch-sync [:comment-form/initialise] {:frame f})
    (rf/dispatch-sync [:rf.route/handle-url-change "/article/hello"] {:frame f})
    (assert (= "hello" (:slug (rf/compute-sub [:article/data] (rf/get-frame-db f)))))
    (assert (= 1 (count (rf/compute-sub [:comments/data] (rf/get-frame-db f)))))))

(defn comment-submit-test []
  (rf/reg-fx :http.canned-comment-post
    {:platforms #{:client :server}}
    (fn [{:keys [frame]} {:keys [url on-success]}]
      (when on-success
        (rf/dispatch
          (conj on-success
                {:comment {:id 2
                           :createdAt "2026-05-02"
                           :updatedAt "2026-05-02"
                           :body "Nice article."
                           :author {:username "alice" :bio nil :image nil :following false}}})
          {:frame frame}))))

  (with-frame [f (rf/make-frame {:on-create [:app/initialise]
                                 :fx-overrides {:http :http.canned-comment-post}})]
    (rf/dispatch-sync [:article/initialise] {:frame f})
    (rf/dispatch-sync [:comments/initialise] {:frame f})
    (rf/dispatch-sync [:comment-form/initialise] {:frame f})
    (rf/dispatch-sync [:auth/store-session {:username "alice" :email "a@b.c" :token "jwt" :bio nil :image nil}] {:frame f})
    (rf/dispatch-sync [:rf.route/handle-url-change "/article/hello"] {:frame f})
    (rf/dispatch-sync [:comment-form/edit-field :body "Nice article."] {:frame f})
    (rf/dispatch-sync [:comment-form/submit] {:frame f})
    (assert (= "" (:body (rf/compute-sub [:comment-form/draft] (rf/get-frame-db f)))))
    (assert (= 1 (count (rf/compute-sub [:comments/data] (rf/get-frame-db f)))))))
