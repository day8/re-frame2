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
            [re-frame.routing]))

;; ============================================================================
;; ROUTES
;; ============================================================================

(rf/reg-route :realworld/home
  {:doc      "The landing page: global feed, your feed, and optional tag filter."
   :path     "/"
   :query    [:map
              [:tag {:optional true} :string]
              [:feed {:optional true} :string]]
   :on-match [[:home/load]]
   :scroll   :top})

(rf/reg-route :realworld.auth/login
  {:doc  "Login page."
   :path "/login"})

(rf/reg-route :realworld.auth/register
  {:doc  "Register page."
   :path "/register"})

(rf/reg-route :realworld.user/settings
  {:doc  "User settings page (requires auth)."
   :path "/settings"
   :on-match [[:settings/load]]
   :tags #{:requires-auth}})

(rf/reg-route :realworld.editor/new
  {:doc       "Create a new article (requires auth)."
   :path      "/editor"
   :tags      #{:requires-auth}
   :on-match  [[:editor/initialise]]
   :can-leave [:editor/can-leave?]})

(rf/reg-route :realworld.editor/edit
  {:doc       "Edit an existing article (requires auth)."
   :path      "/editor/:slug"
   :params    [:map [:slug :string]]
   :tags      #{:requires-auth}
   :can-leave [:editor/can-leave?]
   :on-match  [[:editor/load-article]]})

(rf/reg-route :realworld.article/show
  {:doc      "Article detail page. The #comments fragment scrolls to comments."
   :path     "/article/:slug"
   :params   [:map [:slug :string]]
   :on-match [[:article/load]
              [:comments/load]]
   :scroll   :top})

(rf/reg-route :realworld.profile/show
  {:doc      "A user's profile — articles they authored."
   :path     "/profile/:username"
   :params   [:map [:username :string]]
   :on-match [[:profile/load]
              [:profile.articles/load]]})

(rf/reg-route :realworld.profile/favorites
  {:doc      "A user's profile — articles they have favorited."
   :path     "/profile/:username/favorites"
   :params   [:map [:username :string]]
   :on-match [[:profile/load]
              [:profile.favorites/load]]})

(rf/reg-route :rf.route/not-found
  {:doc  "Fallback when no other route matches."
   :path "/_404"})

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
;; circuits to the unchanged ctx for everything except a programmatic
;; `:rf.route/navigate` — the one event whose `(second event)` is a route
;; id whose tags we want to gate on. (Anchor clicks land here too: the
;; framework `:rf/url-requested` handler resolves the URL to a route and
;; the on-match drain runs; for THIS sketch we gate the programmatic
;; navigation surface the navbar uses, which is the path the headless
;; tests exercise.)
;;
;; Bounce-back (`:return-to`). The headline of an auth guard is returning
;; the user to where they were headed once they sign in. `:rf.route/navigate`
;; opts (the 3rd arg) are NOT persisted by the runtime — the navigate
;; handler reads `:query` / `:fragment` / `:replace?` / `:scroll` /
;; `:bypass-leave-guard?` from opts and drops everything else (Spec 012
;; §Navigation is an event), so an opts-borne `:return-to` would silently
;; evaporate. We therefore stash the original target in `app-db` instead:
;; the `:before` records the intended target on the ctx, and an `:after`
;; folds it into the login navigation's committed `:db` at
;; `[:auth :return-to]`. The auth machine's `:store-session` action reads
;; that slot on a successful login and bounces there (falling back to
;; home), then clears it (auth.cljs).

(def ^:private guard-target-key
  "Private top-level ctx slot the `:before` uses to signal the `:after`
   that it redirected, carrying the original {:id :params} target. Lives
   on the ctx (not in `:coeffects`/`:effects`) so it is invisible to the
   handler and to the committed app-db — pure intra-interceptor signalling."
  :realworld.routing/guard-return-to)

(def auth-guard
  {:id     :realworld.routing/auth-guard
   :before (fn auth-guard-before [ctx]
             (let [[ev-id target params] (get-in ctx [:coeffects :event])]
               (if (= :rf.route/navigate ev-id)
                 (let [route-meta  (rf/handler-meta :route target)
                       needs-auth? (boolean (some #{:requires-auth} (:tags route-meta)))
                       logged-in?  (some? (get-in ctx [:coeffects :db :auth :user]))]
                   (if (and needs-auth? (not logged-in?))
                     ;; Rewrite the in-flight event to a login redirect and
                     ;; record the original target so the `:after` can stash
                     ;; it for post-login bounce-back.
                     (-> ctx
                         (assoc-in [:coeffects :event]
                                   [:rf.route/navigate :realworld.auth/login])
                         (assoc guard-target-key {:id target :params (or params {})}))
                     ctx))
                 ctx)))
   :after  (fn auth-guard-after [ctx]
             ;; Only acts when the `:before` redirected (the slot is set)
             ;; AND the login navigation actually committed a `:db` effect
             ;; (a no-op re-nav or rejected navigation writes none — leave
             ;; it alone). Folds the stashed target into the committed db.
             (if-let [return-to (get ctx guard-target-key)]
               (if (get-in ctx [:effects :db])
                 (assoc-in ctx [:effects :db :auth :return-to] return-to)
                 ctx)
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

(defn install-router! []
  (.addEventListener js/window "popstate"
    (fn [_]
      (rf/dispatch [:rf.route/handle-url-change (current-url)])))
  (rf/dispatch-sync [:rf.route/handle-url-change (current-url)]))

