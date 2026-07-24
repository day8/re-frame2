(ns realworld-resources.routing
  "Routes for the RealWorld-on-resources example. See the routing guide:
   ../../../docs/routing/concepts.md.

   The big idea here is worth saying plainly: entering a route is what causes the
   page's server-state to load — declaratively, via `:resources` route metadata.
   No view kicks off a fetch. On entry the runtime marks each listed resource
   active with owner `[:route route-id nav-token]` and ensures it with cause
   `[:route-entry route-id nav-token]`; on leave it releases the owner by token
   and suppresses any straggling reply by generation. The views just read the
   cache, passively. Navigation is the cause; the data follows.

   A few knobs shape how each route resource behaves. `:blocking?` holds the route
   transition pending until the resource settles; a non-blocking one fetches in
   the background. `:keep-previous?` keeps the current list on screen while a new
   filter or page loads. `:when` makes a resource conditional without resorting to
   sentinel nil params.

   (On the server, that same `:blocking?` flag becomes the SSR wait point. This
   example is client-only; the worked demo of resource SSR preload + hydration is
   `examples/capabilities/ssr/resources_ssr/`. See ../../../docs/ssr/concepts.md.)

   The session-scoped personalised feed is a declarative route resource too — no
   special-casing. The home route declares it with
   `:scope {:from-db :realworld/session}`, a named resolver reference (see
   scope.cljs) the runtime resolves against the navigation handler's app-db at
   route entry. So the route owns the feed under its nav-token and releases it on
   leave, exactly like the public reads. Logged out, the reference resolves nil
   and the feed entry simply isn't planned — fail-closed, nothing to load.

   One wiring note: loading both `re-frame.resources` and `re-frame.routing` is
   what gets the `:resources` route-metadata key accepted, since resources
   late-binds it into routing."
  (:require [re-frame.core :as rf]
            ;; The routing runtime. Loading it triggers its hook + reg-sub
            ;; registrations; without it the reg-route calls have nothing to hook
            ;; into. Aliased so ROUTER WIRING below can build `url-strategy` off
            ;; `routing/with-base-path` + `routing/history-url-strategy`.
            [re-frame.routing :as routing]
            ;; Loading resources is what makes `:resources` route-metadata
            ;; accepted — it's the late-bound routing extension.
            [re-frame.resources]))

;; ============================================================================
;; ROUTES — each declares the server-state its page needs via `:resources`
;; ============================================================================

;; The route resources for the home page and its tag-filtered sibling. Both routes
;; plan the same three reads — global list, popular tags, session feed — and
;; differ in only one spot: where the active tag comes from. The `/tag/:tag` route
;; pulls it from path params; the bare home route has none. So `home-resources`
;; takes a `tag-fn` and lets each route hand in its own tag source.
(defn- home-resources [tag-fn]
  [{:resource  :realworld/articles
    ;; Route → resource params: the active tag (a param, or nil) and `?page=`
    ;; both flow into identity. Every server-visible list option earns a place in
    ;; the cache key.
    :params    (fn [route] {:tag  (tag-fn route)
                            ;; Default to page 1, so the canonical no-`?page=` URL
                            ;; owns the SAME `{:page 1}` key the views subscribe
                            ;; through (`(or (:page q) 1)`). A raw nil would mint a
                            ;; `{:page nil}` entry no view ever reads, which leaves
                            ;; first-page lists stubbornly empty.
                            :page (or (get-in route [:query :page]) 1)})
    :blocking? true
    :keep-previous? true}
   {:resource  :realworld/tags
    :params    (fn [_route] {})
    :blocking? false}
   ;; The personalised feed — a declarative route resource, scoped by the named
   ;; `{:from-db :realworld/session}` resolver. The runtime resolves the scope
   ;; against the navigation handler's app-db at route entry, owns it under the
   ;; route nav-token, and releases it on leave, just like the public reads above.
   ;; Logged out, the reference resolves nil and the feed simply isn't planned —
   ;; no scope, no fetch, no chance of one user's feed leaking to another. `?page=`
   ;; flows into params here like every other paginated list.
   {:resource  :realworld/feed
    :scope     {:from-db :realworld/session}
    ;; Default to page 1 — the feed subscription reads `(or (:page q) 1)` too, so
    ;; the route has to own `{:page 1}` on the bare URL.
    :params    (fn [route] {:page (or (get-in route [:query :page]) 1)})
    :blocking? false
    :keep-previous? true}])

(rf/reg-route :realworld/home
  {:doc   "Home: the global article list and popular tags, plus the personalised
           feed when you're signed in. `?feed=following` switches to the session
           feed (the official-contract token — note it's `following`, not `your`)
           and `?page=` paginates; both flow into the resources' params.
           `:keep-previous?` keeps the current page on screen while the next loads,
           so there's no flicker. The tag filter is its own `/tag/:tag` PATH route
           below — the tag rides as a route param, matching the official Conduit
           route shape."
   :query [:map
           [:feed {:optional true} :string]
           [:page {:optional true} :int]]
   :scroll   :top
   :resources (home-resources (fn [_route] nil))} "/")

(rf/reg-route :realworld/home-tag
  {:doc   "The tag-filtered article list, at the official RealWorld `/tag/:tag`
           PATH route. The active tag is a route param that flows into the
           articles resource's params, so each tag is its own cache entry; `?page=`
           paginates within it (`/tag/:tag?page=2`). Same three reads as the home
           route."
   :params [:map [:tag :string]]
   :query  [:map [:page {:optional true} :int]]
   :scroll :top
   :resources (home-resources (fn [route] (get-in route [:params :tag])))} "/tag/:tag")

(rf/reg-route :realworld.auth/login
  {:doc "Login page."} "/login")

(rf/reg-route :realworld.auth/register
  {:doc "Register page."} "/register")

(rf/reg-route :realworld.user/settings
  {:doc  "User settings page (requires auth). `:on-match` seeds the settings draft
          from the authenticated user once, on route entry. With the load happening
          there rather than in render, the view stays a pure Form-1 that never
          dispatches out of band — so a re-render can't re-run the load and stomp
          on edits you've got in flight."
   :on-match [[:settings/load]]
   :tags #{:requires-auth}} "/settings")

(rf/reg-route :realworld.editor/new
  {:doc       "Create a new article (requires auth). `:on-match` resets the editor
               slice and registers the can-submit flow; `:can-leave` blocks a
               navigate-away while the draft is dirty (see route guard,
               ../../../docs/routing/glossary.md#route-guard). No route
               `:resources` here — you're starting from a blank draft, so there's
               nothing to load."
   :tags      #{:requires-auth}
   :on-match  [[:editor/initialise]]
   :can-leave [:editor/can-leave?]} "/editor")

(rf/reg-route :realworld.editor/edit
  {:doc       "Edit an existing article (requires auth). The article read is a
               declarative route `:resource`, exactly like every other page here:
               the runtime owns it under `[:route :realworld.editor/edit nav-token]`
               on entry and RELEASES that owner on every route leave — so leaving
               the editor for ANY route (home, a profile, settings, the saved
               article, or a new draft) drops the read with no view teardown and no
               hand-rolled owner. `:on-match [[:editor/load-article]]` resets the
               editor slice and asks to be told when that same read settles (an
               OWNERLESS `:reply-to [:editor/article-loaded]` ensure that joins the
               route's load) so it can seed the draft + baseline; it mints no owner
               of its own. It is NON-blocking: the form renders immediately and
               fills in when the read lands. `:can-leave` blocks a dirty
               navigate-away."
   :params    [:map [:slug :string]]
   :tags      #{:requires-auth}
   :on-match  [[:editor/load-article]]
   :resources [{:resource  :realworld/article
                :params    (fn [route] {:slug (get-in route [:params :slug])})
                :blocking? false}]
   :can-leave [:editor/can-leave?]} "/editor/:slug")

(rf/reg-route :realworld.article/show
  {:doc    "Article detail plus its comments. Both load on entry; the comments are
            a sub-resource keyed by the same slug."
   :params [:map [:slug :string]]
   :scroll :top
   :resources
   [{:resource  :realworld/article
     :params    (fn [route] {:slug (get-in route [:params :slug])})
     :blocking? true}
    {:resource  :realworld/comments
     :params    (fn [route] {:slug (get-in route [:params :slug])})
     :when      (fn [route _ctx] (some? (get-in route [:params :slug])))
     :blocking? false
     :keep-previous? true}]} "/article/:slug")

;; The profile SHELL. `:realworld.profile/show` (the authored-articles tab, the
;; canonical `/profile/:username` page) owns the shared profile-banner read
;; ONCE, and the favorites tab below declares `:parent :realworld.profile/show`
;; to inherit it — EP-0037 R2's effective parent-to-leaf resource plan. Before
;; R2 both tabs restated the byte-identical banner entry; now the banner has one
;; clear declaration and the two tabs cannot drift its `:blocking?`. The authored
;; list is this tab's OWN content, so it is gated with `:when` to the show leaf —
;; a navigation to the favorites child inherits the banner but not this list.
(rf/reg-route :realworld.profile/show
  {:doc    "A user's profile banner plus the articles they authored — the default
            profile tab, and the layout PARENT that owns the shared banner read
            for the favorites tab (EP-0037 R2). The `?page=` query paginates the
            authored list."
   :params [:map [:username :string]]
   :query  [:map [:page {:optional true} :int]]
   :resources
   [{:resource  :realworld/profile
     :params    (fn [route] {:username (get-in route [:params :username])})
     :blocking? true}
    {:resource  :realworld/author-articles
     ;; The authored list is THIS tab's own content — gated to the show leaf so
     ;; the favorites child (which shares this banner) does not also fetch it.
     :when      (fn [route _ctx] (= :realworld.profile/show (:id route)))
     ;; Default to page 1 to match the view's `(or (:page q) 1)` key.
     :params    (fn [route] {:username (get-in route [:params :username])
                             :page     (or (get-in route [:query :page]) 1)})
     :blocking? false
     :keep-previous? true}]} "/profile/:username")

(rf/reg-route :realworld.profile/favorites
  {:doc    "A user's profile banner plus the articles they favorited — the second
            official profile tab (`/profile/:username/favorites`). The banner read
            is INHERITED from the `:realworld.profile/show` parent (EP-0037 R2 —
            declaring `:parent` composes its `:resources`), so this tab declares
            ONLY its own `:realworld/favorited-articles` list; `?page=` paginates
            it. Favoriting / unfavoriting from this tab invalidates `[:article
            slug]`, which this list carries — so it refetches, and an unfavorited
            article drops right out."
   :parent :realworld.profile/show
   :params [:map [:username :string]]
   :query  [:map [:page {:optional true} :int]]
   :resources
   [{:resource  :realworld/favorited-articles
     ;; Default to page 1 to match the view's `(or (:page q) 1)` key.
     :params    (fn [route] {:username (get-in route [:params :username])
                             :page     (or (get-in route [:query :page]) 1)})
     :blocking? false
     :keep-previous? true}]} "/profile/:username/favorites")

(rf/reg-route :rf.route/not-found
  {:doc "Fallback when no other route matches."} "/_404")

;; ============================================================================
;; AUTH GUARD
;; ============================================================================
;;
;; Route-level auth turns out to be nothing exotic — just a plain interceptor. It
;; redirects unauthenticated users away from any `:requires-auth`-tagged route to
;; login, stashing where they were headed under `[:auth :return-to]` so we can
;; bounce them back afterward. Crucially it guards all three navigation entry
;; points — programmatic nav, anchor click, and URL-bar / popstate — so there's no
;; back door: a protected route is unreachable while logged out, by any path. See
;; route guard: ../../../docs/routing/glossary.md#route-guard.

;; `current` is the current route slice ([:rf.runtime/routing :current]) — needed
;; to resolve an in-place request (no :to / :url — stay on the current route,
;; change only the query / #fragment) the same way the runtime does. Without it a
;; session that expires WHILE on a `:requires-auth` route could navigate in place
;; straight past the guard.
;;
;; What comes back is the FULL resolved address — `{:to :params :query :fragment}`,
;; a valid `:rf.route/navigate` request in its own right — so the guard can stash
;; the EXACT place the user was headed, not a bare route. A partial `{:to :params}`
;; would strand the query string and #fragment: a deep-link to
;; `/editor/my-slug?draft=1#preview` would bounce back to a bare `/editor/my-slug`
;; after login. `:query` / `:params` default to `{}` (never nil); `:fragment` is
;; `nil` when absent.
(defn- resolve-nav-target [[ev-id a _b] current]
  (case ev-id
    :rf.route/navigate (let [{:keys [to url params query fragment]} a]  ;; a is the flat request map
                         (cond
                           to  {:to to :params (or params {}) :query (or query {}) :fragment fragment}   ;; route-id destination
                           url (when-let [{:keys [route-id params query fragment]} (routing/match-url url)]  ;; {:url ...} escape hatch
                                 {:to route-id :params (or params {}) :query (or query {}) :fragment fragment})
                           :else                            ;; in-place — stay on the current route, patch query / #fragment
                           {:to       (:route-id current)
                            :params   (or (:params current) {})
                            :query    (or query (:query current) {})
                            :fragment (if (contains? a :fragment) fragment (:fragment current))}))
    :rf.route/url-requested  (let [{:keys [to params url query fragment]} a]
                         (cond
                           to  {:to to :params (or params {}) :query (or query {}) :fragment fragment}
                           url (when-let [{:keys [route-id params query fragment]} (routing/match-url url)]
                                 {:to route-id :params (or params {}) :query (or query {}) :fragment fragment})))
    :rf.route/handle-url-change (when-let [{:keys [route-id params query fragment]} (routing/match-url a)]
                                  {:to route-id :params (or params {}) :query (or query {}) :fragment fragment})
    nil))

;; The guard is a registered interceptor, referenced by id
;; (`:realworld-resources.routing/auth-guard`) from the demo frame's
;; `:interceptors` chain — set in the `frame-root {:id …}` ensure form in
;; core.cljs rather than dropped in inline. The timing works out because
;; `reg-interceptor` is a top-level load-time registration, and core.cljs requires
;; this ns: the descriptor is registered well before the provider's config goes
;; looking for it at frame creation.
(rf/reg-interceptor :realworld-resources.routing/auth-guard
  {:doc "Route-level auth guard: redirect unauthenticated users away from
         `:requires-auth`-tagged routes to login, stashing the FULL address they
         were headed for (path, params, query, and #fragment) under
         `[:auth :return-to]` so `:auth/post-login-redirect` (auth.cljs) can bounce
         them back to the exact URL."}
  {:before (fn auth-guard-before [ctx]
             ;; The route slice is framework runtime-db state — read it from the
             ;; :rf.db/runtime coeffect so an in-place request resolves to the
             ;; protected route the user is already on.
             (if-let [{:keys [to] :as target} (resolve-nav-target
                                                (get-in ctx [:coeffects :event])
                                                (get-in ctx [:coeffects :rf.db/runtime
                                                             :rf.runtime/routing :current]))]
               (let [route-meta  (rf/handler-meta :route to)
                     needs-auth? (boolean (some #{:requires-auth} (:tags route-meta)))
                     logged-in?  (some? (get-in ctx [:coeffects :db :auth :user]))]
                 (if (and needs-auth? (not logged-in?))
                   (-> ctx
                       (assoc :rf/skip-handler? true)
                       ;; `target` IS the full resolved address {:to :params :query
                       ;; :fragment} — stash it whole so the bounce-back returns to
                       ;; the exact URL, query string and #fragment included.
                       (assoc-in [:effects :db]
                                 (assoc-in (get-in ctx [:coeffects :db])
                                           [:auth :return-to] target))
                       (assoc-in [:effects :fx]
                                 [[:dispatch [:rf.route/navigate {:to :realworld.auth/login}]]]))
                   ctx))
               ctx))})

;; ============================================================================
;; ROUTER WIRING  (base-path-aware)
;; ============================================================================
;;
;; Each entry is served from its OWN deployment sub-path (a host mounting the
;; demos side by side), but the routes above are written as if the app owned
;; `/`. `with-base-path` wraps the default history strategy so that prefix is
;; stripped/re-added automatically at the framework's four egress/ingress
;; consult points (Spec 012 §URL strategies). The frame's `:url-bound? true`
;; creation installs the base-path-aware popstate listener AND does the first
;; URL→route sync itself — which is the trick that fires the route's
;; `:resources` ensures on entry, with zero hand-rolled listener or explicit
;; install call.
;;
;; DEPLOYMENT BASE IS ENTRY-SPECIFIC. The route table + all the dataflow above
;; are shared verbatim between the two arms; only where each is MOUNTED differs,
;; so each entry names its own base-path strategy:
;;   - the Reagent arm (core.cljs, build `:examples/realworld-resources`) mounts
;;     under `/realworld-resources`  → `url-strategy` below;
;;   - the re-frame.ui arm (ui_core.cljs, build `:examples/realworld-resources-ui`)
;;     mounts under `/realworld-resources-ui`, the deploy sub-path that MATCHES
;;     its `out/examples/realworld-resources-ui` output  → `url-strategy-ui` below.
;; The ui arm must NOT reuse the Reagent `/realworld-resources` base: that prefix
;; fails to strip off a `/realworld-resources-ui/…` URL (it is a prefix-sharing
;; SIBLING, not a path-segment ancestor), so the shell would boot into a
;; not-found route and generated links would target the Reagent mount
;; (rf2-nn5s8 audit rider). `strip-base-path` fails safe on `/`, so either
;; strategy also boots correctly when the build is served at the server root.
(def url-strategy
  (routing/with-base-path routing/history-url-strategy "/realworld-resources"))

;; The re-frame.ui arm's entry-specific twin (see the note above): SAME shared
;; route table + dataflow, mounted under its own `/realworld-resources-ui` base.
;; The Reagent `url-strategy` above is left exactly as it was.
(def url-strategy-ui
  (routing/with-base-path routing/history-url-strategy "/realworld-resources-ui"))
