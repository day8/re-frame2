(ns realworld-resources.views
  "Views + the small event glue for the RealWorld-on-resources example.

   Every page reads its server-state PASSIVELY through `[:rf.resource/*]` subs
   (Spec 016 §Subscriptions) — no view fetches. The route's `:resources`
   metadata caused the load. The cond over `:loading?` / `:has-data?` /
   `:error` / `:fetching?` / `:refresh-error` is the canonical resource render
   shape:
   - `:loading?`                         → skeleton (first load, no data)
   - `:error` AND NOT `:has-data?`       → error (first load failed)
   - else render `:data`, plus a quiet refresh indicator while `:fetching?`,
     plus a refresh warning when a background refresh failed (`:refresh-error`,
     prior data kept).

   WRITES fire mutations (`:rf.mutation/execute`) and watch the instance
   passively (`[:rf.mutation/state {:instance …}]`). A mutation's `:invalidates`
   refetches the affected reads with no further wiring — the read→write→
   invalidate→refetch loop, end to end.

   Scope: the public reads need no scope on the subscription (their
   `:rf.scope/global` policy is sub-resolvable). The session-scoped feed passes
   the SAME `:session/scope` the route ensured under (Spec 016
   §Subscription-side scope resolution) — never a silent cross-scope `:idle`."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.resources]
            [realworld-resources.http :as rh])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; HOME-PAGE QUERY HELPERS — navigation is events; reads follow the route
;; ============================================================================
;;
;; Switching feed / applying a tag is a NAVIGATION (`:rf.route/navigate`); the
;; route's `:resources` then re-ensure the right reads. The view does not fetch
;; — it changes the URL and the declarative route plan does the rest.

;; The home route's `:on-match` ensures the SESSION-scoped personalised feed.
;; A route `:scope` resolver can't see app-db (it gets an empty entry ctx), so
;; the feed is event-driven: this handler reads the authenticated user's scope
;; from `:db` and ensures the feed under an app-minted lease (Spec 016 §Active
;; owners — an app lease MUST have a matching release path). The lease is
;; released on logout via `:rf.resource/clear-scope` (which drops every entry
;; in the session scope, lease included). Public reads stay declarative on the
;; route; only this session read is event-driven.
(rf/reg-event-fx :home/on-match
  {:doc "Home route entry: ensure the authenticated user's feed (session-
         scoped) under a releaseable lease. A no-op for a logged-out visitor —
         no scope, no fetch (the feed never leaks across users)."}
  (fn [{:keys [db]} _]
    (if-let [user (get-in db [:auth :user])]
      (let [scope    [:rf.scope/session {:username (:username user)}]]
        {:fx [[:dispatch [:rf.resource/ensure
                          {:resource :realworld/feed
                           :scope    scope
                           :params   {}
                           :owner    [:lease :realworld/your-feed (:username user)]
                           :cause    [:route-entry :realworld/home]}]]]})
      {})))

(rf/reg-event-fx :home/show-global-feed
  (fn [_ _] {:fx [[:dispatch [:rf.route/navigate :realworld/home {} {:query {}}]]]}))

(rf/reg-event-fx :home/show-your-feed
  (fn [_ _] {:fx [[:dispatch [:rf.route/navigate :realworld/home {} {:query {:feed "your"}}]]]}))

(rf/reg-event-fx :home/apply-tag
  (fn [_ [_ tag]] {:fx [[:dispatch [:rf.route/navigate :realworld/home {} {:query {:tag tag}}]]]}))

(rf/reg-event-fx :home/clear-tag
  (fn [_ _] {:fx [[:dispatch [:rf.route/navigate :realworld/home {} {:query {}}]]]}))

(rf/reg-sub :home/query :<- [:rf.route/query] (fn [q _] (or q {})))
(rf/reg-sub :home/selected-tag :<- [:home/query] (fn [q _] (:tag q)))
(rf/reg-sub :home/your-feed?   :<- [:home/query] (fn [q _] (= "your" (:feed q))))

;; ============================================================================
;; FAVORITE / FOLLOW — fire a mutation, watch its instance
;; ============================================================================
;;
;; Auth-gated: favoriting / following needs a session, so a logged-out click
;; navigates to login rather than firing a tokenless write (the real Conduit
;; backend would 401). One instance id per (verb, slug/username) so concurrent
;; toggles on different articles don't clobber each other.

(rf/reg-event-fx :ui/favorite
  (fn [{:keys [db]} [_ slug favorited?]]
    (if (nil? (get-in db [:auth :user]))
      {:fx [[:dispatch [:rf.route/navigate :realworld.auth/login]]]}
      {:fx [[:dispatch [:rf.mutation/execute
                        {:mutation (if favorited? :realworld/unfavorite :realworld/favorite)
                         :params   {:slug slug}
                         :instance [:favorite slug]
                         :cause    [:click :ui/favorite slug]}]]]})))

(rf/reg-event-fx :ui/follow
  (fn [{:keys [db]} [_ username following?]]
    (if (nil? (get-in db [:auth :user]))
      {:fx [[:dispatch [:rf.route/navigate :realworld.auth/login]]]}
      {:fx [[:dispatch [:rf.mutation/execute
                        {:mutation (if following? :realworld/unfollow :realworld/follow)
                         :params   {:username username}
                         :instance [:follow username]
                         :cause    [:click :ui/follow username]}]]]})))

;; ============================================================================
;; COMMENT FORM — a tiny app-db draft + a post mutation
;; ============================================================================

(rf/reg-event-db :comment-form/edit
  (fn [db [_ body]] (assoc-in db [:comment-form :body] body)))

(rf/reg-event-fx :comment-form/submit
  (fn [{:keys [db]} [_ slug]]
    (let [body (str/trim (or (get-in db [:comment-form :body]) ""))]
      (if (str/blank? body)
        {}
        {:db (assoc-in db [:comment-form :body] "")
         :fx [[:dispatch [:rf.mutation/execute
                          {:mutation :realworld/post-comment
                           :params   {:slug slug :body body}
                           :instance [:post-comment slug]
                           :cause    [:submit :comment-form slug]}]]]}))))

(rf/reg-event-fx :comment/delete
  (fn [_ [_ slug id]]
    {:fx [[:dispatch [:rf.mutation/execute
                      {:mutation :realworld/delete-comment
                       :params   {:slug slug :id id}
                       :instance [:delete-comment slug id]
                       :cause    [:click :comment/delete slug id]}]]]}))

(rf/reg-sub :comment-form/body (fn [db _] (get-in db [:comment-form :body])))

;; ============================================================================
;; ARTICLE-CARD VIEW (shared across home + profile)
;; ============================================================================

(reg-view ^{:doc "A single article card."} article-preview [{:keys [article]}]
  (let [{:keys [slug title description createdAt favoritesCount favorited author tagList]} article
        fav-state @(subscribe [:rf.mutation/state {:instance [:favorite slug]}])]
    [:div.article-preview {:data-testid (str "article-preview-" slug)}
     [:div.article-meta
      [rf/route-link {:to :realworld.profile/show :params {:username (:username author)}}
       [:img {:src (:image author)}]]
      [:div.info
       [rf/route-link {:to :realworld.profile/show :params {:username (:username author)} :class "author"}
        (:username author)]
       [:span.date createdAt]]
      [:button.btn.btn-outline-primary.btn-sm.pull-xs-right
       {:type "button"
        :data-testid (str "favorite-" slug)
        :disabled (:pending? fav-state)
        :class (when favorited "active")
        :on-click #(dispatch [:ui/favorite slug favorited])}
       [:i.ion-heart] " "
       [:span {:data-testid (str "favorites-count-" slug)} favoritesCount]]]
     [rf/route-link {:to :realworld.article/show :params {:slug slug}
                     :class "preview-link" :data-testid (str "article-link-" slug)}
      [:h1 title]
      [:p description]
      [:span "Read more..."]
      (into [:ul.tag-list] (for [tag tagList] ^{:key tag} [:li.tag-default.tag-pill.tag-outline tag]))]]))

(reg-view ^{:doc "Render a list-resource state: skeleton / error / list, with a
                  background-refresh indicator + warning."}
          article-list [{:keys [state empty-msg]}]
  (cond
    (:loading? state) [:div.article-preview {:data-testid "list-skeleton"} "Loading articles…"]
    (and (:error state) (not (:has-data? state)))
    [:div.article-preview.error {:data-testid "list-error"}
     (str "Couldn't load articles: " (rh/failure->message (:error state)))]
    :else
    (let [articles (:articles (:data state))]
      [:<>
       (when (:fetching? state) [:div.refresh-indicator {:data-testid "list-refreshing"} "Refreshing…"])
       (when (:refresh-error state)
         [:div.refresh-warn {:data-testid "list-refresh-warn"} "Refresh failed; showing last-known data."])
       (if (seq articles)
         (into [:div {:data-testid "article-list"}]
               (for [a articles] ^{:key (:slug a)} [article-preview {:article a}]))
         [:div.article-preview {:data-testid "list-empty"} (or empty-msg "No articles are here… yet.")])])))

;; ============================================================================
;; HOME PAGE
;; ============================================================================

(reg-view home-page []
  (let [authed?      @(subscribe [:auth/authenticated?])
        your-feed?   @(subscribe [:home/your-feed?])
        selected-tag @(subscribe [:home/selected-tag])
        session-scope @(subscribe [:session/scope])
        tags-state   @(subscribe [:rf.resource/state {:resource :realworld/tags :params {}}])
        ;; The global list is keyed by the active `:tag` (nil when unfiltered).
        list-state   @(subscribe [:rf.resource/state {:resource :realworld/articles
                                                       :params  {:tag selected-tag}}])
        ;; The personalised feed reads under the SAME session scope the route
        ;; ensured it under — passed explicitly so it isn't a silent
        ;; cross-scope `:idle` (Spec 016 §Subscription-side scope resolution).
        feed-state   (when (and authed? session-scope)
                       @(subscribe [:rf.resource/state {:resource :realworld/feed
                                                        :scope   session-scope :params {}}]))]
    [:div.home-page
     [:div.banner [:div.container [:h1.logo-font "conduit"] [:p "A place to share your knowledge."]]]
     [:div.container.page
      [:div.row
       [:div.col-md-9
        [:div.feed-toggle
         [:ul.nav.nav-pills.outline-active
          (when authed?
            [:li.nav-item
             [:a.nav-link {:href "#" :data-testid "your-feed-tab"
                           :class (when your-feed? "active")
                           :on-click #(do (.preventDefault %) (dispatch [:home/show-your-feed]))}
              "Your Feed"]])
          [:li.nav-item
           [:a.nav-link {:href "#" :data-testid "global-feed-tab"
                         :class (when (not your-feed?) "active")
                         :on-click #(do (.preventDefault %) (dispatch [:home/show-global-feed]))}
            "Global Feed"]]
          (when selected-tag
            [:li.nav-item
             [:a.nav-link.active {:href "#" :data-testid "active-tag"
                                  :on-click #(do (.preventDefault %) (dispatch [:home/clear-tag]))}
              [:i.ion-pound] " " selected-tag]])]]
        (if (and authed? your-feed?)
          [article-list {:state feed-state :empty-msg "Your feed is empty — follow some authors."}]
          [article-list {:state list-state}])]
       [:div.col-md-3
        [:div.sidebar
         [:p "Popular Tags"]
         (if (:has-data? tags-state)
           (into [:div.tag-list {:data-testid "tag-list"}]
                 (for [tag (:tags (:data tags-state))]
                   ^{:key tag}
                   [:a.tag-pill.tag-default {:href "#" :data-testid (str "tag-" tag)
                                             :on-click #(do (.preventDefault %) (dispatch [:home/apply-tag tag]))}
                    tag]))
           [:div.tag-list {:data-testid "tags-loading"} "Loading tags…"])]]]]]))

;; ============================================================================
;; ARTICLE DETAIL + COMMENTS
;; ============================================================================

(reg-view comment-card [{:keys [comment slug current-user]}]
  (let [mine?     (= (:username current-user) (get-in comment [:author :username]))
        del-state @(subscribe [:rf.mutation/state {:instance [:delete-comment slug (:id comment)]}])]
    [:div.card {:data-testid (str "comment-card-" (:id comment))}
     [:div.card-block [:p.card-text {:data-testid "comment-body"} (:body comment)]]
     [:div.card-footer
      [rf/route-link {:to :realworld.profile/show :params {:username (get-in comment [:author :username])}
                      :class "comment-author"}
       [:img.comment-author-img {:src (get-in comment [:author :image])}] " "
       (get-in comment [:author :username])]
      [:span.date-posted (:createdAt comment)]
      (when mine?
        [:button.mod-options {:type "button" :data-testid (str "delete-comment-" (:id comment))
                              :disabled (:pending? del-state)
                              :on-click #(dispatch [:comment/delete slug (:id comment)])}
         [:i.ion-trash-a]])]]))

(reg-view article-page []
  (let [slug          (:slug @(subscribe [:rf.route/params]))
        current-user  @(subscribe [:auth/user])
        article-state @(subscribe [:rf.resource/state {:resource :realworld/article :params {:slug slug}}])
        comments-state @(subscribe [:rf.resource/state {:resource :realworld/comments :params {:slug slug}}])
        post-state    @(subscribe [:rf.mutation/state {:instance [:post-comment slug]}])
        draft         @(subscribe [:comment-form/body])]
    [:div.article-page
     (cond
       (:loading? article-state)
       [:div.article-preview {:data-testid "article-skeleton"} "Loading article…"]

       (and (:error article-state) (not (:has-data? article-state)))
       [:div.article-preview.error {:data-testid "article-error"}
        (str "Couldn't load article: " (rh/failure->message (:error article-state)))]

       :else
       (let [{:keys [slug title description body tagList favoritesCount favorited]} (:article (:data article-state))
             fav-state @(subscribe [:rf.mutation/state {:instance [:favorite slug]}])]
         [:<>
          [:div.banner
           [:div.container
            [:h1 {:data-testid "article-title"} title]
            [:p {:data-testid "article-description"} description]
            (when (:fetching? article-state)
              [:span {:data-testid "article-refreshing"} " (refreshing…)"])
            [:button.btn.btn-sm.btn-outline-primary
             {:type "button" :data-testid "article-favorite"
              :disabled (:pending? fav-state)
              :on-click #(dispatch [:ui/favorite slug favorited])}
             [:i.ion-heart] " "
             [:span {:data-testid "article-favorites-count"} favoritesCount]]]]
          [:div.container.page
           [:div.row.article-content
            [:div.col-md-12
             [:p {:data-testid "article-body"} body]
             (into [:ul.tag-list] (for [tag tagList] ^{:key tag} [:li.tag-default.tag-pill.tag-outline tag]))]]
           [:hr]
           [:div.row
            [:div.col-xs-12.col-md-8.offset-md-2
             (if current-user
               [:form.card.comment-form
                {:data-testid "comment-form"
                 :on-submit (fn [e] (.preventDefault e) (dispatch [:comment-form/submit slug]))}
                [:div.card-block
                 [:textarea.form-control {:data-testid "comment-body-input" :rows 3 :placeholder "Write a comment..."
                                          :value (or draft "") :disabled (:pending? post-state)
                                          :on-change #(dispatch [:comment-form/edit (.. % -target -value)])}]]
                [:div.card-footer
                 [:button.btn.btn-sm.btn-primary {:type "submit" :data-testid "comment-submit"
                                                  :disabled (:pending? post-state)}
                  (if (:pending? post-state) "Posting…" "Post Comment")]]
                (when (:error? post-state)
                  [:div.error-messages (rh/failure->message (:error post-state))])]
               [:p [rf/route-link {:to :realworld.auth/login} "Sign in"] " or "
                [rf/route-link {:to :realworld.auth/register} "sign up"] " to add comments."])
             (cond
               (:loading? comments-state)
               [:p {:data-testid "comments-loading"} "Loading comments…"]
               (and (:error comments-state) (not (:has-data? comments-state)))
               [:p.error {:data-testid "comments-error"} "Couldn't load comments."]
               :else
               (into [:div {:data-testid "comments-list"}]
                     (for [c (:comments (:data comments-state))]
                       ^{:key (:id c)} [comment-card {:comment c :slug slug :current-user current-user}])))]]]]))
     [:p [rf/route-link {:to :realworld/home :data-testid "back-home"} "← Back to feed"]]]))

;; ============================================================================
;; PROFILE
;; ============================================================================

(reg-view profile-page []
  (let [username       (:username @(subscribe [:rf.route/params]))
        current-user   @(subscribe [:auth/user])
        profile-state  @(subscribe [:rf.resource/state {:resource :realworld/profile :params {:username username}}])
        articles-state @(subscribe [:rf.resource/state {:resource :realworld/author-articles :params {:username username}}])]
    [:div.profile-page
     (cond
       (:loading? profile-state)
       [:div.article-preview {:data-testid "profile-skeleton"} "Loading profile…"]

       (and (:error profile-state) (not (:has-data? profile-state)))
       [:div.article-preview.error {:data-testid "profile-error"} "Couldn't load this profile."]

       :else
       (let [{:keys [username bio image following]} (:profile (:data profile-state))
             own?       (= username (:username current-user))
             follow-state @(subscribe [:rf.mutation/state {:instance [:follow username]}])]
         [:<>
          [:div.user-info
           [:div.container
            [:div.row
             [:div.col-xs-12.col-md-10.offset-md-1
              [:img.user-img {:src image}]
              [:h4 {:data-testid "profile-username"} username]
              [:p bio]
              (when (:fetching? profile-state) [:span {:data-testid "profile-refreshing"} " (refreshing…)"])
              (when-not own?
                [:button.btn.btn-sm.btn-outline-secondary.action-btn
                 {:type "button" :data-testid "follow-button"
                  :disabled (:pending? follow-state)
                  :on-click #(dispatch [:ui/follow username following])}
                 (if following "Unfollow " "Follow ") username])]]]]
          [:div.container
           [:div.row
            [:div.col-xs-12.col-md-10.offset-md-1
             [article-list {:state articles-state :empty-msg "No articles here yet."}]]]]]))]))
