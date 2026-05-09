(ns realworld.profile
  "Profile pages for the RealWorld (Conduit) example.

   One route family, three remote-data slices:
   - `:profile` for the public profile banner
   - `:profile.articles` for authored articles
   - `:profile.favorites` for favorited articles

   Follow/unfollow is optimistic and shared across the banner plus any
   article cards rendered from the profile routes."
  (:require [re-frame.core :as rf]
            [realworld.schema :as schema]
            [realworld.http :as rh]
            [realworld.routing :as routing]
            [realworld.articles :as articles])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

(defn current-time-ms [] (.getTime (js/Date.)))

(defn request-slice []
  {:status :idle :data nil :error nil :loaded-at nil :attempt 0})

(defn username-from-db [db]
  (get-in db [:route :params :username]))

;; ============================================================================
;; INITIALISATION
;; ============================================================================

(rf/reg-event-db :profile/initialise
  (fn [db _]
    (-> db
        (assoc :profile (request-slice))
        (assoc :profile.articles (assoc (request-slice) :data []))
        (assoc :profile.favorites (assoc (request-slice) :data [])))))

;; ============================================================================
;; LOADS
;; ============================================================================

(rf/reg-event-fx :profile/load
  {:doc "Load the public profile (username, bio, image, following).
         Public endpoint; data-fetch retry."
   :rf.http/decode-schemas [schema/ProfileResponse]}
  (fn [{:keys [db]} _]
    (let [username (username-from-db db)]
      {:db (-> db
               (assoc-in [:profile :status]
                         (if (get-in db [:profile :data]) :fetching :loading))
               (assoc-in [:profile :error] nil)
               (update-in [:profile :attempt] (fnil inc 0)))
       :fx [[:rf.http/managed
             (rh/request {:method     :get
                          :path       (str "/profiles/" username)
                          :auth?      false
                          :decode     schema/ProfileResponse
                          :retry      rh/data-fetch-retry
                          :request-id [:profile/load username]
                          :on-success [:profile/loaded]
                          :on-failure [:profile/load-failed]})]]})))

(rf/reg-event-db :profile/loaded
  (fn [db [_ {:keys [value]}]]
    (-> db
        (assoc-in [:profile :status] :loaded)
        (assoc-in [:profile :data] (:profile value))
        (assoc-in [:profile :error] nil)
        (assoc-in [:profile :loaded-at] (current-time-ms)))))

(rf/reg-event-db :profile/load-failed
  (fn [db [_ {:keys [failure]}]]
    (-> db
        (assoc-in [:profile :status] :error)
        (assoc-in [:profile :error] (rh/failure->message failure)))))

(rf/reg-event-fx :profile.articles/load
  {:doc "Load the profile's authored articles. Public; data-fetch retry."
   :rf.http/decode-schemas [schema/ArticlesResponse]}
  (fn [{:keys [db]} _]
    (let [username (username-from-db db)]
      {:db (-> db
               (assoc-in [:profile.articles :status] :loading)
               (assoc-in [:profile.articles :error] nil)
               (update-in [:profile.articles :attempt] (fnil inc 0)))
       :fx [[:rf.http/managed
             (rh/request {:method     :get
                          :path       (str "/articles?author=" username)
                          :auth?      false
                          :decode     schema/ArticlesResponse
                          :retry      rh/data-fetch-retry
                          :request-id [:profile.articles/load username]
                          :on-success [:profile.articles/loaded]
                          :on-failure [:profile.articles/load-failed]})]]})))

(rf/reg-event-db :profile.articles/loaded
  (fn [db [_ {:keys [value]}]]
    (-> db
        (assoc-in [:profile.articles :status] :loaded)
        (assoc-in [:profile.articles :data] (vec (:articles value)))
        (assoc-in [:profile.articles :loaded-at] (current-time-ms)))))

(rf/reg-event-db :profile.articles/load-failed
  (fn [db [_ {:keys [failure]}]]
    (-> db
        (assoc-in [:profile.articles :status] :error)
        (assoc-in [:profile.articles :error] (rh/failure->message failure)))))

(rf/reg-event-fx :profile.favorites/load
  {:doc "Load the profile's favorited articles. Public; data-fetch retry."
   :rf.http/decode-schemas [schema/ArticlesResponse]}
  (fn [{:keys [db]} _]
    (let [username (username-from-db db)]
      {:db (-> db
               (assoc-in [:profile.favorites :status] :loading)
               (assoc-in [:profile.favorites :error] nil)
               (update-in [:profile.favorites :attempt] (fnil inc 0)))
       :fx [[:rf.http/managed
             (rh/request {:method     :get
                          :path       (str "/articles?favorited=" username)
                          :auth?      false
                          :decode     schema/ArticlesResponse
                          :retry      rh/data-fetch-retry
                          :request-id [:profile.favorites/load username]
                          :on-success [:profile.favorites/loaded]
                          :on-failure [:profile.favorites/load-failed]})]]})))

(rf/reg-event-db :profile.favorites/loaded
  (fn [db [_ {:keys [value]}]]
    (-> db
        (assoc-in [:profile.favorites :status] :loaded)
        (assoc-in [:profile.favorites :data] (vec (:articles value)))
        (assoc-in [:profile.favorites :loaded-at] (current-time-ms)))))

(rf/reg-event-db :profile.favorites/load-failed
  (fn [db [_ {:keys [failure]}]]
    (-> db
        (assoc-in [:profile.favorites :status] :error)
        (assoc-in [:profile.favorites :error] (rh/failure->message failure)))))

;; ============================================================================
;; FOLLOW / UNFOLLOW
;; ============================================================================

(rf/reg-event-fx :profile/follow
  {:doc "Optimistically mark the profile as followed; reconcile on reply."
   :rf.http/decode-schemas [schema/ProfileResponse]}
  (fn [{:keys [db]} _]
    (let [username (username-from-db db)]
      {:db (assoc-in db [:profile :data :following] true)
       :fx [[:rf.http/managed
             (rh/request {:method     :post
                          :path       (str "/profiles/" username "/follow")
                          :decode     schema/ProfileResponse
                          :on-success [:profile/followed]
                          :on-failure [:profile/follow-rollback false]})]]})))

(rf/reg-event-db :profile/followed
  (fn [db [_ {:keys [value]}]]
    (assoc-in db [:profile :data] (:profile value))))

(rf/reg-event-fx :profile/unfollow
  {:doc "Optimistically clear the followed flag; reconcile on reply."
   :rf.http/decode-schemas [schema/ProfileResponse]}
  (fn [{:keys [db]} _]
    (let [username (username-from-db db)]
      {:db (assoc-in db [:profile :data :following] false)
       :fx [[:rf.http/managed
             (rh/request {:method     :delete
                          :path       (str "/profiles/" username "/follow")
                          :decode     schema/ProfileResponse
                          :on-success [:profile/unfollowed]
                          :on-failure [:profile/follow-rollback true]})]]})))

(rf/reg-event-db :profile/unfollowed
  (fn [db [_ {:keys [value]}]]
    (assoc-in db [:profile :data] (:profile value))))

(rf/reg-event-db :profile/follow-rollback
  (fn [db [_ previous-value _failure-payload]]
    (assoc-in db [:profile :data :following] previous-value)))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :profile (fn [db _] (:profile db)))
(rf/reg-sub :profile/data :<- [:profile] (fn [slice _] (:data slice)))
(rf/reg-sub :profile/error :<- [:profile] (fn [slice _] (:error slice)))
(rf/reg-sub :profile/loading? :<- [:profile]
  (fn [slice _] (#{:loading :fetching} (:status slice))))

(rf/reg-sub :profile.articles/data
  (fn [db _] (get-in db [:profile.articles :data])))

(rf/reg-sub :profile.favorites/data
  (fn [db _] (get-in db [:profile.favorites :data])))

(rf/reg-sub :profile/current-tab
  (fn [db _]
    (if (= :route/profile.favorites (get-in db [:route :id]))
      :favorites
      :articles)))

(rf/reg-sub :profile/current-articles
  (fn [db _]
    (if (= :route/profile.favorites (get-in db [:route :id]))
      (get-in db [:profile.favorites :data])
      (get-in db [:profile.articles :data]))))

(rf/reg-sub :profile/own-profile?
  (fn [db _]
    (= (get-in db [:auth :user :username])
       (get-in db [:profile :data :username]))))

;; ============================================================================
;; VIEWS
;; ============================================================================

(reg-view profile-page []
  (let [profile       @(subscribe [:profile/data])
        profile-error @(subscribe [:profile/error])
        loading?      @(subscribe [:profile/loading?])
        own?          @(subscribe [:profile/own-profile?])
        tab           @(subscribe [:profile/current-tab])
        articles*     @(subscribe [:profile/current-articles])]
    [:div.profile-page
     (cond
       loading?
       [:div.article-preview "Loading profile…"]

       profile-error
       [:div.article-preview.error
        (str "Couldn't load profile: " (pr-str profile-error))]

       profile
       [:<>
        [:div.user-info
         [:div.container
          [:div.row
           [:div.col-xs-12.col-md-10.offset-md-1
            [:img.user-img {:src (:image profile)}]
            [:h4 (:username profile)]
            [:p (:bio profile)]
            (if own?
              [routing/route-link {:to :route/settings
                                   :class "btn btn-sm btn-outline-secondary action-btn"}
               [:i.ion-gear-a] " Edit Profile Settings"]
              [:button.btn.btn-sm.btn-outline-secondary.action-btn
               {:type "button"
                :on-click #(dispatch [(if (:following profile)
                                        :profile/unfollow
                                        :profile/follow)])}
               (if (:following profile) "Unfollow " "Follow ")
               (:username profile)])]]]]
        [:div.container
         [:div.row
          [:div.col-xs-12.col-md-10.offset-md-1
           [:div.articles-toggle
            [:ul.nav.nav-pills.outline-active
             [:li.nav-item
              [routing/route-link {:to     :route/profile
                                   :params {:username (:username profile)}
                                   :class  (str "nav-link" (when (= tab :articles) " active"))}
               "My Articles"]]
             [:li.nav-item
              [routing/route-link {:to     :route/profile.favorites
                                   :params {:username (:username profile)}
                                   :class  (str "nav-link" (when (= tab :favorites) " active"))}
               "Favorited Articles"]]]]
           (if (seq articles*)
             (for [article articles*]
               ^{:key (:slug article)}
               [articles/article-preview {:article article}])
             [:div.article-preview "No articles here yet."])]]]]

       :else
       [:div.article-preview "No profile loaded."])]))

