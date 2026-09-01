(ns re-frame.realworld-shared-backend-cljs-test
  "Sequential contract tests for the shared in-process Conduit demo backend
   (`realworld-shared.demo-backend`), which both RealWorld examples run offline
   against.

   THE PROPERTY UNDER TEST IS TEMPORAL, and it is the one the previous version of
   this suite could not see. That version called a pure URL/method-to-payload
   router ONCE per assertion and checked the envelope SHAPE, which is exactly the
   wrong instrument: every write returned a well-formed reply, every assertion
   passed, and the backend still threw the write away. Create returned a derived
   slug and inserted nothing; post-comment returned a comment while `GET
   .../comments` stayed `[]` forever; favourite echoed the flag you asked for and
   the next list read handed back the seed corpus. A successful write was erased
   by the read it triggered (rf2-9n43e).

   So every test here is a SEQUENCE. Each one walks a `world` — a state atom
   stepped by `demo/transition`, exactly as the apps' `demo/respond` steps
   theirs — writes something, and then reads it back through a normal, separate
   request. A test that passes here is a claim that the write survived the
   refetch it caused.

   Pinning `transition` pins BOTH apps. Each app wires the backend under its own
   thin fx (`:realworld.demo/http-stub` / `:realworld-resources.demo/http-stub`),
   and each of those does nothing but hand its own `defonce`d state atom to
   `demo/respond`, which calls THIS transition — so identical request sequences
   get identical replies in either architecture, and
   `identical-sequences-are-deterministic` below asserts that directly.

   The backend source is example code
   (`examples/real-apps/realworld_shared/demo_backend.cljs`), but the regression
   suite lives HERE in the adapter test tree per the test-free-examples policy
   (rf2-8cevm). Runs under the always-on `:node-test` gate; `transition` is a
   pure fn (state + args-map -> [state' reply]), so no frame or DOM is needed."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [malli.core :as m]
            [realworld-shared.demo-backend :as demo]
            [realworld-shared.schema :as ws]))

(def ^:private base "https://api.realworld.show/api")

(defn- world
  "A fresh demo world — the same starting point a launched app or a reset test
   takes."
  []
  (atom (demo/fresh-state)))

(defn- send!
  "Step `world` through one request and return the reply (`{:ok …}` or
   `{:failure …}`). `extra` merges into the request map — `:body`, `:headers`.

   This is `demo/respond` with the canned-fx tail removed: read the state, apply
   the transition, write the state back. Doing it here rather than hiding it in a
   fixture is the point — the state each assertion reads is visibly the state the
   previous request produced."
  ([world method path] (send! world method path nil))
  ([world method path extra]
   (let [[next-state reply]
         (demo/transition @world
                          {:request (merge {:method method :url (str base path)} extra)})]
     (reset! world next-state)
     reply)))

(defn- ok! [& args] (:ok (apply send! args)))
(defn- failure! [& args] (:failure (apply send! args)))

(defn- slugs [articles-response] (mapv :slug (:articles articles-response)))

(def ^:private demo-username "demo")
(def ^:private seed-author "stub-bot")

;; ============================================================================
;; THE HEADLINE — a write survives the refetch it causes
;; ============================================================================
;;
;; This is the whole bead in two sequences. The resources example's mutations
;; deliberately do NOT patch a partial collection: they invalidate and trust the
;; refetch as authoritative. That is only honest if the refetch can see the
;; write.

(deftest a-write-survives-the-refetch-it-causes
  (testing "a posted comment is in the collection the refetch returns"
    (let [w       (world)
          before  (count (:comments (ok! w :get "/articles/hello-conduit/comments")))
          written (:comment (ok! w :post "/articles/hello-conduit/comments"
                                 {:body {:comment {:body "survives the refetch"}}}))
          ;; The separate, normal read a resource invalidation would issue.
          after   (:comments (ok! w :get "/articles/hello-conduit/comments"))]
      (is (= (inc before) (count after))
          "the refetch returns one MORE comment than before the write")
      (is (some #(= written %) after)
          "and the exact saved comment — id, body, timestamps and all — is in it")))

  (testing "a favourite is still a favourite in the list read that follows it"
    (let [w (world)]
      (is (false? (:favorited (:article (ok! w :get "/articles/second-article"))))
          "not favourited to begin with")
      (ok! w :post "/articles/second-article/favorite")
      (let [detail (:article (ok! w :get "/articles/second-article"))
            listed (->> (:articles (ok! w :get "/articles?limit=100&offset=0"))
                        (some #(when (= "second-article" (:slug %)) %)))]
        (is (true? (:favorited detail))   "the detail refetch still says favourited")
        (is (= 1 (:favoritesCount detail)) "with the count the write produced")
        (is (true? (:favorited listed))   "and so does the list refetch — the one that used to revert it")))))

;; ============================================================================
;; ARTICLE CRUD — create / update / delete, each read back
;; ============================================================================

(deftest create-then-read-by-slug-list-and-author
  (let [w       (world)
        created (:article (ok! w :post "/articles"
                               {:body {:article {:title       "My New Post"
                                                 :description "d"
                                                 :body        "b"
                                                 :tagList     ["freshtag"]}}}))]
    (testing "create derives a slug and reports the acting user as the author"
      (is (= "my-new-post" (:slug created)))
      (is (= demo-username (:username (:author created)))))
    (testing "GET by the new slug returns the new article — the navigation the editor performs"
      (let [fetched (:article (ok! w :get "/articles/my-new-post"))]
        (is (= "my-new-post" (:slug fetched)))
        (is (= "My New Post" (:title fetched)))
        (is (not= "hello-conduit" (:slug fetched))
            "and emphatically not the first seed article, which is what it used to do")))
    (testing "the new article is at the top of page 1 and counted in the grand total"
      (let [page1 (ok! w :get "/articles?limit=10&offset=0")]
        (is (= "my-new-post" (first (slugs page1))) "newest first")
        (is (= (inc (count demo/seed-articles)) (:articlesCount page1)))))
    (testing "the author filter finds it, and only it"
      (let [mine (ok! w :get (str "/articles?author=" demo-username "&limit=10&offset=0"))]
        (is (= ["my-new-post"] (slugs mine)))
        (is (= 1 (:articlesCount mine)))))
    (testing "its tag joins the tag list, which is derived from the articles that exist"
      (is (some #{"freshtag"} (:tags (ok! w :get "/tags")))))
    (testing "saving the same title again yields a second article under a distinct slug"
      (let [again (:article (ok! w :post "/articles"
                                 {:body {:article {:title "My New Post" :description "d"
                                                   :body "b" :tagList []}}}))]
        (is (= "my-new-post-2" (:slug again)))
        (is (= "my-new-post" (:slug (:article (ok! w :get "/articles/my-new-post"))))
            "and the first one is still there under its own slug")))))

(deftest update-then-read
  (let [w (world)]
    (ok! w :put "/articles/hello-conduit"
         {:body {:article {:title "Edited" :description "new description"
                           :body "new body" :tagList ["edited"]}}})
    (testing "a later GET returns the edit, not the seed"
      (let [fetched (:article (ok! w :get "/articles/hello-conduit"))]
        (is (= "Edited" (:title fetched)))
        (is (= "new description" (:description fetched)))
        (is (= ["edited"] (:tagList fetched)))
        (is (= "hello-conduit" (:slug fetched)) "the slug stays put, so the URL you are on keeps working")))
    (testing "and so does the list read"
      (let [listed (->> (:articles (ok! w :get "/articles?limit=100&offset=0"))
                        (some #(when (= "hello-conduit" (:slug %)) %)))]
        (is (= "Edited" (:title listed)))))
    (testing "the tag filter follows the edit — the old tag no longer matches, the new one does"
      (is (empty? (slugs (ok! w :get "/articles?tag=intro"))))
      (is (= ["hello-conduit"] (slugs (ok! w :get "/articles?tag=edited")))))))

(deftest delete-then-read
  (let [w (world)]
    (ok! w :post "/articles/second-article/comments" {:body {:comment {:body "doomed"}}})
    (ok! w :post "/articles/second-article/favorite")
    (ok! w :delete "/articles/second-article")
    (testing "the deleted article is GONE, explicitly — never a plausible substitute"
      (let [f (failure! w :get "/articles/second-article")]
        (is (= :rf.http/http-4xx (:kind f)))
        (is (= 404 (:status (:tags f))))))
    (testing "it leaves the list, and the grand total says so"
      (let [page (ok! w :get "/articles?limit=100&offset=0")]
        (is (not (some #{"second-article"} (slugs page))))
        (is (= (dec (count demo/seed-articles)) (:articlesCount page)))))
    (testing "and everything hanging off it goes too"
      (is (= 404 (:status (:tags (failure! w :get "/articles/second-article/comments"))))
          "its comments are unreachable")
      (is (not (some #{"second-article"}
                     (slugs (ok! w :get (str "/articles?favorited=" demo-username "&limit=100")))))
          "no orphan favourite pointing at a deleted article"))))

(deftest unknown-slugs-fail-instead-of-substituting-the-first-article
  (let [w (world)]
    (testing "an unknown slug is a 404, not article #1"
      (let [reply (send! w :get "/articles/does-not-exist")]
        (is (nil? (:ok reply)) "there is no success payload at all")
        (is (= 404 (:status (:tags (:failure reply)))))
        (is (not= "hello-conduit"
                  (some-> reply :ok :article :slug))
            "the old fallback returned hello-conduit here, which is how create-then-navigate lied")))
    (testing "so are writes against one"
      (is (= 404 (:status (:tags (failure! w :put "/articles/nope" {:body {:article {:title "x"}}})))))
      (is (= 404 (:status (:tags (failure! w :delete "/articles/nope")))))
      (is (= 404 (:status (:tags (failure! w :post "/articles/nope/favorite"))))))
    (testing "and a route the demo does not implement fails loudly rather than returning {}"
      (is (= 404 (:status (:tags (failure! w :get "/nonsense"))))))))

;; ============================================================================
;; COMMENTS
;; ============================================================================

(deftest comment-post-get-delete-sequence
  (let [w    (world)
        seed (:comments (ok! w :get "/articles/hello-conduit/comments"))]
    (testing "the seeded comment is there before anything is written"
      (is (= 1 (count seed)))
      (is (= 1 (:id (first seed)))))
    (let [written (:comment (ok! w :post "/articles/hello-conduit/comments"
                                 {:body {:comment {:body "great read"}}}))]
      (testing "the saved comment carries a DETERMINISTIC id and timestamp, not a random one"
        (is (= 1000 (:id written)))
        (is (= "2026-01-01T00:00:01.000Z" (:createdAt written)))
        (is (= "great read" (:body written)))
        (is (= demo-username (:username (:author written)))))
      (testing "GET returns the exact saved comment"
        (let [fetched (:comments (ok! w :get "/articles/hello-conduit/comments"))]
          (is (= 2 (count fetched)))
          (is (some #(= written %) fetched))))
      (testing "the next comment takes the next id — the counter is state, not chance"
        (is (= 1001 (:id (:comment (ok! w :post "/articles/hello-conduit/comments"
                                        {:body {:comment {:body "and another"}}}))))))
      (testing "DELETE removes exactly that comment and leaves the others"
        (ok! w :delete "/articles/hello-conduit/comments/1000")
        (let [after (:comments (ok! w :get "/articles/hello-conduit/comments"))]
          (is (= #{1 1001} (set (map :id after))))))
      (testing "deleting it twice fails the second time"
        (is (= 404 (:status (:tags (failure! w :delete "/articles/hello-conduit/comments/1000")))))))
    (testing "comments on an article that does not exist fail explicitly"
      (is (= 404 (:status (:tags (failure! w :get "/articles/nope/comments")))))
      (is (= 404 (:status (:tags (failure! w :post "/articles/nope/comments"
                                            {:body {:comment {:body "x"}}}))))))))

;; ============================================================================
;; FAVOURITES
;; ============================================================================

(deftest favorite-persists-through-detail-lists-and-the-favorited-tab
  (let [w (world)]
    (testing "the Favorited tab starts as a strict, honest subset"
      (let [fav (ok! w :get (str "/articles?favorited=" demo-username "&limit=100&offset=0"))]
        (is (pos? (:articlesCount fav)))
        (is (< (:articlesCount fav) (count demo/seed-articles)))
        (is (some #{"hello-conduit"} (slugs fav)))))
    (testing "nobody else has favourites, so their tab is empty rather than a copy of the corpus"
      (is (= 0 (:articlesCount (ok! w :get (str "/articles?favorited=" seed-author "&limit=100"))))))
    (testing "favouriting shows up in the detail read, the global list, the tag list and the tab"
      (ok! w :post "/articles/second-article/favorite")
      (is (true? (:favorited (:article (ok! w :get "/articles/second-article")))))
      (is (= 1 (:favoritesCount (:article (ok! w :get "/articles/second-article")))))
      (let [in-list (fn [resp] (some #(when (= "second-article" (:slug %)) %) (:articles resp)))]
        (is (true? (:favorited (in-list (ok! w :get "/articles?limit=100&offset=0")))))
        (is (true? (:favorited (in-list (ok! w :get "/articles?tag=demo&limit=100&offset=0")))))
        (is (true? (:favorited (in-list (ok! w :get (str "/articles?author=" seed-author "&limit=100")))))))
      (is (some #{"second-article"}
                (slugs (ok! w :get (str "/articles?favorited=" demo-username "&limit=100&offset=0"))))))
    (testing "unfavouriting a SEEDED favourite drops it out of the tab and zeroes the count"
      (let [before (:articlesCount (ok! w :get (str "/articles?favorited=" demo-username "&limit=100")))]
        (ok! w :delete "/articles/hello-conduit/favorite")
        (let [after (ok! w :get (str "/articles?favorited=" demo-username "&limit=100"))]
          (is (= (dec before) (:articlesCount after)))
          (is (not (some #{"hello-conduit"} (slugs after)))))
        (is (false? (:favorited (:article (ok! w :get "/articles/hello-conduit")))))
        (is (= 0 (:favoritesCount (:article (ok! w :get "/articles/hello-conduit")))))))))

;; ============================================================================
;; FOLLOW / FEED / PROFILES
;; ============================================================================

(deftest follow-persists-through-profile-reads-and-drives-the-feed
  (let [w (world)]
    (testing "nothing followed, so the feed is empty — a well-formed envelope, not a special case"
      (is (= {:articles [] :articlesCount 0} (ok! w :get "/articles/feed?limit=10&offset=0")))
      (is (false? (:following (:profile (ok! w :get (str "/profiles/" seed-author)))))))
    (testing "following persists into the profile read that follows it"
      (let [written (:profile (ok! w :post (str "/profiles/" seed-author "/follow")))]
        (is (true? (:following written)))
        (is (true? (:following (:profile (ok! w :get (str "/profiles/" seed-author)))))
            "the profile refetch agrees — it used to always say false")))
    (testing "and the feed fills with that author's articles, paged like any other list"
      (let [feed (ok! w :get "/articles/feed?limit=10&offset=0")]
        (is (= 10 (count (:articles feed))))
        (is (= (count demo/seed-articles) (:articlesCount feed)))
        (is (every? #(= seed-author (:username (:author %))) (:articles feed)))))
    (testing "unfollowing empties it again"
      (ok! w :delete (str "/profiles/" seed-author "/follow"))
      (is (false? (:following (:profile (ok! w :get (str "/profiles/" seed-author))))))
      (is (= 0 (:articlesCount (ok! w :get "/articles/feed?limit=10&offset=0")))))))

;; ============================================================================
;; SETTINGS / SESSION
;; ============================================================================

(deftest settings-changes-survive-later-user-and-profile-reads
  (let [w     (world)
        token (:token (:user (ok! w :post "/users/login"
                                  {:body {:user {:email "demo@conduit.dev" :password "x"}}})))
        auth  {:headers {"Authorization" (str "Token " token)}}]
    (ok! w :put "/user" {:body {:user {:bio "Rewritten bio." :image "https://example.test/a.png"}}})
    (testing "a later GET /user returns the edit"
      (let [u (:user (ok! w :get "/user" auth))]
        (is (= "Rewritten bio." (:bio u)))
        (is (= "https://example.test/a.png" (:image u)))))
    (testing "and so does the profile read"
      (is (= "Rewritten bio." (:bio (:profile (ok! w :get (str "/profiles/" demo-username)))))))
    (testing "renaming yourself moves your byline with you"
      (ok! w :post "/articles" {:body {:article {:title "Mine" :description "d" :body "b" :tagList []}}})
      (ok! w :put "/user" {:body {:user {:username "renamed"}}})
      (is (= "renamed" (:username (:author (:article (ok! w :get "/articles/mine"))))))
      (is (= ["mine"] (slugs (ok! w :get "/articles?author=renamed&limit=10")))
          "and the author filter follows the rename"))))

(deftest session-restore-needs-a-token-this-world-issued
  (let [w (world)]
    (testing "a cold world has issued no token, so restore fails — which is why the demo opens logged out"
      (let [f (failure! w :get "/user")]
        (is (= :rf.http/decode-failure (:kind f))
            "and it fails down the DECODE path, reproducing what a real refusal does to the auth machine")
        (is (true? (:schema-validation-failure? (:tags f))))))
    (let [token (:token (:user (ok! w :post "/users/login"
                                    {:body {:user {:email "demo@conduit.dev" :password "x"}}})))]
      (testing "after a login, that token restores the session"
        (is (= demo-username
               (:username (:user (ok! w :get "/user"
                                      {:headers {"Authorization" (str "Token " token)}}))))))
      (testing "an invalid credential does not"
        (is (some? (failure! w :get "/user" {:headers {"Authorization" "Token not-the-one"}})))
        (is (some? (failure! w :get "/user" {:headers {"Authorization" ""}})))
        (is (some? (failure! w :get "/user")))))))

;; ============================================================================
;; QUERY SEMANTICS — applied to CURRENT state, not the seed
;; ============================================================================

(deftest list-queries-are-applied-to-current-state
  (let [w (world)]
    (testing "limit/offset carve genuinely different slices and articlesCount is the grand total"
      (let [p1 (ok! w :get "/articles?limit=10&offset=0")
            p2 (ok! w :get "/articles?limit=10&offset=10")]
        (is (= 10 (count (:articles p1))))
        (is (= (count demo/seed-articles) (:articlesCount p1)))
        (is (empty? (filter (set (slugs p1)) (slugs p2)))
            "page 2 shares nothing with page 1")))
    (testing "articlesCount describes the FILTERED set, not the corpus and not the page size"
      (let [tagged (ok! w :get "/articles?tag=intro&limit=10&offset=0")]
        (is (= 1 (:articlesCount tagged)))
        (is (= ["hello-conduit"] (slugs tagged)))))
    (testing "a filter reads current state — create an article and the filtered count moves"
      (ok! w :post "/articles" {:body {:article {:title "Tagged Later" :description "d"
                                                 :body "b" :tagList ["intro"]}}})
      (is (= 2 (:articlesCount (ok! w :get "/articles?tag=intro&limit=10&offset=0")))))
    (testing "query values are URL-decoded, so a reserved character in a tag round-trips"
      (ok! w :post "/articles" {:body {:article {:title "Odd Tag" :description "d"
                                                 :body "b" :tagList ["a&b c"]}}})
      (is (= ["odd-tag"] (slugs (ok! w :get "/articles?tag=a%26b%20c&limit=10")))))))

;; ============================================================================
;; DETERMINISM AND ISOLATION
;; ============================================================================

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
        [replies-a state-a] (run)
        [replies-b state-b] (run)]
    (testing "the same sequence against a fresh world produces the same replies AND the same state"
      (is (= replies-a replies-b))
      (is (= state-a state-b)))
    (testing "which is what makes both app seams equivalent — each is this transition plus an atom"
      (is (= (count sequence) (count replies-a)))
      (is (every? #(contains? % :ok) replies-a) "and the whole sequence succeeds"))))

(deftest worlds-are-isolated-from-each-other
  (let [a (world)
        b (world)]
    (ok! a :post "/articles/second-article/favorite")
    (ok! a :post "/articles/hello-conduit/comments" {:body {:comment {:body "only in a"}}})
    (testing "a write in one world is invisible in another — two demos never share a state"
      (is (false? (:favorited (:article (ok! b :get "/articles/second-article")))))
      (is (= 1 (count (:comments (ok! b :get "/articles/hello-conduit/comments"))))))))

;; ============================================================================
;; NEGATIVE CONTROL
;; ============================================================================
;;
;; This is the test that fails if somebody quietly reverts the point of the
;; change — either by discarding the transition's next-state (writes stop
;; landing) or by having reads consult the seed constants again (writes land
;; nowhere anybody can see). Both of those are exactly what the old backend did,
;; and both are invisible to a shape assertion.

(deftest negative-control-the-seed-is-a-starting-value-not-a-store
  (let [seed-snapshot (vec demo/seed-articles)
        w             (world)
        fresh         (world)]
    (ok! w :post "/articles" {:body {:article {:title "Control" :description "d"
                                               :body "b" :tagList ["control"]}}})
    (ok! w :post "/articles/second-article/favorite")
    (ok! w :post "/articles/hello-conduit/comments" {:body {:comment {:body "control"}}})
    (testing "the seed vector itself is untouched by any write"
      (is (= seed-snapshot demo/seed-articles)))
    (testing "and every read of a written thing now DIFFERS from the same read against a fresh world"
      (is (not= (ok! fresh :get "/articles?limit=10&offset=0")
                (ok! w     :get "/articles?limit=10&offset=0"))
          "the list changed")
      (is (not= (ok! fresh :get "/articles/second-article")
                (ok! w     :get "/articles/second-article"))
          "the favourited article changed")
      (is (not= (ok! fresh :get "/articles/hello-conduit/comments")
                (ok! w     :get "/articles/hello-conduit/comments"))
          "the comment collection changed"))
    (testing "while a read of something nobody touched is identical in both worlds"
      (is (= (ok! fresh :get "/articles/article-4")
             (ok! w     :get "/articles/article-4"))
          "so the difference above is the WRITE, not ambient nondeterminism"))))

;; ============================================================================
;; WIRE CONFORMANCE — the replies are still Conduit-shaped
;; ============================================================================
;;
;; The canned-success fx never runs `:decode`, so nothing at runtime would catch
;; a reply that drifted off the wire contract. Validate the envelopes here
;; against the same shared Malli schemas both apps pass as their `:decode`, so
;; the offline backend cannot quietly diverge from the API it is standing in for.

(deftest replies-validate-against-the-shared-wire-schemas
  (let [w     (world)
        token (:token (:user (ok! w :post "/users/login" {:body {:user {:email "e" :password "p"}}})))
        auth  {:headers {"Authorization" (str "Token " token)}}]
    (is (m/validate ws/UserResponse     (ok! w :get "/user" auth))                         "GET /user")
    (is (m/validate ws/UserResponse     (ok! w :put "/user" {:body {:user {:bio "b"}}}))    "PUT /user")
    (is (m/validate ws/ArticleResponse  (ok! w :post "/articles"
                                             {:body {:article {:title "Wire Check" :description "d"
                                                               :body "b" :tagList ["w"]}}})) "POST /articles")
    (is (m/validate ws/ArticleResponse  (ok! w :get "/articles/wire-check"))                "GET /articles/:slug")
    (is (m/validate ws/ArticleResponse  (ok! w :post "/articles/wire-check/favorite"))      "POST favorite")
    (is (m/validate ws/ArticlesResponse (ok! w :get "/articles?limit=10&offset=0"))         "GET /articles")
    (is (m/validate ws/ArticlesResponse (ok! w :get "/articles/feed?limit=10&offset=0"))    "GET /articles/feed")
    (is (m/validate ws/CommentResponse  (ok! w :post "/articles/wire-check/comments"
                                             {:body {:comment {:body "c"}}}))               "POST comment")
    (is (m/validate ws/CommentsResponse (ok! w :get "/articles/wire-check/comments"))       "GET comments")
    (is (m/validate ws/ProfileResponse  (ok! w :get "/profiles/stub-bot"))                  "GET /profiles/:username")
    (is (m/validate ws/ProfileResponse  (ok! w :post "/profiles/stub-bot/follow"))          "POST follow")
    (is (m/validate ws/TagsResponse     (ok! w :get "/tags"))                               "GET /tags")))
