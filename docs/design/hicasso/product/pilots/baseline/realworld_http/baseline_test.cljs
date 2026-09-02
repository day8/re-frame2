(ns realworld-http.baseline-test
  "Behavioural baseline for the two screens under migration — the feed and
   the article editor — plus the routing both depend on.

   Every test spins its own frame, points the app's managed HTTP at a canned
   reply, drives a feature through its events, and asserts on app-db and on
   the subscriptions the views read. Nothing here renders: these tests pin
   what the screens DO, so a view-layer migration that preserves behaviour
   keeps them green untouched, and one that changes behaviour turns them red.

   Run with `npm test` from the app directory. The suite compiles to Node and
   needs no browser."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.http.test-support]
            ;; The app's production source. Requiring `realworld-http.core`
            ;; chains in every feature namespace, so the handlers, subs, views,
            ;; routes and machines register at load and the tests drive them.
            [realworld-http.core]
            [realworld-http.routing]
            ;; Pure helpers the tests call directly.
            [realworld-http.http :as rh]
            [realworld-http.article-editor :as editor]
            [realworld-http.tags :as tags])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

;; ============================================================================
;; Canned replies
;; ============================================================================
;;
;; The framework ships two canned-reply effects for managed HTTP,
;; `:rf.http/managed-canned-success` and `:rf.http/managed-canned-failure`,
;; which synthesise a reply in the transport's own shape. Each test registers
;; a small effect that delegates to one of them with the payload the test
;; wants, and points the app at it through the frame's `:fx-overrides`.

(defn- reg-canned-success!
  "Register `fx-id` to answer every managed request with `value`."
  [fx-id value]
  (rf/reg-fx fx-id
    {:platforms #{:client :server}}
    (fn [frame-ctx args]
      (let [stub (registrar/handler :fx :rf.http/managed-canned-success)]
        (stub frame-ctx (assoc args :value value))))))

(defn- reg-canned-success-by-url!
  "Register `fx-id` to answer each managed request with `(f method url)`
   (or `(f url)` when `f` takes one argument)."
  [fx-id f]
  (rf/reg-fx fx-id
    {:platforms #{:client :server}}
    (fn [frame-ctx args]
      (let [stub   (registrar/handler :fx :rf.http/managed-canned-success)
            req    (:request args)
            method (or (:method req) :get)
            url    (:url req)
            arity  (try
                     (.-length f)
                     (catch :default _ 1))
            value  (if (>= arity 2)
                     (f method url)
                     (f url))]
        (stub frame-ctx (assoc args :value value))))))

(defn- reg-canned-failure!
  "Register `fx-id` to fail every managed request with `kind` and `tags`."
  [fx-id kind tags]
  (rf/reg-fx fx-id
    {:platforms #{:client :server}}
    (fn [frame-ctx args]
      (let [stub (registrar/handler :fx :rf.http/managed-canned-failure)]
        (stub frame-ctx (assoc args :kind kind :tags tags))))))

;; A generic success: every managed request resolves with an empty map. Tests
;; that only care about routing or client-side state use this one.
(reg-canned-success! :realworld.test/canned-success-empty {})

;; Each test creates its own top-level frame, so opt out of the ambient
;; `:rf/default` scope: a frame's `:initial-events` then drain synchronously
;; at boot, and every dispatch in a test body names its frame explicitly.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       reagent-adapter/adapter
     :ambient-frame nil}))

(defn- sub
  "Compute `query` against frame `f`'s current state."
  [f query]
  (rf/compute-sub query (rf/frame-state-value f)))

;; ============================================================================
;; The feed
;; ============================================================================

(defn- articles-load-test []
  (reg-canned-success! :realworld.test/canned-articles
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
  (with-new-frame [f (frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/canned-articles}})]
    (is (= :idle (:status (sub f [:articles/slice]))))
    (rf/dispatch-sync [:articles/load] {:frame f})
    (let [slice (sub f [:articles/slice])]
      (is (= :loaded (:status slice)))
      (is (= 1 (count (:data slice))))
      (is (= "hello-world" (-> slice :data first :slug))))
    (rf/dispatch-sync [:articles/load] {:frame f})
    (let [slice (sub f [:articles/slice])]
      (is (= :loaded (:status slice)))
      (is (= 2 (:attempt slice))))))

(defn- articles-load-failure-test []
  (reg-canned-failure! :realworld.test/canned-articles-failure
                       :rf.http/http-5xx
                       {:status 500
                        :body   "server error"})
  (with-new-frame [f (frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/canned-articles-failure}})]
    (rf/dispatch-sync [:articles/load] {:frame f})
    (is (= :error (:status (sub f [:articles/slice]))))
    (is (some? (sub f [:articles/error])))))

(defn- feed-load-test []
  ;; :feed/load → :feed/loaded populates the user-feed slice and the grand count.
  (reg-canned-success! :realworld.test/feed-ok
    {:articles [{:slug "f1" :title "Feed one" :description "d" :body "b" :tagList []
                 :createdAt "x" :updatedAt "x" :favorited false :favoritesCount 0
                 :author {:username "bob" :bio nil :image nil :following true}}]
     :articlesCount 7})
  (with-new-frame [f (frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/feed-ok}})]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (rf/dispatch-sync [:feed/load] {:frame f})
    (is (= 1 (count (sub f [:feed/data])))
        ":feed/loaded populates the user-feed slice")
    (is (= 7 (sub f [:feed/count]))
        "the grand articles-count is stored for pagination")
    (is (not (sub f [:feed/loading?]))
        "a settled feed load is no longer loading")))

(defn- feed-load-failure-test []
  (reg-canned-failure! :realworld.test/feed-fail :rf.http/http-5xx {:status 500 :body "boom"})
  (with-new-frame [f (frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/feed-fail}})]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (rf/dispatch-sync [:feed/load] {:frame f})
    (is (some? (sub f [:feed/error]))
        ":feed/load-failed surfaces a readable error on the feed slice")))

(defn- tag-query-test []
  (with-new-frame [f (frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    ;; Applying a tag navigates to the `/tag/:tag` PATH route, so the active
    ;; tag is a route param (read via `:home/selected-tag`), not a `?tag=` query.
    (rf/dispatch-sync [:tags/apply-filter "clojure"] {:frame f})
    (is (= :realworld/home-tag (sub f [:rf.route/id])))
    (is (= "clojure" (sub f [:home/selected-tag])))
    ;; The following feed uses the `?feed=following` token.
    (rf/dispatch-sync [:home/show-your-feed] {:frame f})
    (is (= "following" (:feed (sub f [:rf.route/query]))))))

(defn- home-context-test []
  ;; tags/home-context flattens the two home routes into one {:tag :feed :page}.
  (let [rt {:rf.runtime/routing {:current {:params {:tag "clojure"}
                                           :query  {:feed "following" :page 2}}}}]
    (is (= {:tag "clojure" :feed "following" :page 2} (tags/home-context rt))
        "the tag (path param) + feed/page (query) flatten into one context map"))
  (let [rt {:rf.runtime/routing {:current {}}}]
    (is (= {:tag nil :feed nil :page nil} (tags/home-context rt))
        "an empty route yields an all-nil context (no NPE)")))

(defn- paginate-path-integration-test []
  ;; `paginate-path` prepends the path, encodes the filter, and appends the
  ;; shared limit/offset window. Multi-key order isn't guaranteed, so assert
  ;; on the (order-independent) parts.
  (let [p (rh/paginate-path "/articles" nil 1)]
    (is (str/starts-with? p "/articles?"))
    (is (str/includes? p "limit=10"))
    (is (str/includes? p "offset=0")))
  (let [p (rh/paginate-path "/articles" {:tag "clojure"} 3)]
    (is (str/includes? p "tag=clojure") "the filter rides the query")
    (is (str/includes? p "limit=10"))
    (is (str/includes? p "offset=20") "page 3 → offset 20")))

(defn- pagination-nav-events-test []
  (with-new-frame [f (frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    ;; --- :home/show-page carries the active feed forward ---
    ;; Global feed, then page 3: the home route, no ?feed=, ?page=3.
    (rf/dispatch-sync [:home/show-global-feed] {:frame f})
    (rf/dispatch-sync [:home/show-page 3] {:frame f})
    (is (= :realworld/home (sub f [:rf.route/id])))
    (is (= 3 (:page (sub f [:rf.route/query])))
        "global feed page-nav sets ?page= on the home route")
    (is (nil? (:feed (sub f [:rf.route/query])))
        "no feed was active, so ?feed= stays absent")

    ;; Following feed, then page 2: the following token is carried forward.
    (rf/dispatch-sync [:home/show-your-feed] {:frame f})
    (rf/dispatch-sync [:home/show-page 2] {:frame f})
    (is (= :realworld/home (sub f [:rf.route/id])))
    (is (= 2 (:page (sub f [:rf.route/query]))))
    (is (= "following" (:feed (sub f [:rf.route/query])))
        "paging the following feed carries ?feed=following forward")

    ;; --- :home/show-page carries the active tag forward (re-aims at /tag/:tag) ---
    (rf/dispatch-sync [:tags/apply-filter "clojure"] {:frame f})
    (rf/dispatch-sync [:home/show-page 2] {:frame f})
    (is (= :realworld/home-tag (sub f [:rf.route/id]))
        "paging a tag-filtered list re-aims at the /tag/:tag PATH route")
    (is (= "clojure" (:tag (sub f [:rf.route/params])))
        "the tag param is preserved so paging stays inside the tag")
    (is (= 2 (:page (sub f [:rf.route/query]))))

    ;; --- :profile/show-page stays on the same tab + username, swaps only ?page= ---
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.profile/show :params {:username "eve"}}] {:frame f})
    (rf/dispatch-sync [:profile/show-page 2] {:frame f})
    (is (= :realworld.profile/show (sub f [:rf.route/id]))
        "profile page-nav stays on the same (authored) tab")
    (is (= "eve" (:username (sub f [:rf.route/params])))
        "the profile username is unchanged")
    (is (= 2 (:page (sub f [:rf.route/query]))))

    ;; The favorites tab pages independently, still on its own route.
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.profile/favorites :params {:username "eve"}}] {:frame f})
    (rf/dispatch-sync [:profile/show-page 3] {:frame f})
    (is (= :realworld.profile/favorites (sub f [:rf.route/id]))
        "the favorites tab stays on the favorites route when paging")
    (is (= 3 (:page (sub f [:rf.route/query]))))))

(deftest feed
  (testing "global feed loads and re-loads bumping :attempt"
    (articles-load-test))
  (testing "global feed surfaces :error on http failure"
    (articles-load-failure-test))
  (testing "user feed :feed/load / :feed/loaded populate the slice"
    (feed-load-test))
  (testing "user feed :feed/load-failed surfaces an error"
    (feed-load-failure-test))
  (testing "tag filter and feed-kind round-trip via the route"
    (tag-query-test))
  (testing "home-context flattens the two home routes into {:tag :feed :page}"
    (home-context-test))
  (testing "paginate-path threads the page arithmetic + query encoding"
    (paginate-path-integration-test))
  (testing "page-nav events carry the active feed / tag / route forward"
    (pagination-nav-events-test)))

;; ============================================================================
;; The article editor
;; ============================================================================

(defn- ed-has-tag? [f tag]
  (sub f [:rf.machine/has-tag? :ui/article-editor tag]))

(defn- editor-create-test []
  (reg-canned-success! :realworld.test/canned-editor-save
                       {:article {:slug "hello-world"
                                  :title "Hello"
                                  :description "Short"
                                  :body "Body"
                                  :tagList ["demo"]
                                  :createdAt "2026-05-01"
                                  :updatedAt "2026-05-01"
                                  :favorited false
                                  :favoritesCount 0
                                  :author {:username "alice" :bio nil :image nil :following false}}})
  (with-new-frame [f (frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/canned-editor-save}})]
    (rf/dispatch-sync [:editor/initialise] {:frame f})
    ;; The :mode region starts at :create; the :lifecycle region starts at :idle.
    (is (true? (ed-has-tag? f :mode/create)))
    (is (true? (ed-has-tag? f :lifecycle/idle)))
    ;; The :editor/can-submit? flow starts false — the draft is blank (invalid)
    ;; and unchanged.
    (is (false? (sub f [:editor/can-submit?])))
    (rf/dispatch-sync [:editor/edit-field :title "Hello"] {:frame f})
    (rf/dispatch-sync [:editor/edit-field :description "Short"] {:frame f})
    (rf/dispatch-sync [:editor/edit-field :body "Body"] {:frame f})
    ;; Now valid AND dirty → the flow materialised true at [:editor :can-submit?].
    (is (true? (sub f [:editor/can-submit?])))
    (rf/dispatch-sync [:editor/submit] {:frame f})
    ;; A successful submit advances :mode → :edit and :lifecycle → :saved.
    (is (true? (ed-has-tag? f :lifecycle/saved)))
    (is (true? (ed-has-tag? f :mode/edit)))
    (is (false? (sub f [:editor/dirty?])))))

(defn- editor-can-leave-test []
  (with-new-frame [f (frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    (rf/dispatch-sync [:editor/initialise] {:frame f})
    (is (true? (sub f [:editor/can-leave?])))
    (rf/dispatch-sync [:editor/edit-field :title "Changed"] {:frame f})
    (is (false? (sub f [:editor/can-leave?])))))

(defn- editor-pure-helpers-test []
  ;; validate-draft — one message per blank required field; empty map when valid.
  (is (= {} (editor/validate-draft {:title "T" :description "D" :body "B"}))
      "a fully-filled draft validates clean (no error map)")
  (is (= #{:title :description :body}
         (set (keys (editor/validate-draft {:title "" :description "" :body ""}))))
      "every blank required field earns its own error")
  (is (= #{:description}
         (set (keys (editor/validate-draft {:title "T" :description "   " :body "B"}))))
      "a whitespace-only field counts as blank (str/blank?)")
  ;; parse-tag-list — split on comma, trim each, drop blanks, vector out.
  (is (= ["a" "b" "c"] (editor/parse-tag-list "a, b ,c"))
      "tags split on comma, each trimmed")
  (is (= [] (editor/parse-tag-list "")) "empty string → no tags")
  (is (= [] (editor/parse-tag-list nil)) "nil → no tags (no NPE on the split)")
  (is (= ["x"] (editor/parse-tag-list " , x , ,")) "blank entries between commas are dropped")
  ;; draft-from-article — joins the tagList vector back into the comma string
  ;; the form edits.
  (is (= {:title "T" :description "D" :body "B" :tagList "a, b"}
         (editor/draft-from-article {:title "T" :description "D" :body "B" :tagList ["a" "b"]}))
      "an article decodes into the editable draft shape (tagList joined)")
  ;; article-body — wraps the draft into the {:article …} request body, parsing tags.
  (is (= {:article {:title "T" :description "D" :body "B" :tagList ["a" "b"]}}
         (editor/article-body {:title "T" :description "D" :body "B" :tagList "a, b"}))
      "the request body parses the tag string back into a vector"))

(defn- editor-edit-load-and-put-test []
  (let [seen (atom [])]
    (reg-canned-success-by-url! :realworld.test/editor-edit
      (fn [method url]
        (swap! seen conj [method url])
        {:article {:slug "hello-world" :title "Hello, world" :description "Intro"
                   :body "Body text" :tagList ["intro" "demo"]
                   :createdAt "2026-05-01" :updatedAt "2026-05-01"
                   :favorited false :favoritesCount 0
                   :author {:username "alice" :bio nil :image nil :following false}}}))
    (with-new-frame [f (frame/make-anon-frame-record!
                         {:initial-events [[:app/initialise]]
                          :fx-overrides {:rf.http/managed :realworld.test/editor-edit}})]
      ;; /editor/:slug requires auth — sign in so the route's guard passes and
      ;; its :on-match [:editor/load-article] fires (rather than a login redirect).
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/hello-world"] {:frame f})
      ;; :editor/load-article → :use-edit + :fetch-started + GET /articles/hello-world;
      ;; the canned reply lands → :editor/loaded seeds the draft, :fetch-succeeded.
      (is (true? (ed-has-tag? f :mode/edit)) "edit-mode entry flips the :mode region to :edit")
      (is (true? (ed-has-tag? f :editor/can-delete)) ":mode/edit lights the :editor/can-delete tag")
      (is (true? (ed-has-tag? f :lifecycle/idle)) "a settled load leaves the lifecycle region at :idle")
      (let [slice (sub f [:editor/slice])
            draft (sub f [:editor/draft])]
        (is (= "hello-world" (:slug slice)) "the slug is captured for the PUT target")
        (is (= "Hello, world" (:title draft)) "the draft is seeded from the loaded article")
        (is (= "intro, demo" (:tagList draft)) "the tag list is joined into the comma-separated string"))
      (is (false? (sub f [:editor/dirty?]))
          "a freshly-seeded edit draft equals its baseline → not dirty")
      (is (true? (sub f [:editor/can-leave?]))
          "a clean edit draft may leave freely")
      ;; Edit a field → dirty + valid → the flow enables submit → PUT (not POST).
      (reset! seen [])
      (rf/dispatch-sync [:editor/edit-field :title "Hello, edited"] {:frame f})
      (is (true? (sub f [:editor/can-submit?]))
          "an edited, valid draft can submit")
      (rf/dispatch-sync [:editor/submit] {:frame f})
      (is (some (fn [[m u]] (and (= :put m) (str/ends-with? u "/articles/hello-world"))) @seen)
          "edit-mode submit issues a PUT to /articles/:slug (not a POST to /articles)")
      ;; :editor/submit-success → :submit-succeeded (:saved) + :use-edit + navigate.
      (is (true? (ed-has-tag? f :lifecycle/saved)) "a successful save advances the lifecycle → :saved")
      (is (true? (ed-has-tag? f :mode/edit)) "the editor stays in :edit mode after saving"))))

(defn- editor-load-failure-test []
  (reg-canned-failure! :realworld.test/editor-load-fail
                       :rf.http/http-5xx {:status 500 :body "server error"})
  (with-new-frame [f (frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/editor-load-fail}})]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (rf/dispatch-sync [:rf.route/handle-url-change "/editor/doomed"] {:frame f})
    ;; :editor/load-article → GET fails → :editor/load-failed → :fetch-failed.
    (is (true? (ed-has-tag? f :lifecycle/error))
        "a load failure lands the lifecycle region in :error (the page-level error gate)")
    (is (some? (sub f [:editor/submit-error]))
        "the load-failure message is surfaced via :submit-error")))

(defn- editor-delete-test []
  (let [seen (atom [])]
    (reg-canned-success-by-url! :realworld.test/editor-delete
      (fn [method url]
        (swap! seen conj [method url])
        {:article {:slug "doomed" :title "Doomed" :description "d" :body "b" :tagList []
                   :createdAt "x" :updatedAt "x" :favorited false :favoritesCount 0
                   :author {:username "alice" :bio nil :image nil :following false}}}))
    (with-new-frame [f (frame/make-anon-frame-record!
                         {:initial-events [[:app/initialise]]
                          :fx-overrides {:rf.http/managed :realworld.test/editor-delete}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/doomed"] {:frame f})
      (is (= "doomed" (:slug (sub f [:editor/slice])))
          "the article is loaded into edit mode")
      (reset! seen [])
      (rf/dispatch-sync [:editor/delete] {:frame f})
      ;; :editor/delete → DELETE /articles/doomed → :editor/delete-success → reset + home.
      (is (some (fn [[m u]] (and (= :delete m) (str/ends-with? u "/articles/doomed"))) @seen)
          ":editor/delete issues a DELETE to /articles/:slug")
      (is (= :realworld/home (sub f [:rf.route/id]))
          "a successful delete navigates home")
      (is (nil? (:slug (sub f [:editor/slice])))
          "the editor slice is reset to a blank create draft on delete")
      (is (true? (ed-has-tag? f :mode/create))
          "the :mode region resets to :create after a delete"))))

(defn- editor-invalid-submit-test []
  (with-new-frame [f (frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    (rf/dispatch-sync [:editor/initialise] {:frame f})
    ;; A create draft with only a title → description + body still blank → the
    ;; client-invalid branch fires (per-field errors + :_form, no round trip).
    (rf/dispatch-sync [:editor/edit-field :title "Only a title"] {:frame f})
    (rf/dispatch-sync [:editor/submit] {:frame f})
    (let [errors (sub f [:editor/errors])]
      (is (contains? errors :description) "the blank description earns a per-field error")
      (is (contains? errors :body) "the blank body earns a per-field error")
      (is (not (contains? errors :title)) "the filled title has no error")
      (is (= "Please fix the highlighted fields." (:_form errors))
          "the whole-form prompt is set under :_form"))
    (is (some? (sub f [:editor/field-error :description]))
        "submit-attempted? makes a per-field error visible even on an untouched field")
    (is (true? (ed-has-tag? f :lifecycle/idle))
        "an invalid submit issues no request — lifecycle stays :idle (no :submit-started)")
    (is (nil? (sub f [:editor/submit-error]))
        "the client-validation branch clears :submit-error (that door is for transport failures)")))

(defn- editor-same-slug-seed-preserves-typing-test []
  ;; A stub that CAPTURES the request and never replies, so the GET stays in
  ;; flight for as long as this test wants it to. Every other stub here settles
  ;; on the spot, which is precisely the window this test needs to open.
  (let [in-flight (atom nil)]
    (rf/reg-fx :realworld.test/editor-in-flight
      {:platforms #{:client :server}}
      (fn [_frame-ctx args] (reset! in-flight args) nil))
    (with-new-frame [f (frame/make-anon-frame-record!
                         {:initial-events [[:app/initialise]]
                          :fx-overrides {:rf.http/managed :realworld.test/editor-in-flight}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/hello-world"] {:frame f})
      (let [req (deref in-flight)]
        (is (some? req) "entering the edit route lowered the article GET")
        (is (true? (ed-has-tag? f :lifecycle/loading))
            "the lifecycle region sits at :loading while the fetch is out")
        ;; TYPE, while the fetch is still out — the ordinary case, not a race:
        ;; the round trip is slower than the first keystroke.
        (rf/dispatch-sync [:editor/edit-field :title "My unsaved heading"] {:frame f})
        (is (= #{:title} (:touched (sub f [:editor/slice])))
            "typing marked :title touched and nothing else")
        ;; THE SETTLE. Replay the captured `:on-success` with the transport's
        ;; success result appended, exactly as managed HTTP delivers it.
        (rf/dispatch-sync (conj (:on-success req)
                                {:status :ok
                                 :value {:article {:slug "hello-world" :title "Hello, world"
                                                   :description "Intro" :body "Body text"
                                                   :tagList ["intro" "demo"]}}})
                          {:frame f})
        (let [slice (sub f [:editor/slice])
              draft (sub f [:editor/draft])]
          (is (= "My unsaved heading" (:title draft))
              "the touched field keeps the user's text — the settle must not clobber typing")
          (is (= "" (:title (:baseline slice)))
              "the touched field keeps its own baseline too, so the typing reads as UNSAVED")
          (is (= {:title "" :description "Intro" :body "Body text" :tagList "intro, demo"}
                 (:baseline slice))
              "the baseline is seeded leafwise in step with the draft")
          (is (= "Intro" (:description draft)) "an untouched field IS seeded from the loaded article")
          (is (= "intro, demo" (:tagList draft)) "…including the joined tag string")
          (is (= "hello-world" (:slug slice)) "the slice still targets the loaded slug")
          (is (true? (sub f [:editor/dirty?]))
              "typing that survived a settle leaves the draft DIRTY — the save must send it")
          (is (= #{:title} (:touched slice)) "the seed marks nothing touched of its own"))))))

;; The cross-slug half of the same behaviour. The leafwise seed above protects a
;; field the USER HAS TOUCHED — but a reply for article A landing on article B's
;; slice finds every field untouched, so the merge would hand A's values over
;; field by field. Two gates answer two questions: correlation decides WHETHER
;; the reply belongs to this screen, the leafwise seed decides WHICH FIELDS it
;; may write. These two tests drive the real sequence — A's GET out, navigate
;; to B, A settles late — over both reply branches.

(defn- editor-cross-slug-settle-is-refused-test []
  ;; A stub that CAPTURES every lowered request and never replies, so BOTH the A
  ;; and the B GET can be held open and settled by hand, in the order a slow
  ;; network would pick.
  (let [lowered (atom [])
        article (fn [slug title]
                  {:article {:slug slug :title title
                             :description (str "About " slug)
                             :body        (str "Body of " slug)
                             :tagList     [slug]}})]
    (rf/reg-fx :realworld.test/editor-cross-slug
      {:platforms #{:client :server}}
      (fn [_frame-ctx args] (swap! lowered conj args) nil))
    (with-new-frame [f (frame/make-anon-frame-record!
                         {:initial-events [[:app/initialise]]
                          :fx-overrides {:rf.http/managed :realworld.test/editor-cross-slug}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      ;; Enter /editor/alpha — A's GET goes out and stays out.
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/alpha"] {:frame f})
      ;; …and move on to /editor/beta before A replies. A freshly-entered draft
      ;; is clean, so `:can-leave` waves this through: an ordinary navigation.
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/beta"] {:frame f})
      (is (= 2 (count @lowered))
          "each editor entry lowered its own article GET, and nothing else went out")
      (let [[a-req b-req] @lowered]
        (is (= [:editor/load-article "alpha"] (:request-id a-req))
            "the two GETs carry DISTINCT per-slug request-ids, so A's reply is
             delivered in full and correlating it is the app's job")
        (is (= [:editor/load-article "beta"] (:request-id b-req)))
        (is (= [:editor/loaded "alpha"] (:on-success a-req))
            "so the reply target carries the slug it was requested for")
        (is (= [:editor/load-failed "alpha"] (:on-failure a-req))
            "…on the failure branch too")
        ;; B settles normally first: the editor is fully seeded from beta.
        (rf/dispatch-sync (conj (:on-success b-req)
                                {:status :ok :value (article "beta" "Beta")})
                          {:frame f})
        (is (= "Beta" (:title (sub f [:editor/draft])))
            "B's own reply seeds B's draft — the ordinary path is untouched")
        ;; THE LATE ARRIVAL. Nothing has been typed, so every field of beta's
        ;; slice is UNTOUCHED — which is precisely why the leafwise seed is no
        ;; defence here.
        (rf/dispatch-sync (conj (:on-success a-req)
                                {:status :ok :value (article "alpha" "Alpha")})
                          {:frame f})
        (let [slice (sub f [:editor/slice])
              draft (sub f [:editor/draft])]
          (is (= "beta" (:slug slice))
              "a late alpha reply must not re-slug the editor — the PUT target stays beta")
          (is (= {:title "Beta" :description "About beta" :body "Body of beta" :tagList "beta"}
                 draft)
              "…nor rewrite beta's draft")
          (is (= {:title "Beta" :description "About beta" :body "Body of beta" :tagList "beta"}
                 (:baseline slice))
              "…nor beta's baseline, which is what dirty-detection compares against")
          (is (false? (sub f [:editor/dirty?]))
              "so the form stays clean and `:can-leave` still lets the reader go")
          (is (empty? (:touched slice))
              "and the refusal marks nothing touched of its own"))))))

(defn- editor-cross-slug-failure-is-refused-test []
  ;; Same sequence, failure branch: beta's GET is still out when alpha's fails.
  (let [lowered (atom [])]
    (rf/reg-fx :realworld.test/editor-cross-slug-fail
      {:platforms #{:client :server}}
      (fn [_frame-ctx args] (swap! lowered conj args) nil))
    (with-new-frame [f (frame/make-anon-frame-record!
                         {:initial-events [[:app/initialise]]
                          :fx-overrides {:rf.http/managed :realworld.test/editor-cross-slug-fail}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/alpha"] {:frame f})
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/beta"] {:frame f})
      (let [[a-req] @lowered]
        (rf/dispatch-sync (conj (:on-failure a-req)
                                {:status :error
                                 :error  {:kind :rf.http/http-5xx :status 500}})
                          {:frame f})
        (is (nil? (sub f [:editor/submit-error]))
            "alpha's late failure raises no error banner over beta's editor")
        (is (true? (ed-has-tag? f :lifecycle/loading))
            "…and leaves the lifecycle region at :loading, where beta's own fetch put it")
        (is (false? (ed-has-tag? f :lifecycle/error))
            "…so the page-level error gate stays shut for a reply that was never beta's")))))

(deftest article-editor
  (testing "editor create flow saves and clears :dirty?"
    (editor-create-test))
  (testing "editor :can-leave? blocks once the draft diverges"
    (editor-can-leave-test))
  (testing "pure helpers: validate-draft / parse-tag-list / draft-from-article / article-body"
    (editor-pure-helpers-test))
  (testing "edit-mode load seeds the draft, flips :mode/edit, and submit issues a PUT"
    (editor-edit-load-and-put-test))
  (testing "a load failure lands the lifecycle in :error and surfaces the message"
    (editor-load-failure-test))
  (testing ":editor/delete issues a DELETE, resets the slice, and navigates home"
    (editor-delete-test))
  (testing "a client-invalid submit fills per-field errors and fires no request"
    (editor-invalid-submit-test))
  (testing "a same-slug settle seeds LEAFWISE and does not clobber typing"
    (editor-same-slug-seed-preserves-typing-test))
  (testing "a CROSS-slug settle is refused outright — a late A cannot rewrite B's draft, baseline or slug"
    (editor-cross-slug-settle-is-refused-test))
  (testing "a CROSS-slug FAILURE is refused too — a late A error cannot banner B or trip B's lifecycle"
    (editor-cross-slug-failure-is-refused-test)))

;; ============================================================================
;; Routing — the table both screens sit in, and the auth gate on the editor
;; ============================================================================

(defn- routing-tests []
  (with-new-frame [f (frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.article/show :params {:slug "hello"}}] {:frame f})
    (is (= :realworld.article/show (sub f [:rf.route/id])))
    (is (= "hello" (:slug (sub f [:rf.route/params]))))

    (rf/dispatch-sync [:rf.route/handle-url-change "/profile/eve"] {:frame f})
    (is (= :realworld.profile/show (sub f [:rf.route/id])))

    ;; `/settings` requires auth; this check is about the route TABLE, not the
    ;; gate, so sign in first — otherwise the gate correctly refuses the
    ;; logged-out entry and redirects to login (the gate is covered below).
    (rf/dispatch-sync [:auth/store-session {:username "eve" :token "t"}] {:frame f})
    (rf/dispatch-sync [:rf.route/handle-url-change "/settings"] {:frame f})
    (is (= :realworld.user/settings (sub f [:rf.route/id])))

    ;; The tag filter is the `/tag/:tag` PATH route — the tag is a route
    ;; PARAM, not a `?tag=` query.
    (rf/dispatch-sync [:rf.route/handle-url-change "/tag/clojure"] {:frame f})
    (is (= :realworld/home-tag (sub f [:rf.route/id])))
    (is (= "clojure" (:tag (sub f [:rf.route/params]))))

    (rf/dispatch-sync [:rf.route/handle-url-change "/garbage/path"] {:frame f})
    (is (= :rf.route/not-found (sub f [:rf.route/id])))))

(defn- auth-guard-test []
  ;; Each route that requires auth declares `:can-enter [:realworld.routing/authed?]`,
  ;; and an `:rf.route/entry-denied` handler stashes the denied destination and
  ;; replace-navigates to login. Entry denial is TERMINAL: it commits nothing
  ;; and creates NO pending value, so the return after sign-in is a FRESH
  ;; navigate, not a resume.
  (with-new-frame [f (frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    ;; Unauthenticated: navigating to a guarded route is denied and redirects
    ;; to login.
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.user/settings}] {:frame f})
    (is (= :realworld.auth/login (sub f [:rf.route/id]))
        "unauthenticated nav to a :requires-auth route redirects to login")

    ;; A non-guarded route is unaffected.
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld/home}] {:frame f})
    (is (= :realworld/home (sub f [:rf.route/id]))
        "unguarded route navigates normally with the gate active")

    ;; Fresh-return stash: the denial handler records the denied destination
    ;; at [:auth :return-to]. No pending-navigation value is created.
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.user/settings}] {:frame f})
    (is (= {:to :realworld.user/settings}
           (get-in (rf/frame-state-value f) [:rf.db/app :auth :return-to]))
        "the denial stashes the canonical destination for the post-login return")
    (is (nil? (get-in (rf/frame-state-value f) [:rf.db/runtime :rf.runtime/routing
                                                :pending-navigation]))
        "a terminal denial creates NO pending-navigation value")

    ;; Authenticated: the same guarded nav now proceeds.
    (rf/dispatch-sync [:auth/store-session {:username "eve" :token "t"}] {:frame f})
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.user/settings}] {:frame f})
    (is (= :realworld.user/settings (sub f [:rf.route/id]))
        "authenticated nav to a :requires-auth route proceeds")

    ;; The post-login return consumes the stashed destination and clears the
    ;; slot — an ordinary FRESH navigate whose guard re-evaluates.
    (rf/dispatch-sync [:auth/post-login-redirect] {:frame f})
    (is (nil? (get-in (rf/frame-state-value f) [:rf.db/app :auth :return-to]))
        ":auth/post-login-redirect clears the :return-to slot")))

(deftest routing
  (testing "navigate, handle-url-change, query, and not-found all resolve"
    (routing-tests))
  (testing "the auth gate redirects unauthenticated nav to guarded routes and returns after sign-in"
    (auth-guard-test)))

;; ============================================================================
;; Boot
;; ============================================================================

(defn- app-boot-test []
  (with-new-frame [f (frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed      :realworld.test/canned-success-empty
                                       :auth.session/persist :rf/no-op}})]
    ;; `:auth/initialise` consumes the `:auth.session/token` coeffect, so it is
    ;; its own boot step in the real app. Dispatch it here with the token
    ;; pinned through the dispatch-site coeffect stub (Node has no
    ;; localStorage for the supplier to read, so the value would be nil anyway).
    (rf/dispatch-sync [:auth/initialise]
                      {:frame f :rf.cofx {:auth.session/token nil}})
    ;; After init: the :auth + :articles slices and the :realworld/tags +
    ;; :settings/form machine snapshots are present. App data is in app-db;
    ;; machine snapshots in runtime-db.
    (let [db (rf/app-db-value f)
          rt (:rf.db/runtime (rf/frame-state-value f))]
      (is (contains? db :auth))
      (is (contains? db :articles))
      (is (contains? (get-in rt [:rf.runtime/machines :snapshots]) :realworld/tags))
      (is (contains? (get-in rt [:rf.runtime/machines :snapshots]) :settings/form)))))

(deftest boot
  (testing "app boot populates :auth, :articles, and the machine snapshots"
    (app-boot-test)))
