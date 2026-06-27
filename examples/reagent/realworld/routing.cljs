(ns realworld.routing
  "Routes for the RealWorld (Conduit) example.

   This file owns the route table and an app-specific auth guard. The
   anchor view is the framework-shipped `rf/route-link` (registered at
   `:route/link`); call sites pass `:class` / `:data-testid` through to
   the underlying `<a>`. The example uses the current runtime routing
   surface directly:

   - `reg-route`
   - `rf/route-link` (registered view at `:route/link`)
   - `:rf.route/navigate`
   - `:rf.route/handle-url-change`
   - `:rf.route/continue` / `:rf.route/cancel`
   - `:rf.route/id` / `:rf.route/params` / `:rf.route/query`
   - `:rf/url-requested`"
  (:require [clojure.string]
            [re-frame.core :as rf]
            ;; Routing ships in the re-frame2-routing artefact. Requiring
            ;; the ns registers its hooks + subs so the `rf/reg-route` calls
            ;; below resolve. Aliased so the popstate handler can resolve the
            ;; URL owner via `routing/url-owner-frame-id`. See the routing
            ;; guide: ../../../docs/routing/index.md
            [re-frame.routing :as routing]))

;; ============================================================================
;; ROUTES
;; ============================================================================

(rf/reg-route :realworld/home
  {:doc      "The landing page: global feed and (signed-in) your feed.

              ROUTE-SHAPE CONFORMANCE. The official RealWorld
              browser/E2E contract uses `/?feed=following` for the
              authenticated feed (NOT `?feed=your`) and a PATH-param tag route
              `/tag/:tag` (NOT `?tag=`). The tag filter therefore lives on its
              own `:realworld/home-tag` route below; this home route carries
              only `?feed=` (the following toggle) and `?page=`.

              `?page=N` is the 1-indexed pagination page for the active feed
              (official RealWorld limit/offset pagination). It rides the route
              query so back/forward and bookmarking restore the page; `:int`
              coercion turns the URL's `\"2\"` into `2`, and `:query-defaults`
              fills page 1 when the key is absent."
   :query    [:map
              [:feed {:optional true} :string]
              [:page {:optional true} :int]]
   :query-defaults {:page 1}
   :on-match [[:home/load]]
   :scroll   :top} "/")

(rf/reg-route :realworld/home-tag
  {:doc      "The tag-filtered article list at the official RealWorld
              `/tag/:tag` PATH route. `?page=N` paginates within the tag the
              same way the home feed does (`/tag/:tag?page=2`);
              `:query-defaults` fills page 1. Same `:home/load` on-match — it
              reads the active tag off the route params."
   :params   [:map [:tag :string]]
   :query    [:map [:page {:optional true} :int]]
   :query-defaults {:page 1}
   :on-match [[:home/load]]
   :scroll   :top} "/tag/:tag")

(rf/reg-route :realworld.auth/login
  {:doc  "Login page."} "/login")

(rf/reg-route :realworld.auth/register
  {:doc  "Register page."} "/register")

(rf/reg-route :realworld.user/settings
  {:doc  "User settings page (requires auth)."
   :on-match [[:settings/load]]
   :tags #{:requires-auth}} "/settings")

(rf/reg-route :realworld.editor/new
  {:doc       "Create a new article (requires auth)."
   :tags      #{:requires-auth}
   :on-match  [[:editor/initialise]]
   :can-leave [:editor/can-leave?]} "/editor")

(rf/reg-route :realworld.editor/edit
  {:doc       "Edit an existing article (requires auth)."
   :params    [:map [:slug :string]]
   :tags      #{:requires-auth}
   :can-leave [:editor/can-leave?]
   :on-match  [[:editor/load-article]]} "/editor/:slug")

(rf/reg-route :realworld.article/show
  {:doc      "Article detail page. The #comments fragment scrolls to comments."
   :params   [:map [:slug :string]]
   :on-match [[:article/load]
              [:comments/load]]
   :scroll   :top} "/article/:slug")

(rf/reg-route :realworld.profile/show
  {:doc      "A user's profile — articles they authored. `?page=N` paginates
              the authored list (official RealWorld limit/offset pagination)."
   :params   [:map [:username :string]]
   :query    [:map [:page {:optional true} :int]]
   :query-defaults {:page 1}
   :on-match [[:profile/load]
              [:profile.articles/load]]} "/profile/:username")

(rf/reg-route :realworld.profile/favorites
  {:doc      "A user's profile — articles they have favorited. `?page=N`
              paginates the favorited list."
   :params   [:map [:username :string]]
   :query    [:map [:page {:optional true} :int]]
   :query-defaults {:page 1}
   :on-match [[:profile/load]
              [:profile.favorites/load]]} "/profile/:username/favorites")

(rf/reg-route :rf.route/not-found
  {:doc  "Fallback when no other route matches."} "/_404")

;; ============================================================================
;; AUTH GUARD
;; ============================================================================
;;
;; Route-level auth is an interceptor, not a special routing mechanism.
;; Guards are plain interceptors, so they compose and layer. This one
;; redirects unauthenticated users away from any route tagged
;; `:requires-auth` (`:realworld.user/settings`, `:realworld.editor/new`,
;; `:realworld.editor/edit`) to the login page, stashing the original target
;; under `:return-to` for a post-login bounce-back. See the routing guide on
;; guards: ../../../docs/routing/concepts.md#blocking-a-navigation
;;
;; It is wired into the demo frame via the `frame-provider` `:interceptors`
;; in core.cljs. It short-circuits to the unchanged ctx for everything
;; except a navigation event, and gates every navigation entry point so a
;; `:requires-auth` route is unreachable logged-out by any access path:
;;
;;   - `:rf.route/navigate`          — programmatic nav (the navbar);
;;     `(second event)` is the target route id.
;;   - `:rf/url-requested`           — anchor (`rf/route-link`) click;
;;     the request map carries `:to` (the target route id) + `:params`.
;;   - `:rf.route/handle-url-change` — URL-bar entry / reload / popstate;
;;     `(second event)` is a URL string resolved via `rf/match-url`.
;;
;; The guard must gate all three, not just `:rf.route/navigate`: gating
;; navigate alone fails open on the most common path — a logged-out user
;; who typed `/settings`, reloaded a protected page, or followed an anchor
;; would reach the route (and its on-match drain would fire a request the
;; real backend would 401). `resolve-nav-target` normalises each event to
;; an `{:id :params}` target so one redirect path handles them all.
;;
;; The redirect: the `:before` skips the original handler
;; (`:rf/skip-handler?`) so the protected route's slice never commits and
;; its on-match drain never fires, then dispatches `:rf.route/navigate
;; :realworld.auth/login`. The runtime selects the handler from the original
;; event id before interceptors run, so rewriting `[:coeffects :event]`
;; across event ids would run the wrong handler — skip-and-dispatch
;; sidesteps that.
;;
;; Bounce-back (`:return-to`): `:rf.route/navigate` opts (the 3rd arg) are
;; not persisted by the runtime — the navigate handler reads `:query` /
;; `:fragment` / `:replace?` / `:scroll` / `:bypass-leave-guard?` and drops
;; the rest — so an opts-borne `:return-to` would evaporate. Instead the
;; `:before` stashes the target at `[:auth :return-to]` via a `:db` effect
;; (committed before the login dispatch's `:fx` runs). The auth machine's
;; `:store-session` action reads that slot on a successful login, bounces
;; there (falling back to home), and clears it (auth.cljs).

(defn- resolve-nav-target
  "Normalise a navigation event into its target `{:id <route-id>
   :params <map>}`, or nil when `event` is not a navigation (so the guard
   short-circuits). One resolver for all three entry points
   so the redirect path below is identical regardless of HOW the user
   reached the route:

     - `:rf.route/navigate`          → `(second event)` is the route id,
       `(nth event 2)` its path params (programmatic nav).
     - `:rf/url-requested`           → anchor click; the request map
       carries `:to` (route id) + `:params`. Falls back to resolving
       `:url` via `rf/match-url` if `:to` is absent.
     - `:rf.route/handle-url-change` → URL-bar / reload / popstate; the
       URL string is resolved to a route via `rf/match-url`."
  [[ev-id a b]]
  (case ev-id
    :rf.route/navigate
    {:id a :params (or b {})}

    :rf/url-requested
    (let [{:keys [to params url]} a]
      (cond
        to  {:id to :params (or params {})}
        url (when-let [{:keys [route-id params]} (routing/match-url url)]
              {:id route-id :params (or params {})})))

    :rf.route/handle-url-change
    (when-let [{:keys [route-id params]} (routing/match-url a)]
      {:id route-id :params (or params {})})

    nil))

;; Register the guard as a named interceptor here, then reference it by id
;; (`:realworld.routing/auth-guard`) from the demo frame's `:interceptors`
;; in core.cljs. `reg-interceptor` runs at load time, and core.cljs requires
;; this ns, so the descriptor is in place before the frame-provider creates
;; the frame and resolves the id.
(rf/reg-interceptor :realworld.routing/auth-guard
  {:doc "Route-level auth guard: redirect unauthenticated users away from
         `:requires-auth`-tagged routes to login, stashing the intended
         target for post-login bounce-back."}
  {:before (fn auth-guard-before [ctx]
             (if-let [{:keys [id params]} (resolve-nav-target
                                            (get-in ctx [:coeffects :event]))]
               (let [route-meta  (rf/handler-meta :route id)
                     needs-auth? (boolean (some #{:requires-auth} (:tags route-meta)))
                     logged-in?  (some? (get-in ctx [:coeffects :db :auth :user]))]
                 (if (and needs-auth? (not logged-in?))
                   ;; Skip the original handler (so the protected slice +
                   ;; on-match never commit), stash the intended target for
                   ;; post-login bounce-back, and dispatch the login
                   ;; navigation — the SAME redirect for every entry point.
                   (-> ctx
                       (assoc :rf/skip-handler? true)
                       (assoc-in [:effects :db]
                                 (assoc-in (get-in ctx [:coeffects :db])
                                           [:auth :return-to]
                                           {:id id :params params}))
                       (assoc-in [:effects :fx]
                                 [[:dispatch [:rf.route/navigate :realworld.auth/login]]]))
                   ctx))
               ctx))})

;; ============================================================================
;; ROUTER WIRING
;; ============================================================================

;; The example may be served from a sub-path (e.g. /realworld/) by a
;; host that stages many demos side by side; in production it would be
;; mounted at /. `*base-path*` lets the host strip a prefix before the
;; route matcher runs. Set via `set-base-path!` in the run fn.
(def ^:dynamic *base-path* "")

(defn set-base-path! [s]
  (set! *base-path* (or s "")))

(defn- strip-base [s]
  (if (and (seq *base-path*)
           (clojure.string/starts-with? s *base-path*))
    (let [stripped (subs s (count *base-path*))]
      (if (clojure.string/starts-with? stripped "/") stripped (str "/" stripped)))
    s))

(defn current-url []
  (-> (.. js/window -location -pathname)
      strip-base
      (str (.. js/window -location -search)
           (.. js/window -location -hash))))

;; A named handler so the listener install is idempotent: repeated
;; `install-router!` (hot reload, or a co-required test host calling run
;; twice) remove-then-add the same Var, so duplicate popstate listeners
;; never stack — the same idea as the `when-not @react-root` mount guard.
;;
;; The URL-change dispatch targets the URL-owner frame, resolved at call
;; time via `routing/url-owner-frame-id`. The owner is the `:url-bound? true`
;; demo frame the frame-provider creates in core.cljs. Targeting it
;; explicitly matters: a bare `(rf/dispatch …)` with no frame would raise
;; `:rf.error/no-frame-context`. This example uses its own base-path-aware
;; listener (rather than the framework's `rf/install-history-listener!`,
;; which reads the unstripped browser URL) because it serves from a
;; `/realworld/` sub-path; the owner-targeting contract is the same either
;; way.
(defn- on-popstate [_]
  (when-let [owner (routing/url-owner-frame-id)]
    (rf/dispatch [:rf.route/handle-url-change (current-url)] {:frame owner})))

(defn install-router! []
  (.removeEventListener js/window "popstate" on-popstate)
  (.addEventListener js/window "popstate" on-popstate)
  (when-let [owner (routing/url-owner-frame-id)]
    (rf/dispatch-sync [:rf.route/handle-url-change (current-url)] {:frame owner})))

