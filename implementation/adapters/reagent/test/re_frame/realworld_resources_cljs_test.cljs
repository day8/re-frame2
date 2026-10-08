(ns re-frame.realworld-resources-cljs-test
  "Drives the RealWorld-on-resources example (`examples/real-apps/realworld_resources/`)
   through its own wiring: the scope policies, the bearer interceptor, the
   mutations' `:populates` / `:invalidates`, the editor flow and its write
   continuations, the auth machine and session restore, the auth guard, the
   profile tabs' `:parent` branch, and the production-seam receipt against the
   app's own demo backend. The example source stays test-free, so the fixtures
   and the capturing transport stub live here.

   Each test answers managed HTTP by hand through the transport's real reply
   shape (`(conj on-success {:status :ok :value …})`), and url-push is stubbed.
   The production-seam receipt is the exception: it awaits the demo backend's
   deferred replies, which is why the fixtures are map-form (`:async? true`)."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest testing use-fixtures is async]]
            [re-frame.core :as rf]
            [re-frame.privacy :as rf.privacy]
            [re-frame.fx :as rf.fx]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            ;; Activates the Malli validator; without it the CLJS default
            ;; validator soft-passes and the durable AuthSlice test cannot fail.
            [re-frame.schemas.malli]
            [malli.core :as m]
            [re-frame.views]
            [re-frame.http.managed]
            [re-frame.http.test-support]
            [re-frame.resources]
            [re-frame.resources.route :as rf.resources.route]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.test-support]
            [re-frame.routing :as rf.routing]
            [re-frame.trace.tooling :as rf.trace.tooling]
            ;; The example's production source; core chains in every feature ns.
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

;; ============================================================================
;; FIXTURE
;; ============================================================================

(def ^:private last-managed-args (atom nil))

;; Every managed-HTTP request lowered during a test, in order. A route
;; activation lowers a whole plan in one dispatch, so a test addresses one of
;; those requests by its endpoint (`managed-request-for`).
(def ^:private managed-args-log (atom []))

;; The reset fixture's post-dispose hook clears the :resource, :mutation and
;; :resource-scope kinds between tests, and CLJS cannot re-load a namespace, so
;; the example's registrations are snapshot once here and reinstated by `init!`.
(def ^:private resource-kind-snapshots
  (select-keys @rf.registrar/kind->id->metadata
               [:resource :mutation :resource-scope]))

;; Remove this example's rows of those kinds from the shared registrar at load.
;; cljs.test loads every test ns before running any, and another suite's reset
;; clearing them would emit a frameless :rf.registry/handler-cleared burst into
;; a tooling test's trace collector. Only our ids go; `init!` reinstates them.
(swap! rf.registrar/kind->id->metadata
       (fn [reg]
         (reduce (fn [r [kind id->meta]]
                   (update r kind (fn [m] (apply dissoc m (keys id->meta)))))
                 reg
                 resource-kind-snapshots)))

(defn- init!
  "Per-test setup: reinstate the example's resource registrations, publish the
   routing integration, stub managed HTTP and url-push, then make the
   `:url-bound?` default frame LAST. Its construction plans the `\"/\"` route,
   and a plan run before the resource kinds are reinstated records a sticky
   `:rf.error/resource-route-plan`."
  []
  (reset! last-managed-args nil)
  (reset! managed-args-log [])
  ;; `register!` writes registrar and source store in lockstep, so image-loaded
  ;; frames see the reinstated registrations.
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
    {:adapter rf.adapter.reagent/adapter
     :init-fn init!
     ;; Map-form, for the production-seam receipt's `(async done …)` row.
     :async?  true
     ;; The RealWorld twins share ids (`:auth/initialise`, `:rf.route/not-found`,
     ;; …); `:app-ns` keeps this app's rows out of every other suite's baseline
     ;; and reinstates them for this suite's tests.
     :app-ns  "realworld-resources."}))

;; ============================================================================
;; HELPERS
;; ============================================================================

(defn- runtime-db [frame-id] (:rf.db/runtime (rf/frame-state-value frame-id)))

(defn- entry [frame-id scoped-key]
  (get-in (runtime-db frame-id) (rf.resources.state/entry-path scoped-key)))

(defn- entries-for
  "Every cache entry for `resource-id`, under ANY scope."
  [frame resource-id]
  (into [] (comp (map val) (filter #(= resource-id (second (:resource/key %)))))
        (get-in (runtime-db frame) (rf.resources.state/entries-path))))

(defn- stale? [e]
  (or (contains? #{:loading :fetching} (:status e)) (some? (:invalidated-at e))))

(defn- viewer-scope [username] [:rf.scope/viewer {:username username}])

(def ^:private anon-viewer-scope [:rf.scope/viewer :anonymous])

;; The optional-auth reads are viewer-scoped; the tests act as the signed-in
;; reader alice unless they name another viewer.
(defn- article-key [slug]
  (rf.resources.state/scoped-resource-key (viewer-scope "alice") :realworld/article {:slug slug}))

(defn- profile-key [subject]
  (rf.resources.state/scoped-resource-key (viewer-scope "alice") :realworld/profile {:username subject}))

(defn- profile-list-key
  "A profile tab's page-1 list (`:realworld/author-articles` or
   `:realworld/favorited-articles`) of `subject`, as alice sees it."
  [resource-id subject]
  (rf.resources.state/scoped-resource-key (viewer-scope "alice") resource-id {:username subject :page 1}))

(defn- comments-key [viewer slug]
  (rf.resources.state/scoped-resource-key (viewer-scope viewer) :realworld/comments {:slug slug}))

(defn- articles-list-key
  "The home article list, page 1, under `scope`."
  [scope]
  (rf.resources.state/scoped-resource-key scope :realworld/articles {:tag nil :page 1}))

(defn- feed-key [username page]
  (rf.resources.state/scoped-resource-key [:rf.scope/session {:username username}] :realworld/feed {:page page}))

(defn- tags-key []
  (rf.resources.state/scoped-resource-key :rf.scope/global :realworld/tags {}))

(defn- reply-success!
  "Replay the captured `:on-success` with the transport's success result
   appended, on the frame the request ran on (Spec 014 §Reply addressing)."
  [args data frame]
  (rf/dispatch-sync (conj (:on-success args) {:status :ok :value data}) {:frame frame}))

(defn- reply-failure! [args error frame]
  (rf/dispatch-sync (conj (:on-failure args) {:status :error :error error}) {:frame frame}))

(def ^:private http-500 {:kind :rf.http/http-5xx :status 500})

(defn- managed-request-for
  "The logged managed-HTTP request whose URL contains `url-fragment`, or nil."
  [url-fragment]
  (first (filter (fn [args] (str/includes? (str (get-in args [:request :url])) url-fragment))
                 @managed-args-log)))

(defn- feed-requests
  "Every logged managed-HTTP request for the session feed, in order."
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
  "The live route slice `{:route-id :params :query :fragment :transition :error :nav-token}`."
  [frame]
  (get-in (runtime-db frame) [:rf.runtime/routing :current]))

(defn- route-owner?
  "True iff `entry` carries an active `[:route route-id* _]` owner. The
   nav-token is opaque, so this matches on the route id."
  [entry route-id*]
  (boolean (some (fn [o] (and (vector? o)
                              (= :route (first o))
                              (= route-id* (second o))))
                 (:active-owners entry))))

(defn- gc-recheck!
  "Fire the GC re-check for a scoped key: an owner-free, work-free entry is collected."
  [frame scoped-key]
  (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key scoped-key}]
                    {:frame frame}))

(defn- app-frame!
  "A URL-owning anon frame with url-push and session persistence stubbed, so
   navigation is deterministic and no token reaches localStorage. Route auth is
   `:can-enter` route metadata, so the frame needs no auth wiring of its own."
  []
  (rf.frame/make-anon-frame-record!
    {:url-bound?   true
     :fx-overrides {:rf.nav/push-url :rf/no-op
                    :realworld-resources.session/persist :rf/no-op}}))

(defn- auth-frame!
  "An anon frame that owns no URL, with session persistence stubbed."
  []
  (rf.frame/make-anon-frame-record!
    {:fx-overrides {:realworld-resources.session/persist :rf/no-op}}))

;; `:auth/initialise` asks the app's `:realworld-resources.session/load` effect
;; to read localStorage. These helpers stage that storage for one call, so the
;; real effect and its reply run unchanged.

(defn- with-local-storage
  "Run `f` with `globalThis.localStorage` defined by the JS property
   `descriptor`, then put back whatever was there before."
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
  "Run `f` over a localStorage holding `token` under the contract key `jwtToken`
   (nil: an empty store)."
  [token f]
  (with-local-storage
    #js {:configurable true
         :value        #js {:getItem    (fn [k] (when (= k "jwtToken") token))
                            :setItem    (fn [_ _] nil)
                            :removeItem (fn [_] nil)}}
    f))

(defn- init-auth!
  "Run `:auth/initialise` in frame `f` over a store holding `token`."
  [f token]
  (with-saved-jwt token #(rf/dispatch-sync [:auth/initialise] {:frame f})))

(defn- submit-login!
  "Submit the login form as `email`; the form event issues the login POST."
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
  "Open /editor/doomed and settle its article read."
  [f]
  (reset! last-managed-args nil)
  (rf/dispatch-sync [:rf.route/navigate {:to :realworld.editor/edit :params {:slug "doomed"}}]
                    {:frame f})
  (reply-success! @last-managed-args
                  {:article {:slug "doomed" :title "Doomed" :description "d"
                             :body "b" :tagList []}}
                  f))

;; ============================================================================
;; BEARER HEADER, AND THE JWT AS A CLASSIFIED SECRET
;; ============================================================================

(deftest bearer-interceptor-injects-token-when-authed-and-noops-when-logged-out
  (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
    (let [auth-header #(get-in (core/bearer-auth-interceptor {:frame f :request {:url "/articles"}})
                               [:request :headers "Authorization"])]
      (is (nil? (auth-header)) "logged out, a public read carries no Authorization header")
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt-xyz"}] {:frame f})
      (is (= "Token jwt-xyz" (auth-header))))))

;; The wire `ws/User` requires the sensitive :token while `:auth/store-session`
;; stores the user token-free, so a durable AuthSlice validating against the
;; wire shape would roll every login back. This test runs the real AuthSlice
;; under the real post-commit validator; the other tests' anon frames carry no
;; app schema.

(defn- vec-map-slot-props
  "The properties map of one slot in a vector-form Malli `[:map …]`, or nil."
  [map-schema slot-key]
  (some (fn [entry]
          (when (and (vector? entry) (= slot-key (first entry)) (map? (second entry)))
            (second entry)))
        (rest map-schema)))

(deftest durable-auth-user-validates-token-free-wire-user-still-requires-token
  (is (false? (m/validate ws/UserResponse
                          {:user {:email "alice@example.com" :username "alice"
                                  :bio nil :image nil}}))
      "a token-less reply is rejected by the wire schema")
  (is (true? (:sensitive? (vec-map-slot-props ws/User :token)))
      "the wire User's :token slot is :sensitive?, so reply captures redact it")
  (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
    (rf/reg-app-schema [:auth] {:frame f} app-schema/AuthSlice)
    (rf/dispatch-sync [:auth/store-session {:email "alice@example.com" :username "alice"
                                            :token "jwt-abc" :bio nil :image nil}]
                      {:frame f})
    (is (= {:user  {:email "alice@example.com" :username "alice" :bio nil :image nil}
            :token "jwt-abc"}
           (:auth (rf/app-db-value f)))
        "the token-free user commits (a schema failure would roll it back), and the JWT's one home is [:auth :token]")))

(deftest store-session-event-token-redacts-at-its-arg-map-path
  ;; An event's classification paths index into its arg (the event vector's
  ;; second element), so the mark is [:token]; a vector-relative [1 :token]
  ;; would be a silent no-op. Positive assertions, so that spelling cannot pass.
  (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "JWT-RESOURCES-SENTINEL-42d1"}]
                        {:frame f})
      (let [args (->> @traces
                      (keep #(get-in % [:tags :rf.event/v]))
                      (filter #(= :auth/store-session (first %)))
                      (map second))]
        (is (seq args) "the drive emitted the dispatched-event slot under test")
        (is (every? #(= {:username "alice" :token rf.privacy/redacted-sentinel}
                        (select-keys % [:username :token]))
                    args)
            "the JWT reads :rf/redacted at the arg map's :token; the username rides visible")))))

;; ============================================================================
;; MUTATIONS
;; ============================================================================

(deftest favorite-populates-the-detail-and-invalidates-both-scopes
  ;; One mutation reaches two scopes, each through its own per-target
  ;; descriptor: the viewer's article lists and the session feed.
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (let [owned [[:realworld/feed {:page nil} (feed-key "alice" nil)]
                 [:realworld/articles {:tag nil :page 1} (articles-list-key (viewer-scope "alice"))]]]
      (doseq [[resource params] owned]
        (rf/dispatch-sync [:rf.resource/ensure {:resource resource :params params :owner [:app resource]}]
                          {:frame f})
        (reply-success! @last-managed-args {:articles [{:slug "hello-conduit"}] :articlesCount 1} f))
      (is (= [false false] (mapv #(stale? (entry f (peek %))) owned)) "both reads are loaded and fresh")
      (rf/dispatch-sync [:rf.mutation/execute {:mutation :realworld/favorite
                                               :params   {:slug "hello-conduit"}
                                               :instance :test/fav
                                               :cause    [:test :fav]}]
                        {:frame f})
      (reply-success! @last-managed-args
                      {:article {:slug "hello-conduit" :title "Hello, Conduit"
                                 :favorited true :favoritesCount 1}}
                      f)
      (is (true? (-> (entry f (article-key "hello-conduit")) :data :article :favorited))
          ":populates seeded the detail from the reply")
      (is (= [true true] (mapv #(stale? (entry f (peek %))) owned))
          "the session feed and the viewer's list were both invalidated"))))

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
                        [:favorited :favoritesCount]))
        "the optimistic apply flips the heart off and clamps the count at zero")))

(deftest follow-and-unfollow-populate-the-profile-banner-from-the-reply
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (doseq [[mutation following?] [[:realworld/follow true] [:realworld/unfollow false]]]
      (rf/dispatch-sync [:rf.mutation/execute {:mutation mutation :params {:username "eve"}
                                               :instance [:follow "eve"] :cause [:test mutation]}]
                        {:frame f})
      (reply-success! @last-managed-args
                      {:profile {:username "eve" :bio "" :image "" :following following?}} f)
      (is (= following? (-> (entry f (profile-key "eve")) :data :profile :following))
          (str mutation " seeds the banner from its reply")))))

(deftest follow-and-unfollow-restale-the-session-feed
  ;; Your Feed is the articles of the authors you follow, so a follow or an
  ;; unfollow (one shared `:invalidates`) stales the session [:feed]; otherwise
  ;; a feed visited in the last minute is a cache hit with the old membership.
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld/home :query {:feed "following"}}] {:frame f})
    (reply-success! (last (feed-requests)) {:articles [] :articlesCount 0} f)
    (is (= :loaded (:status (entry f (feed-key "alice" 1)))) "Your Feed is loaded and cached")
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.profile/show :params {:username "eve"}}]
                      {:frame f})
    (rf/dispatch-sync [:ui/follow "eve" false] {:frame f})
    (reply-success! (managed-request-for "/profiles/eve/follow")
                    {:profile {:username "eve" :bio "" :image "" :following true}} f)
    (let [seen (count (feed-requests))]
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld/home :query {:feed "following"}}] {:frame f})
      (is (= 1 (- (count (feed-requests)) seen))
          "re-entering Your Feed fetches: the follow staled it"))))

(deftest follow-author-continuation-restales-the-detail-article
  ;; The follow mutation invalidates [:profile username], but the detail page's
  ;; embedded author flag lives in [:article slug], which only the call site
  ;; knows, so its :reply-to continuation re-stales it.
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
        (is (some? (:invalidated-at (entry f (tags-key))))
            "article tags changed, so the cached tag list is no longer served fresh")))))

;; ============================================================================
;; THE EDITOR
;; ============================================================================

(deftest editor-flow-gates-submit-and-reply-to-navigates-to-the-saved-article
  (with-new-frame [f (app-frame!)]
    ;; Boot registers the flow once; the create route's :on-match only resets the slice.
    (rf/dispatch-sync [:editor/register-flow] {:frame f})
    (rf/dispatch-sync [:editor/initialise] {:frame f})
    (is (false? (rf/compute-sub [:editor/can-submit?] (state-value f))) "a blank draft cannot submit")
    (fill-draft! f new-draft)
    (is (= [true false] [(rf/compute-sub [:editor/can-submit?] (state-value f))
                         (rf/compute-sub [:editor/can-leave?] (state-value f))])
        "valid and dirty: submit enables and the :can-leave guard blocks")
    (rf/dispatch-sync [:editor/submit] {:frame f})
    (reply-success! @last-managed-args {:article (assoc new-draft :slug "new-title" :tagList [])} f)
    (is (= [true :realworld.article/show] [(rf/compute-sub [:editor/can-leave?] (state-value f))
                                           (route-id f)])
        "the save continuation re-seeds a clean draft and opens the saved article")))

(deftest editor-save-parses-its-tag-list-exactly-once
  ;; The tag string is split once, at :editor/submit. A second parse would not
  ;; throw: `str/split` coerces a vector with `str`, so the wire would carry the
  ;; vector's printed form as a single tag.
  (doseq [[tags expected] [["" []] ["clojure, SPA" ["clojure" "SPA"]]]]
    (with-new-frame [f (app-frame!)]
      (rf/dispatch-sync [:editor/register-flow] {:frame f})
      (rf/dispatch-sync [:editor/initialise] {:frame f})
      (fill-draft! f (assoc new-draft :tagList tags))
      (rf/dispatch-sync [:editor/submit] {:frame f})
      (is (= (assoc new-draft :tagList expected)
             (get-in @last-managed-args [:request :body :article]))
          (str "the tag field " (pr-str tags) " reaches the wire as " (pr-str expected))))))

(deftest editor-edit-load-seeds-the-draft-and-edit-to-new-blanks-it
  ;; The edit route's :on-match joins the route-owned read with an ownerless
  ;; :reply-to [:editor/article-loaded slug] ensure, which seeds draft and baseline.
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
           [(rf/compute-sub [:editor/draft] (state-value f)) (rf/compute-sub [:editor/dirty?] (state-value f))])
        "the loaded article seeds draft and baseline, its tags joined for the form")
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.editor/new}] {:frame f})
    (is (nil? (rf/compute-sub [:editor/slug] (state-value f)))
        "edit → New Article resets the slice to a blank create draft")))

(deftest editor-edit-then-navigate-to-unrelated-route-releases-the-route-owned-read
  ;; The edit route owns the article read in its :resources, so any route leave
  ;; releases it; an app-minted owner would be stranded by a plain edit → home.
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (open-doomed-editor! f)
    (is (route-owner? (entry f (article-key "doomed")) :realworld.editor/edit)
        "the edit route owns the article read while it is live")
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld/home}] {:frame f})
    (gc-recheck! f (article-key "doomed"))
    (is (nil? (entry f (article-key "doomed")))
        "leaving for home releases it, and the owner-free settled entry is reclaimed")))

(deftest editor-delete-clears-the-slice-and-navigates-home
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (open-doomed-editor! f)
    (rf/dispatch-sync [:editor/delete] {:frame f})
    (reply-success! @last-managed-args {} f)
    (is (= [nil :realworld/home] [(rf/compute-sub [:editor/slug] (state-value f)) (route-id f)])
        "a delete answered on its own page clears the slice and heads home")))

(deftest editor-late-cross-slug-reply-does-not-clobber-the-current-draft
  ;; Leaving edit A for edit B releases A's owner, but cancellation is
  ;; best-effort: a late A settle is still accepted for A's own entry and fans
  ;; out to A's seed continuation, which must not reseed the B draft.
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
             (select-keys (rf/compute-sub [:editor/slice] (state-value f)) [:slug :draft]))
          "the late A reply leaves the editor on B, with B's draft"))))

(deftest editor-same-slug-seed-does-not-clobber-typed-fields
  ;; Typing before the read settles is the ordinary case. The seed is leafwise:
  ;; a touched field keeps its draft AND baseline, so it stays dirty and the save
  ;; sends it; every untouched field takes the loaded value in both.
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
                          [:slug :draft :baseline :touched]))
          "the typed title survives the settle and stays dirty; every untouched field is seeded"))))

;; ----------------------------------------------------------------------------
;; A write outlives the page that issued it
;; ----------------------------------------------------------------------------
;;
;; Leaving a page releases the reads the route owns, never an independent
;; write, so a continuation that navigates or writes page-local state first asks
;; whether the reader is still on the issuing page. The detail-page delete asks
;; the route (`[:ui/article-deleted slug]`); the editor asks the navigation
;; (`[:editor/replied nav-token]`), because a create draft has no slug.

(defn- delete-alpha-then-walk-to!
  "Open /article/alpha and click Delete (the write is held open); given a
   `detour` route request, walk there before the server answers. Returns the
   held delete request."
  [f detour]
  (rf/dispatch-sync [:rf.route/navigate {:to :realworld.article/show :params {:slug "alpha"}}]
                    {:frame f})
  (rf/dispatch-sync [:ui/delete-article "alpha"] {:frame f})
  (let [del @last-managed-args]
    (is (= :delete (get-in del [:request :method])) "alpha's delete write went out and is held")
    (when detour
      (rf/dispatch-sync [:rf.route/navigate detour] {:frame f}))
    del))

(defn- alpha-delete-status [f]
  (:status (rf/compute-sub [:rf/mutation {:instance [:delete-article "alpha"]}] (state-value f))))

(deftest delete-article-continuation-navigates-home-only-from-its-own-page
  ;; A success clears the delete's own instance wherever the reader is; a
  ;; failure navigates nowhere and stays on the instance.
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

;; The editor's writes run under a per-session instance; the tests read it the
;; way the view does, through `:editor/save-instance`.

(defn- editor-instance [f]
  (rf/compute-sub [:editor/save-instance] (state-value f)))

(defn- mutation-at [f instance]
  (rf/compute-sub [:rf/mutation {:instance instance}] (state-value f)))

(defn- editor-save [f]
  (mutation-at f (editor-instance f)))

(defn- cache-home-list!
  "Cache the home article list under alice's viewer scope — fresh for a minute,
   so a revisit is a cache hit — and return its key. The editor's writes stale
   it through `[:article-list]`."
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
      (rf/dispatch-sync [:editor/register-flow] {:frame f})
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld.editor/new}] {:frame f})
      (fill-draft! f new-draft)
      (rf/dispatch-sync [:editor/submit] {:frame f})
      (let [save @last-managed-args]
        (is (= :post (get-in save [:request :method])) "the create write went out and is held")
        (rf/dispatch-sync [:rf.route/navigate {:to :realworld.profile/show :params {:username "eve"}}]
                          {:frame f})
        (let [pending (rf/compute-sub [:rf/pending-navigation] (state-value f))]
          (is (some? pending) "the dirty draft's :can-leave guard held the navigation")
          (rf/dispatch-sync [:rf.route/continue (:id pending)] {:frame f}))
        (reply-success! save {:article (assoc new-draft :slug "new-title" :tagList [])} f)
        (is (= [:realworld.profile/show nil]
               [(route-id f) (rf/compute-sub [:editor/slug] (state-value f))])
            "the late save neither drags the reader to the article nor re-seeds the editor"))))
  (testing "entering a new editor cancels nothing: the old delete's late reply
            runs its :invalidates, is refused by the nav-token gate, and retires
            its own instance. Clearing it on entry would abort the delete and
            leave a cached list serving the deleted article"
    (with-new-frame [f (app-frame!)]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      (let [list-key (cache-home-list! f)]
        (is (not (stale? (entry f list-key))) "the home list is cached and fresh")
        (open-doomed-editor! f)
        (let [old-instance (editor-instance f)]
          (rf/dispatch-sync [:editor/delete] {:frame f})
          (let [del @last-managed-args]
            (is (true? (:pending? (mutation-at f old-instance)))
                "the delete is in flight under its session's instance")
            (rf/dispatch-sync [:rf.route/navigate {:to :realworld.editor/new}] {:frame f})
            (reply-success! del {} f)
            (is (= [:realworld.editor/new true :idle]
                   [(route-id f) (stale? (entry f list-key)) (:status (mutation-at f old-instance))])
                "the reader stays on the new draft, the list goes stale, and the old instance is retired")))))))

(deftest editor-same-slug-return-is-a-new-session-an-old-write-cannot-touch
  ;; Returning to the same article's editor is a new form session (instances key
  ;; on the nav-token, not the slug): the old delete neither busies the new form
  ;; nor, answering :ok or :error, touches its pending save.
  (doseq [answer [:ok :error]]
    (testing (str "the old delete answers " answer)
      (with-new-frame [f (app-frame!)]
        (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
        (rf/dispatch-sync [:editor/register-flow] {:frame f})
        (let [list-key (cache-home-list! f)]
          (is (= :loaded (:status (entry f list-key))) "the home list is cached")
          (open-doomed-editor! f)
          (let [old-instance (editor-instance f)]
            (rf/dispatch-sync [:editor/delete] {:frame f})
            (let [del @last-managed-args]
              (is (= :delete (get-in del [:request :method])) "the delete write went out and is held")
              ;; An identical re-navigation is a no-op, so the detour is what
              ;; makes the return a new navigation.
              (rf/dispatch-sync [:rf.route/navigate {:to :realworld.profile/show :params {:username "eve"}}]
                                {:frame f})
              (rf/dispatch-sync [:rf.route/navigate {:to :realworld.editor/edit :params {:slug "doomed"}}]
                                {:frame f})
              (is (= ["doomed" false] [(rf/compute-sub [:editor/slug] (state-value f))
                                       (boolean (:pending? (editor-save f)))])
                  "back on the same article's editor, and its form is not busy with the old delete")
              (fill-draft! f {:title "Doomed, revised" :description "d2" :body "b2"})
              (rf/dispatch-sync [:editor/submit] {:frame f})
              (case answer
                :ok    (reply-success! del {} f)
                :error (reply-failure! del http-500 f))
              (is (= [true :idle (= :ok answer)]
                     [(:pending? (editor-save f)) (:status (mutation-at f old-instance))
                      (stale? (entry f list-key))])
                  "the new save stays pending, the old delete retires only its own instance, and only a success invalidates"))))))))

(deftest editor-current-page-save-failure-stays-on-its-instance
  ;; :editor/replied clears only a reply it refuses, so a failure on the issuing
  ;; page stays on the session's instance, where the form shows it for a retry.
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (rf/dispatch-sync [:editor/register-flow] {:frame f})
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.editor/new}] {:frame f})
    (fill-draft! f new-draft)
    (rf/dispatch-sync [:editor/submit] {:frame f})
    (reply-failure! @last-managed-args http-500 f)
    (is (= [:realworld.editor/new :error] [(route-id f) (:status (editor-save f))])
        "a failed save navigates nowhere and stays on the session's instance")))

;; ============================================================================
;; AUTH, LOGOUT AND SESSION RESTORE
;; ============================================================================

(deftest logout-clears-both-principal-scopes-and-leaves-global-tags
  ;; Logout drops the departing user's session feed AND their viewer-scoped
  ;; reads (which carry their favorited / following flags); the global tags stay.
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
      (is (nil? (get-in (rf/app-db-value f) [:auth :user])) "signed out")
      (is (= [false false true] (present))
          "the session feed and the viewer-scoped article are dropped; the global tags stay"))))

(deftest auth-machine-login-stores-the-session
  ;; The credential-owning form event issues the login POST and the machine sees
  ;; only bare signals. An interactive login bounces home; a restore stays put.
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
  (is (= [[:token]] (:sensitive (rf.registrar/handler-meta :event :auth/session-read)))
      "the storage read's reply classifies the token it carries")
  (is (nil? (with-local-storage #js {:configurable true
                                     :get          (fn [] (throw (js/Error. "storage is blocked")))}
              auth/read-saved-token))
      "storage that refuses access reads as no saved token, so boot does not throw")
  (with-new-frame [f (auth-frame!)]
    (init-auth! f nil)
    (rf/dispatch-sync [:auth/session-read {:generation (dec (:auth-generation (rf/app-db-value f)))
                                           :token      "jwt-stale"}]
                      {:frame f})
    (is (nil? (get-in (rf/app-db-value f) [:auth :token]))
        "a read reply for an earlier generation is dropped")))

(deftest a-restore-reply-that-outlives-its-session-is-dropped
  ;; A login lands while GET /user is still on the wire; the late restore reply
  ;; is about a session that no longer exists.
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
                                (get-in (rf/app-db-value f) [:auth :token])])
          "the late restore reply does not replace the session the login stored"))))

;; Cold-boot restore is the one principal switch with no route change, so the
;; route's `{:from-db …}` reads fail closed at entry and nothing re-plans them
;; for free. Both restore outcomes dispatch `[:rf.route/replan-resources …]`,
;; which re-plans the current route without navigating.

(deftest logged-out-home-plans-articles-and-tags-and-not-the-feed
  ;; The feed occurrence is admitted only on the ?feed=following arm (its route
  ;; :when), so a nil session scope never fails the public home plan.
  (with-new-frame [f (app-frame!)]
    (init-auth! f nil)
    (is (= :idle (rf/compute-sub [:auth/state] (state-value f))) "no saved token, so no restore")
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld/home}] {:frame f})
    (is (= [nil true true []]
           [(:error (slice f))
            (some? (entry f (articles-list-key anon-viewer-scope)))
            (some? (entry f (tags-key)))
            (entries-for f :realworld/feed)])
        "the plan forms: the articles under the anonymous viewer and the tags, and no feed under any scope")
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld/home :query {:feed "following"}}] {:frame f})
    (is (= :rf.error/resource-route-plan (:rf.error/id (:error (slice f))))
        "logged out, the following arm fails the whole plan: the session scope is nil")))

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
                (entries-for f :realworld/feed)])
            "while the viewer is unresolved the shell defers and nothing is stored under any identity")
        (reply-success! restore-req
                        {:user {:username "alice" :email "alice@example.com" :token "jwt-restore"}}
                        f)
        (is (= [:authed false] [(rf/compute-sub [:auth/state] (state-value f))
                                (rf/compute-sub [:auth/viewer-resolving?] (state-value f))]))
        (is (= [:realworld/home nav-token "following"]
               [(route-id f) (:nav-token (slice f)) (:feed (route-query f))])
            "restore stays put: the same route, the same activation, the query intact")
        (is (= [true true true]
               (mapv #(some? (entry f %))
                     [(articles-list-key (viewer-scope "alice")) (tags-key) (feed-key "alice" 1)]))
            "the replan ensures the whole plan under alice")))))

(deftest session-restore-failure-stays-put-and-replans-under-anonymous
  ;; A rejected saved token clears the session, confirms an anonymous viewer and
  ;; replans the current route's public reads without navigating home.
  (with-new-frame [f (app-frame!)]
    (init-auth! f "jwt-stale")
    (is (= :restoring (rf/compute-sub [:auth/state] (state-value f))))
    (let [restore-req  @last-managed-args
          anon-article (rf.resources.state/scoped-resource-key anon-viewer-scope :realworld/article
                                                               {:slug "public-post"})]
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld.article/show :params {:slug "public-post"}}]
                        {:frame f})
      (let [nav-token (:nav-token (slice f))]
        (is (nil? (entry f anon-article))
            "while restore is in flight the viewer is unresolved, so nothing is stored as anonymous")
        (reply-failure! restore-req {:rf.http/status 401} f)
        (is (= [:realworld.article/show nav-token true]
               [(route-id f) (:nav-token (slice f)) (some? (entry f anon-article))])
            "the deep link stays put and its article is ensured under the anonymous viewer")))))

(deftest optional-auth-representation-is-not-shared-across-viewers
  ;; "Public" is an access policy, not a cache identity: the same article read
  ;; keys per viewer, so alice's favorited=true never reaches bob.
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
                                         (state-value f))))
        "after an account switch, bob's read does not see alice's cached representation")))

;; ============================================================================
;; SETTINGS
;; ============================================================================
;;
;; Logout stays live while a settings save is in flight, so its reply can land
;; on a different session. Nothing below the app rejects it; `:settings/replied`
;; asks whether the session that issued the save is still the one signed in.

(defn- park-a-settings-save!
  "Sign `username` in, open Settings, edit the bio and submit — returning the
   lowered PUT's args, still unanswered."
  [f username]
  (rf/dispatch-sync [:auth/store-session {:username username :email "a@b.c" :token "jwt"
                                          :bio nil :image nil}] {:frame f})
  (rf/dispatch-sync [:settings/load] {:frame f})
  (rf/dispatch-sync [:settings/edit-field :bio "A brand new bio"] {:frame f})
  (reset! last-managed-args nil)
  (rf/dispatch-sync [:settings/submit] {:frame f})
  (let [args @last-managed-args]
    (is (= :put (get-in args [:request :method])) "the settings PUT went out and is held")
    args))

(deftest settings-reply-from-an-old-account-is-refused-after-the-new-one-opens-settings
  ;; The save's owner is captured at submit into its own slot, so bob's route
  ;; entry (:settings/load rebuilds the draft slice) cannot make alice's late
  ;; reply look like his.
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
                                (get-in (rf/app-db-value f) [:auth :token])])
          "alice's late reply does not put her credentials over bob's session"))))

(deftest settings-success-still-lands-after-a-mid-save-detour-back-through-settings
  ;; The refusal must not catch the issuing session itself: alice parks a save,
  ;; leaves, and returns to Settings (re-seeding the draft) before it replies.
  (with-new-frame [f (app-frame!)]
    (let [alice-args (park-a-settings-save! f "alice")]
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld/home}] {:frame f})
      (rf/dispatch-sync [:rf.route/navigate {:to :realworld.user/settings}] {:frame f})
      (reply-success! alice-args
                      {:user {:username "alice" :email "a@b.c" :token "alice-jwt-2"
                              :bio "A brand new bio" :image nil}}
                      f)
      (is (= ["A brand new bio" :realworld.profile/show {:username "alice"}]
             [(get-in (rf/app-db-value f) [:auth :user :bio]) (route-id f) (route-params f)])
          ":settings/replied folds the saved user in and, still on /settings, opens the profile"))))

(deftest settings-save-persists-the-refreshed-session-token
  ;; The next cold boot must read the same credential as the live session.
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

;; ============================================================================
;; ROUTES: PAGINATION, THE AUTH GUARD, THE PROFILE TABS
;; ============================================================================

(deftest pagination-nav-events-carry-feed-tag-and-drop-page-1
  ;; Paging swaps only ?page= and keeps the active feed, tag or profile; page 1
  ;; drops the param, so the first-page URL stays canonical.
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
                        [(route-id f) (:tag p) (:username p) (:feed q) (:page q)]))
          (pr-str events)))))

(deftest auth-guard-return-to-preserves-full-address
  ;; The denial handler stashes the runtime's resolved :destination whole, so a
  ;; login bounce-back lands on the exact URL, query and #fragment included.
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:rf.route/handle-url-change "/editor/my-slug?tab=preview#comments"] {:frame f})
    (is (= :realworld.auth/login (route-id f)) "a logged-out deep link to a guarded route is refused")
    (rf/dispatch-sync [:auth/store-session {:username "eve" :token "t"}] {:frame f})
    (rf/dispatch-sync [:auth/post-login-redirect] {:frame f})
    (is (= [:realworld.editor/edit {:slug "my-slug"} {"tab" "preview"} "comments" nil]
           [(route-id f) (route-params f) (route-query f) (route-fragment f) (return-to f)])
        "the bounce-back restores route, params, query and #fragment, and consumes the crumb"))
  (testing "a protected deep link while restore is in flight is deferred, not bounced"
    (with-new-frame [f (app-frame!)]
      (init-auth! f "jwt-in-flight")
      (rf/dispatch-sync [:rf.route/handle-url-change "/settings"] {:frame f})
      (is (not= :realworld.auth/login (route-id f)) "identity is unknown, so the login bounce is deferred")
      (rf/dispatch-sync [:auth/store-session {:username "eve" :token "jwt-in-flight"}] {:frame f})
      (rf/dispatch-sync [:auth/settle-deferred-entry] {:frame f})
      (is (= [:realworld.user/settings nil] [(route-id f) (return-to f)])
          "once restore settles, a fresh attempt enters the requested route and consumes the stash"))))

(deftest profile-favorites-tab-composes-the-parent-banner-with-its-own-list
  ;; `:realworld.profile/favorites` declares `:parent :realworld.profile/show`
  ;; and only its own list: the parent contributes the banner, and the parent's
  ;; authored list is gated to the show leaf by its `:when`.
  (with-new-frame [f (app-frame!)]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.profile/favorites :params {:username "eve"}}]
                      {:frame f})
    (is (= [true true] (mapv #(route-owner? (entry f %) :realworld.profile/favorites)
                             [(profile-key "eve") (profile-list-key :realworld/favorited-articles "eve")]))
        "the inherited banner and the leaf's own list are ensured under the active route's owner")
    (is (nil? (entry f (profile-list-key :realworld/author-articles "eve")))
        "the parent's authored list stays gated off")
    (is (some? (managed-request-for "favorited=eve"))
        "the leaf fetches GET /articles?favorited=:username")
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.profile/show :params {:username "eve"}}]
                      {:frame f})
    (is (some? (entry f (profile-list-key :realworld/author-articles "eve")))
        "on the show tab the :when opens and the authored list is ensured")))

;; ============================================================================
;; THE PRODUCTION-SEAM RECEIPT
;; ============================================================================
;;
;; Every other test answers managed HTTP by hand, so none can pin the README's
;; claim that a write survives the refetch it causes: a backend answering the
;; refetch from a frozen seed would still pass them. This receipt answers
;; nothing by hand. The frame is wired the way `core.cljs` wires the served app
;; (managed HTTP → `:realworld-resources.demo/http-stub`), replies come back
;; through the backend's deferred path, and the test waits for the runtime to
;; settle. `with-new-frame` is not used: it destroys the frame when the body
;; returns, which for an async body is before anything settles.

(def ^:private demo-user
  "The demo world's one user, as `POST /users/login` issues them."
  {:email "demo@conduit.dev" :token "stub.demo.jwt" :username "demo"
   :bio "Canned demo user." :image ""})

(defn- production-seam-frame! []
  (rf.frame/make-anon-frame-record!
    {:url-bound?   true
     :fx-overrides {:rf.http/managed                     :realworld-resources.demo/http-stub
                    :rf.nav/push-url                     :rf/no-op
                    :realworld-resources.session/persist :rf/no-op}}))

(defn- backend-comments
  "What the demo backend answers `GET /articles/<slug>/comments` with right now —
   a pure read of the app's own world."
  [slug]
  (:ok (second (demo/transition @app-http/demo-state
                                {:request {:method :get
                                           :url    (app-http/full-url
                                                     (str "/articles/" slug "/comments"))}}))))

(deftest production-seam-receipt-a-comment-survives-the-refetch-it-causes
  (async done
    ;; The documented reset boundary: this receipt's world, and nobody else's.
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
                   ;; `:realworld/post-comment` declares `:invalidates` and no
                   ;; `:populates`, so only the refetch can bring the comment in.
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
                     (is (= (backend-comments slug) (:data e))
                         "the refetched entry is the backend's current truth")
                     (is (= "great read" (-> e :data :comments second :body))
                         "…and that truth carries the comment just written")
                     (is (nil? (:invalidated-at e))
                         "the refetch cleared the invalidation it answered"))))
          ;; Report and release; `done` runs once, in the one trailing step.
          (.catch (fn [e]
                    (is false (str "production-seam receipt did not settle: " (.-message e)))
                    nil))
          (.then (fn [_] (done)))))))
