(ns realworld-http.routing
  "Routes for the RealWorld (Conduit) example.

   Two things live here: the route table (the map of URL → what-to-do) and one
   app-specific auth guard that keeps logged-out visitors out of the private
   pages. Links are the framework's own `rf/route-link` (registered at
   `:route/link`); call sites just pass `:class` / `:data-testid` straight
   through to the underlying `<a>`. Beyond that, the example leans on the
   framework's routing surface as-is:

   - `reg-route`
   - `rf/route-link` (the registered view at `:route/link`)
   - `:rf.route/navigate`
   - `:rf.route/handle-url-change`
   - `:rf.route/continue` / `:rf.route/cancel`
   - `:rf.route/id` / `:rf.route/params` / `:rf.route/query`
   - `:rf/url-requested`"
  (:require [clojure.string]
            [re-frame.core :as rf]
            ;; Routing lives in its own artefact. Requiring it registers the
            ;; hooks and subs that make the `rf/reg-route` calls below resolve.
            ;; This one IS aliased — the popstate handler needs to call
            ;; `routing/url-owner-frame-id` directly. See the routing guide:
            ;; ../../../docs/routing/index.md
            [re-frame.routing :as routing]))

;; ============================================================================
;; ROUTES
;; ============================================================================

(rf/reg-route :realworld/home
  {:doc      "The landing page — the global feed, plus your feed once you're
              signed in.

              A note on URL shapes, because we follow the official contract to
              the letter: RealWorld uses `/?feed=following` for the
              authenticated feed (not `?feed=your`) and a PATH-param tag route,
              `/tag/:tag` (not `?tag=`). That's why the tag filter gets its own
              `:realworld/home-tag` route below, and this home route carries
              only `?feed=` (the following toggle) and `?page=`.

              `?page=N` is the 1-indexed page for the active feed. It rides the
              query so Back/Forward and bookmarks restore your spot; `:int`
              coercion turns the URL's `\"2\"` into a real `2`, and
              `:query-defaults` fills in page 1 when the key's missing."
   :query    [:map
              [:feed {:optional true} :string]
              [:page {:optional true} :int]]
   :query-defaults {:page 1}
   :on-match [[:home/load]]
   :scroll   :top} "/")

(rf/reg-route :realworld/home-tag
  {:doc      "The tag-filtered list, at the official `/tag/:tag` PATH route.
              `?page=N` paginates within the tag exactly as the home feed does
              (`/tag/:tag?page=2`); `:query-defaults` fills page 1. It shares
              `:home/load` with the home route — that handler just reads the
              active tag straight off the route params."
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
;; Route-level auth isn't a special routing feature — it's just an interceptor.
;; That's the nice part: because guards are ordinary interceptors, they compose
;; and stack like anything else. This one watches for navigation toward any
;; route tagged `:requires-auth` (`:realworld.user/settings`,
;; `:realworld.editor/new`, `:realworld.editor/edit`), and if you're not signed
;; in it sends you to login instead — stashing where you were headed under
;; `:return-to` so you can be bounced back afterward. See the routing guide on
;; guards: ../../../docs/routing/concepts.md#blocking-a-navigation
;;
;; It's wired into the demo frame via `frame-provider`'s `:interceptors` in
;; core.cljs. For anything that isn't a navigation, it hands the ctx straight
;; back, untouched. For navigations, it covers EVERY way into a route, so a
;; protected page can't be reached logged-out by any door:
;;
;;   - `:rf.route/navigate`          — programmatic nav (the navbar);
;;     `(second event)` is the target route id.
;;   - `:rf/url-requested`           — an anchor (`rf/route-link`) click;
;;     the request map carries `:to` (the target route id) + `:params`.
;;   - `:rf.route/handle-url-change` — typing in the URL bar, a reload, a
;;     popstate; `(second event)` is a URL string, resolved via `rf/match-url`.
;;
;; Guarding only `:rf.route/navigate` would be a classic fail-open: a
;; logged-out user who typed `/settings`, reloaded a protected page, or clicked
;; a link would sail right in — and its on-match drain would fire off a request
;; the real backend just 401s. So `resolve-nav-target` flattens all three
;; events down to one `{:id :params}` target, and a single redirect path
;; handles the lot.
;;
;; How the redirect works: the `:before` sets `:rf/skip-handler?`, so the
;; protected route's own handler never runs — its slice never commits and its
;; on-match drain never fires — and then it dispatches
;; `:rf.route/navigate :realworld.auth/login`. (Why skip-and-dispatch instead
;; of just rewriting the event? The runtime picks the handler from the ORIGINAL
;; event id before interceptors run, so swapping `[:coeffects :event]` to a
;; different id would still run the first handler. Skipping sidesteps the whole
;; problem.)
;;
;; The bounce-back (`:return-to`) takes a little care. You can't smuggle it
;; through `:rf.route/navigate`'s opts (the 3rd arg): the navigate handler only
;; keeps `:query` / `:fragment` / `:replace?` / `:scroll` /
;; `:bypass-leave-guard?` and drops everything else, so an opts-borne
;; `:return-to` would just evaporate. Instead the `:before` writes the target
;; to `[:auth :return-to]` with a `:db` effect (committed before the login
;; dispatch's `:fx` runs). Later, on a successful login, the auth machine's
;; `:store-session` action reads that slot, sends you there (or home if it's
;; empty), and clears it (auth.cljs).

(defn- resolve-nav-target
  "Boil any navigation event down to one shape — `{:id <route-id> :params
   <map>}` — or nil when the event isn't a navigation at all (the guard's cue
   to step aside). Three different events come in; one tidy target goes out, so
   the redirect below doesn't care HOW the user got here:

     - `:rf.route/navigate`          → `(second event)` is the route id,
       `(nth event 2)` the path params (programmatic nav).
     - `:rf/url-requested`           → an anchor click; the request map carries
       `:to` (route id) + `:params`, and falls back to resolving `:url` via
       `rf/match-url` when `:to` isn't there.
     - `:rf.route/handle-url-change` → URL bar / reload / popstate; the URL
       string is matched to a route via `rf/match-url`."
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

;; Register the guard as a named interceptor here, then refer to it by id
;; (`:realworld.routing/auth-guard`) from the demo frame's `:interceptors` in
;; core.cljs. `reg-interceptor` runs at load time and core.cljs requires this
;; ns, so the descriptor is sitting ready before the frame-provider ever tries
;; to resolve the id.
(rf/reg-interceptor :realworld.routing/auth-guard
  {:doc "The route-level auth guard: steer logged-out users away from
         `:requires-auth` routes to login, and remember where they were going
         so we can send them back afterward."}
  {:before (fn auth-guard-before [ctx]
             (if-let [{:keys [id params]} (resolve-nav-target
                                            (get-in ctx [:coeffects :event]))]
               (let [route-meta  (rf/handler-meta :route id)
                     needs-auth? (boolean (some #{:requires-auth} (:tags route-meta)))
                     logged-in?  (some? (get-in ctx [:coeffects :db :auth :user]))]
                 (if (and needs-auth? (not logged-in?))
                   ;; Three moves: skip the original handler (so the protected
                   ;; slice and its on-match never commit), remember where they
                   ;; were headed for the bounce-back, and dispatch the login
                   ;; navigation. The SAME redirect, whichever door they came
                   ;; through.
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

;; This example might be served from a sub-path — a host staging lots of demos
;; side by side could mount it at /realworld/ — even though on its own it'd live
;; at /. `*base-path*` is the little lever that strips that prefix off before
;; the route matcher ever sees the URL. Set it via `set-base-path!` in `run`.
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

;; A NAMED handler, on purpose, so installing the listener is idempotent. Call
;; `install-router!` twice — a hot reload, or a co-required test host running
;; `run` again — and because it's the same Var, the remove-then-add is a no-op
;; and duplicate popstate listeners never pile up. Same trick as the
;; `when-not @react-root` mount guard.
;;
;; The URL-change dispatch is aimed at the frame that OWNS the URL, looked up
;; at call time via `routing/url-owner-frame-id` — the `:url-bound? true` demo
;; frame from core.cljs. Aiming matters: a bare `(rf/dispatch …)` with no frame
;; would raise `:rf.error/no-frame-context`. (And the reason this rolls its own
;; listener instead of the framework's `rf/install-history-listener!` is the
;; `/realworld/` sub-path — the framework's version reads the unstripped
;; browser URL. The owner-targeting contract is identical either way.)
(defn- on-popstate [_]
  (when-let [owner (routing/url-owner-frame-id)]
    (rf/dispatch [:rf.route/handle-url-change (current-url)] {:frame owner})))

(defn install-router! []
  (.removeEventListener js/window "popstate" on-popstate)
  (.addEventListener js/window "popstate" on-popstate)
  (when-let [owner (routing/url-owner-frame-id)]
    (rf/dispatch-sync [:rf.route/handle-url-change (current-url)] {:frame owner})))

