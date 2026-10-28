(ns re-frame.realworld-cljs-test
  "Managed-HTTP RealWorld contracts preserve validation, leafwise edits, reply ownership and persistent backend writes."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest testing use-fixtures is async]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.integration-adapter :as rf.integration-adapter]
            [re-frame.test-support :as rf.test-support]
            [re-frame.schemas.malli]
            [re-frame.views]
            [re-frame.http.stub-transport :as rf.http.stub-transport]
            [realworld-http.schema :as app-schema]
            [realworld-http.core]
            [realworld-http.auth :as auth]
            [realworld-http.routing]
            [realworld-http.http :as rh]
            [realworld-http.article-editor :as editor]
            [realworld-http.ssr :as ssr]
            [realworld-shared.demo-backend :as demo])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.integration-adapter/adapter
     :ambient-frame nil
     :async?        true
     :app-ns        "realworld-http."}))

(defn- answering-frame!
  "A frame from `config` whose every managed request is answered with
   `response`, a stub-transport response map."
  [config response]
  (let [f (rf.frame/make-anon-frame-record! config)]
    (rf.http.stub-transport/install-transport!
      f (rf.http.stub-transport/route-transport (constantly response)))
    f))

(defn- reg-capturing-managed!
  [fx-id sink]
  (rf/reg-fx fx-id
    {:platforms #{:client :server}}
    (fn [_frame-ctx args] (swap! sink conj args) nil)))

(defn- wire-user
  [username token bio]
  {:email (str username "@example.com") :token token :username username :bio bio :image nil})

(defn- with-held-fx
  ([body-fn] (with-held-fx "zed" body-fn))
  ([username body-fn]
   (let [lowered (atom [])]
     (reg-capturing-managed! :realworld.test/held-managed lowered)
     (with-new-frame [f (rf.frame/make-anon-frame-record!
                          {:initial-events [[:app/initialise]]
                           :fx-overrides   {:rf.http/managed      :realworld.test/held-managed
                                            :auth.session/persist :rf/no-op}})]
       (when username
         (rf/dispatch-sync [:auth/store-session (wire-user username "jwt" nil)] {:frame f}))
       (body-fn f lowered)))))

(defn- settle-ok!
  [f target value]
  (rf/dispatch-sync (conj target {:status :ok :value value}) {:frame f}))

(defn- settle-fail! [f target]
  (rf/dispatch-sync (conj target {:status :error :error {:kind :rf.http/http-5xx :http-status 500}})
                    {:frame f}))

(defn- req-by-id
  [lowered id]
  (last (filter #(= id (:request-id %)) lowered)))

(defn- req-by-method+url
  [lowered method url-suffix]
  (last (filter #(and (= method (get-in % [:request :method]))
                      (str/ends-with? (get-in % [:request :url]) url-suffix))
                lowered)))

(defn- sub [f query]
  (rf/compute-sub query (rf/frame-state-value f)))

(defn- visit! [f & urls]
  (doseq [url urls]
    (rf/dispatch-sync [:rf.route/handle-url-change url] {:frame f})))

(defn- location
  [f]
  [(sub f [:rf.route/id]) (sub f [:rf.route/params])])

(defn- address [f]
  (conj (location f) (sub f [:rf.route/query]) (sub f [:rf.route/fragment])))

(defn- return-to [f]
  (get-in (rf/app-db-value f) [:auth :return-to]))

(defn- full-article [slug]
  {:slug slug :title (str "Title " slug) :description (str "About " slug)
   :body (str "Body of " slug) :tagList [slug]
   :createdAt "2026-05-01" :updatedAt "2026-05-01"
   :favorited false :favoritesCount 0
   :author {:username "alice" :bio nil :image nil :following false}})

(defn- articles-of [& slugs]
  {:articles (mapv full-article slugs) :articlesCount (count slugs)})

(defn- full-comment [slug]
  {:id (str "c-" slug) :createdAt "2026-05-01" :updatedAt "2026-05-01"
   :body (str "First on " slug)
   :author {:username "eve" :bio nil :image nil :following false}})

(defn- saved-comment [id body]
  {:id id :createdAt "2026-05-02" :updatedAt "2026-05-02" :body body
   :author {:username "alice" :bio nil :image nil :following false}})

(defn- full-profile [username following?]
  {:username username :bio (str "Bio of " username) :image nil :following following?})

(defn- with-local-storage
  [descriptor f]
  (let [g     js/globalThis
        prior (js/Object.getOwnPropertyDescriptor g "localStorage")]
    (js/Object.defineProperty g "localStorage" descriptor)
    (try
      (f)
      (finally
        (if prior
          (js/Object.defineProperty g "localStorage" prior)
          (js-delete g "localStorage"))))))

(defn- with-saved-jwt
  [token f]
  (with-local-storage
    #js {:configurable true
         :value        #js {:getItem    (fn [k] (when (= k "jwtToken") token))
                            :setItem    (fn [_ _] nil)
                            :removeItem (fn [_] nil)}}
    f))

(defn- init-auth!
  [f token]
  (with-saved-jwt token #(rf/dispatch-sync [:auth/initialise] {:frame f})))

(defn- submit-login!
  [f]
  (rf/dispatch-sync [:auth.login-form/initialise] {:frame f})
  (rf/dispatch-sync [:auth.login-form/edit-field :email "reader@example.com"] {:frame f})
  (rf/dispatch-sync [:auth.login-form/edit-password {:value "pw"}] {:frame f})
  (rf/dispatch-sync [:auth.login-form/submit] {:frame f}))

(deftest realworld-auth-flow
  (testing "a login signs in and lands home; logout signs out"
    (with-new-frame [f (answering-frame!
                         {:fx-overrides {:auth.session/persist :rf/no-op}}
                         {:body {:user (wire-user "alice" "jwt-abc" nil)}})]
      (init-auth! f nil)
      (submit-login! f)
      (is (= [:authed "alice" :realworld/home]
             [(sub f [:auth/state]) (:username (sub f [:auth/user])) (sub f [:rf.route/id])]))
      (rf/dispatch-sync [:auth/flow [:auth/logout]] {:frame f})
      (is (= [:idle nil] [(sub f [:auth/state]) (sub f [:auth/user])]))))

  (testing "a rejected login lands in :error with a message, and dismiss returns to :idle"
    (with-new-frame [f (answering-frame!
                         {}
                         {:status 422 :body {:errors {:body ["email or password is invalid"]}}})]
      (init-auth! f nil)
      (submit-login! f)
      (is (= [:error true] [(sub f [:auth/state]) (some? (sub f [:auth/error]))]))
      (rf/dispatch-sync [:auth/flow [:auth/dismiss]] {:frame f})
      (is (= :idle (sub f [:auth/state])))))

  (testing "an unreadable store reads as no saved token, and a stale storage read is dropped"
    (is (nil? (with-local-storage
                #js {:configurable true :get (fn [] (throw (js/Error. "storage is blocked")))}
                auth/read-saved-token)))
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (init-auth! f nil)
      (rf/dispatch-sync [:auth/session-read {:generation (dec (:auth-generation (rf/app-db-value f)))
                                             :token      "jwt-stale"}]
                        {:frame f})
      (is (= [nil :idle] [(get-in (rf/app-db-value f) [:auth :token]) (sub f [:auth/state])]))))

  (testing "the stored session user is token-free and passes the app's real AuthSlice"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (rf/reg-app-schema [:auth] app-schema/AuthSlice)
      (rf/dispatch-sync [:auth/store-session (wire-user "alice" "jwt-abc" nil)] {:frame f})
      (let [db (rf/app-db-value f)]
        (is (= [{:email "alice@example.com" :username "alice" :bio nil :image nil} "jwt-abc"]
               [(get-in db [:auth :user]) (get-in db [:auth :token])])))))

  (testing "a restore reply that lands after a login is dropped"
    (let [sink (atom [])]
      (reg-capturing-managed! :realworld.test/held-managed sink)
      (with-new-frame [f (rf.frame/make-anon-frame-record!
                           {:fx-overrides {:rf.http/managed      :realworld.test/held-managed
                                           :auth.session/persist :rf/no-op}})]
        (init-auth! f "jwt-alice")
        (let [restore (first @sink)]
          (submit-login! f)
          (settle-ok! f (:on-success (last @sink)) {:user (wire-user "bob" "jwt-bob" nil)})
          (settle-ok! f (:on-success restore) {:user (wire-user "alice" "jwt-alice" nil)})
          (is (= ["bob" "jwt-bob" :authed]
                 [(:username (sub f [:auth/user])) (get-in (rf/app-db-value f) [:auth :token])
                  (sub f [:auth/state])])))))))

(defn- decode-to-url-strategy
  [url]
  {:encode            (fn [path] path)
   :decode            (fn [] url)
   :push!             (fn [_href] nil)
   :replace!          (fn [_href] nil)
   :install-listener! (fn [_on-change] (fn teardown [] nil))})

(defn- booting-frame!
  [url token sink]
  (reg-capturing-managed! :realworld.test/held-managed sink)
  (with-saved-jwt token
    #(rf.frame/make-anon-frame-record!
       {:url-bound?     true
        :url-strategy   (decode-to-url-strategy url)
        :initial-events [[:auth/initialise]
                         [:app/initialise]]
        :fx-overrides   {:rf.http/managed      :realworld.test/held-managed
                         :auth.session/persist :rf/no-op
                         :rf.nav/push-url      :rf/no-op
                         :rf.nav/replace-url   :rf/no-op}})))

(deftest realworld-cold-boot-deep-link-race
  (let [restored {:user (wire-user "alice" "jwt-saved" nil)}]
    (testing "a protected deep link with a saved token waits for the restore, then
              enters the route it asked for"
      (let [sink (atom [])]
        (with-new-frame [f (booting-frame! "/settings" "jwt-saved" sink)]
          (is (= [1 nil {:to :realworld.user/settings} true]
                 [(count @sink) (sub f [:rf.route/id]) (return-to f)
                  (sub f [:realworld.routing/deferred-entry?])]))
          (settle-ok! f (:on-success (first @sink)) restored)
          (is (= [:realworld.user/settings "alice" :authed nil false]
                 [(sub f [:rf.route/id]) (:username (sub f [:auth/user])) (sub f [:auth/state])
                  (return-to f) (sub f [:realworld.routing/deferred-entry?])])))))

    (testing "…and returns to the whole address, query and #fragment included"
      (let [sink (atom [])]
        (with-new-frame [f (booting-frame! "/editor/my-slug?tab=preview#comments" "jwt-saved" sink)]
          (settle-ok! f (:on-success (first @sink)) restored)
          (is (= [:realworld.editor/edit {:slug "my-slug"} {"tab" "preview"} "comments"]
                 (address f))))))

    (testing "a rejected saved token fails closed to login, keeping the stash for the
              sign-in that follows"
      (let [sink (atom [])]
        (with-new-frame [f (booting-frame! "/settings" "jwt-expired" sink)]
          (settle-fail! f (:on-failure (first @sink)))
          (is (= [:realworld.auth/login nil {:to :realworld.user/settings}]
                 [(sub f [:rf.route/id]) (get-in (rf/app-db-value f) [:auth :token]) (return-to f)]))
          (rf/dispatch-sync [:auth/store-session (wire-user "alice" "fresh" nil)] {:frame f})
          (rf/dispatch-sync [:auth/post-login-redirect] {:frame f})
          (is (= :realworld.user/settings (sub f [:rf.route/id]))))))

    (testing "with no saved token there is nothing to wait for, so the bounce is immediate"
      (let [sink (atom [])]
        (with-new-frame [f (booting-frame! "/settings" nil sink)]
          (is (= [[] :realworld.auth/login false]
                 [@sink (sub f [:rf.route/id]) (sub f [:realworld.routing/deferred-entry?])])))))

    (testing "a public deep link commits at once and stays put when the restore lands"
      (let [sink (atom [])]
        (with-new-frame [f (booting-frame! "/article/some-slug" "jwt-saved" sink)]
          (is (= [:realworld.article/show nil] [(sub f [:rf.route/id]) (return-to f)]))
          (settle-ok! f (:on-success (req-by-method+url @sink :get "/user")) restored)
          (is (= [:realworld.article/show :authed] [(sub f [:rf.route/id]) (sub f [:auth/state])])))))))

(deftest realworld-routing
  (testing "the tag filter is a path route, and an unknown URL is not-found"
    (with-held-fx nil
      (fn [f _lowered]
        (visit! f "/tag/clojure")
        (is (= [:realworld/home-tag {:tag "clojure"}] (location f)))
        (visit! f "/garbage/path")
        (is (= :rf.route/not-found (sub f [:rf.route/id]))))))

  (testing "a signed-out visitor to a :requires-auth route is sent to login with the
            whole destination stashed, and signing in returns there and clears it"
    (with-held-fx nil
      (fn [f _lowered]
        (doseq [url ["/settings" "/editor" "/editor/my-slug?tab=preview#comments"]]
          (visit! f url)
          (is (= :realworld.auth/login (sub f [:rf.route/id]))))
        (is (= {:to       :realworld.editor/edit
                :params   {:slug "my-slug"}
                :query    {"tab" "preview"}
                :fragment "comments"}
               (return-to f)))
        (rf/dispatch-sync [:auth/store-session (wire-user "eve" "t" nil)] {:frame f})
        (rf/dispatch-sync [:auth/post-login-redirect] {:frame f})
        (is (= [[:realworld.editor/edit {:slug "my-slug"} {"tab" "preview"} "comments"] nil]
               [(address f) (return-to f)]))))))

(deftest realworld-pagination
  (testing "paginate-path joins the path, the filter and the limit/offset window"
    (doseq [[params page parts] [[nil 1 ["limit=10" "offset=0"]]
                                 [{:tag "clojure"} 3 ["tag=clojure" "limit=10" "offset=20"]]]]
      (let [p (rh/paginate-path "/articles" params page)]
        (is (and (str/starts-with? p "/articles?") (every? #(str/includes? p %) parts))))))

  (testing "a page change keeps the active feed, tag or profile tab and moves only ?page="
    (with-held-fx
      (fn [f _lowered]
        (let [page! #(rf/dispatch-sync [%1 %2] {:frame f})
              page  #(:page (sub f [:rf.route/query]))]
          (rf/dispatch-sync [:home/show-global-feed] {:frame f})
          (page! :home/show-page 3)
          (is (= [:realworld/home 3 nil]
                 [(sub f [:rf.route/id]) (page) (:feed (sub f [:rf.route/query]))]))
          (rf/dispatch-sync [:home/show-your-feed] {:frame f})
          (page! :home/show-page 2)
          (is (= [:realworld/home 2 "following"]
                 [(sub f [:rf.route/id]) (page) (:feed (sub f [:rf.route/query]))]))
          (rf/dispatch-sync [:tags/apply-filter "clojure"] {:frame f})
          (page! :home/show-page 2)
          (is (= [:realworld/home-tag "clojure" 2]
                 [(sub f [:rf.route/id]) (sub f [:home/selected-tag]) (page)]))
          (rf/dispatch-sync [:rf.route/navigate {:to :realworld.profile/show :params {:username "eve"}}]
                            {:frame f})
          (page! :profile/show-page 2)
          (is (= [[:realworld.profile/show {:username "eve"}] 2] [(location f) (page)]))
          (rf/dispatch-sync [:rf.route/navigate {:to :realworld.profile/favorites :params {:username "eve"}}]
                            {:frame f})
          (page! :profile/show-page 3)
          (is (= [:realworld.profile/favorites 3] [(sub f [:rf.route/id]) (page)])))))))

(deftest realworld-ssr
  (testing "a signed-in app frame's hydration payload ships the allowlisted slices and
            the route, and never the JWT"
    (with-held-fx
      (fn [f _]
        (visit! f "/")
        (let [payload (ssr/hydration-payload f)]
          (is (= [true "zed" false :realworld/home]
                 [(every? (set ssr/ssr-app-slice-keys) (keys (:rf/app-db payload)))
                  (get-in payload [:rf/app-db :auth :user :username])
                  (contains? (get-in payload [:rf/app-db :auth]) :token)
                  (get-in payload [:rf/runtime-db :rf.runtime/routing :current :route-id])])))))))

(defn- home [f]
  [(sub f [:articles.home/render]) (mapv :slug (sub f [:articles.home/active-articles]))])

(deftest realworld-articles-feed
  (testing "a current feed's failure surfaces a readable error, and Your Feed keeps the
            server's grand count for paging"
    (with-held-fx
      (fn [f lowered]
        (rf/dispatch-sync [:articles/load] {:frame f})
        (settle-fail! f (:on-failure (last @lowered)))
        (rf/dispatch-sync [:feed/load] {:frame f})
        (settle-fail! f (:on-failure (last @lowered)))
        (is (= [:error true true]
               [(:status (sub f [:articles/slice])) (some? (sub f [:articles/error]))
                (some? (sub f [:feed/error]))]))
        (rf/dispatch-sync [:feed/load] {:frame f})
        (settle-ok! f (:on-success (last @lowered)) (assoc (articles-of "f1") :articlesCount 7))
        (is (= [1 7] [(count (sub f [:feed/data])) (sub f [:feed/count])])))))

  (testing "leaving Your Feed for the Global Feed: its replies, failure or success, before
            or after the current one, never settle the machine"
    (with-held-fx
      (fn [f lowered]
        (visit! f "/?feed=following" "/")
        (let [departed (req-by-id @lowered :feed/load)]
          (settle-fail! f (:on-failure departed))
          (settle-ok! f (:on-success departed) (articles-of))
          (is (= :loading
                 (get-in (rf/frame-state-value f)
                         [:rf.db/runtime :rf.runtime/machines :snapshots :realworld/articles-home
                          :state :data])))
          (settle-ok! f (:on-success (req-by-id @lowered :articles/load)) (articles-of "hello-conduit"))
          (settle-ok! f (:on-success departed) (articles-of))
          (settle-fail! f (:on-failure departed))
          (is (= [:some ["hello-conduit"]] (home f)))))))

  (testing "…and leaving the Global Feed for Your Feed"
    (with-held-fx
      (fn [f lowered]
        (visit! f "/" "/?feed=following")
        (settle-ok! f (:on-success (req-by-id @lowered :articles/load)) (articles-of))
        (settle-ok! f (:on-success (req-by-id @lowered :feed/load)) (articles-of "followed-article"))
        (is (= [:some ["followed-article"]] (home f))))))

  (testing "a page change stays on the same feed, so its reply renders"
    (with-held-fx
      (fn [f lowered]
        (visit! f "/" "/?page=2")
        (settle-ok! f (:on-success (req-by-id @lowered :articles/load)) (articles-of "page-two"))
        (is (= [:some ["page-two"]] (home f)))))))

(deftest realworld-tags
  (testing "a tags load fills the sidebar's list"
    (with-held-fx
      (fn [f lowered]
        (rf/dispatch-sync [:tags/load] {:frame f})
        (settle-ok! f (:on-success (last @lowered)) {:tags ["intro" "demo" "clojure"]})
        (is (= ["intro" "demo" "clojure"] (sub f [:tags/data])))))))

(defn- ed-has-tag? [f tag]
  (sub f [:rf.machine/has-tag? :ui/article-editor tag]))

(defn- open-clean-editor! [f lowered slug]
  (visit! f (str "/editor/" slug))
  (settle-ok! f (:on-success (req-by-id @lowered [:editor/load-article slug]))
              {:article (full-article slug)}))

(defn- editor-delete!
  [f lowered slug]
  (rf/dispatch-sync [:editor/delete] {:frame f})
  (req-by-method+url @lowered :delete (str "/articles/" slug)))

(deftest realworld-article-editor
  (testing "a whitespace-only field is blank, and the request body parses the tag
            string, dropping blank entries"
    (is (= {:description "Description is required."}
           (editor/validate-draft {:title "T" :description "   " :body "B"})))
    (is (= {:article {:title "T" :description "D" :body "B" :tagList ["a" "x"]}}
           (editor/article-body {:title "T" :description "D" :body "B" :tagList "a, , x ,"}))))

  (testing "creating: :can-submit? opens once the draft is valid and dirty, and a save
            lands in :edit mode, clean"
    (with-new-frame [f (answering-frame!
                         {:initial-events [[:app/initialise]]}
                         {:body {:article (full-article "hello-world")}})]
      (let [blank (sub f [:editor/can-submit?])]
        (doseq [[field value] {:title "Hello" :description "Short" :body "Body"}]
          (rf/dispatch-sync [:editor/edit-field field value] {:frame f}))
        (is (= [false true] [blank (sub f [:editor/can-submit?])])))
      (rf/dispatch-sync [:editor/submit] {:frame f})
      (is (= [true true false]
             [(ed-has-tag? f :lifecycle/saved) (ed-has-tag? f :mode/edit) (sub f [:editor/dirty?])]))))

  (testing "an invalid submit shows every field's error and the form prompt, and sends
            nothing"
    (with-held-fx
      (fn [f lowered]
        (rf/dispatch-sync [:editor/edit-field :title "Only a title"] {:frame f})
        (rf/dispatch-sync [:editor/submit] {:frame f})
        (is (= [{:description "Description is required."
                 :body        "Body is required."
                 :_form       "Please fix the highlighted fields."}
                ["Description is required." [] nil]]
               [(sub f [:editor/errors])
                [(sub f [:editor/field-error :description]) @lowered (sub f [:editor/submit-error])]])))))

  (testing "a load that settles after the reader typed seeds only the untouched fields,
            leaving the typed one dirty"
    (with-held-fx
      (fn [f lowered]
        (visit! f "/editor/hello-world")
        (rf/dispatch-sync [:editor/edit-field :title "My unsaved heading"] {:frame f})
        (settle-ok! f (:on-success (req-by-id @lowered [:editor/load-article "hello-world"]))
                    {:article {:slug "hello-world" :title "Hello, world" :description "Intro"
                               :body "Body text" :tagList ["intro" "demo"]}})
        (let [{:keys [slug draft baseline touched]} (sub f [:editor/slice])]
          (is (= [{:title "My unsaved heading" :description "Intro" :body "Body text" :tagList "intro, demo"}
                  {:title "" :description "Intro" :body "Body text" :tagList "intro, demo"}
                  ["hello-world" #{:title} true]]
                 [draft
                  baseline
                  [slug touched (sub f [:editor/dirty?])]]))))))

  (testing "a load reply for an article the reader has left is refused outright, which
            the leafwise seed alone would not do"
    (with-held-fx
      (fn [f lowered]
        (visit! f "/editor/alpha" "/editor/beta")
        (is (= #{[:editor/load-article "alpha"] [:editor/load-article "beta"]}
               (set (map :request-id @lowered))))
        (settle-ok! f (:on-success (req-by-id @lowered [:editor/load-article "beta"]))
                    {:article (full-article "beta")})
        (let [beta (sub f [:editor/slice])]
          (settle-ok! f (:on-success (req-by-id @lowered [:editor/load-article "alpha"]))
                      {:article (full-article "alpha")})
          (is (= beta (sub f [:editor/slice])))))))

  (testing "a load failure for an article the reader has left neither banners nor errors
            the current one, whose own failure does"
    (with-held-fx
      (fn [f lowered]
        (visit! f "/editor/alpha" "/editor/beta")
        (settle-fail! f (:on-failure (req-by-id @lowered [:editor/load-article "alpha"])))
        (is (= [nil true] [(sub f [:editor/submit-error]) (ed-has-tag? f :lifecycle/loading)]))
        (settle-fail! f (:on-failure (req-by-id @lowered [:editor/load-article "beta"])))
        (is (= [true true] [(some? (sub f [:editor/submit-error])) (ed-has-tag? f :lifecycle/error)])))))

  (testing "a departed delete's late settles, success or failure, leave the newer draft
            and its route alone"
    (with-held-fx
      (fn [f lowered]
        (open-clean-editor! f lowered "alpha")
        (let [del   (editor-delete! f lowered "alpha")
              busy? (ed-has-tag? f :editor/busy)]
          (visit! f "/editor")
          (rf/dispatch-sync [:editor/edit-field :title "New unsaved draft"] {:frame f})
          (settle-ok! f (:on-success del) nil)
          (settle-fail! f (:on-failure del))
          (is (= [true :realworld.editor/new "New unsaved draft" nil true]
                 [busy? (sub f [:rf.route/id]) (get-in (sub f [:editor/slice]) [:draft :title])
                  (sub f [:editor/submit-error]) (ed-has-tag? f :lifecycle/idle)]))))))

  (testing "…or the profile the reader detoured to, and the next editing session starts
            unbusy"
    (with-held-fx
      (fn [f lowered]
        (open-clean-editor! f lowered "alpha")
        (let [del (editor-delete! f lowered "alpha")]
          (visit! f "/profile/eve")
          (settle-ok! f (:on-success del) nil)
          (is (= [[:realworld.profile/show {:username "eve"}] true]
                 [(location f) (ed-has-tag? f :editor/busy)])))
        (open-clean-editor! f lowered "beta")
        (is (= [false true] [(ed-has-tag? f :editor/busy) (ed-has-tag? f :lifecycle/idle)])))))

  (testing "a departed save does not drag the reader to the article, and leaving its dirty
            draft asks first"
    (with-held-fx
      (fn [f lowered]
        (open-clean-editor! f lowered "alpha")
        (rf/dispatch-sync [:editor/edit-field :title "Alpha, edited"] {:frame f})
        (rf/dispatch-sync [:editor/submit] {:frame f})
        (let [put (req-by-method+url @lowered :put "/articles/alpha")]
          (rf/dispatch-sync [:rf.route/navigate {:to :realworld.profile/show :params {:username "eve"}}]
                            {:frame f})
          (let [pending (sub f [:rf/pending-navigation])]
            (is (some? pending))
            (rf/dispatch-sync [:rf.route/continue (:id pending)] {:frame f}))
          (settle-ok! f (:on-success put) {:article (full-article "alpha")})
          (is (= [:realworld.profile/show {:username "eve"}] (location f)))))))

  (testing "on the issuing page, settles still land: a delete failure banners and frees
            the form, a delete success blanks the editor and goes home, a save opens
            the saved article"
    (with-held-fx
      (fn [f lowered]
        (open-clean-editor! f lowered "alpha")
        (settle-fail! f (:on-failure (editor-delete! f lowered "alpha")))
        (is (= [true false] [(some? (sub f [:editor/submit-error])) (ed-has-tag? f :editor/busy)]))
        (settle-ok! f (:on-success (editor-delete! f lowered "alpha")) nil)
        (is (= [:realworld/home nil true]
               [(sub f [:rf.route/id]) (:slug (sub f [:editor/slice])) (ed-has-tag? f :mode/create)]))
        (open-clean-editor! f lowered "beta")
        (rf/dispatch-sync [:editor/edit-field :title "Beta, edited"] {:frame f})
        (rf/dispatch-sync [:editor/submit] {:frame f})
        (settle-ok! f (:on-success (req-by-method+url @lowered :put "/articles/beta"))
                    {:article (full-article "beta")})
        (is (= [:realworld.article/show {:slug "beta"}] (location f)))))))

(defn- settings-form [f]
  (get-in (rf/frame-state-value f) [:rf.db/runtime :rf.runtime/machines :snapshots :settings/form]))

(defn- session
  [f]
  (let [db (rf/app-db-value f)]
    [(get-in db [:auth :user :username]) (get-in db [:auth :token])]))

(defn- park-settings-save!
  [f lowered owner field value]
  (rf/dispatch-sync [:auth/store-session (wire-user owner "jwt-1" nil)] {:frame f})
  (rf/dispatch-sync [:rf.route/navigate {:to :realworld.user/settings}] {:frame f})
  (rf/dispatch-sync [:settings/edit-field field value] {:frame f})
  (rf/dispatch-sync [:settings/submit] {:frame f})
  (req-by-method+url @lowered :put "/user"))

(defn- log-out!
  [f]
  (rf/dispatch-sync [:auth/clear-session] {:frame f})
  (rf/dispatch-sync [:settings/form [:reset]] {:frame f})
  (rf/dispatch-sync [:rf.route/navigate {:to :realworld/home}] {:frame f}))

(deftest realworld-settings
  (testing "a save lands the machine in :correct, folds the server's user into the
            session and persists its fresh token"
    (let [persisted (atom [])]
      (rf/reg-fx :realworld.test/capture-token
        (fn [_ {:keys [token]}] (swap! persisted conj token)))
      (with-new-frame [f (answering-frame!
                           {:initial-events [[:app/initialise]]
                            :fx-overrides   {:auth.session/persist :realworld.test/capture-token}}
                           {:body {:user (wire-user "alice" "jwt-2" "New bio")}})]
        (rf/dispatch-sync [:auth/store-session (wire-user "alice" "jwt-1" nil)] {:frame f})
        (rf/dispatch-sync [:settings/load] {:frame f})
        (rf/dispatch-sync [:settings/edit-field :bio "New bio"] {:frame f})
        (rf/dispatch-sync [:settings/submit] {:frame f})
        (is (= [:correct false "New bio" ["jwt-2"]]
               [(:state (settings-form f)) (sub f [:settings/submitting?])
                (get-in (rf/app-db-value f) [:auth :user :bio]) @persisted])))))

  (testing "a failed save lands in :incorrect with a message and leaves the session alone;
            the next edit returns the form to :neutral"
    (with-held-fx
      (fn [f lowered]
        (settle-fail! f (:on-failure (park-settings-save! f lowered "alice" :bio "Doomed bio")))
        (is (= [:incorrect true nil]
               [(:state (settings-form f)) (some? (sub f [:settings/submit-error]))
                (get-in (rf/app-db-value f) [:auth :user :bio])]))
        (rf/dispatch-sync [:settings/edit-field :bio "Better bio"] {:frame f})
        (is (= [:neutral nil] [(:state (settings-form f)) (sub f [:settings/submit-error])])))))

  (testing "a save answered after logout restores nothing, and settles and scrubs the form"
    (with-held-fx
      (fn [f lowered]
        (let [save (park-settings-save! f lowered "alice" :bio "New bio")]
          (rf/dispatch-sync [:auth/clear-session] {:frame f})
          (settle-ok! f (:on-success save) {:user (wire-user "alice" "jwt-2" "New bio")})
          (is (= [[nil nil] :neutral ""]
                 [(session f) (:state (settings-form f))
                  (get-in (settings-form f) [:data :draft :bio])]))))))

  (testing "…nor overwrites the session of the account signed in since"
    (with-held-fx
      (fn [f lowered]
        (let [save (park-settings-save! f lowered "alice" :bio "New bio")]
          (rf/dispatch-sync [:auth/clear-session] {:frame f})
          (rf/dispatch-sync [:auth/store-session (wire-user "bob" "bob-jwt" nil)] {:frame f})
          (settle-ok! f (:on-success save) {:user (wire-user "alice" "jwt-2" "New bio")})
          (is (= ["bob" "bob-jwt"] (session f)))))))

  (testing "with the next account's own save in flight, a previous account's success
            neither replaces the session nor settles or scrubs the newer save, which
            still lands"
    (with-held-fx
      (fn [f lowered]
        (let [alice (park-settings-save! f lowered "alice" :bio "New bio")
              _     (log-out! f)
              bob   (park-settings-save! f lowered "bob" :bio "New bio")]
          (settle-ok! f (:on-success alice) {:user (wire-user "alice" "alice-jwt-2" "Alice bio")})
          (let [form (settings-form f)]
            (is (= [["bob" "jwt-1"] :submitting "New bio" {:owner "bob" :username "bob"}]
                   [(session f) (:state form) (get-in form [:data :draft :bio])
                    (get-in form [:data :pending])])))
          (settle-ok! f (:on-success bob) {:user (wire-user "bob" "bob-jwt-2" "Bob bio")})
          (is (= [["bob" "bob-jwt-2"] "Bob bio" :correct]
                 [(session f) (get-in (rf/app-db-value f) [:auth :user :bio])
                  (:state (settings-form f))]))))))

  (testing "…and a previous account's failure neither settles nor banners it, while the
            newer save's own failure does"
    (with-held-fx
      (fn [f lowered]
        (let [alice (park-settings-save! f lowered "alice" :bio "New bio")
              _     (log-out! f)
              bob   (park-settings-save! f lowered "bob" :bio "New bio")]
          (settle-fail! f (:on-failure alice))
          (let [form (settings-form f)]
            (is (= [["bob" "jwt-1"] :submitting nil "New bio"]
                   [(session f) (:state form) (get-in form [:data :submit-error])
                    (get-in form [:data :draft :bio])])))
          (settle-fail! f (:on-failure bob))
          (is (= [:incorrect true]
                 [(:state (settings-form f)) (some? (sub f [:settings/submit-error]))]))))))

  (testing "a rename to a name another unanswered save claims leaves that account's
            success ambiguous, so it is refused; once both requests have answered the
            ledger drains, and a later save by that account lands"
    (with-held-fx
      (fn [f lowered]
        (let [alice  (park-settings-save! f lowered "alice" :bio "New bio")
              _      (log-out! f)
              bob    (park-settings-save! f lowered "bob" :username "alice")
              ledger #(let [db (rf/app-db-value f)]
                        [(:settings.saves-in-flight db) (:settings.saves-answered db 0)])]
          (is (= 2 (count (first (ledger)))))
          (settle-ok! f (:on-success alice) {:user (wire-user "alice" "alice-jwt-2" "Alice bio")})
          (let [form (settings-form f)]
            (is (= [["bob" "jwt-1"] :submitting "alice" {:owner "bob" :username "alice"}
                    :realworld.user/settings]
                   [(session f) (:state form) (get-in form [:data :draft :username])
                    (get-in form [:data :pending]) (sub f [:rf.route/id])])))
          (settle-fail! f (:on-failure bob))
          (is (= [:incorrect ["bob" "jwt-1"] [[] 0]]
                 [(:state (settings-form f)) (session f) (ledger)]))
          (log-out! f)
          (settle-ok! f (:on-success (park-settings-save! f lowered "alice" :bio "New bio"))
                      {:user (wire-user "alice" "alice-jwt-3" "Alice bio 2")})
          (is (= [["alice" "alice-jwt-3"] :correct :realworld.profile/show]
                 [(session f) (:state (settings-form f)) (sub f [:rf.route/id])]))))))

  (testing "a rename to an unclaimed name still lands, with another account's save
            parked the whole time"
    (with-held-fx
      (fn [f lowered]
        (park-settings-save! f lowered "alice" :bio "New bio")
        (log-out! f)
        (settle-ok! f (:on-success (park-settings-save! f lowered "bob" :username "robert"))
                    {:user (wire-user "robert" "bob-jwt-2" "Bob bio")})
        (is (= [["robert" "bob-jwt-2"] :correct :realworld.profile/show]
               [(session f) (:state (settings-form f)) (sub f [:rf.route/id])]))))))

(defn- settle-comments! [f lowered slug]
  (settle-ok! f (:on-success (req-by-id @lowered [:comments/load slug]))
              {:comments [(full-comment slug)]}))

(defn- open-article!
  [f lowered slug]
  (visit! f (str "/article/" slug))
  (settle-comments! f lowered slug))

(defn- post-comment! [f body]
  (rf/dispatch-sync [:comment-form/edit-field :body body] {:frame f})
  (rf/dispatch-sync [:comment-form/submit] {:frame f}))

(defn- comment-ids [f]
  (mapv :id (sub f [:comments/data])))

(defn- comment-form [f]
  (sub f [:comment-form/slice]))

(defn- draft-body [f]
  (get-in (comment-form f) [:draft :body]))

(deftest realworld-comments
  (testing "a delete rollback whose index outran a shrunken list re-inserts at the tail
            instead of throwing"
    (with-held-fx
      (fn [f _lowered]
        (rf/dispatch-sync [:comments/loaded nil {:value {:comments [{:id 7 :body "survivor"
                                                                     :author {:username "eve"}}]}}]
                          {:frame f})
        (rf/dispatch-sync [:comment/delete-rollback nil {:index 3 :comment {:id 9 :body "rolled-back"
                                                                            :author {:username "mallory"}}}]
                          {:frame f})
        (is (= [7 9] (comment-ids f))))))

  (testing "a blank comment fails on the client with a field error, not a transport one"
    (with-held-fx
      (fn [f _lowered]
        (post-comment! f "   ")
        (is (= ["Comment body is required." nil]
               [(sub f [:comment-form/field-error :body]) (sub f [:comment-form/submit-error])])))))

  (testing "under the app's own schemas a comment posts optimistically, the saved one
            replaces its card in place, a failed one leaves, and a failed delete comes
            back"
    (with-held-fx
      (fn [f lowered]
        (rf/reg-app-schemas app-schema/app-db-schemas)
        (visit! f "/article/alpha")
        (settle-ok! f (:on-success (req-by-id @lowered [:comments/load "alpha"]))
                    {:comments [(saved-comment 1 "First!")]})
        (post-comment! f "Great read")
        (let [posts #(filterv (fn [r] (= :post (get-in r [:request :method]))) @lowered)]
          (is (= 1 (count (posts))))
          (settle-ok! f (:on-success (last (posts))) {:comment (saved-comment 7 "Great read")})
          (is (= [[1 7] :idle ""] [(comment-ids f) (:status (comment-form f)) (draft-body f)]))
          (post-comment! f "Second thoughts")
          (let [cards (count (comment-ids f))]
            (settle-fail! f (:on-failure (last (posts))))
            (is (= [3 [1 7] :idle true]
                   [cards (comment-ids f) (:status (comment-form f))
                    (some? (:submit-error (comment-form f)))]))))
        (rf/dispatch-sync [:comment/delete 7] {:frame f})
        (let [deleted (comment-ids f)]
          (settle-fail! f (:on-failure (req-by-method+url @lowered :delete "/articles/alpha/comments/7")))
          (is (= [[1] [1 7]] [deleted (comment-ids f)])))))))

(defn- settle-article!
  ([f lowered slug] (settle-article! f lowered slug "alice" false))
  ([f lowered slug author following?]
   (settle-ok! f (:reply-to (req-by-id @lowered [:article/load slug]))
               {:article (assoc (full-article slug)
                                :author {:username author :bio nil :image nil :following following?})})))

(deftest realworld-article-page-cross-slug
  (testing "late replies for the article the reader left, success or failure, leave the
            current article and its comments alone, while its own failures land"
    (with-held-fx
      (fn [f lowered]
        (visit! f "/article/alpha" "/article/beta")
        (is (= #{[:article/load "alpha"] [:comments/load "alpha"]
                 [:article/load "beta"] [:comments/load "beta"]}
               (set (map :request-id @lowered))))
        (settle-article! f lowered "beta")
        (settle-comments! f lowered "beta")
        (let [page   #(vector (sub f [:article/slice]) (sub f [:comments/slice]))
              before (page)
              a-art  (:reply-to (req-by-id @lowered [:article/load "alpha"]))
              a-com  (req-by-id @lowered [:comments/load "alpha"])]
          (settle-ok! f a-art {:article (full-article "alpha")})
          (settle-ok! f (:on-success a-com) {:comments [(full-comment "alpha")]})
          (settle-fail! f a-art)
          (settle-fail! f (:on-failure a-com))
          (is (= before (page))))
        (visit! f "/article/gamma")
        (settle-fail! f (:reply-to (req-by-id @lowered [:article/load "gamma"])))
        (settle-fail! f (:on-failure (req-by-id @lowered [:comments/load "gamma"])))
        (is (= [:error true :error]
               [(:status (sub f [:article/slice])) (some? (sub f [:article/error]))
                (:status (sub f [:comments/slice]))])))))

  (testing "a same-slug re-load keeps the page up as a refresh, while a new slug starts
            both slices over, so the old article is never shown under the new URL"
    (with-held-fx
      (fn [f lowered]
        (open-article! f lowered "alpha")
        (settle-article! f lowered "alpha")
        (rf/dispatch-sync [:article/load {:params {:slug "alpha"}}] {:frame f})
        (rf/dispatch-sync [:comments/load {:params {:slug "alpha"}}] {:frame f})
        (is (= [:fetching "Title alpha" ["c-alpha"]]
               [(:status (sub f [:article/slice])) (:title (sub f [:article/data])) (comment-ids f)]))
        (visit! f "/article/beta")
        (let [art (sub f [:article/slice])
              com (sub f [:comments/slice])]
          (is (= [:loading nil "beta" :loading [] "beta"]
                 [(:status art) (:data art) (:slug art) (:status com) (:data com) (:slug com)])))))))

(deftest realworld-comment-mutations-cross-slug
  (testing "a comment POST or DELETE answered after the reader moved on writes nothing on
            the new article, success or failure"
    (with-held-fx
      (fn [f lowered]
        (open-article! f lowered "alpha")
        (post-comment! f "Posted on alpha")
        (rf/dispatch-sync [:comment/delete "c-alpha"] {:frame f})
        (let [post (req-by-method+url @lowered :post "/articles/alpha/comments")
              del  (req-by-method+url @lowered :delete "/articles/alpha/comments/c-alpha")
              beta #(vector (sub f [:comments/slice]) (comment-form f))]
          (open-article! f lowered "beta")
          (rf/dispatch-sync [:comment-form/edit-field :body "Typing on beta"] {:frame f})
          (let [before (beta)]
            (settle-ok! f (:on-success post) {:comment (saved-comment "saved-a" "Posted on alpha")})
            (settle-fail! f (:on-failure post))
            (settle-fail! f (:on-failure del))
            (is (= [before [["c-beta"] "Typing on beta" nil]]
                   [(beta)
                    [(comment-ids f) (draft-body f) (:submit-error (comment-form f))]])))))))

  (testing "a new article releases the comment form, so refusing a late settle cannot
            strand it mid-submit"
    (with-held-fx
      (fn [f lowered]
        (open-article! f lowered "alpha")
        (post-comment! f "Half-written on alpha")
        (let [mid-submit (sub f [:comment-form/submitting?])]
          (visit! f "/article/beta")
          (is (= [true false ""] [mid-submit (sub f [:comment-form/submitting?]) (draft-body f)]))))))

  (testing "a same-slug refresh keeps an unsent draft, and logout takes it away, so the
            next account on that article finds an empty, usable form"
    (with-held-fx
      (fn [f lowered]
        (rf/dispatch-sync [:auth/flow [:auth/login]] {:frame f})
        (rf/dispatch-sync [:auth/flow [:auth/success]] {:frame f})
        (open-article! f lowered "alpha")
        (rf/dispatch-sync [:comment-form/edit-field :body "unsent words"] {:frame f})
        (rf/dispatch-sync [:comments/load {:params {:slug "alpha"}}] {:frame f})
        (is (= "unsent words" (draft-body f)))
        (rf/dispatch-sync [:auth/flow [:auth/logout]] {:frame f})
        (rf/dispatch-sync [:auth/store-session (wire-user "bob" "jwt-bob" nil)] {:frame f})
        (visit! f "/article/alpha")
        (is (= [:fetching "" false]
               [(:status (sub f [:comments/slice])) (draft-body f) (sub f [:comment-form/submitting?])]))))))

(deftest realworld-article-social-cross-slug
  (testing "late follow and delete settles from the article the reader left touch neither
            the current byline, nor its banner, nor the reader's route"
    (with-held-fx
      (fn [f lowered]
        (visit! f "/article/alpha")
        (settle-article! f lowered "alpha" "eve" false)
        (rf/dispatch-sync [:article/toggle-follow-author] {:frame f})
        (rf/dispatch-sync [:article/delete] {:frame f})
        (let [follow (req-by-method+url @lowered :post "/profiles/eve/follow")
              del    (req-by-method+url @lowered :delete "/articles/alpha")]
          (visit! f "/article/beta")
          (settle-article! f lowered "beta" "bob" true)
          (settle-fail! f (:on-failure follow))
          (settle-ok! f (:on-success follow) {:profile {:username "eve" :bio "Writer" :image nil :following true}})
          (settle-fail! f (:on-failure del))
          (settle-ok! f (:on-success del) nil)
          (is (= [{:username "bob" :bio nil :image nil :following true} nil
                  [:realworld.article/show {:slug "beta"}]]
                 [(sub f [:article/author]) (sub f [:article/error]) (location f)]))))))

  (testing "a late delete success does not pull a reader off a non-article page, where
            the article slice still names the deleted article"
    (with-held-fx
      (fn [f lowered]
        (visit! f "/article/alpha")
        (settle-article! f lowered "alpha" "eve" false)
        (rf/dispatch-sync [:article/delete] {:frame f})
        (let [del (req-by-method+url @lowered :delete "/articles/alpha")]
          (visit! f "/profile/eve")
          (is (= "alpha" (:slug (sub f [:article/slice]))))
          (settle-ok! f (:on-success del) nil)
          (is (= [:realworld.profile/show {:username "eve"}] (location f)))))))

  (testing "on the article they were issued on, follow, unfollow and delete settles all land"
    (with-held-fx
      (fn [f lowered]
        (visit! f "/article/alpha")
        (settle-article! f lowered "alpha" "eve" false)
        (rf/dispatch-sync [:article/toggle-follow-author] {:frame f})
        (settle-ok! f (:on-success (req-by-method+url @lowered :post "/profiles/eve/follow"))
                    {:profile {:username "eve" :bio "Writer" :image nil :following true}})
        (is (= {:username "eve" :bio "Writer" :image nil :following true} (sub f [:article/author])))
        (rf/dispatch-sync [:article/toggle-follow-author] {:frame f})
        (let [flipped (:following (sub f [:article/author]))]
          (settle-fail! f (:on-failure (req-by-method+url @lowered :delete "/profiles/eve/follow")))
          (is (= [false true] [flipped (:following (sub f [:article/author]))])))
        (rf/dispatch-sync [:article/delete] {:frame f})
        (settle-fail! f (:on-failure (req-by-method+url @lowered :delete "/articles/alpha")))
        (is (some? (sub f [:article/error])))
        (rf/dispatch-sync [:article/delete] {:frame f})
        (settle-ok! f (:on-success (req-by-method+url @lowered :delete "/articles/alpha")) nil)
        (is (= :realworld/home (sub f [:rf.route/id])))))))

(deftest realworld-favorites
  (testing "a favourite flips optimistically and a failed request rolls the row back"
    (with-held-fx
      (fn [f lowered]
        (rf/dispatch-sync [:articles/loaded nil {:value (articles-of "hello")}] {:frame f})
        (rf/dispatch-sync [:article/toggle-favorite "hello"] {:frame f})
        (let [row        #(select-keys (first (sub f [:articles/data])) [:favorited :favoritesCount])
              optimistic (row)]
          (settle-fail! f (:on-failure (req-by-method+url @lowered :post "/articles/hello/favorite")))
          (is (= [{:favorited true :favoritesCount 1} {:favorited false :favoritesCount 0}]
                 [optimistic (row)])))))))

(defn- pf-has-tag? [f tag]
  (sub f [:rf.machine/has-tag? :ui/profile tag]))

(defn- open-profile! [f lowered username following?]
  (visit! f (str "/profile/" username))
  (settle-ok! f (:on-success (req-by-id @lowered [:profile/load username]))
              {:profile (full-profile username following?)}))

(defn- follow-requests [lowered username]
  (filterv #(str/ends-with? (get-in % [:request :url]) (str "/profiles/" username "/follow"))
           lowered))

(defn- follow-pending [f]
  (:profile.follow-pending (rf/app-db-value f)))

(deftest realworld-profile-page-cross-username
  (testing "late replies for the profile the reader left are refused, so a failure cannot
            strand the current profile in :error, while its own failures land"
    (with-held-fx
      (fn [f lowered]
        (visit! f "/profile/alice" "/profile/bob")
        (is (= #{[:profile/load "alice"] [:profile.articles/load "alice"]
                 [:profile/load "bob"] [:profile.articles/load "bob"]}
               (set (map :request-id @lowered))))
        (let [slices  #(let [db (rf/app-db-value f)] [(:profile db) (:profile.articles db)])
              alice   #(req-by-id @lowered [% "alice"])
              loading (slices)]
          (settle-fail! f (:on-failure (alice :profile/load)))
          (settle-fail! f (:on-failure (alice :profile.articles/load)))
          (is (= [loading true] [(slices) (pf-has-tag? f :data/loading)]))
          (settle-ok! f (:on-success (req-by-id @lowered [:profile/load "bob"]))
                      {:profile (full-profile "bob" false)})
          (settle-ok! f (:on-success (req-by-id @lowered [:profile.articles/load "bob"]))
                      (articles-of "b1"))
          (is (= :loaded (sub f [:profile/render])))
          (let [loaded (slices)]
            (settle-ok! f (:on-success (alice :profile/load)) {:profile (full-profile "alice" true)})
            (settle-ok! f (:on-success (alice :profile.articles/load))
                        (assoc (articles-of "a1") :articlesCount 9))
            (is (= loaded (slices)))))
        (visit! f "/profile/carol")
        (settle-fail! f (:on-failure (req-by-id @lowered [:profile/load "carol"])))
        (settle-fail! f (:on-failure (req-by-id @lowered [:profile.articles/load "carol"])))
        (is (= [:error :error :error]
               [(:status (sub f [:profile/slice])) (sub f [:profile/render])
                (:status (:profile.articles (rf/app-db-value f)))])))))

  (testing "a same-username re-load is a refresh; a new username starts the banner over,
            and the authored rows still loaded for the old one are neither shown nor
            counted under the new URL"
    (with-held-fx
      (fn [f lowered]
        (open-profile! f lowered "alice" false)
        (settle-ok! f (:on-success (req-by-id @lowered [:profile.articles/load "alice"]))
                    (articles-of "a1"))
        (rf/dispatch-sync [:profile/load {:params {:username "alice"}}] {:frame f})
        (rf/dispatch-sync [:profile.articles/load {:params {:username "alice"}}] {:frame f})
        (is (= [:fetching "alice" ["a1"]]
               [(:status (sub f [:profile/slice])) (:username (sub f [:profile/data]))
                (mapv :slug (sub f [:profile.articles/data]))]))
        (reset! lowered [])
        (visit! f "/profile/bob/favorites")
        (let [authored (:profile.articles (rf/app-db-value f))]
          (is (= [#{[:profile/load "bob"] [:profile.favorites/load "bob"]}
                  [:loading nil]
                  ["alice" 1 nil 0 []]]
                 [(set (map :request-id @lowered))
                  [(:status (sub f [:profile/slice])) (sub f [:profile/data])]
                  [(:username authored) (count (:data authored)) (sub f [:profile.articles/data])
                   (sub f [:profile.articles/count]) (sub f [:profile/current-articles])]]))))))

  (testing "the favorites tab loads the banner and the favorited list"
    (with-held-fx
      (fn [f lowered]
        (visit! f "/profile/eve/favorites")
        (settle-ok! f (:on-success (req-by-id @lowered [:profile/load "eve"]))
                    {:profile (full-profile "eve" false)})
        (settle-ok! f (:on-success (req-by-id @lowered [:profile.favorites/load "eve"]))
                    (articles-of "a1"))
        (is (= ["eve" ["a1"]]
               [(:username (sub f [:profile/data])) (mapv :slug (sub f [:profile.favorites/data]))])))))

  (testing "a follow held for the profile the reader left is refused, success or
            rollback, while the current profile's own settles land"
    (with-held-fx
      (fn [f lowered]
        (open-profile! f lowered "alice" false)
        (rf/dispatch-sync [:profile/follow] {:frame f})
        (let [a-follow (req-by-method+url @lowered :post "/profiles/alice/follow")]
          (open-profile! f lowered "bob" false)
          (let [bob (sub f [:profile/slice])]
            (settle-ok! f (:on-success a-follow) {:profile (full-profile "alice" true)})
            (settle-fail! f (:on-failure a-follow))
            (is (= bob (sub f [:profile/slice])))))
        (rf/dispatch-sync [:profile/follow] {:frame f})
        (settle-ok! f (:on-success (req-by-method+url @lowered :post "/profiles/bob/follow"))
                    {:profile (assoc (full-profile "bob" true) :bio "Fresh bio")})
        (is (= {:username "bob" :bio "Fresh bio" :image nil :following true} (sub f [:profile/data])))
        (rf/dispatch-sync [:profile/unfollow] {:frame f})
        (let [flipped (:following (sub f [:profile/data]))]
          (settle-fail! f (:on-failure (req-by-method+url @lowered :delete "/profiles/bob/follow")))
          (is (= [false true] [flipped (:following (sub f [:profile/data]))]))))))

  (testing "the follow toggle is serialised: a second intent while one is in flight
            changes nothing and sends nothing, and either settle releases the latch"
    (with-held-fx
      (fn [f lowered]
        (let [following #(:following (sub f [:profile/data]))
              sent      #(count (follow-requests @lowered "alice"))
              latched   #(sub f [:profile/follow-pending?])]
          (open-profile! f lowered "alice" false)
          (rf/dispatch-sync [:profile/follow] {:frame f})
          (let [before (sub f [:profile/slice])]
            (rf/dispatch-sync [:profile/unfollow] {:frame f})
            (is (= [before 1 true] [(sub f [:profile/slice]) (sent) (latched)])))
          (settle-ok! f (:on-success (req-by-method+url @lowered :post "/profiles/alice/follow"))
                      {:profile (full-profile "alice" true)})
          (is (false? (latched)))
          (rf/dispatch-sync [:profile/unfollow] {:frame f})
          (rf/dispatch-sync [:profile/follow] {:frame f})
          (is (= [false 2 true] [(following) (sent) (latched)]))
          (settle-fail! f (:on-failure (req-by-method+url @lowered :delete "/profiles/alice/follow")))
          (is (= [true false] [(following) (latched)]))))))

  (testing "the latch belongs to the mutation, so it survives a walk away and back"
    (with-held-fx
      (fn [f lowered]
        (open-profile! f lowered "alice" false)
        (rf/dispatch-sync [:profile/follow] {:frame f})
        (let [a-follow (req-by-method+url @lowered :post "/profiles/alice/follow")]
          (open-profile! f lowered "bob" false)
          (open-profile! f lowered "alice" true)
          (rf/dispatch-sync [:profile/unfollow] {:frame f})
          (is (= [true true 1]
                 [(:following (sub f [:profile/data])) (sub f [:profile/follow-pending?])
                  (count (follow-requests @lowered "alice"))]))
          (settle-ok! f (:on-success a-follow) {:profile (full-profile "alice" true)})
          (rf/dispatch-sync [:profile/unfollow] {:frame f})
          (is (= [false 2] [(:following (sub f [:profile/data])) (count (follow-requests @lowered "alice"))]))))))

  (testing "the latch is keyed by username: a bystander's button stays live, and each
            settle releases its own profile alone"
    (with-held-fx
      (fn [f lowered]
        (open-profile! f lowered "alice" false)
        (rf/dispatch-sync [:profile/follow] {:frame f})
        (let [a-follow (req-by-method+url @lowered :post "/profiles/alice/follow")]
          (open-profile! f lowered "bob" false)
          (is (= [#{"alice"} false] [(follow-pending f) (sub f [:profile/follow-pending?])]))
          (rf/dispatch-sync [:profile/follow] {:frame f})
          (settle-ok! f (:on-success a-follow) {:profile (full-profile "alice" true)})
          (is (= #{"bob"} (follow-pending f))))
        (open-profile! f lowered "carol" false)
        (rf/dispatch-sync [:profile/follow] {:frame f})
        (settle-fail! f (:on-failure (req-by-method+url @lowered :post "/profiles/bob/follow")))
        (is (= [#{"carol"} true] [(follow-pending f) (sub f [:profile/follow-pending?])]))))))

(deftest realworld-toggle-latches-favorite-and-byline-follow
  (testing "the favourite is serialised per slug: a second click while one is in flight
            changes nothing and sends nothing, the reply re-seeds the row, and either
            settle releases the slug"
    (with-held-fx
      (fn [f lowered]
        (visit! f "/article/hello")
        (settle-article! f lowered "hello")
        (let [row     #((juxt :favorited :favoritesCount) (sub f [:article/data]))
              sent    #(count (filter (fn [r] (str/ends-with? (get-in r [:request :url])
                                                              "/articles/hello/favorite"))
                                      @lowered))
              latched #(sub f [:article/favorite-pending? "hello"])]
          (rf/dispatch-sync [:article/toggle-favorite "hello"] {:frame f})
          (let [before (sub f [:article/slice])]
            (rf/dispatch-sync [:article/toggle-favorite "hello"] {:frame f})
            (is (= [[true 1] before 1 true] [(row) (sub f [:article/slice]) (sent) (latched)])))
          (settle-ok! f (:on-success (req-by-method+url @lowered :post "/articles/hello/favorite"))
                      {:article (assoc (full-article "hello") :favorited true :favoritesCount 42)})
          (is (= [[true 42] false] [(row) (latched)]))
          (rf/dispatch-sync [:article/toggle-favorite "hello"] {:frame f})
          (is (= [[false 41] 2 true] [(row) (sent) (latched)]))
          (settle-fail! f (:on-failure (req-by-method+url @lowered :delete "/articles/hello/favorite")))
          (is (= [[true 42] false] [(row) (latched)]))))))

  (testing "the byline's Follow shares the banner's latch: a follow held from either page
            refuses the other, and its settle releases it wherever the reader is"
    (with-held-fx
      (fn [f lowered]
        (let [sent #(count (follow-requests @lowered "alice"))]
          (open-profile! f lowered "alice" false)
          (rf/dispatch-sync [:profile/follow] {:frame f})
          (visit! f "/article/alpha")
          (settle-article! f lowered "alpha" "alice" false)
          (rf/dispatch-sync [:article/toggle-follow-author] {:frame f})
          (is (= [false 1 true]
                 [(:following (sub f [:article/author])) (sent) (sub f [:article/author-follow-pending?])]))
          (settle-ok! f (:on-success (req-by-method+url @lowered :post "/profiles/alice/follow"))
                      {:profile (full-profile "alice" true)})
          (is (= [#{} false] [(follow-pending f) (sub f [:article/author-follow-pending?])]))
          (rf/dispatch-sync [:article/toggle-follow-author] {:frame f})
          (is (= [true 2 #{"alice"}] [(:following (sub f [:article/author])) (sent) (follow-pending f)]))
          (visit! f "/profile/alice")
          (is (true? (sub f [:profile/follow-pending?])))
          (let [banner (:following (sub f [:profile/data]))]
            (settle-fail! f (:on-failure (req-by-method+url @lowered :post "/profiles/alice/follow")))
            (is (= [#{} banner] [(follow-pending f) (:following (sub f [:profile/data]))]))))))))

(defn- backend-comments
  [slug]
  (:comments
    (:ok (second (demo/transition @rh/demo-state
                                  {:request {:method :get
                                             :url    (rh/full-url (str "/articles/" slug "/comments"))}})))))

(deftest realworld-production-seam-receipt-a-comment-survives-a-later-load
  (testing "against the app's own demo backend, a posted comment is still there when
            the page loads its comments again"
    (async done
      (reset! rh/demo-state (demo/fresh-state))
      (let [f        (rf.frame/make-anon-frame-record! {})
            _        (rf.http.stub-transport/install-transport! f rh/demo-transport)
            _        (rf/dispatch-sync [:app/initialise] {:frame f})
            slug     "hello-conduit"
            comments #(sub f [:comments/data])
            loaded?  #(= :loaded (sub f [:comments/status]))]
        (rf/dispatch-sync [:auth/store-session {:email "demo@conduit.dev" :token "stub.demo.jwt"
                                                :username "demo" :bio "Canned demo user." :image ""}]
                          {:frame f})
        (visit! f (str "/article/" slug))
        (-> (rf.test-support/poll-until loaded? {:label "the route's comments load settles"})
            (.then (fn [_]
                     (is (= (backend-comments slug) (comments)))
                     (post-comment! f "great read")
                     (rf.test-support/poll-until
                       #(= 1000 (:id (second (comments))))
                       {:label "the POST settles and the saved comment replaces the optimistic card"})))
            (.then (fn [_]
                     (rf/dispatch-sync [:comments/load {:params {:slug slug}}] {:frame f})
                     (rf.test-support/poll-until loaded? {:label "the later load settles"})))
            (.then (fn [_]
                     (let [cs (comments)]
                       (is (= [[[1 1000] "great read" "demo"] (backend-comments slug)]
                              [[(mapv :id cs) (:body (second cs)) (-> cs second :author :username)]
                               cs])))))
            (.catch (fn [e]
                      (is false)
                      nil))
            (.then (fn [_] (done))))))))
