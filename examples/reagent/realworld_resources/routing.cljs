(ns realworld-resources.routing
  "Routes for the RealWorld-on-resources example.

   The headline of this variant: route entry CAUSES the page's server-state to
   load, declaratively, via `:resources` route metadata (Spec 016 §Route
   integration). On entry the runtime marks each listed resource active with
   owner `[:route route-id nav-token]` and ensures it with cause
   `[:route-entry route-id nav-token]`; on leave it releases the owner by token
   and suppresses any stale reply by generation. The views never fetch — they
   read the runtime cache passively.

   `:blocking?` keeps the route transition pending until the resource settles;
   a non-blocking resource fetches in the background. `:keep-previous?` keeps
   the prior list visible while a new filter/page first-loads. `:when` makes a
   resource conditional without sentinel nil params.

   (On the server, the same `:blocking?` flag is the SSR wait point — the route
   plan drains blocking resources before rendering, then serialises the resource
   partition for the client to hydrate without refetching, Spec 016 §SSR and
   hydration over Spec 011. This example is CLIENT-ONLY and does NOT exercise
   that path; the dedicated worked demo of resource SSR preload + hydration is
   `examples/reagent/resources_ssr/`.)

   The SESSION-scoped personalised feed is now ALSO a declarative route
   resource (EP-0016 D3): the home route declares it with `:scope {:from-db
   :realworld/session}`, a named-resolver reference (see scope.cljs) the runtime
   resolves against the navigation handler's app-db at route entry — so the
   route owns the feed under its nav-token and releases it on leave, exactly
   like the public reads. This retires the prior `:home/on-match` event +
   app-minted lease the variant needed before named scope resolvers existed (a
   route `:scope` resolver couldn't see app-db, so the feed had to be ensured
   from an event that could). Logged out, the reference resolves nil and the
   feed entry is simply not planned (fail-closed — no feed to load).

   The resources artefact LATE-BINDS the `:resources` route-metadata key into
   routing, so loading both `re-frame.resources` and `re-frame.routing` is what
   makes the key accepted (Spec 012 rejects unknown bare route-metadata keys
   otherwise)."
  (:require [clojure.string]
            [re-frame.core :as rf]
            ;; Routing ships in day8/re-frame2-routing. Loading the ns triggers
            ;; its hook + reg-sub registrations; without it the reg-route calls
            ;; throw :rf.error/routing-artefact-missing. Aliased so the popstate
            ;; handler resolves the URL owner via `routing/url-owner-frame-id`.
            [re-frame.routing :as routing]
            ;; Loading resources is what makes `:resources` route-metadata
            ;; accepted (the late-bound routing extension).
            [re-frame.resources]))

;; ============================================================================
;; ROUTES — each declares the server-state its page needs via `:resources`
;; ============================================================================

;; The route resources for the home page + its tag-filtered sibling. Both
;; routes plan the same three reads (global list, popular tags, session feed);
;; they differ only in WHERE the active tag comes from — the `/tag/:tag` route
;; reads it from PATH params, the bare home route has none. `home-resources`
;; takes a `tag-fn` so each route supplies its own tag source (rf2-e90vfv).
(defn- home-resources [tag-fn]
  [{:resource  :realworld/articles
    ;; Route → resource params: the active tag (param or nil) AND `?page=` flow
    ;; into identity. Every server-visible list option participates in the
    ;; cache key (Spec 016 §Paginated and previous data).
    :params    (fn [route] {:tag  (tag-fn route)
                            ;; Default to page 1 so the canonical no-`?page=`
                            ;; URL owns the SAME `{:page 1}` key the views
                            ;; subscribe through (`(or (:page q) 1)`); a raw
                            ;; nil would mint a `{:page nil}` entry no view
                            ;; reads, leaving first-page lists permanently
                            ;; empty (rf2-01jbr9).
                            :page (or (get-in route [:query :page]) 1)})
    :blocking? true
    :keep-previous? true}
   {:resource  :realworld/tags
    :params    (fn [_route] {})
    :blocking? false}
   ;; The personalised feed — a declarative route resource scoped by the
   ;; named `{:from-db :realworld/session}` resolver (EP-0016 D3). The runtime
   ;; resolves the scope against the navigation handler's app-db at route
   ;; entry, owns it under the route nav-token, and releases it on leave —
   ;; replacing the prior `:home/on-match` event + app-minted lease. Logged
   ;; out the reference resolves nil, so the feed is simply not planned (no
   ;; scope, no fetch — the feed never leaks across users). The `?page=` flows
   ;; into params here like every other paginated list.
   {:resource  :realworld/feed
    :scope     {:from-db :realworld/session}
    ;; Default to page 1 — the feed subscription reads `(or (:page q) 1)`
    ;; too, so the route must own `{:page 1}` on the bare URL (rf2-01jbr9).
    :params    (fn [route] {:page (or (get-in route [:query :page]) 1)})
    :blocking? false
    :keep-previous? true}])

(rf/reg-route :realworld/home
  {:doc   "Home: the global article list + popular tags, plus (when signed in)
           the personalised feed. `?feed=following` switches to the session
           feed (the official-contract token — NOT `?feed=your`) and `?page=`
           paginates — both flow into the resources' params. `:keep-previous?`
           keeps the prior page visible while the next first-loads (no
           flicker). The tag filter is its own `/tag/:tag` PATH route below
           (rf2-e90vfv route-shape conformance — no `?tag=` query)."
   :path  "/"
   :query [:map
           [:feed {:optional true} :string]
           [:page {:optional true} :int]]
   :scroll   :top
   :resources (home-resources (fn [_route] nil))})

(rf/reg-route :realworld/home-tag
  {:doc   "The tag-filtered article list at the official RealWorld `/tag/:tag`
           PATH route (rf2-e90vfv — replacing the prior `?tag=` query). The
           active tag is a route PARAM that flows into the articles resource's
           params, so each tag is a distinct cache entry; `?page=` paginates
           within it (`/tag/:tag?page=2`). Same three reads as the home route."
   :path   "/tag/:tag"
   :params [:map [:tag :string]]
   :query  [:map [:page {:optional true} :int]]
   :scroll :top
   :resources (home-resources (fn [route] (get-in route [:params :tag])))})

(rf/reg-route :realworld.auth/login
  {:doc "Login page." :path "/login"})

(rf/reg-route :realworld.auth/register
  {:doc "Register page." :path "/register"})

(rf/reg-route :realworld.user/settings
  {:doc  "User settings page (requires auth). `:on-match` seeds the settings
          draft from the authenticated user ONCE on route entry — the same
          route-entry seam the `:rf.http/managed` sibling uses (its settings
          route also `:on-match [[:settings/load]]`). The settings view is then
          a pure Form-1 that NEVER dispatches out of band, so a re-render can no
          longer re-run the load and clobber in-progress field edits."
   :path "/settings"
   :on-match [[:settings/load]]
   :tags #{:requires-auth}})

(rf/reg-route :realworld.editor/new
  {:doc       "Create a new article (requires auth). `:on-match` resets the
               editor slice + registers the can-submit flow; `:can-leave` blocks
               a navigate-away while the draft is dirty (Spec 012 §Redirects and
               guards). No route `:resources` — create starts from a blank draft."
   :path      "/editor"
   :tags      #{:requires-auth}
   :on-match  [[:editor/initialise]]
   :can-leave [:editor/can-leave?]})

(rf/reg-route :realworld.editor/edit
  {:doc       "Edit an existing article (requires auth). `:on-match` seeds the
               editor from the article read under a releaseable lease (the
               editor page's load reaction copies the settled data into the
               draft + baseline); `:can-leave` blocks a dirty navigate-away."
   :path      "/editor/:slug"
   :params    [:map [:slug :string]]
   :tags      #{:requires-auth}
   :on-match  [[:editor/load-article]]
   :can-leave [:editor/can-leave?]})

(rf/reg-route :realworld.article/show
  {:doc    "Article detail + its comments. Both load on entry; the comments
            are a sub-resource keyed by the same slug."
   :path   "/article/:slug"
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
     :keep-previous? true}]})

(rf/reg-route :realworld.profile/show
  {:doc    "A user's profile banner + the articles they AUTHORED (the default
            profile tab). The `?page=` query paginates the authored list."
   :path   "/profile/:username"
   :params [:map [:username :string]]
   :query  [:map [:page {:optional true} :int]]
   :resources
   [{:resource  :realworld/profile
     :params    (fn [route] {:username (get-in route [:params :username])})
     :blocking? true}
    {:resource  :realworld/author-articles
     ;; Default to page 1 to match the view's `(or (:page q) 1)` key (rf2-01jbr9).
     :params    (fn [route] {:username (get-in route [:params :username])
                             :page     (or (get-in route [:query :page]) 1)})
     :blocking? false
     :keep-previous? true}]})

(rf/reg-route :realworld.profile/favorites
  {:doc    "A user's profile banner + the articles they FAVORITED (the second
            official profile tab — `/profile/:username/favorites`). Same banner
            read as `:realworld.profile/show`; the list read is the
            `:realworld/favorited-articles` resource. The `?page=` query
            paginates it. Favoriting / unfavoriting from this tab invalidates
            `[:article slug]`, which this list carries, so it refetches and the
            article drops out on unfavorite (Spec 016 §Mutations)."
   :path   "/profile/:username/favorites"
   :params [:map [:username :string]]
   :query  [:map [:page {:optional true} :int]]
   :resources
   [{:resource  :realworld/profile
     :params    (fn [route] {:username (get-in route [:params :username])})
     :blocking? true}
    {:resource  :realworld/favorited-articles
     ;; Default to page 1 to match the view's `(or (:page q) 1)` key (rf2-01jbr9).
     :params    (fn [route] {:username (get-in route [:params :username])
                             :page     (or (get-in route [:query :page]) 1)})
     :blocking? false
     :keep-previous? true}]})

(rf/reg-route :rf.route/not-found
  {:doc "Fallback when no other route matches." :path "/_404"})

;; ============================================================================
;; AUTH GUARD  (Spec 012 §Redirects and guards)
;; ============================================================================
;;
;; Route-level auth is a plain interceptor. It redirects unauthenticated users
;; away from any `:requires-auth`-tagged route to login, stashing the intended
;; target under `[:auth :return-to]` for post-login bounce-back. Gates all
;; three navigation entry points (programmatic nav, anchor click, URL-bar /
;; popstate) so a protected route is unreachable logged-out by any path.

(defn- resolve-nav-target [[ev-id a _b]]
  (case ev-id
    :rf.route/navigate {:id a :params (or _b {})}
    :rf/url-requested  (let [{:keys [to params url]} a]
                         (cond
                           to  {:id to :params (or params {})}
                           url (when-let [{:keys [route-id params]} (routing/match-url url)]
                                 {:id route-id :params (or params {})})))
    :rf.route/handle-url-change (when-let [{:keys [route-id params]} (routing/match-url a)]
                                  {:id route-id :params (or params {})})
    nil))

;; EP-0022: the guard is a REGISTERED interceptor referenced BY ID
;; (`:realworld-resources.routing/auth-guard`) from the demo frame's
;; `:interceptors` chain in core.cljs — not an inline value. `reg-interceptor`
;; is a top-level load-time registration; core.cljs requires this ns, so the
;; descriptor is registered before `reg-frame` resolves the reference.
(rf/reg-interceptor :realworld-resources.routing/auth-guard
  {:doc "Route-level auth guard (Spec 012 §Redirects and guards): redirect
         unauthenticated users away from `:requires-auth`-tagged routes to
         login, stashing the intended target for post-login bounce-back."}
  {:before (fn auth-guard-before [ctx]
             (if-let [{:keys [id params]} (resolve-nav-target (get-in ctx [:coeffects :event]))]
               (let [route-meta  (rf/handler-meta :route id)
                     needs-auth? (boolean (some #{:requires-auth} (:tags route-meta)))
                     logged-in?  (some? (get-in ctx [:coeffects :db :auth :user]))]
                 (if (and needs-auth? (not logged-in?))
                   (-> ctx
                       (assoc :rf/skip-handler? true)
                       (assoc-in [:effects :db]
                                 (assoc-in (get-in ctx [:coeffects :db])
                                           [:auth :return-to] {:id id :params params}))
                       (assoc-in [:effects :fx]
                                 [[:dispatch [:rf.route/navigate :realworld.auth/login]]]))
                   ctx))
               ctx))})

;; ============================================================================
;; ROUTER WIRING  (base-path-aware, like the :rf.http/managed sibling)
;; ============================================================================

(def ^:dynamic *base-path* "")
(defn set-base-path! [s] (set! *base-path* (or s "")))

(defn- strip-base [s]
  (if (and (seq *base-path*) (clojure.string/starts-with? s *base-path*))
    (let [stripped (subs s (count *base-path*))]
      (if (clojure.string/starts-with? stripped "/") stripped (str "/" stripped)))
    s))

(defn current-url []
  (-> (.. js/window -location -pathname)
      strip-base
      (str (.. js/window -location -search) (.. js/window -location -hash))))

(defn- on-popstate [_]
  (when-let [owner (routing/url-owner-frame-id)]
    (rf/dispatch [:rf.route/handle-url-change (current-url)] {:frame owner})))

(defn install-router! []
  (.removeEventListener js/window "popstate" on-popstate)
  (.addEventListener js/window "popstate" on-popstate)
  (when-let [owner (routing/url-owner-frame-id)]
    (rf/dispatch-sync [:rf.route/handle-url-change (current-url)] {:frame owner})))
