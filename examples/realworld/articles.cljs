(ns realworld.articles
  "Home-page article feeds for the RealWorld (Conduit) example.

   This sketch demonstrates:
   - Pattern-RemoteData for the global article list and popular tags
   - route-query driven loading (`?tag=` and `?feed=your`)
   - home-page tabs expressed as ordinary navigation events
   - view reuse across the home page and profile pages"
  (:require [re-frame.core :as rf]
            [realworld.schema :as schema]
            [realworld.http :as rh]
            [realworld.routing :as routing])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

(defn current-time-ms [] (.getTime (js/Date.)))

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
         `?tag=` query parameter. Uses :rf.http/managed (Spec 014) with
         a Malli-decoded response and the standard data-fetch retry policy.
         The request is tagged with `:request-id :articles/load` so a
         re-issue (e.g., user changes tag mid-load) supersedes the prior
         in-flight request and an `:articles/cancel` fx aborts cleanly."
   :rf.http/decode-schemas [schema/ArticlesResponse]}
  (fn [{:keys [db]} _]
    (let [tag       (get-in db [:route :query :tag])
          path      (if tag
                      (str "/articles?tag=" tag)
                      "/articles")
          has-data? (seq (get-in db [:articles :data]))]
      {:db (-> db
               (assoc-in [:articles :status] (if has-data? :fetching :loading))
               (assoc-in [:articles :error] nil)
               (update-in [:articles :attempt] (fnil inc 0)))
       :fx [[:rf.http/managed
             (rh/request {:method     :get
                          :path       path
                          :auth?      false
                          :decode     schema/ArticlesResponse
                          :retry      rh/data-fetch-retry
                          :request-id :articles/load
                          :on-success [:articles/loaded]
                          :on-failure [:articles/load-failed]})]]})))

(rf/reg-event-db :articles/loaded
  {:doc "Successful fetch. Replace the list and clear any prior error.
         Receives `{:kind :success :value <ArticlesResponse>}` from
         Spec 014's reply-addressing."}
  (fn [db [_ {:keys [value]}]]
    (-> db
        (assoc-in [:articles :status] :loaded)
        (assoc-in [:articles :data] (vec (:articles value)))
        (assoc-in [:articles :error] nil)
        (assoc-in [:articles :loaded-at] (current-time-ms)))))

(rf/reg-event-db :articles/load-failed
  {:doc "Failed fetch. Keep prior data when present and surface a
         human-readable error message (projected from the Spec 014
         failure map)."}
  (fn [db [_ {:keys [failure]}]]
    (-> db
        (assoc-in [:articles :status] :error)
        (assoc-in [:articles :error] (rh/failure->message failure)))))

(rf/reg-event-fx :articles/cancel
  {:doc "Abort an in-flight :articles/load. Useful when the user navigates
         away from the home page mid-fetch (Spec 014 §Aborts)."}
  (fn [_ _]
    {:fx [[:rf.http/managed-abort :articles/load]]}))

(rf/reg-event-db :articles/reset
  (fn [db _]
    (assoc db :articles (request-slice []))))

;; ============================================================================
;; TAGS
;; ============================================================================

(rf/reg-event-fx :tags/load
  {:doc "Fetch the popular-tags list. Public endpoint; data-fetch retry."
   :rf.http/decode-schemas [schema/TagsResponse]}
  (fn [{:keys [db]} _]
    {:db (-> db
             (assoc-in [:tags :status]
                       (if (seq (get-in db [:tags :data])) :fetching :loading))
             (assoc-in [:tags :error] nil)
             (update-in [:tags :attempt] (fnil inc 0)))
     :fx [[:rf.http/managed
           (rh/request {:method     :get
                        :path       "/tags"
                        :auth?      false
                        :decode     schema/TagsResponse
                        :retry      rh/data-fetch-retry
                        :request-id :tags/load
                        :on-success [:tags/loaded]
                        :on-failure [:tags/load-failed]})]]}))

(rf/reg-event-db :tags/loaded
  (fn [db [_ {:keys [value]}]]
    (-> db
        (assoc-in [:tags :status] :loaded)
        (assoc-in [:tags :data] (vec (:tags value)))
        (assoc-in [:tags :error] nil)
        (assoc-in [:tags :loaded-at] (current-time-ms)))))

(rf/reg-event-db :tags/load-failed
  (fn [db [_ {:keys [failure]}]]
    (-> db
        (assoc-in [:tags :status] :error)
        (assoc-in [:tags :error] (rh/failure->message failure)))))

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

(reg-view ^{:doc "A single article card used across the home page and profile pages."}
          article-preview [{:keys [article]}]
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
       {:type "button"
        :on-click #(dispatch [:article/toggle-favorite slug])}
       [:i.ion-heart] " " favoritesCount]]
     [routing/route-link {:to :route/article :params {:slug slug} :class "preview-link"}
      [:h1 title]
      [:p description]
      [:span "Read more..."]
      [:ul.tag-list
       (for [tag tagList]
         ^{:key tag}
         [:li.tag-default.tag-pill.tag-outline tag])]]]))

(reg-view ^{:doc "Global feed / your feed / tag-filtered home page."}
          home-page []
  (let [authed?         @(subscribe [:auth/authenticated?])
        feed-kind       @(subscribe [:home/feed-kind])
        selected-tag    @(subscribe [:home/selected-tag])
        global-loading?  @(subscribe [:articles/loading?])
        global-fetching? @(subscribe [:articles/fetching?])
        global-error    @(subscribe [:articles/error])
        global-articles @(subscribe [:articles/data])
        feed-loading?   @(subscribe [:feed/loading?])
        feed-error      @(subscribe [:feed/error])
        your-feed       @(subscribe [:feed/data])
        tags            @(subscribe [:tags/data])
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
                              (dispatch [:home/show-your-feed]))}
              "Your Feed"]])
          [:li.nav-item
           [:a.nav-link
            {:href "#"
             :class (when (= feed-kind :global) "active")
             :on-click #(do (.preventDefault %)
                            (dispatch [:home/show-global-feed]))}
            "Global Feed"]]
          (when selected-tag
            [:li.nav-item
             [:a.nav-link.active
              {:href "#"
               :on-click #(do (.preventDefault %)
                              (dispatch [:tags/clear-filter]))}
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
                             (dispatch [:tags/apply-filter tag]))}
             tag])]]]]]]))

