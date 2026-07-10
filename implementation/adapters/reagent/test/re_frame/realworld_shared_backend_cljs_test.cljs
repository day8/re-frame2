(ns re-frame.realworld-shared-backend-cljs-test
  "Contract tests for the shared in-process Conduit demo backend
   (`realworld-shared.demo-backend` — rf2-626z91), which replaced the two
   drifting per-app fake backends both RealWorld examples used to hand-maintain.

   The two copies had materially DRIFTED — the managed-HTTP one had a wrong slug
   lookup (every article detail returned the FIRST article), invalid empty / list
   write envelopes for create / update / favorite / settings, an always-false
   follow, a favorited tab that duplicated My Articles, and a non-empty feed —
   while the resources copy was the correct, complete one. This suite pins the
   ONE canonical router (`payload-for-request`) both apps now route through, so
   the drift can't come back. Because both apps wire the backend via a thin fx
   (`:realworld.demo/http-stub` / `:realworld-resources.demo/http-stub`) that
   just delegates to `demo/respond` — which routes through this table — pinning
   the table here pins BOTH effect ids: identical requests get identical replies.

   The backend source is example code
   (`examples/real-apps/realworld_shared/demo_backend.cljs`), but the regression
   suite lives HERE in the adapter test tree per the test-free-examples policy
   (rf2-8cevm). Runs under the always-on `:node-test` gate; `payload-for-request`
   is a pure fn (URL/method → payload), so no frame or DOM is needed."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [realworld-shared.demo-backend :as demo]))

(def ^:private base "https://api.realworld.show/api")

(defn- payload
  ([method path] (payload method path nil))
  ([method path body]
   (demo/payload-for-request {:request (cond-> {:method method :url (str base path)}
                                         body (assoc :body body))})))

;; ============================================================================
;; AUTH / RESTORE / SETTINGS
;; ============================================================================

(deftest auth-restore-and-settings
  ;; The demo user is private to the backend ns, so assert on the envelope SHAPE
  ;; rather than the exact identity.
  (testing "login / register / settings PUT return a {:user …} envelope"
    (is (contains? (payload :post "/users/login") :user) "POST /users/login → {:user …}")
    (is (contains? (payload :post "/users") :user)       "POST /users (register) → {:user …}")
    (is (contains? (payload :put "/user") :user)         "PUT /user (settings) → {:user …}, not an empty envelope"))
  (testing "GET /user (session restore) is the one deliberately-failing route"
    (is (= :realworld-shared.demo-backend/decode-failure (payload :get "/user"))
        "GET /user routes to the ::decode-failure sentinel so the demo never auto-restores a session")))

;; ============================================================================
;; ARTICLE DETAIL — the wrong-slug-lookup drift fix
;; ============================================================================

(deftest article-detail-resolves-the-requested-slug
  (testing "a NON-FIRST slug resolves to that article (not the first) — the headline drift fix"
    (let [{:keys [article]} (payload :get "/articles/second-article")]
      (is (= "second-article" (:slug article))
          "GET /articles/second-article returns the second article, not the first"))
    (let [{:keys [article]} (payload :get "/articles/article-5")]
      (is (= "article-5" (:slug article))
          "GET /articles/article-5 returns article-5"))
    (is (not= (:slug (:article (payload :get "/articles/second-article")))
              (:slug (:article (payload :get "/articles/hello-conduit"))))
        "two different slugs return two different articles (the old copy always returned the first)"))
  (testing "an unknown slug falls back to the first article (a well-formed reply, never a crash)"
    (is (some? (:article (payload :get "/articles/does-not-exist"))))))

;; ============================================================================
;; WRITE ENVELOPES — create / update / delete return VALID shapes
;; ============================================================================

(deftest create-update-delete-return-valid-envelopes
  (testing "POST /articles (create) echoes a full {:article …} built from the body, with a derived slug"
    (let [{:keys [article]} (payload :post "/articles"
                                     {:article {:title "My New Post" :description "d"
                                                :body "b" :tagList ["x"]}})]
      (is (some? article) "create returns an {:article …} envelope, not a list or empty map")
      (is (= "my-new-post" (:slug article)) "the slug is derived from the title")
      (is (= "My New Post" (:title article)) "the submitted title is echoed back")))
  (testing "PUT /articles/:slug (update) echoes the updated article keyed by the URL slug"
    (let [{:keys [article]} (payload :put "/articles/hello-conduit"
                                     {:article {:title "Edited" :description "d"
                                                :body "b" :tagList []}})]
      (is (= "hello-conduit" (:slug article)) "the slug comes from the URL, not the first article")
      (is (= "Edited" (:title article)) "the submitted title is merged in")))
  (testing "DELETE /articles/:slug returns an empty body (not a stray article)"
    (is (= {} (payload :delete "/articles/hello-conduit")))))

;; ============================================================================
;; FAVORITE / FOLLOW — reflect the requested state
;; ============================================================================

(deftest favorite-and-follow-reflect-the-method
  (testing "POST/DELETE favorite echo the resulting favorited flag + count"
    (let [{:keys [article]} (payload :post "/articles/hello-conduit/favorite")]
      (is (true? (:favorited article)) "POST favorite → favorited true")
      (is (= 1 (:favoritesCount article))))
    (let [{:keys [article]} (payload :delete "/articles/hello-conduit/favorite")]
      (is (false? (:favorited article)) "DELETE favorite → favorited false")
      (is (= 0 (:favoritesCount article)))))
  (testing "POST/DELETE follow reflect the requested username + following flag"
    (let [{:keys [profile]} (payload :post "/profiles/eve/follow")]
      (is (= "eve" (:username profile)) "the profile carries the requested username, not a stub")
      (is (true? (:following profile)) "POST follow → following true"))
    (let [{:keys [profile]} (payload :delete "/profiles/eve/follow")]
      (is (false? (:following profile)) "DELETE follow → following false"))))

;; ============================================================================
;; COMMENTS
;; ============================================================================

(deftest comments-routes
  (testing "GET comments is an empty list; POST returns the saved comment; DELETE is empty"
    (is (= {:comments []} (payload :get "/articles/hello-conduit/comments")))
    (let [{:keys [comment]} (payload :post "/articles/hello-conduit/comments"
                                     {:comment {:body "great read"}})]
      (is (= "great read" (:body comment)) "the posted body is echoed on the saved comment")
      (is (int? (:id comment)) "the saved comment carries an id"))
    (is (= {} (payload :delete "/articles/hello-conduit/comments/42")))))

;; ============================================================================
;; LISTS / PAGINATION / FEED / TAGS
;; ============================================================================

(deftest list-pagination-feed-and-tags
  (testing "the global list pages by limit/offset and reports the grand total"
    (let [p1 (payload :get "/articles?limit=10&offset=0")
          p2 (payload :get "/articles?limit=10&offset=10")]
      (is (= 10 (count (:articles p1))) "page 1 is a full page of 10")
      (is (= (count demo/articles) (:articlesCount p1))
          "articlesCount is the grand total, not the page size")
      (is (not= (:slug (first (:articles p1))) (:slug (first (:articles p2))))
          "page 2 is a genuinely different slice")))
  (testing "the feed is empty (no followed authors) but a well-formed envelope"
    (is (= {:articles [] :articlesCount 0} (payload :get "/articles/feed"))))
  (testing "the favorited tab is a DISTINCT subset (not a copy of the full list)"
    (let [fav (payload :get "/articles?favorited=alice&limit=10&offset=0")]
      (is (pos? (:articlesCount fav)) "the favorited subset is non-empty")
      (is (< (:articlesCount fav) (count demo/articles))
          "the favorited count is a strict subset of the full corpus (the tab differs from My Articles)")))
  (testing "tags"
    (is (vector? (:tags (payload :get "/tags"))) "GET /tags → {:tags […]}")
    (is (seq (:tags (payload :get "/tags"))))))
