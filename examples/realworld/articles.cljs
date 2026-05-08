(ns example.realworld.articles
  "Home-page article feeds for the RealWorld (Conduit) example.

   This sketch demonstrates:
   - Pattern-RemoteData for the global article list and popular tags
   - route-query driven loading (`?tag=` and `?feed=your`)
   - home-page tabs expressed as ordinary navigation events
   - view reuse across the home page and profile pages"
  (:require [re-frame-2.core :as rf]
            [example.realworld.schema]
            [example.realworld.http]
            [example.realworld.routing :as routing])
  (:require-macros [re-frame-2.views-macros :refer [reg-view with-frame]]))

(defn current-time-ms []
  #?(:cljs (.getTime (js/Date.))
     :clj  (System/currentTimeMillis)))

(defn request-slice [data]
  {:status :idle :data data :error nil :loaded-at nil :attempt 0})

;; ============================================================================
;; INITIALISATION
;; ============================================================================

(rf/reg-event-db :articles/initialise
  {:doc "Seed the global-articles slice to the standard idle shape."}
  (fn [db _]
    (assoc db :articles (request-slice []))))

(rf/reg-event-db :tags/initialise
  (fn [db _]
    (assoc db :tags (request-slice []))))

;; ============================================================================
;; GLOBAL FEED
;; ============================================================================

(rf/reg-event-fx :articles/load
  {:doc "Fetch the global articles list, optionally filtered by the route's
         `?tag=` query parameter."}
  (fn [{:keys [db]} _]
    (let [tag      (get-in db [:route :query :tag])
          url      (if tag
                     (str "/articles?tag=" tag)
                     "/articles")
          has-data? (seq (get-in db [:articles :data]))]
      {:db (-> db
               (assoc-in [:articles :status] (if has-data? :fetching :loading))
               (assoc-in [:articles :error] nil)
               (update-in [:articles :attempt] (fnil inc 0)))
       :fx [[:http {:method     :get
                    :url        url
                    :auth?      false
                    :on-success [:articles/loaded]
                    :on-error   [:articles/load-failed]}]]})))

(rf/reg-event-db :articles/loaded
  {:doc "Successful fetch. Replace the list and clear any prior error."}
  (fn [db [_ resp]]
    (-> db
        (assoc-in [:articles :status] :loaded)
        (assoc-in [:articles :data] (vec (:articles resp)))
        (assoc-in [:articles :error] nil)
        (assoc-in [:articles :loaded-at] (current-time-ms)))))

(rf/reg-event-db :articles/load-failed
  {:doc "Failed fetch. Keep prior data when present and surface the error."}
  (fn [db [_ err]]
    (-> db
        (assoc-in [:articles :status] :error)
        (assoc-in [:articles :error] err))))

(rf/reg-event-db :articles/reset
  (fn [db _]
    (assoc db :articles (request-slice []))))

;; ============================================================================
;; TAGS
;; ============================================================================

(rf/reg-event-fx :tags/load
  (fn [{:keys [db]} _]
    {:db (-> db
             (assoc-in [:tags :status]
                       (if (seq (get-in db [:tags :data])) :fetching :loading))
             (assoc-in [:tags :error] nil)
             (update-in [:tags :attempt] (fnil inc 0)))
     :fx [[:http {:method     :get
                  :url        "/tags"
                  :auth?      false
                  :on-success [:tags/loaded]
                  :on-error   [:tags/load-failed]}]]}))

(rf/reg-event-db :tags/loaded
  (fn [db [_ resp]]
    (-> db
        (assoc-in [:tags :status] :loaded)
        (assoc-in [:tags :data] (vec (:tags resp)))
        (assoc-in [:tags :error] nil)
        (assoc-in [:tags :loaded-at] (current-time-ms)))))

(rf/reg-event-db :tags/load-failed
  (fn [db [_ err]]
    (-> db
        (assoc-in [:tags :status] :error)
        (assoc-in [:tags :error] err))))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :articles            (fn [db _] (:articles db)))
(rf/reg-sub :articles/status     :<- [:articles] (fn [s _] (:status s)))
(rf/reg-sub :articles/data       :<- [:articles] (fn [s _] (:data s)))
(rf/reg-sub :articles/error      :<- [:articles] (fn [s _] (:error s)))
(rf/reg-sub :articles/loading?   :<- [:articles/status] #(= % :loading))
(rf/reg-sub :articles/fetching?  :<- [:articles/status]
  #(or (= % :loading) (= % :fetching)))

(rf/reg-sub :tags                (fn [db _] (:tags db)))
(rf/reg-sub :tags/data           :<- [:tags] (fn [s _] (:data s)))
(rf/reg-sub :tags/loading?       :<- [:tags]
  (fn [s _] (#{:loading :fetching} (:status s))))

;; ============================================================================
;; VIEWS
;; ============================================================================

(def article-preview
  (reg-view :articles/preview
    {:doc "A single article card used across the home page and profile pages."}
    (fn render-article-preview [{:keys [article]}]
      (let [d (rf/dispatcher)
            {:keys [slug title description createdAt favoritesCount author tagList]} article]
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
           {:type "button"
            :on-click #(d [:article/toggle-favorite slug])}
           [:i.ion-heart] " " favoritesCount]]
         [routing/route-link {:to :route/article :params {:slug slug} :class "preview-link"}
          [:h1 title]
          [:p description]
          [:span "Read more..."]
          [:ul.tag-list
           (for [tag tagList]
             ^{:key tag}
             [:li.tag-default.tag-pill.tag-outline tag])]]]))))

(def home-page
  (reg-view :pages/home
    {:doc "Global feed / your feed / tag-filtered home page."}
    (fn render-home []
      (let [d              (rf/dispatcher)
            s              (rf/subscriber)
            authed?        @(s [:auth/authenticated?])
            feed-kind      @(s [:home/feed-kind])
            selected-tag   @(s [:home/selected-tag])
            global-loading? @(s [:articles/loading?])
            global-fetching? @(s [:articles/fetching?])
            global-error   @(s [:articles/error])
            global-articles @(s [:articles/data])
            feed-loading?  @(s [:feed/loading?])
            feed-error     @(s [:feed/error])
            your-feed      @(s [:feed/data])
            tags           @(s [:tags/data])
            [loading? fetching? err articles]
            (if (= feed-kind :your)
              [feed-loading? false feed-error your-feed]
              [global-loading? global-fetching? global-error global-articles])]
        [:div.home-page
         [:div.banner
          [:div.container
           [:h1.logo-font "conduit"]
           [:p "A place to share your knowledge."]]]
         [:div.container.page
          [:div.row
           [:div.col-md-9
            [:div.feed-toggle
             [:ul.nav.nav-pills.outline-active
              (when authed?
                [:li.nav-item
                 [:a.nav-link
                  {:href "#"
                   :class (when (= feed-kind :your) "active")
                   :on-click #(do (.preventDefault %)
                                  (d [:home/show-your-feed]))}
                  "Your Feed"]])
              [:li.nav-item
               [:a.nav-link
                {:href "#"
                 :class (when (= feed-kind :global) "active")
                 :on-click #(do (.preventDefault %)
                                (d [:home/show-global-feed]))}
                "Global Feed"]]
              (when selected-tag
                [:li.nav-item
                 [:a.nav-link.active
                  {:href "#"
                   :on-click #(do (.preventDefault %)
                                  (d [:tags/clear-filter]))}
                  [:i.ion-pound] " " selected-tag]])]]
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
               (for [article articles]
                 ^{:key (:slug article)}
                 [article-preview {:article article}])])]
           [:div.col-md-3
            [:div.sidebar
             [:p "Popular Tags"]
             [:div.tag-list
              (for [tag tags]
                ^{:key tag}
                [:a.tag-pill.tag-default
                 {:href "#"
                  :on-click #(do (.preventDefault %)
                                 (d [:tags/apply-filter tag]))}
                 tag])]]]]]]))))

;; ============================================================================
;; HEADLESS TESTS
;; ============================================================================

(defn articles-load-test []
  (rf/reg-fx :http.canned-articles
    {:platforms #{:client :server}}
    (fn [{:keys [frame]} {:keys [on-success]}]
      (when on-success
        (rf/dispatch
          (conj on-success
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

  (with-frame [f (rf/make-frame {:on-create [:app/initialise]
                                 :fx-overrides {:http :http.canned-articles}})]
    (assert (= :idle (:status (rf/compute-sub [:articles] (rf/get-frame-db f)))))
    (rf/dispatch-sync [:articles/load] {:frame f})
    (let [slice (rf/compute-sub [:articles] (rf/get-frame-db f))]
      (assert (= :loaded (:status slice)))
      (assert (= 1 (count (:data slice))))
      (assert (= "hello-world" (-> slice :data first :slug))))
    (rf/dispatch-sync [:articles/load] {:frame f})
    (let [slice (rf/compute-sub [:articles] (rf/get-frame-db f))]
      (assert (= :loaded (:status slice)))
      (assert (= 2 (:attempt slice))))))

(defn articles-load-failure-test []
  (rf/reg-fx :http.canned-failure
    {:platforms #{:client :server}}
    (fn [{:keys [frame]} {:keys [on-error]}]
      (when on-error
        (rf/dispatch (conj on-error {:errors {:body ["server error"]}})
                     {:frame frame}))))

  (with-frame [f (rf/make-frame {:on-create [:app/initialise]
                                 :fx-overrides {:http :http.canned-failure}})]
    (rf/dispatch-sync [:articles/load] {:frame f})
    (assert (= :error (:status (rf/compute-sub [:articles] (rf/get-frame-db f)))))
    (assert (some? (rf/compute-sub [:articles/error] (rf/get-frame-db f))))))
