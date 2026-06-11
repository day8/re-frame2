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

(rf/reg-route :realworld/home
  {:doc   "Home: the global article list + popular tags, plus (when signed in)
           the personalised feed. The `:tag` query param filters the list and
           `:page` paginates it — BOTH flow into the resource's params, so a
           tag-filtered list and each page are distinct cache entries.
           `:keep-previous?` keeps the prior page/filter visible while the next
           first-loads (no flicker); back to a previously-loaded page is a
           cache-hit. The session feed is the third route resource, scoped by
           the named `{:from-db :realworld/session}` resolver (EP-0016 D3) —
           logged out it resolves nil and is not planned."
   :path  "/"
   :query [:map
           [:tag  {:optional true} :string]
           [:feed {:optional true} :string]
           [:page {:optional true} :int]]
   :scroll   :top
   :resources
   [{:resource  :realworld/articles
     ;; Route query → resource params: the `?tag=` AND `?page=` flow into
     ;; identity. Every server-visible list option participates in the cache
     ;; key (Spec 016 §Paginated and previous data).
     :params    (fn [route] {:tag  (get-in route [:query :tag])
                             :page (get-in route [:query :page])})
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
     :params    (fn [route] {:page (get-in route [:query :page])})
     :blocking? false
     :keep-previous? true}]})

(rf/reg-route :realworld.auth/login
  {:doc "Login page." :path "/login"})

(rf/reg-route :realworld.auth/register
  {:doc "Register page." :path "/register"})

(rf/reg-route :realworld.user/settings
  {:doc  "User settings page (requires auth)."
   :path "/settings"
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
     :params    (fn [route] {:username (get-in route [:params :username])
                             :page     (get-in route [:query :page])})
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
     :params    (fn [route] {:username (get-in route [:params :username])
                             :page     (get-in route [:query :page])})
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
                           url (when-let [{:keys [route-id params]} (rf/match-url url)]
                                 {:id route-id :params (or params {})})))
    :rf.route/handle-url-change (when-let [{:keys [route-id params]} (rf/match-url a)]
                                  {:id route-id :params (or params {})})
    nil))

(def auth-guard
  {:id     :realworld-resources.routing/auth-guard
   :before (fn auth-guard-before [ctx]
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
