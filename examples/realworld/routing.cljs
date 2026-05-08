(ns example.realworld.routing
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
  (:require [re-frame-2.core :as rf]
            [example.realworld.schema])
  (:require-macros [re-frame-2.views-macros :refer [reg-view with-frame]]))

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

(def route-link
  (reg-view :rf/route-link
    {:doc "Anchor helper for the RealWorld example. Uses the runtime's
           `:rf/url-requested` event so modifier-key and browser-navigation
           semantics stay on the framework path."}
    (fn render-route-link [{:keys [to params query fragment class]} & children]
      (let [d        (rf/dispatcher)
            base-url (rf/route-url to (or params {}) (or query {}))
            url      (str base-url (when fragment (str "#" fragment)))]
        [:a {:href     url
             :class    class
             :on-click (fn [e]
                         (when (and (zero? (.-button e))
                                    (not (.-metaKey e))
                                    (not (.-ctrlKey e))
                                    (not (.-shiftKey e)))
                           (.preventDefault e)
                           (d [:rf/url-requested
                               {:url url
                                :to to
                                :params params
                                :query query
                                :fragment fragment}])))}
         (into [:<>] children)]))))

;; ============================================================================
;; ROUTER WIRING
;; ============================================================================

(defn current-url []
  (str (.. js/window -location -pathname)
       (.. js/window -location -search)
       (.. js/window -location -hash)))

(defn install-router! []
  (.addEventListener js/window "popstate"
    (fn [_]
      (rf/dispatch [:rf.route/handle-url-change (current-url)])))
  (rf/dispatch-sync [:rf.route/handle-url-change (current-url)]))

;; ============================================================================
;; HEADLESS TESTS
;; ============================================================================

(defn routing-tests []
  (with-frame [f (rf/make-frame {:on-create [:app/initialise]})]
    (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "hello"}] {:frame f})
    (assert (= :route/article (rf/compute-sub [:rf.route/id] (rf/get-frame-db f))))
    (assert (= "hello" (:slug (rf/compute-sub [:rf.route/params] (rf/get-frame-db f)))))

    (rf/dispatch-sync [:rf.route/handle-url-change "/profile/eve"] {:frame f})
    (assert (= :route/profile (rf/compute-sub [:rf.route/id] (rf/get-frame-db f))))

    (rf/dispatch-sync [:rf.route/handle-url-change "/settings"] {:frame f})
    (assert (= :route/settings (rf/compute-sub [:rf.route/id] (rf/get-frame-db f))))

    (rf/dispatch-sync [:rf.route/handle-url-change "/?tag=clojure"] {:frame f})
    (assert (= "clojure" (:tag (rf/compute-sub [:rf.route/query] (rf/get-frame-db f)))))

    (rf/dispatch-sync [:rf.route/handle-url-change "/garbage/path"] {:frame f})
    (assert (= :rf.route/not-found (rf/compute-sub [:rf.route/id] (rf/get-frame-db f))))))
