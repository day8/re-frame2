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
            [realworld-resources.http :as rh]
            [realworld-resources.resources :as resources])
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
         no scope, no fetch (the feed never leaks across users). The `?page=`
         flows into the feed's `:params` (it rides the params, not the route
         plan, for a session-scoped read), so each page is a distinct (scope,
         page) cache entry just like the public lists."}
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (if-let [user (get-in db [:auth :user])]
      (let [scope [:rf.scope/session {:username (:username user)}]
            page  (get-in rt [:rf.runtime/routing :current :query :page])]
        {:fx [[:dispatch [:rf.resource/ensure
                          {:resource :realworld/feed
                           :scope    scope
                           :params   {:page page}
                           :owner    [:lease :realworld/your-feed (:username user)]
                           :cause    [:route-entry :realworld/home]}]]]})
      {})))

;; Switching feed / tab / tag RESETS pagination to page 1 (a new filter is a
;; fresh list); paging KEEPS the current feed + tag and only changes `?page=`.
;; The page lives in the route query, so it flows straight into the resource
;; params — no page-cache map, no `:status` field.

(rf/reg-event-fx :home/show-global-feed
  (fn [_ _] {:fx [[:dispatch [:rf.route/navigate :realworld/home {} {:query {}}]]]}))

(rf/reg-event-fx :home/show-your-feed
  (fn [_ _] {:fx [[:dispatch [:rf.route/navigate :realworld/home {} {:query {:feed "your"}}]]]}))

(rf/reg-event-fx :home/apply-tag
  (fn [_ [_ tag]] {:fx [[:dispatch [:rf.route/navigate :realworld/home {} {:query {:tag tag}}]]]}))

(rf/reg-event-fx :home/clear-tag
  (fn [_ _] {:fx [[:dispatch [:rf.route/navigate :realworld/home {} {:query {}}]]]}))

;; Page navigation preserves the active feed + tag (read off the live route
;; query) and swaps only `?page=` — so page N and N+1 share filter but are
;; distinct cache keys. Page 1 drops the param entirely (the canonical
;; first-page URL).
(rf/reg-event-fx :home/go-to-page
  (fn [{rt :rf.db/runtime} [_ page]]
    (let [{:keys [tag feed]} (get-in rt [:rf.runtime/routing :current :query])
          query (cond-> {}
                  tag         (assoc :tag tag)
                  feed        (assoc :feed feed)
                  (> page 1)  (assoc :page page))]
      {:fx [[:dispatch [:rf.route/navigate :realworld/home {} {:query query}]]]})))

(rf/reg-sub :home/query :<- [:rf.route/query] (fn [q _] (or q {})))
(rf/reg-sub :home/selected-tag :<- [:home/query] (fn [q _] (:tag q)))
(rf/reg-sub :home/your-feed?   :<- [:home/query] (fn [q _] (= "your" (:feed q))))
(rf/reg-sub :home/page         :<- [:home/query] (fn [q _] (or (:page q) 1)))

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

;; ----------------------------------------------------------------------------
;; PAGINATION CONTROL — official Conduit shape (numbered 1-indexed pages)
;; ----------------------------------------------------------------------------
;;
;; Page count is derived from the server's `articlesCount` and the fixed
;; `page-size` — the view never tracks "how many pages" in app-db; it's a pure
;; function of the loaded data. Each page link is a navigation that swaps only
;; `?page=`, so paging is declarative: the route plan re-ensures the list under
;; the new page key (a distinct cache entry).

(reg-view ^{:doc "Official-style numbered pagination. `:articles-count` is the
                  server total (from the resource's `articlesCount`);
                  `:current-page` is 1-indexed; `:on-page` is called with a page
                  number to navigate. Renders nothing for a single page."}
          pagination [{:keys [articles-count current-page on-page]}]
  (let [total-pages (max 1 (js/Math.ceil (/ (or articles-count 0) resources/page-size)))]
    (when (> total-pages 1)
      (into [:nav [:ul.pagination {:data-testid "pagination"}]]
            (for [p (range 1 (inc total-pages))]
              ^{:key p}
              [:li.page-item {:class (when (= p current-page) "active")}
               [:a.page-link {:href "#" :data-testid (str "page-" p)
                              :on-click #(do (.preventDefault %) (on-page p))}
                p]])))))

(reg-view ^{:doc "Render a list-resource state: skeleton / error / list, with a
                  background-refresh indicator + warning, the keep-previous
                  placeholder, and (when `:articles-count` + `:on-page` are
                  given) numbered pagination.

                  `:keep-previous?` projection: while a NEW page/filter key
                  first-loads, the resource state carries `:previous? true` +
                  `:previous-data` (the prior key's articles). We render those
                  PLUS a placeholder indicator so the user sees the old page,
                  not a skeleton, until the new page settles — no flicker
                  (Spec 016 §Paginated and previous data)."}
          article-list [{:keys [state empty-msg current-page on-page]}]
  (cond
    ;; First-ever load with no usable data AND no previous page to show.
    (and (:loading? state) (not (:previous? state)))
    [:div.article-preview {:data-testid "list-skeleton"} "Loading articles…"]

    (and (:error state) (not (:has-data? state)) (not (:previous? state)))
    [:div.article-preview.error {:data-testid "list-error"}
     (str "Couldn't load articles: " (rh/failure->message (:error state)))]

    :else
    ;; Prefer this key's own data; fall back to the kept-previous projection
    ;; while the new page first-loads (no skeleton flash on page change).
    (let [data           (or (:data state) (:previous-data state))
          articles       (:articles data)
          articles-count (:articlesCount data)]
      [:<>
       (when (:previous? state)
         [:div.refresh-indicator {:data-testid "list-keeping-previous"}
          "Loading next page…"])
       (when (:fetching? state) [:div.refresh-indicator {:data-testid "list-refreshing"} "Refreshing…"])
       (when (:refresh-error state)
         [:div.refresh-warn {:data-testid "list-refresh-warn"} "Refresh failed; showing last-known data."])
       (if (seq articles)
         (into [:div {:data-testid "article-list"}]
               (for [a articles] ^{:key (:slug a)} [article-preview {:article a}]))
         [:div.article-preview {:data-testid "list-empty"} (or empty-msg "No articles are here… yet.")])
       (when (and on-page articles-count)
         [pagination {:articles-count articles-count
                      :current-page   (or current-page 1)
                      :on-page        on-page}])])))

;; ============================================================================
;; HOME PAGE
;; ============================================================================

(reg-view home-page []
  (let [authed?      @(subscribe [:auth/authenticated?])
        your-feed?   @(subscribe [:home/your-feed?])
        selected-tag @(subscribe [:home/selected-tag])
        page         @(subscribe [:home/page])
        session-scope @(subscribe [:session/scope])
        tags-state   @(subscribe [:rf.resource/state {:resource :realworld/tags :params {}}])
        ;; The global list is keyed by the active `:tag` AND `:page` — the SAME
        ;; params the route ensured under, so the sub reads the right cache key
        ;; (Spec 016 §Paginated and previous data).
        list-state   @(subscribe [:rf.resource/state {:resource :realworld/articles
                                                       :params  {:tag selected-tag :page page}}])
        ;; The personalised feed reads under the SAME session scope AND page the
        ;; route ensured it under — passed explicitly so it isn't a silent
        ;; cross-scope `:idle` (Spec 016 §Subscription-side scope resolution).
        feed-state   (when (and authed? session-scope)
                       @(subscribe [:rf.resource/state {:resource :realworld/feed
                                                        :scope   session-scope
                                                        :params  {:page page}}]))]
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
          [article-list {:state feed-state :empty-msg "Your feed is empty — follow some authors."
                         :current-page page :on-page #(dispatch [:home/go-to-page %])}]
          [article-list {:state list-state
                         :current-page page :on-page #(dispatch [:home/go-to-page %])}])]
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
;; PROFILE  —  two official tabs: My Articles / Favorited Articles
;; ============================================================================
;;
;; The two tabs are two ROUTES (`:realworld.profile/show` for authored,
;; `:realworld.profile/favorites` for favorited), each declaring its list read
;; as route `:resources`. The active tab is just the current route id — no tab
;; state in app-db. The view reads the route id to pick which list resource to
;; subscribe to, and the page off the route query.

(rf/reg-sub :profile/favorites-tab? :<- [:rf.route/id]
  (fn [id _] (= id :realworld.profile/favorites)))
(rf/reg-sub :profile/page :<- [:rf.route/query]
  (fn [q _] (or (:page q) 1)))

;; Page navigation on a profile tab preserves the current route + username and
;; swaps only `?page=`. Page 1 drops the param (the canonical first-page URL).
(rf/reg-event-fx :profile/go-to-page
  (fn [{rt :rf.db/runtime} [_ page]]
    (let [{:keys [current]} (get rt :rf.runtime/routing)
          {:keys [id params]} current
          query (cond-> {} (> page 1) (assoc :page page))]
      {:fx [[:dispatch [:rf.route/navigate id params {:query query}]]]})))

(reg-view profile-page []
  (let [username       (:username @(subscribe [:rf.route/params]))
        favorites?     @(subscribe [:profile/favorites-tab?])
        page           @(subscribe [:profile/page])
        current-user   @(subscribe [:auth/user])
        profile-state  @(subscribe [:rf.resource/state {:resource :realworld/profile :params {:username username}}])
        ;; The active tab picks which list resource (+ params) to read — the
        ;; SAME (resource, params) the matching route ensured under.
        list-state     (if favorites?
                         @(subscribe [:rf.resource/state {:resource :realworld/favorited-articles
                                                          :params  {:username username :page page}}])
                         @(subscribe [:rf.resource/state {:resource :realworld/author-articles
                                                          :params  {:username username :page page}}]))]
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
             ;; The two official profile tabs — each a route-link, the active
             ;; one chosen by the current route id (no app-db tab state).
             [:div.articles-toggle
              [:ul.nav.nav-pills.outline-active
               [:li.nav-item
                [rf/route-link {:to :realworld.profile/show :params {:username username}
                                :class (str "nav-link" (when-not favorites? " active"))
                                :data-testid "profile-tab-authored"}
                 "My Articles"]]
               [:li.nav-item
                [rf/route-link {:to :realworld.profile/favorites :params {:username username}
                                :class (str "nav-link" (when favorites? " active"))
                                :data-testid "profile-tab-favorited"}
                 "Favorited Articles"]]]]
             [article-list {:state list-state
                            :empty-msg (if favorites?
                                         "No favorited articles are here… yet."
                                         "No articles here yet.")
                            :current-page page
                            :on-page #(dispatch [:profile/go-to-page %])}]]]]]))]))
