(ns realworld.comments-test
  "Headless tests for realworld.comments — article-detail load and
   comment-post happy path. Uses Spec 014's `:rf.http/managed`
   substrate via the framework-shipped canned-stub fxs.

   The `:article/load` event uses default reply addressing (Spec 014
   §Reply addressing — default form): no explicit `:on-success` /
   `:on-failure`, so the framework re-dispatches `:article/load` with
   `:rf/reply` merged into the original message. This test exercises
   that path end-to-end via the canned-success stub."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [realworld.comments]
            [realworld.test-helpers :as th])
  (:require-macros [re-frame.core :refer [with-frame]]))

(defn comments-load-test []
  ;; URL-routed stub: the article-detail page issues two requests
  ;; (`/articles/:slug` and `/articles/:slug/comments`); pick the canned
  ;; payload from the URL.
  (th/reg-canned-success-by-url! :rf.http/managed.canned-article-and-comments
                                 (fn [url]
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
                                                :author {:username "alice" :bio nil :image nil :following false}}})))

  (with-frame [f (rf/make-frame {:on-create [:app/initialise]
                                 :fx-overrides {:rf.http/managed :rf.http/managed.canned-article-and-comments}})]
    (rf/dispatch-sync [:article/initialise] {:frame f})
    (rf/dispatch-sync [:comments/initialise] {:frame f})
    (rf/dispatch-sync [:comment-form/initialise] {:frame f})
    (rf/dispatch-sync [:rf.route/handle-url-change "/article/hello"] {:frame f})
    (assert (= "hello" (:slug (rf/compute-sub [:article/data] (rf/get-frame-db f)))))
    (assert (= 1 (count (rf/compute-sub [:comments/data] (rf/get-frame-db f)))))))

(defn comment-submit-test []
  (th/reg-canned-success-by-url! :rf.http/managed.canned-comment-post
                                 (fn [method url]
                                   (cond
                                     ;; POST /articles/:slug/comments → returns the saved comment.
                                     (and (= :post method) (str/ends-with? url "/comments"))
                                     {:comment {:id 2
                                                :createdAt "2026-05-02"
                                                :updatedAt "2026-05-02"
                                                :body "Nice article."
                                                :author {:username "alice" :bio nil :image nil :following false}}}

                                     ;; GET /articles/:slug/comments → empty initial list.
                                     (and (= :get method) (str/ends-with? url "/comments"))
                                     {:comments []}

                                     :else
                                     ;; The route-driven :article/load also fires; return
                                     ;; an article so the page renders.
                                     {:article {:slug "hello"
                                                :title "Hello"
                                                :description "Short"
                                                :body "Body"
                                                :tagList []
                                                :createdAt "2026-05-01"
                                                :updatedAt "2026-05-01"
                                                :favorited false
                                                :favoritesCount 0
                                                :author {:username "alice" :bio nil :image nil :following false}}})))

  (with-frame [f (rf/make-frame {:on-create [:app/initialise]
                                 :fx-overrides {:rf.http/managed :rf.http/managed.canned-comment-post}})]
    (rf/dispatch-sync [:article/initialise] {:frame f})
    (rf/dispatch-sync [:comments/initialise] {:frame f})
    (rf/dispatch-sync [:comment-form/initialise] {:frame f})
    (rf/dispatch-sync [:auth/store-session {:username "alice" :email "a@b.c" :token "jwt" :bio nil :image nil}] {:frame f})
    (rf/dispatch-sync [:rf.route/handle-url-change "/article/hello"] {:frame f})
    (rf/dispatch-sync [:comment-form/edit-field :body "Nice article."] {:frame f})
    (rf/dispatch-sync [:comment-form/submit] {:frame f})
    (assert (= "" (:body (rf/compute-sub [:comment-form/draft] (rf/get-frame-db f)))))
    ;; Initial GET returned [] (no existing comments); POST returned 1
    ;; saved comment → exactly 1 comment in the slice after submit.
    (assert (= 1 (count (rf/compute-sub [:comments/data] (rf/get-frame-db f)))))))
