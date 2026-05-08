(ns example.realworld.profile
  "Profile pages for the RealWorld (Conduit) example.

   One route family, three remote-data slices:
   - `:profile` for the public profile banner
   - `:profile.articles` for authored articles
   - `:profile.favorites` for favorited articles

   Follow/unfollow is optimistic and shared across the banner plus any
   article cards rendered from the profile routes."
  (:require [clojure.string :as str]
            [re-frame-2.core :as rf]
            [example.realworld.schema]
            [example.realworld.http]
            [example.realworld.routing :as routing]
            [example.realworld.articles :as articles])
  (:require-macros [re-frame-2.views-macros :refer [reg-view with-frame]]))

(defn current-time-ms []
  #?(:cljs (.getTime (js/Date.))
     :clj  (System/currentTimeMillis)))

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
  (fn [{:keys [db]} _]
    (let [username (username-from-db db)]
      {:db (-> db
               (assoc-in [:profile :status]
                         (if (get-in db [:profile :data]) :fetching :loading))
               (assoc-in [:profile :error] nil)
               (update-in [:profile :attempt] (fnil inc 0)))
       :fx [[:http {:method     :get
                    :url        (str "/profiles/" username)
                    :auth?      false
                    :on-success [:profile/loaded]
                    :on-error   [:profile/load-failed]}]]})))

(rf/reg-event-db :profile/loaded
  (fn [db [_ resp]]
    (-> db
        (assoc-in [:profile :status] :loaded)
        (assoc-in [:profile :data] (:profile resp))
        (assoc-in [:profile :error] nil)
        (assoc-in [:profile :loaded-at] (current-time-ms)))))

(rf/reg-event-db :profile/load-failed
  (fn [db [_ err]]
    (-> db
        (assoc-in [:profile :status] :error)
        (assoc-in [:profile :error] err))))

(rf/reg-event-fx :profile.articles/load
  (fn [{:keys [db]} _]
    (let [username (username-from-db db)]
      {:db (-> db
               (assoc-in [:profile.articles :status] :loading)
               (assoc-in [:profile.articles :error] nil)
               (update-in [:profile.articles :attempt] (fnil inc 0)))
       :fx [[:http {:method     :get
                    :url        (str "/articles?author=" username)
                    :auth?      false
                    :on-success [:profile.articles/loaded]
                    :on-error   [:profile.articles/load-failed]}]]})))

(rf/reg-event-db :profile.articles/loaded
  (fn [db [_ resp]]
    (-> db
        (assoc-in [:profile.articles :status] :loaded)
        (assoc-in [:profile.articles :data] (vec (:articles resp)))
        (assoc-in [:profile.articles :loaded-at] (current-time-ms)))))

(rf/reg-event-db :profile.articles/load-failed
  (fn [db [_ err]]
    (-> db
        (assoc-in [:profile.articles :status] :error)
        (assoc-in [:profile.articles :error] err))))

(rf/reg-event-fx :profile.favorites/load
  (fn [{:keys [db]} _]
    (let [username (username-from-db db)]
      {:db (-> db
               (assoc-in [:profile.favorites :status] :loading)
               (assoc-in [:profile.favorites :error] nil)
               (update-in [:profile.favorites :attempt] (fnil inc 0)))
       :fx [[:http {:method     :get
                    :url        (str "/articles?favorited=" username)
                    :auth?      false
                    :on-success [:profile.favorites/loaded]
                    :on-error   [:profile.favorites/load-failed]}]]})))

(rf/reg-event-db :profile.favorites/loaded
  (fn [db [_ resp]]
    (-> db
        (assoc-in [:profile.favorites :status] :loaded)
        (assoc-in [:profile.favorites :data] (vec (:articles resp)))
        (assoc-in [:profile.favorites :loaded-at] (current-time-ms)))))

(rf/reg-event-db :profile.favorites/load-failed
  (fn [db [_ err]]
    (-> db
        (assoc-in [:profile.favorites :status] :error)
        (assoc-in [:profile.favorites :error] err))))

;; ============================================================================
;; FOLLOW / UNFOLLOW
;; ============================================================================

(rf/reg-event-fx :profile/follow
  (fn [{:keys [db]} _]
    (let [username (username-from-db db)]
      {:db (assoc-in db [:profile :data :following] true)
       :fx [[:http {:method     :post
                    :url        (str "/profiles/" username "/follow")
                    :on-success [:profile/followed]
                    :on-error   [:profile/follow-rollback false]}]]})))

(rf/reg-event-db :profile/followed
  (fn [db [_ resp]]
    (assoc-in db [:profile :data] (:profile resp))))

(rf/reg-event-fx :profile/unfollow
  (fn [{:keys [db]} _]
    (let [username (username-from-db db)]
      {:db (assoc-in db [:profile :data :following] false)
       :fx [[:http {:method     :delete
                    :url        (str "/profiles/" username "/follow")
                    :on-success [:profile/unfollowed]
                    :on-error   [:profile/follow-rollback true]}]]})))

(rf/reg-event-db :profile/unfollowed
  (fn [db [_ resp]]
    (assoc-in db [:profile :data] (:profile resp))))

(rf/reg-event-db :profile/follow-rollback
  (fn [db [_ previous-value]]
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

(def profile-page
  (reg-view :pages/profile
    (fn render-profile-page []
      (let [d             (rf/dispatcher)
            s             (rf/subscriber)
            profile       @(s [:profile/data])
            profile-error @(s [:profile/error])
            loading?      @(s [:profile/loading?])
            own?          @(s [:profile/own-profile?])
            tab           @(s [:profile/current-tab])
            articles*     @(s [:profile/current-articles])]
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
                    :on-click #(d [(if (:following profile)
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
           [:div.article-preview "No profile loaded."])]))))

;; ============================================================================
;; HEADLESS TESTS
;; ============================================================================

(defn profile-load-test []
  (rf/reg-fx :http.canned-profile
    {:platforms #{:client :server}}
    (fn [{:keys [frame]} {:keys [url on-success]}]
      (when on-success
        (rf/dispatch
          (conj on-success
                (if (str/includes? url "/profiles/")
                  {:profile {:username "eve" :bio "Writes things" :image nil :following false}}
                  {:articles [{:slug "one"
                               :title "One"
                               :description "Short"
                               :body "Body"
                               :tagList []
                               :createdAt "2026-05-01"
                               :updatedAt "2026-05-01"
                               :favorited false
                               :favoritesCount 0
                               :author {:username "eve" :bio nil :image nil :following false}}]}))
          {:frame frame}))))

  (with-frame [f (rf/make-frame {:on-create [:app/initialise]
                                 :fx-overrides {:http :http.canned-profile}})]
    (rf/dispatch-sync [:profile/initialise] {:frame f})
    (rf/dispatch-sync [:rf.route/handle-url-change "/profile/eve"] {:frame f})
    (assert (= "eve" (:username (rf/compute-sub [:profile/data] (rf/get-frame-db f)))))
    (assert (= 1 (count (rf/compute-sub [:profile.articles/data] (rf/get-frame-db f)))))))
