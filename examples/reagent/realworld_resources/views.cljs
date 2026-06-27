(ns realworld-resources.views
  "Views and the small event glue for the RealWorld-on-resources example.

   The thing to hold onto across this whole namespace: no view ever fetches. Every
   page reads its server-state passively, through `[:rf.resource/*]` subs — the
   route's `:resources` metadata is what caused the load. And rendering a resource
   follows one canonical shape, a cond over its status:
   - `:loading?`                   → skeleton (first load, nothing to show yet)
   - `:error` AND NOT `:has-data?` → error (the first load failed outright)
   - otherwise                     → render `:data`, plus a quiet refresh indicator
     while `:fetching?`, plus a warning if a background refresh failed
     (`:refresh-error`, with the prior data still kept on screen).
   See resource status: ../../../docs/resources/glossary.md#resource-status.

   Writes fire mutations (`:rf.mutation/execute`) and watch the instance just as
   passively (`[:rf.mutation/state {:instance …}]`). The success continuation is
   the call-site `:reply-to` event target, dispatched once when the reply is
   accepted — AFTER the mutation's `:invalidates` refetched the affected reads.
   That's the read→write→invalidate→refetch loop, end to end, with not a single
   off-render reaction anywhere in this ns.

   On scope: every page reads through `[:rf.resource/*]` subs that resolve their
   own scope. The public reads carry the sub-resolvable `:rf.scope/global` policy;
   the session-scoped feed declares `:scope {:from-db :realworld/session}`
   (resources.cljs), a named-resolver reference the subscription resolves against
   app-db and re-keys reactively across login / logout. No view ever threads a
   `:scope` payload — the resolver is the one and only place scope gets resolved."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.resources]
            [realworld-resources.http :as rh]
            [realworld-shared.avatar :as avatar]
            [realworld-shared.markdown :as md]
            [realworld-resources.resources :as resources])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; HOME-PAGE QUERY HELPERS — navigation is events; reads follow the route
;; ============================================================================
;;
;; Switching feed or applying a tag is a navigation (`:rf.route/navigate`), and the
;; route's `:resources` then re-ensure the right reads. So the view never fetches —
;; it just changes the URL, and the declarative route plan handles the rest. The
;; personalised feed is one of those route resources, scoped by the named
;; `{:from-db :realworld/session}` resolver (routing.cljs / resources.cljs), so the
;; home page needs no `:on-match` event to ensure it by hand.

;; A couple of route-shape details, to match the official Conduit contract: the
;; tag filter is the `/tag/:tag` PATH route (`:realworld/home-tag`), so the active
;; tag is a route param rather than a `?tag=` query, and the following feed uses
;; `?feed=following` (not `your`).
;;
;; Switching feed / tab / tag resets pagination to page 1 — a new filter is a fresh
;; list, after all — while paging keeps the current feed and tag and changes only
;; `?page=`. The page lives in the route query, so it flows straight into the
;; resource params: no page-cache map, no `:status` field.

(def following-feed-token "following")

(rf/reg-event :home/show-global-feed
  (fn [_ _] {:fx [[:dispatch [:rf.route/navigate :realworld/home {} {:query {}}]]]}))

(rf/reg-event :home/show-your-feed
  (fn [_ _] {:fx [[:dispatch [:rf.route/navigate :realworld/home {} {:query {:feed following-feed-token}}]]]}))

(rf/reg-event :home/apply-tag
  (fn [_ [_ tag]] {:fx [[:dispatch [:rf.route/navigate :realworld/home-tag {:tag tag}]]]}))

(rf/reg-event :home/clear-tag
  (fn [_ _] {:fx [[:dispatch [:rf.route/navigate :realworld/home]]]}))

;; Paging keeps the active feed and tag (read off the live route) and swaps only
;; `?page=`, so page N and N+1 share a filter but live under distinct cache keys.
;; A tag-filtered page re-targets the `/tag/:tag` PATH route with the tag param
;; preserved; otherwise the home route carries `?feed=`. Page 1 drops the `?page=`
;; param entirely — that's the canonical first-page URL.
(rf/reg-event :home/go-to-page
  (fn [{rt :rf.db/runtime} [_ page]]
    (let [current (get-in rt [:rf.runtime/routing :current])
          tag     (get-in current [:params :tag])
          feed    (get-in current [:query :feed])]
      (if tag
        (let [query (cond-> {} (> page 1) (assoc :page page))]
          {:fx [[:dispatch [:rf.route/navigate :realworld/home-tag {:tag tag} {:query query}]]]})
        (let [query (cond-> {}
                      feed       (assoc :feed feed)
                      (> page 1) (assoc :page page))]
          {:fx [[:dispatch [:rf.route/navigate :realworld/home {} {:query query}]]]})))))

(rf/reg-sub :home/selected-tag :<- [:rf.route/params] (fn [p _] (:tag p)))
(rf/reg-sub :home/your-feed?   :<- [:rf.route/query]  (fn [q _] (= following-feed-token (:feed q))))
(rf/reg-sub :home/page         :<- [:rf.route/query]  (fn [q _] (or (:page q) 1)))

;; ============================================================================
;; FAVORITE / FOLLOW — fire a mutation, watch its instance
;; ============================================================================
;;
;; These are auth-gated: favoriting and following both need a session, so a
;; logged-out click bounces to login instead of firing a tokenless write (which
;; the real Conduit backend would 401 anyway). One instance id per (verb,
;; slug/username), so toggling two different articles at once doesn't let one
;; clobber the other.

(rf/reg-event :ui/favorite
  (fn [{:keys [db]} [_ slug favorited?]]
    (if (nil? (get-in db [:auth :user]))
      {:fx [[:dispatch [:rf.route/navigate :realworld.auth/login]]]}
      {:fx [[:dispatch [:rf.mutation/execute
                        {:mutation (if favorited? :realworld/unfavorite :realworld/favorite)
                         :params   {:slug slug}
                         :instance [:favorite slug]
                         :cause    [:click :ui/favorite slug]}]]]})))

(rf/reg-event :ui/follow
  (fn [{:keys [db]} [_ username following?]]
    (if (nil? (get-in db [:auth :user]))
      {:fx [[:dispatch [:rf.route/navigate :realworld.auth/login]]]}
      {:fx [[:dispatch [:rf.mutation/execute
                        {:mutation (if following? :realworld/unfollow :realworld/follow)
                         :params   {:username username}
                         :instance [:follow username]
                         :cause    [:click :ui/follow username]}]]]})))

;; ----------------------------------------------------------------------------
;; ARTICLE-DETAIL SOCIAL CONTROLS
;; ----------------------------------------------------------------------------
;;
;; The Conduit article page shows different controls depending on who's looking. A
;; non-author viewer gets Follow/Unfollow for the author; the author gets Edit (→
;; /editor/:slug) and Delete; a logged-out viewer gets neither. Both the follow and
;; the delete continuations are call-site `:reply-to` targets — the mutation
;; reply-side seam, declared right where the write happens, rather than emulated
;; with Form-3 settle reactions bolted onto the article page.

(rf/reg-event :ui/follow-author
  {:doc "Follow / unfollow the article author, from the detail page. It fires the
         follow / unfollow mutation, but there's a wrinkle: the detail page's
         embedded `:author` lives in the `[:article slug]` entry, not the
         `[:profile username]` resource the follow mutation invalidates. So the
         article is re-staled only once the follow SETTLES, by the
         `:reply-to [:ui/follow-author-replied slug]` continuation (which carries
         the slug). Staling eagerly here would refetch before the server had even
         processed the follow, leaving the embedded flag reading its old value —
         the continuation waits for the accepted reply instead. Auth-gated."}
  (fn [{:keys [db]} [_ slug username following?]]
    (if (nil? (get-in db [:auth :user]))
      {:fx [[:dispatch [:rf.route/navigate :realworld.auth/login]]]}
      {:fx [[:dispatch [:rf.mutation/execute
                        {:mutation (if following? :realworld/unfollow :realworld/follow)
                         :params   {:username username}
                         :instance [:follow username]
                         :reply-to [:ui/follow-author-replied slug]
                         :cause    [:click :ui/follow-author username]}]]]})))

(rf/reg-event :ui/follow-author-replied
  {:doc "Follow / unfollow completion continuation (the `:reply-to` target). On
         `:ok`, stale the current article so its embedded `:author.following`
         refetches. The follow mutation already invalidates `[:profile username]`,
         but the detail's embedded author flag lives in the `[:article slug]`
         entry — and only this call site knows the slug. The slug rides along as a
         static call-site arg, with the reply map appended after it. Global scope,
         since the article read is `:rf.scope/global`."}
  (fn [_ [_ slug {:keys [status]}]]
    (if (= :ok status)
      {:fx [[:dispatch [:rf.resource/invalidate-tags
                        {:scope :rf.scope/global
                         :tags  #{[:article slug]}
                         :cause [:follow-author-detail-sync slug]}]]]}
      {})))

(rf/reg-event :ui/delete-article
  {:doc "Delete the current article from the detail page (author only). Fires the
         `:realworld/delete-article` mutation with a `:reply-to
         [:ui/article-deleted]` continuation that navigates home on success."}
  (fn [{:keys [db]} [_ slug]]
    (if (nil? (get-in db [:auth :user]))
      {:fx [[:dispatch [:rf.route/navigate :realworld.auth/login]]]}
      {:fx [[:dispatch [:rf.mutation/execute
                        {:mutation :realworld/delete-article
                         :params   {:slug slug}
                         :instance [:delete-article slug]
                         :reply-to [:ui/article-deleted]
                         :cause    [:click :ui/delete-article slug]}]]]})))

(rf/reg-event :ui/article-deleted
  {:doc "Delete-article completion continuation (the `:reply-to` target). On `:ok`,
         clear the delete instance and head home — by the time this fires, the
         mutation's `:invalidates` has already staled the lists, the feed, and the
         now-vanished article's detail. The reply carries the `:instance` to
         clear."}
  (fn [_ [_ {:keys [status instance]}]]
    (if (= :ok status)
      {:fx [[:dispatch [:rf.mutation/clear {:instance instance}]]
            [:dispatch [:rf.route/navigate :realworld/home]]]}
      {})))

;; ============================================================================
;; COMMENT FORM — a tiny app-db draft + a post mutation
;; ============================================================================

(rf/reg-event :comment-form/edit
  (fn [{:keys [db]} [_ body]] {:db (assoc-in db [:comment-form :body] body)}))

(rf/reg-event :comment-form/submit
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

(rf/reg-event :comment/delete
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
       [:img.user-pic {:src (avatar/avatar-src (:image author))}]]
      [:div.info
       [rf/route-link {:to :realworld.profile/show :params {:username (:username author)} :class "author"}
        (:username author)]
       [:span.date createdAt]]
      ;; Optimistic favorite. The heart and count already show the click — the
      ;; `:optimistic-tags` apply flipped the cached article before the request even
      ;; left, so `favorited` / `favoritesCount` (read from the resource cache) are
      ;; already the new values. We deliberately don't disable the button on
      ;; `:pending?`: the user sees their change land instantly, and a failed write
      ;; quietly rolls it back. `:optimistic?` means 'showing my optimistic value,
      ;; not yet confirmed' — just enough for a subtle in-flight cue.
      [:button.btn.btn-outline-primary.btn-sm.pull-xs-right
       {:type "button"
        :data-testid (str "favorite-" slug)
        :class (cond-> ""
                 favorited                 (str " active")
                 (:optimistic? fav-state)  (str " optimistic"))
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
;; The page count is worked out from the server's `articlesCount` and the fixed
;; `page-size` — the view never stashes 'how many pages' in app-db, it's purely a
;; function of the loaded data. Each page link is a navigation that swaps only
;; `?page=`, so paging stays declarative: the route plan re-ensures the list under
;; the new page key, which is a distinct cache entry.

(reg-view ^{:doc "Official-style numbered pagination. `:articles-count` is the
                  server total (from the resource's `articlesCount`),
                  `:current-page` is 1-indexed, and `:on-page` is called with a
                  page number to navigate to. Renders nothing when there's only
                  one page."}
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

(reg-view ^{:doc "Render a list-resource state: skeleton / error / list, complete
                  with a background-refresh indicator and warning, the
                  keep-previous placeholder, and — when `:articles-count` and
                  `:on-page` are supplied — numbered pagination.

                  The keep-previous bit is the nice touch: while a new page/filter
                  key first-loads, the resource state carries `:previous? true` and
                  `:previous-data` (the prior key's articles). We render those, plus
                  a small placeholder indicator, so the user keeps seeing the old
                  page rather than a skeleton until the new one settles. No
                  flicker."}
          article-list [{:keys [state empty-msg current-page on-page]}]
  (cond
    ;; First-ever load, with no usable data and no previous page to fall back on.
    (and (:loading? state) (not (:previous? state)))
    [:div.article-preview {:data-testid "list-skeleton"} "Loading articles…"]

    (and (:error state) (not (:has-data? state)) (not (:previous? state)))
    [:div.article-preview.error {:data-testid "list-error"}
     (str "Couldn't load articles: " (rh/failure->message (:error state)))]

    :else
    ;; Prefer this key's own data, but fall back to the kept-previous projection
    ;; while the new page first-loads — that's what avoids a skeleton flash on a
    ;; page change.
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
         ;; The official RealWorld E2E contract checks for the
         ;; `.empty-feed-message` marker on an empty list, so it stays.
         [:div.article-preview.empty-feed-message {:data-testid "list-empty"}
          (or empty-msg "No articles are here… yet.")])
       (when (and on-page articles-count)
         [pagination {:articles-count articles-count
                      :current-page   (or current-page 1)
                      :on-page        on-page}])])))

;; ============================================================================
;; HOME PAGE
;; ============================================================================

(reg-view ^{:doc "The home page — a pure function of subs that never dispatches out
                  of band. The personalised feed reads through the named
                  `{:from-db :realworld/session}` scope resolver: the subscription
                  resolves its own scope (nothing to thread) and re-keys reactively
                  across login / logout. A favourite shows up in Your Feed via the
                  mutation's own session-scoped invalidation descriptor — no
                  off-render reaction, no app-level feed patching."}
          home-page []
  (let [authed?      @(subscribe [:auth/authenticated?])
        your-feed?   @(subscribe [:home/your-feed?])
        selected-tag @(subscribe [:home/selected-tag])
        page         @(subscribe [:home/page])
        tags-state   @(subscribe [:rf.resource/state {:resource :realworld/tags :params {}}])
        ;; The global list is keyed by the active `:tag` and `:page` — the same
        ;; params the route ensured under, so the sub lands on the right cache key.
        list-state   @(subscribe [:rf.resource/state {:resource :realworld/articles
                                                       :params  {:tag selected-tag :page page}}])
        ;; The personalised feed. The resource declares `:scope {:from-db
        ;; :realworld/session}`, so the subscription resolves the session scope
        ;; itself from app-db — no view threads a `:scope` payload. Logged out, the
        ;; resolver yields nil (fail-closed), so we only subscribe when authed; the
        ;; `:page` matches the route-ensured key.
        feed-state   (when authed?
                       @(subscribe [:rf.resource/state {:resource :realworld/feed
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
       [:img.comment-author-img {:src (avatar/avatar-src (get-in comment [:author :image]))}] " "
       (get-in comment [:author :username])]
      [:span.date-posted (:createdAt comment)]
      (when mine?
        [:button.mod-options {:type "button" :data-testid (str "delete-comment-" (:id comment))
                              :disabled (:pending? del-state)
                              :on-click #(dispatch [:comment/delete slug (:id comment)])}
         [:i.ion-trash-a]])]]))

(reg-view ^{:doc "Article-detail contextual controls: the author byline, plus
                  either Follow/Unfollow on the author (for a non-author viewer) or
                  Edit (→ /editor/:slug) + Delete (for the author). A logged-out
                  viewer gets the byline alone. `article` is the unwrapped article
                  map; `del-state` is the delete instance's view."}
          article-meta [{:keys [article del-state]}]
  (let [author    (:author article)
        username  (:username author)
        slug      (:slug article)
        me        @(subscribe [:auth/user])
        authed?   @(subscribe [:auth/authenticated?])
        own?      (and me (= (:username me) username))]
    [:div.article-meta
     [rf/route-link {:to :realworld.profile/show :params {:username username}}
      [:img.user-pic {:src (avatar/avatar-src (:image author))}]]
     [:div.info
      [rf/route-link {:to :realworld.profile/show :params {:username username} :class "author"}
       username]
      [:span.date (:createdAt article)]]
     (cond
       own?
       [:span
        [rf/route-link {:to :realworld.editor/edit :params {:slug slug}
                        :class "btn btn-sm btn-outline-secondary"
                        :data-testid "article-edit"}
         [:i.ion-edit] " Edit Article"]
        " "
        [:button.btn.btn-sm.btn-outline-danger
         {:type "button" :data-testid "article-delete"
          :disabled (:pending? del-state)
          :on-click #(dispatch [:ui/delete-article slug])}
         [:i.ion-trash-a] " Delete Article"]]

       authed?
       [:button.btn.btn-sm.btn-outline-secondary
        {:type "button" :data-testid "article-follow-author"
         :on-click #(dispatch [:ui/follow-author slug username (:following author)])}
        [:i.ion-plus-round] " "
        (if (:following author) "Unfollow " "Follow ") username])]))

(reg-view ^{:doc "The article-detail page — a pure function of subs that never
                  dispatches out of band. The two settle continuations it needs are
                  the mutations' own `:reply-to` targets: deleting fires
                  `:ui/delete-article` → `:reply-to [:ui/article-deleted]`
                  (navigate home), and following the author fires
                  `:ui/follow-author` → `:reply-to [:ui/follow-author-replied slug]`
                  (re-stale `[:article slug]` so the embedded author flag
                  refetches). No Form-3 wrapper, no off-render reaction."}
          article-page []
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
       (let [article   (:article (:data article-state))
             {:keys [slug title description body tagList favoritesCount favorited]} article
             fav-state @(subscribe [:rf.mutation/state {:instance [:favorite slug]}])
             del-state @(subscribe [:rf.mutation/state {:instance [:delete-article slug]}])]
         [:<>
          [:div.banner
           [:div.container
            [:h1 {:data-testid "article-title"} title]
            [:p {:data-testid "article-description"} description]
            (when (:fetching? article-state)
              [:span {:data-testid "article-refreshing"} " (refreshing…)"])
            [:span.article-controls
             ;; The article-detail favorite control is chattier than the card's:
             ;; it shows visible "Favorite"/"Unfavorite" text and toggles
             ;; `.btn-outline-primary` ↔ `.btn-primary` on the favorited flag, and
             ;; the E2E contract checks both. (The compact heart-only card button
             ;; stays `.btn-outline-primary` — also correct per the official
             ;; client.)
             ;; And it's optimistic: the label, the
             ;; `.btn-primary`/`.btn-outline-primary` class, and the count all flip
             ;; the instant the heart is clicked — the cached article was patched
             ;; before the request even left — then settle to the server's value,
             ;; or roll back if the write fails. Not disabled while pending,
             ;; because the optimistic value is the truth on screen until it
             ;; settles.
             [:button.btn.btn-sm
              {:type "button" :data-testid "article-favorite"
               :class (cond-> (if favorited "btn-primary" "btn-outline-primary")
                        (:optimistic? fav-state) (str " optimistic"))
               :on-click #(dispatch [:ui/favorite slug favorited])}
              [:i.ion-heart] " "
              (if favorited "Unfavorite" "Favorite") " Article "
              [:span.counter {:data-testid "article-favorites-count"} "(" favoritesCount ")"]]
             " "
             [article-meta {:article article :del-state del-state}]]]]
          [:div.container.page
           [:div.row.article-content
            [:div.col-md-12
             [:div {:data-testid "article-body"} (md/render body)]
             (into [:ul.tag-list] (for [tag tagList] ^{:key tag} [:li.tag-default.tag-pill.tag-outline tag]))]]
           [:hr]
           [:div.article-actions
            [article-meta {:article article :del-state del-state}]]
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
;; A nice consequence of routing the way we do: the two tabs are simply two routes
;; (`:realworld.profile/show` for authored, `:realworld.profile/favorites` for
;; favorited), each declaring its list read as route `:resources`. The active tab
;; is nothing more than the current route id — there's no tab state in app-db at
;; all. The view reads the route id to choose which list resource to subscribe to,
;; and reads the page off the route query.

(rf/reg-sub :profile/favorites-tab? :<- [:rf.route/id]
  (fn [id _] (= id :realworld.profile/favorites)))
(rf/reg-sub :profile/page :<- [:rf.route/query]
  (fn [q _] (or (:page q) 1)))

;; Paging within a profile tab keeps the current route and username and swaps only
;; `?page=`. Page 1 drops the param — the canonical first-page URL.
(rf/reg-event :profile/go-to-page
  (fn [{rt :rf.db/runtime} [_ page]]
    (let [{:keys [current]} (get rt :rf.runtime/routing)
          {:keys [route-id params]} current
          query (cond-> {} (> page 1) (assoc :page page))]
      {:fx [[:dispatch [:rf.route/navigate route-id params {:query query}]]]})))

(reg-view profile-page []
  (let [username       (:username @(subscribe [:rf.route/params]))
        favorites?     @(subscribe [:profile/favorites-tab?])
        page           @(subscribe [:profile/page])
        current-user   @(subscribe [:auth/user])
        profile-state  @(subscribe [:rf.resource/state {:resource :realworld/profile :params {:username username}}])
        ;; The active tab decides which list resource (and params) to read — the
        ;; same (resource, params) the matching route ensured under.
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
              [:img.user-img {:src (avatar/avatar-src image)}]
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
             ;; The two profile tabs — each just a route-link, with the active one
             ;; picked out by the current route id (and no app-db tab state).
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
