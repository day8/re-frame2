(ns realworld-http.baseline-test

  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [realworld-http.core]
            [realworld-http.routing]
            [realworld-http.http :as rh]
            [realworld-http.article-editor :as editor])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

;; Each stand-in below answers a request by dispatching the reply event the
;; request addresses, with the reply appended as its last argument.
(defn- deliver! [frame-ctx args reply]
  (when-let [target (or (:reply-to args)
                        (if (= :ok (:status reply)) (:on-success args) (:on-failure args)))]
    (rf/dispatch (conj target reply) {:frame (:frame frame-ctx)})))

(defn- reg-canned-success!
  [fx-id value]
  (rf/reg-fx fx-id
    {:platforms #{:client :server}}
    (fn [frame-ctx args]
      (deliver! frame-ctx args {:status :ok :value value}))))

(defn- reg-canned-success-by-url! [fx-id f]
  (rf/reg-fx fx-id
    {:platforms #{:client :server}}
    (fn [frame-ctx args]
      (let [req (:request args)]
        (deliver! frame-ctx args {:status :ok :value (f (or (:method req) :get) (:url req))})))))

(defn- reg-canned-failure!
  [fx-id kind tags]
  (rf/reg-fx fx-id
    {:platforms #{:client :server}}
    (fn [frame-ctx args]
      (deliver! frame-ctx args {:status :error :error (assoc tags :kind kind)}))))

(reg-canned-success! :realworld.test/canned-success-empty {})

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil}))

(defn- sub
  [f query]
  (rf/compute-sub query (rf/frame-state-value f)))

(defn- articles-load-test []
  (reg-canned-success! :realworld.test/canned-articles
    {:articles [{:slug "hello-world" :title "Hello, world" :description "An intro"
                 :body "..." :tagList ["intro"]
                 :createdAt "2026-05-01T00:00:00Z" :updatedAt "2026-05-01T00:00:00Z"
                 :favorited false :favoritesCount 0
                 :author {:username "alice" :bio nil :image nil :following false}}]
     :articlesCount 1})
  (with-new-frame [f (rf.frame/make-anon-frame-record!
                     {:initial-events [[:app/initialise]]
                      :fx-overrides {:rf.http/managed :realworld.test/canned-articles}})]
    (is (= :idle (:status (sub f [:articles/slice]))))
    (rf/dispatch-sync [:articles/load] {:frame f})
    (let [slice (sub f [:articles/slice])]
      (is (= [:loaded ["hello-world"]] [(:status slice) (mapv :slug (:data slice))])))
    (rf/dispatch-sync [:articles/load] {:frame f})
    (is (= [:loaded 2] ((juxt :status :attempt) (sub f [:articles/slice]))))))

(defn- articles-load-failure-test []
  (reg-canned-failure! :realworld.test/canned-articles-failure
                       :rf.http/http-5xx
                       {:status 500
                        :body   "server error"})
  (with-new-frame [f (rf.frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/canned-articles-failure}})]
    (rf/dispatch-sync [:articles/load] {:frame f})
    (is (= :error (:status (sub f [:articles/slice]))))
    (is (some? (sub f [:articles/error])))))

(defn- feed-load-test []
  (reg-canned-success! :realworld.test/feed-ok
    {:articles [{:slug "f1" :title "Feed one" :description "d" :body "b" :tagList []
                 :createdAt "x" :updatedAt "x" :favorited false :favoritesCount 0
                 :author {:username "bob" :bio nil :image nil :following true}}]
     :articlesCount 7})
  (with-new-frame [f (rf.frame/make-anon-frame-record!
                     {:initial-events [[:app/initialise]]
                      :fx-overrides {:rf.http/managed :realworld.test/feed-ok}})]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (rf/dispatch-sync [:feed/load] {:frame f})
    (is (= [1 7 :loaded]
           [(count (sub f [:feed/data])) (sub f [:feed/count])
            (:status (sub f [:feed/slice]))]))))

(defn- feed-load-failure-test []
  (reg-canned-failure! :realworld.test/feed-fail :rf.http/http-5xx {:status 500 :body "boom"})
  (with-new-frame [f (rf.frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/feed-fail}})]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (rf/dispatch-sync [:feed/load] {:frame f})
    (is (some? (sub f [:feed/error])))))

(defn- tag-query-test []
  (with-new-frame [f (rf.frame/make-anon-frame-record!
                     {:initial-events [[:app/initialise]]
                      :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    (rf/dispatch-sync [:tags/apply-filter "clojure"] {:frame f})
    (is (= [:realworld/home-tag "clojure"]
           [(sub f [:rf.route/id]) (sub f [:home/selected-tag])]))
    (rf/dispatch-sync [:home/show-your-feed] {:frame f})
    (is (= "following" (:feed (sub f [:rf.route/query]))))))

(defn- paginate-path-integration-test []
  (let [p (rh/paginate-path "/articles" nil 1)]
    (is (str/starts-with? p "/articles?"))
    (is (str/includes? p "limit=10"))
    (is (str/includes? p "offset=0")))
  (let [p (rh/paginate-path "/articles" {:tag "clojure"} 3)]
    (is (str/includes? p "tag=clojure"))
    (is (str/includes? p "limit=10"))
    (is (str/includes? p "offset=20"))))

(defn- pagination-nav-events-test []
  (with-new-frame [f (rf.frame/make-anon-frame-record!
                     {:initial-events [[:app/initialise]]
                      :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    (rf/dispatch-sync [:home/show-global-feed] {:frame f})
    (rf/dispatch-sync [:home/show-page 3] {:frame f})
    (is (= [:realworld/home 3 nil]
           [(sub f [:rf.route/id]) (:page (sub f [:rf.route/query]))
            (:feed (sub f [:rf.route/query]))]))
    (rf/dispatch-sync [:home/show-your-feed] {:frame f})
    (rf/dispatch-sync [:home/show-page 2] {:frame f})
    (is (= [:realworld/home 2 "following"]
           [(sub f [:rf.route/id]) (:page (sub f [:rf.route/query]))
            (:feed (sub f [:rf.route/query]))]))
    (rf/dispatch-sync [:tags/apply-filter "clojure"] {:frame f})
    (rf/dispatch-sync [:home/show-page 2] {:frame f})
    (is (= [:realworld/home-tag "clojure" 2]
           [(sub f [:rf.route/id]) (:tag (sub f [:rf.route/params]))
            (:page (sub f [:rf.route/query]))]))
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.profile/show :params {:username "eve"}}] {:frame f})
    (rf/dispatch-sync [:profile/show-page 2] {:frame f})
    (is (= [:realworld.profile/show "eve" 2]
           [(sub f [:rf.route/id]) (:username (sub f [:rf.route/params]))
            (:page (sub f [:rf.route/query]))]))
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.profile/favorites :params {:username "eve"}}] {:frame f})
    (rf/dispatch-sync [:profile/show-page 3] {:frame f})
    (is (= [:realworld.profile/favorites 3]
           [(sub f [:rf.route/id]) (:page (sub f [:rf.route/query]))]))))

(deftest feed
  (articles-load-test)
  (articles-load-failure-test)
  (feed-load-test)
  (feed-load-failure-test)
  (tag-query-test)
  (paginate-path-integration-test)
  (pagination-nav-events-test))

(defn- ed-has-tag? [f tag]
  (sub f [:rf.machine/has-tag? :ui/article-editor tag]))

(defn- editor-create-test []
  (reg-canned-success! :realworld.test/canned-editor-save
    {:article {:slug "hello-world" :title "Hello" :description "Short" :body "Body"
               :tagList ["demo"] :createdAt "2026-05-01" :updatedAt "2026-05-01"
               :favorited false :favoritesCount 0
               :author {:username "alice" :bio nil :image nil :following false}}})
  (with-new-frame [f (rf.frame/make-anon-frame-record!
                     {:initial-events [[:app/initialise]]
                      :fx-overrides {:rf.http/managed :realworld.test/canned-editor-save}})]
    (rf/dispatch-sync [:editor/initialise] {:frame f})
    (is (= [true true false]
           [(ed-has-tag? f :mode/create) (ed-has-tag? f :lifecycle/idle)
            (sub f [:editor/can-submit?])]))
    (rf/dispatch-sync [:editor/edit-field :title "Hello"] {:frame f})
    (rf/dispatch-sync [:editor/edit-field :description "Short"] {:frame f})
    (rf/dispatch-sync [:editor/edit-field :body "Body"] {:frame f})
    (is (true? (sub f [:editor/can-submit?])))
    (rf/dispatch-sync [:editor/submit] {:frame f})
    (is (= [true true false]
           [(ed-has-tag? f :lifecycle/saved) (ed-has-tag? f :mode/edit)
            (sub f [:editor/dirty?])]))))

(defn- editor-can-leave-test []
  (with-new-frame [f (rf.frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    (rf/dispatch-sync [:editor/initialise] {:frame f})
    (is (true? (sub f [:editor/can-leave?])))
    (rf/dispatch-sync [:editor/edit-field :title "Changed"] {:frame f})
    (is (false? (sub f [:editor/can-leave?])))))

(defn- editor-pure-helpers-test []
  (is (= {} (editor/validate-draft {:title "T" :description "D" :body "B"})))
  (is (= #{:title :description :body}
         (set (keys (editor/validate-draft {:title "" :description "" :body ""})))))
  (is (= #{:description}
         (set (keys (editor/validate-draft {:title "T" :description "   " :body "B"})))))
  (is (= ["a" "b"] (editor/parse-tag-list " , a , b , ,")))
  (is (= [] (editor/parse-tag-list "")))
  (is (= {:article {:title "T" :description "D" :body "B" :tagList ["a" "b"]}}
         (editor/article-body {:title "T" :description "D" :body "B" :tagList "a, b"}))))

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
    (with-new-frame [f (rf.frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/editor-edit}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/hello-world"] {:frame f})
      (is (= [true true true]
             [(ed-has-tag? f :mode/edit) (ed-has-tag? f :editor/can-delete)
              (ed-has-tag? f :lifecycle/idle)]))
      (is (= ["hello-world" {:title "Hello, world" :description "Intro"
                            :body "Body text" :tagList "intro, demo"}]
             [(:slug (sub f [:editor/slice])) (sub f [:editor/draft])]))
      (is (= [false true] [(sub f [:editor/dirty?]) (sub f [:editor/can-leave?])]))
      (reset! seen [])
      (rf/dispatch-sync [:editor/edit-field :title "Hello, edited"] {:frame f})
      (is (true? (sub f [:editor/can-submit?])))
      (rf/dispatch-sync [:editor/submit] {:frame f})
      (is (some (fn [[m u]] (and (= :put m) (str/ends-with? u "/articles/hello-world"))) @seen))
      (is (= [true true] [(ed-has-tag? f :lifecycle/saved) (ed-has-tag? f :mode/edit)])))))

(defn- editor-load-failure-test []
  (reg-canned-failure! :realworld.test/editor-load-fail
    :rf.http/http-5xx {:status 500 :body "server error"})
  (with-new-frame [f (rf.frame/make-anon-frame-record!
                     {:initial-events [[:app/initialise]]
                      :fx-overrides {:rf.http/managed :realworld.test/editor-load-fail}})]
    (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
    (rf/dispatch-sync [:rf.route/handle-url-change "/editor/doomed"] {:frame f})
    (is (= [true true]
           [(ed-has-tag? f :lifecycle/error) (some? (sub f [:editor/submit-error]))]))))

(defn- editor-delete-test []
  (let [seen (atom [])]
    (reg-canned-success-by-url! :realworld.test/editor-delete
      (fn [method url]
        (swap! seen conj [method url])
        {:article {:slug "doomed" :title "Doomed" :description "d" :body "b" :tagList []
                   :createdAt "x" :updatedAt "x" :favorited false :favoritesCount 0
                   :author {:username "alice" :bio nil :image nil :following false}}}))
    (with-new-frame [f (rf.frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/editor-delete}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/doomed"] {:frame f})
      (is (= "doomed" (:slug (sub f [:editor/slice]))))
      (reset! seen [])
      (rf/dispatch-sync [:editor/delete] {:frame f})
      (is (= [true :realworld/home nil true]
             [(some (fn [[m u]] (and (= :delete m) (str/ends-with? u "/articles/doomed"))) @seen)
              (sub f [:rf.route/id]) (:slug (sub f [:editor/slice]))
              (ed-has-tag? f :mode/create)])))))

(defn- editor-invalid-submit-test []
  (with-new-frame [f (rf.frame/make-anon-frame-record!
                     {:initial-events [[:app/initialise]]
                      :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    (rf/dispatch-sync [:editor/initialise] {:frame f})
    (rf/dispatch-sync [:editor/edit-field :title "Only a title"] {:frame f})
    (rf/dispatch-sync [:editor/submit] {:frame f})
    (let [errors (sub f [:editor/errors])]
      (is (= #{:description :body :_form} (set (keys errors))))
      (is (= "Please fix the highlighted fields." (:_form errors))))
    (is (= [true true nil]
           [(some? (sub f [:editor/field-error :description]))
            (ed-has-tag? f :lifecycle/idle) (sub f [:editor/submit-error])]))))

(defn- editor-same-slug-seed-preserves-typing-test []
  (let [in-flight (atom nil)]
    (rf/reg-fx :realworld.test/editor-in-flight
      {:platforms #{:client :server}}
      (fn [_frame-ctx args] (reset! in-flight args) nil))
    (with-new-frame [f (rf.frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/editor-in-flight}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/hello-world"] {:frame f})
      (is (true? (ed-has-tag? f :lifecycle/loading)))
      (rf/dispatch-sync [:editor/edit-field :title "My unsaved heading"] {:frame f})
      (is (= #{:title} (:touched (sub f [:editor/slice]))))
      (rf/dispatch-sync (conj (:on-success @in-flight)
                             {:status :ok
                              :value {:article {:slug "hello-world" :title "Hello, world"
                                                :description "Intro" :body "Body text"
                                                :tagList ["intro" "demo"]}}})
                        {:frame f})
      (let [slice (sub f [:editor/slice])]
        (is (= {:title "My unsaved heading" :description "Intro"
                :body "Body text" :tagList "intro, demo"}
               (:draft slice)))
        (is (= {:title "" :description "Intro" :body "Body text" :tagList "intro, demo"}
               (:baseline slice)))
        (is (= ["hello-world" true #{:title}]
               [(:slug slice) (sub f [:editor/dirty?]) (:touched slice)]))))))

(defn- editor-cross-slug-settle-is-refused-test []
  (let [lowered (atom [])
        article (fn [slug title]
                  {:article {:slug slug :title title :description (str "About " slug)
                             :body (str "Body of " slug) :tagList [slug]}})]
    (rf/reg-fx :realworld.test/editor-cross-slug
      {:platforms #{:client :server}}
      (fn [_frame-ctx args] (swap! lowered conj args) nil))
    (with-new-frame [f (rf.frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/editor-cross-slug}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/alpha"] {:frame f})
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/beta"] {:frame f})
      (is (= 2 (count @lowered)))
      (let [[a-req b-req] @lowered]
        (is (= [[:editor/load-article "alpha"] [:editor/load-article "beta"]
                [:editor/loaded "alpha"] [:editor/load-failed "alpha"]]
               [(:request-id a-req) (:request-id b-req) (:on-success a-req) (:on-failure a-req)]))
        (rf/dispatch-sync (conj (:on-success b-req)
                               {:status :ok :value (article "beta" "Beta")}) {:frame f})
        (is (= "Beta" (:title (sub f [:editor/draft]))))
        (rf/dispatch-sync (conj (:on-success a-req)
                               {:status :ok :value (article "alpha" "Alpha")}) {:frame f})
        (let [slice (sub f [:editor/slice])
              expected {:title "Beta" :description "About beta" :body "Body of beta" :tagList "beta"}]
          (is (= ["beta" expected expected false #{}]
                 [(:slug slice) (:draft slice) (:baseline slice)
                  (sub f [:editor/dirty?]) (:touched slice)])))))))

(defn- editor-cross-slug-failure-is-refused-test []
  (let [lowered (atom [])]
    (rf/reg-fx :realworld.test/editor-cross-slug-fail
      {:platforms #{:client :server}}
      (fn [_frame-ctx args] (swap! lowered conj args) nil))
    (with-new-frame [f (rf.frame/make-anon-frame-record!
                       {:initial-events [[:app/initialise]]
                        :fx-overrides {:rf.http/managed :realworld.test/editor-cross-slug-fail}})]
      (rf/dispatch-sync [:auth/store-session {:username "alice" :token "jwt"}] {:frame f})
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/alpha"] {:frame f})
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/beta"] {:frame f})
      (rf/dispatch-sync (conj (:on-failure (first @lowered))
                             {:status :error :error {:kind :rf.http/http-5xx :status 500}})
                        {:frame f})
      (is (= [nil true false]
             [(sub f [:editor/submit-error]) (ed-has-tag? f :lifecycle/loading)
              (ed-has-tag? f :lifecycle/error)])))))

(deftest article-editor
  (editor-create-test)
  (editor-can-leave-test)
  (editor-pure-helpers-test)
  (editor-edit-load-and-put-test)
  (editor-load-failure-test)
  (editor-delete-test)
  (editor-invalid-submit-test)
  (editor-same-slug-seed-preserves-typing-test)
  (editor-cross-slug-settle-is-refused-test)
  (editor-cross-slug-failure-is-refused-test))

(defn- routing-tests []
  (with-new-frame [f (rf.frame/make-anon-frame-record!
                     {:initial-events [[:app/initialise]]
                      :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.article/show :params {:slug "hello"}}] {:frame f})
    (is (= [:realworld.article/show "hello"]
           [(sub f [:rf.route/id]) (:slug (sub f [:rf.route/params]))]))
    (rf/dispatch-sync [:rf.route/handle-url-change "/profile/eve"] {:frame f})
    (is (= :realworld.profile/show (sub f [:rf.route/id])))
    (rf/dispatch-sync [:auth/store-session {:username "eve" :token "t"}] {:frame f})
    (rf/dispatch-sync [:rf.route/handle-url-change "/settings"] {:frame f})
    (is (= :realworld.user/settings (sub f [:rf.route/id])))
    (rf/dispatch-sync [:rf.route/handle-url-change "/tag/clojure"] {:frame f})
    (is (= [:realworld/home-tag "clojure"]
           [(sub f [:rf.route/id]) (:tag (sub f [:rf.route/params]))]))
    (rf/dispatch-sync [:rf.route/handle-url-change "/garbage/path"] {:frame f})
    (is (= :rf.route/not-found (sub f [:rf.route/id])))))

(defn- auth-guard-test []
  (with-new-frame [f (rf.frame/make-anon-frame-record!
                     {:initial-events [[:app/initialise]]
                      :fx-overrides {:rf.http/managed :realworld.test/canned-success-empty}})]
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.user/settings}] {:frame f})
    (is (= :realworld.auth/login (sub f [:rf.route/id])))
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld/home}] {:frame f})
    (is (= :realworld/home (sub f [:rf.route/id])))
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.user/settings}] {:frame f})
    (let [state (rf/frame-state-value f)]
      (is (= [{:to :realworld.user/settings} nil]
             [(get-in state [:rf.db/app :auth :return-to])
              (get-in state [:rf.db/runtime :rf.runtime/routing :pending-navigation])])))
    (rf/dispatch-sync [:auth/store-session {:username "eve" :token "t"}] {:frame f})
    (rf/dispatch-sync [:rf.route/navigate {:to :realworld.user/settings}] {:frame f})
    (is (= :realworld.user/settings (sub f [:rf.route/id])))
    (rf/dispatch-sync [:auth/post-login-redirect] {:frame f})
    (is (nil? (get-in (rf/frame-state-value f) [:rf.db/app :auth :return-to])))))

(deftest routing
  (routing-tests)
  (auth-guard-test))
