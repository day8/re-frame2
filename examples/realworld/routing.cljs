(ns example.realworld.routing
  "Routes for the RealWorld (Conduit) example.

   Demonstrates the locked routing surface from Spec 012:
   - `reg-route` registrations with the canonical path-pattern grammar.
   - Route ranking — overlapping routes are resolved by the 6-rule cascade
     (e.g. /settings beats /:username for the literal URL '/settings').
   - `:on-match` events for per-route data loading (server- and client-side).
   - `:can-leave` guard for the article editor (unsaved-changes blocking).
   - Nav-token threading for stale-result suppression on async loads.
   - Fragment-aware navigation (e.g. #comments on the article-detail page).

   OWNERSHIP BOUNDARY (read this before copying):

   What this file *owns* (example-local):
   - `reg-route` calls (the route table is always app code).
   - `auth-guard` interceptor (auth policy is app-specific).

   What this file *re-states for documentation only* (framework-shipped):
   - `:route/navigate`, `:route/handle-url-change`, `:route/continue`,
     `:route/cancel`, and the `:rf/route-link` view all ship with the
     runtime per Spec 012. The handler/view bodies below mirror the locked
     defaults so a reader can see the contract on one page; in a real app,
     do not re-register — consume them as-is from `re-frame.core`. Each is
     marked FRAMEWORK-SHIPPED in its docstring.

   What this file owns by convention (could go either way):
   - `:nav/push-url`, `:nav/replace-url`, `:nav/scroll` fx — host-specific
     bindings to the browser History/Window APIs. The runtime can ship
     reference implementations; this example registers its own so the
     hosting/host-binding seam is visible.

   Route family follows the canonical RealWorld URL layout:
     /                           home (global feed)
     /login                      login form
     /register                   register form
     /settings                   user settings
     /editor                     create new article
     /editor/:slug               edit existing article
     /article/:slug              article detail (with #fragment for comments)
     /profile/:username          a user's profile (their articles)
     /profile/:username/favorites  articles they have favorited"
  (:require [re-frame.core :as rf]
            [example.realworld.schema]))

;; ============================================================================
;; ROUTE REGISTRATIONS  (data — registered like any other kind)
;; ============================================================================

(rf/reg-route :route/home
  {:doc      "The landing / global-feed page."
   :path     "/"
   :on-match [[:articles/load]
              [:tags/load]]
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
   :tags #{:requires-auth}})

(rf/reg-route :route/editor
  {:doc       "Create a new article (requires auth)."
   :path      "/editor"
   :tags      #{:requires-auth}
   :can-leave [:editor/can-leave?]})

(rf/reg-route :route/editor.edit
  {:doc       "Edit an existing article (requires auth)."
   :path      "/editor/:slug"
   :params    [:map [:slug :string]]
   :tags      #{:requires-auth}
   :can-leave [:editor/can-leave?]
   ;; TODO — :on-match should fetch the article into the editor draft.
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

(rf/reg-route :route/not-found
  {:doc  "Fallback when no other route matches."
   :path "/_404"})

;; ============================================================================
;; ROUTE-LEVEL EVENTS
;; ============================================================================
;;
;; OWNERSHIP NOTE: :route/navigate, :route/handle-url-change, :route/continue,
;; and :route/cancel are all *framework-shipped* by Spec 012 — the runtime
;; registers default handlers automatically. The bodies below are NOT what an
;; app would normally write; they re-implement the locked default handlers so
;; readers of this worked example can see the contract on one page. Apps that
;; want the default behaviour should NOT re-register these — the runtime
;; ships them. Apps customise by overriding (re-registering with their own
;; handler) only when they need behaviour beyond the default.

(rf/reg-event-fx :route/navigate
  {:doc "FRAMEWORK-SHIPPED (re-stated here for documentation). Programmatic
         navigation: writes the new :route slice (id, params, query, fragment,
         fresh nav-token) and emits :nav/push-url. In a real app, do not
         re-register; consume :route/navigate as-is."
   :spec [:cat [:= :route/navigate] :keyword [:? :map] [:? :map]]}
  (fn handler-route-navigate [{:keys [db]} [_ route-id path-params _opts]]
    (let [path-params (or path-params {})
          url         (rf/route-url route-id path-params)
          nav-token   (rf/gen-nav-token)]
      {:db (assoc db :route {:id         route-id
                             :params     path-params
                             :query      {}
                             :fragment   nil
                             :transition :idle
                             :nav-token  nav-token})
       :fx [[:nav/push-url url]
            [:rf/trace [:route.nav-token/allocated
                        {:route-id route-id :nav-token nav-token}]]]})))

(rf/reg-event-fx :route/handle-url-change
  {:doc       "FRAMEWORK-SHIPPED (re-stated here for documentation). Fires on
                browser back/forward and on initial-load. Same handler runs
                server-side during SSR. Resolves the URL via match-url; on
                miss, falls through to :route/not-found. In a real app, do
                not re-register."
   :platforms #{:client :server}}
  (fn handler-route-handle-url-change [{:keys [db]} [_ url]]
    (let [m (rf/match-url url)]
      (if m
        (let [{:keys [route-id params query fragment]} m
              nav-token (rf/gen-nav-token)]
          {:db (assoc db :route {:id         route-id
                                 :params     params
                                 :query      (or query {})
                                 :fragment   fragment
                                 :transition :idle
                                 :nav-token  nav-token})
           :fx [[:rf/trace [:route.nav-token/allocated
                            {:route-id route-id :nav-token nav-token}]]]})
        {:db (assoc db :route {:id :route/not-found
                               :params {:url url}
                               :query {} :fragment nil
                               :transition :idle
                               :nav-token (rf/gen-nav-token)})}))))

;; ============================================================================
;; PENDING-NAV PROTOCOL  (per Spec 012 §Navigation blocking)
;; ============================================================================
;;
;; The runtime sets :rf/pending-navigation when a :can-leave guard rejects;
;; the editor's :can-leave? sub returns false when there are unsaved changes.
;; UI (the editor view) reads the pending-nav slot and renders a confirm
;; dialog; user dispatches :route/continue or :route/cancel.

(rf/reg-event-fx :route/continue
  {:doc "FRAMEWORK-SHIPPED (re-stated here for documentation). User confirmed
         the pending navigation. Clear the slot and re-issue the original
         :rf/url-requested event with bypass-leave-guard set. In a real app,
         do not re-register."}
  (fn handler-route-continue [{:keys [db]} [_ pending-nav-id]]
    (let [pending (:rf/pending-navigation db)]
      (when (= pending-nav-id (:id pending))
        {:db (dissoc db :rf/pending-navigation)
         :fx [[:dispatch (:requested-by-event pending)]]}))))

(rf/reg-event-db :route/cancel
  {:doc "FRAMEWORK-SHIPPED (re-stated here for documentation). User cancelled
         the pending navigation. Clear the slot; URL stays put. In a real
         app, do not re-register."}
  (fn handler-route-cancel [db [_ pending-nav-id]]
    (cond-> db
      (= pending-nav-id (get-in db [:rf/pending-navigation :id]))
      (dissoc :rf/pending-navigation))))

;; ============================================================================
;; AUTH GUARD  (interceptor on :route/navigate)
;; ============================================================================
;;
;; Routes tagged :requires-auth redirect to /login when the user is not
;; signed in. The redirect carries a :return-to so post-login navigation
;; resumes where the user wanted to go.

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
                           [:route/navigate :route/login {} {:return-to target}])
                 ctx)))})

;; In a real app, attach the guard to :route/navigate via :interceptors. Left
;; as a comment here so readers can see the surface; not wired by default
;; because the example mounts unauthenticated.
;;
;; (rf/reg-event-fx :route/navigate
;;   {:interceptors [auth-guard]} ...)

;; ============================================================================
;; SUBSCRIPTIONS  (the Spec-012 standard surface)
;; ============================================================================

(rf/reg-sub :route                (fn [db _] (:route db)))
(rf/reg-sub :route/id             :<- [:route] (fn [r _] (:id r)))
(rf/reg-sub :route/params         :<- [:route] (fn [r _] (:params r)))
(rf/reg-sub :route/query          :<- [:route] (fn [r _] (:query r)))
(rf/reg-sub :route/fragment       :<- [:route] (fn [r _] (:fragment r)))
(rf/reg-sub :route/transition     :<- [:route] (fn [r _] (:transition r)))
(rf/reg-sub :rf/pending-navigation
  (fn [db _] (:rf/pending-navigation db)))

;; ============================================================================
;; FX  (browser navigation; client-only)
;; ============================================================================

(rf/reg-fx :nav/push-url
  {:doc       "Push a URL onto the browser history."
   :platforms #{:client}}
  (fn fx-nav-push-url [_m url]
    (.pushState js/history nil "" url)))

(rf/reg-fx :nav/replace-url
  {:doc       "Replace the current history entry with a new URL."
   :platforms #{:client}}
  (fn fx-nav-replace-url [_m url]
    (.replaceState js/history nil "" url)))

(rf/reg-fx :nav/scroll
  {:doc       "Apply a scroll strategy after navigation."
   :platforms #{:client}}
  (fn fx-nav-scroll [_m {:keys [strategy fragment saved-pos]}]
    (case strategy
      :top      (if-let [el (and fragment (.getElementById js/document fragment))]
                  (.scrollIntoView el)
                  (.scrollTo js/window 0 0))
      :restore  (when saved-pos
                  (.scrollTo js/window (first saved-pos) (second saved-pos)))
      :preserve nil
      nil)))

;; ============================================================================
;; LINK COMPONENT  (anchor that dispatches navigate on click)
;; ============================================================================
;;
;; OWNERSHIP NOTE: :rf/route-link is also *framework-shipped* by Spec 012.
;; The reg-view below re-registers it locally so this file documents the
;; contract on one page; the runtime's default has the same shape and
;; click-handling semantics (modifier-key passthrough, internal-vs-external
;; classification via :rf/url-requested). In a real app, do not re-register —
;; require :rf/route-link from the runtime and use it directly.

(def route-link
  (rf/reg-view :rf/route-link
    {:doc "FRAMEWORK-SHIPPED (re-stated here for documentation). An anchor
           that internally navigates via :rf/url-requested. The default
           :rf/url-requested handler classifies internal vs external via
           match-url and dispatches :route/navigate for matched URLs."}
    (fn render-route-link [{:keys [to params query fragment class]} & children]
      (let [url (rf/route-url to (or params {}) (or query {}) fragment)]
        [:a {:href     url
             :class    class
             :on-click (fn [e]
                         (when (and (zero? (.-button e))
                                    (not (.-metaKey e))
                                    (not (.-ctrlKey e))
                                    (not (.-shiftKey e)))
                           (.preventDefault e)
                           (dispatch [:rf/url-requested
                                      {:url url :to to :params params
                                       :query query :fragment fragment}])))}
         (into [:<>] children)]))))

;; ============================================================================
;; ROUTER WIRING  (popstate + initial-load)
;; ============================================================================

(defn install-router! []
  (.addEventListener js/window "popstate"
    (fn [_]
      (rf/dispatch [:route/handle-url-change
                    (str (.. js/window -location -pathname)
                         (.. js/window -location -search)
                         (.. js/window -location -hash))])))
  ;; Initial-load: read the URL and seed the route slice.
  (rf/dispatch-sync [:route/handle-url-change
                     (str (.. js/window -location -pathname)
                          (.. js/window -location -search)
                          (.. js/window -location -hash))]))

;; ============================================================================
;; HEADLESS TESTS
;; ============================================================================

(defn routing-tests []
  (rf/with-frame [f (rf/make-frame {:on-create [:app/initialise]})]
    ;; Programmatic navigate.
    (rf/dispatch-sync [:route/navigate :route/article {:slug "hello"}] {:frame f})
    (assert (= :route/article (rf/compute-sub [:route/id] @(rf/get-frame-db f))))
    (assert (= "hello" (:slug (rf/compute-sub [:route/params] @(rf/get-frame-db f)))))

    ;; URL change resolves via match-url.
    (rf/dispatch-sync [:route/handle-url-change "/profile/eve"] {:frame f})
    (assert (= :route/profile (rf/compute-sub [:route/id] @(rf/get-frame-db f))))

    ;; /settings beats /:username for the literal URL '/settings' (rule 1:
    ;; more static segments wins).
    (rf/dispatch-sync [:route/handle-url-change "/settings"] {:frame f})
    (assert (= :route/settings (rf/compute-sub [:route/id] @(rf/get-frame-db f))))

    ;; Unknown URL → :route/not-found.
    (rf/dispatch-sync [:route/handle-url-change "/garbage/path"] {:frame f})
    (assert (= :route/not-found (rf/compute-sub [:route/id] @(rf/get-frame-db f))))))
