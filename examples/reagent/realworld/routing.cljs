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
            ;; Routing ships in day8/re-frame2-routing.
            ;; Requiring re-frame.routing here triggers its load-time
            ;; hook + reg-sub registrations; without it, the rf/reg-route
            ;; calls below throw :rf.error/routing-artefact-missing.
            ;; Aliased so the popstate handler can resolve the URL owner via
            ;; `routing/url-owner-frame-id` (Spec 012 §popstate drives the
            ;; URL-owner frame) — the EP-0002 owner-targeted dispatch.
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
              `/tag/:tag` PATH route (replacing the prior
              `?tag=` query). `?page=N` paginates within the tag the same way
              the home feed does (`/tag/:tag?page=2`); `:query-defaults` fills
              page 1. Same `:home/load` on-match — it reads the active tag off
              the route params now rather than the query."
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
;; Per Spec 012 §Redirects and guards: route-level auth is an interceptor,
;; not a special routing mechanism. Guards are plain interceptors; they
;; compose, and multiple guards can layer. This one redirects
;; unauthenticated users away from any route tagged `:requires-auth`
;; (`:realworld.user/settings`, `:realworld.editor/new`, `:realworld.editor/edit`) to the login
;; page, stashing the original target under `:return-to` so a post-login
;; handler could bounce the user back.
;;
;; It is wired into the demo frame via `reg-frame :interceptors` in
;; core.cljs (`:interceptors` are "prepended to every event in this
;; frame", Spec 002 §reg-frame). To keep it cheap and correct it short-
;; circuits to the unchanged ctx for everything except a navigation
;; event, and gates EVERY navigation ENTRY POINT so a `:requires-auth`
;; route is unreachable logged-out by ANY access path:
;;
;;   - `:rf.route/navigate`          — programmatic nav (the navbar);
;;     `(second event)` IS the target route id.
;;   - `:rf/url-requested`           — anchor (`rf/route-link`) click;
;;     the request map carries `:to` (the target route id) + `:params`.
;;   - `:rf.route/handle-url-change` — URL-bar entry / reload / popstate;
;;     `(second event)` is a URL string resolved to a route via
;;     `rf/match-url`.
;;
;; The guard MUST gate all three entry points, not just `:rf.route/navigate`:
;; gating navigate alone would FAIL OPEN on the most common access path — a
;; logged-out user who typed `/settings`, reloaded a protected page, or followed
;; an anchor would reach the route (and the on-match drain would then fire a
;; request the real Conduit backend would 401). Gating all three closes that
;; gap — `resolve-nav-target` normalises each event to an `{:id :params}`
;; target so ONE redirect path handles them all.
;;
;; The redirect: the `:before` SKIPS the original handler (`:rf/skip-
;; handler?`) so the protected route's slice never commits and its
;; on-match drain never fires, then dispatches `:rf.route/navigate
;; :realworld.auth/login` itself. This is the same login navigation the
;; programmatic path produced, but it works uniformly for ALL three
;; events — the runtime selects the handler from the ORIGINAL event id
;; before interceptors run, so rewriting `[:coeffects :event]` across
;; event ids (e.g. feeding a route-id to `:rf.route/handle-url-change`'s
;; URL slot) would run the wrong handler. Skip-and-dispatch sidesteps
;; that entirely.
;;
;; Bounce-back (`:return-to`). The headline of an auth guard is returning
;; the user to where they were headed once they sign in. `:rf.route/navigate`
;; opts (the 3rd arg) are NOT persisted by the runtime — the navigate
;; handler reads `:query` / `:fragment` / `:replace?` / `:scroll` /
;; `:bypass-leave-guard?` from opts and drops everything else (Spec 012
;; §Navigation is an event), so an opts-borne `:return-to` would silently
;; evaporate. We therefore stash the original target in `app-db` instead:
;; the `:before` writes it to `[:auth :return-to]` via a `:db` effect
;; (committed before the login dispatch's `:fx` runs, so the redirect's
;; slice merge preserves it). The auth machine's `:store-session` action
;; reads that slot on a successful login and bounces there (falling back
;; to home), then clears it (auth.cljs).

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

;; EP-0022: the guard is a REGISTERED interceptor referenced BY ID
;; (`:realworld.routing/auth-guard`) from the demo frame's `:interceptors`
;; chain in core.cljs — not an inline value. `reg-interceptor` is a top-level
;; load-time registration; core.cljs requires this ns, so the descriptor is
;; registered before `reg-frame` resolves the reference.
(rf/reg-interceptor :realworld.routing/auth-guard
  {:doc "Route-level auth guard (Spec 012 §Redirects and guards): redirect
         unauthenticated users away from `:requires-auth`-tagged routes to
         login, stashing the intended target for post-login bounce-back."}
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

;; Named handler so the listener install is idempotent: repeated
;; `install-router!` (shadow hot reload, or a co-required test host
;; invoking run twice) must not stack duplicate popstate listeners,
;; mirroring the `when-not @react-root` mount guard. We remove-then-add
;; the same Var so the registration is deduped even when the Var is
;; redefined on reload.
;;
;; EP-0002: the URL-change dispatch is targeted at
;; the explicitly-declared URL owner resolved AT CALL TIME via
;; `routing/url-owner-frame-id` (Spec 012 §popstate drives the URL-owner
;; frame) — NOT a frameless `(rf/dispatch …)`, which would raise
;; `:rf.error/no-frame-context`. This example serves from a `/realworld/`
;; sub-path and strips it in `current-url`, so it keeps its own base-path-aware
;; listener rather than the framework's `rf/install-history-listener!` (which
;; reads the unstripped browser URL); the owner-targeting is the same contract
;; that listener implements. The owner is the `:url-bound? true` demo frame
;; registered in `core/run`.
(defn- on-popstate [_]
  (when-let [owner (routing/url-owner-frame-id)]
    (rf/dispatch [:rf.route/handle-url-change (current-url)] {:frame owner})))

(defn install-router! []
  (.removeEventListener js/window "popstate" on-popstate)
  (.addEventListener js/window "popstate" on-popstate)
  (when-let [owner (routing/url-owner-frame-id)]
    (rf/dispatch-sync [:rf.route/handle-url-change (current-url)] {:frame owner})))

