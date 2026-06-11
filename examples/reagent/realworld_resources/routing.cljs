(ns realworld-resources.routing
  "Routes for the RealWorld-on-resources example.

   The headline of this variant: route entry CAUSES the page's server-state to
   load, declaratively, via `:resources` route metadata (Spec 016 §Route
   integration). On entry the runtime marks each listed resource active with
   owner `[:route route-id nav-token]` and ensures it with cause
   `[:route-entry route-id nav-token]`; on leave it releases the owner by token
   and suppresses any stale reply by generation. The views never fetch — they
   read the runtime cache passively.

   `:blocking?` keeps the route transition pending until the resource settles
   (and is the SSR wait point); a non-blocking resource fetches in the
   background. `:keep-previous?` keeps the prior list visible while a new
   filter/page first-loads. `:when` makes a resource conditional without
   sentinel nil params.

   The SESSION-scoped personalised feed is NOT a route resource: a route
   `:scope` / `:when` resolver receives only the route + a routing-supplied
   entry ctx (an empty map here), NOT app-db, so it can't read the
   authenticated user's scope. The idiomatic answer (Spec 016 §Route
   integration: \"an app can use resources entirely from events\") is to ensure
   the feed from the home route's `:on-match` event, which DOES see `:db` — see
   `:home/on-match` below. The public reads (list, tags) stay declarative on
   the route; the session read is event-driven under a releaseable lease.

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
           the personalised feed. The `:tag` query param filters the list — it
           flows into the resource's params, so a tag-filtered list is a
           distinct cache entry."
   :path  "/"
   :query [:map
           [:tag  {:optional true} :string]
           [:feed {:optional true} :string]]
   :scroll   :top
   ;; The session-scoped feed is ensured from `:on-match` (it needs `:db`); the
   ;; public reads are declarative route resources.
   :on-match [[:home/on-match]]
   :resources
   [{:resource  :realworld/articles
     ;; Route query → resource params: the `?tag=` flows into identity.
     :params    (fn [route] {:tag (get-in route [:query :tag])})
     :blocking? true
     :keep-previous? true}
    {:resource  :realworld/tags
     :params    (fn [_route] {})
     :blocking? false}]})

(rf/reg-route :realworld.auth/login
  {:doc "Login page." :path "/login"})

(rf/reg-route :realworld.auth/register
  {:doc "Register page." :path "/register"})

(rf/reg-route :realworld.user/settings
  {:doc  "User settings page (requires auth)."
   :path "/settings"
   :tags #{:requires-auth}})

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
  {:doc    "A user's profile banner + the articles they authored."
   :path   "/profile/:username"
   :params [:map [:username :string]]
   :resources
   [{:resource  :realworld/profile
     :params    (fn [route] {:username (get-in route [:params :username])})
     :blocking? true}
    {:resource  :realworld/author-articles
     :params    (fn [route] {:username (get-in route [:params :username])})
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
