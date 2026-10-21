(ns re-frame.realworld-resources-cljs-test
  "Resource-backed RealWorld contracts: principal scopes, ownership, auth, stale replies and persistent backend writes."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest testing use-fixtures is async]]
            [re-frame.core :as rf]
            [re-frame.privacy :as rf.privacy]
            [re-frame.fx :as rf.fx]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.source-store :as rf.source-store]
            [re-frame.integration-adapter :as rf.integration-adapter]
            [re-frame.test-support :as rf.test-support]
            [re-frame.schemas.malli]
            [malli.core :as m]
            [re-frame.views]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.stub-transport :as rf.http.stub-transport]
            [re-frame.resources]
            [re-frame.resources.route :as rf.resources.route]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.test-support]
            [re-frame.routing :as rf.routing]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [realworld-resources.core :as core]
            [realworld-resources.auth :as auth]
            [realworld-resources.scope]
            [realworld-resources.routing]
            [realworld-resources.http :as app-http]
            [realworld-shared.demo-backend :as demo]
            [realworld-shared.schema :as ws]
            [realworld-resources.schema :as app-schema])
  (:require-macros [re-frame.core :refer [with-new-frame]]
                   [re-frame.test-support :refer [with-trace-recorder!]]))

(def ^:private last-managed-args (atom nil))

(def ^:private managed-args-log (atom []))

(def ^:private resource-kind-snapshots
  (into {} (for [kind [:resource :mutation :resource-scope]
                :let [id->desc (rf.registrar/registrations kind)]
                :when (seq id->desc)]
            [kind id->desc])))

(swap! rf.source-store/kind->id->ns->descriptor
       (fn [reg]
         (reduce (fn [r [kind id->meta]]
                   (update r kind (fn [m] (apply dissoc m (keys id->meta)))))
                 reg
                 resource-kind-snapshots)))

(defn- init!
  []
  (reset! last-managed-args nil)
  (reset! managed-args-log [])
  (doseq [[kind id->meta] resource-kind-snapshots
          [id meta] id->meta]
    (rf.registrar/register! kind id meta))
  (rf.routing/reset-counters!)
  (rf.resources.route/install-routing-integration!)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args]
                                (reset! last-managed-args args)
                                (swap! managed-args-log conj args)
                                nil))
  (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "realworld-resources default app frame."}))

(def ^:private isolate-trace-bus-fixture
  "OUTER fixture (listed first): clear trace listeners and rings around each
   test, so the reset fixture's per-test burst of frameless
   `:rf.registry/handler-cleared` traces never reaches a collector a tooling test
   left registered. Map-form, like the reset fixture, because cljs.test runs an
   `(async done …)` row only when every :each fixture is a map."
  {:before (fn []
             (rf.trace.tooling/clear-listeners!)
             (rf.trace.tooling/clear-trace-rings!))
   :after  (fn []
             (rf.trace.tooling/clear-listeners!)
             (rf.trace.tooling/clear-trace-rings!))})

(use-fixtures :each
  isolate-trace-bus-fixture
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.integration-adapter/adapter
     :init-fn init!
     :async?  true
     :app-ns  "realworld-resources."}))

(defn- runtime-db [frame-id] (:rf.db/runtime (rf/frame-state-value frame-id)))

(defn- entry [frame-id scoped-key]
  (get-in (runtime-db frame-id) (rf.resources.state/entry-path scoped-key)))

(defn- entries-for
  [frame resource-id]
  (into [] (comp (map val) (filter #(= resource-id (second (:resource/key %)))))
        (get-in (runtime-db frame) (rf.resources.state/entries-path))))

(defn- stale? [e]
  (or (contains? #{:loading :fetching} (:status e)) (some? (:invalidated-at e))))

(defn- viewer-scope [username] [:rf.scope/viewer {:username username}])

(def ^:private anon-viewer-scope [:rf.scope/viewer :anonymous])

(defn- article-key [slug]
  (rf.resources.state/scoped-resource-key (viewer-scope "alice") :realworld/article {:slug slug}))

(defn- profile-key [subject]
  (rf.resources.state/scoped-resource-key (viewer-scope "alice") :realworld/profile {:username subject}))

(defn- profile-list-key
  [resource-id subject]
  (rf.resources.state/scoped-resource-key (viewer-scope "alice") resource-id {:username subject :page 1}))

(defn- comments-key [viewer slug]
  (rf.resources.state/scoped-resource-key (viewer-scope viewer) :realworld/comments {:slug slug}))

(defn- articles-list-key
  [scope]
  (rf.resources.state/scoped-resource-key scope :realworld/articles {:tag nil :page 1}))

(defn- feed-key [username page]
  (rf.resources.state/scoped-resource-key [:rf.scope/session {:username username}] :realworld/feed {:page page}))

(defn- tags-key []
  (rf.resources.state/scoped-resource-key :rf.scope/global :realworld/tags {}))

(defn- reply-success!
  [args data frame]
  (rf/dispatch-sync (conj (:on-success args) {:status :ok :value data}) {:frame frame}))

(defn- reply-failure! [args error frame]
  (rf/dispatch-sync (conj (:on-failure args) {:status :error :error error}) {:frame frame}))

(def ^:private http-500 {:kind :rf.http/http-5xx :http-status 500})

(defn- managed-request-for
  [url-fragment]
  (first (filter (fn [args] (str/includes? (str (get-in args [:request :url])) url-fragment))
                 @managed-args-log)))

(defn- feed-requests
  []
  (filterv #(str/includes? (str (get-in % [:request :url])) "/articles/feed")
           @managed-args-log))

(defn- state-value [frame] (rf/frame-state-value frame))

(defn- route-id [frame] (rf/compute-sub [:rf.route/id] (state-value frame)))
(defn- route-params [frame] (rf/compute-sub [:rf.route/params] (state-value frame)))
(defn- route-query [frame] (rf/compute-sub [:rf.route/query] (state-value frame)))
(defn- route-fragment [frame] (rf/compute-sub [:rf.route/fragment] (state-value frame)))
(defn- return-to [frame] (get-in (rf/app-db-value frame) [:auth :return-to]))

(defn- slice
  [frame]
  (get-in (runtime-db frame) [:rf.runtime/routing :current]))

(defn- route-owner?
  [entry route-id*]
  (boolean (some (fn [o] (and (vector? o)
                              (= :route (first o))
                              (= route-id* (second o))))
                 (:active-owners entry))))

(defn- gc-recheck!
  [frame scoped-key]
  (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key scoped-key}]
                    {:frame frame}))

(defn- app-frame!
  "An app frame beside the fixture's `:rf/default`, which owns the URL."
  []
  (rf.frame/make-anon-frame-record!
    {:fx-overrides {:rf.nav/push-url :rf/no-op
                    :realworld-resources.session/persist :rf/no-op}}))

(defn- auth-frame!
  []
  (rf.frame/make-anon-frame-record!
    {:fx-overrides {:realworld-resources.session/persist :rf/no-op}}))

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
  [f email]
  (rf/dispatch-sync [:auth.login-form/initialise] {:frame f})
  (rf/dispatch-sync [:auth.login-form/edit-field :email email] {:frame f})
  (rf/dispatch-sync [:auth.login-form/edit-password {:value "pw"}] {:frame f})
  (rf/dispatch-sync [:auth.login-form/submit] {:frame f}))

(def ^:private new-draft {:title "New Title" :description "A desc" :body "Some body"})

(defn- fill-draft! [f fields]
  (doseq [[field value] fields]
    (rf/dispatch-sync [:editor/edit-field field value] {:frame f})))

(defn- open-doomed-editor!
  [f]
  (reset! last-managed-args nil)
  (rf/dispatch-sync [:rf.route/navigate {:to :realworld.editor/edit :params {:slug "doomed"}}]
                    {:frame f})
  (reply-success! @last-managed-args
                  {:article {:slug "doomed" :title "Doomed" :description "d"
                             :body "b" :tagList []}}
                  f))

(deftest bearer-interceptor-injects-token-when-authed-and-noops-when-logged-out
  (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
    (let [auth-header #(get-in (core/bearer-auth-interceptor {:frame f :request {:url "/articles"}})
                               [:request :headers "Authorization"])]
      (is (nil? (auth-header)))
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt-xyz"}] {:frame f})
      (is (= "Token jwt-xyz" (auth-header))))))

(defn- vec-map-slot-props
  [map-schema slot-key]
  (some (fn [entry]
          (when (and (vector? entry) (= slot-key (first entry)) (map? (second entry)))
            (second entry)))
        (rest map-schema)))

(deftest durable-auth-user-validates-token-free-wire-user-still-requires-token
  (is (false? (m/validate ws/UserResponse
                          {:user {:email "alice@example.com" :username "alice"
                                  :bio nil :image nil}})))
  (is (true? (:sensitive? (vec-map-slot-props ws/User :token))))
  (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
    (rf/reg-app-schema [:auth] app-schema/AuthSlice)
    (rf/dispatch-sync [:auth/store-session {:email "alice@example.com" :username "alice"
                                            :token "jwt-abc" :bio nil :image nil}]
                      {:frame f})
    (is (= {:user  {:email "alice@example.com" :username "alice" :bio nil :image nil}
            :token "jwt-abc"}
           (:auth (rf/app-db-value f))))))

(deftest store-session-event-token-redacts-at-its-arg-map-path
  (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "JWT-RESOURCES-SENTINEL-42d1"}]
                        {:frame f})
      (let [args (->> @traces
                      (keep #(get-in % [:tags :rf.event/v]))
                      (filter #(= :auth/store-session (first %)))
                      (map second))]
        (is (= [true true]
               [(boolean (seq args))
                (every? #(= {:username "alice" :token rf.privacy/redacted-sentinel}
                      (select-keys % [:username :token]))
                  args)]))))))

(deftest favorite-populates-the-detail-and-invalidates-both-scopes
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (let [owned [[:realworld/feed {:page nil} (feed-key "alice" nil)]
                 [:realworld/articles {:tag nil :page 1} (articles-list-key (viewer-scope "alice"))]]]
      (doseq [[resource params] owned]
        (rf/dispatch-sync [:rf.resource/ensure {:resource resource :params params :owner [:app resource]}]
                          {:frame f})
        (reply-success! @last-managed-args {:articles [{:slug "hello-conduit"}] :articlesCount 1} f))
      (is (= [false false] (mapv #(stale? (entry f (peek %))) owned)))
      (rf/dispatch-sync [:rf.mutation/execute {:mutation :realworld/favorite
                                               :params   {:slug "hello-conduit"}
                                               :instance :test/fav
                                               :cause    [:test :fav]}]
                        {:frame f})
      (reply-success! @last-managed-args
                      {:article {:slug "hello-conduit" :title "Hello, Conduit"
                                 :favorited true :favoritesCount 1}}
                      f)
      (is (= [true [true true]]
             [(true? (-> (entry f (article-key "hello-conduit")) :data :article :favorited))
              (mapv #(stale? (entry f (peek %))) owned)])))))

(deftest unfavorite-optimistic-patch-clamps-count-at-zero
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (rf/dispatch-sync [:rf.resource/ensure {:resource :realworld/article :params {:slug "hello-conduit"}
                                            :owner [:app :test/detail]}]
                      {:frame f})
    (reply-success! @last-managed-args
                    {:article {:slug "hello-conduit" :title "Hello, Conduit"
                               :favorited true :favoritesCount 0}}
                    f)
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :realworld/unfavorite
                                             :params   {:slug "hello-conduit" :username "alice"}
                                             :instance [:favorite "hello-conduit"]
                                             :cause    [:test :unfav]}]
                      {:frame f})
    (is (= {:favorited false :favoritesCount 0}
           (select-keys (-> (entry f (article-key "hello-conduit")) :data :article)
                        [:favorited :favoritesCount])))))

(deftest follow-and-unfollow-populate-the-profile-banner-from-the-reply
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (doseq [[mutation following?] [[:realworld/follow true] [:realworld/unfollow false]]]
      (rf/dispatch-sync [:rf.mutation/execute {:mutation mutation :params {:username "eve"}
                                               :instance [:follow "eve"] :cause [:test mutation]}]
                        {:frame f})
      (reply-success! @last-managed-args
                      {:profile {:username "eve" :bio "" :image "" :following following?}} f)
      (is (= following? (-> (entry f (profile-key "eve")) :data :profile :following))))))

(deftest follow-and-unfollow-restale-the-session-feed
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld/home :query {:feed "following"}}] {:frame f})
    (reply-success! (last (feed-requests)) {:articles [] :articlesCount 0} f)
    (is (= :loaded (:status (entry f (feed-key "alice" 1)))))
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.profile/show :params {:username "eve"}}]
                      {:frame f})
    (rf/dispatch-sync [:ui/follow "eve" false] {:frame f})
    (reply-success! (managed-request-for "/profiles/eve/follow")
                    {:profile {:username "eve" :bio "" :image "" :following true}} f)
    (let [seen (count (feed-requests))]
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld/home :query {:feed "following"}}] {:frame f})
      (is (= 1 (- (count (feed-requests)) seen))))))

(deftest follow-author-continuation-restales-the-detail-article
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (rf/dispatch-sync [:rf.resource/ensure {:resource :realworld/article :params {:slug "hello-conduit"}
                                            :owner [:app :test/detail]}]
                      {:frame f})
    (reply-success! @last-managed-args
                    {:article {:slug "hello-conduit" :title "Hello"
                               :author {:username "eve" :following false}}}
                    f)
    (is (= :loaded (:status (entry f (article-key "hello-conduit")))))
    (rf/dispatch-sync [:ui/follow-author "hello-conduit" "eve" false] {:frame f})
    (reply-success! @last-managed-args {:profile {:username "eve" :following true}} f)
    (is (stale? (entry f (article-key "hello-conduit"))))))

(deftest delete-comment-invalidates-the-comments-read
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (rf/dispatch-sync [:rf.resource/ensure {:resource :realworld/comments :params {:slug "hello-conduit"}
                                            :owner [:app :test/comments]}]
                      {:frame f})
    (reply-success! @last-managed-args {:comments [{:id 2 :body "hi" :author {:username "eve"}}]} f)
    (is (= :loaded (:status (entry f (comments-key "alice" "hello-conduit")))))
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :realworld/delete-comment
                                             :params   {:slug "hello-conduit" :id 2}
                                             :instance [:delete-comment "hello-conduit" 2]
                                             :cause    [:test :del]}]
                      {:frame f})
    (reply-success! @last-managed-args {} f)
    (is (stale? (entry f (comments-key "alice" "hello-conduit"))))))

(deftest article-writes-invalidate-the-global-tag-list
  (doseq [[operation params result]
          [[:realworld/save-article
            {:title "New" :description "New article" :body "Body" :tagList ["new-tag"]}
            {:article {:slug "new" :author {:username "alice"}}}]
           [:realworld/delete-article {:slug "new"} {}]]]
    (testing (str operation)
      (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
        (rf/dispatch-sync [:auth/store-session {:username "alice" :email "a@b.c"
                                                :token "jwt" :bio nil :image nil}] {:frame f})
        (rf/dispatch-sync [:rf.resource/ensure {:resource :realworld/tags :params {}
                                                :cause :test}] {:frame f})
        (reply-success! @last-managed-args {:tags ["old-tag"]} f)
        (rf/dispatch-sync [:rf.mutation/execute {:mutation operation :params params
                                                 :instance :test/article-write :cause :test}] {:frame f})
        (reply-success! @last-managed-args result f)
        (is (some? (:invalidated-at (entry f (tags-key)))))))))

(deftest editor-gate-opens-on-a-valid-dirty-draft-and-reply-to-navigates-to-the-saved-article
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:editor/initialise] {:frame f})
    (is (false? (rf/compute-sub [:editor/can-submit?] (state-value f))))
    (fill-draft! f new-draft)
    (is (= [true false] [(rf/compute-sub [:editor/can-submit?] (state-value f))
                         (rf/compute-sub [:editor/can-leave?] (state-value f))]))
    (rf/dispatch-sync [:editor/submit] {:frame f})
    (reply-success! @last-managed-args {:article (assoc new-draft :slug "new-title" :tagList [])} f)
    (is (= [true :realworld.article/show] [(rf/compute-sub [:editor/can-leave?] (state-value f))
                                           (route-id f)]))))

(deftest editor-save-parses-its-tag-list-exactly-once
  (doseq [[tags expected] [["" []] ["clojure, SPA" ["clojure" "SPA"]]]]
    (with-new-frame [f (app-frame!)]
      (rf/dispatch-sync [:editor/initialise] {:frame f})
      (fill-draft! f (assoc new-draft :tagList tags))
      (rf/dispatch-sync [:editor/submit] {:frame f})
      (is (= (assoc new-draft :tagList expected)
             (get-in @last-managed-args [:request :body :article]))))))

(deftest editor-edit-load-seeds-the-draft-and-edit-to-new-blanks-it
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.editor/edit :params {:slug "hello-conduit"}}]
                      {:frame f})
    (reply-success! @last-managed-args
                    {:article {:slug "hello-conduit" :title "Hello, Conduit" :description "A desc"
                               :body "Some body" :tagList ["clojure" "SPA"]}}
                    f)
    (is (= [{:title "Hello, Conduit" :description "A desc" :body "Some body" :tagList "clojure, SPA"}
            false]
           [(rf/compute-sub [:editor/draft] (state-value f)) (rf/compute-sub [:editor/dirty?] (state-value f))]))
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.editor/new}] {:frame f})
    (is (nil? (rf/compute-sub [:editor/slug] (state-value f))))))

(deftest editor-edit-then-navigate-to-unrelated-route-releases-the-route-owned-read
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (open-doomed-editor! f)
    (is (route-owner? (entry f (article-key "doomed")) :realworld.editor/edit))
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld/home}] {:frame f})
    (gc-recheck! f (article-key "doomed"))
    (is (nil? (entry f (article-key "doomed"))))))

(deftest editor-delete-clears-the-slice-and-navigates-home
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (open-doomed-editor! f)
    (rf/dispatch-sync [:editor/delete] {:frame f})
    (reply-success! @last-managed-args {} f)
    (is (= [nil :realworld/home] [(rf/compute-sub [:editor/slug] (state-value f)) (route-id f)]))))

(deftest editor-late-cross-slug-reply-does-not-clobber-the-current-draft
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.editor/edit :params {:slug "article-a"}}]
                      {:frame f})
    (let [a-read @last-managed-args]
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld.editor/edit :params {:slug "article-b"}}]
                        {:frame f})
      (reply-success! @last-managed-args
                      {:article {:slug "article-b" :title "Bee Article" :description "about b"
                                 :body "body b" :tagList ["bee"]}}
                      f)
      (reply-success! a-read
                      {:article {:slug "article-a" :title "Ay Article" :description "about a"
                                 :body "body a" :tagList ["ay"]}}
                      f)
      (is (= {:slug  "article-b"
              :draft {:title "Bee Article" :description "about b" :body "body b" :tagList "bee"}}
             (select-keys (rf/compute-sub [:editor/slice] (state-value f)) [:slug :draft]))))))

(deftest editor-same-slug-seed-does-not-clobber-typed-fields
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.editor/edit :params {:slug "article-a"}}]
                      {:frame f})
    (let [a-read @last-managed-args]
      (rf/dispatch-sync [:editor/edit-field :title "My unsaved heading"] {:frame f})
      (reply-success! a-read
                      {:article {:slug "article-a" :title "Ay Article" :description "about a"
                                 :body "body a" :tagList ["ay"]}}
                      f)
      (is (= {:slug     "article-a"
              :draft    {:title "My unsaved heading" :description "about a" :body "body a" :tagList "ay"}
              :baseline {:title "" :description "about a" :body "body a" :tagList "ay"}
              :touched  #{:title}}
             (select-keys (rf/compute-sub [:editor/slice] (state-value f))
                          [:slug :draft :baseline :touched]))))))

(defn- delete-alpha-then-walk-to!
  [f detour]
  (rf/dispatch-sync [:rf.route/navigate {:to :realworld.article/show :params {:slug "alpha"}}]
                    {:frame f})
  (rf/dispatch-sync [:ui/delete-article "alpha"] {:frame f})
  (let [del @last-managed-args]
    (is (= :delete (get-in del [:request :method])))
    (when detour
      (rf/dispatch-sync [:rf.route/navigate detour] {:frame f}))
    del))

(defn- alpha-delete-status [f]
  (:status (rf/compute-sub [:rf/mutation {:instance [:delete-article "alpha"]}] (state-value f))))

(deftest delete-article-continuation-navigates-home-only-from-its-own-page
  (doseq [[detour answer expected]
          [[nil :ok [:realworld/home nil nil :idle]]
           [{:to :realworld.article/show :params {:slug "beta"}} :ok
            [:realworld.article/show "beta" nil :idle]]
           [{:to :realworld.profile/show :params {:username "eve"}} :ok
            [:realworld.profile/show nil "eve" :idle]]
           [nil :error [:realworld.article/show "alpha" nil :error]]]]
    (testing (str (pr-str detour) " then " answer)
      (with-new-frame [f (app-frame!)]
        (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
        (let [del (delete-alpha-then-walk-to! f detour)]
          (case answer
            :ok    (reply-success! del {} f)
            :error (reply-failure! del http-500 f))
          (is (= expected (let [p (route-params f)]
                            [(route-id f) (:slug p) (:username p) (alpha-delete-status f)]))))))))

(defn- editor-instance [f]
  (rf/compute-sub [:editor/save-instance] (state-value f)))

(defn- mutation-at [f instance]
  (rf/compute-sub [:rf/mutation {:instance instance}] (state-value f)))

(defn- editor-save [f]
  (mutation-at f (editor-instance f)))

(defn- cache-home-list!
  [f]
  (reset! last-managed-args nil)
  (rf/dispatch-sync [:rf.resource/ensure {:resource :realworld/articles
                                          :params   {:tag nil :page 1}
                                          :cause    :test}]
                    {:frame f})
  (reply-success! @last-managed-args {:articles [] :articlesCount 0} f)
  (articles-list-key (viewer-scope "alice")))

(deftest editor-write-continuations-stay-with-the-page-that-issued-them
  (testing "a dirty draft's :can-leave parks the navigation, and a save answered
            after the reader confirmed the leave neither navigates nor re-seeds"
    (with-new-frame [f (app-frame!)]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld.editor/new}] {:frame f})
      (fill-draft! f new-draft)
      (rf/dispatch-sync [:editor/submit] {:frame f})
      (let [save @last-managed-args]
        (is (= :post (get-in save [:request :method])))
        (rf/dispatch-sync [:rf.route/navigate {:to :realworld.profile/show :params {:username "eve"}}]
                          {:frame f})
        (let [pending (rf/compute-sub [:rf/pending-navigation] (state-value f))]
          (is (some? pending))
          (rf/dispatch-sync [:rf.route/continue (:id pending)] {:frame f}))
        (reply-success! save {:article (assoc new-draft :slug "new-title" :tagList [])} f)
        (is (= [:realworld.profile/show nil]
               [(route-id f) (rf/compute-sub [:editor/slug] (state-value f))])))))
  (testing "entering a new editor cancels nothing: the old delete's late reply
            runs its :invalidates, is refused by the nav-token gate, and retires
            its own instance. Clearing it on entry would abort the delete and
            leave a cached list serving the deleted article"
    (with-new-frame [f (app-frame!)]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      (let [list-key (cache-home-list! f)]
        (is (not (stale? (entry f list-key))))
        (open-doomed-editor! f)
        (let [old-instance (editor-instance f)]
          (rf/dispatch-sync [:editor/delete] {:frame f})
          (let [del @last-managed-args]
            (is (true? (:pending? (mutation-at f old-instance))))
            (rf/dispatch-sync [:rf.route/navigate {:to :realworld.editor/new}] {:frame f})
            (reply-success! del {} f)
            (is (= [:realworld.editor/new true :idle]
                   [(route-id f) (stale? (entry f list-key)) (:status (mutation-at f old-instance))]))))))))

(deftest editor-same-slug-return-is-a-new-session-an-old-write-cannot-touch
  (doseq [answer [:ok :error]]
    (testing (str "the old delete answers " answer)
      (with-new-frame [f (app-frame!)]
        (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
        (let [list-key (cache-home-list! f)]
          (is (= :loaded (:status (entry f list-key))))
          (open-doomed-editor! f)
          (let [old-instance (editor-instance f)]
            (rf/dispatch-sync [:editor/delete] {:frame f})
            (let [del @last-managed-args]
              (is (= :delete (get-in del [:request :method])))
              (rf/dispatch-sync [:rf.route/navigate {:to :realworld.profile/show :params {:username "eve"}}]
                                {:frame f})
              (rf/dispatch-sync [:rf.route/navigate {:to :realworld.editor/edit :params {:slug "doomed"}}]
                                {:frame f})
              (is (= ["doomed" false] [(rf/compute-sub [:editor/slug] (state-value f))
                                       (boolean (:pending? (editor-save f)))]))
              (fill-draft! f {:title "Doomed, revised" :description "d2" :body "b2"})
              (rf/dispatch-sync [:editor/submit] {:frame f})
              (case answer
                :ok    (reply-success! del {} f)
                :error (reply-failure! del http-500 f))
              (is (= [true :idle (= :ok answer)]
                     [(:pending? (editor-save f)) (:status (mutation-at f old-instance))
                      (stale? (entry f list-key))])))))))))

(deftest editor-current-page-save-failure-stays-on-its-instance
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.editor/new}] {:frame f})
    (fill-draft! f new-draft)
    (rf/dispatch-sync [:editor/submit] {:frame f})
    (reply-failure! @last-managed-args http-500 f)
    (is (= [:realworld.editor/new :error] [(route-id f) (:status (editor-save f))]))))

(deftest logout-clears-both-principal-scopes-and-leaves-global-tags
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (doseq [[ensure data] [[{:resource :realworld/feed :params {:page 1}}
                            {:articles [{:slug "x"}] :articlesCount 1}]
                           [{:resource :realworld/article :params {:slug "hello-conduit"}}
                            {:article {:slug "hello-conduit" :title "Hi" :favorited true}}]
                           [{:resource :realworld/tags :params {}}
                            {:tags ["clojure" "conduit"]}]]]
      (rf/dispatch-sync [:rf.resource/ensure (assoc ensure :owner [:app (:resource ensure)])] {:frame f})
      (reply-success! @last-managed-args data f))
    (let [present #(mapv (fn [k] (some? (entry f k)))
                         [(feed-key "alice" 1) (article-key "hello-conduit") (tags-key)])]
      (is (= [true true true] (present)))
      (rf/dispatch-sync [:auth/clear-session] {:frame f})
      (is (= [nil [false false true]]
             [(get-in (rf/app-db-value f) [:auth :user])
              (present)])))))

(deftest auth-machine-login-stores-the-session
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.auth/login}] {:frame f})
    (init-auth! f nil)
    (submit-login! f "alice@example.com")
    (reply-success! @last-managed-args
                    {:user {:username "alice" :email "alice@example.com" :token "jwt"}}
                    f)
    (is (= [:authed "alice" :realworld/home]
           [(rf/compute-sub [:auth/state] (state-value f))
            (:username (rf/compute-sub [:auth/user] (state-value f)))
            (route-id f)]))))

(deftest session-load-seam-reads-storage-through-an-effect
  (is (= [[:token]] (:sensitive (rf.registrar/handler-meta :event :auth/session-read))))
  (is (nil? (with-local-storage #js {:configurable true
                                     :get          (fn [] (throw (js/Error. "storage is blocked")))}
              auth/read-saved-token)))
  (with-new-frame [f (auth-frame!)]
    (init-auth! f nil)
    (rf/dispatch-sync [:auth/session-read {:generation (dec (:auth-generation (rf/app-db-value f)))
                                           :token      "jwt-stale"}]
                      {:frame f})
    (is (nil? (get-in (rf/app-db-value f) [:auth :token])))))

(deftest a-restore-reply-that-outlives-its-session-is-dropped
  (with-new-frame [f (auth-frame!)]
    (init-auth! f "jwt-alice")
    (is (= :restoring (rf/compute-sub [:auth/state] (state-value f))))
    (let [restore-req @last-managed-args]
      (submit-login! f "bob@example.com")
      (reply-success! @last-managed-args
                      {:user {:username "bob" :email "bob@example.com" :token "jwt-bob"}}
                      f)
      (reply-success! restore-req
                      {:user {:username "alice" :email "alice@example.com" :token "jwt-alice"}}
                      f)
      (is (= ["bob" "jwt-bob"] [(get-in (rf/app-db-value f) [:auth :user :username])
                                (get-in (rf/app-db-value f) [:auth :token])])))))

(deftest logged-out-home-plans-articles-and-tags-and-not-the-feed
  (with-new-frame [f (app-frame!)]
    (init-auth! f nil)
    (is (= :idle (rf/compute-sub [:auth/state] (state-value f))))
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld/home}] {:frame f})
    (is (= [nil true true []]
           [(:error (slice f))
            (some? (entry f (articles-list-key anon-viewer-scope)))
            (some? (entry f (tags-key)))
            (entries-for f :realworld/feed)]))
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld/home :query {:feed "following"}}] {:frame f})
    (is (= :rf.error/resource-route-plan (:rf.error/id (:error (slice f)))))))

(deftest session-restore-success-replans-the-following-feed-deep-link-under-the-viewer
  (with-new-frame [f (app-frame!)]
    (init-auth! f "jwt-restore")
    (is (= :restoring (rf/compute-sub [:auth/state] (state-value f))))
    (let [restore-req @last-managed-args]
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld/home :query {:feed "following"}}]
                        {:frame f})
      (let [nav-token (:nav-token (slice f))]
        (is (= [true [] []]
               [(rf/compute-sub [:auth/viewer-resolving?] (state-value f))
                (entries-for f :realworld/articles)
                (entries-for f :realworld/feed)]))
        (reply-success! restore-req
                        {:user {:username "alice" :email "alice@example.com" :token "jwt-restore"}}
                        f)
        (is (= [[:authed false] [:realworld/home nav-token "following"] [true true true]]
               [[(rf/compute-sub [:auth/state] (state-value f))
                 (rf/compute-sub [:auth/viewer-resolving?] (state-value f))]
                [(route-id f) (:nav-token (slice f)) (:feed (route-query f))]
                (mapv #(some? (entry f %))
                  [(articles-list-key (viewer-scope "alice")) (tags-key) (feed-key "alice" 1)])]))))))

(deftest session-restore-failure-stays-put-and-replans-under-anonymous
  (with-new-frame [f (app-frame!)]
    (init-auth! f "jwt-stale")
    (is (= :restoring (rf/compute-sub [:auth/state] (state-value f))))
    (let [restore-req  @last-managed-args
          anon-article (rf.resources.state/scoped-resource-key anon-viewer-scope :realworld/article
                                                               {:slug "public-post"})]
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld.article/show :params {:slug "public-post"}}]
                        {:frame f})
      (let [nav-token (:nav-token (slice f))]
        (is (nil? (entry f anon-article)))
        (reply-failure! restore-req {:rf.http/status 401} f)
        (is (= [:realworld.article/show nav-token true]
               [(route-id f) (:nav-token (slice f)) (some? (entry f anon-article))]))))))

(deftest optional-auth-representation-is-not-shared-across-viewers
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt-a"}] {:frame f})
    (rf/dispatch-sync [:rf.resource/ensure {:resource :realworld/article :params {:slug "leak-test"}
                                            :owner [:app :test/detail]}]
                      {:frame f})
    (reply-success! @last-managed-args
                    {:article {:slug "leak-test" :title "Leak" :favorited true :favoritesCount 9
                               :author {:username "carol" :following true}}}
                    f)
    (is (true? (-> (entry f (article-key "leak-test")) :data :article :favorited)))
    (rf/dispatch-sync [:auth/store-session {:username "bob" :token "jwt-b"}] {:frame f})
    (is (not (:has-data? (rf/compute-sub [:rf/resource {:resource :realworld/article
                                                        :params   {:slug "leak-test"}}]
                                         (state-value f)))))))

(defn- park-a-settings-save!
  [f username]
  (rf/dispatch-sync [:auth/store-session {:username username :email "a@b.c" :token "jwt"
                                          :bio nil :image nil}] {:frame f})
  (rf/dispatch-sync [:settings/load] {:frame f})
  (rf/dispatch-sync [:settings/edit-field :bio "A brand new bio"] {:frame f})
  (reset! last-managed-args nil)
  (rf/dispatch-sync [:settings/submit] {:frame f})
  (let [args @last-managed-args]
    (is (= :put (get-in args [:request :method])))
    args))

(deftest settings-reply-from-an-old-account-is-refused-after-the-new-one-opens-settings
  (with-new-frame [f (app-frame!)]
    (let [alice-args (park-a-settings-save! f "alice")]
      (rf/dispatch-sync [:auth/clear-session] {:frame f})
      (rf/dispatch-sync [:auth/store-session {:username "bob" :email "b@b.c" :token "bob-jwt"
                                              :bio nil :image nil}] {:frame f})
      (rf/dispatch-sync [:settings/load] {:frame f})
      (reply-success! alice-args
                      {:user {:username "alice" :email "a@b.c" :token "alice-jwt-2"
                              :bio "A brand new bio" :image nil}}
                      f)
      (is (= ["bob" "bob-jwt"] [(get-in (rf/app-db-value f) [:auth :user :username])
                                (get-in (rf/app-db-value f) [:auth :token])])))))

(deftest settings-success-still-lands-after-a-mid-save-detour-back-through-settings
  (with-new-frame [f (app-frame!)]
    (let [alice-args (park-a-settings-save! f "alice")]
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld/home}] {:frame f})
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld.user/settings}] {:frame f})
      (reply-success! alice-args
                      {:user {:username "alice" :email "a@b.c" :token "alice-jwt-2"
                              :bio "A brand new bio" :image nil}}
                      f)
      (is (= ["A brand new bio" :realworld.profile/show {:username "alice"}]
             [(get-in (rf/app-db-value f) [:auth :user :bio]) (route-id f) (route-params f)])))))

(deftest settings-save-persists-the-refreshed-session-token
  (let [persisted (atom [])
        user      {:email "alice@example.com" :username "alice" :token "jwt-new"
                   :bio "Updated" :image nil}]
    (rf/reg-fx :realworld-resources.test/capture-settings-token
      (fn [_ {:keys [token]}] (swap! persisted conj token)))
    (with-new-frame [f (rf.frame/make-anon-frame-record!
                        {:fx-overrides {:realworld-resources.session/persist
                                        :realworld-resources.test/capture-settings-token}})]
      (rf/dispatch-sync [:auth/store-session (assoc user :token "jwt-old")] {:frame f})
      (rf/dispatch-sync [:settings/load] {:frame f})
      (rf/dispatch-sync [:settings/submit] {:frame f})
      (reply-success! @last-managed-args {:user user} f)
      (is (= ["jwt-new" ["jwt-new"]] [(get-in (rf/app-db-value f) [:auth :token]) @persisted])))))

(deftest pagination-nav-events-carry-feed-tag-and-drop-page-1
  (with-new-frame [f (app-frame!)]
    (doseq [[events expected]
            [[[[:home/show-global-feed] [:home/go-to-page 2]] [:realworld/home nil nil nil 2]]
             [[[:home/go-to-page 1]] [:realworld/home nil nil nil nil]]
             [[[:home/show-your-feed] [:home/go-to-page 2]] [:realworld/home nil nil "following" 2]]
             [[[:home/apply-tag "clojure"] [:home/go-to-page 2]] [:realworld/home-tag "clojure" nil nil 2]]
             [[[:home/go-to-page 1]] [:realworld/home-tag "clojure" nil nil nil]]
             [[[:rf.route/navigate {:to :realworld.profile/show :params {:username "eve"}}]
               [:profile/go-to-page 3]]
              [:realworld.profile/show nil "eve" nil 3]]
             [[[:profile/go-to-page 1]] [:realworld.profile/show nil "eve" nil nil]]]]
      (doseq [event events]
        (rf/dispatch-sync event {:frame f}))
      (is (= expected (let [p (route-params f) q (route-query f)]
                        [(route-id f) (:tag p) (:username p) (:feed q) (:page q)]))))))

(deftest auth-guard-return-to-preserves-full-address
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:rf.route/handle-url-change "/editor/my-slug?tab=preview#comments"] {:frame f})
    (is (= :realworld.auth/login (route-id f)))
    (rf/dispatch-sync [:auth/store-session {:username "eve" :token "t"}] {:frame f})
    (rf/dispatch-sync [:auth/post-login-redirect] {:frame f})
    (is (= [:realworld.editor/edit {:slug "my-slug"} {"tab" "preview"} "comments" nil]
           [(route-id f) (route-params f) (route-query f) (route-fragment f) (return-to f)])))
  (testing "a protected deep link while restore is in flight is deferred, not bounced"
    (with-new-frame [f (app-frame!)]
      (init-auth! f "jwt-in-flight")
      (rf/dispatch-sync [:rf.route/handle-url-change "/settings"] {:frame f})
      (is (not= :realworld.auth/login (route-id f)))
      (rf/dispatch-sync [:auth/store-session {:username "eve" :token "jwt-in-flight"}] {:frame f})
      (rf/dispatch-sync [:auth/settle-deferred-entry] {:frame f})
      (is (= [:realworld.user/settings nil] [(route-id f) (return-to f)])))))

(deftest profile-favorites-tab-composes-the-parent-banner-with-its-own-list
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.profile/favorites :params {:username "eve"}}]
                      {:frame f})
    (is (= [[true true] nil true]
           [(mapv #(route-owner? (entry f %) :realworld.profile/favorites)
              [(profile-key "eve") (profile-list-key :realworld/favorited-articles "eve")])
            (entry f (profile-list-key :realworld/author-articles "eve"))
            (some? (managed-request-for "favorited=eve"))]))
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.profile/show :params {:username "eve"}}]
                      {:frame f})
    (is (some? (entry f (profile-list-key :realworld/author-articles "eve"))))))

(def ^:private demo-user
  "The demo world's one user, as `POST /users/login` issues them."
  {:email "demo@conduit.dev" :token "stub.demo.jwt" :username "demo"
   :bio "Canned demo user." :image ""})

(defn- production-seam-frame! []
  ;; The fixture parks `:rf.http/managed` in a capturing stub; this frame runs
  ;; the real one, beneath the app's own demo transport.
  (rf.fx/reg-fx :rf.http/managed rf.http.managed/managed-handler)
  (let [f (rf.frame/make-anon-frame-record!
            {:fx-overrides {:rf.nav/push-url                     :rf/no-op
                            :realworld-resources.session/persist :rf/no-op}})]
    (rf.http.stub-transport/install-transport! f app-http/demo-transport)
    f))

(defn- backend-comments
  [slug]
  (:ok (second (demo/transition @app-http/demo-state
                                {:request {:method :get
                                           :url    (app-http/full-url
                                                     (str "/articles/" slug "/comments"))}}))))

(deftest production-seam-receipt-a-comment-survives-the-refetch-it-causes
  (async done
    (reset! app-http/demo-state (demo/fresh-state))
    (let [f                (production-seam-frame!)
          slug             "hello-conduit"
          k                (comments-key "demo" slug)
          first-generation (atom nil)]
      (rf/dispatch-sync [:auth/store-session demo-user] {:frame f})
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld.article/show :params {:slug slug}}]
                        {:frame f})
      (-> (rf.test-support/poll-until
            #(= :loaded (:status (entry f k)))
            {:label "the route-owned comments read settles from the demo backend"})
          (.then (fn [_]
                   (reset! first-generation (:generation (entry f k)))
                   (rf/dispatch-sync [:comment-form/edit "great read"] {:frame f})
                   (rf/dispatch-sync [:comment-form/submit slug] {:frame f})
                   (rf.test-support/poll-until
                     #(let [e (entry f k)
                            m (rf/compute-sub [:rf/mutation {:instance [:post-comment slug]}]
                                              (state-value f))]
                        (and (true? (:success? m))
                             (> (:generation e) @first-generation)
                             (= :loaded (:status e))))
                     {:label "the write settles, the invalidated read refetches, and the refetch settles"})))
          (.then (fn [_]
                   (let [e (entry f k)]
                     (is (= [(backend-comments slug) "great read" nil]
                            [(:data e)
                             (-> e :data :comments second :body)
                             (:invalidated-at e)])))))
          (.catch (fn [e]
                    (is false)
                    nil))
          (.then (fn [_] (done)))))))
