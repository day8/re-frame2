(ns realworld.routing
  "Routes for the RealWorld (Conduit) example.

   This file owns the route table, an app-specific auth guard, and a local
   route-link helper view. It uses the current runtime routing surface
   directly:

   - `reg-route`
   - `:rf.route/navigate`
   - `:rf.route/handle-url-change`
   - `:rf.route/continue` / `:rf.route/cancel`
   - `:rf.route/id` / `:rf.route/params` / `:rf.route/query`
   - `:rf/url-requested`"
  (:require [clojure.string]
            [re-frame.core :as rf]
            ;; rf2-k682: routing ships in day8/re-frame-2-routing.
            ;; Requiring re-frame.routing here triggers its load-time
            ;; hook + reg-sub registrations; without it, the rf/reg-route
            ;; calls below throw :rf.error/routing-artefact-missing.
            [re-frame.routing])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

;; ============================================================================
;; ROUTES
;; ============================================================================

(rf/reg-route :route/home
  {:doc      "The landing page: global feed, your feed, and optional tag filter."
   :path     "/"
   :query    [:map
              [:tag {:optional true} :string]
              [:feed {:optional true} :string]]
   :on-match [[:home/load]]
   :scroll   :top})

(rf/reg-route :route/login
  {:doc  "Login page."
   :path "/login"})

(rf/reg-route :route/register
  {:doc  "Register page."
   :path "/register"})

(rf/reg-route :route/settings
  {:doc  "User settings page (requires auth)."
   :path "/settings"
   :on-match [[:settings/load]]
   :tags #{:requires-auth}})

(rf/reg-route :route/editor
  {:doc       "Create a new article (requires auth)."
   :path      "/editor"
   :tags      #{:requires-auth}
   :on-match  [[:editor/initialise]]
   :can-leave [:editor/can-leave?]})

(rf/reg-route :route/editor.edit
  {:doc       "Edit an existing article (requires auth)."
   :path      "/editor/:slug"
   :params    [:map [:slug :string]]
   :tags      #{:requires-auth}
   :can-leave [:editor/can-leave?]
   :on-match  [[:editor/load-article]]})

(rf/reg-route :route/article
  {:doc      "Article detail page. The #comments fragment scrolls to comments."
   :path     "/article/:slug"
   :params   [:map [:slug :string]]
   :on-match [[:article/load]
              [:comments/load]]
   :scroll   :top})

(rf/reg-route :route/profile
  {:doc      "A user's profile — articles they authored."
   :path     "/profile/:username"
   :params   [:map [:username :string]]
   :on-match [[:profile/load]
              [:profile.articles/load]]})

(rf/reg-route :route/profile.favorites
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

(def auth-guard
  {:id     :route/auth-guard
   :before (fn auth-guard-before [ctx]
             (let [event       (get-in ctx [:coeffects :event])
                   target      (second event)
                   route-meta  (rf/handler-meta :route target)
                   needs-auth? (boolean (some #{:requires-auth} (:tags route-meta)))
                   logged-in?  (some? (get-in ctx [:coeffects :db :auth :user]))]
               (if (and needs-auth? (not logged-in?))
                 (assoc-in ctx [:coeffects :event]
                           [:rf.route/navigate :route/login {} {:return-to target}])
                 ctx)))})

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :rf/pending-navigation
  (fn [db _] (:rf/pending-navigation db)))

;; ============================================================================
;; LINK VIEW
;; ============================================================================

(reg-view ^{:doc "Anchor helper for the RealWorld example. Uses the runtime's
                   `:rf/url-requested` event so modifier-key and browser-navigation
                   semantics stay on the framework path."}
          route-link [{:keys [to params query fragment class]} & children]
  (let [base-url (rf/route-url to (or params {}) (or query {}))
        url      (str base-url (when fragment (str "#" fragment)))]
    [:a {:href     url
         :class    class
         :on-click (fn [e]
                     (when (and (zero? (.-button e))
                                (not (.-metaKey e))
                                (not (.-ctrlKey e))
                                (not (.-shiftKey e)))
                       (.preventDefault e)
                       (dispatch [:rf/url-requested
                                  {:url url
                                   :to to
                                   :params params
                                   :query query
                                   :fragment fragment}])))}
     (into [:<>] children)]))

;; ============================================================================
;; ROUTER WIRING
;; ============================================================================

;; The example may be served from a sub-path (e.g. /realworld/) by the
;; Playwright orchestrator; in production it would be mounted at /.
;; `*base-path*` lets the host strip a prefix before the route matcher
;; runs. Set via `set-base-path!` in the run fn.
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

