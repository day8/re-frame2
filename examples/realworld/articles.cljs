(ns example.realworld.articles
  "The article-list page for the RealWorld (Conduit) example.

   Demonstrates the canonical Pattern-RemoteData lifecycle:
   - The :articles slice carries the standard 5-key shape
     (:status / :data / :error / :loaded-at / :attempt).
   - Four standard events: :articles/load, :articles/loaded,
     :articles/load-failed, :articles/reset.
   - Convenience subs: :articles/loading?, :articles/fetching?,
     :articles/has-data?.
   - Views render explicitly on :loading? / :error / loaded-data per the
     pattern's conformance checklist.

   The route's :on-match dispatches :articles/load when the home page
   becomes active (per Spec 012 §Per-route data loading)."
  (:require [re-frame.core :as rf]
            [example.realworld.schema]
            [example.realworld.http]
            [example.realworld.routing :as routing]))

(defn current-time-ms []
  #?(:cljs (.getTime (js/Date.))
     :clj  (System/currentTimeMillis)))

;; ============================================================================
;; INITIALISATION
;; ============================================================================

(rf/reg-event-db :articles/initialise
  {:doc "Seed the :articles slice to the standard :idle shape."}
  (fn handler-articles-initialise [db _]
    (assoc db :articles {:status :idle :data nil :error nil
                         :loaded-at nil :attempt 0})))

;; ============================================================================
;; LIFECYCLE EVENTS  (Pattern-RemoteData four standard events)
;; ============================================================================

(rf/reg-event-fx :articles/load
  {:doc "Fetch the global articles list. Sets :loading (no prior :data) or
         :fetching (revalidate over existing :data); the :http fx dispatches
         :articles/loaded or :articles/load-failed on completion."}
  (fn handler-articles-load [{:keys [db]} _]
    (let [has-data? (some? (get-in db [:articles :data]))]
      {:db (-> db
               (assoc-in  [:articles :status] (if has-data? :fetching :loading))
               (assoc-in  [:articles :error]  nil)
               (update-in [:articles :attempt] (fnil inc 0)))
       :fx [[:http {:method     :get
                    :url        "/articles"
                    :auth?      false   ;; global feed is public
                    :on-success [:articles/loaded]
                    :on-error   [:articles/load-failed]}]]})))

(rf/reg-event-db :articles/loaded
  {:doc "Successful fetch. Replace :data; clear :error; bump :loaded-at."}
  (fn handler-articles-loaded [db [_ resp]]
    (-> db
        (assoc-in [:articles :status]    :loaded)
        (assoc-in [:articles :data]      (:articles resp))
        (assoc-in [:articles :error]     nil)
        (assoc-in [:articles :loaded-at] (current-time-ms)))))

(rf/reg-event-db :articles/load-failed
  {:doc "Failed fetch. Set :error; keep prior :data if any."}
  (fn handler-articles-load-failed [db [_ err]]
    (-> db
        (assoc-in [:articles :status] :error)
        (assoc-in [:articles :error]  err))))

(rf/reg-event-db :articles/reset
  {:doc "Reset the slice to :idle. Useful on navigation away."}
  (fn handler-articles-reset [db _]
    (assoc db :articles {:status :idle :data nil :error nil
                         :loaded-at nil :attempt 0})))

;; ============================================================================
;; TAGS  (sidebar — uses the same lifecycle shape)
;; ============================================================================

(rf/reg-event-db :tags/initialise
  (fn [db _]
    (assoc db :tags {:status :idle :data nil :error nil
                     :loaded-at nil :attempt 0})))

(rf/reg-event-fx :tags/load
  {:doc "Fetch the popular tags list."}
  (fn handler-tags-load [{:keys [db]} _]
    {:db (-> db
             (assoc-in  [:tags :status] (if (get-in db [:tags :data]) :fetching :loading))
             (update-in [:tags :attempt] (fnil inc 0)))
     :fx [[:http {:method     :get
                  :url        "/tags"
                  :auth?      false
                  :on-success [:tags/loaded]
                  :on-error   [:tags/load-failed]}]]}))

(rf/reg-event-db :tags/loaded
  (fn [db [_ resp]]
    (-> db
        (assoc-in [:tags :status]    :loaded)
        (assoc-in [:tags :data]      (:tags resp))
        (assoc-in [:tags :loaded-at] (current-time-ms)))))

(rf/reg-event-db :tags/load-failed
  (fn [db [_ err]]
    (-> db
        (assoc-in [:tags :status] :error)
        (assoc-in [:tags :error]  err))))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :articles            (fn [db _] (get db :articles)))
(rf/reg-sub :articles/status     :<- [:articles] (fn [s _] (:status s)))
(rf/reg-sub :articles/data       :<- [:articles] (fn [s _] (:data s)))
(rf/reg-sub :articles/error      :<- [:articles] (fn [s _] (:error s)))
(rf/reg-sub :articles/loading?   :<- [:articles/status] #(= % :loading))
(rf/reg-sub :articles/fetching?  :<- [:articles/status]
  #(or (= % :loading) (= % :fetching)))
(rf/reg-sub :articles/has-data?  :<- [:articles/data] some?)

(rf/reg-sub :tags                (fn [db _] (get db :tags)))
(rf/reg-sub :tags/data           :<- [:tags] (fn [s _] (:data s)))
(rf/reg-sub :tags/loading?       :<- [:tags] (fn [s _] (= (:status s) :loading)))

;; ============================================================================
;; VIEWS
;; ============================================================================

(def article-preview
  (rf/reg-view :articles/preview
    {:doc "A single article card in the list."}
    (fn render-article-preview [{:keys [article]}]
      (let [{:keys [slug title description createdAt favoritesCount author tagList]} article]
        [:div.article-preview
         [:div.article-meta
          [routing/route-link {:to     :route/profile
                               :params {:username (:username author)}}
           [:img {:src (:image author)}]]
          [:div.info
           [routing/route-link {:to     :route/profile
                                :params {:username (:username author)}
                                :class  "author"}
            (:username author)]
           [:span.date createdAt]]
          [:button.btn.btn-outline-primary.btn-sm.pull-xs-right
           {:on-click #(dispatch [:article/toggle-favorite slug])}
           [:i.ion-heart] " " favoritesCount]]
         [routing/route-link {:to :route/article :params {:slug slug} :class "preview-link"}
          [:h1 title]
          [:p description]
          [:span "Read more..."]
          [:ul.tag-list
           (for [t tagList]
             ^{:key t} [:li.tag-default.tag-pill.tag-outline t])]]]))))

(def home-page
  (rf/reg-view :pages/home
    {:doc "Home page — the global feed plus the popular-tags sidebar."}
    (fn render-home []
      (let [loading?  @(subscribe [:articles/loading?])
            fetching? @(subscribe [:articles/fetching?])
            err       @(subscribe [:articles/error])
            articles  @(subscribe [:articles/data])
            tags      @(subscribe [:tags/data])]
        [:div.home-page
         [:div.banner
          [:div.container [:h1.logo-font "conduit"]
           [:p "A place to share your knowledge."]]]
         [:div.container.page
          [:div.row
           [:div.col-md-9
            [:div.feed-toggle
             [:ul.nav.nav-pills.outline-active
              [:li.nav-item [:a.nav-link.active "Global Feed"]]]]
            (cond
              loading?
              [:div.article-preview "Loading articles…"]

              err
              [:div.article-preview.error
               (str "Couldn't load articles: " (pr-str err))]

              (empty? articles)
              [:div.article-preview "No articles are here… yet."]

              :else
              [:<>
               (when fetching? [:div.refresh-indicator "Refreshing…"])
               (for [a articles]
                 ^{:key (:slug a)} [article-preview {:article a}])])]
           [:div.col-md-3
            [:div.sidebar
             [:p "Popular Tags"]
             [:div.tag-list
              (for [t tags]
                ^{:key t} [:a.tag-pill.tag-default t])]]]]]]))))

;; ============================================================================
;; HEADLESS TESTS
;; ============================================================================

(defn articles-load-test []
  (rf/reg-fx :http.canned-articles
    {:platforms #{:client :server}}
    (fn [{:keys [frame]} {:keys [on-success]}]
      (when on-success
        (rf/dispatch (conj on-success
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
                     {:frame frame}))))

  (rf/with-frame [f (rf/make-frame {:on-create    [:articles/initialise]
                                    :fx-overrides {:http :http.canned-articles}})]
    ;; Initial state.
    (assert (= :idle (:status (rf/compute-sub [:articles] @(rf/get-frame-db f)))))

    ;; Load -> :loading -> :loaded with the canned data.
    (rf/dispatch-sync [:articles/load] {:frame f})
    (let [slice (rf/compute-sub [:articles] @(rf/get-frame-db f))]
      (assert (= :loaded (:status slice)))
      (assert (= 1       (count  (:data slice))))
      (assert (= "hello-world" (-> slice :data first :slug))))

    ;; Re-load over existing data -> :fetching, then :loaded again.
    (rf/dispatch-sync [:articles/load] {:frame f})
    (let [slice (rf/compute-sub [:articles] @(rf/get-frame-db f))]
      (assert (= :loaded (:status slice)))
      (assert (= 2 (:attempt slice))))))

(defn articles-load-failure-test []
  (rf/reg-fx :http.canned-failure
    {:platforms #{:client :server}}
    (fn [{:keys [frame]} {:keys [on-error]}]
      (when on-error
        (rf/dispatch (conj on-error {:errors {:body ["server error"]}})
                     {:frame frame}))))

  (rf/with-frame [f (rf/make-frame {:on-create    [:articles/initialise]
                                    :fx-overrides {:http :http.canned-failure}})]
    (rf/dispatch-sync [:articles/load] {:frame f})
    (assert (= :error (:status (rf/compute-sub [:articles] @(rf/get-frame-db f)))))
    (assert (some? (rf/compute-sub [:articles/error] @(rf/get-frame-db f))))))
