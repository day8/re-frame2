(ns routing.core
  "Worked example for [Construction Prompt CP-7](../../Construction-Prompts.md)
   and [EP 012 Routing](../../012-Routing.md). A small three-page app:
   home, articles list, article detail. Demonstrates URL ↔ frame state,
   navigation as event, route as sub, and route-aware root-view dispatch.

   Per [000-Vision §Working design implications](../../docs/specification/000-Vision.md#working-design-implications): routing is *state plus events*, not
   a separate subsystem. The URL is a derivable view of `app-db`; navigation
   is an event. The same `:route/handle-url-change` handler runs server- and
   client-side for SSR.

   This is a deliberately minimal subset of the Spec 012 routing surface,
   intended as a teaching sketch — see examples/realworld/routing.cljs for
   the full surface (`:on-match`, `:can-leave`, route ranking, nav-token
   stale-result suppression, fragment scroll strategies). The events,
   subscriptions, and link-component name used here match the runtime
   contract so a reader sees the standard names from the start:

   - canonical link view `:rf/route-link`     (re-stated locally for the example)
   - clicks dispatch `:rf/url-requested`      (the runtime's classifier event)
   - which the runtime maps to `:route/navigate` for internal URLs
   - canonical :route slice keys              — id, params, query, fragment,
                                                  transition, error, nav-token
   - `:route/handle-url-change` on popstate / initial-load
   - `:route`, `:route/id`, `:route/params`   — route subs
   - case-on-`:route/id` at the root          — page dispatch
   - `:nav/push-url` fx (client-only)          — browser history push"
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]))

;; ============================================================================
;; ROUTES  (data — registered like any other kind)
;; ============================================================================

(rf/reg-route :route/home
  {:doc  "Landing page."
   :path "/"})

(rf/reg-route :route/articles
  {:doc  "Articles list."
   :path "/articles"})

(rf/reg-route :route/article-detail
  {:doc    "Detail page for one article."
   :path   "/articles/:id"
   :params [:map [:id :string]]})

(rf/reg-route :route/not-found
  {:doc  "Fallback when no other route matches."
   :path "/_404"})

;; ============================================================================
;; SCHEMA  (the route slice in app-db)
;; ============================================================================

;; The :route slice canonical shape per [012 §The :route slice]:
;; {:id :params :query :fragment :transition :error :nav-token}.
(rf/reg-app-schema [:route]
  [:map
   [:id         :keyword]
   [:params     {:default {}} :map]
   [:query      {:default {}} :map]
   [:fragment   {:optional true} [:maybe :string]]
   [:transition {:default :idle} [:enum :idle :loading :error]]
   [:error      {:optional true} [:maybe :map]]
   [:nav-token  {:optional true} [:maybe :string]]])

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event-db :app/initialise
  (fn [_ _]
    {:route    {:id         :route/home
                :params     {}
                :query      {}
                :fragment   nil
                :transition :idle
                :error      nil
                :nav-token  nil}
     :articles [{:id "intro" :title "Intro to re-frame2"  :body "..."}
                {:id "ssr"   :title "Server rendering"   :body "..."}]}))

(rf/reg-event-fx :route/navigate
  {:doc  "MINIMAL re-statement of the framework-shipped :route/navigate
          (Spec 012). The runtime ships a default; this body is mirrored
          here for teaching. Real apps consume :route/navigate as-is.
          The Spec-012 signature accepts an optional opts map (e.g.
          {:return-to ...}, {:replace? true}); this minimal subset only
          uses path-params."
   :spec [:cat [:= :route/navigate] :keyword [:? :map] [:? :map]]}
  (fn handler-route-navigate [{:keys [db]} [_ route-id params _opts]]
    (let [params    (or params {})
          url       (rf/route-url route-id params)
          nav-token (rf/gen-nav-token)]
      {:db (assoc db :route {:id         route-id
                             :params     params
                             :query      {}
                             :fragment   nil
                             :transition :idle
                             :error      nil
                             :nav-token  nav-token})
       :fx [[:nav/push-url url]
            [:rf/trace [:route.nav-token/allocated
                        {:route-id route-id :nav-token nav-token}]]]})))

(rf/reg-event-db :route/handle-url-change
  {:doc       "Triggered on browser back/forward and on initial page load.
               Same handler runs server-side during SSR."
   :platforms #{:client :server}}
  (fn handler-route-handle-url-change [db [_ url]]
    (let [{:keys [route-id params query fragment]} (rf/match-url url)
          nav-token                                (rf/gen-nav-token)]
      (assoc db :route
             (if route-id
               {:id         route-id
                :params     (or params {})
                :query      (or query {})
                :fragment   fragment
                :transition :idle
                :error      nil
                :nav-token  nav-token}
               {:id         :route/not-found
                :params     {:url url}
                :query      {}
                :fragment   nil
                :transition :idle
                :error      nil
                :nav-token  nav-token})))))

;; ============================================================================
;; FX  (client-only navigation push)
;; ============================================================================

(rf/reg-fx :nav/push-url
  {:doc       "Push a URL onto the browser history."
   :platforms #{:client}}
  (fn fx-nav-push-url [_m url]
    (.pushState js/history nil "" url)))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :route          (fn [db _] (:route db)))

(rf/reg-sub :route/id
  :<- [:route]
  (fn [r _] (:id r)))

(rf/reg-sub :route/params
  :<- [:route]
  (fn [r _] (:params r)))

(rf/reg-sub :articles       (fn [db _] (:articles db)))

(rf/reg-sub :article-by-id
  :<- [:articles]
  (fn [arts [_ id]] (first (filter #(= id (:id %)) arts))))

;; ============================================================================
;; LINK COMPONENT — anchor that dispatches :rf/url-requested on click
;; ============================================================================
;;
;; Registered under the canonical name :rf/route-link (per Spec 012). Click
;; dispatches :rf/url-requested — the runtime's classifier event that decides
;; internal-vs-external and dispatches :route/navigate for internal URLs.
;; That extra step is what lets the framework own the modifier-key passthrough
;; and external-link semantics for everyone uniformly.

(def route-link
  (rf/reg-view :rf/route-link
    (fn render-route-link [{:keys [to params]} & children]
      (let [url (rf/route-url to (or params {}))]
        [:a {:href     url
             :on-click (fn [e]
                         (when (and (zero? (.-button e))
                                    (not (.-metaKey e))
                                    (not (.-ctrlKey e))
                                    (not (.-shiftKey e)))
                           (.preventDefault e)
                           (dispatch [:rf/url-requested
                                      {:url url :to to :params (or params {})}])))}
         (into [:span] children)]))))

;; ============================================================================
;; PAGE VIEWS
;; ============================================================================

(def home-page
  (rf/reg-view :pages/home
    (fn render-home []
      [:div
       [:h1 "Welcome"]
       [:p [route-link {:to :route/articles} "See the articles →"]]])))

(def articles-page
  (rf/reg-view :pages/articles
    (fn render-articles []
      [:div
       [:h1 "Articles"]
       [:ul
        (for [{:keys [id title]} @(subscribe [:articles])]
          ^{:key id}
          [:li [route-link {:to :route/article-detail :params {:id id}} title]])]])))

(def article-detail-page
  (rf/reg-view :pages/article-detail
    (fn render-article-detail []
      (let [id      (:id @(subscribe [:route/params]))
            article @(subscribe [:article-by-id id])]
        (if article
          [:div
           [:h1 (:title article)]
           [:p (:body article)]
           [:p [route-link {:to :route/articles} "← Back"]]]
          [:div
           [:p "Article not found."]
           [:p [route-link {:to :route/articles} "← Back"]]])))))

(def not-found-page
  (rf/reg-view :pages/not-found
    (fn render-not-found []
      (let [url (:url @(subscribe [:route/params]))]
        [:div
         [:h1 "Not found"]
         [:p (str "No route matches: " url)]
         [:p [route-link {:to :route/home} "Home"]]]))))

(def root-view
  (rf/reg-view :app/root
    (fn render-root []
      (case @(subscribe [:route/id])
        :route/home            [home-page]
        :route/articles        [articles-page]
        :route/article-detail  [article-detail-page]
        [not-found-page]))))

;; ============================================================================
;; ROUTER WIRING
;; ============================================================================

(defn install-router! []
  (.addEventListener js/window "popstate"
    (fn [_]
      (rf/dispatch [:route/handle-url-change (.. js/window -location -pathname)])))
  ;; Initial-load: read the URL and seed the route slice.
  (rf/dispatch-sync [:route/handle-url-change (.. js/window -location -pathname)]))

;; ============================================================================
;; HEADLESS TESTS
;; ============================================================================

(defn routing-tests []
  (rf/with-frame [f (rf/make-frame {:on-create [:app/initialise]})]
    ;; Navigate to articles list.
    (rf/dispatch-sync [:route/navigate :route/articles] {:frame f})
    (assert (= :route/articles (rf/compute-sub [:route/id] @(rf/get-frame-db f))))

    ;; Navigate to article detail with params.
    (rf/dispatch-sync [:route/navigate :route/article-detail {:id "intro"}] {:frame f})
    (assert (= :route/article-detail (rf/compute-sub [:route/id] @(rf/get-frame-db f))))
    (assert (= "intro" (:id (rf/compute-sub [:route/params] @(rf/get-frame-db f)))))

    ;; URL change (popstate-style) → resolves via match-url.
    (rf/dispatch-sync [:route/handle-url-change "/articles/ssr"] {:frame f})
    (assert (= :route/article-detail (rf/compute-sub [:route/id] @(rf/get-frame-db f))))

    ;; Unknown URL → not-found.
    (rf/dispatch-sync [:route/handle-url-change "/garbage"] {:frame f})
    (assert (= :route/not-found (rf/compute-sub [:route/id] @(rf/get-frame-db f))))))

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/dispatch-sync [:app/initialise])
  (install-router!)
  (rdc/render root [root-view]))
