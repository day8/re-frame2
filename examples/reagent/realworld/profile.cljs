(ns realworld.profile
  "Profile pages for the RealWorld (Conduit) example.

   This sketch demonstrates:
   - Pattern-NineStates — one parallel state machine `:ui/profile` with
     two orthogonal regions (`:tab` × `:data`) replacing the prior
     cross-axis sub + priority `cond` shape (per rf2-ljw9).
   - Pattern-RemoteData lifecycle folded into the `:data` region; the
     region's state-keyword IS the banner's status. The article-list
     items still live in app-db slices (`:profile.articles`,
     `:profile.favorites`) so the optimistic-update paths in
     favorites.cljs continue to find articles across slices.
   - Tab switching expressed as `:tab` region transitions broadcast
     from the route's `:on-match` (just like the home page's
     `:home/load` broadcasts the feed-region transition).
   - The profile view's root is a `case` over `:profile/render`, a
     selector sub that consults a render-priority table against the
     machine's tag union (per Pattern-NineStates §4).

   Three remote-data slices behind the view:
   - `:profile`             — public banner (username, bio, image, following)
   - `:profile.articles`    — authored articles
   - `:profile.favorites`   — favorited articles

   Follow/unfollow is optimistic and shared across the banner plus any
   article cards rendered from the profile routes."
  (:require [re-frame.core :as rf]
            ;; Per rf2-xbtj, the Spec 005 state-machine ns lives in the
            ;; day8/re-frame2-machines artefact. Loading the ns here
            ;; registers its late-bind hooks so rf/reg-machine (called
            ;; below at ns-load) and the `:rf/machine` framework subs
            ;; resolve.
            [re-frame.machines]
            [realworld.schema :as schema]
            [realworld.http :as rh]
            [realworld.routing :as routing]
            [realworld.articles :as articles])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

(defn current-time-ms [] (.getTime (js/Date.)))

(defn request-slice []
  {:status :idle :data nil :error nil :loaded-at nil :attempt 0})

(defn username-from-db [db]
  (get-in db [:rf/route :params :username]))

;; ============================================================================
;; THE MACHINE — :ui/profile  (one machine, two regions)
;; ============================================================================
;;
;; The profile page has two orthogonal axes:
;;
;;   :tab    — which list of articles is rendered (authored vs favorited).
;;             Driven by `:show-articles` / `:show-favorites` broadcasts
;;             from the route's `:on-match` (see `routing.cljs`'s
;;             `:route/profile` and `:route/profile.favorites`). The
;;             state-keyword tells the view which app-db slice's items
;;             to render.
;;
;;   :data   — Pattern-NineStates' data lifecycle for the banner fetch.
;;             The slice's `:status` field is still set for parity with
;;             other slices, but the region's state-keyword IS the
;;             page's render gate. The article-list slices keep their
;;             slice shape and load independently.
;;
;; Per Spec 005 §Transition broadcast: every event delivered to the
;; machine is broadcast to every region. Region-distinct event names
;; below avoid collisions; `:reset` is handled by every region as a
;; self-target.

(def profile-machine
  {:type :parallel

   ;; The banner's latest error map. The profile/article items
   ;; themselves still live in app-db slices.
   :data {:error nil}

   :actions
   {:set-error
    (fn action-set-error [data [_ {:keys [failure]}]]
      {:data (assoc data :error failure)})

    :clear-error
    (fn action-clear-error [data _event]
      {:data (assoc data :error nil)})}

   :regions
   {;; ---- :data region — Pattern-NineStates lifecycle for the banner ----
    :data
    {:initial :nothing
     :states
     {:nothing
      {:tags #{:data/nothing}
       :on   {:fetch-started :loading
              :reset         :nothing}}

      :loading
      ;; First fetch in flight; banner not yet rendered.
      {:tags #{:data/loading :data/transient}
       :on   {:fetch-succeeded {:target :loaded :action :clear-error}
              :fetch-failed    {:target :error  :action :set-error}
              :reset           :nothing}}

      :refreshing
      ;; Reload while prior banner remains visible. Tagged :data/loaded
      ;; so the render-priority resolves to the `:loaded` view; the
      ;; :data/refreshing tag could drive an inline indicator.
      {:tags #{:data/loaded :data/refreshing :data/transient}
       :on   {:fetch-succeeded {:target :loaded :action :clear-error}
              :fetch-failed    {:target :error  :action :set-error}
              :reset           :nothing}}

      :loaded
      {:tags #{:data/loaded}
       :on   {:fetch-started :refreshing
              :reset         :nothing}}

      :error
      {:tags #{:data/error}
       :on   {:fetch-started :loading
              :reset         :nothing}}}}

    ;; ---- :tab region — which article list the view renders ----
    :tab
    {:initial :articles
     :states
     {:articles
      ;; `/profile/:username` — reads the :profile.articles slice.
      {:tags #{:tab/articles}
       :on   {:show-favorites :favorites
              :show-articles  :articles
              :reset          :articles}}

      :favorites
      ;; `/profile/:username/favorites` — reads :profile.favorites.
      {:tags #{:tab/favorites}
       :on   {:show-articles  :articles
              :show-favorites :favorites
              :reset          :articles}}}}}})

(rf/reg-machine :ui/profile profile-machine)

;; ============================================================================
;; INITIALISATION
;; ============================================================================

(rf/reg-event-fx :profile/initialise
  (fn [{:keys [db]} _]
    {:db (-> db
             (assoc :profile (request-slice))
             (assoc :profile.articles (assoc (request-slice) :data []))
             (assoc :profile.favorites (assoc (request-slice) :data [])))
     :fx [[:dispatch [:ui/profile [:reset]]]]}))

;; ============================================================================
;; LOADS
;; ============================================================================

(rf/reg-event-fx :profile/load
  {:doc "Load the public profile (username, bio, image, following).
         Public endpoint; data-fetch retry.

         Broadcasts `:fetch-started` into the `:ui/profile` machine so
         the `:data` region advances to `:loading` (or `:refreshing`
         from `:loaded`)."
   :rf.http/decode-schemas [schema/ProfileResponse]}
  (fn [{:keys [db]} _]
    (let [username (username-from-db db)]
      {:db (-> db
               (assoc-in [:profile :status]
                         (if (get-in db [:profile :data]) :fetching :loading))
               (assoc-in [:profile :error] nil)
               (update-in [:profile :attempt] (fnil inc 0)))
       :fx [[:dispatch [:ui/profile [:fetch-started]]]
            [:rf.http/managed
             (rh/request {:method     :get
                          :path       (str "/profiles/" username)
                          :auth?      false
                          :decode     schema/ProfileResponse
                          :retry      rh/data-fetch-retry
                          :request-id [:profile/load username]
                          :on-success [:profile/loaded]
                          :on-failure [:profile/load-failed]})]]})))

(rf/reg-event-fx :profile/loaded
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (-> db
             (assoc-in [:profile :status] :loaded)
             (assoc-in [:profile :data] (:profile value))
             (assoc-in [:profile :error] nil)
             (assoc-in [:profile :loaded-at] (current-time-ms)))
     :fx [[:dispatch [:ui/profile [:fetch-succeeded]]]]}))

(rf/reg-event-fx :profile/load-failed
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    (let [message (rh/failure->message failure)]
      {:db (-> db
               (assoc-in [:profile :status] :error)
               (assoc-in [:profile :error] message))
       :fx [[:dispatch [:ui/profile [:fetch-failed {:failure message}]]]]})))

(rf/reg-event-fx :profile.articles/load
  {:doc "Load the profile's authored articles. Public; data-fetch retry.
         Also broadcasts `:show-articles` so the `:ui/profile` :tab
         region tracks the active tab."
   :rf.http/decode-schemas [schema/ArticlesResponse]}
  (fn [{:keys [db]} _]
    (let [username (username-from-db db)]
      {:db (-> db
               (assoc-in [:profile.articles :status] :loading)
               (assoc-in [:profile.articles :error] nil)
               (update-in [:profile.articles :attempt] (fnil inc 0)))
       :fx [[:dispatch [:ui/profile [:show-articles]]]
            [:rf.http/managed
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
  {:doc "Load the profile's favorited articles. Public; data-fetch retry.
         Also broadcasts `:show-favorites` so the `:ui/profile` :tab
         region tracks the active tab."
   :rf.http/decode-schemas [schema/ArticlesResponse]}
  (fn [{:keys [db]} _]
    (let [username (username-from-db db)]
      {:db (-> db
               (assoc-in [:profile.favorites :status] :loading)
               (assoc-in [:profile.favorites :error] nil)
               (update-in [:profile.favorites :attempt] (fnil inc 0)))
       :fx [[:dispatch [:ui/profile [:show-favorites]]]
            [:rf.http/managed
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

(rf/reg-sub :profile         (fn [db _] (:profile db)))
(rf/reg-sub :profile/data    :<- [:profile] (fn [s _] (:data s)))
(rf/reg-sub :profile/error   :<- [:profile] (fn [s _] (:error s)))

(rf/reg-sub :profile.articles/data
  (fn [db _] (get-in db [:profile.articles :data])))

(rf/reg-sub :profile.favorites/data
  (fn [db _] (get-in db [:profile.favorites :data])))

(rf/reg-sub :profile/own-profile?
  (fn [db _]
    (= (get-in db [:auth :user :username])
       (get-in db [:profile :data :username]))))

;; ---- render-priority + :profile/render selector ----
;;
;; Plain data: a vector of {:tag :render} pairs consulted in order.
;; The `:profile/render` sub reads the machine's tag union and returns
;; the first :render whose :tag is present. The view's `case` over
;; the resolved keyword is the only branch site.
;;
;; Priority rationale: the data region's lifecycle wins outright —
;; `:loading` (first-load spinner) above `:error` above `:loaded`.
;; `:refreshing` resolves to `:loaded` (the prior banner stays visible).

(def render-priority
  [{:tag :data/loading :render :loading}
   {:tag :data/error   :render :error}
   {:tag :data/loaded  :render :loaded}
   {:tag :data/nothing :render :nothing}])

(rf/reg-sub :profile/render
  {:doc "Resolve the profile page's render-model keyword by consulting
         the render-priority table against the `:ui/profile` machine's
         tag union. The root view's `case` is the only branch site."}
  :<- [:rf/machine :ui/profile]
  (fn sub-profile-render [snap _]
    (let [tags (:tags snap)]
      (some (fn [{:keys [tag render]}]
              (when (contains? tags tag) render))
            render-priority))))

(rf/reg-sub :profile/current-articles
  {:doc "The article list currently rendered by the profile view: the
         `:tab` region's active state-keyword picks which app-db slice
         to read from."}
  :<- [:rf/machine :ui/profile]
  :<- [:profile.articles/data]
  :<- [:profile.favorites/data]
  (fn sub-current-articles [[snap authored favorited] _]
    (case (get-in snap [:state :tab])
      :favorites (or favorited [])
      (or authored []))))

;; ============================================================================
;; VIEWS
;; ============================================================================

(reg-view ^{:doc "Data region :loading — first banner fetch in flight."}
          profile-loading []
  [:div.article-preview "Loading profile…"])

(reg-view ^{:doc "Data region :error — banner fetch failed."}
          profile-error []
  (let [err @(subscribe [:profile/error])]
    [:div.article-preview.error
     (str "Couldn't load profile: " (pr-str err))]))

(reg-view ^{:doc "Data region :nothing — pre-fetch placeholder."}
          profile-nothing []
  [:div.article-preview "No profile loaded."])

(reg-view ^{:doc "Data region :loaded — banner, tabs, and the active
                  tab's article list."}
          profile-loaded []
  (let [profile     @(subscribe [:profile/data])
        own?        @(subscribe [:profile/own-profile?])
        on-favs?    @(rf/has-tag? :ui/profile :tab/favorites)
        articles*   @(subscribe [:profile/current-articles])]
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
                                :class  (str "nav-link" (when-not on-favs? " active"))}
            "My Articles"]]
          [:li.nav-item
           [routing/route-link {:to     :route/profile.favorites
                                :params {:username (:username profile)}
                                :class  (str "nav-link" (when on-favs? " active"))}
            "Favorited Articles"]]]]
        (if (seq articles*)
          (for [article articles*]
            ^{:key (:slug article)}
            [articles/article-preview {:article article}])
          [:div.article-preview "No articles here yet."])]]]]))

(reg-view profile-page []
  (let [render-mode @(subscribe [:profile/render])]
    [:div.profile-page
     (case render-mode
       :loading [profile-loading]
       :error   [profile-error]
       :loaded  [profile-loaded]
       :nothing [profile-nothing]
       [profile-nothing])]))
