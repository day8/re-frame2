(ns realworld-resources.ui-views
  "The passive pages + shell chrome for the RealWorld (Conduit) example, rendered
   in NATIVE re-frame.ui (`ui/defview`) — the compiled-view counterpart of the
   Reagent `realworld-resources.views` (plus the header / footer / dialog chrome
   that lived in `realworld-resources.core`).

   Rendering tier only. Every read is a passive `(sub [:rf.resource/* …])` /
   `(sub [:rf/mutation …])`; every write is a committed event vector (a literal
   `[:event …]`, a `{:event … :prevent-default true}` options map for an anchor /
   form that must not let the browser navigate, or a `ui/event` when the live
   native value is needed). The DATAFLOW — the `:home/*` / `:ui/*` /
   `:comment-form/*` / `:profile/*` events + subs — lives in
   `realworld-resources.views` and is UNCHANGED and shared; these views reach it by
   keyword. No view ever fetches; the route's `:resources` metadata causes the
   load, and the view renders whatever the cache already holds.

   Two compiled-view idioms recur and are worth naming once:

   - **Dynamic lists render a KEYED CHILD VIEW in `for` child position.** A `for`
     sits directly in a parent element's child position (not `into`, which the
     compiler cannot see), and each row is an internal `defview` call carrying
     `:key`. A row whose handler needs a per-row value (a page number, a tag)
     passes that value as a PROP to the keyed child — a handler may not capture a
     `for` binding (per-row committed slots need per-row instances), so the child
     view is where the handler reads it.

   - **Resource status is one canonical `cond`.** `:loading?` → skeleton;
     `:error` without data → error; otherwise render `:data`, plus a quiet
     refresh / keep-previous hint. The view never invents stale-while-revalidate;
     the resource state carries it."
  (:require ["react" :as react]
            [re-frame.ui :as ui :refer [defview sub]]
            [realworld-resources.http :as rh]
            [realworld-shared.avatar :as avatar]
            [realworld-shared.markdown :as md]))

;; ============================================================================
;; APP-SHELL CHROME — header / footer / dialog / not-found
;; ============================================================================

(defview header
  "The Conduit navbar. Route-links for Home / New Article / Settings / the user's
   own profile (with avatar) when signed in, or Sign in / Sign up when not."
  []
  (let [authed? (sub [:auth/authenticated?])
        user    (sub [:auth/user])]
    [:nav.navbar.navbar-light
     [:div.container
      [ui/route-link {:to :realworld/home :class "navbar-brand"} "conduit"]
      [:ul.nav.navbar-nav.pull-xs-right
       [:li.nav-item [ui/route-link {:to :realworld/home :class "nav-link"} "Home"]]
       (if authed?
         [:<>
          [:li.nav-item
           [ui/route-link {:to :realworld.editor/new :class "nav-link" :data-testid "nav-new-article"}
            [:i.ion-compose] " New Article"]]
          [:li.nav-item
           [ui/route-link {:to :realworld.user/settings :class "nav-link" :data-testid "nav-settings"}
            [:i.ion-gear-a] " Settings"]]
          [:li.nav-item
           [ui/route-link {:to :realworld.profile/show :params {:username (:username user)}
                           :class "nav-link" :data-testid "nav-username"}
            [:img.user-pic {:src (avatar/avatar-src (:image user)) :alt ""}]
            " " (:username user)]]
          [:li.nav-item
           [:a.nav-link {:data-testid "nav-logout" :href "#"
                         :on-click {:event [:auth/flow [:auth/logout]] :prevent-default true}}
            "Logout"]]]
         [:<>
          [:li.nav-item [ui/route-link {:to :realworld.auth/login :class "nav-link" :data-testid "nav-signin"} "Sign in"]]
          [:li.nav-item [ui/route-link {:to :realworld.auth/register :class "nav-link" :data-testid "nav-signup"} "Sign up"]]])]]]))

(defview footer
  []
  [:footer
   [:div.container
    [ui/route-link {:to :realworld/home :class "logo-font"} "conduit"]
    [:span.attribution "An interactive learning project from Thinkster."
     " Code & design licensed under MIT."]]])

(defview pending-nav-dialog
  "The 'you have unsaved changes' dialog. Appears when a `:can-leave` guard blocks
   a navigation (the editor refusing to discard a dirty draft). Reads the blocked
   navigation off `:rf/pending-navigation`; the buttons wave it through or call it
   off. Renders nothing when there is no pending navigation."
  []
  (let [pending (sub [:rf/pending-navigation])
        id      (:id pending)]
    [:<>
     (when pending
       [:div.pending-nav-overlay {:data-testid "pending-nav-dialog"}
        [:div.pending-nav-dialog
         [:p (or (:reason pending) "You have unsaved changes. Leave anyway?")]
         [:button {:data-testid "pending-nav-discard"
                   :on-click [:rf.route/continue id]}
          "Discard changes"]
         [:button {:data-testid "pending-nav-stay"
                   :on-click [:rf.route/cancel id]}
          "Stay"]]])]))

(defview not-found-page
  []
  [:div.not-found-page
   [:h1 "Page not found"]
   [ui/route-link {:to :realworld/home} "Home"]])

;; ============================================================================
;; ARTICLE CARD (shared across home + profile lists)
;; ============================================================================

(defview article-preview
  "A single article card — a keyed row in a list. Its favourite button is
   optimistic: the heart + count already show the click (the `:optimistic-tags`
   patched the cached article before the request left), and `:optimistic?` off the
   mutation state drives a subtle in-flight cue. Not disabled while pending — the
   user is already looking at their change."
  [{:keys [article]}]
  (let [{:keys [slug title description createdAt favoritesCount favorited author tagList]} article
        fav-state (sub [:rf/mutation {:instance [:favorite slug]}])]
    [:div.article-preview {:data-testid (str "article-preview-" slug)}
     [:div.article-meta
      [ui/route-link {:to :realworld.profile/show :params {:username (:username author)}}
       [:img.user-pic {:src (avatar/avatar-src (:image author)) :alt ""}]]
      [:div.info
       [ui/route-link {:to :realworld.profile/show :params {:username (:username author)} :class "author"}
        (:username author)]
       [:span.date createdAt]]
      [:button.btn.btn-outline-primary.btn-sm.pull-xs-right
       {:type "button"
        :data-testid (str "favorite-" slug)
        :class (cond-> ""
                 favorited                (str " active")
                 (:optimistic? fav-state) (str " optimistic"))
        :on-click [:ui/favorite slug favorited]}
       [:i.ion-heart] " "
       [:span {:data-testid (str "favorites-count-" slug)} favoritesCount]]]
     [ui/route-link {:to :realworld.article/show :params {:slug slug}
                     :class "preview-link" :data-testid (str "article-link-" slug)}
      [:h1 title]
      [:p description]
      [:span "Read more..."]
      [:ul.tag-list
       (for [tag tagList]
         [:li.tag-default.tag-pill.tag-outline {:key tag} tag])]]]))

;; ============================================================================
;; PAGINATION — official Conduit shape (numbered 1-indexed pages)
;; ============================================================================

(defview page-link
  "One numbered pagination link — a keyed row. `on-page` is the navigation event
   id (`:home/go-to-page` / `:profile/go-to-page`) threaded through as a prop, so
   the click builds `[on-page page]` in a `ui/event` (the row value is a prop, not
   a captured `for` binding). preventDefault keeps the `href=\"#\"` from jumping."
  [{:keys [page current-page on-page]}]
  [:li.page-item {:class (when (= page current-page) "active")}
   [:a.page-link {:href "#" :data-testid (str "page-" page)
                  :on-click (ui/event [e] (.preventDefault e) [on-page page])}
    (str page)]])

(defview pagination
  "Official-style numbered pagination. `:articles-count` is the server total,
   `:current-page` is 1-indexed, and `:on-page` is the navigation event id fired
   with a page number. Renders nothing when there is only one page."
  [{:keys [articles-count current-page on-page]}]
  (let [total-pages (rh/page-count articles-count)]
    [:<>
     (when (> total-pages 1)
       [:nav
        [:ul.pagination {:data-testid "pagination"}
         (for [p (range 1 (inc total-pages))]
           [page-link {:key p :page p :current-page current-page :on-page on-page}])]])]))

(defview article-list
  "Render a list-resource state: skeleton / error / list, with a background-refresh
   indicator + warning, the keep-previous placeholder, and — when `:articles-count`
   and `:on-page` are supplied — numbered pagination. While a new page/filter key
   first-loads, `:previous?` + `:previous-data` keep the prior page on screen (no
   skeleton flash)."
  [{:keys [state empty-msg current-page on-page]}]
  (cond
    (and (:loading? state) (not (:previous? state)))
    [:div.article-preview {:data-testid "list-skeleton"} "Loading articles…"]

    (and (:error state) (not (:has-data? state)) (not (:previous? state)))
    [:div.article-preview.error {:data-testid "list-error"}
     (str "Couldn't load articles: " (rh/failure->message (:error state)))]

    :else
    (let [data           (or (:data state) (:previous-data state))
          articles       (:articles data)
          articles-count (:articlesCount data)]
      [:<>
       (when (:previous? state)
         [:div.refresh-indicator {:data-testid "list-keeping-previous"} "Loading next page…"])
       (when (:fetching? state)
         [:div.refresh-indicator {:data-testid "list-refreshing"} "Refreshing…"])
       (when (:refresh-error state)
         [:div.refresh-warn {:data-testid "list-refresh-warn"} "Refresh failed; showing last-known data."])
       (if (seq articles)
         [:div {:data-testid "article-list"}
          (for [a articles]
            [article-preview {:key (:slug a) :article a}])]
         [:div.article-preview.empty-feed-message {:data-testid "list-empty"}
          (or empty-msg "No articles are here… yet.")])
       (when (and on-page articles-count)
         [pagination {:articles-count articles-count
                      :current-page   (or current-page 1)
                      :on-page        on-page}])])))

;; ============================================================================
;; HOME PAGE
;; ============================================================================

(defview sidebar-tag
  "One popular-tag pill — a keyed row. `tag` is its own prop, so the
   apply-tag handler reads it without capturing a `for` binding."
  [{:keys [tag]}]
  [:a.tag-pill.tag-default {:href "#" :data-testid (str "tag-" tag)
                            :on-click {:event [:home/apply-tag tag] :prevent-default true}}
   tag])

(defview home-page
  "The home page — a pure function of subs. The personalised feed reads through
   the named `{:from-db :realworld/session}` scope resolver: the subscription
   resolves its own scope and re-keys reactively across login / logout. A
   favourite shows up in Your Feed via the mutation's own session-scoped
   invalidation descriptor — no off-render reaction, no app-level feed patching."
  []
  (let [authed?      (sub [:auth/authenticated?])
        your-feed?   (sub [:home/your-feed?])
        selected-tag (sub [:home/selected-tag])
        page         (sub [:home/page])
        tags-state   (sub [:rf/resource {:resource :realworld/tags :params {}}])
        list-state   (sub [:rf/resource {:resource :realworld/articles
                                         :params  {:tag selected-tag :page page}}])
        feed-state   (when authed?
                       (sub [:rf/resource {:resource :realworld/feed :params {:page page}}]))]
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
                           :on-click {:event [:home/show-your-feed] :prevent-default true}}
              "Your Feed"]])
          [:li.nav-item
           [:a.nav-link {:href "#" :data-testid "global-feed-tab"
                         :class (when (not your-feed?) "active")
                         :on-click {:event [:home/show-global-feed] :prevent-default true}}
            "Global Feed"]]
          (when selected-tag
            [:li.nav-item
             [:a.nav-link.active {:href "#" :data-testid "active-tag"
                                  :on-click {:event [:home/clear-tag] :prevent-default true}}
              [:i.ion-pound] " " selected-tag]])]]
        (if (and authed? your-feed?)
          [article-list {:state feed-state :empty-msg "Your feed is empty — follow some authors."
                         :current-page page :on-page :home/go-to-page}]
          [article-list {:state list-state
                         :current-page page :on-page :home/go-to-page}])]
       [:div.col-md-3
        [:div.sidebar
         [:p "Popular Tags"]
         (if (:has-data? tags-state)
           [:div.tag-list {:data-testid "tag-list"}
            (for [tag (:tags (:data tags-state))]
              [sidebar-tag {:key tag :tag tag}])]
           [:div.tag-list {:data-testid "tags-loading"} "Loading tags…"])]]]]]))

;; ============================================================================
;; ARTICLE DETAIL + COMMENTS
;; ============================================================================

;; ----------------------------------------------------------------------------
;; ARTICLE BODY — the one genuinely runtime-shaped subtree, crossed via ui/raw
;; ----------------------------------------------------------------------------
;;
;; `md/render` (realworld-shared.markdown) emits sanitized CommonMark as HICCUP
;; DATA — never raw HTML, so React escapes all text (no dangerouslySetInnerHTML).
;; re-frame.ui compiles views and deliberately will NOT interpret a runtime hiccup
;; vector as a template ("hiccup is compiled, not interpreted"). So the article
;; body — the single place in the app whose markup shape is decided at runtime —
;; crosses into the compiled view through the sanctioned `ui/raw` HOST-ELEMENT
;; door: this interpreter turns the sanitized hiccup DATA into real React elements
;; (with every text run a React child), preserving the renderer's escaping
;; guarantee across the boundary.

(def ^:private md-attr-renames
  "The handful of HTML→React attribute spellings CommonMark output can carry."
  {:class :className :for :htmlFor :colspan :colSpan :rowspan :rowSpan})

(defn- md-attrs->js [attrs]
  (clj->js (persistent!
            (reduce-kv (fn [m k v] (assoc! m (get md-attr-renames k k) v))
                       (transient {}) (or attrs {})))))

(defn hiccup->element
  "Sanitized markdown hiccup DATA → a React element for `ui/raw`. Text runs stay
   React text children (escaped by React), so the markdown renderer's no-raw-HTML
   guarantee holds across the boundary."
  [node]
  (cond
    (string? node) node
    (number? node) node
    (nil? node)    nil
    (seq? node)    (apply react/createElement react/Fragment #js {}
                          (keep hiccup->element node))
    (vector? node)
    (let [tag        (nth node 0)
          has-attrs? (map? (nth node 1 nil))
          attrs      (when has-attrs? (nth node 1))
          children   (if has-attrs? (subvec node 2) (subvec node 1))]
      (apply react/createElement
             (if (= :<> tag) react/Fragment (name tag))
             (md-attrs->js attrs)
             (keep hiccup->element children)))
    :else (str node)))

(defview comment-card
  "One comment — a keyed row. The delete control shows only for the author's own
   comments; `slug` and the comment id are props, so the handler reads them without
   capturing a `for` binding."
  [{:keys [comment slug current-user]}]
  (let [id        (:id comment)
        mine?     (= (:username current-user) (get-in comment [:author :username]))
        del-state (sub [:rf/mutation {:instance [:delete-comment slug id]}])]
    [:div.card {:data-testid (str "comment-card-" id)}
     [:div.card-block [:p.card-text {:data-testid "comment-body"} (:body comment)]]
     [:div.card-footer
      [ui/route-link {:to :realworld.profile/show :params {:username (get-in comment [:author :username])}
                      :class "comment-author"}
       [:img.comment-author-img {:src (avatar/avatar-src (get-in comment [:author :image])) :alt ""}] " "
       (get-in comment [:author :username])]
      [:span.date-posted (:createdAt comment)]
      (when mine?
        [:button.mod-options {:type "button" :data-testid (str "delete-comment-" id)
                              :aria-label "Delete comment"
                              :disabled (:pending? del-state)
                              :on-click [:comment/delete slug id]}
         [:i.ion-trash-a]])]]))

(defview article-meta
  "Article-detail contextual controls: the author byline, plus Follow/Unfollow on
   the author (non-author viewer) or Edit + Delete (the author). A logged-out
   viewer gets the byline alone. `article` is the unwrapped article map."
  [{:keys [article del-state]}]
  (let [author   (:author article)
        username (:username author)
        slug     (:slug article)
        me       (sub [:auth/user])
        authed?  (sub [:auth/authenticated?])
        own?     (and me (= (:username me) username))]
    [:div.article-meta
     [ui/route-link {:to :realworld.profile/show :params {:username username}}
      [:img.user-pic {:src (avatar/avatar-src (:image author)) :alt ""}]]
     [:div.info
      [ui/route-link {:to :realworld.profile/show :params {:username username} :class "author"}
       username]
      [:span.date (:createdAt article)]]
     (cond
       own?
       [:span
        [ui/route-link {:to :realworld.editor/edit :params {:slug slug}
                        :class "btn btn-sm btn-outline-secondary"
                        :data-testid "article-edit"}
         [:i.ion-edit] " Edit Article"]
        " "
        [:button.btn.btn-sm.btn-outline-danger
         {:type "button" :data-testid "article-delete"
          :disabled (:pending? del-state)
          :on-click [:ui/delete-article slug]}
         [:i.ion-trash-a] " Delete Article"]]

       authed?
       [:button.btn.btn-sm.btn-outline-secondary
        {:type "button" :data-testid "article-follow-author"
         :on-click [:ui/follow-author slug username (:following author)]}
        [:i.ion-plus-round] " "
        (if (:following author) "Unfollow " "Follow ") username]

       :else nil)]))

(defview comment-form
  "The comment composer — shown to a signed-in reader, else a sign-in prompt. A
   controlled textarea (`:rf.ui/value`) and a submit that preventDefaults."
  [{:keys [slug current-user]}]
  (let [post-state (sub [:rf/mutation {:instance [:post-comment slug]}])
        draft      (sub [:comment-form/body])]
    (if current-user
      [:form.card.comment-form
       {:data-testid "comment-form"
        :on-submit {:event [:comment-form/submit slug] :prevent-default true}}
       [:div.card-block
        [:textarea.form-control {:data-testid "comment-body-input" :rows 3 :placeholder "Write a comment..."
                                 :value (or draft "") :disabled (:pending? post-state)
                                 :on-input [:comment-form/edit :rf.ui/value]}]]
       [:div.card-footer
        [:button.btn.btn-sm.btn-primary {:type "submit" :data-testid "comment-submit"
                                         :disabled (:pending? post-state)}
         (if (:pending? post-state) "Posting…" "Post Comment")]]
       (when (:error? post-state)
         [:div.error-messages (rh/failure->message (:error post-state))])]
      [:p
       [ui/route-link {:to :realworld.auth/login} "Sign in"] " or "
       [ui/route-link {:to :realworld.auth/register} "sign up"] " to add comments."])))

(defview article-page
  "The article-detail page — a pure function of subs. The two settle continuations
   it needs (navigate-home on delete, re-stale on follow) are the mutations' own
   `:reply-to` targets in the shared dataflow, so this view holds no off-render
   reaction."
  []
  (let [slug           (:slug (sub [:rf.route/params]))
        current-user   (sub [:auth/user])
        article-state  (sub [:rf/resource {:resource :realworld/article :params {:slug slug}}])
        comments-state (sub [:rf/resource {:resource :realworld/comments :params {:slug slug}}])]
    [:div.article-page
     (cond
       (:loading? article-state)
       [:div.article-preview {:data-testid "article-skeleton"} "Loading article…"]

       (and (:error article-state) (not (:has-data? article-state)))
       [:div.article-preview.error {:data-testid "article-error"}
        (str "Couldn't load article: " (rh/failure->message (:error article-state)))]

       :else
       (let [article   (:article (:data article-state))
             {:keys [title description body tagList favoritesCount favorited]} article
             fav-state (sub [:rf/mutation {:instance [:favorite slug]}])
             del-state (sub [:rf/mutation {:instance [:delete-article slug]}])]
         [:<>
          [:div.banner
           [:div.container
            [:h1 {:data-testid "article-title"} title]
            [:p {:data-testid "article-description"} description]
            (when (:fetching? article-state)
              [:span {:data-testid "article-refreshing"} " (refreshing…)"])
            [:span.article-controls
             [:button.btn.btn-sm
              {:type "button" :data-testid "article-favorite"
               :class (cond-> (if favorited "btn-primary" "btn-outline-primary")
                        (:optimistic? fav-state) (str " optimistic"))
               :on-click [:ui/favorite slug favorited]}
              [:i.ion-heart] " "
              (if favorited "Unfavorite" "Favorite") " Article "
              [:span.counter {:data-testid "article-favorites-count"} "(" favoritesCount ")"]]
             " "
             [article-meta {:article article :del-state del-state}]]]]
          [:div.container.page
           [:div.row.article-content
            [:div.col-md-12
             [:div {:data-testid "article-body"}
              (ui/raw (hiccup->element (md/render body)))]
             [:ul.tag-list
              (for [tag tagList]
                [:li.tag-default.tag-pill.tag-outline {:key tag} tag])]]]
           [:hr]
           [:div.article-actions
            [article-meta {:article article :del-state del-state}]]
           [:div.row
            [:div.col-xs-12.col-md-8.offset-md-2
             [comment-form {:slug slug :current-user current-user}]
             (cond
               (:loading? comments-state)
               [:p {:data-testid "comments-loading"} "Loading comments…"]
               (and (:error comments-state) (not (:has-data? comments-state)))
               [:p.error {:data-testid "comments-error"} "Couldn't load comments."]
               :else
               [:div {:data-testid "comments-list"}
                (for [c (:comments (:data comments-state))]
                  [comment-card {:key (:id c) :comment c :slug slug :current-user current-user}])])]]]]))
     [:p [ui/route-link {:to :realworld/home :data-testid "back-home"} "← Back to feed"]]]))

;; ============================================================================
;; PROFILE — two official tabs: My Articles / Favorited Articles
;; ============================================================================

(defview profile-page
  "A user's profile banner plus one of two article tabs. The active tab is nothing
   more than the current route id (`:realworld.profile/show` authored vs
   `:realworld.profile/favorites`) — there is no tab state in app-db; the view
   reads the route id to choose which list resource to subscribe to."
  []
  (let [username      (:username (sub [:rf.route/params]))
        favorites?    (sub [:profile/favorites-tab?])
        page          (sub [:profile/page])
        current-user  (sub [:auth/user])
        profile-state (sub [:rf/resource {:resource :realworld/profile :params {:username username}}])
        list-state    (if favorites?
                        (sub [:rf/resource {:resource :realworld/favorited-articles
                                            :params  {:username username :page page}}])
                        (sub [:rf/resource {:resource :realworld/author-articles
                                            :params  {:username username :page page}}]))]
    [:div.profile-page
     (cond
       (:loading? profile-state)
       [:div.article-preview {:data-testid "profile-skeleton"} "Loading profile…"]

       (and (:error profile-state) (not (:has-data? profile-state)))
       [:div.article-preview.error {:data-testid "profile-error"} "Couldn't load this profile."]

       :else
       (let [{:keys [bio image following]} (:profile (:data profile-state))
             profile-username (:username (:profile (:data profile-state)))
             own?             (= profile-username (:username current-user))
             follow-state     (sub [:rf/mutation {:instance [:follow profile-username]}])]
         [:<>
          [:div.user-info
           [:div.container
            [:div.row
             [:div.col-xs-12.col-md-10.offset-md-1
              [:img.user-img {:src (avatar/avatar-src image) :alt ""}]
              [:h4 {:data-testid "profile-username"} profile-username]
              [:p bio]
              (when (:fetching? profile-state) [:span {:data-testid "profile-refreshing"} " (refreshing…)"])
              (when-not own?
                [:button.btn.btn-sm.btn-outline-secondary.action-btn
                 {:type "button" :data-testid "follow-button"
                  :disabled (:pending? follow-state)
                  :on-click [:ui/follow profile-username following]}
                 (if following "Unfollow " "Follow ") profile-username])]]]]
          [:div.container
           [:div.row
            [:div.col-xs-12.col-md-10.offset-md-1
             [:div.articles-toggle
              [:ul.nav.nav-pills.outline-active
               [:li.nav-item
                [ui/route-link {:to :realworld.profile/show :params {:username profile-username}
                                :class (str "nav-link" (when-not favorites? " active"))
                                :data-testid "profile-tab-authored"}
                 "My Articles"]]
               [:li.nav-item
                [ui/route-link {:to :realworld.profile/favorites :params {:username profile-username}
                                :class (str "nav-link" (when favorites? " active"))
                                :data-testid "profile-tab-favorited"}
                 "Favorited Articles"]]]]
             [article-list {:state list-state
                            :empty-msg (if favorites?
                                         "No favorited articles are here… yet."
                                         "No articles here yet.")
                            :current-page page
                            :on-page :profile/go-to-page}]]]]]))]))
