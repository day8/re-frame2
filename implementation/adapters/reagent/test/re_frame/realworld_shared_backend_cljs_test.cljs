(ns re-frame.realworld-shared-backend-cljs-test
  "Contract tests for the shared in-process Conduit demo backend
   (`realworld-shared.demo-backend`), which both RealWorld examples run offline
   against. Both apps step their own state atom through `demo/transition`, so
   pinning `transition` pins both.

   The property under test is temporal: every test writes something and then
   reads it back through a separate request, because a backend that threw
   writes away would still return well-shaped replies."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [malli.core :as m]
            [realworld-shared.demo-backend :as demo]
            [realworld-shared.schema :as ws]))

(def ^:private base "https://api.realworld.show/api")

(defn- world [] (atom (demo/fresh-state)))

(defn- send!
  "Step `world` through one request and return the reply (`{:ok …}` or
   `{:failure …}`), as `demo/respond` does without the canned-fx tail."
  ([world method path] (send! world method path nil))
  ([world method path extra]
   (let [[next-state reply]
         (demo/transition @world
                          {:request (merge {:method method :url (str base path)} extra)})]
     (reset! world next-state)
     reply)))

(defn- ok! [& args] (:ok (apply send! args)))
(defn- status! [& args] (:status (:tags (:failure (apply send! args)))))

(defn- slugs [articles-response] (mapv :slug (:articles articles-response)))

(defn- login! [w]
  (:token (:user (ok! w :post "/users/login" {:body {:user {:email "demo@conduit.dev" :password "x"}}}))))

(def ^:private demo-username "demo")
(def ^:private seed-author "stub-bot")

(deftest create-then-read-by-slug-list-and-author
  (let [w       (world)
        created (:article (ok! w :post "/articles"
                               {:body {:article {:title       "My New Post"
                                                 :description "d"
                                                 :body        "b"
                                                 :tagList     ["freshtag"]}}}))]
    (is (= ["my-new-post" demo-username] [(:slug created) (:username (:author created))])
        "create derives a slug and reports the acting user as the author")
    (is (= "My New Post" (:title (:article (ok! w :get "/articles/my-new-post")))))
    (let [page1 (ok! w :get "/articles?limit=10&offset=0")]
      (is (= ["my-new-post" (inc (count demo/seed-articles))]
             [(first (slugs page1)) (:articlesCount page1)])
          "newest first, and counted in the grand total"))
    (is (= ["my-new-post"] (slugs (ok! w :get (str "/articles?author=" demo-username "&limit=10&offset=0")))))
    (is (some #{"freshtag"} (:tags (ok! w :get "/tags"))) "the tag list is derived from the articles that exist")
    (testing "saving the same title again yields a second article under a distinct slug"
      (is (= "my-new-post-2"
             (:slug (:article (ok! w :post "/articles"
                                   {:body {:article {:title "My New Post" :description "d"
                                                     :body "b" :tagList []}}})))))
      (is (= "my-new-post" (:slug (:article (ok! w :get "/articles/my-new-post"))))))))

(deftest update-then-read
  (let [w (world)]
    (ok! w :put "/articles/hello-conduit"
         {:body {:article {:title "Edited" :description "new description"
                           :body "new body" :tagList ["edited"]}}})
    (is (= {:slug "hello-conduit" :title "Edited" :description "new description" :tagList ["edited"]}
           (select-keys (:article (ok! w :get "/articles/hello-conduit")) [:slug :title :description :tagList]))
        "a later GET returns the edit, and the slug stays put")
    (is (= "Edited" (->> (:articles (ok! w :get "/articles?limit=100&offset=0"))
                         (some #(when (= "hello-conduit" (:slug %)) (:title %))))))
    (is (= ["hello-conduit"] (slugs (ok! w :get "/articles?tag=edited"))))))

(deftest delete-then-read
  (let [w (world)]
    (ok! w :post "/articles/second-article/favorite")
    (ok! w :delete "/articles/second-article")
    (is (= [:rf.http/http-4xx 404]
           ((juxt :kind (comp :status :tags)) (:failure (send! w :get "/articles/second-article"))))
        "the deleted article is gone, never a plausible substitute")
    (let [page (ok! w :get "/articles?limit=100&offset=0")]
      (is (not-any? #{"second-article"} (slugs page)))
      (is (= (dec (count demo/seed-articles)) (:articlesCount page))))
    (is (not-any? #{"second-article"} (slugs (ok! w :get (str "/articles?favorited=" demo-username "&limit=100"))))
        "no orphan favourite pointing at a deleted article")))

(deftest unknown-slugs-fail-instead-of-substituting-the-first-article
  (let [w (world)]
    (is (= 404 (status! w :get "/articles/does-not-exist"))
        "an unknown slug is a 404, not article #1")
    (is (= 404 (status! w :post "/articles/nope/favorite")))
    (is (= 404 (status! w :get "/nonsense"))
        "a route the demo does not implement fails loudly rather than returning {}")))

(deftest comment-post-get-delete-sequence
  (let [w    (world)
        seed (:comments (ok! w :get "/articles/hello-conduit/comments"))]
    (is (= [1] (mapv :id seed)) "the seeded comment is there before anything is written")
    (let [written (:comment (ok! w :post "/articles/hello-conduit/comments"
                                 {:body {:comment {:body "great read"}}}))]
      (is (= [1000 "2026-01-01T00:00:01.000Z" "great read" demo-username]
             ((juxt :id :createdAt :body (comp :username :author)) written))
          "the saved comment carries a deterministic id and timestamp")
      (is (= (conj seed written) (:comments (ok! w :get "/articles/hello-conduit/comments"))))
      (is (= 1001 (:id (:comment (ok! w :post "/articles/hello-conduit/comments"
                                      {:body {:comment {:body "and another"}}}))))))
    (ok! w :delete "/articles/hello-conduit/comments/1000")
    (is (= [1 1001] (mapv :id (:comments (ok! w :get "/articles/hello-conduit/comments"))))
        "DELETE removes exactly that comment")
    (is (= 404 (status! w :delete "/articles/hello-conduit/comments/1000")) "a second delete fails")
    (is (= 404 (status! w :get "/articles/nope/comments")))))

(deftest favorite-persists-through-detail-lists-and-the-favorited-tab
  (let [w      (world)
        tab    #(slugs (ok! w :get (str "/articles?favorited=" demo-username "&limit=100&offset=0")))
        detail #((juxt :favorited :favoritesCount) (:article (ok! w :get (str "/articles/" %))))]
    (let [fav (tab)]
      (is (some #{"hello-conduit"} fav))
      (is (< (count fav) (count demo/seed-articles)) "the Favorited tab is a strict subset, not the corpus"))
    (is (= 0 (:articlesCount (ok! w :get (str "/articles?favorited=" seed-author "&limit=100")))))
    (ok! w :post "/articles/second-article/favorite")
    (is (= [true 1] (detail "second-article")))
    (is (true? (some #(when (= "second-article" (:slug %)) (:favorited %))
                     (:articles (ok! w :get "/articles?limit=100&offset=0")))))
    (is (some #{"second-article"} (tab)))
    (ok! w :delete "/articles/hello-conduit/favorite")
    (is (not-any? #{"hello-conduit"} (tab)) "unfavouriting a seeded favourite drops it out of the tab")
    (is (= [false 0] (detail "hello-conduit")))))

(deftest follow-persists-through-profile-reads-and-drives-the-feed
  (let [w         (world)
        feed      #(ok! w :get "/articles/feed?limit=10&offset=0")
        following #(:following (:profile (ok! w :get (str "/profiles/" seed-author))))]
    (is (= {:articles [] :articlesCount 0} (feed)) "an empty feed is a well-formed envelope")
    (is (= [true true]
           [(:following (:profile (ok! w :post (str "/profiles/" seed-author "/follow")))) (following)])
        "following persists into the profile read that follows it")
    (let [f (feed)]
      (is (= [10 (count demo/seed-articles)] [(count (:articles f)) (:articlesCount f)]))
      (is (every? #(= seed-author (:username (:author %))) (:articles f))))
    (ok! w :delete (str "/profiles/" seed-author "/follow"))
    (is (= [false 0] [(following) (:articlesCount (feed))]) "unfollowing empties it again")))

(deftest settings-changes-survive-later-user-and-profile-reads
  (let [w    (world)
        auth {:headers {"Authorization" (str "Token " (login! w))}}]
    (ok! w :put "/user" {:body {:user {:bio "Rewritten bio." :image "https://example.test/a.png"}}})
    (is (= ["Rewritten bio." "https://example.test/a.png"]
           ((juxt :bio :image) (:user (ok! w :get "/user" auth)))))
    (is (= "Rewritten bio." (:bio (:profile (ok! w :get (str "/profiles/" demo-username))))))
    (testing "renaming yourself moves your byline with you"
      (ok! w :post "/articles" {:body {:article {:title "Mine" :description "d" :body "b" :tagList []}}})
      (ok! w :put "/user" {:body {:user {:username "renamed"}}})
      (is (= "renamed" (:username (:author (:article (ok! w :get "/articles/mine"))))))
      (is (= ["mine"] (slugs (ok! w :get "/articles?author=renamed&limit=10")))))))

(deftest session-restore-needs-a-token-this-world-issued
  (let [w (world)]
    (is (= [:rf.http/decode-failure true]
           ((juxt :kind (comp :schema-validation-failure? :tags)) (:failure (send! w :get "/user"))))
        "a cold world has issued no token, so restore fails down the decode path, as a real refusal does")
    (let [token (login! w)]
      (is (= demo-username
             (:username (:user (ok! w :get "/user" {:headers {"Authorization" (str "Token " token)}})))))
      (is (some? (:failure (send! w :get "/user" {:headers {"Authorization" "Token not-the-one"}})))))))

(deftest list-queries-are-applied-to-current-state
  (let [w (world)]
    (let [p1 (ok! w :get "/articles?limit=10&offset=0")
          p2 (ok! w :get "/articles?limit=10&offset=10")]
      (is (= [10 (count demo/seed-articles)] [(count (:articles p1)) (:articlesCount p1)]))
      (is (empty? (filter (set (slugs p1)) (slugs p2))) "page 2 shares nothing with page 1"))
    (let [tagged (ok! w :get "/articles?tag=intro&limit=10&offset=0")]
      (is (= [["hello-conduit"] 1] [(slugs tagged) (:articlesCount tagged)])
          "articlesCount describes the filtered set"))
    (ok! w :post "/articles" {:body {:article {:title "Tagged Later" :description "d"
                                               :body "b" :tagList ["intro"]}}})
    (is (= 2 (:articlesCount (ok! w :get "/articles?tag=intro&limit=10&offset=0"))))
    (ok! w :post "/articles" {:body {:article {:title "Odd Tag" :description "d"
                                               :body "b" :tagList ["a&b c"]}}})
    (is (= ["odd-tag"] (slugs (ok! w :get "/articles?tag=a%26b%20c&limit=10")))
        "query values are URL-decoded")))

(deftest identical-sequences-are-deterministic
  (let [sequence [[:post   "/users/login"                      {:body {:user {:email "e" :password "p"}}}]
                  [:post   "/articles"                         {:body {:article {:title "Same" :description "d"
                                                                                 :body "b" :tagList ["t"]}}}]
                  [:post   "/articles/same/comments"           {:body {:comment {:body "c"}}}]
                  [:post   "/articles/hello-conduit/favorite"  nil]
                  [:post   "/profiles/stub-bot/follow"         nil]
                  [:get    "/articles?limit=10&offset=0"       nil]
                  [:get    "/articles/same/comments"           nil]
                  [:get    "/articles/feed?limit=10&offset=0"  nil]]
        run     (fn []
                  (let [w (world)]
                    [(mapv (fn [[method path extra]] (send! w method path extra)) sequence)
                     @w]))
        a       (run)]
    (is (every? #(contains? % :ok) (first a)) "precondition: the whole sequence succeeds")
    (is (= a (run)) "the same sequence against a fresh world produces the same replies and state")))

;; The canned-success fx never runs `:decode`, so validate the replies against
;; the shared schemas both apps decode with.
(deftest replies-validate-against-the-shared-wire-schemas
  (let [w    (world)
        auth {:headers {"Authorization" (str "Token " (login! w))}}]
    (ok! w :post "/articles" {:body {:article {:title "Wire Check" :description "d"
                                               :body "b" :tagList ["w"]}}})
    (doseq [[schema reply label]
            [[ws/UserResponse     (ok! w :get "/user" auth)                             "GET /user"]
             [ws/ArticleResponse  (ok! w :get "/articles/wire-check")                   "GET /articles/:slug"]
             [ws/ArticlesResponse (ok! w :get "/articles?limit=10&offset=0")            "GET /articles"]
             [ws/CommentResponse  (ok! w :post "/articles/wire-check/comments"
                                       {:body {:comment {:body "c"}}})                  "POST comment"]
             [ws/CommentsResponse (ok! w :get "/articles/wire-check/comments")          "GET comments"]
             [ws/ProfileResponse  (ok! w :get "/profiles/stub-bot")                     "GET /profiles/:username"]
             [ws/TagsResponse     (ok! w :get "/tags")                                  "GET /tags"]]]
      (is (m/validate schema reply) label))))
