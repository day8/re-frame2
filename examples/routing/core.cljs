(ns routing.core
  "Worked example for [Construction Prompt CP-7](../../Construction-Prompts.md)
   and [EP 012 Routing](../../012-Routing.md). A small three-page app:
   home, articles list, article detail. Demonstrates URL ↔ frame state,
   navigation as event, route as sub, and route-aware root-view dispatch.

   Per [reorient.md](../../reorient.md): routing is *state plus events*, not
   a separate subsystem. The URL is a derivable view of `app-db`; navigation
   is an event. The same `:route/handle-url-change` handler runs server- and
   client-side for SSR.

   Demonstrates:
   - reg-route                              — routes as registry entries
   - :route/navigate                        — navigation as event
   - :route/handle-url-change                — popstate / initial-load handler
   - :route, :route/id, :route/params        — route subs
   - case-on-:route/id at the root          — page dispatch
   - :nav/push-url fx (client-only)          — browser history push"
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

(rf/reg-app-schema [:route]
  [:map
   [:id     :keyword]
   [:params {:default {}} :map]])

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event-db :app/initialise
  (fn [_ _]
    {:route    {:id :route/home :params {}}
     :articles [{:id "intro" :title "Intro to re-frame2"  :body "..."}
                {:id "ssr"   :title "Server rendering"   :body "..."}]}))

(rf/reg-event-fx :route/navigate
  {:doc  "Navigate to a registered route."
   :spec [:cat [:= :route/navigate] :keyword [:? :map]]}
  (fn handler-route-navigate [{:keys [db]} [_ route-id params]]
    (let [params (or params {})
          url    (rf/route-url route-id params)]
      {:db (assoc db :route {:id route-id :params params})
       :fx [[:nav/push-url url]]})))

(rf/reg-event-db :route/handle-url-change
  {:doc       "Triggered on browser back/forward and on initial page load.
               Same handler runs server-side during SSR."
   :platforms #{:client :server}}
  (fn handler-route-handle-url-change [db [_ url]]
    (let [m (rf/match-url url)]
      (assoc db :route
             (if m
               {:id (:route-id m) :params (:params m)}
               {:id :route/not-found :params {:url url}})))))

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
;; LINK COMPONENT — anchor that dispatches navigate on click
;; ============================================================================

(def route-link
  (rf/reg-view :route/link
    (fn render-route-link [{:keys [to params]} & children]
      (let [url (rf/route-url to (or params {}))]
        [:a {:href     url
             :on-click (fn [e]
                         (.preventDefault e)
                         (dispatch [:route/navigate to (or params {})]))}
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
